package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.CreatedDocument;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.VerifiedItems;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/** The only {@link DocumentRecordPort} implementation. Every table name is schema-qualified. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostgresDocumentRecordAdapter implements DocumentRecordPort {

    private static final String S = BaymaxSchema.NAME;

    private final DocumentRepository documents;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final ObservationRepository observations;
    private final MedicationEventRepository medications;
    private final FollowUpRepository followUps;
    private final DatabaseClient db;

    @Override
    public Mono<Document> create(Document document) {
        DocumentEntity entity = DocumentEntity.from(document);
        entity.setId(null);
        return documents.save(entity).map(DocumentEntity::toRecord);
    }

    /**
     * One statement, so the database decides: {@code ON CONFLICT … DO NOTHING} on V21's partial unique index leaves the
     * loser's {@code RETURNING} empty, and it reads the winner's row. A read-then-insert would let two racing presses
     * both see "none" and both insert.
     */
    @Override
    public Mono<CreatedDocument> createOnce(Document d, String uploadKey) {
        if (uploadKey == null) {
            return create(d).map(doc -> new CreatedDocument(doc, true));
        }
        return db.sql("INSERT INTO " + S + ".document (patient_id, family_id, status, page_count, created_at, updated_at, upload_key) "
                        + "VALUES (:patient, :family, :status, :pages, :createdAt, :updatedAt, :key) "
                        + "ON CONFLICT (family_id, upload_key) WHERE upload_key IS NOT NULL DO NOTHING RETURNING id")
                .bind("patient", d.patientId()).bind("family", d.familyId()).bind("status", d.status().name())
                .bind("pages", d.pageCount())
                .bind("createdAt", OffsetDateTime.ofInstant(d.createdAt(), ZoneOffset.UTC))
                .bind("updatedAt", OffsetDateTime.ofInstant(d.updatedAt(), ZoneOffset.UTC))
                .bind("key", uploadKey)
                .map((row, meta) -> row.get("id", UUID.class)).one()
                .flatMap(id -> find(id).map(doc -> new CreatedDocument(doc, true)))
                .switchIfEmpty(Mono.defer(() -> findByUploadKey(d.familyId(), uploadKey).map(doc -> new CreatedDocument(doc, false))));
    }

    @Override
    public Mono<Document> findByUploadKey(UUID familyId, String uploadKey) {
        return documents.findByFamilyIdAndUploadKey(familyId, uploadKey).map(DocumentEntity::toRecord);
    }

    @Override
    public Mono<Document> find(UUID documentId) {
        return documents.findById(documentId).map(DocumentEntity::toRecord);
    }

    @Override
    public Mono<Document> update(Document document) {
        return writeDocument(document).thenReturn(document);
    }

    @Override
    @Transactional
    public Mono<Document> saveExtraction(Document document, VerifiedItems items) {
        return writeDocument(document)
                .then(insertObservations(document.id(), items))
                .then(insertMedications(document.id(), items))
                .then(insertFollowUps(document.id(), items))
                .thenReturn(document)
                .doOnSuccess(d -> log.info("[baymax] extraction saved documentId={} values={} medicines={} followUps={} dropped={}",
                        d.id(), items.observations().size(), items.medications().size(),
                        items.followUps().size(), items.unverified().total()));
    }

    /**
     * Written as SQL rather than through the repository because {@code extraction_json} is JSONB and needs
     * an explicit cast; R2DBC would otherwise send it as text and Postgres would refuse the column type.
     * Nullable columns are bound one by one because {@code bind} rejects a null value.
     */
    private Mono<Long> writeDocument(Document d) {
        var spec = db.sql("""
                        UPDATE %s.document SET
                            document_type = :type, doc_date = :docDate, doc_date_uncertain = :dateUncertain,
                            facility = :facility,
                            extraction_json = CAST(:extraction AS jsonb), confidence_overall = :confidence,
                            status = :status, status_reason = :reason, status_reason_code = :reasonCode,
                            model_final = :model,
                            cost_usd = :cost, page_count = :pages, updated_at = :updatedAt,
                            unverified_values = :unverifiedValues,
                            unverified_medicines = :unverifiedMedicines,
                            unverified_follow_up = :unverifiedFollowUp,
                            unverified_clinical_context = :unverifiedContext,
                            clinical_context = CAST(:clinicalContext AS jsonb)
                        WHERE id = :id
                        """.formatted(S))
                .bind("id", d.id())
                .bind("pages", d.pageCount())
                .bind("unverifiedValues", d.unverified().values())
                .bind("unverifiedMedicines", d.unverified().medicines())
                .bind("unverifiedFollowUp", d.unverified().followUp())
                .bind("unverifiedContext", d.unverified().clinicalContext())
                .bind("status", d.status().name())
                .bind("dateUncertain", d.docDateUncertain())
                .bind("updatedAt", OffsetDateTime.ofInstant(d.updatedAt(), ZoneOffset.UTC));
        spec = bindOrNull(spec, "type", d.documentType(), String.class);
        spec = bindOrNull(spec, "docDate", d.docDate(), java.time.LocalDate.class);
        spec = bindOrNull(spec, "facility", d.facility(), String.class);
        spec = bindOrNull(spec, "extraction", d.extractionJson(), String.class);
        spec = bindOrNull(spec, "confidence", d.confidenceOverall(), Double.class);
        spec = bindOrNull(spec, "reason", d.statusReason(), String.class);
        spec = bindOrNull(spec, "reasonCode", d.statusReasonCode(), String.class);
        spec = bindOrNull(spec, "model", d.modelFinal(), String.class);
        spec = bindOrNull(spec, "cost", d.costUsd(), BigDecimal.class);
        spec = bindOrNull(spec, "clinicalContext", d.clinicalContextJson(), String.class);
        return spec.fetch().rowsUpdated();
    }

    private static DatabaseClient.GenericExecuteSpec bindOrNull(DatabaseClient.GenericExecuteSpec spec,
                                                                String name, Object value, Class<?> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private Mono<Void> insertObservations(UUID documentId, VerifiedItems items) {
        return Flux.fromIterable(items.observations())
                .concatMap(o -> db.sql("""
                                INSERT INTO %s.observation
                                    (patient_id, document_id, name, canonical_name, value, unit,
                                     ref_low, ref_high, flag, crop_key, observed_at)
                                VALUES (:patient, :document, :name, :canonical, :value, :unit,
                                        :refLow, :refHigh, :flag, :crop, :observedAt)
                                """.formatted(S))
                        .bind("patient", o.patientId()).bind("document", documentId)
                        .bind("name", o.name()).bind("value", o.value()).bind("crop", o.cropKey())
                        .bind("observedAt", OffsetDateTime.ofInstant(o.observedAt(), ZoneOffset.UTC))
                        .bind("canonical", nullable(o.canonicalName())).bind("unit", nullable(o.unit()))
                        .bind("refLow", nullable(o.refLow())).bind("refHigh", nullable(o.refHigh()))
                        .bind("flag", nullable(o.flag()))
                        .fetch().rowsUpdated())
                .then();
    }

    private Mono<Void> insertMedications(UUID documentId, VerifiedItems items) {
        return Flux.fromIterable(items.medications())
                .concatMap(m -> db.sql("""
                                INSERT INTO %s.medication_event
                                    (patient_id, document_id, name, dose_text, route, frequency_text,
                                     timing_text, duration_text, action, crop_key, at)
                                VALUES (:patient, :document, :name, :dose, :route, :frequency,
                                        :timing, :duration, 'recorded', :crop, :at)
                                """.formatted(S))
                        .bind("patient", m.patientId()).bind("document", documentId)
                        .bind("name", m.name()).bind("crop", m.cropKey())
                        .bind("at", OffsetDateTime.ofInstant(m.at(), ZoneOffset.UTC))
                        .bind("dose", nullable(m.doseText())).bind("frequency", nullable(m.frequencyText()))
                        .bind("route", nullable(m.route())).bind("timing", nullable(m.timingText()))
                        .bind("duration", nullable(m.durationText()))
                        .fetch().rowsUpdated())
                .then();
    }

    private Mono<Void> insertFollowUps(UUID documentId, VerifiedItems items) {
        return Flux.fromIterable(items.followUps())
                .concatMap(f -> db.sql("""
                                INSERT INTO %s.follow_up
                                    (patient_id, document_id, instruction, due_date, status, crop_key)
                                VALUES (:patient, :document, :instruction, :dueDate, 'open', :crop)
                                """.formatted(S))
                        .bind("patient", f.patientId()).bind("document", documentId)
                        .bind("instruction", f.instruction()).bind("crop", f.cropKey())
                        .bind("dueDate", f.dueDate() == null
                                ? org.springframework.r2dbc.core.Parameter.empty(java.time.LocalDate.class)
                                : f.dueDate())
                        .fetch().rowsUpdated())
                .then();
    }

    @Override
    public Mono<BigDecimal> refreshCost(UUID documentId) {
        return db.sql("""
                        UPDATE %1$s.document SET cost_usd =
                            (SELECT SUM(cost_usd) FROM %1$s.ai_call_log WHERE document_id = :id)
                        WHERE id = :id
                        RETURNING cost_usd
                        """.formatted(S))
                .bind("id", documentId)
                // SUM over rows that are all unpriced is NULL, and a row mapper must never return null
                .map((row, meta) -> {
                    BigDecimal sum = row.get("cost_usd", BigDecimal.class);
                    return sum == null ? BigDecimal.ZERO : sum;
                })
                .one()
                .defaultIfEmpty(BigDecimal.ZERO);
    }

    @Override
    public Flux<Map<String, Object>> observationsOf(UUID documentId) {
        return observations.findByDocumentId(documentId).map(ObservationEntity::toView);
    }

    @Override
    public Flux<Map<String, Object>> medicinesOf(UUID documentId) {
        return medications.findByDocumentId(documentId).map(MedicationEventEntity::toView);
    }

    @Override
    public Flux<Map<String, Object>> followUpsOf(UUID documentId) {
        return followUps.findByDocumentId(documentId).map(FollowUpEntity::toView);
    }

    @Override
    public Mono<Map<String, Object>> clinicalContextOf(UUID documentId) {
        return documents.findById(documentId)
                .mapNotNull(DocumentEntity::getClinicalContext)
                .handle((json, sink) -> {
                    try {
                        sink.next(mapper.readValue(json, new TypeReference<Map<String, Object>>() { }));
                    } catch (Exception e) {
                        log.warn("[baymax] could not read clinical_context for {}: {}", documentId, e.getMessage());
                    }
                });
    }

    /**
     * Documents that consume a free-tier slot this month — DONE only (DR-24).
     *
     * <p>A NEEDS_RETAKE document delivered nothing: we could not read the family's photo, told them so, and
     * said it was not their fault. Charging a slot for it bills them for our failure and lands hardest on the
     * families with the worst cameras, who are the ones the product exists for. FAILED does not count either,
     * for the same reason. Deliberately NOT {@code countInWindow}, which stays a plain count of rows.
     */
    @Override
    public Mono<Long> countInMonth(UUID familyId, YearMonth month) {
        return db.sql("SELECT COUNT(*) FROM " + S + ".document WHERE family_id = :family "
                        + "AND created_at >= :from AND created_at < :to AND status = 'DONE'")
                .bind("family", familyId)
                .bind("from", OffsetDateTime.ofInstant(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC))
                .bind("to", OffsetDateTime.ofInstant(month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC))
                .map((row, meta) -> {
                    Long count = row.get(0, Long.class);
                    return count == null ? 0L : count;
                }).one().defaultIfEmpty(0L);
    }

    @Override
    public Mono<Document> latestDoneDocument(UUID familyId) {
        return documents.latestDone(familyId).map(DocumentEntity::toRecord);
    }

    /** Every document row in the window, whatever its status — reporting, not billing. */
    @Override
    public Mono<Long> countInWindow(UUID familyId, Instant from, Instant to) {
        return db.sql("SELECT COUNT(*) FROM " + S + ".document WHERE family_id = :family "
                        + "AND created_at >= :from AND created_at < :to")
                .bind("family", familyId)
                .bind("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .bind("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .map((row, meta) -> {
                    Long count = row.get(0, Long.class);
                    return count == null ? 0L : count;
                }).one().defaultIfEmpty(0L);
    }

    @Override
    public Mono<Instant> lastDocumentAt(UUID familyId) {
        // MAX over no rows is NULL; Optional keeps that out of the row mapper, which cannot emit null
        return db.sql("SELECT MAX(created_at) FROM " + S + ".document WHERE family_id = :family")
                .bind("family", familyId)
                .map((row, meta) -> java.util.Optional.ofNullable(row.get(0, OffsetDateTime.class)))
                .one()
                .flatMap(found -> found.map(odt -> Mono.just(odt.toInstant())).orElseGet(Mono::empty));
    }

    @Override
    public Flux<UUID> idsOfFamily(UUID familyId) {
        return db.sql("SELECT id FROM " + S + ".document WHERE family_id = :family")
                .bind("family", familyId).map((row, meta) -> row.get(0, UUID.class)).all();
    }

    @Override
    public Flux<UUID> idsOfPatient(UUID patientId) {
        return db.sql("SELECT id FROM " + S + ".document WHERE patient_id = :patient")
                .bind("patient", patientId).map((row, meta) -> row.get(0, UUID.class)).all();
    }

    private static Object nullable(String value) {
        return value == null ? org.springframework.r2dbc.core.Parameter.empty(String.class) : value;
    }

    // --- BMX-5: timeline reads and the scoped lookup ------------------------------------------------------

    @Override
    public Flux<Document> timelineOf(UUID patientId, Instant before, int limit) {
        return documents.timelineOf(patientId, java.time.OffsetDateTime.ofInstant(before, java.time.ZoneOffset.UTC), limit)
                .map(DocumentEntity::toRecord);
    }

    @Override
    public Mono<Document> findVisible(UUID familyId, UUID documentId) {
        return db.sql("""
                        SELECT d.id FROM %1$s.document d
                        JOIN %1$s.patient_profile p ON p.id = d.patient_id
                        WHERE d.id = :document AND (p.family_id = :family
                           OR EXISTS (SELECT 1 FROM %1$s.share_member s JOIN %1$s.family_account f ON f.whatsapp_number = s.whatsapp_number
                                      WHERE s.patient_id = p.id AND f.id = :family))
                        """.formatted(S))
                .bind("document", documentId).bind("family", familyId)
                .map((row, meta) -> row.get("id", UUID.class)).one()
                .flatMap(this::find);
    }

    @Override
    public Mono<int[]> sectionCounts(UUID documentId) {
        return db.sql("""
                        SELECT (SELECT count(*) FROM %1$s.observation WHERE document_id = :d) AS v,
                               (SELECT count(*) FROM %1$s.medication_event WHERE document_id = :d) AS m,
                               (SELECT count(*) FROM %1$s.follow_up WHERE document_id = :d) AS f
                        """.formatted(S))
                .bind("d", documentId)
                .map((row, meta) -> new int[]{row.get("v", Long.class).intValue(), row.get("m", Long.class).intValue(),
                        row.get("f", Long.class).intValue()})
                .one();
    }

    /**
     * The nudges that quote this paper. A nudge names its source only through {@code trigger_key} (NudgeRules) and
     * copies the source's words into {@code vars_json}, so it is matched by what it QUOTES, not only by its key:
     * <ul>
     *   <li>the paper itself ({@code document:<id>}), one of its follow-ups ({@code follow_up:<id>}) or one of its
     *       medicines ({@code medication:<id>});</li>
     *   <li>every {@code trend} of the patient on a marker one of this paper's values has. A trend is keyed to the run's
     *       FIRST reading ({@code trend:<marker>:<observation id>}) but quotes every reading up to the LATEST, which is
     *       normally the paper just uploaded — the one a family deletes as "wrong paper" — so the key alone misses the
     *       usual case. The marker is {@code observation.canonical_name}, the same value {@code markersOf} reads;</li>
     *   <li>every {@code medicine_changed} of the patient keyed to a prescription dated on or after this paper, when
     *       this paper has medicines: such a nudge is keyed {@code document:<newer>} and quotes the OLDER prescription's
     *       medicines verbatim as its "before" side.</li>
     * </ul>
     * Wider than exact on purpose: withdrawing an unsent nudge is the safe direction. A trend is re-evaluated on every
     * run from the data that is left; a medicine_changed that did not quote this paper is lost (its rule runs only when
     * a prescription finishes), which is the price of never sending one that did. Read while the paper's rows still
     * exist, so it must run before they are deleted.
     */
    private static final String NUDGES_OF_THE_PAPER = "(n.trigger_key = 'document:' || CAST(:d AS text)"
            + " OR n.trigger_key IN (SELECT 'follow_up:' || f.id FROM " + S + ".follow_up f WHERE f.document_id = :d)"
            + " OR n.trigger_key IN (SELECT 'medication:' || m.id FROM " + S + ".medication_event m WHERE m.document_id = :d)"
            + " OR (n.rule = 'trend' AND EXISTS (SELECT 1 FROM " + S + ".observation o WHERE o.document_id = :d"
            + " AND o.patient_id = n.patient_id AND (n.trigger_key LIKE 'trend:%:' || o.id"
            + " OR (o.canonical_name IS NOT NULL AND starts_with(n.trigger_key, 'trend:' || o.canonical_name || ':')))))"
            + " OR (n.rule = 'medicine_changed' AND EXISTS (SELECT 1 FROM " + S + ".medication_event m WHERE m.document_id = :d)"
            + " AND n.trigger_key IN (SELECT 'document:' || x.id FROM " + S + ".document x JOIN " + S + ".document me ON me.id = :d"
            + " WHERE x.patient_id = me.patient_id AND n.patient_id = me.patient_id AND x.id <> me.id"
            + " AND COALESCE(x.doc_date, x.created_at::date) >= COALESCE(me.doc_date, me.created_at::date))))";

    /**
     * Not delivered: the nudge row says it was not sent AND its message, if any, was never accepted by the provider.
     * {@code nudge.status} alone is not enough — a gated nudge keeps {@code 'gated'} after the reviewer approves it and
     * WhatsApp accepts it (approve updates only the message), and that row is the cap ledger ({@code countedSince}).
     */
    private static final String UNDELIVERED_NUDGE = "n.status <> 'sent' AND NOT EXISTS (SELECT 1 FROM " + S
            + ".outbound_message dm WHERE dm.id = n.message_id AND dm.sent_at IS NOT NULL)";

    /**
     * One transaction: everything the paper wrote, then the paper. Its ai_call_log rows keep their numbers and lose the
     * id. Its {@code stored_object} ledger rows go here too, as {@code deleteFamily} does for a family, so that if the
     * bucket cannot be emptied afterwards only bucket objects survive, and those are what {@code orphaned_objects} names.
     *
     * <p>The paper's row is locked first ({@code FOR UPDATE}). A message about the paper is only ever stored under a
     * {@code FOR KEY SHARE} lock on the same row ({@code PostgresOutboundMessageAdapter.save}), so a message composed
     * while this runs is either stored before it and withdrawn by it, or refused after it: none survives a delete.
     *
     * <p><b>Every message and nudge made from the paper that has not reached the family is withdrawn</b>, whatever its
     * state: a {@code pending} explanation stays approvable (approve never re-reads the document) and a HELD nudge is
     * composed from its {@code vars_json} alone (releaseHeld never re-reads the source rows), so either would still send
     * WhatsApp text about a paper the family deleted. Unsent means {@code sent_at IS NULL} (V14: the provider accepted it)
     * — pending, approved but never delivered, rejected, failed_safety — and a nudge that quotes the paper
     * ({@link #NUDGES_OF_THE_PAPER}) and was not delivered ({@link #UNDELIVERED_NUDGE}), with the unsent message of a
     * gated nudge, which carries no document_id. Delivered history ({@code sent_at} set, a {@code sent} nudge, a gated
     * nudge whose message was accepted) is kept, pending PO decision P1 (first raised by A13, #76).
     *
     * @return every row deleted, across all tables
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public Mono<Long> deleteDocument(UUID documentId) {
        return db.sql("SELECT id FROM " + S + ".document WHERE id = :d FOR UPDATE").bind("d", documentId).fetch().all().then()
                // V22: the WhatsApp message the paper arrived in keeps its key (a Meta re-delivery must still be a duplicate)
                // and its family, and loses the patient, the document and Meta's media id, a handle that fetches the photo
                // again while Meta keeps it. Same transaction; the FK's SET NULL alone would leave the media id.
                .then(db.sql("UPDATE " + S + ".inbound_message SET media_id = NULL, patient_id = NULL, document_id = NULL"
                        + " WHERE document_id = :d").bind("d", documentId).then())
                .then(db.sql("UPDATE " + S + ".ai_call_log SET document_id = NULL WHERE document_id = :d").bind("d", documentId).then())
                .then(rows("DELETE FROM " + S + ".outbound_message WHERE document_id = :d AND sent_at IS NULL", documentId))
                .flatMap(n -> rows("DELETE FROM " + S + ".outbound_message WHERE sent_at IS NULL AND id IN (SELECT n.message_id FROM "
                        + S + ".nudge n WHERE n.message_id IS NOT NULL AND " + UNDELIVERED_NUDGE + " AND " + NUDGES_OF_THE_PAPER + ")",
                        documentId).map(m -> n + m))
                .flatMap(n -> rows("DELETE FROM " + S + ".nudge n WHERE " + UNDELIVERED_NUDGE + " AND " + NUDGES_OF_THE_PAPER, documentId)
                        .map(m -> n + m))
                .flatMap(n -> rows("DELETE FROM " + S + ".stored_object WHERE document_id = :d", documentId).map(m -> n + m))
                .flatMap(n -> rows("DELETE FROM " + S + ".observation WHERE document_id = :d", documentId).map(m -> n + m))
                .flatMap(n -> rows("DELETE FROM " + S + ".medication_event WHERE document_id = :d", documentId).map(m -> n + m))
                .flatMap(n -> rows("DELETE FROM " + S + ".follow_up WHERE document_id = :d", documentId).map(m -> n + m))
                .flatMap(n -> rows("DELETE FROM " + S + ".document WHERE id = :d", documentId).map(m -> n + m));
    }

    private Mono<Long> rows(String sql, UUID documentId) {
        return db.sql(sql).bind("d", documentId).fetch().rowsUpdated();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Mono<Long> deleteItems(UUID documentId) {
        return db.sql("DELETE FROM " + S + ".observation WHERE document_id = :d").bind("d", documentId).fetch().rowsUpdated()
                .zipWith(db.sql("DELETE FROM " + S + ".medication_event WHERE document_id = :d").bind("d", documentId).fetch().rowsUpdated(), Long::sum)
                .zipWith(db.sql("DELETE FROM " + S + ".follow_up WHERE document_id = :d").bind("d", documentId).fetch().rowsUpdated(), Long::sum);
    }

    @Override
    public Flux<UUID> idsWithStatus(Document.Status status) {
        return db.sql("SELECT id FROM " + S + ".document WHERE status = :s ORDER BY created_at")
                .bind("s", status.name()).map((row, meta) -> row.get("id", UUID.class)).all();
    }

    @Override
    public Mono<Integer> markRecoveryAttempt(UUID documentId, Instant interruptedBefore) {
        return db.sql("UPDATE " + S + ".document SET recovery_attempts = recovery_attempts + 1 "
                        + "WHERE id = :id AND status IN ('RECEIVED','PROCESSING') AND updated_at < :before "
                        + "RETURNING recovery_attempts")
                .bind("id", documentId)
                .bind("before", OffsetDateTime.ofInstant(interruptedBefore, ZoneOffset.UTC))
                .map((row, meta) -> row.get("recovery_attempts", Integer.class))
                .one();
    }

    /**
     * A DONE or NEEDS_RETAKE document with no family-facing message about it. Detail and nudge rows do not count, and
     * neither does an explanation row with no body: that is a FAILED_SAFETY attempt (stored before the PR 9 fallback is
     * built), so a fallback that never got stored — a restart in that window, a throw — leaves the document unanswered
     * and the sweep picks it up. PENDING and REJECTED rows carry their body and count: the reviewer has them.
     */
    private static final String FINISHED_UNANSWERED = "(d.status IN ('DONE','NEEDS_RETAKE') AND NOT EXISTS (SELECT 1 FROM "
            + S + ".outbound_message m WHERE m.document_id = d.id AND m.kind IN ('explanation','retake') AND m.body IS NOT NULL))";
    /** A FAILED document is answered only by its failure notice (PR 9, V23): the family was told "reading it". */
    private static final String FAILED_UNNOTIFIED = "(d.status = 'FAILED' AND NOT EXISTS (SELECT 1 FROM "
            + S + ".outbound_message m WHERE m.document_id = d.id AND m.kind = 'failure'))";
    /** Either. The outer parentheses keep the time window applying to BOTH branches (DocumentRecoveryAcceptanceTest). */
    private static final String NEVER_EXPLAINED = "(" + FINISHED_UNANSWERED + " OR " + FAILED_UNNOTIFIED + ")";

    @Override
    public Flux<UUID> unexplainedSince(Instant since, Instant before) {
        return db.sql("SELECT d.id FROM " + S + ".document d WHERE " + NEVER_EXPLAINED
                        + " AND d.updated_at >= :since AND d.updated_at < :before ORDER BY d.updated_at")
                .bind("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .bind("before", OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
                .map((row, meta) -> row.get("id", UUID.class))
                .all();
    }

    @Override
    public Mono<Long> countUnexplainedBefore(Instant since) {
        return countBefore(FINISHED_UNANSWERED, since);
    }

    @Override
    public Mono<Long> countFailedUnnotifiedBefore(Instant since) {
        return countBefore(FAILED_UNNOTIFIED, since);
    }

    private Mono<Long> countBefore(String predicate, Instant since) {
        return db.sql("SELECT COUNT(*) FROM " + S + ".document d WHERE " + predicate + " AND d.updated_at < :since")
                .bind("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .map((row, meta) -> {
                    Long count = row.get(0, Long.class);
                    return count == null ? 0L : count;
                })
                .one()
                .defaultIfEmpty(0L);
    }
}
