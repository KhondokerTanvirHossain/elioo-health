package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.testsupport.SchemaSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan W10-api (S12): {@code PUT /app/api/preferences} sets the non-secret {@code baymax_theme} cookie and nothing else,
 * through the real router, guard and session store on Postgres.
 *
 * <ul>
 *   <li>The cookie is {@code Path=/app}, never {@code /}: it is the theme of the app under {@code /app}, as the session
 *       cookie is, and MedScribe's pages at {@code /medscribeai} never see it. {@code HttpOnly}, {@code SameSite=Lax},
 *       a year, and {@code Secure} exactly when the session cookie is ({@code baymax.auth.cookie-secure}).</li>
 *   <li>Only {@code {"theme": "auto" | "light" | "dark"}} is accepted. Every other value or shape is 400 {@code INVALID}
 *       and sets no cookie, so a typo never leaves a cookie the layout cannot read.</li>
 *   <li>Session and CSRF are the guard's, as for every mutation, and a refusal sets no cookie.</li>
 *   <li>Nothing is stored server-side: asked of the whole {@code baymax} schema, never of a list.</li>
 * </ul>
 * Each answer's {@code Set-Cookie} lines are read from the response headers, every one of them, so a second cookie (a
 * widened copy at {@code Path=/}, say) cannot hide behind the parsed first one.
 */
