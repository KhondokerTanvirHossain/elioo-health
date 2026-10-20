package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
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
        Instant now = Instant.now();
        return context.getBean(HealthRecordPort.class)
                .createPatient(new PatientProfile(null, family, name, 64, PatientProfile.Sex.FEMALE, List.of(), now, now)).block();
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
