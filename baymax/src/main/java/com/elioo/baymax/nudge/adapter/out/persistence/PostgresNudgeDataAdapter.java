package com.elioo.baymax.nudge.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.nudge.application.port.out.NudgeOptOutPort;
import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Patient-scoped reads over the extraction tables, and the two opt-out columns. Raw SQL; the indexes exist (V5). */
@Component
@RequiredArgsConstructor
public class PostgresNudgeDataAdapter implements NudgeDataPort, NudgeOptOutPort {

    private static final String S = BaymaxSchema.NAME;
    private static final String PATIENT_COLS = "p.id AS patient_id, p.family_id, p.chronic_flags, p.nudges_opted_out_at AS p_out, f.nudges_opted_out_at AS f_out";
    private static final String MED_COLS = "m.id, m.patient_id, d.family_id, m.document_id, m.at, d.doc_date, m.name, m.dose_text, m.frequency_text, m.timing_text, m.duration_text, m.crop_key";

    private final DatabaseClient db;

    @Override
    public Flux<PatientRef> patients() {
        return db.sql("SELECT " + PATIENT_COLS + " FROM " + S + ".patient_profile p JOIN " + S + ".family_account f ON f.id = p.family_id ORDER BY p.created_at")
                .map((row, meta) -> patient(row)).all();
    }

    @Override
    public Mono<PatientRef> patient(UUID patientId) {
        return db.sql("SELECT " + PATIENT_COLS + " FROM " + S + ".patient_profile p JOIN " + S + ".family_account f ON f.id = p.family_id WHERE p.id = :id")
                .bind("id", patientId).map((row, meta) -> patient(row)).one();
    }

    private static PatientRef patient(Row row) {
        String[] flags = row.get("chronic_flags", String[].class);
        return new PatientRef(row.get("patient_id", UUID.class), row.get("family_id", UUID.class),
                flags == null ? List.of() : Arrays.asList(flags), instant(row, "p_out"), instant(row, "f_out"));
    }

    @Override
    public Flux<FollowUpRow> openFollowUpsDueOn(LocalDate dueDate) {
        return db.sql("SELECT u.id, u.patient_id, d.family_id, u.document_id, u.instruction, u.due_date, u.crop_key FROM " + S + ".follow_up u "
                        + "JOIN " + S + ".document d ON d.id = u.document_id WHERE u.status = 'open' AND u.due_date = :due")
                .bind("due", dueDate)
                .map((row, meta) -> new FollowUpRow(row.get("id", UUID.class), row.get("patient_id", UUID.class), row.get("family_id", UUID.class),
                        row.get("document_id", UUID.class), row.get("instruction", String.class), row.get("due_date", LocalDate.class), row.get("crop_key", String.class)))
                .all();
    }

    @Override
    public Flux<MedicationRow> medicationsWithDurationSince(Instant since) {
        return db.sql("SELECT " + MED_COLS + " FROM " + S + ".medication_event m JOIN " + S + ".document d ON d.id = m.document_id "
                        + "WHERE m.duration_text IS NOT NULL AND m.duration_text <> '' AND m.at >= :since")
                .bind("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC)).map((row, meta) -> medication(row)).all();
    }

    @Override
    public Flux<MedicationRow> medicationsOfPatient(UUID patientId) {
        return db.sql("SELECT " + MED_COLS + " FROM " + S + ".medication_event m JOIN " + S + ".document d ON d.id = m.document_id "
                        + "WHERE m.patient_id = :p AND d.status = 'DONE' AND d.document_type = 'prescription' "
                        + "ORDER BY COALESCE(d.doc_date, d.created_at::date) DESC, d.created_at DESC, m.name")
                .bind("p", patientId).map((row, meta) -> medication(row)).all();
    }

    private static MedicationRow medication(Row row) {
        return new MedicationRow(row.get("id", UUID.class), row.get("patient_id", UUID.class), row.get("family_id", UUID.class),
                row.get("document_id", UUID.class), instant(row, "at"), row.get("doc_date", LocalDate.class), row.get("name", String.class),
                row.get("dose_text", String.class), row.get("frequency_text", String.class), row.get("timing_text", String.class),
                row.get("duration_text", String.class), row.get("crop_key", String.class));
    }

    @Override
    public Flux<ObservationRow> observationsOf(UUID patientId, String canonicalName) {
        return db.sql("SELECT id, document_id, name, value, unit, observed_at, crop_key FROM " + S + ".observation "
                        + "WHERE patient_id = :p AND canonical_name = :c ORDER BY observed_at, id")
                .bind("p", patientId).bind("c", canonicalName)
                .map((row, meta) -> new ObservationRow(row.get("id", UUID.class), row.get("document_id", UUID.class), row.get("name", String.class),
                        row.get("value", String.class), row.get("unit", String.class), instant(row, "observed_at"), row.get("crop_key", String.class)))
                .all();
    }

    @Override
    public Mono<Instant> lastDocumentAt(UUID patientId) {
        return db.sql("SELECT max(created_at) AS t FROM " + S + ".document WHERE patient_id = :p").bind("p", patientId)
                .map((row, meta) -> instant(row, "t")).one().filter(t -> t != null);
    }

    @Override
    public Flux<String> markersOf(UUID patientId) {
        return db.sql("SELECT DISTINCT canonical_name FROM " + S + ".observation WHERE patient_id = :p AND canonical_name IS NOT NULL")
                .bind("p", patientId).map((row, meta) -> row.get("canonical_name", String.class)).all();
    }

    @Override
    public Mono<Void> optOutPatient(UUID patientId, Instant at) {
        return db.sql("UPDATE " + S + ".patient_profile SET nudges_opted_out_at = COALESCE(nudges_opted_out_at, :at) WHERE id = :id")
                .bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).bind("id", patientId).then();
    }

    @Override
    public Mono<Void> optOutFamily(UUID familyId, Instant at) {
        return db.sql("UPDATE " + S + ".family_account SET nudges_opted_out_at = COALESCE(nudges_opted_out_at, :at) WHERE id = :id")
                .bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).bind("id", familyId).then();
    }

    static Instant instant(Row row, String col) {
        OffsetDateTime t = row.get(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
