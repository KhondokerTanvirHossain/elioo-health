package com.elioo.baymax.wa.adapter.out.persistence;

import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.adapter.out.persistence.PostgresNudgeAdapterTest;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.application.port.out.InboundMessagePort;
import com.elioo.baymax.wa.domain.InboundMessage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F9a on real Postgres. Meta re-delivers webhooks; the only thing that makes "answered once" true is the unique key, so
 * the duplicate path is exercised where it can actually fail — a mocked port with a HashSet would turn a duplicate into
 * a silent no-op exactly where Postgres raises 23505 (DR-22).
 */
@Testcontainers
class PostgresInboundMessageAdapterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private ReactiveWebApplicationContextRunner runner() {
        return PostgresNudgeAdapterTest.runnerFor(POSTGRES);
    }

    /** The adapter stores what it is given; the service gives it an HMAC hex. */
    private static String key(int n) {
        return String.format("%064x", n);
    }

    private static FamilyAccount family(HealthRecordPort records, String number) {
        Instant t = Instant.parse("2026-10-17T08:00:00Z");
        return records.createFamily(new FamilyAccount(null, number, "Rahim", FamilyAccount.Plan.FREE, t, t)).block();
    }

    @Test
    void aRedeliveredMessageIsClaimedOnceAndTheSecondClaimIsEmptyNotAnError() {
        runner().run(context -> {
            InboundMessagePort inbound = context.getBean(InboundMessagePort.class);
            FamilyAccount f = family(context.getBean(HealthRecordPort.class), "+8801700000601");
            InboundMessage message = InboundMessage.received(key(1), f.id(), "text", "বিস্তারিত", null,
                    Instant.parse("2026-10-17T08:00:00Z"));

            StepVerifier.create(inbound.claim(message)).assertNext(row -> {
                assertThat(row.id()).isNotNull();
                assertThat(row.providerKey()).isEqualTo(key(1));
                assertThat(row.familyId()).isEqualTo(f.id());
                assertThat(row.body()).isEqualTo("বিস্তারিত");
                assertThat(row.providerTimestamp()).isEqualTo(Instant.parse("2026-10-17T08:00:00Z"));
                assertThat(row.receivedAt()).isNotNull();
                assertThat(row.handledAt()).isNull();
            }).verifyComplete();
            // the identical callback again: "already have it" is EMPTY — not an error (a 503 would make Meta retry for a
            // week) and not a second row (a second answer)
            StepVerifier.create(inbound.claim(message)).verifyComplete();
            assertThat(scalar("select count(*) from baymax.inbound_message where provider_key = '" + key(1) + "'")).isEqualTo("1");
        });
    }

    @Test
    void sixteenSimultaneousDeliveriesOfOneMessageYieldExactlyOneClaim() {
        runner().run(context -> {
            InboundMessagePort inbound = context.getBean(InboundMessagePort.class);
            InboundMessage message = InboundMessage.received(key(2), null, "image", null, "MEDIA-9", null);

            List<InboundMessage> claimed = Flux.range(0, 16)
                    .flatMap(i -> inbound.claim(message).subscribeOn(Schedulers.parallel()), 16)
                    .collectList().block(Duration.ofSeconds(30));

            assertThat(claimed).as("claims won by simultaneous deliveries of one message").hasSize(1);
            assertThat(scalar("select count(*) from baymax.inbound_message where provider_key = '" + key(2) + "'")).isEqualTo("1");
        });
    }

    /** The dedupe and the privacy rules rest on the database, not on the adapter. */
    @Test
    void theDatabaseRefusesASecondRowARawIdAndTextWithoutAFamily() {
        runner().run(context -> {
            sql("insert into baymax.inbound_message (provider_key, type) values ('" + key(3) + "','text')");
            assertThatThrownBy(() -> sql("insert into baymax.inbound_message (provider_key, type) values ('" + key(3) + "','text')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("inbound_message_provider_key_key")
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
            assertThatThrownBy(() -> sql("insert into baymax.inbound_message (provider_key, type) values ('"
                    + WaFixtures.wamid("8801700000602") + "','text')"))
                    .as("a raw wamid is the phone number; it may not be the key").hasMessageContaining("inbound_message_provider_key_check");
            assertThatThrownBy(() -> sql("insert into baymax.inbound_message (provider_key, type) values ('"
                    + key(0xabcdef).toUpperCase() + "','text')"))
                    .as("lowercase hex only: one spelling per key, or the unique constraint has two").hasMessageContaining("inbound_message_provider_key_check");
            assertThatThrownBy(() -> sql("insert into baymax.inbound_message (provider_key, type, body) values ('" + key(4) + "','text','x')"))
                    .hasMessageContaining("inbound_message_body_needs_family");
        });
    }

    @Test
    void handlingIsRecordedOnceWithItsOutcome() {
        runner().run(context -> {
            InboundMessagePort inbound = context.getBean(InboundMessagePort.class);
            inbound.claim(InboundMessage.received(key(5), null, "text", null, null, null)).block();

            inbound.markHandled(key(5), "UNKNOWN_SENDER").block();
            inbound.markHandled(key(5), "FAILED").block();   // a late second writer does not overwrite

            assertThat(scalar("select outcome || '/' || (handled_at is not null) from baymax.inbound_message where provider_key = '"
                    + key(5) + "'")).isEqualTo("UNKNOWN_SENDER/true");
        });
    }

    @Test
    void aPhotoIsLinkedToItsPatientAndDocument() {
        runner().run(context -> {
            InboundMessagePort inbound = context.getBean(InboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            FamilyAccount f = family(records, "+8801700000603");
            Instant t = Instant.parse("2026-10-17T08:00:00Z");
            PatientProfile p = records.createPatient(new PatientProfile(null, f.id(), "মা", 70, PatientProfile.Sex.FEMALE,
                    List.of(), t, t)).block();
            UUID doc = UUID.randomUUID();
            sql("insert into baymax.document (id, patient_id, family_id, document_type, status, page_count) values ('"
                    + doc + "','" + p.id() + "','" + f.id() + "','lab_report','PROCESSING',1)");
            inbound.claim(InboundMessage.received(key(7), f.id(), "image", null, "MEDIA-7", null)).block();

            inbound.linkDocument(key(7), p.id(), doc).block();

            assertThat(scalar("select patient_id || '/' || document_id from baymax.inbound_message where provider_key = '"
                    + key(7) + "'")).isEqualTo(p.id() + "/" + doc);
            // the document going does not take the message's record with it, nor block the delete
            sql("delete from baymax.document where id = '" + doc + "'");
            assertThat(scalar("select (document_id is null) || '/' || patient_id from baymax.inbound_message where provider_key = '"
                    + key(7) + "'")).isEqualTo("true/" + p.id());
        });
    }

    /** Both directions: what is unhandled and older than the cutoff is counted; handled or newer is not. */
    @Test
    void onlyUnhandledMessagesReceivedBeforeTheCutoffAreCounted() {
        runner().run(context -> {
            InboundMessagePort inbound = context.getBean(InboundMessagePort.class);
            Instant cutoff = Instant.parse("2026-10-17T08:00:00Z");
            sql("insert into baymax.inbound_message (provider_key, type, received_at) values ('" + key(11) + "','text','2026-10-17T07:00:00Z')");
            sql("insert into baymax.inbound_message (provider_key, type, received_at) values ('" + key(12) + "','image','2026-10-17T07:30:00Z')");
            sql("insert into baymax.inbound_message (provider_key, type, received_at, handled_at, outcome) values ('"
                    + key(13) + "','text','2026-10-17T07:00:00Z','2026-10-17T07:00:05Z','REPLIED')");
            sql("insert into baymax.inbound_message (provider_key, type, received_at) values ('" + key(14) + "','text','2026-10-17T08:00:01Z')");

            assertThat(inbound.countUnhandledBefore(cutoff).block()).isEqualTo(2L);
        });
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
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
