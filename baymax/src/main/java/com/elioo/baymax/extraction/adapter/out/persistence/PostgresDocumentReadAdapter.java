package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.extraction.application.port.out.DocumentReadPort;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The only {@link DocumentReadPort} implementation: plain SQL over {@code document}, schema-qualified, read only. */
@Component
@RequiredArgsConstructor
public class PostgresDocumentReadAdapter implements DocumentReadPort {

    private static final String S = BaymaxSchema.NAME;
    /** A bound on one marker's history for one patient; the sheet shows ten after the unit fold (ResultService). */
    static final int READINGS_SCANNED = 200;

    private final DatabaseClient db;

    /**
     * This patient's NEEDS_RETAKE papers sent after their last finished one (DONE or FAILED) and up to this one. A paper
     * still being read neither counts nor ends the streak. Per patient: a sibling's retake is not this paper's.
     */
    @Override
    public Mono<Integer> retakeOrdinal(UUID documentId) {
        return db.sql("SELECT CASE WHEN me.status <> 'NEEDS_RETAKE' THEN 0 ELSE (SELECT count(*) FROM " + S + ".document d "
                        + "WHERE d.patient_id = me.patient_id AND d.status = 'NEEDS_RETAKE' AND d.created_at <= me.created_at "
                        + "AND d.created_at > coalesce((SELECT max(x.created_at) FROM " + S + ".document x WHERE x.patient_id = me.patient_id "
                        + "AND x.status IN ('DONE', 'FAILED') AND x.created_at < me.created_at), '-infinity'::timestamptz)) END AS n "
                        + "FROM " + S + ".document me WHERE me.id = :id")
                .bind("id", documentId)
                .map((row, meta) -> row.get("n", Long.class).intValue()).one().defaultIfEmpty(0);
    }

    /**
     * This patient's readings of one marker on DONE papers that come before {@code beforeDocumentId} in the timeline's
     * own order (timeline_date, created_at, id), newest first. The observation's patient_id is the patient filter: a
     * sibling's creatinine is never Ma's history. A paper that does not exist has nothing before it.
     */
    @Override
    public Flux<Map<String, Object>> readingsOf(UUID patientId, String canonicalName, UUID beforeDocumentId) {
        return db.sql("SELECT o.id, o.document_id, o.value, o.unit, o.crop_key, d.timeline_date, d.doc_date IS NULL AS date_is_fallback "
                        + "FROM " + S + ".observation o JOIN " + S + ".document d ON d.id = o.document_id "
                        + "JOIN " + S + ".document me ON me.id = :before "
                        + "WHERE o.patient_id = :patient AND o.canonical_name = :canonical AND d.status = 'DONE' "
                        + "AND (d.timeline_date, d.created_at, d.id) < (me.timeline_date, me.created_at, me.id) "
                        + "ORDER BY d.timeline_date DESC, d.created_at DESC, d.id DESC, o.id LIMIT " + READINGS_SCANNED)
                .bind("patient", patientId).bind("canonical", canonicalName).bind("before", beforeDocumentId)
                .map((row, meta) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", row.get("id", UUID.class));
                    m.put("document_id", row.get("document_id", UUID.class));
                    m.put("value", row.get("value", String.class));
                    String unit = row.get("unit", String.class);
                    if (unit != null) {
                        m.put("unit", unit);
                    }
                    m.put("crop_key", row.get("crop_key", String.class));
                    m.put("date", row.get("timeline_date", LocalDate.class));
                    m.put("date_is_fallback", Boolean.TRUE.equals(row.get("date_is_fallback", Boolean.class)));
                    return m;
                }).all();
    }
}
