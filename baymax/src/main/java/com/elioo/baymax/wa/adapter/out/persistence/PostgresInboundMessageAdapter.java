package com.elioo.baymax.wa.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.wa.application.port.out.InboundMessagePort;
import com.elioo.baymax.wa.domain.InboundMessage;
import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Component
@RequiredArgsConstructor
public class PostgresInboundMessageAdapter implements InboundMessagePort {

    private static final String T = BaymaxSchema.NAME + ".inbound_message";
    private static final String COLUMNS = "id, provider_key, family_id, patient_id, document_id, type, body, media_id, "
            + "provider_timestamp, received_at, handled_at, outcome";

    private final DatabaseClient db;

    @Override
    public Mono<InboundMessage> claim(InboundMessage m, Instant reclaimBefore) {
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
        return spec.map((row, meta) -> from(row)).one()
                .switchIfEmpty(Mono.defer(() -> reclaim(m.providerKey(), reclaimBefore)));
    }

    /**
     * The key is there: take it back only when an earlier instance claimed it and never answered. Row-locked by the
     * UPDATE, and the WHERE is re-read after any concurrent reclaim commits (received_at is then this instance's), so two
     * simultaneous re-deliveries reclaim it once. A message in flight here was received after {@code before}: never taken.
     */
    private Mono<InboundMessage> reclaim(String providerKey, Instant before) {
        if (before == null) {
            return Mono.empty();
        }
        return db.sql("UPDATE " + T + " SET received_at = now(), handled_at = NULL, outcome = NULL"
                        + " WHERE provider_key = :key AND received_at < :before"
                        + " AND (handled_at IS NULL OR outcome = '" + InboundMessage.CUT_OFF + "')"
                        + " RETURNING " + COLUMNS)
                .bind("key", providerKey).bind("before", at(before))
                .map((row, meta) -> from(row)).one()
                .doOnNext(row -> log.info("[baymax] wa inbound reclaimed: an earlier instance claimed it and never answered"));
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
        // the family too, when the claim did not know it (registered between the claim and this photo's handling): a row
        // holding a patient but no family would be missed by the family delete and block its patient_profile delete
        return db.sql("UPDATE " + T + " SET patient_id = :patient, document_id = :document,"
                        + " family_id = COALESCE(family_id, (SELECT family_id FROM " + BaymaxSchema.NAME
                        + ".patient_profile WHERE id = :patient)) WHERE provider_key = :key")
                .bind("patient", patientId).bind("document", documentId).bind("key", providerKey)
                .then();
    }

    @Override
    public Mono<Long> closeUnhandledBefore(Instant before) {
        // one statement: the rows it closes are exactly the rows it counts, and a second startup finds them closed
        return db.sql("UPDATE " + T + " SET handled_at = now(), outcome = '" + InboundMessage.CUT_OFF + "'"
                        + " WHERE handled_at IS NULL AND received_at < :before")
                .bind("before", at(before))
                .fetch().rowsUpdated();
    }

    @Override
    public Mono<Long> purgeUnknownSendersBefore(Instant before) {
        return db.sql("DELETE FROM " + T + " WHERE family_id IS NULL AND received_at < :before")
                .bind("before", at(before))
                .fetch().rowsUpdated();
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
