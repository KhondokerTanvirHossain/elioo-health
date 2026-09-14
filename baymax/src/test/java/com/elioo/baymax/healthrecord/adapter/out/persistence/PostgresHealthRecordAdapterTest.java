package com.elioo.baymax.healthrecord.adapter.out.persistence;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.FamilyActivity;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.healthrecord.domain.ShareMember;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V4 schema, the port's queries, the consent-immutability trigger and the free-tier document count, on real Postgres. */
@Testcontainers
class PostgresHealthRecordAdapterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withPropertyValues(
                        "baymax.enabled=true", "llm.provider=groq", "llm.groq.api-key=test-key",
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + jdbcUrl(),
                        "baymax.flyway.user=" + POSTGRES.getUsername(),
                        "baymax.flyway.password=" + POSTGRES.getPassword());
    }

    @Test
    void familiesPatientsSharesConsentImmutabilityAndFreeTierDocuments() {
        runner().run(context -> {
            HealthRecordPort port = context.getBean(HealthRecordPort.class);
            FreeTierUseCase freeTier = context.getBean(FreeTierUseCase.class);
            Instant t = Instant.parse("2026-09-13T08:00:00Z");

            FamilyAccount family = port.createFamily(new FamilyAccount(null, "+8801700000001", "Rahim", FamilyAccount.Plan.FREE, t, t)).block();
            assertThat(family.id()).isNotNull();
            assertThat(port.findFamilyByWhatsapp("+8801700000001").block().id()).isEqualTo(family.id());
            assertThat(port.findFamilyByWhatsapp("+8801700000009").blockOptional()).isEmpty();

            // duplicate number is a 409 at the boundary
            StepVerifier.create(port.createFamily(new FamilyAccount(null, "+8801700000001", "Other", FamilyAccount.Plan.FREE, t, t)))
                    .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 409)
                    .verify();

            PatientProfile ma = port.createPatient(new PatientProfile(null, family.id(), "Ma", 74, PatientProfile.Sex.FEMALE,
                    List.of("diabetes", "hypertension"), t, t)).block();
            assertThat(ma.chronicFlags()).containsExactly("diabetes", "hypertension");
            assertThat(port.countPatients(family.id()).block()).isEqualTo(1L);
            assertThat(port.patientsOf(family.id()).collectList().block()).extracting(PatientProfile::name).containsExactly("Ma");

            ShareMember sib = port.addShareMember(new ShareMember(null, ma.id(), "+8801811111111", t, null)).block();
            assertThat(sib.id()).isNotNull();
            assertThat(port.countShareMembers(ma.id()).block()).isEqualTo(1L);
            StepVerifier.create(port.addShareMember(new ShareMember(null, ma.id(), "+8801811111111", t, null)))
                    .expectErrorMatches(e -> e instanceof BaymaxException b && b.reason().equals("share_member_exists"))
                    .verify();

            // consent timestamps cannot be changed even by raw SQL; other columns can
            assertThatThrownBy(() -> sql("update baymax.family_account set terms_accepted_at = now() where id = '" + family.id() + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> sql("update baymax.patient_profile set proxy_consent_at = now() where id = '" + ma.id() + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            sql("update baymax.family_account set owner_name = 'Rahim Uddin' where id = '" + family.id() + "'");
            assertThat(port.findFamily(family.id()).block().ownerName()).isEqualTo("Rahim Uddin");

            // three documents this calendar month → 402; plan=family → allowed. Since BMX-2 the count comes
            // from the document table, so seed documents as well as the images that belong to them.
            YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);
            String inMonth = thisMonth.atDay(2).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
            String lastMonth = thisMonth.minusMonths(1).atDay(2).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
            for (int i = 1; i <= 3; i++) {
                UUID doc = UUID.randomUUID();
                seedDocument(family.id(), ma.id(), doc, inMonth);
                seedObject(family.id(), ma.id(), doc, "page-1", inMonth);
                seedObject(family.id(), ma.id(), doc, "page-2", inMonth); // two objects, one document
            }
            UUID older = UUID.randomUUID();
            seedDocument(family.id(), ma.id(), older, lastMonth);
            seedObject(family.id(), ma.id(), older, "page-1", lastMonth); // not this month

            StepVerifier.create(freeTier.checkCanUploadDocument(family.id()))
                    .expectErrorMatches(e -> e instanceof FreeTierExceededException f && f.reason().equals("free_tier_documents"))
                    .verify();
            sql("update baymax.family_account set plan = 'family' where id = '" + family.id() + "'");
            StepVerifier.create(freeTier.checkCanUploadDocument(family.id())).verifyComplete();

            List<FamilyActivity> activity = port.familyActivity(
                    thisMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    thisMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()).collectList().block();
            FamilyActivity mine = activity.stream().filter(a -> a.familyId().equals(family.id())).findFirst().orElseThrow();
            assertThat(mine.plan()).isEqualTo(FamilyAccount.Plan.FAMILY);
            assertThat(mine.patients()).isEqualTo(1);
            assertThat(mine.documentsInWindow()).isEqualTo(3);
            assertThat(mine.lastDocumentAt()).isEqualTo(Instant.parse(inMonth));

            // hard delete removes everything for the family and detaches cost rows
            UUID doc = port.documentIdsOf(family.id()).blockFirst();
            sql("insert into baymax.ai_call_log (document_id, purpose, provider, model) values ('" + doc + "', 'extract', 'groq', 'm')");
            List<UUID> docs = port.documentIdsOf(family.id()).collectList().block();
            assertThat(docs).hasSize(4);
            sql("delete from baymax.stored_object where family_id = '" + family.id() + "'"); // what DocumentStorageService does first
            sql("delete from baymax.document where family_id = '" + family.id() + "'"); // documents reference the family
            assertThat(port.deleteFamily(family.id(), docs).block()).isEqualTo(1L);
            assertThat(scalar("select count(*) from baymax.share_member")).isEqualTo("0");
            assertThat(scalar("select count(*) from baymax.patient_profile")).isEqualTo("0");
            assertThat(scalar("select count(*) from baymax.family_account")).isEqualTo("0");
            assertThat(scalar("select count(*) from baymax.ai_call_log where document_id is null")).isEqualTo("1");
            assertThat(scalar("select count(*) from baymax.ai_call_log")).isEqualTo("1");
        });
    }

    private static void seedDocument(UUID family, UUID patient, UUID doc, String createdAt) throws Exception {
        sql("insert into baymax.document (id, patient_id, family_id, status, page_count, created_at, updated_at) values ('"
                + doc + "','" + patient + "','" + family + "','DONE',1,'" + createdAt + "','" + createdAt + "')");
    }

    private static void seedObject(UUID family, UUID patient, UUID doc, String name, String createdAt) throws Exception {
        sql("insert into baymax.stored_object (family_id, patient_id, document_id, kind, page_no, storage_key, content_type, size_bytes, created_at) values ('"
                + family + "','" + patient + "','" + doc + "','page'," + (name.equals("page-1") ? 1 : 2) + ",'"
                + family + "/" + patient + "/" + doc + "/" + name + ".jpg','image/jpeg',100,'" + createdAt + "')");
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String jdbcUrl() {
        return POSTGRES.getJdbcUrl() + "&sslmode=disable";
    }
}
