package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.application.port.out.OtpDeliveryPort;
import com.elioo.baymax.web.application.port.out.OtpStorePort;
import com.elioo.baymax.web.application.port.out.SessionStorePort;
import com.elioo.baymax.web.domain.OtpCode;
import com.elioo.baymax.web.domain.WebSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The OTP contract, with an in-memory stand-in for the code row so attempts and burning are observable. */
class WebAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    private static final String NUMBER = "+8801711111111";
    private static final UUID FAMILY = UUID.randomUUID();

    private final OtpStorePort otps = mock(OtpStorePort.class);
    private final SessionStorePort sessions = mock(SessionStorePort.class);
    private final OtpDeliveryPort delivery = mock(OtpDeliveryPort.class);
    private final AuditPort audit = mock(AuditPort.class);
    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final BaymaxProperties properties = new BaymaxProperties();
    private final AtomicReference<OtpCode> row = new AtomicReference<>();
    private final AtomicReference<String> deliveredCode = new AtomicReference<>();
    private WebAuthService service;

    @BeforeEach
    void wire() {
        properties.getAuth().setHmacSecret("test-secret");
        service = new WebAuthService(otps, sessions, delivery, audit, records, new PhoneHasher(properties), properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(audit.record(any())).thenReturn(Mono.empty());
        when(otps.countLive(anyString(), any(), any())).thenReturn(Mono.just(0L));
        when(otps.create(any())).thenAnswer(i -> {
            OtpCode c = i.getArgument(0);
            row.set(new OtpCode(UUID.randomUUID(), c.phoneHash(), c.codeHash(), c.familyId(), c.expiresAt(), 0, null, null, c.createdAt()));
            return Mono.just(row.get());
        });
        when(delivery.deliver(anyString(), anyString(), anyString())).thenAnswer(i -> {
            deliveredCode.set(i.getArgument(2));
            return Mono.empty();
        });
        when(otps.latestLive(anyString(), any())).thenAnswer(i -> Mono.justOrEmpty(row.get()).filter(c -> c.isLive(NOW)));
        when(otps.recordAttempt(any(), anyInt(), any())).thenAnswer(i -> {
            OtpCode c = row.get();
            row.set(new OtpCode(c.id(), c.phoneHash(), c.codeHash(), c.familyId(), c.expiresAt(), i.getArgument(1), null, i.getArgument(2), c.createdAt()));
            return Mono.empty();
        });
        when(otps.consume(any(), any())).thenAnswer(i -> {
            OtpCode c = row.get();
            row.set(new OtpCode(c.id(), c.phoneHash(), c.codeHash(), c.familyId(), c.expiresAt(), c.attempts(), NOW, null, c.createdAt()));
            return Mono.empty();
        });
        when(sessions.create(anyString(), any(), any(), any())).thenAnswer(i ->
                Mono.just(new WebSession(UUID.randomUUID(), i.getArgument(1), NOW, NOW, i.getArgument(3))));
        when(records.findFamilyByWhatsapp(NUMBER)).thenReturn(Mono.just(
                new FamilyAccount(FAMILY, NUMBER, "Owner", FamilyAccount.Plan.FREE, NOW, NOW)));
    }

    private String wrong() {
        return deliveredCode.get().equals("000000") ? "111111" : "000000";
    }

    /** Acceptance: given a wrong code five times, the code is dead and a new request is required. */
    @Test
    void fiveWrongGuessesBurnTheCodeAndTheRightOneNoLongerWorks() {
        String hash = service.requestCode(NUMBER).block();
        for (int i = 1; i <= 5; i++) {
            StepVerifier.create(service.verify(hash, wrong())).expectError(BaymaxException.class).verify();
        }
        assertThat(row.get().attempts()).isEqualTo(5);
        assertThat(row.get().burnedAt()).isNotNull();

        StepVerifier.create(service.verify(hash, deliveredCode.get()))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 401).verify();
        verify(sessions, never()).create(anyString(), any(), any(), any());

        // a fresh request issues a fresh code, and that one opens a session
        service.requestCode(NUMBER).block();
        StepVerifier.create(service.verify(hash, deliveredCode.get()))
                .assertNext(issued -> assertThat(issued.session().familyId()).isEqualTo(FAMILY))
                .verifyComplete();
    }

    @Test
    void fourWrongGuessesThenTheRightOneStillOpensASession() {
        String hash = service.requestCode(NUMBER).block();
        for (int i = 1; i <= 4; i++) {
            StepVerifier.create(service.verify(hash, wrong())).expectError().verify();
        }
        StepVerifier.create(service.verify(hash, deliveredCode.get())).expectNextCount(1).verifyComplete();
    }

    @Test
    void aCodeIsSingleUse() {
        String hash = service.requestCode(NUMBER).block();
        StepVerifier.create(service.verify(hash, deliveredCode.get())).expectNextCount(1).verifyComplete();
        StepVerifier.create(service.verify(hash, deliveredCode.get())).expectError(BaymaxException.class).verify();
    }

    /** A number without an account gets the same request behaviour and the same "no" on verify. */
    @Test
    void anUnknownNumberIsIndistinguishableOnRequestAndRefusedOnVerify() {
        when(records.findFamilyByWhatsapp("+8801722222222")).thenReturn(Mono.empty());
        String hash = service.requestCode("+8801722222222").block();
        assertThat(hash).matches("[0-9a-f]{64}");
        verify(delivery).deliver(eq("+8801722222222"), anyString(), anyString());   // a code still goes out
        StepVerifier.create(service.verify(hash, deliveredCode.get()))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 401).verify();
        verify(sessions, never()).create(anyString(), any(), any(), any());
    }

    @Test
    void aMalformedNumberStillCompletesTheRequestAndSendsNothing() {
        StepVerifier.create(service.requestCode("not a number")).expectNextCount(1).verifyComplete();
        verify(otps, never()).create(any());
        verify(delivery, never()).deliver(anyString(), anyString(), anyString());
    }

    @Test
    void theSixthLiveCodeInAnHourIsSilentlyNotIssued() {
        when(otps.countLive(anyString(), any(), any())).thenReturn(Mono.just(5L));
        StepVerifier.create(service.requestCode(NUMBER)).expectNextCount(1).verifyComplete();
        verify(otps, never()).create(any());
    }

    @Test
    void theNumberNeverReachesTheDeliveryLogLineOrTheStore() {
        service.requestCode(NUMBER).block();
        ArgumentCaptor<OtpCode> stored = ArgumentCaptor.forClass(OtpCode.class);
        verify(otps).create(stored.capture());
        assertThat(stored.getValue().phoneHash()).doesNotContain("8801").matches("[0-9a-f]{64}");
        assertThat(stored.getValue().codeHash()).isNotEqualTo(deliveredCode.get());
    }

    @Test
    void sessionsSlideOnlyAfterTheTouchInterval() {
        WebSession fresh = new WebSession(UUID.randomUUID(), FAMILY, NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(3600));
        when(sessions.findLive(anyString(), any())).thenReturn(Mono.just(fresh));
        StepVerifier.create(service.authenticate("tok")).assertNext(s -> assertThat(s.expiresAt()).isEqualTo(fresh.expiresAt())).verifyComplete();
        verify(sessions, never()).touch(any(), any(), any());

        WebSession stale = new WebSession(UUID.randomUUID(), FAMILY, NOW.minusSeconds(7200), NOW.minusSeconds(7200), NOW.plusSeconds(3600));
        when(sessions.findLive(anyString(), any())).thenReturn(Mono.just(stale));
        when(sessions.touch(any(), any(), any())).thenReturn(Mono.empty());
        StepVerifier.create(service.authenticate("tok"))
                .assertNext(s -> assertThat(s.expiresAt()).isEqualTo(NOW.plus(properties.getAuth().getSessionTtl()))).verifyComplete();
        verify(sessions).touch(eq(stale.id()), eq(NOW), any());
    }

    @Test
    void withoutTheHmacSecretLoginIsUnavailableNotOpen() {
        properties.getAuth().setHmacSecret("");
        StepVerifier.create(service.verifyNumber(NUMBER, "123456"))
                .expectErrorMatches(e -> ((BaymaxException) e).status().value() == 503).verify();
    }
}
