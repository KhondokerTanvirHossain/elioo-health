package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.config.BaymaxProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The confirm token a paper delete must carry (plan B4c): bound to one session and one paper, ten minutes long, signed
 * under its own purpose so no other user of {@code baymax.auth.hmac-secret} can mint one, and refused — never thrown
 * over — when it is anything else.
 */
class DeleteConfirmationTest {

    private static final Instant NOW = Instant.parse("2026-10-21T09:00:00Z");
    private static final String SECRET = "delete-test-secret";
    private static final UUID S1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID S2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID D1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID D2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    @Test
    void aTokenOpensOnlyItsOwnPaperInItsOwnSession() {
        DeleteConfirmation confirm = at(NOW, SECRET);
        DeleteConfirmation.Token token = confirm.mint(S1, D1);

        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(confirm.valid(token.value(), S1, D1)).as("its own session and paper").isTrue();
        assertThat(confirm.valid(token.value(), S1, D2)).as("another paper, same session").isFalse();
        assertThat(confirm.valid(token.value(), S2, D1)).as("the same paper, another session").isFalse();
    }

    @Test
    void aTokenLastsTenMinutesAndNotASecondMore() {
        String token = at(NOW, SECRET).mint(S1, D1).value();

        assertThat(at(NOW.plus(Duration.ofMinutes(9).plusSeconds(59)), SECRET).valid(token, S1, D1)).as("at +9:59").isTrue();
        assertThat(at(NOW.plus(Duration.ofMinutes(10).plusSeconds(1)), SECRET).valid(token, S1, D1)).as("at +10:01").isFalse();
    }

    @Test
    void aTamperedTokenIsRefused() {
        DeleteConfirmation confirm = at(NOW, SECRET);
        String token = confirm.mint(S1, D1).value();
        int dot = token.indexOf('.');
        String expires = token.substring(0, dot);
        String mac = token.substring(dot + 1);

        char last = mac.charAt(mac.length() - 1);
        String flipped = expires + "." + mac.substring(0, mac.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThat(confirm.valid(flipped, S1, D1)).as("one character of the MAC flipped").isFalse();

        String later = (Long.parseLong(expires) + Duration.ofDays(1).toSeconds()) + "." + mac;
        assertThat(confirm.valid(later, S1, D1)).as("the expiry moved a day on, the MAC kept").isFalse();
    }

    @Test
    void aMalformedTokenIsRefusedNotAnError() {
        DeleteConfirmation confirm = at(NOW, SECRET);
        for (String bad : new String[]{"", "abc", "1.2.3", "99999999999999999999.x", null, ".", "x.y"}) {
            assertThat(confirm.valid(bad, S1, D1)).as("malformed %s", bad).isFalse();
        }
    }

    @Test
    void aTokenFromAnotherPurposeIsRefused() throws Exception {
        DeleteConfirmation confirm = at(NOW, SECRET);
        long expires = confirm.mint(S1, D1).expiresAt().getEpochSecond();
        String ids = S1 + "|" + D1 + "|" + expires;

        assertThat(confirm.valid(expires + "." + mac("nudge-optout:" + ids), S1, D1)).as("the opt-out link's purpose").isFalse();
        assertThat(confirm.valid(expires + "." + mac(ids), S1, D1)).as("no purpose at all").isFalse();
        // the control: the same recipe under this purpose is the token itself, so the two refusals are about the prefix
        assertThat(confirm.valid(expires + "." + mac("paper-delete:v1|" + ids), S1, D1)).as("this purpose").isTrue();
    }

    @Test
    void aBlankSecretMintsNothingAndValidatesNothing() {
        String real = at(NOW, SECRET).mint(S1, D1).value();
        for (String blank : new String[]{"", "  ", null}) {
            DeleteConfirmation confirm = at(NOW, blank);
            assertThatThrownBy(() -> confirm.mint(S1, D1)).as("mint under %s", blank)
                    .isInstanceOf(IllegalStateException.class).hasMessage("baymax.auth.hmac-secret is not set");
            assertThat(confirm.valid(real, S1, D1)).as("validate under %s", blank).isFalse();
        }
    }

    private static DeleteConfirmation at(Instant now, String secret) {
        BaymaxProperties properties = new BaymaxProperties();
        properties.getAuth().setHmacSecret(secret);
        return new DeleteConfirmation(properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String mac(String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
}
