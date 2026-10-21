package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.web.application.port.out.NowAckPort;
import com.elioo.baymax.web.domain.NowSeen;
import io.r2dbc.spi.R2dbcException;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The only {@link NowAckPort}. {@link #seenOf} reads stored columns only, and the digest and the release state are
 * computed by Postgres from them, so the reasons never leave the database as text; the latest explanation is the
 * timeline's own pick ({@code ORDER BY created_at DESC, id DESC}). {@link #record} writes {@code now_ack} and nothing else.
 */
@Component
@RequiredArgsConstructor
public class PostgresNowAckAdapter implements NowAckPort {

    private static final String S = BaymaxSchema.NAME;

    private static final String SEEN = """
            SELECT d.id, d.patient_id, d.urgency,
                   encode(sha256(convert_to(coalesce(d.urgency_reasons, '[]'::jsonb)::text, 'UTF8')), 'hex') AS reasons_digest,
                   coalesce((SELECT CASE m.gate_status WHEN 'approved' THEN 'released' ELSE m.gate_status END
                             FROM %1$s.outbound_message m
                             WHERE m.document_id = d.id AND m.kind = 'explanation'
                             ORDER BY m.created_at DESC, m.id DESC LIMIT 1), 'none') AS release_state
            FROM %1$s.document d
            WHERE d.id = ANY(:ids)
            """.formatted(S);

    private static final String ACKED = """
            SELECT document_id AS id, patient_id, urgency, reasons_digest, release_state
            FROM %1$s.now_ack WHERE family_id = :viewer AND document_id = ANY(:ids)
            """.formatted(S);

    /**
     * Only while the paper is still there: a paper deleted between the banner read and the tap records nothing, instead of
     * failing the ack on {@code now_ack_document_id_patient_id_fkey}. A delete that has not committed yet still passes the
     * EXISTS, and its commit then fails the insert on that key, which {@link #record} also reads as "gone".
     */
    private static final String RECORD = """
            INSERT INTO %1$s.now_ack (family_id, patient_id, document_id, urgency, reasons_digest, release_state, acked_at)
            SELECT CAST(:viewer AS uuid), CAST(:patient AS uuid), CAST(:document AS uuid), CAST(:urgency AS varchar),
                   CAST(:digest AS char(64)), CAST(:state AS varchar), CAST(:at AS timestamptz)
            WHERE EXISTS (SELECT 1 FROM %1$s.document WHERE id = :document AND patient_id = :patient)
            ON CONFLICT DO NOTHING
            """.formatted(S);

    /** Postgres's foreign_key_violation: the only refusal that means "the paper (or its patient) is gone". */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private final DatabaseClient db;

    @Override
    public Flux<NowSeen> seenOf(Collection<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Flux.empty();
        }
        return db.sql(SEEN).bind("ids", documentIds.toArray(UUID[]::new)).map(PostgresNowAckAdapter::seen).all();
    }

    @Override
    public Flux<NowSeen> ackedBy(UUID viewerFamilyId, Collection<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Flux.empty();
        }
        return db.sql(ACKED).bind("viewer", viewerFamilyId).bind("ids", documentIds.toArray(UUID[]::new))
                .map(PostgresNowAckAdapter::seen).all();
    }

    @Override
    public Mono<Long> record(UUID viewerFamilyId, List<NowSeen> seen, Instant at) {
        OffsetDateTime when = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        return Flux.fromIterable(seen)
                .concatMap(s -> db.sql(RECORD)
                        .bind("viewer", viewerFamilyId).bind("patient", s.patientId()).bind("document", s.documentId())
                        .bind("urgency", s.urgency()).bind("digest", s.reasonsDigest()).bind("state", s.releaseState())
                        .bind("at", when)
                        .fetch().rowsUpdated()
                        .onErrorResume(PostgresNowAckAdapter::paperGone, e -> Mono.just(0L)))
                .reduce(0L, Long::sum);
    }

    private static boolean paperGone(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof R2dbcException r && FOREIGN_KEY_VIOLATION.equals(r.getSqlState())) {
                return true;
            }
        }
        return false;
    }

    private static NowSeen seen(Readable row) {
        return new NowSeen(row.get("id", UUID.class), row.get("patient_id", UUID.class), row.get("urgency", String.class),
                row.get("reasons_digest", String.class), row.get("release_state", String.class));
    }
}
