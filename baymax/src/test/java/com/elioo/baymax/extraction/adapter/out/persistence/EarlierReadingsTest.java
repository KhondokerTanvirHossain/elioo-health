package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.web.application.port.in.ResultUseCase;
import com.elioo.baymax.web.domain.EarlierHistory;
import com.elioo.baymax.web.domain.EarlierReading;
import com.elioo.baymax.web.domain.ValueSheetView;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
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
 * never crosses patients, and is "earlier": a paper dated after this one is not an earlier reading. Through the one
 * public history read, {@link ResultUseCase#valueSheet} (every viewed paper here is DONE, so its status gate lets each
 * through), so the 404s for another document's observation and another family are on the real path.
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
            // each paper's crop under its own key, so a reading shown beside another paper's strip is caught (DR-12)
            sql("INSERT INTO baymax.observation (patient_id, document_id, name, canonical_name, value, unit, crop_key, observed_at) VALUES ('"
                    + patient + "','" + doc + "','" + name + "','" + canonical + "','" + value + "',"
                    + (unit == null ? "NULL" : "'" + unit + "'") + ",'" + cropOf(doc) + "','" + at + "')");
            return doc;
        }

        /** The paper's printed date: timeline_date is generated from it (V20), so the order is the printed one. */
        UUID printed(UUID doc, LocalDate date) throws Exception {
            sql("UPDATE baymax.document SET doc_date = '" + date + "' WHERE id = '" + doc + "'");
            return doc;
        }

        static String cropOf(UUID doc) {
            return "k/" + doc + "/c.jpg";
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

            List<EarlierReading> rows = history(context, f.familyId(), now, f.observationIn(now)).map(EarlierHistory::sameUnit).block();

            assertThat(rows).extracting(EarlierReading::documentId).containsExactly(earlier);
            assertThat(rows).extracting(EarlierReading::value).containsExactly("140");
            assertThat(rows).extracting(EarlierReading::unit).as("the unit as printed on that paper").containsExactly("μmol/L");
            assertThat(rows).extracting(EarlierReading::observationId).containsExactly(f.observationIn(earlier));
            assertThat(rows).extracting(EarlierReading::cropKey).as("the earlier reading's own crop, not the viewed paper's")
                    .containsExactly(Fixture.cropOf(earlier));
            assertThat(rows).extracting(EarlierReading::date).as("no printed date: the upload day in Dhaka")
                    .containsExactly(LocalDate.of(2026, 10, 20));
            assertThat(rows).extracting(EarlierReading::dateIsFallback).containsExactly(true);
        });
    }

    /**
     * Plan W6-api's "first time" rule, both directions over the real history: a marker read before only in another unit
     * (µmol/L, asked about in mg/dL) has no reading in this unit and IS known to have been read, so the sheet must not
     * say "the first time"; a marker never read for this patient (only Baba's, in another unit) is not known to
     * have been read; and readings in this unit beside one in another keep both facts.
     */
    @Test
    void earlierHistoryKnowsTheMarkerWasReadInAnotherUnit() {
        runner().run(context -> {
            Fixture f = fixture(context, "+8801700000719");
            f.printed(f.doneWith(f.ma(), T, "creatinine", "Creatinine", "140", "µmol/L"), LocalDate.of(2026, 7, 1));
            UUID mgdl = f.printed(f.doneWith(f.ma(), T.plusSeconds(60), "creatinine", "Creatinine", "1.6", "mg/dL"), LocalDate.of(2026, 8, 1));
            UUID later = f.printed(f.doneWith(f.ma(), T.plusSeconds(120), "creatinine", "Creatinine", "1.4", "mg/dL"), LocalDate.of(2026, 9, 1));

            EarlierHistory onlyOther = history(context, f.familyId(), mgdl, f.observationIn(mgdl)).block();
            assertThat(onlyOther.sameUnit()).as("no earlier creatinine in mg/dL").isEmpty();
            assertThat(onlyOther.readInAnotherUnit()).as("creatinine was read before, in µmol/L").isTrue();

            EarlierHistory both = history(context, f.familyId(), later, f.observationIn(later)).block();
            assertThat(both.sameUnit()).extracting(EarlierReading::documentId).containsExactly(mgdl);
            assertThat(both.readInAnotherUnit()).isTrue();

            // the other direction: Baba's urea in mmol/L is not Ma's history, so Ma's first urea (mg/dL) is her first
            f.doneWith(f.baba(), T, "urea", "Urea", "7.1", "mmol/L");
            UUID first = f.doneWith(f.ma(), T.plusSeconds(180), "urea", "Urea", "40", "mg/dL");
            EarlierHistory none = history(context, f.familyId(), first, f.observationIn(first)).block();
            assertThat(none.sameUnit()).isEmpty();
            assertThat(none.readInAnotherUnit()).as("another patient's reading in another unit is not this patient's history").isFalse();
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

            assertThat(history(context, f.familyId(), third, f.observationIn(third)).map(EarlierHistory::sameUnit).block())
                    .extracting(EarlierReading::documentId).as("newest first, the paper being read left out").containsExactly(second, first);
            assertThat(history(context, f.familyId(), first, f.observationIn(first)).map(EarlierHistory::sameUnit).block())
                    .as("nothing came before the first paper").isEmpty();
        });
    }

    /**
     * "Before" is the timeline's order, the printed date first (V20's timeline_date), the upload time only within a day:
     * a paper dated earlier but sent later IS an earlier reading, and a paper sent earlier but dated later is NOT. Its
     * printed date is the row's date, not marked as the upload day.
     */
    @Test
    void thePrintedDateDecidesWhatCameBeforeNotTheUploadTime() {
        runner().run(context -> {
            Fixture f = fixture(context, "+8801700000708");
            UUID dated15SentFirst = f.printed(f.doneWith(f.ma(), T.minusSeconds(60), "potassium", "Potassium", "4.0", "mmol/L"),
                    LocalDate.of(2026, 10, 15));
            UUID datedLaterSentFirst = f.printed(f.doneWith(f.ma(), T, "potassium", "Potassium", "5.9", "mmol/L"), LocalDate.of(2026, 10, 18));
            UUID viewed = f.printed(f.doneWith(f.ma(), T.plusSeconds(60), "potassium", "Potassium", "4.3", "mmol/L"), LocalDate.of(2026, 10, 16));
            UUID datedEarlierSentLater = f.printed(f.doneWith(f.ma(), T.plusSeconds(120), "potassium", "Potassium", "4.1", "mmol/L"),
                    LocalDate.of(2026, 10, 14));

            List<EarlierReading> rows = history(context, f.familyId(), viewed, f.observationIn(viewed)).map(EarlierHistory::sameUnit).block();

            assertThat(rows).extracting(EarlierReading::documentId).as("dated 14 Oct, sent after the 16 Oct paper: earlier; "
                    + "dated 18 Oct, sent before it: later; newest by printed date first, 15 Oct before 14 Oct though sent first")
                    .containsExactly(dated15SentFirst, datedEarlierSentLater).doesNotContain(datedLaterSentFirst);
            assertThat(rows).extracting(EarlierReading::date).as("the printed date")
                    .containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 14));
            assertThat(rows).extracting(EarlierReading::dateIsFallback).as("a printed date is not the upload day").containsExactly(false, false);
        });
    }

    /**
     * Ten rows, the newest ten IN THIS UNIT: the cap is counted after the unit fold, so newer readings in another unit
     * never push same-unit ones off the list.
     */
    @Test
    void theListIsTheTenNewestInThisUnitCountedAfterTheFold() {
        runner().run(context -> {
            Fixture f = fixture(context, "+8801700000709");
            List<UUID> sameUnit = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                sameUnit.add(f.doneWith(f.ma(), T.plusSeconds(10L * i), "creatinine", "Creatinine", String.valueOf(100 + i), "µmol/L"));
            }
            for (int i = 0; i < 5; i++) {   // newer than every same-unit one, in another unit
                f.doneWith(f.ma(), T.plusSeconds(500 + 10L * i), "creatinine", "Creatinine", "1." + i, "mg/dL");
            }
            UUID viewed = f.doneWith(f.ma(), T.plusSeconds(1000), "creatinine", "Creatinine", "160", "µmol/L");

            List<EarlierReading> rows = history(context, f.familyId(), viewed, f.observationIn(viewed)).map(EarlierHistory::sameUnit).block();

            List<UUID> newestTen = new java.util.ArrayList<>(sameUnit.subList(2, 12));
            java.util.Collections.reverse(newestTen);
            assertThat(rows).extracting(EarlierReading::documentId).as("the ten newest µmol/L readings, newest first").containsExactlyElementsOf(newestTen);
            assertThat(rows).extracting(EarlierReading::unit).containsOnly("µmol/L");
        });
    }

    /** A value printed without a unit: unitless earlier readings are its history, and a unit-ful one is not — both ways. */
    @Test
    void aUnitlessValueMatchesUnitlessReadingsOnly() {
        runner().run(context -> {
            Fixture f = fixture(context, "+8801700000710");
            UUID unitless = f.doneWith(f.ma(), T, "urine albumin", "Urine Albumin", "Nil", null);
            UUID withUnit = f.doneWith(f.ma(), T.plusSeconds(60), "urine albumin", "Urine Albumin", "30", "mg/L");
            UUID viewedUnitless = f.doneWith(f.ma(), T.plusSeconds(120), "urine albumin", "Urine Albumin", "Trace", null);
            UUID viewedWithUnit = f.doneWith(f.ma(), T.plusSeconds(180), "urine albumin", "Urine Albumin", "45", "mg/L");

            assertThat(history(context, f.familyId(), viewedUnitless, f.observationIn(viewedUnitless)).map(EarlierHistory::sameUnit).block())
                    .extracting(EarlierReading::documentId).as("unitless beside unitless").containsExactly(unitless);
            assertThat(history(context, f.familyId(), viewedWithUnit, f.observationIn(viewedWithUnit)).map(EarlierHistory::sameUnit).block())
                    .extracting(EarlierReading::documentId).as("mg/L beside mg/L, never the unitless one").containsExactly(withUnit);
        });
    }

    /** The dangerous direction: an observation id from another document cannot open a sheet on this one. */
    @Test
    void anObservationFromAnotherDocumentIsNotFound() {
        runner().run(context -> {
            Fixture f = fixture(context, "+8801700000704");
            UUID a = f.doneWith(f.ma(), T, "hba1c", "HbA1c", "8.4", "%");
            UUID b = f.doneWith(f.ma(), T.plusSeconds(60), "hba1c", "HbA1c", "7.9", "%");
            StepVerifier.create(history(context, f.familyId(), a, f.observationIn(b)).map(EarlierHistory::sameUnit))
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
            StepVerifier.create(history(context, theirs.familyId(), doc, mine.observationIn(doc)).map(EarlierHistory::sameUnit))
                    .expectErrorSatisfies(e -> assertThat(((BaymaxException) e).status()).isEqualTo(HttpStatus.NOT_FOUND)).verify();
        });
    }

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES);
    }

    /** The value's history as the sheet reads it: the gated read both sheet routes use, never an ungated scan. */
    private static Mono<EarlierHistory> history(ApplicationContext context, UUID familyId, UUID documentId, UUID observationId) {
        return context.getBean(ResultUseCase.class).valueSheet(familyId, documentId, observationId).map(ValueSheetView::history);
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
