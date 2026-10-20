package com.elioo.baymax.wa.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elioo.baymax.config.BaymaxProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

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
        wa.setAllowlist(List.of("+8801700000000"));
        p.getAuth().setHmacSecret("s".repeat(32));   // F9a: every inbound message is keyed by it
        return p;
    }

    private static void validate(BaymaxProperties p) {
        new WaConfigValidator(p).validate();
    }

    private ListAppender<ILoggingEvent> logged;
    private Logger validatorLogger;

    @BeforeEach
    void captureLogs() {
        validatorLogger = (Logger) LoggerFactory.getLogger(WaConfigValidator.class);
        logged = new ListAppender<>();
        logged.start();
        validatorLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLogs() {
        validatorLogger.detachAppender(logged);
    }

    /** The WARN lines startup actually emitted, i.e. what the post-deploy grep for "graph version" reads. */
    private List<String> graphVersionWarnsLogged() {
        return logged.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("graph version"))
                .toList();
    }

    @Test
    void awellFormedConfigurationStarts() {
        assertThatCode(() -> validate(valid())).doesNotThrowAnyException();
    }

    /**
     * F9a: the webhook claims every message under HMAC(wamid) before it answers. Without the secret every claim fails
     * and every callback is a 503 — Meta retrying for a week while no family hears anything. Refused at startup instead.
     */
    @Test
    void withoutTheHmacSecretWhatsappRefusesToStartAndSaysWhy() {
        for (String blank : new String[]{null, "", "   "}) {
            BaymaxProperties p = valid();
            p.getAuth().setHmacSecret(blank);
            assertThatThrownBy(() -> validate(p)).as("hmac secret <%s>", blank)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BAYMAX_AUTH_HMAC_SECRET")
                    .hasMessageContaining("inbound messages are keyed by it")
                    .hasMessageContaining("every webhook answers 503");
        }
    }

    @Test
    void withTheHmacSecretSetWhatsappStarts() {
        BaymaxProperties p = valid();
        p.getAuth().setHmacSecret("a-long-random-server-secret-0123456789");
        assertThatCode(() -> validate(p)).doesNotThrowAnyException();
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
        allow.getWa().setAllowlist(List.of("01700000000"));
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

    /** D2: with WhatsApp on, a web-minted code would make the open form a sender to any typed number. */
    @Test
    void theWebFormMayNotMintCodesWhileWhatsappIsOn() {
        BaymaxProperties p = valid();
        p.getAuth().setWebMintsCode(true);
        assertThatThrownBy(() -> validate(p)).hasMessageContaining("BAYMAX_AUTH_WEB_MINTS_CODE");
    }

    @Test
    void thePublicNumberForTheDeepLinkIsDigitsOnlyAndBlankIsAllowed() {
        BaymaxProperties p = valid();
        p.getWa().setPublicNumber("+880 1700-000000");
        assertThatThrownBy(() -> validate(p)).hasMessageContaining("BAYMAX_WA_PUBLIC_NUMBER");
        p.getWa().setPublicNumber("8801700000000");
        assertThatCode(() -> validate(p)).doesNotThrowAnyException();
        p.getWa().setPublicNumber("");
        assertThatCode(() -> validate(p)).doesNotThrowAnyException();
    }

    /**
     * PO 2026-10-18: a code is sent free only inside WhatsApp's 24 h window. A margin above 24 h would send into a closed
     * window (which fails, and the page would then show the button anyway), so it is refused at boot; zero or negative
     * would never send. 24 h itself is allowed, 23 h is the default.
     */
    @Test
    void theServiceWindowMarginIsPositiveAndAtMostTwentyFourHours() {
        BaymaxProperties p = valid();
        p.getWa().setServiceWindow(java.time.Duration.ofHours(25));
        assertThatThrownBy(() -> validate(p)).hasMessageContaining("BAYMAX_WA_SERVICE_WINDOW");
        p.getWa().setServiceWindow(java.time.Duration.ZERO);
        assertThatThrownBy(() -> validate(p)).hasMessageContaining("BAYMAX_WA_SERVICE_WINDOW");
        p.getWa().setServiceWindow(java.time.Duration.ofHours(24));
        assertThatCode(() -> validate(p)).doesNotThrowAnyException();
        p.getWa().setServiceWindow(java.time.Duration.ofHours(23));
        assertThatCode(() -> validate(p)).doesNotThrowAnyException();
    }

    // ---- Graph API version and read receipts (plan PR 10, Task 10.2) ---------------------------------------------

    @Test
    void aMalformedGraphVersionIsRefusedAndNamed() {
        BaymaxProperties p = valid();
        p.getWa().setGraphVersion("21.0");
        assertThatThrownBy(() -> validate(p)).hasMessageContaining("BAYMAX_WA_GRAPH_VERSION");
        p.getWa().setGraphVersion("v21.0 # rollback");
        assertThatThrownBy(() -> validate(p)).hasMessageContaining("BAYMAX_WA_GRAPH_VERSION");
    }

    @Test
    void anUnknownReadReceiptModeIsRefused() {
        BaymaxProperties p = valid();
        p.getWa().setReadReceipts("yes");
        assertThatThrownBy(() -> validate(p)).hasMessageContaining("BAYMAX_WA_READ_RECEIPTS");
        for (String mode : List.of("off", "read", "typing")) {
            p.getWa().setReadReceipts(mode);
            assertThatCode(() -> validate(p)).as(mode).doesNotThrowAnyException();
        }
    }

    /**
     * The RUNBOOK says "blank = the shipped default". A blank line in ~/medscribe.env reaches Spring as an empty string,
     * not as an absent variable, and {@code ${X:default}} keeps the empty string: without this, a blank
     * BAYMAX_WA_GRAPH_VERSION would build https://graph.facebook.com//… for every send.
     */
    @Test
    void aBlankVersionOrModeMeansTheShippedDefault() {
        BaymaxProperties p = valid();
        p.getWa().setGraphVersion("");
        p.getWa().setReadReceipts("  ");
        assertThat(p.getWa().getGraphVersion()).isEqualTo(new BaymaxProperties().getWa().getGraphVersion());
        assertThat(p.getWa().getReadReceipts()).isEqualTo("off");
        assertThatCode(() -> validate(p)).doesNotThrowAnyException();
    }

    /** Derived from the version in use, not a second property that can disagree with it. */
    @Test
    void theExpiryWarningFollowsTheConfiguredVersion() {
        assertThat(WaConfigValidator.graphVersionWarnings("v21.0", LocalDate.parse("2026-10-17")))
                .as("96 days before expiry: outside the 90-day window").isEmpty();
        assertThat(WaConfigValidator.graphVersionWarnings("v21.0", LocalDate.parse("2026-11-01")))
                .singleElement().asString().contains("expires 2027-01-21");   // 81 days: warned
        assertThat(WaConfigValidator.graphVersionWarnings("v25.0", LocalDate.parse("2026-10-17"))).isEmpty();
        assertThat(WaConfigValidator.graphVersionWarnings("v21.0", LocalDate.parse("2027-02-01")))
                .singleElement().asString().contains("expired");
        assertThat(WaConfigValidator.graphVersionWarnings("v99.0", LocalDate.parse("2026-10-17")))
                .singleElement().asString().contains("not in the expiry table");
    }

    /**
     * An expired version is warned, never refused: refusing to start would take MedScribe down too. Asserted on what
     * validate() logged, not on graphVersionWarnings: the post-deploy grep reads the log, and a validate() that never
     * looped over the warnings would leave the static helper's tests green.
     */
    @Test
    void anExpiredVersionStillStartsAndIsWarnedInTheLog() {
        BaymaxProperties p = valid();
        p.getWa().setGraphVersion("v21.0");
        assertThatCode(() -> new WaConfigValidator(p).validate(LocalDate.parse("2027-02-01"))).doesNotThrowAnyException();
        assertThat(graphVersionWarnsLogged()).singleElement().asString().contains("v21.0").contains("expired");
    }

    /** The other direction: the shipped version, far from expiry, logs no line the post-deploy grep would match. */
    @Test
    void theShippedVersionFarFromExpiryLogsNoGraphVersionLine() {
        BaymaxProperties p = valid();
        new WaConfigValidator(p).validate(LocalDate.parse("2026-10-21"));
        assertThat(p.getWa().getGraphVersion()).isEqualTo("v25.0");
        assertThat(logged.list).as("no level at all carries the phrase").map(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains("graph version"));
        // and the positive line the post-deploy check counts is there, naming the version in use
        assertThat(logged.list).map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.matches(".*wa config ok: .*graphVersion=v25\\.0 .*"));
    }

    @Test
    void aVersionNotInTheTableStillStartsAndIsWarned() {
        BaymaxProperties p = valid();
        p.getWa().setGraphVersion("v19.0");
        assertThatCode(() -> new WaConfigValidator(p).validate(LocalDate.parse("2026-10-21"))).doesNotThrowAnyException();
        assertThat(graphVersionWarnsLogged()).singleElement().asString().contains("not in the expiry table");
    }

    /**
     * The PO edits these two lines by hand, once during a Graph incident (the rollback). docker --env-file keeps a
     * trailing space or a CR, and a refused start takes Medioo and MedScribe down on the shared box: surrounding
     * whitespace and case are normalised, while a value that is malformed inside is still refused.
     */
    @Test
    void surroundingWhitespaceAndCaseAreForgivenButAMalformedValueIsNot() {
        for (String v : List.of("v21.0 ", "v21.0\r", " v21.0", "\tv21.0\r\n")) {
            BaymaxProperties p = valid();
            p.getWa().setGraphVersion(v);
            assertThat(p.getWa().getGraphVersion()).as("[%s]", v.replace("\r", "CR")).isEqualTo("v21.0");
            assertThatCode(() -> validate(p)).doesNotThrowAnyException();
        }
        for (String m : List.of("Typing", "TYPING ", "typing\r")) {
            BaymaxProperties p = valid();
            p.getWa().setReadReceipts(m);
            assertThat(p.getWa().getReadReceipts()).as("[%s]", m.replace("\r", "CR")).isEqualTo("typing");
            assertThatCode(() -> validate(p)).doesNotThrowAnyException();
        }
        BaymaxProperties commented = valid();
        commented.getWa().setGraphVersion("v21.0 # rollback");
        assertThatThrownBy(() -> validate(commented)).hasMessageContaining("BAYMAX_WA_GRAPH_VERSION");
        BaymaxProperties bare = valid();
        bare.getWa().setGraphVersion(" 21.0 ");
        assertThatThrownBy(() -> validate(bare)).hasMessageContaining("BAYMAX_WA_GRAPH_VERSION");
        BaymaxProperties unknownMode = valid();
        unknownMode.getWa().setReadReceipts(" yes ");
        assertThatThrownBy(() -> validate(unknownMode)).hasMessageContaining("BAYMAX_WA_READ_RECEIPTS");
    }

    /**
     * Regression guard for the stale pin: v21.0 was a literal in application.properties and the default in
     * BaymaxProperties, with nothing to say it expires 2027-01-21. Derived from the shipped files, not a hand-copied
     * version string: the default that ships (application.properties, resolved with the environment variable absent)
     * must be the code default and must be in the expiry table. This half fails only on a code change.
     */
    @Test
    void theShippedGraphVersionIsTheCodeDefaultAndInTheTable() throws Exception {
        String shipped = shippedGraphVersion(Map.of());
        assertThat(shipped).isEqualTo(new BaymaxProperties().getWa().getGraphVersion());
        assertThat(WaConfigValidator.GRAPH_EXPIRY).as("the shipped version must be in the expiry table").containsKey(shipped);
    }

    /** Fewer days left than this and the build is red. */
    private static final int EXPIRY_GATE_DAYS = 180;

    /** The first UTC day on which {@link #theShippedGraphVersionIsFarFromItsExpiry} fails for a given expiry. */
    private static LocalDate gateFiresOn(LocalDate expiry) {
        return expiry.minusDays(EXPIRY_GATE_DAYS - 1);
    }

    /**
     * A merge gate on a date, not on a code change (PR #96 asks the PO to acknowledge it): from {@link #gateFiresOn} the
     * build is red until the shipped version moves, half a year before Meta switches it off. Remedy: add the next
     * version's row to GRAPH_EXPIRY and bump DEFAULT_GRAPH_VERSION and application.properties (RUNBOOK "Graph API
     * version"). A hotfix that cannot wait for that migration sets the repository variable GRAPH_EXPIRY_GUARD_SKIP=true,
     * which CI passes to the build, and deletes it again afterwards.
     */
    @Test
    void theShippedGraphVersionIsFarFromItsExpiry() throws Exception {
        Assumptions.assumeFalse("true".equalsIgnoreCase(System.getenv("GRAPH_EXPIRY_GUARD_SKIP")),
                "GRAPH_EXPIRY_GUARD_SKIP=true: the Graph expiry date gate is skipped for this build");
        String shipped = shippedGraphVersion(Map.of());
        LocalDate expiry = WaConfigValidator.GRAPH_EXPIRY.get(shipped);
        long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(ZoneOffset.UTC), expiry);   // the validator's clock
        assertThat(daysLeft)
                .as("Graph " + shipped + " expires " + expiry + ", " + daysLeft + " days from today; this gate fires from "
                        + gateFiresOn(expiry) + ". Add the next version's row to WaConfigValidator.GRAPH_EXPIRY and bump "
                        + "DEFAULT_GRAPH_VERSION and baymax.wa.graph-version (RUNBOOK 'Graph API version'); a hotfix that "
                        + "cannot wait sets the repository variable GRAPH_EXPIRY_GUARD_SKIP=true")
                .isGreaterThanOrEqualTo(EXPIRY_GATE_DAYS);
    }

    /** The date the RUNBOOK names is the date the gate computes, so the two cannot drift apart. */
    @Test
    void theRunbookNamesTheDayTheExpiryGateFires() throws Exception {
        LocalDate fires = gateFiresOn(WaConfigValidator.GRAPH_EXPIRY.get(BaymaxProperties.Wa.DEFAULT_GRAPH_VERSION));
        assertThat(Files.readString(Path.of("../docs/RUNBOOK.md"), StandardCharsets.UTF_8))
                .contains("the build goes red from **" + fires + "**");
    }

    /** The rollback needs no code change: BAYMAX_WA_GRAPH_VERSION wins over the shipped default, and blank does not. */
    @Test
    void theGraphVersionIsOverriddenByItsEnvironmentVariable() throws Exception {
        // v22.0: neither the old literal nor the new default, so a line that ignores the environment cannot pass
        assertThat(shippedGraphVersion(Map.of("BAYMAX_WA_GRAPH_VERSION", "v22.0"))).isEqualTo("v22.0");
        assertThat(shippedGraphVersion(Map.of("BAYMAX_WA_GRAPH_VERSION", "")))
                .isEqualTo(new BaymaxProperties().getWa().getGraphVersion());
    }

    /**
     * baymax.wa.graph-version as the deployed app resolves it: the real application.properties under a real
     * system-environment source (the only one that applies Spring's relaxed env-var names), bound to BaymaxProperties.
     */
    private static String shippedGraphVersion(Map<String, Object> env) throws Exception {
        java.util.Properties file = new java.util.Properties();
        try (var in = Files.newBufferedReader(Path.of("../medscribe-ai/src/main/resources/application.properties"),
                StandardCharsets.UTF_8)) {
            file.load(in);
        }
        assertThat(file.getProperty("baymax.wa.graph-version")).as("the line exists").isNotNull();
        var environment = new org.springframework.core.env.StandardEnvironment();
        var sources = environment.getPropertySources();
        sources.remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(org.springframework.core.env.StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        sources.addLast(new org.springframework.core.env.PropertiesPropertySource("application.properties", file));
        return org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("baymax.wa", BaymaxProperties.Wa.class).get().getGraphVersion();
    }
}
