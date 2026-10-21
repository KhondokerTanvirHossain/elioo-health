package com.elioo.baymax.wa.adapter.in;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.common.redact.Redact;
import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.wa.WaFixtures;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.web.application.service.PhoneHasher;
import com.elioo.healthcare.llm.config.LlmAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * F9a acceptance, over the real webhook, real wiring and real Postgres: Meta re-delivers a signed callback and the
 * family hears ONE reply. Only the Graph boundary is stubbed, recording what would have been sent.
 *
 * <p>The first two tests are the regression guard and compile on the pre-fix source (they use only the webhook, the
 * signature, the fixtures and SQL); they were replayed red there before the fix. The rest read {@code inbound_message}.
 */
@Testcontainers
class WaInboundIdempotencyAcceptanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static final String APP_SECRET = "88e3540dd539aabbccddeeff00112233";
    static final String DIGITS = "8801700000777";
    static final String NUMBER = "+" + DIGITS;
    static final List<String> SENT = new CopyOnWriteArrayList<>();

    static String callback(String wamid) {
        return """
                {"object":"whatsapp_business_account","entry":[{"changes":[{"value":{"messages":[
                  {"id":"%s","type":"text","from":"8801700000777","timestamp":"1792224000",
                   "text":{"body":"বিস্তারিত"}}]}}]}]}""".formatted(wamid);
    }

    /**
     * Graph, stubbed: records what would have been sent. Registered with withBean, never as a nested @Configuration:
     * BaymaxAutoConfiguration's component scan covers com.elioo.baymax, test classes included, and a scanned stub would
     * become a second primary WaMessagingPort in every other test's context.
     */
    static WaMessagingPort recordingGraph() {
        return new WaMessagingPort() {
            @Override
            public Mono<byte[]> downloadMedia(String mediaId) {
                return Mono.error(new IllegalStateException("no media in this test"));
            }

            @Override
            public Mono<String> sendText(String to, String body) {
                // lazy, like WaGraphClient: recorded when the send is subscribed, not when it is built
                return Mono.fromCallable(() -> {
                    SENT.add(body);
                    return WaFixtures.wamid(DIGITS, 100 + SENT.size());
                });
            }
        };
    }

    @BeforeEach
    void clear() {
        SENT.clear();
    }

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withBean("recordingGraph", WaMessagingPort.class, WaInboundIdempotencyAcceptanceTest::recordingGraph,
                        bd -> bd.setPrimary(true))
                .withPropertyValues(
                        "baymax.enabled=true", "llm.provider=groq", "llm.groq.api-key=test-key",
                        "baymax.wa.enabled=true",
                        "baymax.wa.token=EAA" + "x".repeat(200),
                        "baymax.wa.app-secret=" + APP_SECRET,
                        "baymax.wa.verify-token=78d41af3449a9bfbd560140166c6f2c2",
                        "baymax.wa.phone-number-id=1412074765313019",
                        "baymax.wa.waba-id=4407553579467225",
                        "baymax.wa.allowlist=" + NUMBER,
                        "baymax.auth.hmac-secret=" + "s".repeat(32),   // the inbound key is HMAC(wamid)
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + POSTGRES.getJdbcUrl() + "&sslmode=disable",
                        "baymax.flyway.user=" + POSTGRES.getUsername(),
                        "baymax.flyway.password=" + POSTGRES.getPassword());
    }

    @SuppressWarnings("unchecked")
    private static WebTestClient webhook(ApplicationContext context) {
        return WebTestClient.bindToRouterFunction((RouterFunction<ServerResponse>) context.getBean("baymaxWaRoutes")).build();
    }

    private static void deliver(WebTestClient webhook, byte[] body) throws Exception {
        deliver(webhook, body, 200);
    }

    private static void deliver(WebTestClient webhook, byte[] body, int status) throws Exception {
        webhook.post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isEqualTo(status);
    }

    /**
     * For a test whose defect is SILENCE: wait for a first reply but do not fail when none comes, so the red is the
     * count ("Expected size: 1 but was: 0"), never an await timeout that names nothing.
     */
    private static void settleAllowingNone() {
        try {
            await().atMost(Duration.ofSeconds(8)).until(() -> !SENT.isEmpty());
        } catch (org.awaitility.core.ConditionTimeoutException none) {
            return;
        }
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).until(() -> true);
    }

    private static String key(ApplicationContext context, String wamid) {
        return context.getBean(PhoneHasher.class).phone("wamid:" + wamid);   // providerId, spelled out (replayable)
    }

    /** Replies are sent from detached work: wait for the first, then give any second the same time before counting. */
    private static void settle() {
        await().atMost(Duration.ofSeconds(10)).until(() -> !SENT.isEmpty());
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).until(() -> true);
    }

    /** The sent bodies by copy key, so a red names the reply rather than printing Bangla. */
    private static List<String> keys() {
        String noDocument = new com.elioo.baymax.outbound.application.service.Copy().bn("wa.no_document", java.util.Map.of());
        return SENT.stream().map(b -> b.equals(noDocument) ? "<wa.no_document>" : "<other: " + b.length() + " chars>").toList();
    }

    @Test
    void theSameMessageDeliveredTwiceIsAnsweredOnce() {
        runner().run(context -> {
            seedFamily();
            byte[] body = callback(WaFixtures.wamid(DIGITS, 1)).getBytes(StandardCharsets.UTF_8);

            deliver(webhook(context), body);
            deliver(webhook(context), body);   // Meta re-delivers the identical signed callback
            settle();

            assertThat(keys()).as("replies to one WhatsApp message id that Meta delivered twice").hasSize(1);
        });
    }

    @Test
    void twoSimultaneousDeliveriesOfOneMessageAreAnsweredOnce() {
        runner().run(context -> {
            seedFamily();
            byte[] body = callback(WaFixtures.wamid(DIGITS, 2)).getBytes(StandardCharsets.UTF_8);
            WebTestClient client = webhook(context);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                List<Future<?>> both = List.of(
                        pool.submit(() -> {
                            go.await();
                            deliver(client, body);
                            return null;
                        }),
                        pool.submit(() -> {
                            go.await();
                            deliver(client, body);
                            return null;
                        }));
                go.countDown();
                for (Future<?> f : both) {
                    f.get();
                }
            } finally {
                pool.shutdownNow();
            }
            settle();

            assertThat(keys()).as("replies to one WhatsApp message id delivered twice at the same instant").hasSize(1);
        });
    }

    private static void seedFamily() throws Exception {
        sql("insert into baymax.family_account (whatsapp_number, owner_name, terms_accepted_at) values ('"
                + NUMBER + "','Rahim', now()) on conflict (whatsapp_number) do nothing");
    }

    // ---- these read inbound_message and are not part of the replay ----

    @Test
    void aClaimedMessageRecordsItsFamilyTextAndOutcome() {
        runner().run(context -> {
            seedFamily();
            String wamid = WaFixtures.wamid(DIGITS, 3);
            // providerId, spelled out: this file compiles on the pre-fix source, where the guard was replayed
            String key = context.getBean(PhoneHasher.class).phone("wamid:" + wamid);
            deliver(webhook(context), callback(wamid).getBytes(StandardCharsets.UTF_8));
            settle();
            await().atMost(Duration.ofSeconds(5)).until(() -> "1".equals(scalar(
                    "select count(*) from baymax.inbound_message where handled_at is not null and provider_key = '" + key + "'")));

            assertThat(scalar("select (family_id is not null) || '/' || body || '/' || outcome from baymax.inbound_message "
                    + "where provider_key = '" + key + "'")).isEqualTo("true/বিস্তারিত/REPLIED");
        });
    }

    /** No phone number and no wamid in the table for an unknown sender, in any column: a wamid IS the number. */
    @Test
    void anUnknownSendersMessageLeavesNoNumberAndNoWamidInTheTable() {
        runner().run(context -> {
            String stranger = "8801700000999";
            String wamid = WaFixtures.wamid(stranger, 4);
            String key = context.getBean(PhoneHasher.class).phone("wamid:" + wamid);   // providerId, spelled out
            deliver(webhook(context), callback(wamid).replace(DIGITS, stranger).getBytes(StandardCharsets.UTF_8));
            await().atMost(Duration.ofSeconds(10)).until(() -> "1".equals(scalar(
                    "select count(*) from baymax.inbound_message where provider_key = '" + key + "'")));

            String row = scalar("select row_to_json(t)::text from baymax.inbound_message t where provider_key = '" + key + "'");
            assertThat(row).contains("\"family_id\":null").contains("\"body\":null");
            assertThat(row).as("a wamid is the sender's number; neither may be stored")
                    .doesNotContain(wamid).doesNotContain(wamid.substring("wamid.".length(), "wamid.".length() + 16))
                    .doesNotContain(stranger).doesNotContain(WaFixtures.decodedDigits(wamid))
                    .doesNotContain("বিস্তারিত");
        });
    }

    /** A re-delivery's log lines carry digests: never the wamid, never the number. */
    @Test
    void noLogLineCarriesTheWamidOrTheNumber() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        try {
            runner().run(context -> {
                seedFamily();
                String wamid = WaFixtures.wamid(DIGITS, 5);
                byte[] body = callback(wamid).getBytes(StandardCharsets.UTF_8);
                deliver(webhook(context), body);
                deliver(webhook(context), body);
                settle();
                String all = logged.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
                assertThat(all).contains("wa inbound duplicate ref=" + Redact.id(wamid));
                assertThat(all).doesNotContain(wamid).doesNotContain(DIGITS).doesNotContain(WaFixtures.decodedDigits(wamid));
            });
        } finally {
            root.detachAppender(logged);
        }
    }

    // ---- a claim committed and its dispatch lost (F9a review, finding 8) ----

    /**
     * The claim's INSERT commits in autocommit; the dispatch runs only when its answer comes back. If Meta hangs up in
     * between, Reactor Netty cancels the request, the dispatch never runs, and Meta re-delivers because it never got a
     * 200: that retry must not be ignored as a duplicate of a message nobody answered. The claim's answer is slowed here
     * (the row is committed first) and the request cancelled once the row is in the table.
     */
    @Test
    void aClaimThatCommittedBeforeMetaHungUpIsStillAnsweredOnce() {
        runner().run(context -> {
            seedFamily();
            String wamid = WaFixtures.wamid(DIGITS, 6);
            byte[] body = callback(wamid).getBytes(StandardCharsets.UTF_8);
            com.elioo.baymax.wa.application.service.WaIntakeService slow =
                    org.mockito.Mockito.spy(context.getBean(com.elioo.baymax.wa.application.service.WaIntakeService.class));
            org.mockito.Mockito.doAnswer(inv -> ((Mono<?>) inv.callRealMethod()).delayElement(Duration.ofSeconds(2)))
                    .when(slow).claim(org.mockito.ArgumentMatchers.any());
            WaWebhookHandler handler = new WaWebhookHandler(
                    context.getBean(com.elioo.baymax.config.BaymaxProperties.class), new com.fasterxml.jackson.databind.ObjectMapper(), slow);
            org.springframework.mock.web.reactive.function.server.MockServerRequest request =
                    org.springframework.mock.web.reactive.function.server.MockServerRequest.builder()
                            .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET)).body(Mono.just(body));

            reactor.core.Disposable exchange = handler.receive(request).subscribe();
            await().atMost(Duration.ofSeconds(10)).until(() -> "1".equals(scalar(
                    "select count(*) from baymax.inbound_message where provider_key = '" + key(context, wamid) + "'")));
            exchange.dispose();                    // Meta hung up after the commit, before any answer
            deliver(webhook(context), body);       // and re-delivers, because it never got its 200
            settleAllowingNone();

            assertThat(keys()).as("replies to a message whose claim committed before Meta hung up").hasSize(1);
        });
    }

    /**
     * A deploy SIGKILLs the container between a claim's commit and its dispatch; Meta never got a 200 and re-delivers to
     * the NEW instance. The row is from before this instance started and was never handled: reclaimed and answered.
     */
    @Test
    void aMessageAnEarlierInstanceClaimedAndNeverAnsweredIsAnsweredWhenMetaReDeliversIt() {
        runner().run(context -> {
            seedFamily();
            String wamid = WaFixtures.wamid(DIGITS, 7);
            sql("insert into baymax.inbound_message (provider_key, type, received_at) values ('" + key(context, wamid)
                    + "','text', now() - interval '1 hour')");
            byte[] body = callback(wamid).getBytes(StandardCharsets.UTF_8);

            deliver(webhook(context), body);
            deliver(webhook(context), body);   // and once more: reclaimed once, then a duplicate
            settleAllowingNone();

            assertThat(keys()).as("replies to a message an earlier instance claimed and never answered").hasSize(1);
        });
    }

    /** The other direction: a row received after THIS instance started is in flight here and is never taken again. */
    @Test
    void aMessageInFlightInThisInstanceIsNeverReclaimed() {
        runner().run(context -> {
            seedFamily();
            String wamid = WaFixtures.wamid(DIGITS, 8);
            sql("insert into baymax.inbound_message (provider_key, type, received_at) values ('" + key(context, wamid)
                    + "','text', now() + interval '1 minute')");

            deliver(webhook(context), callback(wamid).getBytes(StandardCharsets.UTF_8));
            await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).until(() -> true);

            assertThat(keys()).as("a second answer to a message this instance is still handling").isEmpty();
        });
    }

    // ---- a failed claim is a 503 and the retry is answered once (finding 7); errors split by kind (4, 13) ----

    /** The table cannot be written: 503 and no reply; it comes back, Meta retries the same bytes, ONE reply. */
    @Test
    void whenTheClaimCannotBeWrittenTheAnswerIs503AndMetasRetryIsAnsweredOnce() {
        runner().run(context -> {
            seedFamily();
            byte[] body = callback(WaFixtures.wamid(DIGITS, 9)).getBytes(StandardCharsets.UTF_8);
            sql("alter table baymax.inbound_message rename to inbound_message_off");
            try {
                deliver(webhook(context), body, 503);
                await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).until(() -> true);
                assertThat(SENT).as("nothing recorded, nothing answered").isEmpty();
            } finally {
                sql("alter table baymax.inbound_message_off rename to inbound_message");
            }
            deliver(webhook(context), body);
            deliver(webhook(context), body);
            settle();

            assertThat(keys()).as("replies after a 503 and two retries of the same callback").hasSize(1);
        });
    }

    /**
     * One callback, two messages, and only the second claim fails: the first is claimed and answered, the request is a
     * 503; on Meta's retry the first is a duplicate and the second is answered. One reply per message id.
     */
    @Test
    void whenOnlyTheSecondClaimOfACallbackFailsEachMessageIsStillAnsweredOnce() {
        runner().run(context -> {
            seedFamily();
            String text = WaFixtures.wamid(DIGITS, 10);
            String sticker = WaFixtures.wamid(DIGITS, 11);
            byte[] body = ("""
                    {"object":"whatsapp_business_account","entry":[{"changes":[{"value":{"messages":[
                      {"id":"%s","type":"text","from":"8801700000777","timestamp":"1792224000","text":{"body":"বিস্তারিত"}},
                      {"id":"%s","type":"sticker","from":"8801700000777","timestamp":"1792224001",
                       "sticker":{"id":"STK-1","mime_type":"image/webp"}}]}}]}]}""").formatted(text, sticker)
                    .getBytes(StandardCharsets.UTF_8);
            sql("create function baymax.test_refuse_sticker() returns trigger language plpgsql as $$ begin "
                    + "if new.type = 'sticker' then raise exception 'test outage'; end if; return new; end $$");
            sql("create trigger test_refuse_sticker before insert on baymax.inbound_message for each row "
                    + "execute function baymax.test_refuse_sticker()");
            try {
                deliver(webhook(context), body, 503);
                settle();
                assertThat(keys()).as("the first message, claimed before the second failed, is answered").containsExactly("<wa.no_document>");
            } finally {
                sql("drop trigger test_refuse_sticker on baymax.inbound_message");
                sql("drop function baymax.test_refuse_sticker()");
            }
            SENT.clear();
            deliver(webhook(context), body);
            settle();

            assertThat(keys()).as("on the retry: the first is a duplicate, the second answered once")
                    .hasSize(1).doesNotContain("<wa.no_document>");
            assertThat(scalar("select count(*) from baymax.inbound_message where provider_key in ('" + key(context, text)
                    + "','" + key(context, sticker) + "') and handled_at is not null")).isEqualTo("2");
        });
    }

    /**
     * Postgres text refuses U+0000 (22021), and it would refuse it on every retry for 7 days: a data error is not a 503.
     * The message is answered unkeyed, as one with no provider id is; no row, so the 200 is not a stored claim.
     */
    @Test
    void aTextThePostgresTextTypeRefusesIsAnsweredNotRetriedForAWeek() {
        runner().run(context -> {
            seedFamily();
            String wamid = WaFixtures.wamid(DIGITS, 12);
            byte[] body = callback(wamid).replace("বিস্তারিত", "বিস্তারিত\\u0000").getBytes(StandardCharsets.UTF_8);
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).findValue("body").asText())
                    .as("the fixture really carries a NUL").contains("\0");

            deliver(webhook(context), body, 200);
            settle();

            assertThat(SENT).as("answered once").hasSize(1);
            assertThat(scalar("select count(*) from baymax.inbound_message where provider_key = '" + key(context, wamid) + "'"))
                    .as("unrecordable, so processed without a claim").isEqualTo("0");
        });
    }

    /** The other direction: a database that cannot be reached is a 503, so Meta holds the message and retries. */
    @Test
    void aClosedConnectionPoolIsA503() {
        runner().run(context -> {
            seedFamily();
            io.r2dbc.spi.ConnectionFactory factory = context.getBean(io.r2dbc.spi.ConnectionFactory.class);
            assertThat(factory).isInstanceOf(io.r2dbc.pool.ConnectionPool.class);
            ((io.r2dbc.pool.ConnectionPool) factory).dispose();

            deliver(webhook(context), callback(WaFixtures.wamid(DIGITS, 13)).getBytes(StandardCharsets.UTF_8), 503);
            await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).until(() -> true);
            assertThat(SENT).isEmpty();
        });
    }

    /**
     * A timestamp beyond Instant's range made the walk throw outside every error handler: a 500, which Meta retries
     * forever. It is read as "no timestamp" and the message is answered.
     */
    @Test
    void aTimestampOutOfRangeIsAnsweredWithoutOne() {
        runner().run(context -> {
            seedFamily();
            String wamid = WaFixtures.wamid(DIGITS, 14);
            byte[] body = callback(wamid).replace("\"1792224000\"", "\"99999999999999999\"").getBytes(StandardCharsets.UTF_8);
            assertThat(new String(body, StandardCharsets.UTF_8)).contains("99999999999999999");

            deliver(webhook(context), body, 200);
            settle();

            assertThat(keys()).as("answered once").hasSize(1);
            assertThat(scalar("select (provider_timestamp is null)::text from baymax.inbound_message where provider_key = '"
                    + key(context, wamid) + "'")).isEqualTo("true");
        });
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static String scalar(String query) throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
