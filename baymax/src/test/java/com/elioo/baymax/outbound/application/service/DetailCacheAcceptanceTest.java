package com.elioo.baymax.outbound.application.service;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.outbound.application.port.in.ExplainDocumentUseCase;
import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.DeliveryOutcome;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.outbound.domain.UrgencyAssessment;
import com.elioo.baymax.wa.WaFixtures;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 on real Postgres: the DETAIL cache read ({@code latestOfKind}), the reasons it is keyed on, and the atomic
 * delivery claim. The in-memory fake cannot fail like this database does (V14's CHECKs, concurrent UPDATEs), so
 * every constraint path the wiring tests touch is exercised here for real. Columns are read over JDBC, not from
 * the returned record: the claim lives in the database.
 */
@Testcontainers
class DetailCacheAcceptanceTest {

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
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                                + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + POSTGRES.getJdbcUrl() + "&sslmode=disable",
                        "baymax.flyway.user=" + POSTGRES.getUsername(),
                        "baymax.flyway.password=" + POSTGRES.getPassword());
    }

    private static OutboundMessage row(UUID family, UUID document, OutboundMessage.Kind kind, String body, Instant at) {
        return new OutboundMessage(null, family, null, document, kind, Urgency.ROUTINE, List.of(), body,
                OutboundMessage.GateStatus.RELEASED, null, null, null, null, at);
    }

    @Test
    void latestOfKindIsTheNewestRowOfThatKindForThatDocumentOnly() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T09:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000801", "Rahim", FamilyAccount.Plan.FREE, t, t)).block();
            UUID doc = UUID.randomUUID(), other = UUID.randomUUID();

            messages.save(row(family.id(), doc, OutboundMessage.Kind.DETAIL, "পুরনো বিস্তারিত", t)).block();
            OutboundMessage newer = messages.save(row(family.id(), doc, OutboundMessage.Kind.DETAIL, "নতুন বিস্তারিত", t.plusSeconds(60))).block();
            messages.save(row(family.id(), doc, OutboundMessage.Kind.EXPLANATION, "ব্যাখ্যা", t.plusSeconds(120))).block();
            messages.save(row(family.id(), other, OutboundMessage.Kind.DETAIL, "অন্য কাগজ", t.plusSeconds(180))).block();

            assertThat(messages.latestOfKind(doc, OutboundMessage.Kind.DETAIL).block())
                    .extracting(OutboundMessage::id).isEqualTo(newer.id());
            assertThat(messages.latestOfKind(doc, OutboundMessage.Kind.RETAKE).blockOptional()).isEmpty();
            assertThat(messages.latestOfKind(UUID.randomUUID(), OutboundMessage.Kind.DETAIL).blockOptional()).isEmpty();
        });
    }

    /**
     * The web stores a DETAIL without delivering it; WhatsApp delivers that same row later. V14 must accept the
     * provider's acceptance on the stored row, and sent_at must come from that acceptance alone.
     */
    @Test
    void aDetailStoredUndeliveredAndDeliveredLaterRecordsTheOutcomeOnTheSameRow() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T09:30:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000802", "Karim", FamilyAccount.Plan.FREE, t, t)).block();
            OutboundMessage stored = messages.save(row(family.id(), UUID.randomUUID(), OutboundMessage.Kind.DETAIL, "বিস্তারিত", t)).block();
            assertThat(column(stored.id(), "sent_at")).as("stored, not delivered").isNull();
            assertThat(column(stored.id(), "delivery_status")).isNull();

            String wamid = WaFixtures.wamid("8801700000802");
            messages.recordDelivery(stored.id(), DeliveryOutcome.sent(wamid), t.plusSeconds(30)).block();

            assertThat(column(stored.id(), "sent_at")).isNotNull();
            assertThat(column(stored.id(), "delivery_status")).isEqualTo("sent");
            assertThat(column(stored.id(), "provider_message_id")).isEqualTo(wamid);
            assertThat(messages.latestOfKind(stored.documentId(), OutboundMessage.Kind.DETAIL).block().sentAt()).isNotNull();
        });
    }

    /**
     * Rows saved at the same instant (a FAILED_SAFETY attempt and its released successor) read back deliverable first.
     * The ids are chosen so that the last tie-break, {@code id DESC}, would pick the FAILED_SAFETY row on its own: only
     * the deliverable-first clause can make this pass, every time, rather than on a coin flip over random uuids.
     */
    @Test
    void rowsSavedAtTheSameInstantComeBackDeliverableFirstOnEveryRead() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T10:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000803", "Salma", FamilyAccount.Plan.FREE, t, t)).block();
            UUID doc = UUID.randomUUID();
            UUID failedId = UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff");
            UUID releasedId = UUID.fromString("00000000-0000-4000-8000-000000000001");
            execute("INSERT INTO baymax.outbound_message (id, family_id, document_id, kind, urgency, urgency_reasons, body, gate_status, created_at) "
                    + "VALUES ('" + failedId + "','" + family.id() + "','" + doc + "','explanation','now','[]',NULL,'failed_safety','" + t + "')");
            execute("INSERT INTO baymax.outbound_message (id, family_id, document_id, kind, urgency, urgency_reasons, body, gate_status, created_at) "
                    + "VALUES ('" + releasedId + "','" + family.id() + "','" + doc + "','explanation','routine','[]','কোডের লেখা','released','" + t + "')");

            assertThat(messages.latestOfKind(doc, OutboundMessage.Kind.EXPLANATION).block().id())
                    .as("the FAILED_SAFETY row won the same-instant tie").isEqualTo(releasedId);
        });
    }

    /** urgency_reasons carry marker names, and lab names carry commas: the stored set must equal the fresh one. */
    @Test
    void urgencyReasonsSurviveTheRoundTripEvenWhenAMarkerNameHasAComma() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T11:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000804", "Nasrin", FamilyAccount.Plan.FREE, t, t)).block();
            Map<String, Object> value = new HashMap<>(Map.of(
                    "name", "Cholesterol, Total", "value", "450", "ref_low", "0", "ref_high", "200"));
            Document document = new Document(UUID.randomUUID(), UUID.randomUUID(), family.id(), "lab_report", null, null,
                    "{}", 0.9, Document.Status.DONE, null, null, null, 1, t, t);
            UrgencyAssessment assessed = new UrgencyService(new BaymaxProperties(), Clock.fixed(t, ZoneOffset.UTC))
                    .assess(new DocumentFacts(document, "Ma", List.of(value), List.of(), Map.of(), "https://x/y"));
            assertThat(assessed.reasons()).as("vacuity: the fixture must produce a reason with a comma in it")
                    .anySatisfy(r -> assertThat(r).contains(","));

            messages.save(new OutboundMessage(null, family.id(), null, document.id(), OutboundMessage.Kind.DETAIL,
                    assessed.level(), assessed.reasons(), "বিস্তারিত", OutboundMessage.GateStatus.RELEASED,
                    null, null, null, null, t)).block();
            OutboundMessage readBack = messages.latestOfKind(document.id(), OutboundMessage.Kind.DETAIL).block();

            assertThat(readBack.urgencyReasons()).containsExactlyElementsOf(assessed.reasons());
            assertThat(ExplanationService.stillCurrent(readBack, assessed))
                    .as("a cache hit, so a second ask makes no model call").isTrue();
        });
    }

    /** Rows written before the JSON encoding still read as they always did. */
    @Test
    void aLegacyCommaJoinedReasonListStillReads() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T11:30:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000805", "Rokeya", FamilyAccount.Plan.FREE, t, t)).block();
            UUID doc = UUID.randomUUID();
            execute("INSERT INTO baymax.outbound_message (family_id, document_id, kind, urgency, urgency_reasons, body, gate_status) "
                    + "VALUES ('" + family.id() + "','" + doc + "','detail','this_week','follow_up_past_due,value_outside_range:ldl|stopgap','x','released')");

            assertThat(messages.latestOfKind(doc, OutboundMessage.Kind.DETAIL).block().urgencyReasons())
                    .containsExactly("follow_up_past_due", "value_outside_range:ldl|stopgap");
        });
    }

    @Test
    void sixteenConcurrentClaimsOfOneRowYieldExactlyOne() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T12:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000806", "Shirin", FamilyAccount.Plan.FREE, t, t)).block();
            OutboundMessage stored = messages.save(row(family.id(), UUID.randomUUID(), OutboundMessage.Kind.DETAIL, "বিস্তারিত", t)).block();

            List<OutboundMessage> won = Flux.range(0, 16)
                    .flatMap(i -> messages.claimDelivery(stored.id()).subscribeOn(Schedulers.parallel()), 16)
                    .collectList().block(Duration.ofSeconds(30));

            assertThat(won).as("claims won on one row").hasSize(1);
            assertThat(column(stored.id(), "delivery_status")).isEqualTo("pending");
        });
    }

    /** Both directions: a failed delivery may be claimed again (the family wrote, the window is open); a sent one may not. */
    @Test
    void aFailedDeliveryMayBeClaimedAgainButASentOneMayNot() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T12:30:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000807", "Laila", FamilyAccount.Plan.FREE, t, t)).block();
            OutboundMessage stored = messages.save(row(family.id(), UUID.randomUUID(), OutboundMessage.Kind.DETAIL, "বিস্তারিত", t)).block();

            assertThat(messages.claimDelivery(stored.id()).blockOptional()).isPresent();
            assertThat(messages.claimDelivery(stored.id()).blockOptional()).as("pending: someone holds it").isEmpty();
            messages.recordDelivery(stored.id(), DeliveryOutcome.failed(DeliveryOutcome.WINDOW_EXPIRED), t.plusSeconds(5)).block();
            assertThat(messages.claimDelivery(stored.id()).blockOptional()).as("failed: may be tried again").isPresent();
            messages.recordDelivery(stored.id(), DeliveryOutcome.sent(WaFixtures.wamid("8801700000807")), t.plusSeconds(9)).block();
            assertThat(messages.claimDelivery(stored.id()).blockOptional()).as("sent: never again").isEmpty();
        });
    }

    /** The two non-deliverable directions: a PENDING row and a FAILED_SAFETY row (no body) can never be claimed. */
    @Test
    void aRowThatIsNotReleasedOrApprovedCannotBeClaimed() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T12:45:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000810", "Parveen", FamilyAccount.Plan.FREE, t, t)).block();
            OutboundMessage pending = messages.save(new OutboundMessage(null, family.id(), null, UUID.randomUUID(),
                    OutboundMessage.Kind.DETAIL, Urgency.NOW, List.of(), "অপরীক্ষিত", OutboundMessage.GateStatus.PENDING,
                    null, null, null, null, t)).block();
            OutboundMessage failed = messages.save(new OutboundMessage(null, family.id(), null, UUID.randomUUID(),
                    OutboundMessage.Kind.DETAIL, Urgency.NOW, List.of(), null, OutboundMessage.GateStatus.FAILED_SAFETY,
                    null, null, null, null, t)).block();

            assertThat(messages.claimDelivery(pending.id()).blockOptional()).isEmpty();
            assertThat(messages.claimDelivery(failed.id()).blockOptional()).isEmpty();
            assertThat(column(pending.id(), "delivery_status")).isNull();
            assertThat(column(failed.id(), "delivery_status")).isNull();
        });
    }

    /** The log adapter's path stamps sent_at and clears the claim, inside V14's CHECKs. */
    @Test
    void theLogAdapterPathStampsSentAtAndClearsTheClaim() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T13:00:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000808", "Hasina", FamilyAccount.Plan.FREE, t, t)).block();
            OutboundMessage stored = messages.save(row(family.id(), UUID.randomUUID(), OutboundMessage.Kind.DETAIL, "বিস্তারিত", t)).block();

            messages.claimDelivery(stored.id()).block();
            messages.markSent(stored.id(), t.plusSeconds(1)).block();

            assertThat(column(stored.id(), "sent_at")).isNotNull();
            assertThat(column(stored.id(), "delivery_status")).isNull();
        });
    }

    /**
     * The two rows whose delivery_status is NULL with sent_at set — one stamped by the log adapter (markSent clears the
     * claim) and one written before V13 — rest on {@code sent_at IS NULL} alone. Neither may be claimed again.
     */
    @Test
    void aRowAlreadyStampedSentCannotBeClaimedEvenWithNoDeliveryStatus() {
        runner().run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            Instant t = Instant.parse("2026-10-20T13:15:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000811", "Ayesha", FamilyAccount.Plan.FREE, t, t)).block();
            OutboundMessage logged = messages.save(row(family.id(), UUID.randomUUID(), OutboundMessage.Kind.DETAIL, "বিস্তারিত", t)).block();
            messages.claimDelivery(logged.id()).block();
            messages.markSent(logged.id(), t.plusSeconds(1)).block();
            UUID legacy = UUID.randomUUID();
            execute("INSERT INTO baymax.outbound_message (id, family_id, document_id, kind, urgency, urgency_reasons, body, gate_status, sent_at) "
                    + "VALUES ('" + legacy + "','" + family.id() + "','" + UUID.randomUUID() + "','detail','routine','[]','x','released','" + t + "')");
            assertThat(column(logged.id(), "delivery_status")).as("vacuity: only sent_at stands between it and a claim").isNull();
            assertThat(column(legacy, "delivery_status")).isNull();

            assertThat(messages.claimDelivery(logged.id()).blockOptional()).as("the log-stamped row was claimed again").isEmpty();
            assertThat(messages.claimDelivery(legacy).blockOptional()).as("a legacy sent row was claimed again").isEmpty();
        });
    }

    /** End to end on Postgres: two asks deliver one stored row at the same instant; the channel sees it once. */
    @Test
    void twoConcurrentDeliversOfOneRowHandItToTheChannelOnce() {
        AtomicInteger calls = new AtomicInteger();
        // registered programmatically, never as a @Configuration: the auto-configuration component-scans
        // com.elioo.baymax, so an annotated test class here would leak a third @Primary into every other context test
        MessageDeliveryPort counting = m -> {
            int n = calls.incrementAndGet();
            return Mono.just(DeliveryOutcome.sent(WaFixtures.wamid("8801700000809", n)));
        };
        runner().withBean("countingDelivery", MessageDeliveryPort.class, () -> counting, bd -> bd.setPrimary(true)).run(context -> {
            OutboundMessagePort messages = context.getBean(OutboundMessagePort.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            ExplainDocumentUseCase explainer = context.getBean(ExplainDocumentUseCase.class);
            Instant t = Instant.parse("2026-10-20T13:30:00Z");
            FamilyAccount family = records.createFamily(new FamilyAccount(
                    null, "+8801700000809", "Momena", FamilyAccount.Plan.FREE, t, t)).block();
            OutboundMessage stored = messages.save(row(family.id(), UUID.randomUUID(), OutboundMessage.Kind.DETAIL, "বিস্তারিত", t)).block();

            Flux.range(0, 2)
                    .flatMap(i -> explainer.deliver(stored).subscribeOn(Schedulers.parallel()), 2)
                    .collectList().block(Duration.ofSeconds(30));

            assertThat(calls.get()).as("deliveries handed to the channel for one row").isEqualTo(1);
            assertThat(column(stored.id(), "delivery_status")).isEqualTo("sent");
            assertThat(column(stored.id(), "sent_at")).isNotNull();
        });
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String column(UUID id, String name) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + name + " FROM baymax.outbound_message WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
