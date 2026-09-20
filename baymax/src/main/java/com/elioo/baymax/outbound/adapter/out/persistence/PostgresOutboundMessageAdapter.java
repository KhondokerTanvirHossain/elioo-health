package com.elioo.baymax.outbound.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.MessageCount;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PostgresOutboundMessageAdapter implements OutboundMessagePort {

    private static final String T = BaymaxSchema.NAME + ".outbound_message";
    private static final String COLS = "id, family_id, patient_id, document_id, kind, urgency, urgency_reasons, body, gate_status, "
            + "reviewer, reject_reason, decided_at, sent_at, created_at";

    private final DatabaseClient db;

    @Override
    public Mono<OutboundMessage> save(OutboundMessage m) {
        var spec = db.sql("INSERT INTO " + T + " (family_id, patient_id, document_id, kind, urgency, urgency_reasons, body, gate_status, sent_at, created_at) "
                        + "VALUES (:family, :patient, :document, :kind, :urgency, :reasons, :body, :status, :sent, :created) RETURNING " + COLS)
                .bind("family", m.familyId()).bind("kind", m.kind().dbValue()).bind("urgency", m.urgency().dbValue())
                .bind("status", m.gateStatus().dbValue()).bind("created", at(m.createdAt()))
                .bind("reasons", String.join(",", m.urgencyReasons() == null ? List.of() : m.urgencyReasons()));
        spec = m.patientId() == null ? spec.bindNull("patient", UUID.class) : spec.bind("patient", m.patientId());
        spec = m.documentId() == null ? spec.bindNull("document", UUID.class) : spec.bind("document", m.documentId());
        spec = m.body() == null ? spec.bindNull("body", String.class) : spec.bind("body", m.body());
        spec = m.sentAt() == null ? spec.bindNull("sent", OffsetDateTime.class) : spec.bind("sent", at(m.sentAt()));
        return spec.map((row, meta) -> from(row)).one();
    }

    @Override
    public Mono<OutboundMessage> find(UUID id) {
        return db.sql("SELECT " + COLS + " FROM " + T + " WHERE id = :id").bind("id", id).map((row, meta) -> from(row)).one();
    }

    @Override
    public Mono<OutboundMessage> decide(UUID id, OutboundMessage.GateStatus status, String reviewer, String reason, Instant decidedAt, Instant sentAt) {
        var spec = db.sql("UPDATE " + T + " SET gate_status = :status, reviewer = :reviewer, reject_reason = :reason, decided_at = :decided, "
                        + "sent_at = :sent WHERE id = :id RETURNING " + COLS)
                .bind("status", status.dbValue()).bind("reviewer", reviewer).bind("decided", at(decidedAt)).bind("id", id);
        spec = reason == null ? spec.bindNull("reason", String.class) : spec.bind("reason", reason);
        spec = sentAt == null ? spec.bindNull("sent", OffsetDateTime.class) : spec.bind("sent", at(sentAt));
        return spec.map((row, meta) -> from(row)).one();
    }

    @Override
    public Mono<Void> markSent(UUID id, Instant sentAt) {
        return db.sql("UPDATE " + T + " SET sent_at = :sent WHERE id = :id").bind("sent", at(sentAt)).bind("id", id).then();
    }

    /**
     * sent_at is bound to the timestamp ONLY when the provider accepted the message; every other outcome binds
     * NULL. That is the whole point of V13 — a reviewer reads sent_at as "the family received it".
     */
    @Override
    public Mono<OutboundMessage> recordDelivery(UUID id, com.elioo.baymax.outbound.domain.DeliveryOutcome outcome, Instant sentAt) {
        return db.sql("UPDATE " + T + " SET delivery_status = :status, provider_message_id = :wamid, "
                        + "delivery_error = :error, sent_at = :sent WHERE id = :id")
                .bind("status", outcome.status() == null ? io.r2dbc.spi.Parameters.in(io.r2dbc.spi.R2dbcType.VARCHAR)
                        : io.r2dbc.spi.Parameters.in(io.r2dbc.spi.R2dbcType.VARCHAR, outcome.status().dbValue()))
                .bind("wamid", outcome.messageId() == null ? io.r2dbc.spi.Parameters.in(io.r2dbc.spi.R2dbcType.VARCHAR)
                        : io.r2dbc.spi.Parameters.in(io.r2dbc.spi.R2dbcType.VARCHAR, outcome.messageId()))
                .bind("error", outcome.error() == null ? io.r2dbc.spi.Parameters.in(io.r2dbc.spi.R2dbcType.VARCHAR)
                        : io.r2dbc.spi.Parameters.in(io.r2dbc.spi.R2dbcType.VARCHAR, outcome.error()))
                .bind("sent", outcome.wasSent() ? io.r2dbc.spi.Parameters.in(io.r2dbc.spi.R2dbcType.TIMESTAMP_WITH_TIME_ZONE, at(sentAt))
                        : io.r2dbc.spi.Parameters.in(io.r2dbc.spi.R2dbcType.TIMESTAMP_WITH_TIME_ZONE))
                .bind("id", id)
                .fetch().rowsUpdated()
                .then(find(id));
    }

    @Override
    public Flux<OutboundMessage> pending() {
        return db.sql("SELECT " + COLS + " FROM " + T + " WHERE gate_status = 'pending' ORDER BY created_at").map((row, meta) -> from(row)).all();
    }

    @Override
    public Mono<OutboundMessage> latestDeliverable(UUID documentId) {
        return db.sql("SELECT " + COLS + " FROM " + T + " WHERE document_id = :d AND gate_status IN ('released', 'approved') AND body IS NOT NULL "
                        + "ORDER BY created_at DESC LIMIT 1").bind("d", documentId).map((row, meta) -> from(row)).one();
    }

    @Override
    public Flux<MessageCount> counts(Instant from, Instant to) {
        return db.sql("SELECT urgency, gate_status, count(*) AS n FROM " + T + " WHERE created_at >= :from AND created_at < :to "
                        + "GROUP BY urgency, gate_status ORDER BY urgency, gate_status")
                .bind("from", at(from)).bind("to", at(to))
                .map((row, meta) -> new MessageCount(Urgency.fromDbValue(row.get("urgency", String.class)),
                        OutboundMessage.GateStatus.fromDbValue(row.get("gate_status", String.class)), row.get("n", Long.class)))
                .all();
    }

    private static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(Row row, String col) {
        OffsetDateTime v = row.get(col, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static OutboundMessage from(Row row) {
        String reasons = row.get("urgency_reasons", String.class);
        return new OutboundMessage(row.get("id", UUID.class), row.get("family_id", UUID.class), row.get("patient_id", UUID.class),
                row.get("document_id", UUID.class), OutboundMessage.Kind.fromDbValue(row.get("kind", String.class)),
                Urgency.fromDbValue(row.get("urgency", String.class)),
                reasons == null || reasons.isBlank() ? List.of() : Arrays.asList(reasons.split(",")),
                row.get("body", String.class), OutboundMessage.GateStatus.fromDbValue(row.get("gate_status", String.class)),
                row.get("reviewer", String.class), row.get("reject_reason", String.class),
                instant(row, "decided_at"), instant(row, "sent_at"), instant(row, "created_at"));
    }

    @Override
    public Mono<Long> consecutiveRetakes(UUID familyId) {
        return db.sql("SELECT count(*) FROM " + T + " WHERE family_id = :f AND kind = 'retake' AND created_at > "
                        + "COALESCE((SELECT max(created_at) FROM " + T + " WHERE family_id = :f AND kind <> 'retake'), 'epoch'::timestamptz)")
                .bind("f", familyId).map((row, meta) -> row.get(0, Long.class)).one();
    }
}
