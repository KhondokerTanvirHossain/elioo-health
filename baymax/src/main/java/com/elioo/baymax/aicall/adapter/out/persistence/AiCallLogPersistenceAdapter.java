package com.elioo.baymax.aicall.adapter.out.persistence;

import com.elioo.baymax.aicall.application.port.out.AiCallLogPort;
import com.elioo.baymax.aicall.domain.AiCallRecord;
import com.elioo.baymax.aicall.domain.DocumentAiCost;
import com.elioo.baymax.config.BaymaxSchema;
import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AiCallLogPersistenceAdapter implements AiCallLogPort {

    private static final String PER_DOCUMENT_SQL = """
            SELECT document_id,
                   COUNT(*)                                          AS calls,
                   COUNT(*) FILTER (WHERE l.cost_usd IS NULL)          AS unpriced_calls,
                   COALESCE(SUM(l.input_tokens), 0)                    AS input_tokens,
                   COALESCE(SUM(l.output_tokens), 0)                   AS output_tokens,
                   SUM(l.cost_usd)                                     AS cost_usd,
                   STRING_AGG(DISTINCT l.provider || '/' || l.model, '|' ORDER BY l.provider || '/' || l.model) AS models,
                   AVG(l.confidence)                                   AS avg_confidence,
                   COALESCE(MAX(d.unverified_values + d.unverified_medicines + d.unverified_follow_up), 0)
                                                                     AS unverified_items,
                   MIN(l.created_at)                                   AS first_call_at,
                   MAX(l.created_at)                                   AS last_call_at
            FROM %1$s.ai_call_log l
            LEFT JOIN %1$s.document d ON d.id = l.document_id
            WHERE l.created_at >= :from AND l.created_at < :to
            GROUP BY document_id
            ORDER BY document_id NULLS LAST
            """.formatted(BaymaxSchema.NAME);

    private final AiCallLogRepository repository;
    private final DatabaseClient databaseClient;

    @Override
    public Mono<AiCallRecord> save(AiCallRecord record) {
        return repository.save(AiCallLogEntity.from(record)).map(AiCallLogEntity::toRecord);
    }

    @Override
    public Flux<DocumentAiCost> perDocument(Instant from, Instant to) {
        return databaseClient.sql(PER_DOCUMENT_SQL)
                .bind("from", OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC))
                .bind("to", OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC))
                .map((row, meta) -> toCost(row))
                .all();
    }

    private static DocumentAiCost toCost(Row row) {
        return new DocumentAiCost(
                row.get("document_id", UUID.class),
                required(row.get("calls", Long.class)),
                required(row.get("unpriced_calls", Long.class)),
                required(row.get("input_tokens", Long.class)),
                required(row.get("output_tokens", Long.class)),
                row.get("cost_usd", BigDecimal.class),
                row.get("models", String.class),
                row.get("avg_confidence", Double.class),
                required(row.get("unverified_items", Long.class)),
                instant(row.get("first_call_at", OffsetDateTime.class)),
                instant(row.get("last_call_at", OffsetDateTime.class)));
    }

    private static long required(Long value) {
        return value == null ? 0L : value;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    @Override
    public Flux<Integer> extractOutputTokens(Instant from, Instant to) {
        return databaseClient.sql("SELECT output_tokens FROM " + BaymaxSchema.NAME + ".ai_call_log "
                        + "WHERE purpose = 'extract' "
                        + "AND created_at >= :from AND created_at < :to ORDER BY output_tokens")
                .bind("from", OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC))
                .bind("to", OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC))
                .map((row, meta) -> row.get("output_tokens", Integer.class)).all();
    }
}
