package com.elioo.baymax.wa.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.wa.application.port.out.InboundMessagePort;
import com.elioo.baymax.wa.domain.InboundMessage;
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
 * {@code baymax.inbound_message} (V22). The claim is one statement, {@code INSERT … ON CONFLICT DO NOTHING RETURNING}:
 * Postgres decides which of two simultaneous deliveries inserted the row, and the loser gets no row back — empty, not a
 * unique-constraint error — so a re-delivery is acknowledged and never answered twice.
 */
@Component
@RequiredArgsConstructor
public class PostgresInboundMessageAdapter implements InboundMessagePort {

    private static final String T = BaymaxSchema.NAME + ".inbound_message";
    private static final String COLUMNS = "id, provider_key, family_id, patient_id, document_id, type, body, media_id, "
            + "provider_timestamp, received_at, handled_at, outcome";

    private final DatabaseClient db;

    @Override
    public Mono<InboundMessage> claim(InboundMessage m) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("INSERT INTO " + T
                        + " (provider_key, family_id, type, body, media_id, provider_timestamp)"
                        + " VALUES (:key, :family, :type, :body, :media, :sent)"
                        + " ON CONFLICT ON CONSTRAINT inbound_message_provider_key_key DO NOTHING"
                        + " RETURNING " + COLUMNS)
                .bind("key", m.providerKey())
                .bind("type", m.type());
        spec = m.familyId() == null ? spec.bindNull("family", UUID.class) : spec.bind("family", m.familyId());
        spec = m.body() == null ? spec.bindNull("body", String.class) : spec.bind("body", m.body());
        spec = m.mediaId() == null ? spec.bindNull("media", String.class) : spec.bind("media", m.mediaId());
        spec = m.providerTimestamp() == null ? spec.bindNull("sent", OffsetDateTime.class)
                : spec.bind("sent", at(m.providerTimestamp()));
        return spec.map((row, meta) -> from(row)).one();
    }

    @Override
    public Mono<Void> markHandled(String providerKey, String outcome) {
        return db.sql("UPDATE " + T + " SET handled_at = now(), outcome = :outcome"
                        + " WHERE provider_key = :key AND handled_at IS NULL")
                .bind("outcome", outcome).bind("key", providerKey)
                .then();
    }

    @Override
    public Mono<Void> linkDocument(String providerKey, UUID patientId, UUID documentId) {
        return db.sql("UPDATE " + T + " SET patient_id = :patient, document_id = :document WHERE provider_key = :key")
                .bind("patient", patientId).bind("document", documentId).bind("key", providerKey)
                .then();
    }

    @Override
    public Mono<Long> countUnhandledBefore(Instant before) {
        return db.sql("SELECT count(*) FROM " + T + " WHERE handled_at IS NULL AND received_at < :before")
                .bind("before", at(before))
                .map((row, meta) -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    private static InboundMessage from(Row row) {
        return new InboundMessage(row.get("id", UUID.class), row.get("provider_key", String.class),
                row.get("family_id", UUID.class), row.get("patient_id", UUID.class), row.get("document_id", UUID.class),
                row.get("type", String.class), row.get("body", String.class), row.get("media_id", String.class),
                instant(row.get("provider_timestamp", OffsetDateTime.class)), instant(row.get("received_at", OffsetDateTime.class)),
                instant(row.get("handled_at", OffsetDateTime.class)), row.get("outcome", String.class));
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
