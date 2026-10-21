package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.storage.domain.StorageKeys;
import com.elioo.baymax.web.application.port.out.TimelineReadPort;
import com.elioo.baymax.web.domain.TimelineCursor;
import com.elioo.baymax.web.domain.TimelineEntry;
import com.elioo.baymax.web.domain.TimelineFilter;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The only {@link TimelineReadPort}: one SQL statement per page over stored columns, schema-qualified, read only. The
 * order is {@code timeline_date} (D2: the printed date, else for a retake or an undated paper the Dhaka upload day; generated
 * by Postgres and read, never re-derived here), then upload instant, then id, and the
 * cursor compares the same triple, so no row is skipped or repeated at a page boundary. The urgency, the verdict
 * counts and the review state are the stored ones; nothing here assesses.
 */
@Component
@RequiredArgsConstructor
public class PostgresTimelineReadAdapter implements TimelineReadPort {

    private static final String S = BaymaxSchema.NAME;

    /** Every column a {@link TimelineEntry} needs; the unverified sum is VerifiedItems.Unverified.total()'s four sections. */
    private static final String COLUMNS = """
            SELECT d.id, d.family_id, d.patient_id, d.document_type, d.facility, d.status, d.status_reason_code,
                   d.timeline_date, d.created_at, d.urgency,
                   d.unverified_values + d.unverified_medicines + d.unverified_follow_up + d.unverified_clinical_context AS unverified,
                   d.timeline_date_is_upload_day AS date_is_fallback, d.timeline_date_uncertain AS date_uncertain,
                   (SELECT count(*) FROM %1$s.observation o WHERE o.document_id = d.id) AS values_n,
                   (SELECT count(*) FROM %1$s.observation o WHERE o.document_id = d.id AND o.verdict IN ('outside', 'critical')) AS outside_n,
                   (SELECT count(*) FROM %1$s.medication_event m WHERE m.document_id = d.id) AS medicines_n,
                   (SELECT count(*) FROM %1$s.follow_up f WHERE f.document_id = d.id) AS follow_ups_n,
                   (SELECT m.gate_status FROM %1$s.outbound_message m
                     WHERE m.document_id = d.id AND m.kind = 'explanation' ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS review_state
            FROM %1$s.document d
            """.formatted(S);

    private static final String PAGE = COLUMNS + """
            WHERE d.patient_id = :patient
              AND (d.timeline_date, d.created_at, d.id) < (:date, :created, :id)
              %s
            ORDER BY d.timeline_date DESC, d.created_at DESC, d.id DESC
            LIMIT :limit
            """;

    /** Plan B3: the calendar's month, by the filing day, in the page's order. */
    private static final String MONTH = COLUMNS + """
            WHERE d.patient_id = :patient AND d.timeline_date BETWEEN :first AND :last
            ORDER BY d.timeline_date DESC, d.created_at DESC, d.id DESC
            """;

    private static final String ATTENTION = COLUMNS + """
            WHERE d.patient_id = :patient AND d.status = 'DONE' AND d.urgency IN ('now', 'this_week') AND d.created_at >= :since
            ORDER BY CASE d.urgency WHEN 'now' THEN 0 ELSE 1 END, d.timeline_date DESC, d.created_at DESC, d.id DESC
            LIMIT :limit
            """;

    /**
     * C4: the papers not finished yet — being read, or waiting for a retake — newest upload first. Never DONE or FAILED.
     * D1 (migration retake_replaces): a retake whose new photo was sent from its page ({@code replaces_document_id}) is no longer waiting, unless
     * that photo FAILED on our side: then the family still owes a readable one. The new photo is its own card (being read,
     * or a retake itself), so the stack holds one card per paper the family is asked about, never the stale one beside it.
     */
    private static final String IN_FLIGHT = COLUMNS + """
            WHERE d.patient_id = :patient AND d.status IN ('RECEIVED', 'PROCESSING', 'NEEDS_RETAKE') AND d.created_at >= :since
              AND %1$s
            ORDER BY d.created_at DESC, d.id DESC
            """.formatted(DocumentSql.NOT_REPLACED);

    private final DatabaseClient db;

    @Override
    public Flux<TimelineEntry> page(UUID patientId, TimelineCursor after, TimelineFilter filter, int limit) {
        // the filter fragment is a fixed constant per enum value: request text never reaches the SQL
        return db.sql(PAGE.formatted(filter.sql()))
                .bind("patient", patientId)
                .bind("date", after.date())
                .bind("created", OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC))
                .bind("id", after.id())
                .bind("limit", limit)
                .map(PostgresTimelineReadAdapter::entry).all();
    }

    @Override
    public Flux<TimelineEntry> month(UUID patientId, LocalDate first, LocalDate last) {
        return db.sql(MONTH)
                .bind("patient", patientId)
                .bind("first", first)
                .bind("last", last)
                .map(PostgresTimelineReadAdapter::entry).all();
    }

    @Override
    public Flux<TimelineEntry> attention(UUID patientId, Instant since, int limit) {
        return db.sql(ATTENTION)
                .bind("patient", patientId)
                .bind("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .bind("limit", limit)
                .map(PostgresTimelineReadAdapter::entry).all();
    }

    @Override
    public Flux<TimelineEntry> inFlightAndRetakes(UUID patientId, Instant since) {
        return db.sql(IN_FLIGHT)
                .bind("patient", patientId)
                .bind("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .map(PostgresTimelineReadAdapter::entry).all();
    }

    private static TimelineEntry entry(Readable row) {
        UUID id = row.get("id", UUID.class);
        String urgency = row.get("urgency", String.class);
        String status = row.get("status", String.class);
        return new TimelineEntry(id, row.get("timeline_date", LocalDate.class),
                Boolean.TRUE.equals(row.get("date_is_fallback", Boolean.class)),
                row.get("document_type", String.class), row.get("facility", String.class), status,
                count(row, "values_n"), count(row, "medicines_n"), count(row, "follow_ups_n"), count(row, "unverified"),
                StorageKeys.page(row.get("family_id", UUID.class), row.get("patient_id", UUID.class), id, 1),
                row.get("created_at", OffsetDateTime.class).toInstant(),
                urgency == null ? null : Urgency.fromDbValue(urgency), count(row, "outside_n"),
                row.get("review_state", String.class),
                "NEEDS_RETAKE".equals(status) ? row.get("status_reason_code", String.class) : null,
                Boolean.TRUE.equals(row.get("date_uncertain", Boolean.class)));
    }

    private static int count(Readable row, String column) {
        Number n = row.get(column, Number.class);
        return n == null ? 0 : n.intValue();
    }
}
