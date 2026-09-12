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
                   COUNT(*) FILTER (WHERE cost_usd IS NULL)          AS unpriced_calls,
                   COALESCE(SUM(input_tokens), 0)                    AS input_tokens,
                   COALESCE(SUM(output_tokens), 0)                   AS output_tokens,
                   SUM(cost_usd)                                     AS cost_usd,
                   STRING_AGG(DISTINCT provider || '/' || model, '|' ORDER BY provider || '/' || model) AS models,
                   AVG(confidence)                                   AS avg_confidence,
                   MIN(created_at)                                   AS first_call_at,
                   MAX(created_at)                                   AS last_call_at
            FROM %s.ai_call_log
            WHERE created_at >= :from AND created_at < :to
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
                instant(row.get("first_call_at", OffsetDateTime.class)),
                instant(row.get("last_call_at", OffsetDateTime.class)));
    }

    private static long required(Long value) {
        return value == null ? 0L : value;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
