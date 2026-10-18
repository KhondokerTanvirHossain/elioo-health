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
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)}");

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

    @Test
    void everyRuleSizesTextThroughTheTokensAndLatinSizesStayOnLatinSelectors() {
        Matcher r = RULE.matcher(Tokens.rules(CSS));
        List<String> failures = new ArrayList<>();
        int sized = 0;
        boolean body = false;
        while (r.find()) {
            String selector = r.group(1).trim();
            String decl = r.group(2);
            for (Matcher m = Pattern.compile("font-size\\s*:\\s*([^;]+)").matcher(decl); m.find(); ) {
                sized++;
                String v = m.group(1).trim();
                Matcher tok = Pattern.compile("var\\(--fs-([a-z0-9-]+)\\)").matcher(v);
                if (!tok.matches()) {
                    failures.add(selector + " font-size " + v);
                } else if (LATIN_ONLY.contains(tok.group(1)) && !LATIN_SELECTOR.matcher(selector).find()) {
                    failures.add(selector + " uses the Latin-only size --fs-" + tok.group(1));
                }
            }
            for (Matcher m = Pattern.compile("line-height\\s*:\\s*([^;]+)").matcher(decl); m.find(); ) {
                String v = m.group(1).trim();
                if (!v.matches("var\\(--lh-[a-z0-9-]+\\)") && !(v.matches("[0-9.]+") && Double.parseDouble(v) >= 1.5)) {
                    failures.add(selector + " line-height " + v);
                }
            }
            if (selector.equals("body") && decl.contains("var(--fs-body)") && decl.contains("var(--lh-body)")) {
                body = true;
            }
        }
        assertThat(sized).as("font-size declarations read").isGreaterThan(20);
        assertThat(body).as("the body rule uses --fs-body/--lh-body").isTrue();
        assertThat(failures).isEmpty();
    }
}
