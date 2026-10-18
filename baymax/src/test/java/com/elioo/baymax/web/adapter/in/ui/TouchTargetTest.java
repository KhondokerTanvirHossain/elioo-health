package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §1 rule 12 / WCAG 2.5.8 at Medioo's 48 px: every control a page renders is at least 48 px tall. The controls are
 * read from every rendered page ({@link RenderedPages}), and the height from the rules that actually match each one in
 * the cascade ({@link Css}), never from a class name: a rule scoped to {@code .top} does not make the same class tall
 * elsewhere, and a min-height on an element left {@code display:inline} does nothing.
 *
 * <p>A control is a link with an href, a button, a select, a textarea, a text-like input, and a label that wraps a
 * checkbox or radio (the label is the target, not the 22 px box). Not a control: anything with tabindex=-1, and a link
 * inside a sentence (its parent has text of its own), which is the WCAG 2.5.8 inline exception.
 */
class TouchTargetTest {

    private static final String CSS = new WebUiTestKit().servedText("/assets/baymax.css");
    private static final List<Css.Rule> RULES = Css.rules(CSS);
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    /** min-height or height, and never max-height or line-height. */
    private static final Pattern SIZE = Pattern.compile("(?<![a-z-])(?:min-)?height\\s*:\\s*([^;]+)");
    private static final Set<String> TALL_TOKENS = Set.of("--target-min", "--sp-7", "--sp-8", "--header-h", "--tabbar-h", "--composer-h", "--now-strip-h");
    private static final Set<String> INLINE_BY_DEFAULT = Set.of("a", "label", "span");
    private static final Set<String> NOT_TEXT_INPUTS = Set.of("hidden", "checkbox", "radio");

    static boolean tall(String value) {
        Matcher var = Pattern.compile("var\\((--[a-z0-9-]+)\\)").matcher(value);
        if (var.find() && TALL_TOKENS.contains(var.group(1))) {
            return true;
        }
        Matcher px = Pattern.compile("^([0-9.]+)px$").matcher(value.trim());
        return px.matches() && Double.parseDouble(px.group(1)) >= 48;
    }

    static boolean isControl(Dom.Element e) {
        if ("-1".equals(e.attrs().get("tabindex"))) {
            return false;
        }
        return switch (e.tag()) {
            case "a" -> e.attrs().containsKey("href") && (e.parent() == null || e.parent().ownText().isEmpty());
            case "button", "select", "textarea" -> true;
            case "input" -> !NOT_TEXT_INPUTS.contains(e.attrs().getOrDefault("type", "text")) && !inLabel(e);
            case "label" -> e.children().stream().anyMatch(c -> c.tag().equals("input")
                    && Set.of("checkbox", "radio").contains(c.attrs().getOrDefault("type", "")));
            default -> false;
        };
    }

    private static boolean inLabel(Dom.Element e) {
        for (Dom.Element x = e.parent(); x != null; x = x.parent()) {
            if (x.tag().equals("label")) {
                return true;
            }
        }
        return false;
    }

    /** True when the cascade gives the element a min-height or height of at least 48 px on a box that honours it. */
    static boolean fortyEight(Dom.Element e) {
        if (INLINE_BY_DEFAULT.contains(e.tag()) && display(e).equals("inline") && !display(e.parent()).matches("(inline-)?(flex|grid)")) {
            return false;   // a min-height on an inline box does nothing, unless a flex or grid parent makes it an item
        }
        boolean tall = false;
        for (String property : List.of("min-height", "height")) {
            Optional<Css.Rule> w = Css.winner(RULES, e, property);
            tall |= w.isPresent() && tall(Css.valueOf(w.get(), property));
        }
        return tall;
    }

    private static String display(Dom.Element e) {
        if (e == null) {
            return "block";
        }
        Optional<Css.Rule> w = Css.winner(RULES, e, "display");
        return w.map(r -> Css.valueOf(r, "display")).orElse(INLINE_BY_DEFAULT.contains(e.tag()) ? "inline" : "block");
    }

    @Test
    void everyControlAPageRendersIsFortyEightPixelsTall() {
        Set<String> failures = new TreeSet<>();
        Set<String> broken = new TreeSet<>();
        int controls = 0;
        for (Map.Entry<String, String> page : RenderedPages.all().entrySet()) {
            Dom dom = Dom.parse(page.getValue());
            if (!dom.unclosed.isEmpty()) {
                broken.add(page.getKey() + ": " + dom.unclosed);
            }
            for (Dom.Element e : dom.all) {
                if (!isControl(e)) {
                    continue;
                }
                controls++;
                if (!fortyEight(e)) {
                    failures.add(page.getKey() + ": " + e.describe());
                }
            }
        }
        assertThat(broken).as("pages whose markup does not nest").isEmpty();
        assertThat(controls).as("controls found on the rendered pages").isGreaterThan(100);
        assertThat(failures).isEmpty();
    }

    /** A link or button a rule makes into a box (inline-flex, flex, block, grid) is a control: it must be ≥ 48 px. */
    @Test
    void everyLinkOrButtonARuleTurnsIntoABoxIsFortyEightPixelsTall() {
        Matcher r = RULE.matcher(Tokens.rules(CSS));
        List<String> failures = new ArrayList<>();
        int boxes = 0;
        while (r.find()) {
            for (String selector : r.group(1).split(",")) {
                String last = selector.trim().replaceAll(".*\\s", "");
                if (!last.matches("(a|button)(\\[[^]]*])?") || !r.group(2).matches("(?s).*display\\s*:\\s*(inline-)?(flex|block|grid).*")) {
                    continue;
                }
                boxes++;
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
        assertThat(boxes).as("link or button rules that make a box").isPositive();
        assertThat(failures).isEmpty();
    }
}
