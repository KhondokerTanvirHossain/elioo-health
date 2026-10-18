package com.elioo.baymax.web.application.service;

import com.elioo.baymax.config.BaymaxAutoConfiguration;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.wa.application.port.out.WaMessagingPort;
import com.elioo.baymax.web.application.port.in.CodeRejectedException;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.domain.CodeIssue;
import com.elioo.baymax.web.domain.VerifyFailure;
import com.elioo.healthcare.llm.config.LlmAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hourly cap bounds what was actually done for a number — codes made and messages sent — not how many codes
 * happen to be live at this moment. Against a real Postgres, because the defect lived in the SQL: the cap counted only
 * live codes ({@code consumed_at IS NULL AND burned_at IS NULL AND expires_at > now}), so five wrong guesses burned a
 * code, it dropped out of the count, and the next request minted and sent another. With a stranger's account number
 * inside its window that was an unbounded WhatsApp flood to that family, each message bringing five more guesses.
 * Every rate-limit unit test stubbed the count with a constant, which cannot fail the way this query did.
 */
@Testcontainers
class OtpHourlyCapAgainstPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String NUMBER = "+8801700000561";
    private static final Instant T0 = Instant.parse("2026-10-18T10:00:00Z");
    private static final Pattern SIX = Pattern.compile("(?<![0-9])([0-9]{6})(?![0-9])");

    /** A clock the test moves forward, so a code can expire and the hour can roll over. */
    static final class MovableClock extends Clock {
        private volatile Instant now = T0;

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final WaMessagingPort wa = mock(WaMessagingPort.class);

    private ReactiveWebApplicationContextRunner runner() {
        return new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(R2dbcAutoConfiguration.class, R2dbcDataAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                        LlmAutoConfiguration.class, BaymaxAutoConfiguration.class))
                .withBean(Clock.class, () -> clock)
                .withBean(WaMessagingPort.class, () -> wa)
                .withPropertyValues("baymax.enabled=true", "llm.provider=groq", "llm.groq.api-key=test-key",
                        "baymax.extract.vision-model=", "baymax.auth.hmac-secret=test",
                        "baymax.wa.allowlist=" + NUMBER,
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName(),
                        "spring.r2dbc.username=" + POSTGRES.getUsername(), "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "baymax.flyway.url=" + POSTGRES.getJdbcUrl() + "&sslmode=disable",
                        "baymax.flyway.user=" + POSTGRES.getUsername(), "baymax.flyway.password=" + POSTGRES.getPassword());
    }

    /** The code in the last WhatsApp message Medioo sent; the mock is cleared after each read. */
    private String codeJustSent() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(wa, atLeastOnce()).sendText(eq(NUMBER), body.capture());
        Matcher m = SIX.matcher(body.getValue());
        assertThat(m.find()).as("a six-digit code in the message").isTrue();
        clearInvocations(wa);
        return m.group(1);
    }

    private static String wrong(String code) {
        return String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);
    }

    /** The attack's own step: five wrong guesses at the code just sent, which burns it. */
    private VerifyFailure burn(WebAuthUseCase auth, String hash, String code) {
        VerifyFailure last = null;
        for (int i = 0; i < 5; i++) {
            last = auth.verify(hash, wrong(code)).map(s -> VerifyFailure.WRONG)
                    .onErrorResume(CodeRejectedException.class, e -> Mono.just(e.failure())).block();
        }
        return last;
    }

    @Test
    void theSixthCodeInAnHourIsRefusedWhateverHappenedToTheFirstFiveAndTheHourRollsOver() {
        when(wa.sendText(anyString(), anyString())).thenReturn(Mono.just("wamid.cap"));
        runner().run(context -> {
            WebAuthUseCase auth = context.getBean(WebAuthUseCase.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            DatabaseClient db = context.getBean(DatabaseClient.class);
            records.createFamily(new FamilyAccount(null, NUMBER, "Cap", FamilyAccount.Plan.FREE, T0, T0)).block();
            auth.noteInbound(NUMBER).block();                 // the family wrote: the web may now send a code
            String hash = auth.hash(NUMBER);

            // 1, 2: web requests, each code burned by five wrong guesses (the flood's cycle)
            for (int i = 0; i < 2; i++) {
                assertThat(auth.requestCode(NUMBER).block().issue()).isEqualTo(CodeIssue.SENT);
                assertThat(burn(auth, hash, codeJustSent())).isEqualTo(VerifyFailure.BURNED);
            }
            // 3: the WhatsApp কোড path shares the cap; this one is used, and opens a session
            assertThat(auth.issueCodeFor(NUMBER).block()).isEqualTo(CodeIssue.SENT);
            assertThat(auth.verify(hash, codeJustSent()).block()).isNotNull();
            // 4: left to expire
            assertThat(auth.requestCode(NUMBER).block().issue()).isEqualTo(CodeIssue.SENT);
            codeJustSent();
            clock.advance(Duration.ofMinutes(11));
            // 5: burned again
            assertThat(auth.requestCode(NUMBER).block().issue()).isEqualTo(CodeIssue.SENT);
            assertThat(burn(auth, hash, codeJustSent())).isEqualTo(VerifyFailure.BURNED);

            // none of the five is live now; the sixth is still refused, on both paths, with nothing made or sent
            assertThat(auth.requestCode(NUMBER).block().issue()).isEqualTo(CodeIssue.RATE_LIMITED);
            assertThat(auth.issueCodeFor(NUMBER).block()).isEqualTo(CodeIssue.RATE_LIMITED);
            verify(wa, never()).sendText(anyString(), anyString());
            assertThat(db.sql("SELECT count(*) FROM baymax.otp_code WHERE phone_hash = :p").bind("p", hash)
                    .map((row, meta) -> row.get(0, Long.class)).one().block())
                    .as("five codes in the hour, not six").isEqualTo(5L);

            // the other direction: the cap is a rolling hour, not a lock-out — once the first four are older than an
            // hour (only the fifth, made at +11 min, still counts), the family can sign in again
            clock.advance(Duration.ofMinutes(50));
            assertThat(auth.requestCode(NUMBER).block().issue()).isEqualTo(CodeIssue.SENT);
            verify(wa, times(1)).sendText(eq(NUMBER), anyString());
        });
    }

    /** The cap is per number: one family's five do not refuse another's first. Own numbers: the container is shared. */
    @Test
    void theCapIsPerNumber() {
        String a = "+8801700000563";
        String b = "+8801700000564";
        when(wa.sendText(anyString(), anyString())).thenReturn(Mono.just("wamid.cap"));
        runner().withPropertyValues("baymax.wa.allowlist=" + a + "," + b).run(context -> {
            WebAuthUseCase auth = context.getBean(WebAuthUseCase.class);
            HealthRecordPort records = context.getBean(HealthRecordPort.class);
            records.createFamily(new FamilyAccount(null, a, "A", FamilyAccount.Plan.FREE, T0, T0)).block();
            records.createFamily(new FamilyAccount(null, b, "B", FamilyAccount.Plan.FREE, T0, T0)).block();
            for (int i = 0; i < 5; i++) {
                assertThat(auth.issueCodeFor(a).block()).isEqualTo(CodeIssue.SENT);
            }
            assertThat(auth.issueCodeFor(a).block()).isEqualTo(CodeIssue.RATE_LIMITED);
            assertThat(auth.issueCodeFor(b).block()).isEqualTo(CodeIssue.SENT);
        });
    }
}
