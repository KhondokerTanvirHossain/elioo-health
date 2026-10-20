package com.elioo.baymax.extraction.adapter.out.persistence;

import com.elioo.baymax.config.BaymaxSchema;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.extraction.domain.CreatedDocument;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D3's retry rule on real Postgres (V21). A mock ledger cannot fail the way the database does (CLAUDE.md, DR-22): a
 * second insert with the same key comes back as "already there", the database refuses a duplicate from any writer, and
 * two presses racing on separate connections make one row.
 */
@Testcontainers
class DocumentUploadKeyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant T = Instant.parse("2026-10-19T08:00:00Z");

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES);
    }

    private static PatientProfile patient(ApplicationContext context, String number) {
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount f = records.createFamily(new FamilyAccount(null, number, "A", FamilyAccount.Plan.FREE, T, T)).block();
        return records.createPatient(new PatientProfile(null, f.id(), "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), T, T)).block();
    }

    @Test
    void theSameKeyTwiceIsOneDocumentAndAnotherFamilysSameKeyIsItsOwn() {
        runner().run(context -> {
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            PatientProfile pa = patient(context, "+8801700000721");
            PatientProfile pb = patient(context, "+8801700000722");

            CreatedDocument first = documents.createOnce(Document.received(pa.id(), pa.familyId(), 2, T), "k-0f8e2c1a").block();
            CreatedDocument retry = documents.createOnce(Document.received(pa.id(), pa.familyId(), 2, T), "k-0f8e2c1a").block();
            CreatedDocument other = documents.createOnce(Document.received(pb.id(), pb.familyId(), 1, T), "k-0f8e2c1a").block();

            assertThat(first.fresh()).isTrue();
            assertThat(first.document().pageCount()).isEqualTo(2);
            assertThat(first.document().status()).isEqualTo(Document.Status.RECEIVED);
            assertThat(retry.fresh()).isFalse();
            assertThat(retry.document().id()).isEqualTo(first.document().id());
            assertThat(other.fresh()).isTrue();
            assertThat(other.document().id()).isNotEqualTo(first.document().id());
            assertThat(documents.findByUploadKey(pa.familyId(), "k-0f8e2c1a").block().id()).isEqualTo(first.document().id());
            assertThat(documents.findByUploadKey(pb.familyId(), "k-0f8e2c1a").block().id()).isEqualTo(other.document().id());
            // asserted on the table, not on the port's own answer: one row per family, two families
            assertThat(scalar("SELECT count(*) FROM " + BaymaxSchema.NAME + ".document WHERE family_id = '" + pa.familyId()
                    + "' AND upload_key = 'k-0f8e2c1a'")).isEqualTo("1");
            assertThat(scalar("SELECT count(*) FROM " + BaymaxSchema.NAME + ".document WHERE upload_key = 'k-0f8e2c1a'")).isEqualTo("2");

            // the pipeline's own writes (writeDocument) never name the key, so a document that moved on is still found
            documents.update(first.document().withStatus(Document.Status.PROCESSING, null, T.plusSeconds(5))).block();
            assertThat(documents.findByUploadKey(pa.familyId(), "k-0f8e2c1a").block().status()).isEqualTo(Document.Status.PROCESSING);
        });
    }

    /** No key (WhatsApp, the JSON API) is never a replay: each call is a new row, and nothing is looked up. */
    @Test
    void withoutAKeyEveryCallIsANewDocument() {
        runner().run(context -> {
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            PatientProfile p = patient(context, "+8801700000725");
            CreatedDocument one = documents.createOnce(Document.received(p.id(), p.familyId(), 1, T), null).block();
            CreatedDocument two = documents.createOnce(Document.received(p.id(), p.familyId(), 1, T), null).block();
            assertThat(one.fresh()).isTrue();
            assertThat(two.fresh()).isTrue();
            assertThat(two.document().id()).isNotEqualTo(one.document().id());
            assertThat(scalar("SELECT count(*) FROM " + BaymaxSchema.NAME + ".document WHERE family_id = '" + p.familyId()
                    + "' AND upload_key IS NULL")).isEqualTo("2");
        });
    }

    @Test
    void theDatabaseRefusesASecondRowWithTheKeyFromAnyWriterButNotTwoRowsWithoutOne() {
        runner().run(context -> {
            PatientProfile p = patient(context, "+8801700000723");
            DatabaseClient db = context.getBean(DatabaseClient.class);
            String insert = "INSERT INTO " + BaymaxSchema.NAME + ".document (patient_id, family_id, upload_key) VALUES (:p, :f, :k)";
            db.sql(insert).bind("p", p.id()).bind("f", p.familyId()).bind("k", "k-dup-0001").then().block();
            assertThatThrownBy(() -> db.sql(insert).bind("p", p.id()).bind("f", p.familyId()).bind("k", "k-dup-0001").then().block())
                    .hasMessageContaining("document_upload_key_uq");
            assertThatThrownBy(() -> db.sql(insert).bind("p", p.id()).bind("f", p.familyId()).bind("k", "bad key!").then().block())
                    .hasMessageContaining("document_upload_key_check");
            db.sql(insert).bind("p", p.id()).bind("f", p.familyId()).bind("k", "k-dup-0002").then().block();   // a well-formed new key
            String plain = "INSERT INTO " + BaymaxSchema.NAME + ".document (patient_id, family_id) VALUES (:p, :f)";
            db.sql(plain).bind("p", p.id()).bind("f", p.familyId()).then().block();
            db.sql(plain).bind("p", p.id()).bind("f", p.familyId()).then().block();   // WhatsApp/API rows: no key, no conflict
            assertThat(scalar("SELECT count(*) FROM " + BaymaxSchema.NAME + ".document WHERE family_id = '" + p.familyId() + "'"))
                    .isEqualTo("4");
        });
    }

    /** Two presses racing on separate connections: exactly one is fresh, both answer the same id, one row exists. */
    @Test
    void twoConcurrentPressesWithOneKeyMakeOneDocument() {
        runner().run(context -> {
            PatientProfile p = patient(context, "+8801700000724");
            DocumentRecordPort documents = context.getBean(DocumentRecordPort.class);
            Mono<CreatedDocument> press = Mono.defer(() -> documents.createOnce(Document.received(p.id(), p.familyId(), 1, T), "k-race-0001"))
                    .subscribeOn(Schedulers.boundedElastic());
            var both = Mono.zip(press, press).block();
            assertThat(List.of(both.getT1().fresh(), both.getT2().fresh())).containsExactlyInAnyOrder(true, false);
            assertThat(both.getT1().document().id()).isEqualTo(both.getT2().document().id());
            assertThat(scalar("SELECT count(*) FROM " + BaymaxSchema.NAME + ".document WHERE upload_key = 'k-race-0001'")).isEqualTo("1");
        });
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
