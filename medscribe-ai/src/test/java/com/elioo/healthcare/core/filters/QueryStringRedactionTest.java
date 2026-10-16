package com.elioo.healthcare.core.filters;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-23: a credential in a query string must never reach the log.
 *
 * <p>{@code IWebFilter} logged the full request URI at INFO on both receive and respond. Two live leaks:</p>
 *
 * <ul>
 *   <li><b>The WhatsApp verify token is already in the production log.</b> Meta re-verifies periodically, so
 *       it accumulates on every handshake.</li>
 *   <li><b>BMX-8's opt-out link is {@code /app/nudges/opt-out?p=<patientId>&t=<HMAC>}</b> — an
 *       unauthenticated access link with its token in the query string, live since #33. It has stayed out of
 *       the log only because the gate has never released a nudge, so no family has clicked one. The first
 *       click writes a working opt-out token for a real patient into the log.</li>
 * </ul>
 *
 * <p>Tanvir's amendment made the deferral a deadline: this lands before any nudge reaches a real family.</p>
 *
 * <p>Both directions in every test. Redacting everything would pass the safe half trivially and destroy the
 * log's usefulness — a filter that hides {@code page=2} makes debugging a real incident harder, and someone
 * will then turn it off. So the non-secret parameters must survive verbatim.</p>
 */
class QueryStringRedactionTest {

    private static String redacted(String uri) {
        return IWebFilter.redactSecrets(URI.create(uri).toString());
    }

    /** The opt-out link, the case DR-23's amendment is about. */
    @Test
    void anOptOutTokenIsRedacted() {
        String safe = redacted("https://medioo.eliooo.org/app/nudges/opt-out?p=9f1b2c3d&t=a1b2c3d4e5f6");

        assertThat(safe).as("the HMAC must not survive").doesNotContain("a1b2c3d4e5f6");
        assertThat(safe).as("the patient id is not a secret and identifies the row in a log").contains("9f1b2c3d");
        assertThat(safe).contains("t=REDACTED");
    }

    /** The WhatsApp handshake, which has been writing its token to the log on every verification. */
    @Test
    void theWhatsappVerifyTokenIsRedacted() {
        String safe = redacted("https://medioo.eliooo.org/api/v1/baymax/wa/webhook"
                + "?hub.mode=subscribe&hub.verify_token=78d41af3449a9bfbd560&hub.challenge=1158201444");

        assertThat(safe).doesNotContain("78d41af3449a9bfbd560");
        assertThat(safe).contains("hub.verify_token=REDACTED");
        assertThat(safe).as("the mode and challenge are not secrets and are what makes a handshake debuggable")
                .contains("hub.mode=subscribe").contains("hub.challenge=1158201444");
    }

    /** Every parameter name DR-23 lists, plus the shapes that mean the same thing. */
    @Test
    void everySecretShapedParameterIsRedacted() {
        for (String name : new String[]{"t", "token", "verify_token", "hub.verify_token", "key", "secret",
                "api_key", "apikey", "access_token", "auth", "password", "signature", "sig"}) {
            String safe = redacted("https://x/y?" + name + "=SUPERSECRETVALUE");
            assertThat(safe)
                    .as("parameter '%s' must be redacted", name)
                    .doesNotContain("SUPERSECRETVALUE");
        }
    }

    /** Case must not be a way around it: a lab or a partner may send TOKEN=. */
    @Test
    void redactionIsCaseInsensitive() {
        assertThat(redacted("https://x/y?TOKEN=abc123")).doesNotContain("abc123");
        assertThat(redacted("https://x/y?Api_Key=abc123")).doesNotContain("abc123");
    }

    /**
     * THE OTHER DIRECTION. A filter that redacts everything is safe and useless: a log that hides which page
     * was requested makes a real incident harder to diagnose, and the next person turns the filter off.
     */
    @Test
    void ordinaryParametersSurviveVerbatim() {
        String safe = redacted("https://x/documents?page=2&size=50&status=DONE&patient=9f1b2c3d");

        assertThat(safe).contains("page=2").contains("size=50").contains("status=DONE").contains("patient=9f1b2c3d");
        assertThat(safe).doesNotContain("REDACTED");
    }

    /** A parameter that merely CONTAINS a secret word is not itself a secret. */
    @Test
    void aParameterThatOnlyResemblesASecretIsNotRedacted() {
        String safe = redacted("https://x/y?tokenizer=bert&keyword=fever&sort=key");

        assertThat(safe)
                .as("tokenizer, keyword and a value of 'key' are not credentials")
                .contains("tokenizer=bert").contains("keyword=fever").contains("sort=key");
    }

    /** A URI with no query string is returned untouched. */
    @Test
    void aUriWithNoQueryIsUnchanged() {
        assertThat(redacted("https://medioo.eliooo.org/app/documents/abc"))
                .isEqualTo("https://medioo.eliooo.org/app/documents/abc");
    }

    /** Several secrets in one URI are all redacted, not just the first. */
    @Test
    void everySecretInOneUriIsRedacted() {
        String safe = redacted("https://x/y?t=AAA&page=1&token=BBB&key=CCC");

        assertThat(safe).doesNotContain("AAA").doesNotContain("BBB").doesNotContain("CCC");
        assertThat(safe).contains("page=1");
    }

    /** A valueless or empty parameter must not crash the filter — logging must never break a request. */
    @Test
    void oddShapesDoNotThrow() {
        assertThat(redacted("https://x/y?t")).doesNotContain("REDACTED=");
        assertThat(redacted("https://x/y?t=")).isNotNull();
        assertThat(redacted("https://x/y?&&")).isNotNull();
        assertThat(IWebFilter.redactSecrets(null)).isNull();
    }
}
