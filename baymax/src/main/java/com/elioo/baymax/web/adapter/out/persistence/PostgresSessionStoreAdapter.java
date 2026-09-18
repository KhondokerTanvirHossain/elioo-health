package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.web.application.port.out.SessionStorePort;
import com.elioo.baymax.web.domain.WebSession;
import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.out.persistence.PostgresOtpStoreAdapter.at;
import static com.elioo.baymax.web.adapter.out.persistence.PostgresOtpStoreAdapter.instant;

@Component
@RequiredArgsConstructor
public class PostgresSessionStoreAdapter implements SessionStorePort {

    private static final String T = BaymaxSchema.NAME + ".web_session";
    private static final String COLUMNS = "id, family_id, created_at, last_seen_at, expires_at";

    private final DatabaseClient db;

    @Override
    public Mono<WebSession> create(String tokenHash, UUID familyId, Instant now, Instant expiresAt) {
        return db.sql("INSERT INTO " + T + " (token_hash, family_id, created_at, last_seen_at, expires_at) "
                        + "VALUES (:token, :family, :now, :now, :expires) RETURNING " + COLUMNS)
                .bind("token", tokenHash).bind("family", familyId).bind("now", at(now)).bind("expires", at(expiresAt))
                .map((row, meta) -> fromRow(row)).one();
    }

    @Override
    public Mono<WebSession> findLive(String tokenHash, Instant now) {
        return db.sql("SELECT " + COLUMNS + " FROM " + T + " WHERE token_hash = :token AND revoked_at IS NULL AND expires_at > :now")
                .bind("token", tokenHash).bind("now", at(now))
                .map((row, meta) -> fromRow(row)).one();
    }

    @Override
    public Mono<Void> touch(UUID sessionId, Instant now, Instant expiresAt) {
        return db.sql("UPDATE " + T + " SET last_seen_at = :now, expires_at = :expires WHERE id = :id")
                .bind("now", at(now)).bind("expires", at(expiresAt)).bind("id", sessionId).then();
    }

    @Override
    public Mono<Void> revoke(String tokenHash, Instant at) {
        return db.sql("UPDATE " + T + " SET revoked_at = :at WHERE token_hash = :token AND revoked_at IS NULL")
                .bind("at", at(at)).bind("token", tokenHash).then();
    }

    private static WebSession fromRow(Row row) {
        return new WebSession(row.get("id", UUID.class), row.get("family_id", UUID.class),
                instant(row, "created_at"), instant(row, "last_seen_at"), instant(row, "expires_at"));
    }
}
