package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §8 / §10: every text colour clears 4.5:1 on the background it is painted on, in light and in dark.
 *
 * <p>The pairs come from what is drawn, not from a rule's own declarations. For every element that carries text on
 * every page the router renders ({@link RenderedPages}), the colour is the one the cascade gives it — its own rule or,
 * when it sets none, the nearest ancestor's — and the background is the nearest one painted under it ({@link Css},
 * {@link Dom}). So a link inside a tinted card, or body text inside a block that only sets a background, is measured on
 * that block, which a rule-by-rule reading cannot see.
 *
 * <p>A rule no fixture page renders (a hover state, a block only some data shows) is still measured from the
 * stylesheet: colour and background as written together; a colour alone on --c-surface and --c-bg; a background alone
 * under the inherited body colour. Only the pairs no rule can express (the focus ring, control outlines) are listed.
 */
class TokenContrastTest {

    private static final String CSS = new WebUiTestKit().servedText("/assets/baymax.css");
    private static final List<Css.Rule> RULES = Css.rules(CSS);
    private static final Pattern VAR = Pattern.compile("var\\((--c-[a-z0-9-]+)\\)");
    private static final Set<String> SEE_THROUGH = Set.of("inherit", "currentcolor", "transparent", "none", "unset", "initial");

    record Pair(String where, String fg, String bg, double min) {
    }

    /** The two themes in a fixed order, so a failure message is the same on every run. */
    private static List<Map.Entry<String, Map<String, String>>> themes() {
        return List.of(Map.entry("light", Tokens.light(CSS)), Map.entry("dark", Tokens.dark(CSS)));
    }

    /** WCAG 1.4.11: the focus ring and control outlines against what they sit on (3:1). Not expressible as a rule pair. */
    static final List<Pair> NON_TEXT = List.of(new Pair("focus ring", "--c-focus", "--c-bg", 3), new Pair("focus ring", "--c-focus", "--c-surface", 3),
            new Pair("control outline", "--c-outline", "--c-surface", 3), new Pair("control outline", "--c-outline", "--c-bg", 3));
    /** Logotypes are exempt from 1.4.3; each exemption names the rule that colours it and why. The list only shrinks. */
    static final Map<String, String> EXEMPT = Map.of(".wm-text .oo", "the wordmark's green \"oo\" is a logotype (WCAG 1.4.3 exception); never used for text");

    /** What decides a colour or a background on an element: the token and the rule it came from. */
    record Paint(String token, String rule) {
    }

    /** The text colour the cascade gives the element: its own winning rule, else the nearest ancestor's. */
    static Paint colour(Dom.Element e) {
        for (Dom.Element x = e; x != null; x = x.parent()) {
            Optional<Css.Rule> w = Css.winner(RULES, x, "color");
            if (w.isPresent()) {
                String v = Css.valueOf(w.get(), "color");
                Matcher m = VAR.matcher(v);
                if (m.matches()) {
                    return new Paint(m.group(1), w.get().selector());
                }
                if (!SEE_THROUGH.contains(v.toLowerCase())) {
                    return new Paint("not a token: " + v, w.get().selector());
                }
            }
        }
        return new Paint("no colour set on the page", "-");
    }

    /** The background painted under the element: its own, else the nearest ancestor's that is not see-through. */
    static Paint background(Dom.Element e) {
        for (Dom.Element x = e; x != null; x = x.parent()) {
            Optional<Css.Rule> w = Css.winner(RULES, x, "background", "background-color");
            if (w.isPresent()) {
                String v = Css.valueOf(w.get(), "background", "background-color");
                Matcher m = VAR.matcher(v);
                if (m.find()) {
                    return new Paint(m.group(1), w.get().selector());
                }
                String first = v.trim().split("\\s+")[0].toLowerCase();
                if (!SEE_THROUGH.contains(first) && !first.startsWith("url(")) {
                    return new Paint("not a token: " + v, w.get().selector());
                }
            }
        }
        return new Paint("no background set on the page", "-");
    }

    private static boolean inHead(Dom.Element e) {
        for (Dom.Element x = e; x != null; x = x.parent()) {
            if (x.tag().equals("head")) {
                return true;
            }
        }
        return false;
    }

    /** Every (colour, background) a rendered page draws text in, once per pair of deciding rules. */
    static List<Pair> renderedPairs(Map<String, String> pages, List<String> broken) {
        Map<String, Pair> pairs = new LinkedHashMap<>();
        pages.forEach((where, html) -> {
            Dom dom = Dom.parse(html);
            if (!dom.unclosed.isEmpty()) {
                broken.add(where + ": " + dom.unclosed);
            }
            for (Dom.Element e : dom.all) {
                if (e.ownText().isEmpty() || inHead(e)) {
                    continue;
                }
                Paint fg = colour(e);
                if (EXEMPT.containsKey(fg.rule())) {
                    continue;
                }
                Paint bg = background(e);
                pairs.putIfAbsent(fg.rule() + "|" + bg.rule() + "|" + fg.token() + "|" + bg.token(),
                        new Pair(fg.rule() + " on " + bg.rule() + " (" + where + ": " + e.describe() + ")", fg.token(), bg.token(), 4.5));
            }
        });
        return List.copyOf(pairs.values());
    }

