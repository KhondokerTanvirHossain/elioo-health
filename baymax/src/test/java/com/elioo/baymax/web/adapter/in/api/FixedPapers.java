package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.web.application.port.out.SessionStorePort;
import com.elioo.baymax.web.application.service.PhoneHasher;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Rows with fixed ids and fixed instants under a fixed clock, for the plan B3 contract tests whose answers are written as
 * MSW fixtures and byte-compared (Snapshot): a random id or a "today" in a fixture would change it on every run. Ids are
 * derived from a case name and a role ({@link #id}), so two cases in one container never collide and a fixture reads
 * the same on every machine. Fictional throughout (DR-36): numbers in the +8801700000xxx range, English names.
 */
final class FixedPapers {

    /** The developer-facing reason every retake here stores: in the database, never in the JSON. */
    static final String DEVELOPER_PROSE = "developer prose";

    private FixedPapers() {
    }

    /** The real Baymax module and WebFlux under a clock fixed at {@code now}, with no scheduled writer running. */
    static ReactiveWebApplicationContextRunner runnerAt(PostgreSQLContainer<?> postgres, Instant now) {
        return AppApiContext.runner(postgres)
                .withBean(Clock.class, () -> Clock.fixed(now, ZoneOffset.UTC))
                .withPropertyValues("baymax.urgency.sweep-cron=-");
    }

    /** The id a case gives one of its rows: stable across runs, distinct across cases and roles. */
    static UUID id(String kase, String role) {
        return UUID.nameUUIDFromBytes((kase + ":" + role).getBytes(StandardCharsets.UTF_8));
    }

    static UUID family(PostgreSQLContainer<?> postgres, UUID id, String number, String owner) throws Exception {
        exec(postgres, "INSERT INTO baymax.family_account (id, whatsapp_number, owner_name, plan, terms_accepted_at, created_at) "
                + "VALUES (?, ?, ?, 'family', ?, ?)", id, number, owner, ts(Instant.parse("2026-09-01T00:00:00Z")),
                ts(Instant.parse("2026-09-01T00:00:00Z")));
        return id;
    }

    static PatientProfile patient(PostgreSQLContainer<?> postgres, UUID id, UUID family, String name) throws Exception {
        Instant at = Instant.parse("2026-09-01T00:00:00Z");
        exec(postgres, "INSERT INTO baymax.patient_profile (id, family_id, name, age, sex, proxy_consent_at, created_at) "
                + "VALUES (?, ?, ?, 64, 'female', ?, ?)", id, family, name, ts(at), ts(at));
        return new PatientProfile(id, family, name, 64, PatientProfile.Sex.FEMALE, List.of(), at, at);
    }

    /** Shares the patient with {@code number}, as the admin share route stores it. */
    static void share(PostgreSQLContainer<?> postgres, PatientProfile patient, String number) throws Exception {
        exec(postgres, "INSERT INTO baymax.share_member (patient_id, whatsapp_number) VALUES (?, ?)", patient.id(), number);
    }

    /**
     * One paper with exactly these columns. {@code urgency} null is never assessed; a NEEDS_RETAKE paper gets the
     * {@code low_overall} code the constraint requires. One page, a fictional facility.
     */
    static UUID paper(PostgreSQLContainer<?> postgres, UUID id, PatientProfile p, String type, String status, String docDate,
                      Instant createdAt, String urgency) throws Exception {
        exec(postgres, "INSERT INTO baymax.document (id, patient_id, family_id, document_type, doc_date, facility, status, status_reason_code, "
                        + "status_reason, page_count, created_at, updated_at, urgency, urgency_reasons, urgency_assessed_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'Shapla Diagnostics', ?, ?, ?, 1, ?, ?, ?, " + (urgency == null ? "NULL" : "'[]'::jsonb") + ", ?)",
                id, p.id(), p.familyId(), type, docDate == null ? null : LocalDate.parse(docDate), status,
                "NEEDS_RETAKE".equals(status) ? "low_overall" : null, "NEEDS_RETAKE".equals(status) ? DEVELOPER_PROSE : null,
                ts(createdAt), ts(createdAt), urgency, urgency == null ? null : ts(createdAt));
        return id;
    }

    /** One open follow-up the doctor wrote on {@code document}, due on {@code due}, with its crop. */
    static void followUp(PostgreSQLContainer<?> postgres, UUID id, PatientProfile p, UUID document, String instruction, String due) throws Exception {
        exec(postgres, "INSERT INTO baymax.follow_up (id, patient_id, document_id, instruction, due_date, status, crop_key) "
                + "VALUES (?, ?, ?, ?, ?, 'open', ?)", id, p.id(), document, instruction, LocalDate.parse(due),
                p.familyId() + "/" + p.id() + "/" + document + "/crop-f" + id.toString().substring(0, 8) + ".jpg");
    }

    /** A live session for the family under the fixed clock {@code now}; the token is the cookie value. */
    static String session(ApplicationContext context, UUID family, Instant now) {
        String token = "tok-" + UUID.randomUUID();
        context.getBean(SessionStorePort.class)
                .create(PhoneHasher.sha256(token), family, now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofDays(1))).block();
        return token;
    }

    static void exec(PostgreSQLContainer<?> postgres, String sql, Object... args) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl() + "&sslmode=disable", postgres.getUsername(), postgres.getPassword());
             PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }
            s.executeUpdate();
        }
    }

    /** Every row the query answers, each as its columns read as text, in the query's order. */
    static List<List<String>> rows(PostgreSQLContainer<?> postgres, String query) throws Exception {
        List<List<String>> out = new java.util.ArrayList<>();
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl() + "&sslmode=disable", postgres.getUsername(), postgres.getPassword());
             java.sql.Statement s = c.createStatement(); java.sql.ResultSet rs = s.executeQuery(query)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new java.util.ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    row.add(rs.getString(i));
                }
                out.add(row);
            }
        }
        return out;
    }

    /** The single number a {@code select count(*) …} answers. */
    static long count(PostgreSQLContainer<?> postgres, String query) throws Exception {
        return AppApiContext.count(postgres, query);
    }

    private static Timestamp ts(Instant at) {
        return Timestamp.from(at);
    }
}
