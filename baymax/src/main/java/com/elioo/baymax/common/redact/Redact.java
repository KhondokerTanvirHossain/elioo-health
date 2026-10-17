package com.elioo.baymax.common.redact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Log-safe references. A WhatsApp message id (wamid) is base64 of the phone number, so it never goes in a log; its
 * SHA-256 prefix correlates log lines without revealing it (the opaque tail makes the digest unguessable from a number).
 */
public final class Redact {

    private Redact() {
    }

    public static String id(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return "-";
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(providerId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