    /** Rules no rendered page draws (or only in a state), measured from the stylesheet alone. */
    static List<Pair> stylesheetPairs(Set<Css.Rule> rendered) {
        List<Pair> pairs = new ArrayList<>();
        for (Css.Rule r : RULES) {
            if (EXEMPT.containsKey(r.selector()) || r.pseudoElement()) {
                continue;
            }
            Matcher c = r.decls().containsKey("color") ? VAR.matcher(r.decls().get("color")) : null;
            String bgValue = r.decls().getOrDefault("background", r.decls().get("background-color"));
            Matcher b = bgValue == null ? null : VAR.matcher(bgValue);
            boolean hasC = c != null && c.matches();
            boolean hasB = b != null && b.find();
            if (hasC && hasB) {
                pairs.add(new Pair(r.selector(), c.group(1), b.group(1), 4.5));
            } else if (rendered.contains(r)) {
                continue;
            } else if (hasC) {
                pairs.add(new Pair(r.selector() + " (no page draws it)", c.group(1), "--c-surface", 4.5));
                pairs.add(new Pair(r.selector() + " (no page draws it)", c.group(1), "--c-bg", 4.5));
            } else if (hasB) {
                pairs.add(new Pair(r.selector() + " (no page draws it) under the body colour", "--c-text", b.group(1), 4.5));
            }
        }
        return pairs;
    }

    static Set<Css.Rule> renderedRules(Map<String, String> pages) {
        Set<Css.Rule> rendered = new LinkedHashSet<>();
        for (String html : pages.values()) {
            for (Dom.Element e : Dom.parse(html).all) {
                for (Css.Rule r : RULES) {
                    if (Css.matches(r, e)) {
                        rendered.add(r);
                    }
                }
            }
        }
        return rendered;
    }

    static List<String> measure(List<Pair> pairs) {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> theme : themes()) {
            for (Pair p : pairs) {
                String fg = theme.getValue().get(p.fg());
                String bg = theme.getValue().get(p.bg());
                if (fg == null || bg == null) {
                    failures.add("%s: %s — %s on %s is not defined in this theme".formatted(theme.getKey(), p.where(), p.fg(), p.bg()));
                    continue;
                }
                double ratio = ratio(fg, bg);
                if (ratio < p.min()) {
                    failures.add("%s: %s — %s on %s is %.2f:1, needs %.1f:1".formatted(theme.getKey(), p.where(), p.fg(), p.bg(), ratio, p.min()));
                }
            }
        }
        return failures;
    }

    @Test
    void everyTextAPageDrawsClearsTheBackgroundUnderItInLightAndDark() {
        Map<String, String> pages = RenderedPages.all();
        List<String> broken = new ArrayList<>();
        List<Pair> pairs = renderedPairs(pages, broken);
        assertThat(broken).as("pages whose markup does not nest (the tree would measure the wrong background)").isEmpty();
        assertThat(pairs).as("text pairs drawn on the rendered pages; a broken parse would pass vacuously").hasSizeGreaterThan(30);
        assertThat(measure(pairs)).isEmpty();
    }

    @Test
    void everyRuleNoPageDrawsClearsItsBackgroundInLightAndDark() {
        Set<Css.Rule> rendered = renderedRules(RenderedPages.all());
        assertThat(rendered).as("rules some rendered page draws").hasSizeGreaterThan(60);
        List<Pair> pairs = new ArrayList<>(stylesheetPairs(rendered));
        assertThat(pairs).as("pairs derived from the served rules; a broken parse would pass vacuously").hasSizeGreaterThan(10);
        pairs.addAll(NON_TEXT);
        assertThat(measure(pairs)).isEmpty();
    }

    /** The dark palette is written twice (media query and data-theme); the copies may not drift. */
    @Test
    void theTwoDarkBlocksAreTheSameAndRedefineEveryColour() {
        assertThat(Tokens.dark(CSS)).hasSizeGreaterThan(30).isEqualTo(Tokens.darkAttribute(CSS));
        assertThat(Tokens.dark(CSS).keySet()).containsAll(Tokens.light(CSS).keySet().stream().filter(k -> k.startsWith("--c-")).toList());
    }

    /** An exemption that no rule needs any more is removed (the list only shrinks), and each one is drawn on a page. */
    @Test
    void everyExemptionStillNamesARuleAPageDraws() {
        assertThat(EXEMPT).as("exemptions (empty would make this vacuous; remove the test with the last one)").isNotEmpty();
        Set<String> drawn = new LinkedHashSet<>();
        renderedRules(RenderedPages.all()).forEach(r -> drawn.add(r.selector()));
        assertThat(drawn).containsAll(EXEMPT.keySet());
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
