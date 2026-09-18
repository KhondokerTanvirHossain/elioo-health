package com.elioo.baymax.web.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.healthrecord.domain.ShareMember;
import com.elioo.baymax.web.application.port.out.OtpStorePort;
import com.elioo.baymax.web.application.port.out.SessionStorePort;
import com.elioo.baymax.web.domain.OtpCode;
import com.elioo.healthcare.llm.config.LlmAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access rules as SQL decides them, against a real Postgres: a second family's document is simply not
 * there (so the service answers 404, never 403), and a share member reaches a patient through their number.
 */
@Testcontainers
class WebPersistenceAdaptersTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withPropertyValues("baymax.enabled=true", "llm.provider=groq", "llm.groq.api-key=test-key",
                        "baymax.extract.vision-model=", "baymax.auth.hmac-secret=test",
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(), "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + POSTGRES.getJdbcUrl() + "&sslmode=disable",
                        "baymax.flyway.user=" + POSTGRES.getUsername(), "baymax.flyway.password=" + POSTGRES.getPassword());
    }

    @Test
    void visibilityIsDecidedInSql() {
        runner().run(context -> {
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            Instant now = Instant.now();
            FamilyAccount a = records.createFamily(new FamilyAccount(null, "+8801711111111", "A", FamilyAccount.Plan.FREE, now, now)).block();
            FamilyAccount b = records.createFamily(new FamilyAccount(null, "+8801722222222", "B", FamilyAccount.Plan.FREE, now, now)).block();
            FamilyAccount c = records.createFamily(new FamilyAccount(null, "+8801733333333", "C", FamilyAccount.Plan.FREE, now, now)).block();
            PatientProfile pb = records.createPatient(new PatientProfile(null, b.id(), "Ma", 60, PatientProfile.Sex.FEMALE, List.of(), now, now)).block();
            Document db = documents.create(Document.received(pb.id(), b.id(), 1, now)).block();

            // B sees its own; A sees nothing of B's — empty, not an error
            assertThat(records.visiblePatient(b.id(), pb.id()).block().owner()).isTrue();
            StepVerifier.create(records.visiblePatient(a.id(), pb.id())).verifyComplete();
            StepVerifier.create(documents.findVisible(a.id(), db.id())).verifyComplete();
            assertThat(documents.findVisible(b.id(), db.id()).block().id()).isEqualTo(db.id());

            // C is shared on B's patient by C's number: sees the patient and the document, as a non-owner
            records.addShareMember(new ShareMember(null, pb.id(), "+8801733333333", now, null)).block();
            assertThat(records.visiblePatient(c.id(), pb.id()).block().owner()).isFalse();
            assertThat(documents.findVisible(c.id(), db.id()).block().id()).isEqualTo(db.id());
            assertThat(records.visiblePatients(c.id()).collectList().block()).hasSize(1);
            assertThat(records.visiblePatients(a.id()).collectList().block()).isEmpty();

            // timeline paging: newest first, strictly before the cursor
            Document older = documents.create(Document.received(pb.id(), b.id(), 1, now.minusSeconds(100))).block();
            List<Document> page = documents.timelineOf(pb.id(), now.plusSeconds(1), 10).collectList().block();
            assertThat(page).extracting(Document::id).containsExactly(db.id(), older.id());
            assertThat(documents.timelineOf(pb.id(), db.createdAt(), 10).collectList().block()).extracting(Document::id).containsExactly(older.id());
            assertThat(documents.sectionCounts(db.id()).block()).containsExactly(0, 0, 0);
            assertThat(documents.deleteDocument(db.id()).block()).isEqualTo(1L);
            StepVerifier.create(documents.find(db.id())).verifyComplete();
        });
    }

    @Test
    void otpAndSessionRowsRoundTripAndTheirLivenessRulesHold() {
        runner().run(context -> {
            OtpStorePort otps = context.getBean(OtpStorePort.class);
            SessionStorePort sessions = context.getBean(SessionStorePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant now = Instant.now();
            FamilyAccount f = records.createFamily(new FamilyAccount(null, "+8801744444444", "F", FamilyAccount.Plan.FREE, now, now)).block();

            OtpCode c = otps.create(new OtpCode(null, "hash1", "codehash", f.id(), now.plusSeconds(600), 0, null, null, now)).block();
            assertThat(c.id()).isNotNull();
            assertThat(c.familyId()).isEqualTo(f.id());
            assertThat(otps.countLive("hash1", now.minusSeconds(3600), now).block()).isEqualTo(1L);
            assertThat(otps.latestLive("hash1", now).block().id()).isEqualTo(c.id());
            otps.recordAttempt(c.id(), 5, now).block();                           // burned
            StepVerifier.create(otps.latestLive("hash1", now)).verifyComplete();
            assertThat(otps.countLive("hash1", now.minusSeconds(3600), now).block()).isZero();

            var s = sessions.create("tokhash", f.id(), now, now.plusSeconds(60)).block();
            assertThat(sessions.findLive("tokhash", now).block().familyId()).isEqualTo(f.id());
            StepVerifier.create(sessions.findLive("tokhash", now.plusSeconds(61))).verifyComplete();   // expired
            sessions.touch(s.id(), now.plusSeconds(30), now.plusSeconds(3600)).block();
            assertThat(sessions.findLive("tokhash", now.plusSeconds(61)).block()).isNotNull();          // slid
            sessions.revoke("tokhash", now.plusSeconds(40)).block();
            StepVerifier.create(sessions.findLive("tokhash", now.plusSeconds(41))).verifyComplete();   // revoked
        });
    }
}
