package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.family.application.service.FamilyAccountService;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.web.application.port.in.CodeRejectedException;
import com.elioo.baymax.web.application.port.in.WebAuthUseCase;
import com.elioo.baymax.web.application.port.out.AuditPort;
import com.elioo.baymax.web.application.port.out.InboundWindowPort;
import com.elioo.baymax.web.application.port.out.OtpDeliveryPort;
import com.elioo.baymax.web.application.port.out.OtpStorePort;
import com.elioo.baymax.web.application.port.out.SessionStorePort;
import com.elioo.baymax.web.domain.AuditEvent;
import com.elioo.baymax.web.domain.CodeIssue;
import com.elioo.baymax.web.domain.CodeRequest;
import com.elioo.baymax.web.domain.IssuedSession;
import com.elioo.baymax.web.domain.OtpCode;
import com.elioo.baymax.web.domain.VerifyFailure;
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
import java.util.UUID;

/**
 * OTP login. A code is minted in exactly two situations (DR-39, amended 2026-10-18: sign-in is free):
 * <ul>
 *   <li>the WhatsApp sender asked for one ({@link #issueCodeFor}); their message is the proof of the number and opens
 *       the window, so the reply is free;</li>
 *   <li>the web form named a number that has an account, is permitted by the allowlist, and wrote to Medioo inside
 *       {@code baymax.wa.service-window} ({@link #requestCode}); the code then goes to WhatsApp automatically.</li>
 * </ul>
 * Every other request mints nothing and sends nothing, so the open web form is never a sender to arbitrary numbers.
 * The 5-per-hour limit applies to both. A refused code is a {@link CodeRejectedException}: one 401 shape for the API,
 * a reason for the web page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebAuthService implements WebAuthUseCase {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** How far back a refusal looks for the newest code, to tell EXPIRED and BURNED from NO_CODE. */
    private static final Duration REFUSAL_LOOKBACK = Duration.ofHours(1);

    private final OtpStorePort otps;
    private final SessionStorePort sessions;
    private final OtpDeliveryPort delivery;
    private final AuditPort audit;
    private final HealthRecordPort records;
    private final PhoneHasher hasher;
    private final BaymaxProperties properties;
    private final Clock clock;
    private final InboundWindowPort window;

    @Override
    public String hash(String rawWhatsappNumber) {
        return hasher.phone(FamilyAccountService.normalizeNumber(rawWhatsappNumber));
    }

    @Override
    public Mono<CodeRequest> requestCode(String rawWhatsappNumber) {
        final String number;
        try {
            number = FamilyAccountService.normalizeNumber(rawWhatsappNumber);
        } catch (RuntimeException notANumber) {
            // still a 200 upstream; nothing to hash, nothing to send, nothing to say
            return Mono.just(new CodeRequest(hasher.sha256("not-a-number:" + rawWhatsappNumber), CodeIssue.NO_ACCOUNT));
        }
        String phoneHash = hasher.phone(number);
        Instant now = clock.instant();
        return audit.record(AuditEvent.otp(AuditEvent.Kind.OTP_REQUEST, phoneHash, now))
                .then(records.findFamilyByWhatsapp(number).map(f -> Optional.of(f.id())).defaultIfEmpty(Optional.empty()))
                .flatMap(family -> {
                    if (family.isEmpty()) {
                        return notSent(phoneHash, CodeIssue.NO_ACCOUNT);           // never an open sender (PO, d)
                    }
                    if (properties.getAuth().isWebMintsCode()) {
                        return mint(number, phoneHash, family.get(), now);        // a laptop with WhatsApp off
                    }
                    if (!properties.getWa().permits(number)) {
                        return notSent(phoneHash, CodeIssue.NOT_PERMITTED);
                    }
                    Instant opened = now.minus(properties.getWa().getServiceWindow());
                    return window.lastInbound(phoneHash)
                            .filter(last -> !last.isBefore(opened))
                            .flatMap(last -> mint(number, phoneHash, family.get(), now))
                            .switchIfEmpty(Mono.defer(() -> notSent(phoneHash, CodeIssue.OUTSIDE_WINDOW)));
                })
                .map(issue -> new CodeRequest(phoneHash, issue));
    }

    @Override
    public Mono<CodeIssue> issueCodeFor(String verifiedSenderNumber) {
        // defer: a missing HMAC secret becomes an error signal the caller can answer, not a throw at assembly
        return Mono.defer(() -> {
            String number = FamilyAccountService.normalizeNumber(verifiedSenderNumber);
            String phoneHash = hasher.phone(number);
            Instant now = clock.instant();
            return audit.record(AuditEvent.otp(AuditEvent.Kind.OTP_REQUEST, phoneHash, now))
                    .then(records.findFamilyByWhatsapp(number).map(f -> Optional.of(f.id())).defaultIfEmpty(Optional.empty()))
                    .flatMap(family -> family.isEmpty() ? notSent(phoneHash, CodeIssue.NO_ACCOUNT)
                            : !properties.getWa().permits(number) ? notSent(phoneHash, CodeIssue.NOT_PERMITTED)
                            : mint(number, phoneHash, family.get(), now));
        });
    }

    @Override
    public Mono<Void> noteInbound(String verifiedSenderNumber) {
        return Mono.defer(() -> {
            String number = FamilyAccountService.normalizeNumber(verifiedSenderNumber);
            String phoneHash = hasher.phone(number);
            Instant now = clock.instant();
            return records.findFamilyByWhatsapp(number)
                    .flatMap(family -> window.recordInbound(phoneHash, family.id(), now));
        });
    }

    /** The one place a code is made: within the hourly limit, stored hashed, handed to delivery, never logged. */
    private Mono<CodeIssue> mint(String number, String phoneHash, UUID familyId, Instant now) {
        BaymaxProperties.Auth cfg = properties.getAuth();
        return otps.countLive(phoneHash, now.minus(Duration.ofHours(1)), now)
                .flatMap(live -> {
                    if (live >= cfg.getOtpMaxLivePerHour()) {
                        log.info("[baymax] otp request rate-limited phoneHash={} live={}", phoneHash, live);
                        return Mono.just(CodeIssue.RATE_LIMITED);
                    }
                    String code = sixDigits();
                    return otps.create(new OtpCode(null, phoneHash, PhoneHasher.sha256(code), familyId,
                                    now.plus(cfg.getOtpTtl()), 0, null, null, now))
                            .flatMap(saved -> delivery.deliver(number, phoneHash, code))
                            .defaultIfEmpty(false)
                            .map(accepted -> accepted ? CodeIssue.SENT : CodeIssue.NOT_DELIVERED);
                });
    }

    private static Mono<CodeIssue> notSent(String phoneHash, CodeIssue why) {
        log.info("[baymax] otp request not sent phoneHash={} reason={}", phoneHash, why);
        return Mono.just(why);
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
            return Mono.error(new CodeRejectedException(VerifyFailure.FORMAT));
        }
        return verify(hash, code);
    }

    @Override
    public Mono<IssuedSession> verify(String phoneHash, String rawCode) {
        Instant now = clock.instant();
        String code = fold(rawCode);
        if (phoneHash == null || code == null || !code.matches("[0-9]{6}")) {
            return refuse(phoneHash, now, VerifyFailure.FORMAT);
        }
        return otps.latestLive(phoneHash, now)
                .switchIfEmpty(Mono.defer(() -> whyNoLiveCode(phoneHash, now)
                        .flatMap(failure -> refuse(phoneHash, now, failure))))
                .flatMap(otp -> {
                    boolean match = MessageDigest.isEqual(otp.codeHash().getBytes(), PhoneHasher.sha256(code).getBytes());
                    if (!match) {
                        return wrongGuess(otp, phoneHash, now);
                    }
                    if (otp.familyId() == null) {
                        // right code, no account (a code minted before PR 12 for a typed number): spent, and "no"
                        return otps.consume(otp.id(), now).then(refuse(phoneHash, now, VerifyFailure.WRONG));
                    }
                    return otps.consume(otp.id(), now).then(open(otp.familyId(), phoneHash, now));
                });
    }

    /** No live code: say BURNED or EXPIRED only when the newest recent code really was; anything else is NO_CODE. */
    private Mono<VerifyFailure> whyNoLiveCode(String phoneHash, Instant now) {
        return otps.latestSince(phoneHash, now.minus(REFUSAL_LOOKBACK))
                .map(newest -> newest.burnedAt() != null ? VerifyFailure.BURNED
                        : newest.consumedAt() != null ? VerifyFailure.NO_CODE
                        : VerifyFailure.EXPIRED)
                .defaultIfEmpty(VerifyFailure.NO_CODE);
    }

    private <T> Mono<T> refuse(String phoneHash, Instant now, VerifyFailure failure) {
        return audit.record(AuditEvent.otp(AuditEvent.Kind.OTP_VERIFY_FAIL, phoneHash, now))
                .then(Mono.error(new CodeRejectedException(failure)));
    }

    private Mono<IssuedSession> wrongGuess(OtpCode otp, String phoneHash, Instant now) {
        int attempts = otp.attempts() + 1;
        boolean burn = attempts >= properties.getAuth().getOtpMaxAttempts();
        return otps.recordAttempt(otp.id(), attempts, burn ? now : null)
                .then(audit.record(AuditEvent.otp(burn ? AuditEvent.Kind.OTP_BURNED : AuditEvent.Kind.OTP_VERIFY_FAIL, phoneHash, now)))
                .then(Mono.error(new CodeRejectedException(burn ? VerifyFailure.BURNED : VerifyFailure.WRONG)));
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

    /**
     * A code as the family typed it, as the six ASCII digits that were hashed. Java's {@code \d} is ASCII-only,
     * so a code typed on a Bangla keyboard (Ridmik, Gboard Bangla) was refused as wrong: every Unicode decimal
     * digit maps to its ASCII value, and spaces from a paste (including NBSP) are dropped. Nothing else is
     * touched, so a letter or a seventh digit still fails the shape check.
     */
    static String fold(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                continue;
            }
            sb.append(Character.isDigit(c) ? (char) ('0' + Character.digit(c, 10)) : c);
        }
        return sb.toString();
    }

    private static String sixDigits() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
