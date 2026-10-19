package com.elioo.baymax.outbound.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.outbound.application.port.out.DocumentUrgencyPort;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import io.r2dbc.spi.Row;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * F1 (V18): the document's stored urgency. The trigger rejects a lowering; this adapter merges in one statement
 * (max of the levels, union of the reasons), so a correct writer never asks for one. No {@code ?} jsonb operator on
 * purpose: Spring's named-parameter parser would read it as a bind marker.
 *
 * <p>F2 (V19): the value verdicts are written in the SAME transaction as the document's urgency, joined on the
 * observation id (never on the name), each by a guarded UPDATE that touches the row only when the new verdict is
 * higher; the trigger {@code observation_verdict_never_softens} rejects a softening whoever writes. A verdict that
 * cannot be written rolls the document's urgency back with it.
 */
@Component
public class PostgresDocumentUrgencyAdapter implements DocumentUrgencyPort {

    private static final String S = BaymaxSchema.NAME;

    private static final String RAISE = """
            UPDATE %1$s.document SET
                urgency = CASE WHEN urgency IS NULL
                                    OR %1$s.urgency_rank(CAST(:level AS varchar)) > %1$s.urgency_rank(urgency)
                               THEN CAST(:level AS varchar) ELSE urgency END,
                urgency_reasons = (SELECT coalesce(jsonb_agg(DISTINCT r ORDER BY r), '[]'::jsonb)
                                   FROM jsonb_array_elements_text(coalesce(urgency_reasons, '[]'::jsonb)
                                                                  || CAST(:reasons AS jsonb)) AS t(r)),
                urgency_assessed_at = :at
            WHERE id = :id
            RETURNING id, urgency, urgency_reasons::text AS reasons, urgency_assessed_at
            """.formatted(S);

    /** Only ever raises a row: verdict_rank and urgency_rank are V19's and V18's, the same order the trigger enforces. */
    private static final String RAISE_VERDICT = """
            UPDATE %1$s.observation SET verdict = :kind, verdict_level = :level, verdict_side = :side, verdict_reason = :reason
            WHERE id = :obs AND document_id = :doc
              AND (verdict IS NULL
                   OR %1$s.verdict_rank(CAST(:kind AS varchar)) > %1$s.verdict_rank(verdict)
                   OR (verdict = :kind AND %1$s.urgency_rank(CAST(:level AS varchar)) > %1$s.urgency_rank(verdict_level)))
            """.formatted(S);

    private static final String VERDICTS = """
            SELECT id, verdict, verdict_level, verdict_side, verdict_reason FROM %1$s.observation
            WHERE document_id = :doc AND verdict IS NOT NULL
            ORDER BY observed_at, id
            """.formatted(S);

    private static final String FIND = """
            SELECT id, urgency, urgency_reasons::text AS reasons, urgency_assessed_at
            FROM %1$s.document WHERE id = :id AND urgency IS NOT NULL
            """.formatted(S);

    /**
     * The sweep's queue. The verdict half is F2's backfill and every re-crop: an observation without a verdict is assessed
     * (every shown value gets one, so one assessment empties it). The past-due half is UrgencyService's rule (any follow-up of the document, due before the
     * patient's today) minus the documents already carrying its reason, so an assessment always removes what it picked.
     * {@code @>} is containment, not the {@code ?} operator the class comment warns about.
     */
    private static final String NEEDING_ASSESSMENT = """
            SELECT d.id FROM %1$s.document d
            WHERE d.status = 'DONE'
              AND (d.urgency IS NULL
                   OR (NOT d.urgency_reasons @> '["follow_up_past_due"]'::jsonb
                       AND EXISTS (SELECT 1 FROM %1$s.follow_up f WHERE f.document_id = d.id AND f.due_date < :today))
                   OR EXISTS (SELECT 1 FROM %1$s.observation o WHERE o.document_id = d.id AND o.verdict IS NULL))
            ORDER BY d.urgency_attempted_at NULLS FIRST, d.created_at, d.id
            LIMIT :limit
            """.formatted(S);

