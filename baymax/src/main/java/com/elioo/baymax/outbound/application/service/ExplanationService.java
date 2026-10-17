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
 *
 * <p>F4: composition, persistence and delivery are three steps. {@link #composeChecked} composes and decides the gate
 * status and touches nothing; {@link #release} stores (and parks a PENDING row with the reviewer); {@link #deliver} is
 * the only path from this service to {@link MessageDeliveryPort}, behind an atomic claim on the row, so a row reaches
 * a family at most once. DETAIL is cached per document while its urgency is current ({@link #stillCurrent}).
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
        return composeChecked(documentId, OutboundMessage.Kind.EXPLANATION).flatMap(this::release);
    }

    @Override
    public Mono<OutboundMessage> composeChecked(UUID documentId, OutboundMessage.Kind kind) {
        if (kind != OutboundMessage.Kind.EXPLANATION && kind != OutboundMessage.Kind.DETAIL) {
            return Mono.error(new IllegalArgumentException("composeChecked composes EXPLANATION or DETAIL, not " + kind));
        }
        return documents.find(documentId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("document_not_found", "no document with id " + documentId)))
                .flatMap(document -> switch (document.status()) {
                    // a detail request on a retake used to file ANOTHER retake row and escalate the next retake copy
                    case NEEDS_RETAKE -> kind == OutboundMessage.Kind.EXPLANATION ? retake(document) : Mono.<OutboundMessage>empty();
                    case DONE -> facts(document).flatMap(facts -> compose(facts, kind, urgency.assess(facts)));
                    default -> Mono.<OutboundMessage>empty();
                });
    }

    @Override
    public Mono<OutboundMessage> detail(UUID documentId) {
        return documents.find(documentId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("document_not_found", "no document with id " + documentId)))
                .filter(document -> document.status() == Document.Status.DONE)
                .flatMap(document -> facts(document).flatMap(facts -> {
                    UrgencyAssessment assessed = urgency.assess(facts);
                    return messages.latestOfKind(documentId, OutboundMessage.Kind.DETAIL)
                            .filter(cached -> stillCurrent(cached, assessed))
                            .doOnNext(cached -> log.info("[baymax] detail cache hit documentId={} messageId={} gate={}",
                                    documentId, cached.id(), cached.gateStatus().dbValue()))
                            .switchIfEmpty(Mono.defer(() -> compose(facts, OutboundMessage.Kind.DETAIL, assessed)
                                    .flatMap(this::store)));
                }));
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
            // composed only: release() stores and delivers it, so composeChecked stays free of side effects
            return Mono.just(new OutboundMessage(null, document.familyId(), document.patientId(), document.id(),
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
     * Composes one EXPLANATION or DETAIL from the facts at the urgency already assessed. Loads the document's stored
     * medicines (DR-16): never in the skeleton, never in the prompt; after the model's text has passed the checklist
     * they are appended verbatim, then verified character for character against the store. Saves nothing.
     */
    private Mono<OutboundMessage> compose(DocumentFacts facts, OutboundMessage.Kind kind, UrgencyAssessment assessed) {
        return documents.medicinesOf(facts.document().id()).collectList()
                .flatMap(medicines -> compose(facts, kind, assessed, medicines));
    }

    private Mono<OutboundMessage> compose(DocumentFacts facts, OutboundMessage.Kind kind, UrgencyAssessment assessed,
                                          List<Map<String, Object>> medicines) {
        boolean detail = kind == OutboundMessage.Kind.DETAIL;
        String skeleton = skeleton(facts, assessed, detail);

        // nothing stands out and nothing is urgent: the template is the whole message, the model adds nothing
        boolean templateOnly = !detail && assessed.level() == Urgency.ROUTINE;
        Mono<String> body = templateOnly ? Mono.just(skeleton) : generate(facts, assessed, skeleton, null)
                .flatMap(first -> {
                    List<String> violations = safety.violations(first, facts, assessed.level());
                    if (violations.isEmpty()) {
                        return Mono.just(first);
                    }
                    log.info("[baymax] explanation rejected once documentId={} violations={}", facts.document().id(),
                            MessageSafetyCheck.forLog(violations));
                    log.debug("[baymax] explanation rejected once detail documentId={} violations={}", facts.document().id(), violations);
                    return generate(facts, assessed, skeleton, violations).flatMap(second -> {
                        List<String> again = safety.violations(second, facts, assessed.level());
                        if (again.isEmpty()) {
                            return Mono.just(second);
                        }
                        log.warn("[baymax] explanation failed closed documentId={} violations={}", facts.document().id(),
                                MessageSafetyCheck.forLog(again));
                        log.debug("[baymax] explanation failed closed detail documentId={} violations={}", facts.document().id(), again);
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
                    // DR-26: the doctor's own diagnosis lines, verbatim, inserted after generation like the
                    // medicines — never explained, never paraphrased, never passed to the model. The family's
                    // first question is whether anything is wrong and the doctor has written the answer;
                    // listing the medicines while omitting the diagnosis they treat is the silent failure.
                    String diagnosis = DiagnosisTranscription.block(copy.bn("diagnosis.header", Map.of()), facts);
                    String head = diagnosis.isEmpty() ? text : text + "\n\n" + diagnosis;
                    String block = MedicineTranscription.block(copy.bn("medicines.header", Map.of()), medicines);
                    String full = block.isEmpty() ? head : head + "\n\n" + block;
                    List<String> missing = MedicineTranscription.verify(full, medicines);
                    if (!missing.isEmpty()) {
                        throw new IllegalStateException("medicine transcription is not verbatim: " + missing);
                    }
                    List<String> dxMissing = DiagnosisTranscription.verify(full, facts);
                    if (!dxMissing.isEmpty()) {
                        throw new IllegalStateException("diagnosis transcription is not verbatim: " + dxMissing);
                    }
                    // The cap is asserted on what is actually SENT, after every insertion — it used to run on
                    // the model's text while the longest part was appended afterwards, so it never covered the
                    // medicine block at all (thirteenth flattering failure: a check that does not cover what it
                    // claims to). Overflow splits into further messages; nothing is truncated or dropped.
                    // INTERIM (until the multi-message contract exists — storage, gate pairing with an atomic
                    // approve, delivery ordering): an overflowing body is SENT AS ONE MESSAGE and logged. A long
                    // bubble costs readability; failing closed on length alone would cost the family their
                    // dosing instructions, and that trade is never worth making. The number, forbidden-phrase
                    // and ellipsis checks above still fail closed exactly as before.
                    int cap = properties.getOutbound().getMaxChars();
                    if (full.length() > cap) {
                        log.info("[baymax] explanation is {} chars, over the {} cap — sent whole, not truncated; "
                                        + "the multi-message split needs the pairing contract (documentId={})",
                                full.length(), cap, facts.document().id());
                    }
                    return new OutboundMessage(null, facts.document().familyId(), facts.document().patientId(), facts.document().id(),
                            kind, assessed.level(), assessed.reasons(), full, gate.decide(assessed.level()),
                            null, null, null, null, clock.instant());
                })
                .switchIfEmpty(Mono.fromSupplier(() -> new OutboundMessage(null, facts.document().familyId(), facts.document().patientId(),
                        facts.document().id(), kind, assessed.level(), assessed.reasons(), null,
                        OutboundMessage.GateStatus.FAILED_SAFETY, null, null, null, null, clock.instant())))
                // urgency is never lowered by any later stage: the row must carry what was assessed
                .flatMap(message -> message.urgency() != assessed.level()
                        ? Mono.<OutboundMessage>error(new IllegalStateException("urgency changed after assessment"))
                        : Mono.just(message));
    }

    @Override
    public Mono<OutboundMessage> release(OutboundMessage composed) {
        if (composed.id() != null) {
            return Mono.error(new IllegalStateException("release takes a composed, unsaved message; got id=" + composed.id()));
        }
        return store(composed).flatMap(saved -> saved.gateStatus() == OutboundMessage.GateStatus.RELEASED
                ? deliver(saved) : Mono.just(saved));
    }

    /** Persist only: PENDING is parked with the reviewer, FAILED_SAFETY is recorded, RELEASED waits for deliver(). */
    private Mono<OutboundMessage> store(OutboundMessage m) {
        return switch (m.gateStatus()) {
            case PENDING -> messages.save(m).flatMap(saved -> reviewer.notifyPending(saved).thenReturn(saved));
            case FAILED_SAFETY -> messages.save(m);
            // sentAt stays NULL (V13/V14): only a provider acceptance sets it, and nothing has been attempted yet.
            // Setting it at save time was the same lie approve() used to tell.
            case RELEASED -> messages.save(new OutboundMessage(null, m.familyId(), m.patientId(), m.documentId(), m.kind(),
                    m.urgency(), m.urgencyReasons(), m.body(), OutboundMessage.GateStatus.RELEASED, null, null, null, null, m.createdAt()));
            case APPROVED, REJECTED -> Mono.error(new IllegalStateException("only the reviewer decides " + m.gateStatus().dbValue()));
        };
    }

    @Override
    public Mono<OutboundMessage> deliver(OutboundMessage stored) {
        if (stored.id() == null || !stored.isDeliverable()) {
            return Mono.error(new IllegalStateException("not deliverable: id=" + stored.id()
                    + " gate=" + (stored.gateStatus() == null ? null : stored.gateStatus().dbValue())));
        }
        Instant now = clock.instant();
        // F4: a row reaches the channel at most once. The claim is one UPDATE … WHERE sent_at IS NULL AND
        // (delivery_status IS NULL OR delivery_status = 'failed') RETURNING; empty means another caller holds the row.
        return messages.claimDelivery(stored.id())
                .flatMap(claimed -> delivery.deliver(claimed)
                        .onErrorResume(e -> {
                            // exception text can carry a provider URL or error body: class name above DEBUG
                            log.error("[baymax] released message delivery threw id={} error={}", stored.id(), e.getClass().getSimpleName());
                            log.debug("[baymax] released message delivery threw detail id={}: {}", stored.id(), e.toString());
                            return Mono.just(DeliveryOutcome.failed(DeliveryOutcome.SEND_FAILED));
                        })
                        .flatMap(outcome -> outcome.status() == null
                                // the log adapter delivered nothing; stamp sent_at (markSent also clears the claim)
                                ? messages.markSent(stored.id(), now).then(messages.find(stored.id()))
                                : messages.recordDelivery(stored.id(), outcome, now)));
    }

    /**
     * A cached DETAIL answers only for the urgency it was composed at. Urgency can rise after composition
     * (follow_up_past_due is date-driven), and a stale row would understate it — so a row at a LOWER level, or the
     * same level for different reasons, is recomposed. A row at a HIGHER level is kept: nothing lowers urgency (HC2;
     * F1 makes this central in phase 1b). FAILED_SAFETY and REJECTED rows are hits too: regenerating until the
     * checklist passes would make it a filter over chance, and recomposing after a reject would route around the
     * reviewer.
     */
    static boolean stillCurrent(OutboundMessage cached, UrgencyAssessment now) {
        if (cached.urgency() != now.level()) {
            return cached.urgency().isAtLeast(now.level());
        }
        return new java.util.HashSet<>(cached.urgencyReasons() == null ? List.of() : cached.urgencyReasons())
                .equals(new java.util.HashSet<>(now.reasons()));
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
     *
     * <p>2026-10: the claim also needs every value INSIDE by code ({@link UrgencyService#insideByCode}). Range
     * strings existing and a blank model flag are not evidence; LDL 250 against '<100' was claimed normal (D35).
     * The model's flag and critical mark may withdraw the claim, never grant it.
     */
    static boolean canSayWithinNormalRange(DocumentFacts facts) {
        List<Map<String, Object>> values = facts.values();
        if (values == null || values.isEmpty()) {
            return false;
        }
        // DR-28: a value read but not shown (no crop) is still on the page; the claim would cover it unseen
        if (facts.document().unverified().values() > 0) {
            return false;
        }
        for (Map<String, Object> value : values) {
            if (!UrgencyService.insideByCode(value)) {
                return false;
            }
            // the model's flag and critical mark may only WITHDRAW the claim, never grant it
            String flag = value.get("flag") == null ? "" : String.valueOf(value.get("flag"));
            if (!flag.isBlank() && !"normal".equalsIgnoreCase(flag)) {
                return false;
            }
            if (Boolean.TRUE.equals(value.get("critical"))) {
                return false;
            }
        }
        return true;
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
