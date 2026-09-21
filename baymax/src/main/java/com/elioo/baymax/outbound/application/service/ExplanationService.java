package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.aicall.application.service.MeteredLlmClient;
import com.elioo.baymax.aicall.domain.AiCallPurpose;
import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.config.ExtractionModelConfiguration;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.application.port.out.ReviewerNotificationPort;
import com.elioo.baymax.outbound.domain.DeliveryOutcome;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import com.elioo.healthcare.llm.model.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The family's message for a finished document (BMX-6). The skeleton comes from {@link Copy} — the urgency
 * line, the doctor-first line and the standout lines are fixed text with the extraction's own numbers —
 * and the model is asked only to phrase it as plain Bangla within that skeleton. Then the checklist runs in
 * code: numbers ⊆ extraction, no medicine/dose verbs, doctor-first on anything urgent, ≤ 600 characters.
 * One regeneration with the violations named, then fail closed: a row with no body, and a log line.
 *
 * <p>Urgency is assessed once, before composition, and asserted afterwards: the stored message carries the
 * urgency it was assessed at, or nothing is stored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExplanationService implements ExplainDocumentUseCase {

    private final DocumentRecordPort documents;
    private final HealthRecordPort records;
    private final OutboundMessagePort messages;
    private final MeteredLlmClient metered;
    private final ExtractionModelConfiguration.ExtractionClients clients;
    private final UrgencyService urgency;
    private final MessageSafetyCheck safety;
    private final OutboundMessageGate gate;
    private final ReviewerNotificationPort reviewer;
    private final MessageDeliveryPort delivery;
    private final Copy copy;
    private final BaymaxProperties properties;
    private final Clock clock;

    @Override
    public Mono<OutboundMessage> explain(UUID documentId) {
        return compose(documentId, false);
    }

    @Override
    public Mono<OutboundMessage> detail(UUID documentId) {
        return compose(documentId, true);
    }

    private Mono<OutboundMessage> compose(UUID documentId, boolean detail) {
        return documents.find(documentId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("document_not_found", "no document with id " + documentId)))
                .flatMap(document -> switch (document.status()) {
                    case NEEDS_RETAKE -> retake(document);
                    case DONE -> facts(document).flatMap(facts -> documents.medicinesOf(document.id()).collectList()
                            .flatMap(medicines -> explanation(facts, medicines, detail)));
                    default -> Mono.empty();
                });
    }

    /**
     * NEEDS_RETAKE: the retake prompt and nothing else — no facts, no model, no explanation. The copy escalates
     * with the family's consecutive retakes: the first asks for another photo, the second says exactly what to
     * change, the third offers help instead of a third attempt (a family whose first photos bounce does not
     * send a third — PO, after the first real walkthrough).
     */
    private Mono<OutboundMessage> retake(Document document) {
        return messages.consecutiveRetakes(document.familyId()).defaultIfEmpty(0L).flatMap(before -> {
            String key = before >= 2 ? "retake.third" : before == 1 ? "retake.second" : "retake";
            String body = copy.bn(key, Map.of());
            return release(new OutboundMessage(null, document.familyId(), document.patientId(), document.id(),
                    OutboundMessage.Kind.RETAKE, Urgency.ROUTINE, List.of("retake_" + (before + 1)), body,
                    gate.decide(Urgency.ROUTINE), null, null, null, null, clock.instant()));
        });
    }

    private Mono<DocumentFacts> facts(Document document) {
        String link = properties.getOutbound().getPublicBaseUrl() + "/app/documents/" + document.id();
        return Mono.zip(
                documents.observationsOf(document.id()).collectList(),
                documents.followUpsOf(document.id()).collectList(),
                documents.clinicalContextOf(document.id()).defaultIfEmpty(Map.of()),
                records.findPatient(document.patientId()).map(p -> p.name()).defaultIfEmpty("")
        ).map(t -> new DocumentFacts(document, t.getT4(), t.getT1(), t.getT2(), t.getT3(), link));
    }

    /**
     * @param medicines the document's stored medicines (DR-16): never in the skeleton, never in the prompt; after
     *                  the model's text has passed the checklist they are appended verbatim, then verified
     *                  character for character against the store
     */
    private Mono<OutboundMessage> explanation(DocumentFacts facts, List<Map<String, Object>> medicines, boolean detail) {
        UrgencyAssessment assessed = urgency.assess(facts);
        String skeleton = skeleton(facts, assessed, detail);
        OutboundMessage.Kind kind = detail ? OutboundMessage.Kind.DETAIL : OutboundMessage.Kind.EXPLANATION;

        // nothing stands out and nothing is urgent: the template is the whole message, the model adds nothing
        boolean templateOnly = !detail && assessed.level() == Urgency.ROUTINE;
        Mono<String> body = templateOnly ? Mono.just(skeleton) : generate(facts, assessed, skeleton, null)
                .flatMap(first -> {
                    List<String> violations = safety.violations(first, facts, assessed.level());
                    if (violations.isEmpty()) {
                        return Mono.just(first);
                    }
                    log.info("[baymax] explanation rejected once documentId={} violations={}", facts.document().id(), violations);
                    return generate(facts, assessed, skeleton, violations).flatMap(second -> {
                        List<String> again = safety.violations(second, facts, assessed.level());
                        if (again.isEmpty()) {
                            return Mono.just(second);
                        }
                        log.warn("[baymax] explanation failed closed documentId={} violations={}", facts.document().id(), again);
                        return Mono.empty();
                    });
                });

        return body
                .map(text -> {
                    List<String> finalCheck = safety.violations(text, facts, assessed.level());
                    if (!finalCheck.isEmpty()) {
                        throw new IllegalStateException("template violated its own checklist: " + finalCheck);
                    }
                    // DR-16: the verbatim medicine block goes on after the checklist has passed on the model's text —
                    // it is exempt from the phrase and number checks (it IS the extraction) and must match the store
                    // An ellipsis in a dosing instruction is a safety defect, not a formatting one: it stood
                    // in for "৩০ দিন। তারপর" and turned a two-phase regimen into two simultaneous doses.
                    List<String> elided = MedicineTranscription.elided(medicines);
                    if (!elided.isEmpty()) {
                        throw new IllegalStateException("medicine instruction is abbreviated, not transcribed: " + elided);
                    }
                    String block = MedicineTranscription.block(copy.bn("medicines.header", Map.of()), medicines);
                    String full = block.isEmpty() ? text : text + "\n\n" + block;
                    List<String> missing = MedicineTranscription.verify(full, medicines);
                    if (!missing.isEmpty()) {
                        throw new IllegalStateException("medicine transcription is not verbatim: " + missing);
                    }
                    return new OutboundMessage(null, facts.document().familyId(), facts.document().patientId(), facts.document().id(),
                            kind, assessed.level(), assessed.reasons(), full, gate.decide(assessed.level()),
                            null, null, null, null, clock.instant());
                })
                .switchIfEmpty(Mono.fromSupplier(() -> new OutboundMessage(null, facts.document().familyId(), facts.document().patientId(),
                        facts.document().id(), kind, assessed.level(), assessed.reasons(), null,
                        OutboundMessage.GateStatus.FAILED_SAFETY, null, null, null, null, clock.instant())))
                .flatMap(message -> {
                    // urgency is never lowered by any later stage: the row must carry what was assessed
                    if (message.urgency() != assessed.level()) {
                        return Mono.error(new IllegalStateException("urgency changed after assessment"));
                    }
                    return message.gateStatus() == OutboundMessage.GateStatus.FAILED_SAFETY ? messages.save(message) : release(message);
                });
    }

    /** Persist, then either park it with the reviewer or hand it to delivery. Nothing PENDING is delivered. */
    private Mono<OutboundMessage> release(OutboundMessage message) {
        if (message.gateStatus() == OutboundMessage.GateStatus.PENDING) {
            return messages.save(message).flatMap(saved -> reviewer.notifyPending(saved).thenReturn(saved));
        }
        Instant now = clock.instant();
        // sentAt stays NULL here: V13 says only a provider acceptance sets it, and nothing has been attempted
        // yet. Setting it at save time was the same lie approve() used to tell — a row claiming delivery of a
        // message that may never arrive.
        OutboundMessage toSend = new OutboundMessage(message.id(), message.familyId(), message.patientId(), message.documentId(),
                message.kind(), message.urgency(), message.urgencyReasons(), message.body(), OutboundMessage.GateStatus.RELEASED,
                null, null, null, null, message.createdAt());
        return messages.save(toSend).flatMap(saved -> delivery.deliver(saved)
                .onErrorResume(e -> {
                    log.error("[baymax] released message delivery threw id={} : {}", saved.id(), e.toString());
                    return Mono.just(DeliveryOutcome.failed(DeliveryOutcome.SEND_FAILED));
                })
                .flatMap(outcome -> outcome.status() == null
                        // the log adapter delivered nothing; keep v1 behaviour and stamp sent_at
                        ? messages.markSent(saved.id(), now).then(messages.find(saved.id()))
                        : messages.recordDelivery(saved.id(), outcome, now)));
    }

    /**
     * May the ROUTINE message say "everything is within the normal range"?
     *
     * <p>Only when the document actually carries values with printed ranges and every one of them is inside.
     * The claim is about the document, not about the patient, and a document with no printed ranges cannot
     * support it. On 2026-09-21 a prescription recording near-blackout, hallucinations and a new Parkinson's
     * diagnosis was sent with that line, because the ROUTINE template asserted it unconditionally — the copy
     * had been written for lab reports and nothing checked that the document was one.
     *
     * <p>ROUTINE itself is unchanged and correct: a diagnosis on a prescription is not a reason to see a
     * doctor, because the doctor just wrote it (PO ruling 2026-09-18). What was wrong was the reassurance.
     */
    static boolean canSayWithinNormalRange(DocumentFacts facts) {
        List<Map<String, Object>> values = facts.values();
        if (values == null || values.isEmpty()) {
            return false;
        }
        boolean anyWithRange = false;
        for (Map<String, Object> value : values) {
            boolean hasLow = value.get("ref_low") != null && !String.valueOf(value.get("ref_low")).isBlank();
            boolean hasHigh = value.get("ref_high") != null && !String.valueOf(value.get("ref_high")).isBlank();
            if (!hasLow && !hasHigh) {
                // a value we cannot place against a printed range: the claim would cover it without evidence
                return false;
            }
            anyWithRange = true;
            String flag = value.get("flag") == null ? "" : String.valueOf(value.get("flag"));
            if (!flag.isBlank() && !"normal".equalsIgnoreCase(flag)) {
                return false;
            }
            if (Boolean.TRUE.equals(value.get("critical"))) {
                return false;
            }
        }
        return anyWithRange;
    }

    /** The fixed skeleton: template lines with the extraction's own numbers. */
    String skeleton(DocumentFacts facts, UrgencyAssessment assessed, boolean detail) {
        Document d = facts.document();
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("document_type", copy.documentType(d.documentType()));
        // dates a family reads are Bangla day-and-month, never ISO (PO ruling 2026-09-19, every outbound message)
        vars.put("date", d.docDate() == null ? "" : BanglaDate.format(d.docDate(), LocalDate.now(clock)));
        vars.put("patient", facts.patientName());
        vars.put("link", facts.link());
        vars.put("standout", standout(facts, assessed));
        if (detail) {
            StringBuilder b = new StringBuilder(copy.bn("detail.header", vars)).append('\n');
            for (Map<String, Object> v : facts.values()) {
                b.append(line(v, kindOf(v, facts), v)).append('\n');
            }
            for (Map<String, Object> f : facts.followUps()) {
                if (f.get("due_date") != null) {
                    b.append(copy.bn("detail.follow_up", Map.of("date", BanglaDate.format(String.valueOf(f.get("due_date")), LocalDate.now(clock))))).append('\n');
                }
            }
            if (assessed.level() == Urgency.NOW) {
                b.append(copy.bn("urgency.now", Map.of())).append('\n');
            } else if (assessed.level() == Urgency.THIS_WEEK) {
                b.append(copy.bn("urgency.this_week", Map.of())).append('\n');
            }
            b.append(copy.bn("detail.footer", vars));
            return b.toString().trim();
        }
        return switch (assessed.level()) {
            case NOW -> copy.bn("explanation.now", vars);
            case THIS_WEEK -> copy.bn("explanation.this_week", vars);
            case ROUTINE -> copy.bn(canSayWithinNormalRange(facts)
                    ? "explanation.routine" : "explanation.routine.filed", vars);
        };
    }

    private String standout(DocumentFacts facts, UrgencyAssessment assessed) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> v : facts.values()) {
            String kind = kindOf(v, facts);
            if (kind != null) {
                lines.add(line(v, kind, v));
            }
        }
        if (lines.isEmpty() && assessed.level() == Urgency.THIS_WEEK) {
            lines.add(copy.bn("line.this_week_no_value", Map.of()));
        }
        return String.join("\n", lines);
    }

    /** Which standout line a value earns, under the same STOPGAP rule urgency uses; null inside the range or without one. */
    private String kindOf(Map<String, Object> v, DocumentFacts facts) {
        String text = facts.document().extractionJson() == null ? "" : facts.document().extractionJson();
        return switch (urgency.criticalKind(v, text)) {
            case CRITICAL_HIGH -> "line.value_critical_high";
            case CRITICAL_LOW -> "line.value_critical_low";
            case OUTSIDE -> "line.value_outside_range";
            case NONE -> null;
        };
    }

    private String line(Map<String, Object> v, String key, Map<String, Object> vars) {
        if (key == null) {
            key = "line.value_outside_range";
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("value_name", str(vars.get("name")));
        m.put("value", str(vars.get("value")));
        m.put("unit", str(vars.get("unit")));
        m.put("ref_low", str(vars.get("ref_low")));
        m.put("ref_high", str(vars.get("ref_high")));
        return copy.bn(key, m);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** One model call: phrase the skeleton as plain Bangla, changing no number and no fixed line. */
    private Mono<String> generate(DocumentFacts facts, UrgencyAssessment assessed, String skeleton, List<String> priorViolations) {
        String system = """
                You write short Bangla messages for a family about one medical document. You are given a SKELETON: \
                it is already correct and already contains every number and every fixed line. Your job is only to \
                phrase it as natural, plain Bangla a non-medical person understands, keeping it to 4-6 short lines.
                Rules, all absolute:
                - Keep every number exactly as it appears in the skeleton, in the same script. Add no number.
                - Keep the URGENCY LINE and the "সাথে নিয়ে যাবেন" line word for word. Keep the link unchanged.
                - Never say what a result means clinically. Never name a disease from a number. Never mention a \
                medicine, a tablet, a dose, or tell anyone to take, stop, start or change anything.
                - No advice beyond "see a doctor" as the skeleton phrases it. No reassurance beyond the skeleton.
                - Under %d characters in total. Output the message text only: no title, no quotes, no notes.
                """.formatted(properties.getOutbound().getMaxChars());
        StringBuilder user = new StringBuilder("SKELETON:\n").append(skeleton).append("\n\nURGENCY LINE: ")
                .append(assessed.level() == Urgency.NOW ? copy.bn("urgency.now", Map.of())
                        : assessed.level() == Urgency.THIS_WEEK ? copy.bn("urgency.this_week", Map.of()) : "(none)");
        if (priorViolations != null) {
            user.append("\n\nYour previous attempt was rejected for: ").append(String.join(", ", priorViolations))
                    .append(". Fix exactly those; change nothing else.");
        }
        LlmRequest request = LlmRequest.custom(user.toString(), system, null, 400, null);
        return metered.using(clients.cheap()).invoke(AiCallPurpose.EXPLAIN, facts.document().id(), request)
                .map(r -> r.content() == null ? "" : r.content().trim());
    }
}
