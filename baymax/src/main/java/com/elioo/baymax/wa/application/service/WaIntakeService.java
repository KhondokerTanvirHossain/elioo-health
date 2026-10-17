package com.elioo.baymax.wa.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.outbound.application.service.UrgencyLines;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Answers every inbound WhatsApp message exactly once (Phase 0) and turns an inbound image or PDF into a document on
 * the family's timeline (BMX-10 phase 1). Text is routed by {@link WaTextRouter}, whole message only, with zero model
 * calls: greeting, the detail words, thanks, and an honest fallback that carries the red-flag line.
 *
 * <p>Resolution is sender → family → patient. Every branch that cannot reach {@code accept} answers the family
 * on WhatsApp rather than only the log: <b>a photo that vanishes in silence is the worst outcome of the
 * three</b>, worse than a refusal that says why.
 *
 * <p><b>Ambiguity is refused, never guessed.</b> §1.4 says one number carries many patients and every message
 * must be able to ask "Ma or Baba?"; phase 1 has no such conversation, so a family with more than one patient
 * gets a reply saying Medioo cannot tell whose report this is. Picking the first would misfile one parent's
 * report under the other, and the timeline, the trend series and every nudge built on it would then be wrong
 * and internally consistent — the failure mode this repository keeps finding.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "baymax.wa", name = "enabled", havingValue = "true")
public class WaIntakeService {

    private final HealthRecordPort records;
    private final com.elioo.baymax.extraction.application.port.out.DocumentRecordPort documents;
    private final com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase explainer;
    private final DocumentIntakeUseCase intake;
    private final WaMessagingPort graph;
    private final Copy copy;
    private final BaymaxProperties properties;

    /** Why an inbound image did not become a document; each carries the reply the family receives. */
    public enum Outcome {
        ACCEPTED, UNKNOWN_SENDER, AMBIGUOUS_PATIENT, FREE_TIER, FAILED,
        /** A fixed-copy reply was sent (greeting, thanks, fallback, voice, video, no document yet). */
        REPLIED,
        /** Nothing the family sent, so nothing to answer: a Meta system notice, or a reaction being removed. */
        IGNORED
    }

    /**
     * The one entry point from the webhook. Interim (plan PR 6 task 6.3): it keeps today's behaviour for text, image and
     * document, so this commit regresses nothing; task 6.5 answers every other type.
     */
    public Mono<Outcome> onInbound(com.elioo.baymax.wa.domain.WaInbound in) {
        return switch (in.type()) {
            case TEXT -> onText(in.from(), in.text());
            case IMAGE, DOCUMENT -> onImage(in.from(), in.mediaId());
            default -> Mono.just(Outcome.ACCEPTED);
        };
    }

    /**
     * @param from   the sender's WhatsApp number as Meta reports it, digits only and no plus
     * @param mediaId the image to fetch
     */
    public Mono<Outcome> onImage(String from, String mediaId) {
        String number = e164(from);
        return records.findFamilyByWhatsapp(number)
                .flatMap(family -> records.patientsOf(family.id()).collectList()
                        .flatMap(patients -> withPatients(number, mediaId, patients)))
                // defer: switchIfEmpty evaluates its argument at assembly time, so an eager reply(...) here
                // sent the unknown-sender message on EVERY inbound image, known families included
                .switchIfEmpty(Mono.defer(() -> reply(number, "wa.unknown", Outcome.UNKNOWN_SENDER,
                        "sender has no family account")))
                .onErrorResume(FreeTierExceededException.class, e ->
                        reply(number, "wa.free_tier", Outcome.FREE_TIER, "free tier exhausted"))
                .onErrorResume(e -> {
                    // e.toString() of a WebClientResponseException is "404 … from GET <signed media URL>"
                    log.error("[baymax] wa intake failed mediaId={} error={}", mediaId,
                            e instanceof BaymaxException b ? "BaymaxException:" + b.reason() : e.getClass().getSimpleName());
                    return Mono.just(Outcome.FAILED);
                });
    }

