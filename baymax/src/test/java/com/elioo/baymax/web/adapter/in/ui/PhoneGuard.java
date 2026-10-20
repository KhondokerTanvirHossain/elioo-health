package com.elioo.baymax.web.adapter.in.ui;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The page-level "no phone number" check, in one place: every test that asks whether a page carries a phone number asks
 * here (FamilyViewTest's sweep and its count, BaymaxWebUiRouterTest's landing page, PhoneNumberGuardTest's two
 * directions).
 * <p>
 * A page is read twice: as written, and percent-decoded the way a browser reads a URL ({@code +} is a space, every
 * {@code %XX} run is UTF-8), because this app URL-encodes the language toggle's {@code ?back=} on every signed-in page
 * and the NOW page's wa.me share text ({@code %20} for spaces) — a number in either is a number. In both readings every
 * canonical UUID is masked first. A random UUID holds a number-shaped run ({@code 19492940-0135-4005-952b-…}) about once
 * in 1,600 draws, which is how the add sheet's fresh {@code upload_key} flaked the sweep; a UUID is the only random token
 * a page writes and it can never be a phone number, so masking it exempts nothing else. Then the number must not sit
 * inside a longer run of decimal digits.
 * <p>
 * Known limit: a number glued to further decimal digits ({@code 9801700000000123}) is not reported, nor is a number
 * written in a form neither reading shows (HTML numeric entities, double encoding). Letters, {@code _}, {@code -} and
 * {@code %XX} escapes on either side do not hide one.
 */
final class PhoneGuard {

    /**
     * A Bangladeshi mobile number however it is written: +880 / 880 / 0, ASCII or Bangla digits, with or without a
     * space or hyphen between groups. Java's \\d does not match Bangla digits, and the page is in Bangla.
     */
    static final String BD_MOBILE_CORE = "(?:\\+?[8৮][8৮][0০][\\s-]?|[0০])[1১][3-9৩-৯](?:[\\s-]?[0-9০-৯]){8}";

    /** The number, not inside a longer run of digits. */
    static final Pattern BD_MOBILE = Pattern.compile("(?<![0-9০-৯])" + BD_MOBILE_CORE + "(?![0-9০-৯])");

    /** A canonical UUID, upper or lower case, wherever it sits (after {@code %2F} included). */
    static final Pattern UUID_SHAPE = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Pattern PERCENT_RUN = Pattern.compile("(?:%[0-9A-Fa-f]{2})+");
    private static final int CONTEXT = 30;

    private PhoneGuard() {
    }

    /**
     * The first number on the page, named with about 30 characters either side and the reading it was found in, so a
     * red assertion says what it saw; empty when the page carries none.
     */
    static Optional<String> find(String html) {
        for (Reading r : readings(html)) {
            Matcher m = BD_MOBILE.matcher(r.text());
            if (m.find()) {
                String t = r.text();
                String around = t.substring(Math.max(0, m.start() - CONTEXT), Math.min(t.length(), m.end() + CONTEXT));
                return Optional.of("matched '" + m.group() + "' (" + r.name() + ") in …" + around + "…");
            }
        }
        return Optional.empty();
    }

    /** How many numbers the page carries: the larger count of the two readings, so an encoded copy is one more. */
    static long count(String html) {
        return readings(html).stream().mapToLong(r -> BD_MOBILE.matcher(r.text()).results().count()).max().orElse(0);
    }

    private record Reading(String name, String text) {
    }

    private static List<Reading> readings(String html) {
        // decoded first: it reads as a person would, so a red assertion quotes '/app/x?to=+880…' and not '%2Fapp%2Fx%3Fto%3D…'
        return List.of(new Reading("percent-decoded", mask(decode(html))), new Reading("as written", mask(html)));
    }

    private static String mask(String text) {
        return UUID_SHAPE.matcher(text).replaceAll("<uuid>");
    }

    /** URL decoding that never throws: a malformed escape stays as it is, a broken UTF-8 sequence becomes U+FFFD. */
    static String decode(String text) {
        Matcher m = PERCENT_RUN.matcher(text.replace('+', ' '));
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String run = m.group();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < run.length(); i += 3) {
                bytes.write(Integer.parseInt(run, i + 1, i + 3, 16));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(bytes.toString(StandardCharsets.UTF_8)));
        }
        return m.appendTail(out).toString();
    }
}
