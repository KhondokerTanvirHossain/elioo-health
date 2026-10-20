package com.elioo.baymax.wa.config;

import com.elioo.baymax.config.BaymaxProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses to start with a malformed WhatsApp configuration (BMX-10).
 *
 * <p>Written after a verify token spent an hour looking like a wrong value in Meta's dashboard. The real cause
 * was an inline {@code #} comment in {@code medscribe.env}: Docker's {@code --env-file} does not strip them, so
 * the value became the token plus eighteen spaces plus a sentence. The app accepted a 96-character verify token
 * without complaint and the mismatch surfaced only as a handshake rejection with no hint of why.
 *
 * <p>Every check here is a shape the value cannot legitimately have. They catch the paste accidents — a
 * comment, a quote, surrounding whitespace, an empty value with the channel enabled — not the case of a
 * correctly-shaped but wrong secret, which only the provider can judge. The message names the variable and what
 * is wrong with it, because the failure this replaces named neither.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "baymax.wa", name = "enabled", havingValue = "true")
public class WaConfigValidator {

    /** Meta app secrets are 32 lowercase hex characters. Anything else is not one. */
    private static final int APP_SECRET_LENGTH = 32;
    /** A system-user token is long; this only rules out obvious truncation and placeholders. */
    private static final int MIN_TOKEN_LENGTH = 40;

    /**
     * When each Graph API version stops working, from Meta's changelog
     * (https://developers.facebook.com/docs/graph-api/changelog/versions, fetched 2026-10-21; the page lists v26.0,
     * released 2026-07-29, with expiry "TBD", so it is not in this table yet). Add a row when the version changes.
     */
    static final Map<String, LocalDate> GRAPH_EXPIRY = Map.of(
            "v21.0", LocalDate.parse("2027-01-21"),
            "v22.0", LocalDate.parse("2027-05-20"),
            "v23.0", LocalDate.parse("2027-10-08"),
            "v24.0", LocalDate.parse("2028-02-18"),
            "v25.0", LocalDate.parse("2028-07-29"));
    /** How long before expiry startup starts warning. */
    static final int EXPIRY_WARNING_DAYS = 90;
    private static final Set<String> READ_RECEIPT_MODES = Set.of("off", "read", "typing");

    private final BaymaxProperties properties;

    /**
     * The startup warnings for a Graph version, derived from {@link #GRAPH_EXPIRY} and the version actually in use.
     * Logged at WARN, never thrown: refusing to start over an expiry would take MedScribe down with Baymax. Every line
     * contains "graph version", which the post-deploy check greps for.
     */
    static List<String> graphVersionWarnings(String version, LocalDate today) {
        LocalDate expiry = GRAPH_EXPIRY.get(version);
        if (expiry == null) {
            return List.of("wa graph version " + version + " is not in the expiry table (WaConfigValidator.GRAPH_EXPIRY); "
                    + "check https://developers.facebook.com/docs/graph-api/changelog/versions and add it");
        }
        long daysLeft = ChronoUnit.DAYS.between(today, expiry);
        if (daysLeft <= 0) {
            return List.of("wa graph version " + version + " expired " + expiry
                    + ": Graph calls may already fail; set BAYMAX_WA_GRAPH_VERSION blank (the shipped default) or newer");
        }
        if (daysLeft <= EXPIRY_WARNING_DAYS) {
            return List.of("wa graph version " + version + " expires " + expiry + " (" + daysLeft
                    + " days); move off it before then");
        }
        return List.of();
    }

