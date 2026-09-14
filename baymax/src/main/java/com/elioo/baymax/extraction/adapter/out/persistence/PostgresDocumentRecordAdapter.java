package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
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
                        items.followUps().size(), items.dropped()));
    }

    /**
     * Written as SQL rather than through the repository because {@code extraction_json} is JSONB and needs
     * an explicit cast; R2DBC would otherwise send it as text and Postgres would refuse the column type.
     * Nullable columns are bound one by one because {@code bind} rejects a null value.
     */
    private Mono<Long> writeDocument(Document d) {
        var spec = db.sql("""
                        UPDATE %s.document SET
                            document_type = :type, doc_date = :docDate, facility = :facility,
                            extraction_json = CAST(:extraction AS jsonb), confidence_overall = :confidence,
                            status = :status, status_reason = :reason, model_final = :model,
                            cost_usd = :cost, page_count = :pages, updated_at = :updatedAt
                        WHERE id = :id
                        """.formatted(S))
                .bind("id", d.id())
                .bind("pages", d.pageCount())
                .bind("status", d.status().name())
                .bind("updatedAt", OffsetDateTime.ofInstant(d.updatedAt(), ZoneOffset.UTC));
        spec = bindOrNull(spec, "type", d.documentType(), String.class);
        spec = bindOrNull(spec, "docDate", d.docDate(), java.time.LocalDate.class);
        spec = bindOrNull(spec, "facility", d.facility(), String.class);
        spec = bindOrNull(spec, "extraction", d.extractionJson(), String.class);
        spec = bindOrNull(spec, "confidence", d.confidenceOverall(), Double.class);
        spec = bindOrNull(spec, "reason", d.statusReason(), String.class);
        spec = bindOrNull(spec, "model", d.modelFinal(), String.class);
        spec = bindOrNull(spec, "cost", d.costUsd(), BigDecimal.class);
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
                                    (patient_id, document_id, name, dose_text, frequency_text,
                                     duration_text, action, crop_key, at)
                                VALUES (:patient, :document, :name, :dose, :frequency, :duration,
                                        'recorded', :crop, :at)
                                """.formatted(S))
                        .bind("patient", m.patientId()).bind("document", documentId)
                        .bind("name", m.name()).bind("crop", m.cropKey())
                        .bind("at", OffsetDateTime.ofInstant(m.at(), ZoneOffset.UTC))
                        .bind("dose", nullable(m.doseText())).bind("frequency", nullable(m.frequencyText()))
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
    public Mono<Long> countInMonth(UUID familyId, YearMonth month) {
        return countInWindow(familyId,
                month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

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
}
