package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.extraction.application.port.out.DocumentReadPort;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** The only {@link DocumentReadPort} implementation: plain SQL over {@code document}, schema-qualified, read only. */
@Component
@RequiredArgsConstructor
public class PostgresDocumentReadAdapter implements DocumentReadPort {

    private static final String S = BaymaxSchema.NAME;

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
}
