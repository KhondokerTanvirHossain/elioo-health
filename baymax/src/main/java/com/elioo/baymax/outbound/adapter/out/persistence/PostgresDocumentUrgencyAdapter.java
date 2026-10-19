package com.elioo.baymax.outbound.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.outbound.application.port.out.DocumentUrgencyPort;
import com.elioo.baymax.outbound.domain.StoredUrgency;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * F1 (V18): the document's stored urgency. The trigger rejects a lowering; this adapter merges in one statement
 * (max of the levels, union of the reasons), so a correct writer never asks for one. No {@code ?} jsonb operator on
 * purpose: Spring's named-parameter parser would read it as a bind marker.
 */
@Component
@RequiredArgsConstructor
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

    private static final String FIND = """
            SELECT id, urgency, urgency_reasons::text AS reasons, urgency_assessed_at
            FROM %1$s.document WHERE id = :id AND urgency IS NOT NULL
            """.formatted(S);

    /**
     * The sweep's queue. The past-due half is UrgencyService's rule (any follow-up of the document, due before the
     * patient's today) minus the documents already carrying its reason, so an assessment always removes what it picked.
     * {@code @>} is containment, not the {@code ?} operator the class comment warns about.
     */
    private static final String NEEDING_ASSESSMENT = """
            SELECT d.id FROM %1$s.document d
            WHERE d.status = 'DONE'
              AND (d.urgency IS NULL
                   OR (NOT d.urgency_reasons @> '["follow_up_past_due"]'::jsonb
                       AND EXISTS (SELECT 1 FROM %1$s.follow_up f WHERE f.document_id = d.id AND f.due_date < :today)))
            ORDER BY d.urgency_attempted_at NULLS FIRST, d.created_at, d.id
            LIMIT :limit
            """.formatted(S);

    /** The attempt stamp only: not the level (the trigger ignores this column) and not updated_at (the DETAIL cache key). */
    private static final String MARK_ATTEMPT = """
            UPDATE %1$s.document SET urgency_attempted_at = :at WHERE id = :id
            """.formatted(S);

    private final DatabaseClient db;

    @Override
    public Mono<StoredUrgency> raise(UUID documentId, UrgencyAssessment assessed, Instant at) {
        return db.sql(RAISE)
                .bind("id", documentId)
                .bind("level", assessed.level().dbValue())
                .bind("reasons", UrgencyReasonsJson.encode(assessed.reasons()))
                .bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .map((row, meta) -> stored(row))
                .one();
    }

    @Override
    public Mono<StoredUrgency> find(UUID documentId) {
        return db.sql(FIND).bind("id", documentId).map((row, meta) -> stored(row)).one();
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
