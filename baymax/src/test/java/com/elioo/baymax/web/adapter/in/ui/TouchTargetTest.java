package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §1 rule 12 / WCAG 2.5.8 at Medioo's 48 px: every styled control a page renders has a rule giving it
 * min-height or height ≥ 48 px. Controls are derived from every fixture page (a classed a/button/label is a control;
 * an unclassed link inside prose is the WCAG inline exception), and from the stylesheet (a link or button a rule turns
 * into a block or flex box).
 */
class TouchTargetTest {

    private static final String CSS = new WebUiTestKit().servedText("/assets/baymax.css");
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    private static final Pattern CONTROL = Pattern.compile("<(a|button|label)\\b[^>]*\\bclass=\"([^\"]+)\"");
    private static final Pattern SIZE = Pattern.compile("(?:min-)?height\\s*:\\s*([^;]+)");
    private static final Set<String> TALL_TOKENS = Set.of("--target-min", "--sp-7", "--sp-8", "--header-h", "--tabbar-h", "--composer-h", "--now-strip-h");
    /** Classes on a/button/label that are not targets, each with why. */
    private static final Map<String, String> NOT_TARGETS = Map.of("scrim", "aria-hidden, tabindex=-1; the close control is the target");

    static boolean tall(String value) {
        Matcher var = Pattern.compile("var\\((--[a-z0-9-]+)\\)").matcher(value);
        if (var.find() && TALL_TOKENS.contains(var.group(1))) {
            return true;
        }
        Matcher px = Pattern.compile("^([0-9.]+)px$").matcher(value.trim());
        return px.matches() && Double.parseDouble(px.group(1)) >= 48;
    }

    /**
     * class → true when some rule whose last compound names exactly that class (no pseudo-class) is ≥ 48 px tall. An
     * element rule ({@code button{…}}) is recorded under {@code @button}.
     */
    static Map<String, Boolean> sizedClasses(String css) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        Matcher r = RULE.matcher(Tokens.rules(css));
        while (r.find()) {
            for (String selector : r.group(1).split(",")) {
                String last = selector.trim().replaceAll(".*\\s", "");
                if (last.contains(":")) {
                    continue;
                }
                Matcher size = SIZE.matcher(r.group(2));
                boolean isTall = false;
                while (size.find()) {
                    isTall |= tall(size.group(1));
                }
                if (last.matches("[a-z]+")) {
                    out.merge("@" + last, isTall, Boolean::logicalOr);
                }
                for (Matcher c = Pattern.compile("\\.([a-z0-9-]+)").matcher(last); c.find(); ) {
                    out.merge(c.group(1), isTall, Boolean::logicalOr);
                }
            }
        }
        return out;
    }

    @Test
    void everyControlAPageRendersHasAFortyEightPixelRule() {
        Map<String, Boolean> sized = sizedClasses(CSS);
        Set<String> failures = new TreeSet<>();
        int controls = 0;
        for (Map.Entry<String, String> page : RenderedPages.all().entrySet()) {
            Matcher m = CONTROL.matcher(page.getValue());
            while (m.find()) {
                List<String> classes = List.of(m.group(2).trim().split("\\s+"));
                if (classes.stream().anyMatch(NOT_TARGETS::containsKey)) {
                    continue;
                }
                controls++;
                if (classes.stream().noneMatch(c -> sized.getOrDefault(c, false)) && !(m.group(1).equals("button") && sized.getOrDefault("@button", false))) {
                    failures.add(page.getKey() + ": <" + m.group(1) + " class=\"" + m.group(2) + "\">");
                }
            }
        }
        assertThat(controls).as("controls found on the fixture pages").isGreaterThan(10);
        assertThat(failures).isEmpty();
    }

    /** A link or button a rule makes into a box (inline-flex, flex, block, grid) is a control: it must be ≥ 48 px. */
    @Test
    void everyLinkOrButtonARuleTurnsIntoABoxIsFortyEightPixelsTall() {
        Matcher r = RULE.matcher(Tokens.rules(CSS));
        List<String> failures = new ArrayList<>();
        while (r.find()) {
            for (String selector : r.group(1).split(",")) {
                String last = selector.trim().replaceAll(".*\\s", "");
                if (!last.matches("(a|button)(\\[[^]]*])?") || !r.group(2).matches("(?s).*display\\s*:\\s*(inline-)?(flex|block|grid).*")) {
                    continue;
                }
                Matcher size = SIZE.matcher(r.group(2));
                boolean isTall = false;
                while (size.find()) {
                    isTall |= tall(size.group(1));
                }
                if (!isTall) {
                    failures.add(selector.trim());
                }
            }
        }
        assertThat(failures).isEmpty();
    }
}
