package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §1 rule 12, §8: body 17 px, nothing Bangla below 14 px, line-height ≥ 1.5. Checked on the tokens AND on every
 * rule: a rule may only size text through the tokens, and the Latin-only sizes only on Latin-only selectors.
 */
class TypeScaleTest {

    private static final String CSS = new WebUiTestKit().servedText("/assets/baymax.css");
    private static final Set<String> LATIN_ONLY = Set.of("caption-latin", "badge-latin", "value-large", "value", "unit", "range");
    /** Selectors that only ever hold printed Latin (values, units, ranges, badges, bar ends). */
    private static final Pattern LATIN_SELECTOR = Pattern.compile("\\.(val|unit|range|badge|ends)\\b");

    @Test
    void everyBanglaStyleTokenIsAtLeastFourteenPixelsWithLineHeightAtLeastOneAndAHalf() {
        Map<String, String> t = Tokens.light(CSS);
        List<String> styles = t.keySet().stream().filter(k -> k.startsWith("--fs-")).map(k -> k.substring(5)).toList();
        assertThat(styles).as("type styles defined").hasSizeGreaterThanOrEqualTo(18);
        assertThat(t.get("--fs-body")).isEqualTo("1.0625rem");   // 17 px
        List<String> failures = new ArrayList<>();
        for (String s : styles) {
            double px = Double.parseDouble(t.get("--fs-" + s).replace("rem", "")) * 16;
            String lh = t.get("--lh-" + s);
            assertThat(lh).as("--lh-" + s + " is defined").isNotNull();
            if (LATIN_ONLY.contains(s)) {
                continue;
            }
            if (px < 14) {
                failures.add(s + " is " + px + " px");
            }
            if (Double.parseDouble(lh) < 1.5) {
                failures.add(s + " line-height " + lh);
            }
        }
        assertThat(failures).isEmpty();
    }

    /** The size and optional line-height of a {@code font:} shorthand: the first length or token, then "/" and the next. */
    private static final Pattern FONT_SIZE = Pattern.compile(
            "(var\\(--[a-z0-9-]+\\)|[0-9.]+(?:px|rem|em|%|pt|vw|vh)|(?:xx?-)?(?:small|large)|medium|smaller|larger)(?:\\s*/\\s*(var\\(--[a-z0-9-]+\\)|[0-9.]+[a-z%]*|normal))?");

    /**
     * Every rule, one selector at a time (a selector list is split, so {@code .val,.label} is two rules), through
     * font-size, line-height and the {@code font:} shorthand alike: sizes only through --fs-* tokens, line-heights only
     * through --lh-* tokens or a number ≥ 1.5, and the Latin-only sizes and line-heights only on Latin-only selectors.
     */
    @Test
    void everyRuleSizesTextThroughTheTokensAndLatinSizesStayOnLatinSelectors() {
        List<String> failures = new ArrayList<>();
        int sized = 0;
        int shorthands = 0;
        boolean body = false;
        for (Css.Rule r : Css.rules(CSS)) {
            String selector = r.selector();
            List<String> sizes = new ArrayList<>();
            List<String> heights = new ArrayList<>();
            if (r.decls().containsKey("font-size")) {
                sizes.add(r.decls().get("font-size"));
            }
            if (r.decls().containsKey("line-height")) {
                heights.add(r.decls().get("line-height"));
            }
            if (r.decls().containsKey("font")) {
                String v = r.decls().get("font").replaceAll("\"[^\"]*\"|'[^']*'", "").trim();
                if (!Set.of("inherit", "initial", "unset").contains(v)) {
                    shorthands++;
                    Matcher m = FONT_SIZE.matcher(v);
                    if (!m.find()) {
                        failures.add(selector + " font " + v + " has no size the check can read");
                    } else {
                        sizes.add(m.group(1));
                        if (m.group(2) != null) {
                            heights.add(m.group(2));
                        }
                        if (selector.equals("body") && m.group(1).equals("var(--fs-body)") && "var(--lh-body)".equals(m.group(2))) {
                            body = true;
                        }
                    }
                }
            }
            boolean latin = LATIN_SELECTOR.matcher(selector).find();
            for (String v : sizes) {
                sized++;
                Matcher tok = Pattern.compile("var\\(--fs-([a-z0-9-]+)\\)").matcher(v.trim());
                if (!tok.matches()) {
                    failures.add(selector + " font-size " + v);
                } else if (LATIN_ONLY.contains(tok.group(1)) && !latin) {
                    failures.add(selector + " uses the Latin-only size --fs-" + tok.group(1));
                }
            }
            for (String v : heights) {
                Matcher tok = Pattern.compile("var\\(--lh-([a-z0-9-]+)\\)").matcher(v.trim());
                if (tok.matches()) {
                    if (LATIN_ONLY.contains(tok.group(1)) && !latin) {
                        failures.add(selector + " uses the Latin-only line-height --lh-" + tok.group(1));
                    }
                } else if (!(v.trim().matches("[0-9.]+") && Double.parseDouble(v.trim()) >= 1.5)) {
                    failures.add(selector + " line-height " + v);
                }
            }
        }
        assertThat(sized).as("font sizes read (font-size and the font shorthand)").isGreaterThan(20);
        assertThat(shorthands).as("font shorthands read; the body sets its size through one").isPositive();
        assertThat(body).as("the body rule uses --fs-body/--lh-body").isTrue();
        assertThat(failures).isEmpty();
    }
}