    private Mono<Outcome> withPatients(String number, String mediaId, List<PatientProfile> patients) {
        if (patients.isEmpty()) {
            return reply(number, "wa.unknown", Outcome.UNKNOWN_SENDER, "family has no patient yet");
        }
        if (patients.size() > 1) {
            // §1.4 disambiguation is a later phase; refusing is the only honest answer phase 1 has
            log.warn("[baymax] wa intake refused: family has {} patients and phase 1 cannot ask which one", patients.size());
            return reply(number, "wa.ambiguous", Outcome.AMBIGUOUS_PATIENT, "more than one patient",
                    Map.of("link", properties.getOutbound().getPublicBaseUrl() + "/app"));
        }
        PatientProfile patient = patients.get(0);
        return send(number, copy.bn("wa.received", Map.of()))
                .then(graph.downloadMedia(mediaId))
                .flatMap(bytes -> intake.accept(patient.id(), List.of(new Upload(mediaId + ".jpg", bytes))))
                .doOnNext(document -> log.info("[baymax] wa intake accepted documentId={} patientId={} bytes>0",
                        document.id(), patient.id()))
                .thenReturn(Outcome.ACCEPTED);
    }

    /**
     * Text, a button tap's title, or the words of a quick reply. Every message gets exactly one reply, and none is
     * silent (Phase 0): before, anything but the literal বিস্তারিত/detail/bistarito was acknowledged in the log and
     * the family heard nothing. {@link WaTextRouter} matches the whole message; the route is logged, the text is not.
     *
     * <p>The body is delivered by the delivery port, never sent from here (F4): before F4 this method sent
     * {@code message.body()} itself after {@code detail()} had already delivered it, and under the urgency gate it
     * sent a PENDING body nobody had reviewed.
     */
    public Mono<Outcome> onText(String from, String text) {
        String number = e164(from);
        WaTextRouter.Route route = WaTextRouter.route(text);
        log.info("[baymax] wa text route={} chars={}", route, text == null ? 0 : text.length());
        return withFamily(number, family -> switch (route) {
            case DETAIL -> answerLatestDetail(number, family);
            case GREETING_HELLO, GREETING_SALAM, GREETING_NAMASKAR -> greet(number, route);
            case THANKS -> thanks(number, family);
            case FALLBACK -> replyWithRedFlag(number, "wa.fallback", "text not understood");
        }, "text");
    }

    /** The detail answer (F4): the latest DONE document's DETAIL row, answered by answerDetail. */
    private Mono<Outcome> answerLatestDetail(String number, FamilyAccount family) {
        return documents.latestDoneDocument(family.id())
                .flatMap(document -> explainer.detail(document.id()))
                .flatMap(message -> answerDetail(number, message))
                .switchIfEmpty(Mono.defer(() -> reply(number, "wa.no_document", Outcome.REPLIED,
                        "detail asked for but no readable document yet")));
    }

    /** Mirrors the family's greeting and never assumes religion (spec §7.4); zero model calls (W1). */
    private Mono<Outcome> greet(String number, WaTextRouter.Route route) {
        String opener = copy.bn(switch (route) {
            case GREETING_SALAM -> "wa.greeting.salam";
            case GREETING_NAMASKAR -> "wa.greeting.namaskar";
            default -> "wa.greeting.hello";
        }, Map.of());
        log.info("[baymax] wa reply key=wa.greeting route={}", route);
        return send(number, String.join("\n", opener, copy.bn("wa.greeting.intro", Map.of()),
                copy.bn("wa.greeting.none", Map.of())))
                .thenReturn(Outcome.REPLIED);
    }

    /** W12 without the standing urgency, which is plan PR 7: a one-line close plus the red-flag line. */
    private Mono<Outcome> thanks(String number, FamilyAccount family) {
        return replyWithRedFlag(number, "wa.thanks", "thanks");
    }

    private Mono<Outcome> withFamily(String number, Function<FamilyAccount, Mono<Outcome>> known, String what) {
        return records.findFamilyByWhatsapp(number)
                .flatMap(known)
                // defer: an eager reply(...) here would send the unknown-sender text to every known family too
                .switchIfEmpty(Mono.defer(() -> reply(number, "wa.unknown", Outcome.UNKNOWN_SENDER,
                        what + " from unknown sender")));
    }

