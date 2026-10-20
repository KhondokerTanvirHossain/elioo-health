package com.elioo.baymax.web.adapter.in.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.testsupport.LoggedText;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B1 step 9: no {@code /app/api} request puts the family's number or a patient's name into a log line at INFO or
 * above — not in the message, not in an attached exception's chain, not in an MDC value; each field checked on its own.
 * Every route is exercised, read from the context's routers: a route added later with no request below fails here
 * instead of going unchecked. The capture starts after the context is up, so the events are the requests' own, and at
 * least one {@code [baymax-api]} line must be among them — a sweep over nothing would pass for the wrong reason.
 */
@Testcontainers
class AppApiLogSafetyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String NUMBER = "+8801700000123";
    private static final String NAME = "রোকেয়া পরীক্ষা";
    private static final List<String> NEEDLES = List.of("8801700000123", "01700000123", NAME);

    @Test
    void noApiRouteLogsTheOwnersNumberOrThePatientsNameAtInfoOrAbove() {
        AppApiContext.runner(POSTGRES).run(context -> {
            FamilyAccount family = AppApiContext.family(context, NUMBER, "Tanvir");
            PatientProfile rokeya = AppApiContext.patient(context, family.id(), NAME);
            AppApiContext.patient(context, family.id(), "Karim");
            AppApiContext.paper(context, POSTGRES, rokeya, "now");
            String token = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            // each route's requests: the success path and its refusals, so every branch that could log is reached
            Map<String, BiConsumer<WebTestClient, String>> exercise = new LinkedHashMap<>();
            exercise.put("GET /app/api/me", (c, t) -> {
                c.get().uri("/app/api/me").cookie("baymax_session", t).exchange().expectStatus().isOk();
                c.get().uri("/app/api/me").cookie("baymax_session", t).cookie("baymax_patient", rokeya.id().toString())
                        .exchange().expectStatus().isOk();
                c.get().uri("/app/api/me").exchange().expectStatus().isUnauthorized();
            });
            exercise.put("PUT /app/api/active-patient", (c, t) -> {
                c.put().uri("/app/api/active-patient").cookie("baymax_session", t).header("X-Medioo", "1")
                        .header("Origin", AppApiContext.ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("patient_id", rokeya.id().toString())).exchange().expectStatus().isNoContent();
                c.put().uri("/app/api/active-patient").cookie("baymax_session", t).header("X-Medioo", "1")
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("patient_id", UUID.randomUUID().toString()))
                        .exchange().expectStatus().isNotFound();
                c.put().uri("/app/api/active-patient").cookie("baymax_session", t).header("X-Medioo", "1")
                        .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"patient_id\": " + NAME.length() + ", bad json")
                        .exchange().expectStatus().isBadRequest();
                c.put().uri("/app/api/active-patient").cookie("baymax_session", t).header("X-Medioo", "1")
                        .header("Origin", "https://evil.example").contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("patient_id", NAME)).exchange().expectStatus().isForbidden();
                c.put().uri("/app/api/active-patient").cookie("baymax_session", t).contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("patient_id", NAME)).exchange().expectStatus().isForbidden();
            });
            exercise.put("* /app/api/**", (c, t) -> {
                c.get().uri("/app/api/nothing-here").cookie("baymax_session", t).exchange().expectStatus().isNotFound();
                c.post().uri("/app/api/nothing-here").cookie("baymax_session", t).header("X-Medioo", "1")
                        .contentType(MediaType.TEXT_PLAIN).bodyValue(NAME).exchange().expectStatus().isEqualTo(415);
            });

            List<String> apiRoutes = AppApiRouteContractTest.routes(context).stream()
                    .filter(r -> r.pattern().startsWith(AppApiRouter.BASE + "/")).map(RouteWalk.Route::line).sorted().toList();
            assertThat(apiRoutes).as("routes read from the context").isNotEmpty();
            assertThat(exercise.keySet()).as("every /app/api route has requests in this test").containsExactlyInAnyOrderElementsOf(apiRoutes);

            Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            Level before = root.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            root.addAppender(appender);
            root.setLevel(Level.INFO);
            try {
                exercise.values().forEach(requests -> requests.accept(client, token));
            } finally {
                root.detachAppender(appender);
                root.setLevel(before);
            }

            List<ILoggingEvent> events = appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).toList();
            assertThat(events.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("[baymax-api]")))
                    .as("the requests logged their access lines, so the sweep below reads something").isNotEmpty();
            List<String> leaks = new ArrayList<>();
            for (ILoggingEvent e : events) {
                check(leaks, e, "message", e.getFormattedMessage());
                check(leaks, e, "message+throwable", LoggedText.of(e));
                e.getMDCPropertyMap().forEach((k, v) -> check(leaks, e, "mdc." + k, v));
            }
            assertThat(leaks).as("log fields at INFO or above carrying the number or the name").isEmpty();
        });
    }

    private static void check(List<String> leaks, ILoggingEvent e, String field, String value) {
        if (value == null) {
            return;
        }
        List<String> hits = NEEDLES.stream().filter(value::contains).collect(Collectors.toList());
        if (!hits.isEmpty()) {
            leaks.add(e.getLevel() + " " + e.getLoggerName() + " " + field + " carries " + hits.size() + " needle(s)");
        }
    }
}
