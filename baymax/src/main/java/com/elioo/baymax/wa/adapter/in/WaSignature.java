package com.elioo.baymax.wa.adapter.in;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Validates Meta's {@code X-Hub-Signature-256} over the raw request body (BMX-10).
 *
 * <p>The webhook is a public endpoint — it has no token and cannot have one, since Meta decides what to send.
 * The signature is the only thing separating a real callback from anyone who found the URL, so a body that
 * does not verify is never parsed, let alone acted on.
 *
 * <p>Two properties this class exists to hold:
 * <ul>
 *   <li>the HMAC is computed over the <em>raw bytes</em> as received — re-serialising parsed JSON would change
 *       the bytes and break verification for reasons that look like a signature mismatch;</li>
 *   <li>the comparison is constant-time ({@link MessageDigest#isEqual}), never {@code String.equals}, which
 *       returns early on the first differing character and leaks the prefix.</li>
 * </ul>
 */
@Slf4j
public final class WaSignature {

    private static final String PREFIX = "sha256=";
    private static final String ALGORITHM = "HmacSHA256";

    private WaSignature() {
    }

    /**
     * True when {@code header} is a valid signature of {@code body} under {@code appSecret}.
     *
     * <p>Fails closed on every other shape: a blank secret (misconfiguration must not open the door), a missing
     * or malformed header, or a bad hex encoding. No branch here returns true by default.
     */
    public static boolean valid(String header, byte[] body, String appSecret) {
        if (appSecret == null || appSecret.isBlank()) {
            log.error("[baymax] wa webhook: app secret is blank — every callback will be rejected (fail closed)");
            return false;
        }
        if (header == null || !header.startsWith(PREFIX) || body == null) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(header.substring(PREFIX.length()).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return MessageDigest.isEqual(mac.doFinal(body), provided);
        } catch (Exception e) {
            log.error("[baymax] wa webhook: signature check failed to run: {}", e.toString());
            return false;
        }
    }

    /** The signature Meta would send for this body — test helper and the shape the doc comment describes. */
    static String sign(byte[] body, String appSecret) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
