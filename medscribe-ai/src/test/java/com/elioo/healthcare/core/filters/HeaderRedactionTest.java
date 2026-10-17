package com.elioo.healthcare.core.filters;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * DR-23 covered query strings; headers were still logged raw at INFO on receive and respond. A session cookie,
 * the Set-Cookie that opens a session and the admin token are credentials: any one of them in the log is a
 * working login for whoever can read it. Both directions: a filter that hides every header is useless.
 */
class HeaderRedactionTest {

    private ListAppender<ILoggingEvent> logged;
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(IWebFilter.class);
        logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    private String run(MockServerHttpRequest request, String setCookie) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        new IWebFilter(mock(Tracer.class)).filter(exchange, ex -> {
            ex.getResponse().getHeaders().add(HttpHeaders.SET_COOKIE, setCookie);
            return ex.getResponse().setComplete();
        }).block();
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    @Test
    void sessionCookiesAdminTokenAndSetCookieNeverReachTheLog() {
        String log = run(MockServerHttpRequest.get("/app/home")
                        .header(HttpHeaders.COOKIE, "baymax_session=SESSIONTOKEN7f3a; baymax_lang=bn")
                        .header("X-Baymax-Admin-Token", "ADMINTOKEN9c1d")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer BEARER5e2b")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 test-agent")
                        .build(),
                "baymax_session=NEWSESSION4b8e; Path=/app; HttpOnly");

        assertThat(log).doesNotContain("SESSIONTOKEN7f3a").doesNotContain("ADMINTOKEN9c1d")
                .doesNotContain("BEARER5e2b").doesNotContain("NEWSESSION4b8e");
        // guards against a vacuous pass: the filter did log both lines, with the redaction marker in them
        assertThat(log).contains("Request Received From").contains("Response Sending To").contains("REDACTED");
        // THE OTHER DIRECTION: the headers that make an incident debuggable survive verbatim
        assertThat(log).contains("Mozilla/5.0 test-agent").contains("/app/home");
    }

    /**
     * The RUNBOOK leak counter greps the log for a credential header that is not followed by REDACTED. It can only
     * do that if the redacted line keeps the format the unredacted one had ({@code Name:"value"}, HttpHeaders'
     * own); a Map's {@code Name=[value]} silently made the counter read 0 whatever the log held.
     */
    @Test
    void theRedactedHeaderKeepsTheFormatTheLeakCounterGrepsFor() {
        String log = run(MockServerHttpRequest.get("/app/home")
                        .header("X-Baymax-Admin-Token", "ADMINTOKEN9c1d")
                        .header(HttpHeaders.COOKIE, "baymax_session=SESSIONTOKEN7f3a")
                        .build(),
                "baymax_session=NEWSESSION4b8e; Path=/app; HttpOnly");

        assertThat(log).contains("X-Baymax-Admin-Token:\"REDACTED\"").contains("Cookie:\"REDACTED\"")
                .contains("Set-Cookie:\"REDACTED\"");
    }

    /** The response half on its own: a Set-Cookie that opens a session is a login, even with no cookie sent. */
    @Test
    void aSetCookieAloneNeverReachesTheLog() {
        String log = run(MockServerHttpRequest.post("/app/login/verify").build(),
                "baymax_session=NEWSESSION4b8e; Path=/app; HttpOnly");

        assertThat(log).contains("Response Sending To").doesNotContain("NEWSESSION4b8e").doesNotContain("baymax_session=");
    }
}