    /** The attempt stamp only: not the level (the trigger ignores this column) and not updated_at (the DETAIL cache key). */
    private static final String MARK_ATTEMPT = """
            UPDATE %1$s.document SET urgency_attempted_at = :at WHERE id = :id
            """.formatted(S);

    private final DatabaseClient db;
    private final TransactionalOperator transactional;

    /**
     * The operator is built on this client's own connection factory, so the document UPDATE and the verdict UPDATEs
     * share one connection and one transaction in every context, including those without the transaction
     * auto-configuration.
     */
    public PostgresDocumentUrgencyAdapter(DatabaseClient db) {
        this.db = db;
        this.transactional = TransactionalOperator.create(new R2dbcTransactionManager(db.getConnectionFactory()));
    }

    @Override
    public Mono<StoredUrgency> raise(UUID documentId, UrgencyAssessment assessed, Instant at) {
        Mono<StoredUrgency> work = db.sql(RAISE)
                .bind("id", documentId)
                .bind("level", assessed.level().dbValue())
                .bind("reasons", UrgencyReasonsJson.encode(assessed.reasons()))
                .bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .map((row, meta) -> stored(row))
                .one()
                .flatMap(document -> Flux.fromIterable(assessed.values())
                        .concatMap(v -> raiseVerdict(documentId, v))
                        .then(verdictsOf(documentId))
                        .map(document::withValues));
        return transactional.transactional(work);
    }

    /** One row, raised only if higher. Refused before any write when it names no observation: there is no row to raise. */
    private Mono<Long> raiseVerdict(UUID documentId, ValueVerdict v) {
        if (v.observationId() == null) {
            return Mono.error(new IllegalStateException("verdict without observation id"));
        }
        DatabaseClient.GenericExecuteSpec spec = db.sql(RAISE_VERDICT)
                .bind("obs", v.observationId())
                .bind("doc", documentId)
                .bind("kind", v.kind().dbValue())
                .bind("level", v.level().dbValue())
                .bind("reason", v.reason());
        spec = v.side() == null ? spec.bindNull("side", String.class) : spec.bind("side", v.side().dbValue());
        return spec.fetch().rowsUpdated();
    }

    private Mono<List<ValueVerdict>> verdictsOf(UUID documentId) {
        return db.sql(VERDICTS).bind("doc", documentId)
                .map((row, meta) -> new ValueVerdict(row.get("id", UUID.class),
                        ValueVerdict.Kind.fromDbValue(row.get("verdict", String.class)),
                        Urgency.fromDbValue(row.get("verdict_level", String.class)),
                        ValueVerdict.Side.fromDbValue(row.get("verdict_side", String.class)),
                        row.get("verdict_reason", String.class)))
                .all().collectList();
    }

    @Override
    public Mono<StoredUrgency> find(UUID documentId) {
        return db.sql(FIND).bind("id", documentId).map((row, meta) -> stored(row)).one()
                .flatMap(document -> verdictsOf(documentId).map(document::withValues));
    }

    @Override
    public Flux<UUID> needingAssessment(LocalDate today, int limit) {
        return db.sql(NEEDING_ASSESSMENT)
                .bind("today", today)
                .bind("limit", limit)
                .map((row, meta) -> row.get("id", UUID.class))
                .all();
    }

    @Override
    public Mono<Void> markAttempt(UUID documentId, Instant at) {
        return db.sql(MARK_ATTEMPT)
                .bind("id", documentId)
                .bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .then();
    }

    private static StoredUrgency stored(Row row) {
        OffsetDateTime at = row.get("urgency_assessed_at", OffsetDateTime.class);
        return new StoredUrgency(row.get("id", UUID.class), Urgency.fromDbValue(row.get("urgency", String.class)),
                UrgencyReasonsJson.decode(row.get("reasons", String.class)), at == null ? null : at.toInstant());
    }
}
