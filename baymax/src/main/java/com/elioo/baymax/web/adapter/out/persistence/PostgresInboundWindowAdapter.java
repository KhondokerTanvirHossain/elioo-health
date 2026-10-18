package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.web.application.port.out.InboundWindowPort;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.out.persistence.PostgresOtpStoreAdapter.at;

@Component
@RequiredArgsConstructor
public class PostgresInboundWindowAdapter implements InboundWindowPort {

    private static final String T = BaymaxSchema.NAME + ".wa_last_inbound";

    private final DatabaseClient db;

    @Override
    public Mono<Void> recordInbound(String phoneHash, UUID familyId, Instant at) {
        // GREATEST: two callbacks can arrive out of order, and an older one must never shorten the window
        return db.sql("INSERT INTO " + T + " (phone_hash, family_id, last_inbound_at) VALUES (:phone, :family, :at) "
                        + "ON CONFLICT (phone_hash) DO UPDATE SET family_id = EXCLUDED.family_id, "
                        + "last_inbound_at = GREATEST(" + T + ".last_inbound_at, EXCLUDED.last_inbound_at)")
                .bind("phone", phoneHash).bind("family", familyId).bind("at", at(at))
                .then();
    }

    @Override
    public Mono<Instant> lastInbound(String phoneHash) {
        return db.sql("SELECT last_inbound_at FROM " + T + " WHERE phone_hash = :phone")
                .bind("phone", phoneHash)
                .map((row, meta) -> row.get(0, OffsetDateTime.class).toInstant())
                .one();
    }
}
