package com.elioo.baymax.web.adapter.in.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan W3-api: {@code POST /app/api/sign-out} through the real module on Postgres — the context's router, guard,
 * session store and auth service — so "the session is over" is read back from the store the next request resolves
 * against, not from a mock that agreed. Each refusal is asserted on the sign-out route's own access line (never the
 * catch-all's) and leaves the session alive.
 */
@Testcontainers
class SignOutApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final AtomicInteger NUMBERS = new AtomicInteger();
    private static final String ROUTE = "/app/api/sign-out";

    /** A fictional number in the fixture range, unique per call. */
    private static String number() {
        return "+88017000007" + String.format("%02d", NUMBERS.incrementAndGet());
    }

    private static WebTestClient.RequestBodySpec signOut(WebTestClient client, String token) {
        WebTestClient.RequestBodySpec spec = client.post().uri(ROUTE);
        if (token != null) {
            spec.cookie("baymax_session", token);
        }
        return spec;
    }

    private static String text(EntityExchangeResult<byte[]> r) {
        return r.getResponseBody() == null ? "" : new String(r.getResponseBody(), StandardCharsets.UTF_8);
    }

    private static int me(WebTestClient client, String token) {
        return client.get().uri("/app/api/me").cookie("baymax_session", token).exchange().returnResult(String.class).getStatus().value();
    }

    /** The request, and the guard's access lines it left: the route pattern there says which route answered. */
    private static Answer during(Supplier<EntityExchangeResult<byte[]>> request) {
        Logger logger = (Logger) LoggerFactory.getLogger(AppApiGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            EntityExchangeResult<byte[]> r = request.get();
            return new Answer(r, appender.list.stream().filter(e -> e.getLevel() == Level.INFO).map(ILoggingEvent::getFormattedMessage).toList());
        } finally {
            logger.detachAppender(appender);
        }
    }

    record Answer(EntityExchangeResult<byte[]> result, List<String> lines) {
        String status() {
            return result.getStatus().value() + " " + text(result);
        }

        void answeredBySignOut() {
            assertThat(lines).as("the access line names the sign-out route, not the catch-all")
                    .anyMatch(m -> m.startsWith("[baymax-api] POST " + ROUTE + " "));
        }
    }

    @Test
    void signOutEndsTheSessionAndClearsTheCookie() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, number(), "Tanvir");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            assertThat(me(client, token)).as("the session is live before").isEqualTo(200);

            Answer a = during(() -> signOut(client, token).header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).exchange().expectBody().returnResult());
            assertThat(a.status()).isEqualTo("204 ");
            a.answeredBySignOut();
            List<String> cleared = a.result().getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                    .filter(c -> c.startsWith("baymax_session=")).toList();
            assertThat(cleared).as("one Set-Cookie for the session").hasSize(1);
            HttpCookie c = HttpCookie.parse("set-cookie: " + cleared.get(0)).get(0);
            assertThat(c.getValue()).isEmpty();
            assertThat(c.getMaxAge()).as("Max-Age").isZero();
            assertThat(c.getPath()).isEqualTo("/app");
            assertThat(cleared.get(0)).contains("Max-Age=0").contains("Path=/app");

            assertThat(me(client, token)).as("the old token after sign-out").isEqualTo(401);
        });
    }

    @Test
    void signOutWithoutXMedioIsRefused() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, number(), "Tanvir");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Answer a = during(() -> signOut(client, token).header("Origin", AppApiContext.ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).exchange().expectBody().returnResult());
            assertThat(a.status()).isEqualTo("403 {\"reason\":\"MISSING_HEADER\"}");
            a.answeredBySignOut();
            assertThat(me(client, token)).as("a refused sign-out ends nothing").isEqualTo(200);
        });
    }

    /** {@code apiSend}'s rule for a body-less POST: it sends {@code Content-Type: application/json}, because the guard needs one. */
    @Test
    void signOutWithNoContentTypeIs415() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, number(), "Tanvir");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Answer a = during(() -> signOut(client, token).header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                    .exchange().expectBody().returnResult());
            assertThat(a.status()).isEqualTo("415 {\"reason\":\"UNSUPPORTED_MEDIA_TYPE\"}");
            a.answeredBySignOut();
            assertThat(me(client, token)).as("a refused sign-out ends nothing").isEqualTo(200);
        });
    }

    @Test
    void signOutWithoutASessionIs401() {
        AppApiContext.runner(POSTGRES).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Answer a = during(() -> signOut(client, null).header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).exchange().expectBody().returnResult());
            assertThat(a.status()).isEqualTo("401 {\"reason\":\"UNAUTHENTICATED\"}");
            a.answeredBySignOut();
            assertThat(a.result().getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)).as("nothing to clear, nothing set").isEmpty();
        });
    }
}
