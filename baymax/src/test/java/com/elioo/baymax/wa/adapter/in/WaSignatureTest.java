package com.elioo.baymax.wa.adapter.in;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The webhook is public and the signature is the only thing separating a real callback from anyone who found
 * the URL, so every rejection path is asserted, not just the happy one.
 */
class WaSignatureTest {

    private static final String SECRET = "app-secret-under-test";
    private static final byte[] BODY = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void aSignatureMetaWouldSendIsAccepted() throws Exception {
        assertThat(WaSignature.valid(WaSignature.sign(BODY, SECRET), BODY, SECRET)).isTrue();
    }

    @Test
    void aBodyAlteredAfterSigningIsRejected() throws Exception {
        String header = WaSignature.sign(BODY, SECRET);
        byte[] tampered = "{\"object\":\"whatsapp_business_account\",\"evil\":1}".getBytes(StandardCharsets.UTF_8);
        assertThat(WaSignature.valid(header, tampered, SECRET)).isFalse();
    }

    @Test
    void aSignatureFromADifferentSecretIsRejected() throws Exception {
        assertThat(WaSignature.valid(WaSignature.sign(BODY, "someone-elses-secret"), BODY, SECRET)).isFalse();
    }

    /** Misconfiguration must close the door, not open it: a blank secret rejects everything. */
    @Test
    void aBlankSecretRejectsEvenAnOtherwiseValidSignature() throws Exception {
        String header = WaSignature.sign(BODY, SECRET);
        assertThat(WaSignature.valid(header, BODY, "")).isFalse();
        assertThat(WaSignature.valid(header, BODY, null)).isFalse();
    }

    @Test
    void malformedHeadersAreRejectedRatherThanThrowing() {
        assertThat(WaSignature.valid(null, BODY, SECRET)).isFalse();
        assertThat(WaSignature.valid("", BODY, SECRET)).isFalse();
        assertThat(WaSignature.valid("sha1=abcdef", BODY, SECRET)).isFalse();
        assertThat(WaSignature.valid("sha256=nothexatall", BODY, SECRET)).isFalse();
        assertThat(WaSignature.valid("sha256=", BODY, SECRET)).isFalse();
        assertThat(WaSignature.valid("deadbeef", BODY, SECRET)).isFalse();
    }

    @Test
    void aNullBodyIsRejected() throws Exception {
        assertThat(WaSignature.valid(WaSignature.sign(BODY, SECRET), null, SECRET)).isFalse();
    }

    /**
     * The HMAC is over the raw bytes as received. Re-serialising parsed JSON — even to something semantically
     * identical — changes the bytes and must not verify, which is why the handler keeps the raw body.
     */
    @Test
    void reSerialisedJsonDoesNotVerifyAgainstTheOriginalSignature() throws Exception {
        String header = WaSignature.sign(BODY, SECRET);
        byte[] reSerialised = "{\"object\": \"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(WaSignature.valid(header, reSerialised, SECRET)).isFalse();
    }
}
