package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.domain.EarlierReading;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The value sheet's history (B6, s13). Same marker AND same unit (a mg/dL reading beside a µmol/L one is a different
 * number), where "same unit" survives MICRO SIGN vs GREEK MU (CLAUDE.md: 8 of 9 "misses" in batch 2 were that fold),
 * never crosses patients, and is "earlier": a paper dated after this one is not an earlier reading. Through
 * {@link ResultUseCase}, so the 404s for another document's observation and another family are on the real path.
 * Each test makes its own family, so they run in any order.
 */
@Testcontainers
class EarlierReadingsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final Instant T = Instant.parse("2026-10-20T04:00:00Z");

    /** A family with two patients; doneWith() writes a DONE document holding one observation row and returns its id. */
    record Fixture(ApplicationContext context, UUID familyId, UUID ma, UUID baba) {

        UUID doneWith(UUID patient, Instant at, String canonical, String name, String value, String unit) throws Exception {
            return docWith(Document.Status.DONE, patient, at, canonical, name, value, unit);
        }

        UUID docWith(Document.Status status, UUID patient, Instant at, String canonical, String name, String value, String unit) throws Exception {
            DocumentRecordPort docs = context.getBean(DocumentRecordPort.class);
            Document received = docs.create(Document.received(patient, familyId, 1, at)).block();
            UUID doc = status == Document.Status.RECEIVED ? received.id()
                    : docs.update(received.withStatus(status, null, null, at)).block().id();
            sql("INSERT INTO baymax.observation (patient_id, document_id, name, canonical_name, value, unit, crop_key, observed_at) VALUES ('"
                    + patient + "','" + doc + "','" + name + "','" + canonical + "','" + value + "','" + unit + "','k/c.jpg','" + at + "')");
            return doc;
        }

        UUID observationIn(UUID doc) throws Exception {
            return UUID.fromString(scalar("SELECT id FROM baymax.observation WHERE document_id = '" + doc + "'"));
        }
    }

    @Test
    void earlierReadingsMatchMarkerAndFoldedUnitForThisPatientOnly() {
        runner().run(context -> {
            Fixture f = fixture(context, "+8801700000703");
            UUID now = f.doneWith(f.ma(), T.plusSeconds(300), "creatinine", "Creatinine", "160", "µmol/L");       // MICRO SIGN
            UUID earlier = f.doneWith(f.ma(), T.plusSeconds(200), "creatinine", "S. Creatinine", "140", "μmol/L"); // GREEK MU
            f.doneWith(f.ma(), T.plusSeconds(100), "creatinine", "Creatinine", "1.6", "mg/dL");                     // another unit
            f.doneWith(f.baba(), T.plusSeconds(150), "creatinine", "Creatinine", "150", "µmol/L");                  // another patient

            List<EarlierReading> rows = context.getBean(ResultUseCase.class)
                    .earlierReadings(f.familyId(), now, f.observationIn(now)).collectList().block();

            assertThat(rows).extracting(EarlierReading::documentId).containsExactly(earlier);
            assertThat(rows).extracting(EarlierReading::value).containsExactly("140");
            assertThat(rows).extracting(EarlierReading::unit).as("the unit as printed on that paper").containsExactly("μmol/L");
            assertThat(rows).extracting(EarlierReading::observationId).containsExactly(f.observationIn(earlier));
            assertThat(rows).extracting(EarlierReading::cropKey).as("the earlier reading's own crop").containsExactly("k/c.jpg");
            assertThat(rows).extracting(EarlierReading::date).as("no printed date: the upload day in Dhaka")
                    .containsExactly(LocalDate.of(2026, 10, 20));
            assertThat(rows).extracting(EarlierReading::dateIsFallback).containsExactly(true);
        });
    }

    /**
     * "আগের রিপোর্টে" in both directions: from the newest paper the older ones are listed newest first; from the oldest
     * one, a paper sent after it is not an earlier reading; a paper still being read (not DONE) is never one.
     */
    @Test
    void onlyFinishedPapersBeforeThisOneAreEarlierNewestFirst() {
        runner().run(context -> {
            Fixture f = fixture(context, "+8801700000707");
            UUID first = f.doneWith(f.ma(), T, "hba1c", "HbA1c", "8.4", "%");
            UUID second = f.doneWith(f.ma(), T.plusSeconds(60), "hba1c", "HbA1c", "7.9", "%");
            UUID third = f.doneWith(f.ma(), T.plusSeconds(120), "hba1c", "HbA1c", "7.1", "%");
            f.docWith(Document.Status.PROCESSING, f.ma(), T.plusSeconds(90), "hba1c", "HbA1c", "6.6", "%");
            ResultUseCase results = context.getBean(ResultUseCase.class);

            assertThat(results.earlierReadings(f.familyId(), third, f.observationIn(third)).collectList().block())
                    .extracting(EarlierReading::documentId).as("newest first, the paper being read left out").containsExactly(second, first);
            assertThat(results.earlierReadings(f.familyId(), first, f.observationIn(first)).collectList().block())
                    .as("nothing came before the first paper").isEmpty();
        });
    }

    /** The dangerous direction: an observation id from another document cannot open a sheet on this one. */
    @Test
    void anObservationFromAnotherDocumentIsNotFound() {
        runner().run(context -> {
            Fixture f = fixture(context, "+8801700000704");
            UUID a = f.doneWith(f.ma(), T, "hba1c", "HbA1c", "8.4", "%");
            UUID b = f.doneWith(f.ma(), T.plusSeconds(60), "hba1c", "HbA1c", "7.9", "%");
            StepVerifier.create(context.getBean(ResultUseCase.class).earlierReadings(f.familyId(), a, f.observationIn(b)).collectList())
                    .expectErrorSatisfies(e -> assertThat(((BaymaxException) e).status()).isEqualTo(HttpStatus.NOT_FOUND)).verify();
        });
    }

    /** And a family that cannot see the document gets 404, whatever observation it names. */
    @Test
    void anotherFamilyGetsNotFound() {
        runner().run(context -> {
            Fixture mine = fixture(context, "+8801700000705");
            Fixture theirs = fixture(context, "+8801700000706");
            UUID doc = mine.doneWith(mine.ma(), T, "hba1c", "HbA1c", "8.4", "%");
            StepVerifier.create(context.getBean(ResultUseCase.class).earlierReadings(theirs.familyId(), doc, mine.observationIn(doc)).collectList())
                    .expectErrorSatisfies(e -> assertThat(((BaymaxException) e).status()).isEqualTo(HttpStatus.NOT_FOUND)).verify();
        });
    }

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES);
    }

    /** A family of two (Ma and Baba) under a fictional number in the fixture range. */
    private static Fixture fixture(ApplicationContext context, String number) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount f = records.createFamily(new FamilyAccount(null, number, "Rahim", FamilyAccount.Plan.FREE, T, T)).block();
        UUID ma = records.createPatient(new PatientProfile(null, f.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), T, T)).block().id();
        UUID baba = records.createPatient(new PatientProfile(null, f.id(), "Baba", 72, PatientProfile.Sex.MALE, List.of(), T, T)).block().id();
        return new Fixture(context, f.id(), ma, baba);
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            assertThat(rs.next()).as("a row for " + query).isTrue();
            return rs.getString(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
