package com.elioo.baymax.wa.config;

import com.elioo.baymax.config.BaymaxProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The value that cost an hour is the first test. Every other case is a paste accident that the app previously
 * accepted in silence, surfacing later as a 403 or a handshake rejection with nothing pointing at the cause.
 */
class WaConfigValidatorTest {

    private static final String GOOD_TOKEN = "EAA" + "x".repeat(200);
    private static final String GOOD_SECRET = "88e3540dd539aabbccddeeff00112233";
    private static final String GOOD_VERIFY = "78d41af3449a9bfbd560140166c6f2c2";

    private BaymaxProperties valid() {
        BaymaxProperties p = new BaymaxProperties();
        BaymaxProperties.Wa wa = p.getWa();
        wa.setEnabled(true);
        wa.setToken(GOOD_TOKEN);
        wa.setAppSecret(GOOD_SECRET);
        wa.setVerifyToken(GOOD_VERIFY);
        wa.setPhoneNumberId("1412074765313019");
        wa.setWabaId("4407553579467225");
        wa.setAllowlist(List.of("+8801793399171"));
        return p;
    }

    private static void validate(BaymaxProperties p) {
        new WaConfigValidator(p).validate();
    }

    @Test
    void awellFormedConfigurationStarts() {
        assertThatCode(() -> validate(valid())).doesNotThrowAnyException();
    }

    /**
     * The real failure, 2026-09-20: the env line carried the annotation from the handover as an inline comment,
     * docker --env-file kept it, and the verify token became 96 characters. Meta's handshake was rejected with
     * no hint that the stored value was malformed rather than merely different.
     */
    @Test
    void theInlineCommentThatCostAnHourIsRefusedAtStartupAndNamed() {
        BaymaxProperties p = valid();
        p.getWa().setVerifyToken(GOOD_VERIFY + "                  # a string you invent; must match Meta exactly");

        assertThatThrownBy(() -> validate(p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BAYMAX_WA_VERIFY_TOKEN")
                .hasMessageContaining("inline comment")
                .hasMessageContaining("own line");
    }

    @Test
    void anInlineCommentIsCaughtOnEverySecretNotJustTheVerifyToken() {
        BaymaxProperties tokenBad = valid();
        tokenBad.getWa().setToken(GOOD_TOKEN + "   # system-user token, expiry Never");
        assertThatThrownBy(() -> validate(tokenBad)).hasMessageContaining("BAYMAX_WA_TOKEN");

        BaymaxProperties secretBad = valid();
        secretBad.getWa().setAppSecret(GOOD_SECRET + "   # App Dashboard -> Settings -> Basic");
        assertThatThrownBy(() -> validate(secretBad)).hasMessageContaining("BAYMAX_WA_APP_SECRET");
    }

    /** An app secret that is not 32 hex characters rejects every inbound callback; say so at boot instead. */
    @Test
    void anAppSecretOfTheWrongShapeIsRefused() {
        BaymaxProperties p = valid();
        p.getWa().setAppSecret("tooshort");
        assertThatThrownBy(() -> validate(p))
                .hasMessageContaining("BAYMAX_WA_APP_SECRET")
                .hasMessageContaining("32 hex characters")
                .hasMessageContaining("every inbound callback would be rejected");
    }

    @Test
    void aTruncatedTokenIsRefused() {
        BaymaxProperties p = valid();
        p.getWa().setToken("EAAshort");
        assertThatThrownBy(() -> validate(p))
                .hasMessageContaining("BAYMAX_WA_TOKEN").hasMessageContaining("truncated");
    }

    @Test
    void blankValuesWithTheChannelEnabledAreRefused() {
        BaymaxProperties p = valid();
        p.getWa().setToken("");
        p.getWa().setVerifyToken("   ");
        assertThatThrownBy(() -> validate(p))
                .hasMessageContaining("BAYMAX_WA_TOKEN is blank")
                .hasMessageContaining("BAYMAX_WA_VERIFY_TOKEN is blank");
    }

    @Test
    void quotesAndWhitespaceAndPlaceholdersAreRefused() {
        BaymaxProperties quoted = valid();
        quoted.getWa().setVerifyToken("\"" + GOOD_VERIFY + "\"");
        assertThatThrownBy(() -> validate(quoted)).hasMessageContaining("wrapped in quotes");

        BaymaxProperties spaced = valid();
        spaced.getWa().setVerifyToken(" " + GOOD_VERIFY + " ");
        assertThatThrownBy(() -> validate(spaced)).hasMessageContaining("whitespace");

        BaymaxProperties placeholder = valid();
        placeholder.getWa().setPhoneNumberId("<your-phone-number-id>");
        assertThatThrownBy(() -> validate(placeholder)).hasMessageContaining("placeholder");
    }

    @Test
    void nonNumericIdsAndNonE164AllowlistEntriesAreRefused() {
        BaymaxProperties ids = valid();
        ids.getWa().setWabaId("not-a-number");
        assertThatThrownBy(() -> validate(ids)).hasMessageContaining("BAYMAX_WA_WABA_ID").hasMessageContaining("digits only");

        BaymaxProperties allow = valid();
        allow.getWa().setAllowlist(List.of("01793399171"));
        assertThatThrownBy(() -> validate(allow)).hasMessageContaining("E.164");
    }

    /** Secrets are never echoed: the message must be actionable without printing the value. */
    @Test
    void theMessageNamesTheProblemWithoutPrintingTheSecret() {
        BaymaxProperties p = valid();
        String secret = GOOD_VERIFY + " # comment";
        p.getWa().setVerifyToken(secret);
        assertThatThrownBy(() -> validate(p))
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(GOOD_VERIFY);
                    assertThat(e.getMessage()).contains("BAYMAX_WA_VERIFY_TOKEN");
                });
    }
}
