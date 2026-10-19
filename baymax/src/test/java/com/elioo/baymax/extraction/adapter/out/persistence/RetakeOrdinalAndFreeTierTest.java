package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.extraction.application.port.out.DocumentReadPort;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B5, on real Postgres: the retake streak is per patient and resets on a finished paper; a retake never uses the
 * family's free allowance (DR-24: only DONE counts). Each test makes its own family, so they run in any order.
 */
@Testcontainers
class RetakeOrdinalAndFreeTierTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    /** In October 2026, and the clock the context runs on: the month counted is this one, not the month the test runs. */
    private static final Instant T = Instant.parse("2026-10-20T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T.plusSeconds(3600), ZoneOffset.UTC);
    private static final AtomicInteger FAMILIES = new AtomicInteger();

    /** A family of two (Ma and Baba) with fictional numbers in the fixture range, and a way to file their papers. */
    record Fixture(ApplicationContext context, UUID familyId, UUID ma, UUID baba) {
        static Fixture create(ApplicationContext context) {
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            FamilyAccount f = records.createFamily(new FamilyAccount(null,
                    "+88017000008" + String.format("%02d", FAMILIES.incrementAndGet()), "Rahim", FamilyAccount.Plan.FREE, T, T)).block();
            UUID ma = records.createPatient(new PatientProfile(null, f.id(), "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), T, T)).block().id();
            UUID baba = records.createPatient(new PatientProfile(null, f.id(), "Baba", 72, PatientProfile.Sex.MALE, List.of(), T, T)).block().id();
            return new Fixture(context, f.id(), ma, baba);
        }

        /** A paper sent at {@code at} and left in {@code status}: a retake carries its code, as V20 requires. */
        UUID doc(UUID patient, Document.Status status, Instant at) {
            DocumentRecordPort docs = context.getBean(DocumentRecordPort.class);
            Document d = docs.create(Document.received(patient, familyId, 1, at)).block();
            if (status == Document.Status.RECEIVED) {
                return d.id();
            }
            String code = status == Document.Status.NEEDS_RETAKE ? "low_overall" : null;
            return docs.update(d.withStatus(status, status == Document.Status.DONE ? null : "test", code, at)).block().id();
        }
    }

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES).withBean(Clock.class, () -> CLOCK)
                .withPropertyValues("baymax.free.max-docs-per-month=2", "baymax.free.max-patients=2");
    }

    @Test
    void theStreakCountsThisPatientsRetakesSinceTheirLastReadPaper() {
        runner().run(context -> {
            DocumentReadPort reads = context.getBean(DocumentReadPort.class);
            Fixture f = Fixture.create(context);
            UUID r1 = f.doc(f.ma(), Document.Status.NEEDS_RETAKE, T);
            UUID done = f.doc(f.ma(), Document.Status.DONE, T.plusSeconds(60));
            UUID r2 = f.doc(f.ma(), Document.Status.NEEDS_RETAKE, T.plusSeconds(120));
            UUID babasRetake = f.doc(f.baba(), Document.Status.NEEDS_RETAKE, T.plusSeconds(150));
            UUID r3 = f.doc(f.ma(), Document.Status.NEEDS_RETAKE, T.plusSeconds(180));

            assertThat(reads.retakeOrdinal(r1).block()).isEqualTo(1);
            assertThat(reads.retakeOrdinal(r2).block()).as("the DONE paper between ends the streak").isEqualTo(1);
            assertThat(reads.retakeOrdinal(r3).block()).as("Baba's retake does not join Ma's streak").isEqualTo(2);
            assertThat(reads.retakeOrdinal(done).block()).as("a read paper is no retake").isEqualTo(0);
            assertThat(reads.retakeOrdinal(babasRetake).block()).isEqualTo(1);
        });
    }

    /**
     * What else ends a streak and what does not: a FAILED paper ends it (it finished, on our side; the next retake is a
     * new run), a paper still being read does not (it has not finished, so the retakes are still in a row).
     */
    @Test
    void aFailedPaperEndsTheStreakAndAPaperStillBeingReadDoesNot() {
        runner().run(context -> {
            DocumentReadPort reads = context.getBean(DocumentReadPort.class);
            Fixture f = Fixture.create(context);
            f.doc(f.ma(), Document.Status.NEEDS_RETAKE, T);
            f.doc(f.ma(), Document.Status.FAILED, T.plusSeconds(60));
            UUID afterFailed = f.doc(f.ma(), Document.Status.NEEDS_RETAKE, T.plusSeconds(120));
            f.doc(f.ma(), Document.Status.RECEIVED, T.plusSeconds(150));
            UUID pastInFlight = f.doc(f.ma(), Document.Status.NEEDS_RETAKE, T.plusSeconds(180));

            assertThat(reads.retakeOrdinal(afterFailed).block()).as("a FAILED paper between ends the streak").isEqualTo(1);
            assertThat(reads.retakeOrdinal(pastInFlight).block()).as("a paper still being read does not").isEqualTo(2);
            assertThat(reads.retakeOrdinal(UUID.randomUUID()).block()).as("no such document").isEqualTo(0);
        });
    }

    /** Through FreeTierUseCase on the fixed clock: retakes are free, read papers count — and the month really holds them. */
    @Test
    void retakesNeverUseTheAllowanceAndReadPapersDo() {
        runner().run(context -> {
            FreeTierUseCase freeTier = context.getBean(FreeTierUseCase.class);
            DocumentRecordPort docs = context.getBean(DocumentRecordPort.class);
            Fixture f = Fixture.create(context);
            f.doc(f.ma(), Document.Status.DONE, T);
            for (int i = 1; i <= 3; i++) {
                f.doc(f.ma(), Document.Status.NEEDS_RETAKE, T.plusSeconds(i * 60L));
            }
            assertThat(docs.countInWindow(f.familyId(), T.minusSeconds(1), T.plusSeconds(3600)).block())
                    .as("all four papers are in the counted month").isEqualTo(4L);
            assertThat(docs.countInMonth(f.familyId(), YearMonth.of(2026, 10)).block()).as("only the read one counts").isEqualTo(1L);
            StepVerifier.create(freeTier.checkCanUploadDocument(f.familyId())).verifyComplete();

            f.doc(f.ma(), Document.Status.DONE, T.plusSeconds(600));
            StepVerifier.create(freeTier.checkCanUploadDocument(f.familyId())).expectError(FreeTierExceededException.class).verify();
        });
    }

    /** One concept, two counters: for a one-patient family (WhatsApp's case) the web's streak equals WhatsApp's count. */
    @Test
    void forOnePatientTheWebStreakAgreesWithWhatsAppsConsecutiveRetakes() {
        runner().run(context -> {
            Fixture f = Fixture.create(context);
            UUID last = null;
            for (int i = 0; i < 3; i++) {
                last = f.doc(f.ma(), Document.Status.NEEDS_RETAKE, T.plusSeconds(i * 60L));
                context.getBean(ExplainDocumentUseCase.class).explain(last).onErrorResume(e -> Mono.empty()).block();
            }
            long whatsapp = context.getBean(OutboundMessagePort.class).consecutiveRetakes(f.familyId()).block();
            assertThat(whatsapp).as("WhatsApp counted the three retake messages").isEqualTo(3L);
            assertThat(context.getBean(DocumentReadPort.class).retakeOrdinal(last).block()).isEqualTo((int) whatsapp);
        });
    }
}
