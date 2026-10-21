package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.web.application.port.out.HealthReadPort;
import com.elioo.baymax.web.domain.LatestPrescription;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only {@link HealthReadPort}: plain SQL over stored columns, schema-qualified, read only. Every date is the paper's
 * filing day as Postgres generates it ({@code timeline_date}, {@code timeline_date_is_upload_day},
 * {@code timeline_date_uncertain}, D2), read and never re-derived; the order is the timeline's own
 * ({@code timeline_date}, then {@code created_at}, then id).
 */
@Component
@RequiredArgsConstructor
public class PostgresHealthReadAdapter implements HealthReadPort {

    private static final String S = BaymaxSchema.NAME;

    private static final String LAB_POINTS = """
            SELECT o.id, o.document_id, o.name, o.canonical_name, o.value, o.unit, o.ref_low, o.ref_high, o.verdict, o.verdict_side,
                   o.crop_key, o.source_line,
                   d.timeline_date, d.timeline_date_is_upload_day AS date_is_fallback, d.timeline_date_uncertain AS date_uncertain
            FROM %1$s.observation o JOIN %1$s.document d ON d.id = o.document_id
            WHERE o.patient_id = :patient AND d.patient_id = :patient AND d.status = 'DONE' AND o.canonical_name IS NOT NULL
            ORDER BY d.timeline_date DESC, d.created_at DESC, d.id DESC, o.id
            """.formatted(S);

    /** The newest DONE prescription, whatever its medicine count: never the newest one that happens to list medicines. */
    private static final String LATEST = """
            SELECT d.id, d.created_at, d.timeline_date, d.timeline_date_is_upload_day AS date_is_fallback,
                   d.timeline_date_uncertain AS date_uncertain
            FROM %1$s.document d
            WHERE d.patient_id = :patient AND d.status = 'DONE' AND d.document_type = 'prescription'
            ORDER BY d.timeline_date DESC, d.created_at DESC, d.id DESC
            LIMIT 1
            """.formatted(S);

    /** The paper's medicines as written, in the order the paper page lists them (MedicationEventRepository). */
    private static final String MEDICINES = """
            SELECT m.name, m.dose_text, m.frequency_text, m.timing_text, m.duration_text, m.crop_key
            FROM %1$s.medication_event m
            WHERE m.document_id = :document
            ORDER BY m.name, m.id
            """.formatted(S);

    /**
     * The newest paper uploaded after {@code :after} that may be a prescription and is not read yet: being read (its type
     * is not known), or waiting for a retake as a prescription or with no type.
     */
    private static final String NEWER_NOT_READ = """
            SELECT d.id
            FROM %1$s.document d
            WHERE d.patient_id = :patient AND d.created_at > :after
              AND (d.status IN ('RECEIVED', 'PROCESSING')
                   OR (d.status = 'NEEDS_RETAKE' AND (d.document_type = 'prescription' OR d.document_type IS NULL)))
            ORDER BY d.created_at DESC, d.id DESC
            LIMIT 1
            """.formatted(S);

    /** Before every upload: a timestamptz Postgres takes, unlike Instant.MIN. */
    private static final OffsetDateTime BEFORE_ANY = OffsetDateTime.parse("1900-01-01T00:00:00Z");

    private final DatabaseClient db;

    @Override
    public Flux<Map<String, Object>> labPoints(UUID patientId) {
        return db.sql(LAB_POINTS).bind("patient", patientId).map(PostgresHealthReadAdapter::point).all();
    }

    @Override
    public Mono<LatestPrescription> latestPrescription(UUID patientId) {
        return db.sql(LATEST).bind("patient", patientId)
                .map((row, meta) -> new Latest(row.get("id", UUID.class), row.get("created_at", OffsetDateTime.class),
                        row.get("timeline_date", LocalDate.class), Boolean.TRUE.equals(row.get("date_is_fallback", Boolean.class)),
                        Boolean.TRUE.equals(row.get("date_uncertain", Boolean.class))))
                .one()
                .flatMap(latest -> Mono.zip(medicines(latest.id()), newerNotRead(patientId, latest.createdAt()))
                        .map(t -> new LatestPrescription(latest.id(), latest.date(), latest.uploadDay(), latest.uncertain(), t.getT1(), t.getT2())))
                .switchIfEmpty(Mono.defer(() -> newerNotRead(patientId, BEFORE_ANY).map(LatestPrescription::none)));
    }

    private record Latest(UUID id, OffsetDateTime createdAt, LocalDate date, boolean uploadDay, boolean uncertain) {
    }

    private Mono<List<Map<String, Object>>> medicines(UUID documentId) {
        return db.sql(MEDICINES).bind("document", documentId)
                .map(PostgresHealthReadAdapter::medicine).all().collectList();
    }

    private Mono<Optional<UUID>> newerNotRead(UUID patientId, OffsetDateTime after) {
        return db.sql(NEWER_NOT_READ).bind("patient", patientId).bind("after", after)
                .map((row, meta) -> row.get("id", UUID.class)).one()
                .map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    private static Map<String, Object> point(Readable row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("id", UUID.class));
        m.put("document_id", row.get("document_id", UUID.class));
        for (String column : List.of("name", "canonical_name", "value", "unit", "ref_low", "ref_high", "verdict", "verdict_side",
                "crop_key", "source_line")) {
            String v = row.get(column, String.class);
            if (v != null) {
                m.put(column, v);
            }
        }
        m.put("date", row.get("timeline_date", LocalDate.class));
        m.put("date_is_fallback", Boolean.TRUE.equals(row.get("date_is_fallback", Boolean.class)));
        m.put("date_uncertain", Boolean.TRUE.equals(row.get("date_uncertain", Boolean.class)));
        return m;
    }

    private static Map<String, Object> medicine(Readable row) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String column : List.of("name", "dose_text", "frequency_text", "timing_text", "duration_text", "crop_key")) {
            String v = row.get(column, String.class);
            if (v != null) {
                m.put(column, v);
            }
        }
        return m;
    }
}
