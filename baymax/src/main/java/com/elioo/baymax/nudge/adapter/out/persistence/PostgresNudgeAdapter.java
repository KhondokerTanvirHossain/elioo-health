package com.elioo.baymax.nudge.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.nudge.application.port.out.NudgePort;
import com.elioo.baymax.nudge.domain.Nudge;
import com.elioo.baymax.nudge.domain.NudgeCount;
import com.elioo.baymax.nudge.domain.NudgeRule;
import com.elioo.baymax.nudge.domain.NudgeStatus;
import com.elioo.baymax.nudge.domain.NudgeUrgency;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PostgresNudgeAdapter implements NudgePort {

    private static final String T = BaymaxSchema.NAME + ".nudge";
    private static final String COLS = "id, family_id, patient_id, rule, trigger_key, urgency, status, drop_reason, vars_json::text AS vars_json, message_id, hold_until, created_at, resolved_at";

    private final DatabaseClient db;
    private final ObjectMapper mapper;

    @Override
    public Mono<Nudge> save(Nudge n) {
        var spec = db.sql("INSERT INTO " + T + " (family_id, patient_id, rule, trigger_key, urgency, status, drop_reason, vars_json, message_id, hold_until, created_at, resolved_at) "
                        + "VALUES (:family, :patient, :rule, :key, :urgency, :status, :reason, CAST(:vars AS jsonb), :message, :hold, :created, :resolved) RETURNING " + COLS)
                .bind("family", n.familyId()).bind("patient", n.patientId()).bind("rule", n.rule().dbValue()).bind("key", n.triggerKey())
                .bind("urgency", n.urgency().name().toLowerCase(Locale.ROOT)).bind("status", n.status().dbValue()).bind("created", at(n.createdAt()));
        spec = n.dropReason() == null ? spec.bindNull("reason", String.class) : spec.bind("reason", n.dropReason());
        spec = n.vars() == null ? spec.bindNull("vars", String.class) : spec.bind("vars", write(n.vars()));
        spec = n.messageId() == null ? spec.bindNull("message", UUID.class) : spec.bind("message", n.messageId());
        spec = n.holdUntil() == null ? spec.bindNull("hold", OffsetDateTime.class) : spec.bind("hold", at(n.holdUntil()));
        spec = n.resolvedAt() == null ? spec.bindNull("resolved", OffsetDateTime.class) : spec.bind("resolved", at(n.resolvedAt()));
        return spec.map((row, meta) -> from(row)).one();
    }

    @Override
    public Mono<Boolean> exists(UUID patientId, NudgeRule rule, String triggerKey) {
        return db.sql("SELECT count(*) FROM " + T + " WHERE patient_id = :p AND rule = :r AND trigger_key = :k")
                .bind("p", patientId).bind("r", rule.dbValue()).bind("k", triggerKey).map((row, meta) -> row.get(0, Long.class) > 0).one();
    }

    @Override
    public Mono<Boolean> consumeDeferred(UUID patientId, NudgeRule rule, String triggerKey, Instant at) {
        return db.sql("UPDATE " + T + " SET status = 'dropped', drop_reason = 'superseded_by_retry', resolved_at = :at "
                        + "WHERE patient_id = :p AND rule = :r AND trigger_key = :k AND status = 'deferred'")
                .bind("p", patientId).bind("r", rule.dbValue()).bind("k", triggerKey).bind("at", at(at))
                .fetch().rowsUpdated().map(n -> n > 0);
    }

    @Override
    public Mono<Long> countedSince(UUID patientId, Instant since) {
        return db.sql("SELECT count(*) FROM " + T + " WHERE patient_id = :p AND status IN ('sent', 'gated') AND COALESCE(resolved_at, created_at) >= :since")
                .bind("p", patientId).bind("since", at(since)).map((row, meta) -> row.get(0, Long.class)).one();
    }

    @Override
    public Mono<Nudge> latest(UUID patientId, NudgeRule rule) {
        return db.sql("SELECT " + COLS + " FROM " + T + " WHERE patient_id = :p AND rule = :r ORDER BY created_at DESC LIMIT 1")
                .bind("p", patientId).bind("r", rule.dbValue()).map((row, meta) -> from(row)).one();
    }

    @Override
    public Flux<Nudge> heldDueBy(Instant now) {
        return db.sql("SELECT " + COLS + " FROM " + T + " WHERE status = 'held' AND hold_until <= :now ORDER BY hold_until")
                .bind("now", at(now)).map((row, meta) -> from(row)).all();
    }

    @Override
    public Mono<Nudge> resolve(UUID nudgeId, NudgeStatus status, String dropReason, UUID messageId, Instant resolvedAt) {
        var spec = db.sql("UPDATE " + T + " SET status = :status, drop_reason = :reason, message_id = :message, resolved_at = :resolved, hold_until = NULL WHERE id = :id RETURNING " + COLS)
                .bind("status", status.dbValue()).bind("resolved", at(resolvedAt)).bind("id", nudgeId);
        spec = dropReason == null ? spec.bindNull("reason", String.class) : spec.bind("reason", dropReason);
        spec = messageId == null ? spec.bindNull("message", UUID.class) : spec.bind("message", messageId);
        return spec.map((row, meta) -> from(row)).one();
    }

    @Override
    public Flux<NudgeCount> counts(Instant from, Instant to) {
        return db.sql("SELECT family_id, rule, status, drop_reason, count(*) AS n FROM " + T + " WHERE created_at >= :from AND created_at < :to "
                        + "GROUP BY family_id, rule, status, drop_reason ORDER BY family_id, rule, status")
                .bind("from", at(from)).bind("to", at(to))
                .map((row, meta) -> new NudgeCount(row.get("family_id", UUID.class), NudgeRule.fromDbValue(row.get("rule", String.class)),
                        NudgeStatus.fromDbValue(row.get("status", String.class)), row.get("drop_reason", String.class), row.get("n", Long.class)))
                .all();
    }

    private Nudge from(Row row) {
        String vars = row.get("vars_json", String.class);
        return new Nudge(row.get("id", UUID.class), row.get("family_id", UUID.class), row.get("patient_id", UUID.class),
                NudgeRule.fromDbValue(row.get("rule", String.class)), row.get("trigger_key", String.class),
                NudgeUrgency.valueOf(row.get("urgency", String.class).toUpperCase(Locale.ROOT)), NudgeStatus.fromDbValue(row.get("status", String.class)),
                row.get("drop_reason", String.class), vars == null ? Map.of() : read(vars), row.get("message_id", UUID.class),
                PostgresNudgeDataAdapter.instant(row, "hold_until"), PostgresNudgeDataAdapter.instant(row, "created_at"), PostgresNudgeDataAdapter.instant(row, "resolved_at"));
    }

    private String write(Map<String, String> vars) {
        try {
            return mapper.writeValueAsString(vars);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, String> read(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
