package com.elioo.healthcare;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.testsupport.LogCapture;
import com.elioo.baymax.web.application.port.out.SessionStorePort;
import com.elioo.baymax.web.application.service.PhoneHasher;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.util.UriUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code /app/api} guarantees that only the whole deployable can give, because they depend on beans the Baymax
 * module's own tests do not load: MedScribe's {@code IWebFilter}, which writes every request at INFO, and MedScribe's
 * routers. One real context (medscribe-ai plus {@code baymax.enabled=true}) over Testcontainers Postgres:
 * <ul>
 *   <li>no {@code /app/api} request puts the family's number or a patient's name into any log line at INFO or above,
 *       through the deployed filter chain — with the name and the number in the query string and in a path segment,
 *       raw and percent-encoded, each field (message, throwable chain, MDC value) on its own;</li>
 *   <li>no router of the deployable publishes a path under {@code /app} or {@code /baymax} that the committed route
 *       snapshot ({@code docs/infra/app-routes.txt}, generated from the Baymax module) does not list.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureWebTestClient
@Testcontainers
@TestPropertySource(locations = "classpath:application-test.properties", properties = {
        "baymax.enabled=true", "baymax.wa.enabled=false", "baymax.nudge.enabled=false", "baymax.recovery.enabled=false",
        "baymax.web.public-origin=https://medioo.eliooo.org"})
class AppApiDeployableTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String r2dbc = "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName();
        registry.add("spring.r2dbc.url", () -> r2dbc + "?schema=medscribe");
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", () -> POSTGRES.getJdbcUrl() + "&sslmode=disable");
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    private static final String ORIGIN = "https://medioo.eliooo.org";
    private static final String NUMBER = "+8801700000124";
    private static final String NAME = "রোকেয়া পরীক্ষা";
    private static final List<String> NEEDLES = List.of("8801700000124", "01700000124", "%2B8801700000124", NAME,
            URLEncoder.encode(NAME, StandardCharsets.UTF_8), UriUtils.encodePathSegment(NAME, StandardCharsets.UTF_8),
            UriUtils.encodeQueryParam(NAME, StandardCharsets.UTF_8));

    @Autowired
    WebTestClient client;

    @Autowired
    ApplicationContext context;

    @Test
    void noApiRequestPutsTheNumberOrTheNameIntoAnyLogLineThroughTheDeployedFilterChain() {
        Instant now = Instant.now();
        HealthRecordPort records = context.getBean(HealthRecordPort.class);
        FamilyAccount family = records.createFamily(new FamilyAccount(null, NUMBER, "Tanvir", FamilyAccount.Plan.FAMILY, now, now)).block();
        PatientProfile rokeya = records.createPatient(new PatientProfile(null, family.id(), NAME, 64, PatientProfile.Sex.FEMALE,
                List.of(), now, now)).block();
        String token = "tok-" + UUID.randomUUID();
        context.getBean(SessionStorePort.class).create(PhoneHasher.sha256(token), family.id(), now, now.plus(Duration.ofDays(1))).block();

        List<ILoggingEvent> events = capture(() -> {
            client.get().uri(b -> b.path("/app/api/me").queryParam("probe", NAME).queryParam("n", NUMBER).build())
                    .cookie("baymax_session", token).exchange().expectStatus().isOk()
                    .expectHeader().exists("Trace-Id");   // IWebFilter is in the chain this sweep reads
            client.get().uri(b -> b.path("/app/api/me").queryParam("probe", NAME).build()).exchange().expectStatus().isUnauthorized();
            client.put().uri(b -> b.path("/app/api/active-patient").queryParam("q", NAME).build()).cookie("baymax_session", token)
                    .header("X-Medioo", "1").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("patient_id", rokeya.id().toString())).exchange().expectStatus().isNoContent();
            client.put().uri("/app/api/active-patient").cookie("baymax_session", token).header("X-Medioo", "1")
                    .header("Origin", "https://evil.example").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("patient_id", NAME)).exchange().expectStatus().isForbidden();
            client.get().uri(b -> b.path("/app/api/x").queryParam("q", NAME).queryParam("n", NUMBER).build())
                    .cookie("baymax_session", token).exchange().expectStatus().isNotFound();
            client.get().uri(b -> b.path("/app/api/{name}").build(NAME)).cookie("baymax_session", token)
                    .exchange().expectStatus().isNotFound();
            client.get().uri(b -> b.path("/app/api/{n}/{name}").build(NUMBER, NAME)).exchange().expectStatus().isUnauthorized();
            // the dangerous direction for the filter: outside /app/api it still writes its request block
            client.get().uri("/app/").exchange().expectStatus().isOk();
        });

        List<ILoggingEvent> atInfo = events.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).toList();
        assertThat(atInfo.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("[baymax-api]")))
                .as("the API's own access lines were captured").hasSizeGreaterThanOrEqualTo(7);
        assertThat(atInfo.stream().filter(e -> e.getLoggerName().endsWith("IWebFilter"))
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains("Request Received")))
                .as("IWebFilter wrote the /app/ request, so it is live in this context").isNotEmpty();

        List<String> leaks = new ArrayList<>();
        for (ILoggingEvent e : atInfo) {
            check(leaks, e, "message", e.getFormattedMessage());
            check(leaks, e, "message+throwable", withThrowable(e));
            e.getMDCPropertyMap().forEach((k, v) -> check(leaks, e, "mdc." + k, v));
        }
        assertThat(leaks).as("log fields at INFO or above carrying the number or the name").isEmpty();
    }

    /**
     * The route snapshot is generated from the Baymax module's routers; this is the rest of the deployable. Every path
     * any router predicate names (or-branches and nested prefixes included) that lies under /app or /baymax must be
     * one the snapshot lists, so a MedScribe route added there cannot slip past the WhatsApp-URL contract.
     */
    @Test
    void noRouterOfTheDeployablePublishesAnAppPathTheSnapshotDoesNotList() throws Exception {
        Set<String> snapshot = Files.readAllLines(Path.of("../docs/infra/app-routes.txt"), StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.substring(l.indexOf(' ') + 1))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(snapshot).as("patterns in the committed snapshot").contains("/app/api/me", "/baymax/{*rest}");

        Map<String, RouterFunction> routers = context.getBeansOfType(RouterFunction.class);
        assertThat(routers.keySet()).as("MedScribe and Baymax routers in the deployable")
                .contains("mediooAppApiRoutes", "baymaxWebUiRoutes").hasSizeGreaterThan(5);
        Set<String> paths = new TreeSet<>();
        routers.values().forEach(r -> paths.addAll(paths(r)));
        assertThat(paths).as("paths read from every router").contains("/app/api/me").hasSizeGreaterThan(20);

        List<String> unlisted = paths.stream()
                .filter(p -> p.equals("/app") || p.startsWith("/app/") || p.equals("/baymax") || p.startsWith("/baymax/"))
                .filter(p -> !snapshot.contains(p)).toList();
        assertThat(unlisted).as("/app or /baymax paths served by the deployable but not in docs/infra/app-routes.txt").isEmpty();
    }

    /** Every path pattern a router's predicates name, joined onto its nested prefixes. */
    private static Set<String> paths(RouterFunction<?> router) {
        Set<String> out = new TreeSet<>();
        Deque<String> prefixes = new ArrayDeque<>();
        router.accept(new RouterFunctions.Visitor() {
            @Override public void startNested(RequestPredicate predicate) {
                List<String> p = pathsOf(predicate);
                prefixes.push(prefix() + (p.isEmpty() ? "" : p.get(0)));
            }
            @Override public void endNested(RequestPredicate predicate) {
                prefixes.pop();
            }
            @Override public void route(RequestPredicate predicate, HandlerFunction<?> handler) {
                pathsOf(predicate).forEach(p -> out.add(prefix() + p));
            }
            @Override public void resources(Function<ServerRequest, Mono<Resource>> lookup) {
                throw new IllegalStateException("a resource router this check cannot read: " + lookup);
            }
            @Override public void attributes(Map<String, Object> attributes) { }
            @Override public void unknown(RouterFunction<?> routerFunction) {
                throw new IllegalStateException("a router this check cannot read: " + routerFunction);
            }
            private String prefix() {
                return prefixes.isEmpty() ? "" : prefixes.peek();
            }
        });
        return out;
    }

    private static List<String> pathsOf(RequestPredicate predicate) {
        List<String> found = new ArrayList<>();
        predicate.accept(new RequestPredicates.Visitor() {
            @Override public void method(Set<org.springframework.http.HttpMethod> methods) { }
            @Override public void path(String pattern) { found.add(pattern); }
            @Override public void pathExtension(String extension) { }
            @Override public void header(String name, String value) { }
            @Override public void queryParam(String name, String value) { }
            @Override public void startAnd() { }
            @Override public void and() { }
            @Override public void endAnd() { }
            @Override public void startOr() { }
            @Override public void or() { }
            @Override public void endOr() { }
            @Override public void startNegate() { }
            @Override public void endNegate() { }
            @Override public void unknown(RequestPredicate p) {
                throw new IllegalStateException("a predicate this check cannot read: " + p);
            }
        });
        return found;
    }

    private static List<ILoggingEvent> capture(Runnable requests) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.INFO);
        try {
            requests.run();
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
        }
        return LogCapture.events(appender);
    }

    private static String withThrowable(ILoggingEvent e) {
        StringBuilder b = new StringBuilder(e.getFormattedMessage());
        IThrowableProxy t = e.getThrowableProxy();
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            b.append(" | ").append(t.getClassName()).append(": ").append(t.getMessage());
            for (IThrowableProxy s : t.getSuppressed()) {
                b.append(" | ").append(s.getClassName()).append(": ").append(s.getMessage());
            }
        }
        return b.toString();
    }

    private static void check(List<String> leaks, ILoggingEvent e, String field, String value) {
        if (value == null) {
            return;
        }
        long hits = NEEDLES.stream().filter(value::contains).count();
        if (hits > 0) {
            leaks.add(e.getLevel() + " " + e.getLoggerName() + " " + field + " carries " + hits + " needle(s)");
        }
    }
}