    private Mono<Outcome> replyWithRedFlag(String number, String key, String why) {
        log.info("[baymax] wa reply key={} reason={}", key, why);
        return send(number, withRedFlag(copy.bn(key, Map.of()))).thenReturn(Outcome.REPLIED);
    }

    /** Phase 0: every fallback, thanks, voice, video and sticker reply carries the approved red-flag line, once. */
    private String withRedFlag(String body) {
        String line = copy.bn("safety.red_flag_line", Map.of());
        return body.contains(line) ? body : body + "\n" + line;
    }

    /**
     * F4: this method never sends a composed body. A released or approved row not yet delivered goes through
     * explainer.deliver, which claims the row (at most once), records the provider's outcome (V13/V14) and is refused by
     * the delivery adapter for anything unreviewed. Every other state gets fixed copy: PENDING the "being checked" line,
     * REJECTED and FAILED_SAFETY the paper link, an already-delivered detail a pointer to it. Urgent documents lead with
     * the UrgencyLines block (D5): the urgency is never held back for review.
     */
    private Mono<Outcome> answerDetail(String number, OutboundMessage m) {
        return switch (m.gateStatus()) {
            case RELEASED, APPROVED -> m.sentAt() == null
                    ? explainer.deliver(m)
                            .map(d -> d.sentAt() != null ? Outcome.ACCEPTED : Outcome.FAILED)
                            // the claim was lost: a concurrent ask is delivering this very row right now
                            .switchIfEmpty(Mono.defer(() -> fixed(number, m, "wa.detail.already_sent", "detail being delivered")))
                    : fixed(number, m, "wa.detail.already_sent", "detail already delivered");
            case PENDING -> fixed(number, m, "wa.detail.pending", "detail pending review");
            case REJECTED, FAILED_SAFETY -> fixed(number, m, "wa.detail.unavailable", "detail withheld");
        };
    }

    private Mono<Outcome> fixed(String number, OutboundMessage m, String key, String why) {
        String text = UrgencyLines.lead(copy, m.urgency(), copy.bn(key,
                Map.of("link", properties.getOutbound().getPublicBaseUrl() + "/app/documents/" + m.documentId())));
        log.info("[baymax] wa detail answered documentId={} gate={} urgency={} reason={} chars={}",
                m.documentId(), m.gateStatus().dbValue(), m.urgency().dbValue(), why, text.length());
        return send(number, text).thenReturn(Outcome.ACCEPTED);
    }

    private Mono<Outcome> reply(String number, String key, Outcome outcome, String why) {
        return reply(number, key, outcome, why, Map.of());
    }

    private Mono<Outcome> reply(String number, String key, Outcome outcome, String why, Map<String, String> vars) {
        log.info("[baymax] wa intake outcome={} reason={}", outcome, why);
        return send(number, copy.bn(key, vars)).thenReturn(outcome);
    }

    /**
     * Phase-1 allowlist, enforced in code rather than relying on the Meta test number's own restriction, which
     * disappears the moment we move to a production number. A refusal is logged, never silently dropped.
     */
    private Mono<Void> send(String number, String body) {
        List<String> allowlist = properties.getWa().getAllowlist();
        if (!allowlist.isEmpty() && allowlist.stream().noneMatch(a -> a.trim().equals(number))) {
            log.warn("[baymax] wa outbound refused: recipient is not on BAYMAX_WA_ALLOWLIST (phase 1)");
            return Mono.empty();
        }
        return graph.sendText(number, body)
                .onErrorResume(e -> {
                    // the reply failing must not lose the document that was already accepted
                    log.error("[baymax] wa outbound failed error={}", e.getClass().getSimpleName());
                    return Mono.empty();
                })
                .then();
    }

    /** Meta reports numbers without a plus; every stored number is E.164. */
    static String e164(String from) {
        if (from == null || from.isBlank()) {
            return "";
        }
        return from.startsWith("+") ? from : "+" + from;
    }
}
