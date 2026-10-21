package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.config.BaymaxProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * The confirm token {@code DELETE /app/api/documents/{id}} must carry (plan B4c): the second, deliberate step that a
 * stray request — a replayed fetch, a script, a cached page — cannot take for the family. It opens one paper, in one
 * session, for ten minutes.
 *
 * <p>The value is {@code <expiresEpochSeconds>.<base64url(HMAC-SHA256(secret, "paper-delete:v1|" + session + "|" +
 * document + "|" + expires))>}. The purpose prefix keeps it apart from every other MAC under
 * {@code baymax.auth.hmac-secret} ({@code PhoneHasher}, the nudge opt-out link's {@code "nudge-optout:"}), so no token
 * minted for another purpose ever opens a delete. The MAC is compared with {@link MessageDigest#isEqual}; anything
 * malformed is simply not valid, never an exception. A blank secret fails closed: nothing is minted, nothing validates.
 */
@Component
public class DeleteConfirmation {

    static final Duration TTL = Duration.ofMinutes(10);
    private static final String PURPOSE = "paper-delete:v1|";

    private final BaymaxProperties properties;
    private final Clock clock;

    public DeleteConfirmation(BaymaxProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** A token and when it stops opening anything. */
    public record Token(String value, Instant expiresAt) {
    }

    public Token mint(UUID sessionId, UUID documentId) {
        String secret = secret();
        if (secret == null) {
            throw new IllegalStateException("baymax.auth.hmac-secret is not set");
        }
        long expires = clock.instant().plus(TTL).getEpochSecond();
        return new Token(expires + "." + mac(secret, sessionId, documentId, expires), Instant.ofEpochSecond(expires));
    }

    public boolean valid(String token, UUID sessionId, UUID documentId) {
        String secret = secret();
        if (secret == null || token == null || sessionId == null || documentId == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1 || token.indexOf('.', dot + 1) >= 0) {
            return false;
        }
        long expires;
        try {
            expires = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        if (expires < 0 || clock.instant().getEpochSecond() > expires) {
            return false;
        }
        byte[] expected = mac(secret, sessionId, documentId, expires).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, token.substring(dot + 1).getBytes(StandardCharsets.UTF_8));
    }

    private String secret() {
        String s = properties.getAuth().getHmacSecret();
        return s == null || s.isBlank() ? null : s;
    }

    private static String mac(String secret, UUID sessionId, UUID documentId, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((PURPOSE + sessionId + "|" + documentId + "|" + expires).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is part of every JDK", e);
        }
    }
}
