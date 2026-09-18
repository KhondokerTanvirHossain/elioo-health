package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.aicall.application.service.MeteredLlmClient;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration;
import com.elioo.baymax.nudge.domain.NudgeCandidate;
import com.elioo.baymax.outbound.application.port.in.ComposeNudgeUseCase;
import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.application.port.out.ReviewerNotificationPort;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.healthcare.llm.model.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A nudge through the BMX-6 path (BMX-8): a fixed skeleton from {@code nudge.<rule>} carrying the stored strings,
 * one EXPLAIN-style model call to phrase it (purpose NUDGE, so cost per nudge is a query away), the same
 * checklist with the candidate's own numbers as the closed set, the review gate — every nudge PENDING while
 * {@code gate-nudges} is on (DR-18) — and the same release. The urgency comes in as {@code NudgeUrgency}: NOW
 * cannot be expressed, so it cannot be stored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NudgeComposer implements ComposeNudgeUseCase {

    /** Template variables whose stored text must appear in the body character for character. */
    static final List<String> VERBATIM_KEYS = List.of("values", "changes", "instruction", "marker", "medicine", "dose_text", "frequency_text", "timing_text", "duration_text");

    private final OutboundMessagePort messages;
    private final MeteredLlmClient metered;
    private final ExtractionModelConfiguration.ExtractionClients clients;
    private final MessageSafetyCheck safety;
    private final OutboundMessageGate gate;
    private final ReviewerNotificationPort reviewer;
    private final MessageDeliveryPort delivery;
    private final Copy copy;
    private final BaymaxProperties properties;
    private final Clock clock;

    @Override
    public Mono<OutboundMessage> compose(NudgeCandidate c, String optOutLink) {
        Urgency urgency = c.urgency().toUrgency();
        Map<String, String> vars = new LinkedHashMap<>(c.vars());
        vars.put("optout_link", optOutLink);
        String optout = copy.bn("nudge.optout", vars);
        String skeleton = copy.bn("nudge." + c.rule().dbValue(), vars);
        Set<String> allowed = new HashSet<>(c.numbers());
        // the cap is on what the family receives (PO ruling 2026-09-19): the model's budget is the cap minus the opt-out line
        int budget = properties.getOutbound().getMaxChars() - optout.length() - 1;
        // the stored strings the message must carry word for word: values, changes, instructions, medicine names
        List<String> verbatim = new java.util.ArrayList<>();
        for (String key : VERBATIM_KEYS) {
            String v = c.vars().get(key);
            if (v != null && v.trim().length() >= 2) {
                verbatim.add(v.trim());
            }
        }
        OutboundMessage.GateStatus status = properties.getOutbound().isGateNudges() ? OutboundMessage.GateStatus.PENDING : gate.decide(urgency);

        return generate(c, skeleton, budget, null)
                .flatMap(first -> {
                    List<String> v = violations(first, budget, allowed, optOutLink, urgency, verbatim);
                    if (v.isEmpty()) {
                        return Mono.just(first);
                    }
                    log.info("[baymax] nudge rejected once rule={} patientId={} violations={}", c.rule(), c.patientId(), v);
                    return generate(c, skeleton, budget, v).filter(second -> {
                        List<String> again = violations(second, budget, allowed, optOutLink, urgency, verbatim);
                        if (!again.isEmpty()) {
                            log.warn("[baymax] nudge failed closed rule={} patientId={} violations={}", c.rule(), c.patientId(), again);
                        }
                        return again.isEmpty();
                    });
                })
                .map(text -> {
                    // the opt-out line is fixed copy appended after the checklist; every nudge carries it (DR-18)
                    String body = text + "\n" + optout;
                    if (body.length() > properties.getOutbound().getMaxChars()) {
                        throw new IllegalStateException("nudge body over the cap with the opt-out line: " + body.length());
                    }
                    return new OutboundMessage(null, c.familyId(), c.patientId(), null, OutboundMessage.Kind.NUDGE, urgency,
                            List.of("nudge:" + c.rule().dbValue()), body, status, null, null, null, null, clock.instant());
                })
                .switchIfEmpty(Mono.fromSupplier(() -> new OutboundMessage(null, c.familyId(), c.patientId(), null, OutboundMessage.Kind.NUDGE,
                        urgency, List.of("nudge:" + c.rule().dbValue()), null, OutboundMessage.GateStatus.FAILED_SAFETY, null, null, null, null, clock.instant())))
                .flatMap(m -> {
                    if (m.urgency() == Urgency.NOW) {
                        return Mono.error(new IllegalStateException("a nudge can never be NOW"));
                    }
                    return m.gateStatus() == OutboundMessage.GateStatus.FAILED_SAFETY ? messages.save(m) : release(m);
                });
    }

    private Mono<OutboundMessage> release(OutboundMessage message) {
        if (message.gateStatus() == OutboundMessage.GateStatus.PENDING) {
            return messages.save(message).flatMap(saved -> reviewer.notifyPending(saved).thenReturn(saved));
        }
        Instant now = clock.instant();
        OutboundMessage sent = new OutboundMessage(message.id(), message.familyId(), message.patientId(), message.documentId(),
                message.kind(), message.urgency(), message.urgencyReasons(), message.body(), OutboundMessage.GateStatus.RELEASED,
                null, null, null, now, message.createdAt());
        return messages.save(sent).flatMap(saved -> delivery.deliver(saved).thenReturn(saved));
    }

    /** One model call: phrase the skeleton as plain Bangla, changing no number and no quoted string. */
    /** The checklist, with the length check on the model's budget rather than the whole cap. */
    private List<String> violations(String text, int budget, Set<String> allowed, String link, Urgency urgency, List<String> verbatim) {
        List<String> v = new java.util.ArrayList<>(safety.violations(text, allowed, link, urgency, verbatim));
        v.removeIf(x -> x.startsWith("too_long:"));
        if (text != null && text.length() > budget) {
            v.add("too_long:" + text.length() + ">" + budget);
        }
        return v;
    }

    private Mono<String> generate(NudgeCandidate c, String skeleton, int budget, List<String> priorViolations) {
        String system = """
                You write short Bangla messages for a family about their relative's stored health records. You are given a \
                SKELETON: it is already correct and already contains every number, every date and every quoted string. \
                Your job is only to phrase it as natural, plain Bangla a non-medical person understands, in 3-5 short lines.
                Rules, all absolute:
                - Keep every number and date exactly as in the skeleton, in the same script. Add no number.
                - Keep every quoted string and every line that lists values or medicines word for word.
                - Never say what a value means, never name a disease or a cause, never mention what a medicine is for, \
                never tell anyone to take, stop, start or change anything.
                - No advice beyond "see a doctor" as the skeleton phrases it. No reassurance beyond the skeleton.
                - Under %d characters. Output the message text only: no title, no quotes, no notes.
                """.formatted(budget);
        StringBuilder user = new StringBuilder("SKELETON:\n").append(skeleton);
        if (priorViolations != null) {
            user.append("\n\nYour previous attempt was rejected for: ").append(String.join(", ", priorViolations))
                    .append(". Fix exactly those; change nothing else.");
        }
        LlmRequest request = LlmRequest.custom(user.toString(), system, null, 400, null);
        return metered.using(clients.cheap()).invoke(AiCallPurpose.NUDGE, null, request)
                .map(r -> r.content() == null ? "" : r.content().trim());
    }
}
