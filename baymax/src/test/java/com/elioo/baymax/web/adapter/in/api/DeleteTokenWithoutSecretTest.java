package com.elioo.baymax.web.adapter.in.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.testsupport.DeletionSweep;
import com.elioo.baymax.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A blank {@code baymax.auth.hmac-secret} fails closed on the real routes (plan B4c), not only in
 * {@code DeleteConfirmation}'s unit test: the token route answers a bare 5xx with one {@code [baymax-api]} ERROR line and
 * no body, and a delete with any token deletes nothing.
 */
@Testcontainers
class DeleteTokenWithoutSecretTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void withoutASecretNoTokenIsMintedAndNothingIsDeleted() {
        AppApiContext.runner(POSTGRES).withPropertyValues("baymax.auth.hmac-secret=").run(context -> {
            assertThat(context).hasNotFailed();
            FamilyAccount family = AppApiContext.family(context, "+8801700000491", "Rahim");
            PatientProfile ma = AppApiContext.patient(context, family.id(), "Ma");
            UUID paper = AppApiContext.paper(context, POSTGRES, ma, "routine");
            String session = AppApiContext.session(context, family.id());
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
            Map<String, Long> before;
            try (Connection c = connect()) {
                before = DeletionSweep.rowsByTable(c, paper);
            }
            assertThat(before).as("the paper before the attempts").containsEntry("document", 1L);

            Logger guard = (Logger) LoggerFactory.getLogger(AppApiGuard.class);
            ListAppender<ILoggingEvent> events = new ListAppender<>();
            events.start();
            guard.addAppender(events);
            try {
                byte[] body = client.get().uri("/app/api/documents/" + paper + "/delete-token").cookie("baymax_session", session)
                        .exchange().expectStatus().is5xxServerError().expectBody().returnResult().getResponseBodyContent();
                assertThat(body).as("the 5xx's body").isNullOrEmpty();
            } finally {
                guard.detachAppender(events);
            }
            List<String> errors = LogCapture.events(events).stream().filter(e -> e.getLevel() == Level.ERROR).map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(errors).as("ERROR lines for the token route").hasSize(1);
            assertThat(errors.get(0)).as("the guard's line").startsWith("[baymax-api]");

            for (String token : List.of("", "4102444800.AAAA", "garbage")) {
                client.method(HttpMethod.DELETE).uri("/app/api/documents/" + paper).cookie("baymax_session", session)
                        .header(AppApiGuard.HEADER, "1").header(HttpHeaders.ORIGIN, AppApiContext.ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("confirm_token", token)).exchange()
                        .expectStatus().isBadRequest().expectBody().json("{\"reason\":\"CONFIRM_REQUIRED\"}", true);
            }
            try (Connection c = connect()) {
                assertThat(DeletionSweep.rowsByTable(c, paper)).as("the paper after the deletes without a secret").isEqualTo(before);
            }
        });
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&sslmode=disable", POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
