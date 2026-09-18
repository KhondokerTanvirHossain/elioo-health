package com.elioo.baymax.web.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The stand-in for a phone number everywhere but family_account: HMAC-SHA256 under a server secret. A plain
 * SHA-256 would not do — Bangladeshi numbers are a space of about 10^9 and a leaked table would be walked
 * back in minutes. The same class hashes cookie tokens and OTP codes (plain SHA-256: those are random or
 * short-lived and keyed to a row, so a secret buys nothing there).
 */
@Component
@RequiredArgsConstructor
public class PhoneHasher {

    private final BaymaxProperties properties;

    public String phone(String normalisedNumber) {
        String secret = properties.getAuth().getHmacSecret();
        if (!StringUtils.hasText(secret)) {
            // fail closed, like the admin token: no secret, no login, and the reason is in the response
            throw new BaymaxException(HttpStatus.SERVICE_UNAVAILABLE, "auth_not_configured",
                    "baymax.auth.hmac-secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalisedNumber.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
