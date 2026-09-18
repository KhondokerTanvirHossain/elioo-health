package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.web.application.port.out.OtpStorePort;
import com.elioo.baymax.web.domain.OtpCode;
import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PostgresOtpStoreAdapter implements OtpStorePort {

    private static final String T = BaymaxSchema.NAME + ".otp_code";
    private static final String COLUMNS = "id, phone_hash, code_hash, family_id, expires_at, attempts, consumed_at, burned_at, created_at";

    private final DatabaseClient db;

    @Override
    public Mono<OtpCode> create(OtpCode c) {
        var spec = db.sql("INSERT INTO " + T + " (phone_hash, code_hash, family_id, expires_at, attempts, created_at) "
                        + "VALUES (:phone, :code, :family, :expires, 0, :created) RETURNING " + COLUMNS)
                .bind("phone", c.phoneHash())
                .bind("code", c.codeHash())
                .bind("expires", at(c.expiresAt()))
                .bind("created", at(c.createdAt()));
        spec = c.familyId() == null ? spec.bindNull("family", UUID.class) : spec.bind("family", c.familyId());
        return spec.map((row, meta) -> fromRow(row)).one();
    }

    @Override
    public Mono<Long> countLive(String phoneHash, Instant since, Instant now) {
        return db.sql("SELECT count(*) FROM " + T + " WHERE phone_hash = :phone AND created_at >= :since "
                        + "AND consumed_at IS NULL AND burned_at IS NULL AND expires_at > :now")
                .bind("phone", phoneHash).bind("since", at(since)).bind("now", at(now))
                .map((row, meta) -> row.get(0, Long.class)).one();
    }

    @Override
    public Mono<OtpCode> latestLive(String phoneHash, Instant now) {
        return db.sql("SELECT " + COLUMNS + " FROM " + T + " WHERE phone_hash = :phone "
                        + "AND consumed_at IS NULL AND burned_at IS NULL AND expires_at > :now "
                        + "ORDER BY created_at DESC LIMIT 1")
                .bind("phone", phoneHash).bind("now", at(now))
                .map((row, meta) -> fromRow(row)).one();
    }

    @Override
    public Mono<Void> recordAttempt(UUID codeId, int attempts, Instant burnedAt) {
        var spec = db.sql("UPDATE " + T + " SET attempts = :attempts, burned_at = :burned WHERE id = :id")
                .bind("attempts", attempts).bind("id", codeId);
        spec = burnedAt == null ? spec.bindNull("burned", OffsetDateTime.class) : spec.bind("burned", at(burnedAt));
        return spec.then();
    }

    @Override
    public Mono<Void> consume(UUID codeId, Instant at) {
        return db.sql("UPDATE " + T + " SET consumed_at = :at WHERE id = :id").bind("at", at(at)).bind("id", codeId).then();
    }

    static OffsetDateTime at(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    static Instant instant(Row row, String col) {
        OffsetDateTime v = row.get(col, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static OtpCode fromRow(Row row) {
        return new OtpCode(row.get("id", UUID.class), row.get("phone_hash", String.class), row.get("code_hash", String.class),
                row.get("family_id", UUID.class), instant(row, "expires_at"), row.get("attempts", Integer.class),
                instant(row, "consumed_at"), instant(row, "burned_at"), instant(row, "created_at"));
    }
}
