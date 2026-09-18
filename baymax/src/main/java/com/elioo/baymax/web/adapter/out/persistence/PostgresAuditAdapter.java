package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.FamilyViewCount;
import com.elioo.baymax.web.domain.OtpDailyCount;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static com.elioo.baymax.web.adapter.out.persistence.PostgresOtpStoreAdapter.at;

@Component
@RequiredArgsConstructor
public class PostgresAuditAdapter implements AuditPort {

    private static final String T = BaymaxSchema.NAME + ".audit_event";

    private final DatabaseClient db;

    @Override
    public Mono<Void> record(AuditEvent e) {
        var spec = db.sql("INSERT INTO " + T + " (kind, phone_hash, family_id, patient_id, document_id, created_at) "
                        + "VALUES (:kind, :phone, :family, :patient, :document, :at)")
                .bind("kind", e.kind().dbValue()).bind("at", at(e.at()));
        spec = e.phoneHash() == null ? spec.bindNull("phone", String.class) : spec.bind("phone", e.phoneHash());
        spec = e.familyId() == null ? spec.bindNull("family", UUID.class) : spec.bind("family", e.familyId());
        spec = e.patientId() == null ? spec.bindNull("patient", UUID.class) : spec.bind("patient", e.patientId());
        spec = e.documentId() == null ? spec.bindNull("document", UUID.class) : spec.bind("document", e.documentId());
        return spec.then();
    }

    @Override
    public Flux<OtpDailyCount> otpPerNumberPerDay(Instant from, Instant to) {
        return db.sql("SELECT (created_at AT TIME ZONE 'UTC')::date AS day, phone_hash, "
                        + "count(*) FILTER (WHERE kind = 'otp_request') AS requests, "
                        + "count(*) FILTER (WHERE kind = 'otp_verify_ok') AS ok, "
                        + "count(*) FILTER (WHERE kind IN ('otp_verify_fail', 'otp_burned')) AS failed "
                        + "FROM " + T + " WHERE phone_hash IS NOT NULL AND created_at >= :from AND created_at < :to "
                        + "GROUP BY 1, 2 ORDER BY 1, 2")
                .bind("from", at(from)).bind("to", at(to))
                .map((row, meta) -> new OtpDailyCount(row.get("day", LocalDate.class), row.get("phone_hash", String.class),
                        row.get("requests", Long.class), row.get("ok", Long.class), row.get("failed", Long.class)))
                .all();
    }

    @Override
    public Flux<FamilyViewCount> viewsPerFamily(Instant from, Instant to) {
        return db.sql("SELECT family_id, count(*) FILTER (WHERE kind = 'timeline_view') AS timeline, "
                        + "count(*) FILTER (WHERE kind = 'document_view') AS documents "
                        + "FROM " + T + " WHERE family_id IS NOT NULL AND created_at >= :from AND created_at < :to "
                        + "GROUP BY family_id ORDER BY family_id")
                .bind("from", at(from)).bind("to", at(to))
                .map((row, meta) -> new FamilyViewCount(row.get("family_id", UUID.class),
                        row.get("timeline", Long.class), row.get("documents", Long.class)))
                .all();
    }
}
