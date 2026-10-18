package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §8 / §10: every text colour clears 4.5:1 on the background it is painted on, in light and in dark. The pairs
 * are derived from the served stylesheet: a rule declaring both color and background is measured as written; a rule
 * declaring only color is measured on --c-surface and --c-bg. Only the two pairs a rule cannot express are listed.
 */
class TokenContrastTest {

    private static final String CSS = new WebUiTestKit().servedText("/assets/baymax.css");
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    private static final Pattern COLOR = Pattern.compile("(?<![-a-z])color\\s*:\\s*var\\((--c-[a-z0-9-]+)\\)");
    private static final Pattern BACKGROUND = Pattern.compile("background(?:-color)?\\s*:\\s*var\\((--c-[a-z0-9-]+)\\)");

    record Pair(String where, String fg, String bg, double min) {
    }

    /** WCAG 1.4.11: the focus ring and control outlines against what they sit on (3:1). Not expressible as a rule pair. */
    static final List<Pair> NON_TEXT = List.of(new Pair("focus ring", "--c-focus", "--c-bg", 3), new Pair("focus ring", "--c-focus", "--c-surface", 3),
            new Pair("control outline", "--c-outline", "--c-surface", 3), new Pair("control outline", "--c-outline", "--c-bg", 3));
    /**
     * Logotypes are exempt from 1.4.3; each exemption names the selector and why. Empty until A5 draws the text
     * wordmark (".wm-text .oo", the green "oo", a logotype); the list only shrinks after that.
     */
    static final Map<String, String> EXEMPT = Map.of();

    static List<Pair> derivedPairs(String css) {
        List<Pair> pairs = new ArrayList<>();
        Matcher r = RULE.matcher(Tokens.rules(css));
        while (r.find()) {
            String selector = r.group(1).trim();
            if (selector.startsWith("@") || EXEMPT.containsKey(selector)) {
                continue;
            }
            Matcher c = COLOR.matcher(r.group(2));
            if (!c.find()) {
                continue;
            }
            Matcher b = BACKGROUND.matcher(r.group(2));
            if (b.find()) {
                pairs.add(new Pair(selector, c.group(1), b.group(1), 4.5));
            } else {
                pairs.add(new Pair(selector, c.group(1), "--c-surface", 4.5));
                pairs.add(new Pair(selector, c.group(1), "--c-bg", 4.5));
            }
        }
        return pairs;
    }

    @Test
    void everyTextColourClearsItsBackgroundInLightAndDark() {
        List<Pair> pairs = new ArrayList<>(derivedPairs(CSS));
        assertThat(pairs).as("pairs derived from the served rules; a broken parse would pass vacuously").hasSizeGreaterThan(30);
        pairs.addAll(NON_TEXT);
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> theme : Map.of("light", Tokens.light(CSS), "dark", Tokens.dark(CSS)).entrySet()) {
            for (Pair p : pairs) {
                String fg = theme.getValue().get(p.fg());
                String bg = theme.getValue().get(p.bg());
                assertThat(fg).as("%s %s (%s) is defined", theme.getKey(), p.fg(), p.where()).isNotNull();
                assertThat(bg).as("%s %s (%s) is defined", theme.getKey(), p.bg(), p.where()).isNotNull();
                double ratio = ratio(fg, bg);
                if (ratio < p.min()) {
                    failures.add("%s: %s — %s on %s is %.2f:1, needs %.1f:1".formatted(theme.getKey(), p.where(), p.fg(), p.bg(), ratio, p.min()));
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    /** The dark palette is written twice (media query and data-theme); the copies may not drift. */
    @Test
    void theTwoDarkBlocksAreTheSameAndRedefineEveryColour() {
        assertThat(Tokens.dark(CSS)).hasSizeGreaterThan(30).isEqualTo(Tokens.darkAttribute(CSS));
        assertThat(Tokens.dark(CSS).keySet()).containsAll(Tokens.light(CSS).keySet().stream().filter(k -> k.startsWith("--c-")).toList());
    }

    /** An exemption that no rule needs any more is removed (the list only shrinks). */
    @Test
    void everyExemptionStillNamesARealRule() {
        Set<String> selectors = new LinkedHashSet<>();
        Matcher r = RULE.matcher(Tokens.rules(CSS));
        while (r.find()) {
            selectors.add(r.group(1).trim());
        }
        assertThat(selectors).containsAll(EXEMPT.keySet());
    }

    static double ratio(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        String h = hex.substring(1, 7);
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
