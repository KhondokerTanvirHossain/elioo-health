package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.family.application.service.FamilyAccountService;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.application.port.out.OtpDeliveryPort;
import com.elioo.baymax.web.application.port.out.OtpStorePort;
import com.elioo.baymax.web.application.port.out.SessionStorePort;
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.IssuedSession;
import com.elioo.baymax.web.domain.OtpCode;
import com.elioo.baymax.web.domain.WebSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * OTP login. The contract that shapes every branch here: a caller learns nothing from a request but the
 * hash it already could have computed, and nothing from a failed verify but "no". So an unknown number, a
 * malformed number, a rate-limited number and a valid one all complete {@link #requestCode} identically,
 * and a wrong code, a burned code, an expired code and a number with no account all fail
 * {@link #verify} with the same 401.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebAuthService implements WebAuthUseCase {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpStorePort otps;
    private final SessionStorePort sessions;
    private final OtpDeliveryPort delivery;
    private final AuditPort audit;
    private final HealthRecordPort records;
    private final PhoneHasher hasher;
    private final BaymaxProperties properties;
    private final Clock clock;

    @Override
    public String hash(String rawWhatsappNumber) {
        return hasher.phone(FamilyAccountService.normalizeNumber(rawWhatsappNumber));
    }

    @Override
    public Mono<String> requestCode(String rawWhatsappNumber) {
        final String number;
        try {
            number = FamilyAccountService.normalizeNumber(rawWhatsappNumber);
        } catch (RuntimeException notANumber) {
            // still a 200 upstream; nothing to hash, nothing to send, nothing to say
            return Mono.just(hasher.sha256("not-a-number:" + rawWhatsappNumber));
        }
        String phoneHash = hasher.phone(number);
        Instant now = clock.instant();
        BaymaxProperties.Auth cfg = properties.getAuth();

        return audit.record(AuditEvent.otp(AuditEvent.Kind.OTP_REQUEST, phoneHash, now))
                .then(otps.countLive(phoneHash, now.minus(Duration.ofHours(1)), now))
                .flatMap(live -> {
                    if (live >= cfg.getOtpMaxLivePerHour()) {
                        log.info("[baymax] otp request rate-limited phoneHash={} live={}", phoneHash, live);
                        return Mono.empty();          // silently: the response upstream is the same 200
                    }
                    String code = sixDigits();
                    // the family is resolved now, while the number is in hand, and remembered on the row;
                    // a number with no account gets a code all the same, so the caller sees no difference
                    return records.findFamilyByWhatsapp(number).map(f -> Optional.of(f.id()))
                            .defaultIfEmpty(Optional.empty())
                            .flatMap(family -> otps.create(new OtpCode(null, phoneHash, PhoneHasher.sha256(code),
                                    family.orElse(null), now.plus(cfg.getOtpTtl()), 0, null, null, now)))
                            .flatMap(saved -> delivery.deliver(number, phoneHash, code));
                })
                .thenReturn(phoneHash);
    }

    @Override
    public Mono<IssuedSession> verifyNumber(String rawWhatsappNumber, String code) {
        final String hash;
        try {
            hash = hash(rawWhatsappNumber);
        } catch (RuntimeException e) {
            if (e instanceof BaymaxException be && be.status().is5xxServerError()) {
                return Mono.error(e);           // not configured: say so, that is not an information leak
            }
            return Mono.error(invalid());
        }
        return verify(hash, code);
    }

    @Override
    public Mono<IssuedSession> verify(String phoneHash, String code) {
        Instant now = clock.instant();
        if (phoneHash == null || code == null || !code.matches("\\d{6}")) {
            return audit.record(AuditEvent.otp(AuditEvent.Kind.OTP_VERIFY_FAIL, phoneHash, now))
                    .then(Mono.error(invalid()));
        }
        return otps.latestLive(phoneHash, now)
                .switchIfEmpty(Mono.defer(() -> audit.record(AuditEvent.otp(AuditEvent.Kind.OTP_VERIFY_FAIL, phoneHash, now))
                        .then(Mono.error(invalid()))))
                .flatMap(otp -> {
                    boolean match = MessageDigest.isEqual(otp.codeHash().getBytes(), PhoneHasher.sha256(code).getBytes());
                    if (!match) {
                        return wrongGuess(otp, phoneHash, now);
                    }
                    if (otp.familyId() == null) {
                        // right code, no account: the code is spent and the answer is the same "no"
                        return otps.consume(otp.id(), now)
                                .then(audit.record(AuditEvent.otp(AuditEvent.Kind.OTP_VERIFY_FAIL, phoneHash, now)))
                                .then(Mono.error(invalid()));
                    }
                    return otps.consume(otp.id(), now).then(open(otp.familyId(), phoneHash, now));
                });
    }

    private Mono<IssuedSession> wrongGuess(OtpCode otp, String phoneHash, Instant now) {
        int attempts = otp.attempts() + 1;
        boolean burn = attempts >= properties.getAuth().getOtpMaxAttempts();
        return otps.recordAttempt(otp.id(), attempts, burn ? now : null)
                .then(audit.record(AuditEvent.otp(burn ? AuditEvent.Kind.OTP_BURNED : AuditEvent.Kind.OTP_VERIFY_FAIL, phoneHash, now)))
                .then(Mono.error(invalid()));
    }


    private Mono<IssuedSession> open(java.util.UUID familyId, String phoneHash, Instant now) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = now.plus(properties.getAuth().getSessionTtl());
        return sessions.create(PhoneHasher.sha256(token), familyId, now, expires)
                .flatMap(session -> audit.record(AuditEvent.otp(AuditEvent.Kind.OTP_VERIFY_OK, phoneHash, now))
                        .thenReturn(new IssuedSession(token, session)));
    }

    @Override
    public Mono<WebSession> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Mono.empty();
        }
        Instant now = clock.instant();
        return sessions.findLive(PhoneHasher.sha256(token), now)
                .flatMap(session -> {
                    Duration sinceSeen = Duration.between(session.lastSeenAt(), now);
                    if (sinceSeen.compareTo(properties.getAuth().getSessionTouchInterval()) < 0) {
                        return Mono.just(session);
                    }
                    Instant expires = now.plus(properties.getAuth().getSessionTtl());
                    return sessions.touch(session.id(), now, expires)
                            .thenReturn(new WebSession(session.id(), session.familyId(), session.createdAt(), now, expires));
                });
    }

    @Override
    public Mono<Void> logout(String token) {
        if (token == null || token.isBlank()) {
            return Mono.empty();
        }
        return sessions.revoke(PhoneHasher.sha256(token), clock.instant());
    }

    private static BaymaxException invalid() {
        return BaymaxException.unauthorized("invalid_code", "the code is wrong, expired or used");
    }

    private static String sixDigits() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
