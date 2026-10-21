package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.healthrecord.domain.ShareMember;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.web.application.port.out.SessionStorePort;
import com.elioo.baymax.web.application.service.PhoneHasher;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The real Baymax module over Testcontainers Postgres with WebFlux, for the {@code /app/api} tests that must see the
 * context's own router ordering, filters, services and adapters. Families are made through the ports; a session is a
 * row in the real session store whose token the test holds, so the cookie is resolved exactly as in production.
 */
final class AppApiContext {

    static final String ORIGIN = "https://medioo.eliooo.org";

    private AppApiContext() {
    }

    static ReactiveWebApplicationContextRunner runner(PostgreSQLContainer<?> postgres) {
        return PostgresNudgeAdapterTest.runnerFor(postgres)
                .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class))
                .withPropertyValues("baymax.wa.enabled=false", "baymax.nudge.enabled=false", "baymax.web.public-origin=" + ORIGIN);
    }

    static FamilyAccount family(ApplicationContext context, String number, String ownerName) {
        Instant now = Instant.now();
        return context.getBean(HealthRecordPort.class)
                .createFamily(new FamilyAccount(null, number, ownerName, FamilyAccount.Plan.FAMILY, now, now)).block();
    }

    static PatientProfile patient(ApplicationContext context, UUID family, String name) {
        return patient(context, family, name, 64, PatientProfile.Sex.FEMALE);
    }

    static PatientProfile patient(ApplicationContext context, UUID family, String name, int age, PatientProfile.Sex sex) {
        Instant now = Instant.now();
        return context.getBean(HealthRecordPort.class)
                .createPatient(new PatientProfile(null, family, name, age, sex, List.of(), now, now)).block();
    }

    /** Shares another family's patient with this number (the sibling's view): visible, but not owned. */
    static void share(ApplicationContext context, PatientProfile patient, String number) {
        context.getBean(HealthRecordPort.class)
                .addShareMember(new ShareMember(null, patient.id(), number, Instant.now(), null)).block();
    }

    /** One stored page image for a paper, which is what the {@code documents} count of {@code /me} counts. */
    static void storedPage(PostgreSQLContainer<?> postgres, PatientProfile patient, UUID document) throws Exception {
        sql(postgres, "INSERT INTO baymax.stored_object (family_id, patient_id, document_id, kind, page_no, storage_key, content_type,"
                + " size_bytes, created_at) VALUES ('" + patient.familyId() + "','" + patient.id() + "','" + document + "','page',1,'"
                + patient.familyId() + "/" + patient.id() + "/" + document + "/page-1.jpg','image/jpeg',100,now())");
    }

    static void sql(PostgreSQLContainer<?> postgres, String statement) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl() + "&sslmode=disable", postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    /** The single number a {@code select count(*) …} answers. */
    static long count(PostgreSQLContainer<?> postgres, String query) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl() + "&sslmode=disable", postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getLong(1);
        }
    }

    /** One stored value of a paper, as extraction stores it (with its crop, V5; no verdict, as before V19). */
    static void value(PostgreSQLContainer<?> postgres, PatientProfile patient, UUID document, String name, String value) throws Exception {
        sql(postgres, "INSERT INTO baymax.observation (patient_id, document_id, name, value, crop_key, observed_at) VALUES ('"
                + patient.id() + "','" + document + "','" + name.replace("'", "''") + "','" + value + "','"
                + patient.familyId() + "/" + patient.id() + "/" + document + "/crop-1.jpg',now())");
    }

    /** The paper's explanation, composed and not sent (V14: sent_at NULL), in this review state. */
    static void explanation(PostgreSQLContainer<?> postgres, PatientProfile patient, UUID document, String gateStatus, String body) throws Exception {
        sql(postgres, "INSERT INTO baymax.outbound_message (family_id, patient_id, document_id, kind, urgency, urgency_reasons, body, gate_status)"
                + " VALUES ('" + patient.familyId() + "','" + patient.id() + "','" + document + "','explanation','now','[]','" + body + "','"
                + gateStatus + "')");
    }

    /** A live session for the family; the returned token is the cookie value. */
    static String session(ApplicationContext context, UUID family) {
        String token = "tok-" + UUID.randomUUID();
        Instant now = Instant.now();
        context.getBean(SessionStorePort.class).create(PhoneHasher.sha256(token), family, now, now.plus(Duration.ofDays(1))).block();
        return token;
    }

    /** A finished lab report uploaded now with a stored F1 level, as the urgency writer leaves it. */
    static UUID paper(ApplicationContext context, PostgreSQLContainer<?> postgres, PatientProfile patient, String level) throws Exception {
        UUID id = context.getBean(DocumentRecordPort.class)
                .create(Document.received(patient.id(), patient.familyId(), 1, Instant.now())).block().id();
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl() + "&sslmode=disable", postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("UPDATE baymax.document SET status = 'DONE', document_type = 'lab_report', urgency = '" + level
                    + "', urgency_reasons = '[]', urgency_assessed_at = now() WHERE id = '" + id + "'");
        }
        return id;
    }
}
