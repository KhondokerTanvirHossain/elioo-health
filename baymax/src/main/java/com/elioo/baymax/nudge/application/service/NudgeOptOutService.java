package com.elioo.baymax.nudge.application.service;

import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.nudge.application.port.out.NudgeOptOutPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Opt-out (DR-18), effective immediately. A link in a nudge carries an HMAC of the patient id under the auth
 * secret, so a family can stop nudges from the message itself without a session; the same call from the app
 * needs none. Family-level opt-out covers every patient of the family, including the silence rule — if a
 * patient has died, Medioo asking after them is the worst failure this product has.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NudgeOptOutService implements NudgeOptOutUseCase {

    private final NudgeOptOutPort optOut;
    private final NudgeDataPort data;
    private final BaymaxProperties properties;
    private final Clock clock;

    @Override
    public String token(UUID patientId) {
        String secret = properties.getAuth().getHmacSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("baymax.auth.hmac-secret is not set");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("nudge-optout:" + patientId).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean tokenValid(UUID patientId, String token) {
        if (token == null) {
            return false;
        }
        return MessageDigest.isEqual(token(patientId).getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> optOutPatient(UUID patientId) {
        return optOut.optOutPatient(patientId, clock.instant())
                .doOnSuccess(v -> log.info("[baymax] nudges opted out patientId={}", patientId));
    }

    @Override
    public Mono<Void> optOutFamily(UUID patientId) {
        return data.patient(patientId).flatMap(p -> optOut.optOutFamily(p.familyId(), clock.instant())
                .doOnSuccess(v -> log.info("[baymax] nudges opted out familyId={}", p.familyId())));
    }
}