    @PostConstruct
    void validate() {
        BaymaxProperties.Wa wa = properties.getWa();
        List<String> problems = new ArrayList<>();

        required(problems, "BAYMAX_WA_TOKEN", wa.getToken());
        required(problems, "BAYMAX_WA_APP_SECRET", wa.getAppSecret());
        required(problems, "BAYMAX_WA_VERIFY_TOKEN", wa.getVerifyToken());
        required(problems, "BAYMAX_WA_PHONE_NUMBER_ID", wa.getPhoneNumberId());
        required(problems, "BAYMAX_WA_WABA_ID", wa.getWabaId());

        noPasteArtefacts(problems, "BAYMAX_WA_TOKEN", wa.getToken());
        noPasteArtefacts(problems, "BAYMAX_WA_APP_SECRET", wa.getAppSecret());
        noPasteArtefacts(problems, "BAYMAX_WA_VERIFY_TOKEN", wa.getVerifyToken());
        noPasteArtefacts(problems, "BAYMAX_WA_PHONE_NUMBER_ID", wa.getPhoneNumberId());
        noPasteArtefacts(problems, "BAYMAX_WA_WABA_ID", wa.getWabaId());

        String secret = trimmedOrEmpty(wa.getAppSecret());
        if (!secret.isEmpty() && !secret.matches("[0-9a-fA-F]{" + APP_SECRET_LENGTH + "}")) {
            problems.add("BAYMAX_WA_APP_SECRET must be " + APP_SECRET_LENGTH
                    + " hex characters (it is " + secret.length() + " characters); every inbound callback would be rejected");
        }
        String token = trimmedOrEmpty(wa.getToken());
        if (!token.isEmpty() && token.length() < MIN_TOKEN_LENGTH) {
            problems.add("BAYMAX_WA_TOKEN looks truncated at " + token.length()
                    + " characters; every outbound send and media download would fail");
        }
        digitsOnly(problems, "BAYMAX_WA_PHONE_NUMBER_ID", wa.getPhoneNumberId());
        digitsOnly(problems, "BAYMAX_WA_WABA_ID", wa.getWabaId());

        for (String number : wa.getAllowlist()) {
            if (!number.trim().matches("\\+[1-9]\\d{7,14}")) {
                problems.add("BAYMAX_WA_ALLOWLIST entry is not E.164 (+ then 8-15 digits): '" + number.trim() + "'");
            }
        }

        if (properties.getAuth().isWebMintsCode()) {
            problems.add("BAYMAX_AUTH_WEB_MINTS_CODE must be false while BAYMAX_WA_ENABLED=true — the web form must never "
                    + "send codes to typed numbers (D2, DR-39)");
        }
        String publicNumber = trimmedOrEmpty(wa.getPublicNumber());
        if (!publicNumber.isEmpty() && !publicNumber.matches("[1-9]\\d{7,14}")) {
            problems.add("BAYMAX_WA_PUBLIC_NUMBER must be digits only, country code first, no plus or spaces (8801…); it is "
                    + publicNumber.length() + " characters with something else in it");
        }
        java.time.Duration window = wa.getServiceWindow();
        if (window == null || window.isNegative() || window.isZero() || window.compareTo(java.time.Duration.ofHours(24)) > 0) {
            problems.add("BAYMAX_WA_SERVICE_WINDOW must be more than zero and at most 24h (WhatsApp's free window); it is "
                    + window);
        }

        String version = wa.getGraphVersion();
        if (version == null || !version.matches("v\\d+\\.\\d+")) {
            problems.add("BAYMAX_WA_GRAPH_VERSION must look like v25.0 (a 'v', then digits, a dot, digits), or be blank "
                    + "for the shipped default; it is " + (version == null ? 0 : version.length()) + " characters");
        }
        if (!READ_RECEIPT_MODES.contains(wa.getReadReceipts())) {
            problems.add("BAYMAX_WA_READ_RECEIPTS must be off, read or typing (or blank for off)");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("WhatsApp channel is enabled but its configuration is malformed:\n  - "
                    + String.join("\n  - ", problems)
                    + "\n\nCheck ~/medscribe.env: an inline '# comment' after a value is NOT stripped by "
                    + "docker --env-file and becomes part of the value. Put comments on their own line.");
        }
        if (publicNumber.isEmpty()) {
            log.warn("[baymax] BAYMAX_WA_PUBLIC_NUMBER is blank: the sign-in page will show no WhatsApp button");
        }
        for (String warning : graphVersionWarnings(version, LocalDate.now(ZoneOffset.UTC))) {
            log.warn("[baymax] {}", warning);
        }
        log.info("[baymax] wa config ok: phoneNumberId={} wabaId={} allowlist={} entries serviceWindow={} graphVersion={} "
                        + "readReceipts={}",
                wa.getPhoneNumberId(), wa.getWabaId(), wa.getAllowlist().size(), window, version, wa.getReadReceipts());
    }

    private static void required(List<String> problems, String name, String value) {
        if (trimmedOrEmpty(value).isEmpty()) {
            problems.add(name + " is blank but the channel is enabled");
        }
    }

    /**
     * The paste accidents, named individually so the message says which one happened. A value is never echoed:
     * these are secrets, and the length and the shape are enough to act on.
     */
    private static void noPasteArtefacts(List<String> problems, String name, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (value.contains("#")) {
            problems.add(name + " contains '#' — an inline comment became part of the value "
                    + "(length " + value.length() + "); move the comment to its own line");
        }
        if (!value.equals(value.trim())) {
            problems.add(name + " has leading or trailing whitespace (length " + value.length() + ")");
        }
        if (value.contains(" ")) {
            problems.add(name + " contains a space (length " + value.length() + "); none of these values may");
        }
        if (value.startsWith("\"") || value.endsWith("\"") || value.startsWith("'") || value.endsWith("'")) {
            problems.add(name + " is wrapped in quotes; docker --env-file keeps them as part of the value");
        }
        if (value.startsWith("<") || value.endsWith(">")) {
            problems.add(name + " still looks like a placeholder: " + value);
        }
    }

    private static void digitsOnly(List<String> problems, String name, String value) {
        String v = trimmedOrEmpty(value);
        if (!v.isEmpty() && !v.matches("\\d+")) {
            problems.add(name + " must be digits only (it is '" + v + "')");
        }
    }

    private static String trimmedOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
