package com.elioo.baymax.wa.application.service;

import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.in.DocumentIntakeUseCase;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Turns an inbound WhatsApp image into a document on the family's timeline (BMX-10 phase 1).
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
    private final DocumentIntakeUseCase intake;
    private final WaMessagingPort graph;
    private final Copy copy;
    private final BaymaxProperties properties;

    /** Why an inbound image did not become a document; each carries the reply the family receives. */
    public enum Outcome {
        ACCEPTED, UNKNOWN_SENDER, AMBIGUOUS_PATIENT, FREE_TIER, FAILED
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
                    log.error("[baymax] wa intake failed mediaId={} : {}", mediaId, e.toString());
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

    /** A text message: acknowledged and logged, nothing more in phase 1. */
    public Mono<Outcome> onText(String from) {
        String number = e164(from);
        return records.findFamilyByWhatsapp(number)
                .map(family -> Outcome.ACCEPTED)
                .switchIfEmpty(Mono.defer(() -> reply(number, "wa.unknown", Outcome.UNKNOWN_SENDER,
                        "text from unknown sender")));
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
                    log.error("[baymax] wa outbound failed: {}", e.toString());
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
