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
import reactor.core.publisher.Mono;

import java.time.Instant;
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

    private static StoredUrgency stored(Row row) {
        OffsetDateTime at = row.get("urgency_assessed_at", OffsetDateTime.class);
        return new StoredUrgency(row.get("id", UUID.class), Urgency.fromDbValue(row.get("urgency", String.class)),
                UrgencyReasonsJson.decode(row.get("reasons", String.class)), at == null ? null : at.toInstant());
    }
}