@Testcontainers
class PreferencesApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String ROUTE = "/app/api/preferences";
    private static final AtomicInteger NUMBERS = new AtomicInteger(800);

    private static String nextNumber() {
        return "+8801700000" + NUMBERS.incrementAndGet();
    }

    @ParameterizedTest
    @ValueSource(strings = {"auto", "light", "dark"})
    void eachThemeSetsItsCookieUnderApp(String theme) {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, nextNumber(), "Tanvir");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            EntityExchangeResult<byte[]> r = put(client, token, "{\"theme\":\"" + theme + "\"}")
                    .expectStatus().isNoContent().expectBody().returnResult();

            List<String> lines = r.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
            assertThat(lines).as("Set-Cookie lines for " + theme).hasSize(1);
            assertThat(r.getResponseCookies().keySet()).as("the cookies set for " + theme).containsExactly("baymax_theme");
            ResponseCookie cookie = r.getResponseCookies().getFirst("baymax_theme");
            assertThat(cookie.getValue()).as("the cookie's value").isEqualTo(theme);
            assertThat(cookie.getPath()).as("the cookie's Path").isEqualTo("/app");
            assertThat(cookie.getMaxAge()).as("the cookie's Max-Age").isEqualTo(Duration.ofSeconds(31_536_000));
            assertThat(cookie.getSameSite()).as("the cookie's SameSite").isEqualTo("Lax");
            assertThat(cookie.isHttpOnly()).as("the cookie's HttpOnly").isTrue();
            assertThat(cookie.isSecure()).as("Secure, as baymax.auth.cookie-secure defaults to true").isTrue();
            assertThat(cookie.getDomain()).as("no Domain: the host alone").isNull();
        });
    }

    /** The other direction of {@code Secure}: off exactly when the session cookie's is off. */
    @Test
    void theCookieIsSecureOnlyWhenTheSessionCookieIs() {
        AppApiContext.runner(POSTGRES).withPropertyValues("baymax.auth.cookie-secure=false").run(context -> {
            FamilyAccount family = AppApiContext.family(context, nextNumber(), "Tanvir");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            EntityExchangeResult<byte[]> r = put(client, token, "{\"theme\":\"dark\"}")
                    .expectStatus().isNoContent().expectBody().returnResult();

            ResponseCookie cookie = r.getResponseCookies().getFirst("baymax_theme");
            assertThat(cookie).as("the theme cookie").isNotNull();
            assertThat(cookie.isSecure()).as("Secure with baymax.auth.cookie-secure=false").isFalse();
            assertThat(cookie.getPath()).as("the cookie's Path").isEqualTo("/app");
        });
    }

    @Test
    void anUnknownThemeIsInvalidAndSetsNoCookie() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, nextNumber(), "Tanvir");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            List<String> bodies = List.of(
                    "{\"theme\":\"blue\"}",
                    "{\"theme\":\"DARK\"}",
                    "{\"theme\":\" dark\"}",
                    "{\"theme\":\"\"}",
                    "{\"theme\":null}",
                    "{\"theme\":1}",
                    "{\"theme\":true}",
                    "{\"theme\":[\"dark\"]}",
                    "{\"theme\":{\"value\":\"dark\"}}",
                    "{}",
                    "{\"mode\":\"dark\"}",
                    "{\"theme\":\"dark\",\"lang\":\"en\"}",
                    "[\"dark\"]",
                    "\"dark\"",
                    "null",
                    "{\"theme\":",
                    "",
                    // a duplicate key: a lenient parser keeps the last value and the object still has one field
                    "{\"theme\":\"blue\",\"theme\":\"dark\"}",
                    // a second JSON value, or trailing text, after a valid one: a lenient decoder reads the first and stops
                    "{\"theme\":\"dark\"}{\"theme\":\"blue\"}",
                    "{\"theme\":\"dark\"} trailing");
            List<String> wrong = new ArrayList<>();
            for (String body : bodies) {
                EntityExchangeResult<byte[]> r = put(client, token, body).expectBody().returnResult();
                String answer = r.getResponseBody() == null ? "" : new String(r.getResponseBody());
                if (r.getStatus().value() != 400 || !answer.equals("{\"reason\":\"INVALID\"}")) {
                    wrong.add(body + " → " + r.getStatus().value() + " " + answer);
                }
                if (!r.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).isEmpty()) {
                    wrong.add(body + " set a cookie " + r.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));
                }
            }
            assertThat(wrong).as("bodies that were not a bare 400 INVALID with no cookie").isEmpty();
        });
    }

    @Test
    void withoutXMedioItIsRefused() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, nextNumber(), "Tanvir");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            EntityExchangeResult<byte[]> noHeader = client.put().uri(ROUTE).cookie("baymax_session", token)
                    .header("Origin", AppApiContext.ORIGIN).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"theme\":\"dark\"}")
                    .exchange().expectStatus().isForbidden().expectBody().json("{\"reason\":\"MISSING_HEADER\"}", true).returnResult();
            assertThat(noHeader.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)).as("cookies set without X-Medioo").isEmpty();

            EntityExchangeResult<byte[]> foreign = client.put().uri(ROUTE).cookie("baymax_session", token).header("X-Medioo", "1")
                    .header("Origin", "https://evil.example").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"theme\":\"dark\"}")
                    .exchange().expectStatus().isForbidden().expectBody().json("{\"reason\":\"FORBIDDEN_ORIGIN\"}", true).returnResult();
            assertThat(foreign.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)).as("cookies set for a foreign Origin").isEmpty();

            EntityExchangeResult<byte[]> plain = client.put().uri(ROUTE).cookie("baymax_session", token).header("X-Medioo", "1")
                    .header("Origin", AppApiContext.ORIGIN).contentType(MediaType.TEXT_PLAIN).bodyValue("{\"theme\":\"dark\"}")
                    .exchange().expectStatus().isEqualTo(415).expectBody().json("{\"reason\":\"UNSUPPORTED_MEDIA_TYPE\"}", true)
                    .returnResult();
            assertThat(plain.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)).as("cookies set for a text/plain body").isEmpty();
        });
    }

    @Test
    void withoutASessionItIs401() {
        AppApiContext.runner(POSTGRES).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            EntityExchangeResult<byte[]> none = client.put().uri(ROUTE).header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"theme\":\"dark\"}").exchange()
                    .expectStatus().isUnauthorized().expectBody().json("{\"reason\":\"UNAUTHENTICATED\"}", true).returnResult();
            assertThat(none.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)).as("cookies set with no session").isEmpty();

            EntityExchangeResult<byte[]> unknown = put(client, "tok-not-a-session", "{\"theme\":\"dark\"}")
                    .expectStatus().isUnauthorized().expectBody().json("{\"reason\":\"UNAUTHENTICATED\"}", true).returnResult();
            assertThat(unknown.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                    .filter(c -> c.startsWith("baymax_theme=")).toList()).as("a theme cookie set for an unknown session").isEmpty();
        });
    }

    /**
     * "Nothing is stored server-side", derived from the schema: every baymax table, by count and by the md5 of its rows,
     * before and after a theme PUT. A write to any table, present or added later, fails here.
     */
    @Test
    void aThemeIsStoredNowhereButTheCookie() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, nextNumber(), "Tanvir");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            SchemaSnapshot.Exclusions exclusions = SchemaSnapshot.Exclusions.none()
                    .column("web_session", "last_seen_at", "AppApiGuard's session resolve slides it on every request (V8)")
                    .column("web_session", "expires_at", "AppApiGuard's session resolve slides it on every request (V8)");
            Map<String, SchemaSnapshot.Table> before;
            Map<String, SchemaSnapshot.Table> after;
            List<String> all;
            try (Connection c = connect()) {
                before = SchemaSnapshot.of(c, "baymax", exclusions);
            }
            put(client, token, "{\"theme\":\"dark\"}").expectStatus().isNoContent();
            put(client, token, "{\"theme\":\"light\"}").expectStatus().isNoContent();
            try (Connection c = connect()) {
                after = SchemaSnapshot.of(c, "baymax", exclusions);
                all = SchemaSnapshot.tables(c, "baymax");
            }
            assertThat(before.keySet()).as("the snapshot read every baymax table").hasSizeGreaterThanOrEqualTo(15)
                    .containsExactlyInAnyOrderElementsOf(all);
            assertThat(before.get("family_account").rows()).as("the family the session belongs to is in the snapshot").isPositive();
            assertThat(before.get("web_session").rows()).as("the session is in the snapshot").isPositive();
            assertThat(after).as("every baymax table, before and after two theme PUTs").isEqualTo(before);
        });
    }

    private static WebTestClient.ResponseSpec put(WebTestClient client, String token, String rawJson) {
        return client.put().uri(ROUTE).cookie("baymax_session", token).header("X-Medioo", "1").header("Origin", AppApiContext.ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(rawJson).exchange();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
