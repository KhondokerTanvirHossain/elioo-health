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
        webhook.post().uri(WaWebhookRouter.WEBHOOK_PATH)
                .header("X-Hub-Signature-256", WaSignature.sign(body, APP_SECRET))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
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
