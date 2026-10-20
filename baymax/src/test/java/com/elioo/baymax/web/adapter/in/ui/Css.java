package com.elioo.baymax.web.adapter.in.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The served stylesheet's rules (the token blocks and @font-face removed, {@link Tokens#rules}), one per selector of a
 * selector list, with a small selector matcher and cascade over {@link Dom} elements. Enough CSS for the derived checks
 * (TokenContrastTest, TouchTargetTest) to ask what actually styles an element on a rendered page instead of trusting a
 * class name: type, class, id, attribute and the structural pseudo-classes the stylesheet uses; descendant, child and
 * sibling combinators; specificity, then source order. Media queries are read as always applying, which over-applies a
 * rule. A selector the matcher does not understand throws, so a rule is never silently left out.
 */
final class Css {

    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    private static final Pattern DECL = Pattern.compile("([a-z-]+)\\s*:\\s*([^;]+)");
    private static final Pattern COMPOUND = Pattern.compile(
            "([a-z][a-z0-9]*|\\*)?((?:\\.[a-zA-Z0-9_-]+|#[a-zA-Z0-9_-]+|\\[[^]]+]|::?[a-z-]+(?:\\([^)]*\\))?)*)");
    private static final Pattern PART = Pattern.compile("\\.[a-zA-Z0-9_-]+|#[a-zA-Z0-9_-]+|\\[[^]]+]|::?[a-z-]+(?:\\([^)]*\\))?");
    private static final Pattern NTH = Pattern.compile(":nth-child\\(([0-9]+)\\)");
    /** Pseudo-classes for a state the element is not in at rest. */
    private static final Set<String> STATES = Set.of(":hover", ":active", ":focus", ":focus-visible", ":focus-within", ":visited");

    /** One compound selector: a type (or null) and its class, id, attribute and pseudo parts. */
    record Compound(String tag, List<String> parts) {

        static Compound parse(String text, String selector) {
            Matcher m = COMPOUND.matcher(text);
            if (!m.matches()) {
                throw new IllegalArgumentException("selector the matcher cannot read: " + selector);
            }
            List<String> parts = new ArrayList<>();
            for (Matcher p = PART.matcher(m.group(2)); p.find(); ) {
                parts.add(p.group());
            }
            return new Compound(m.group(1) == null || m.group(1).equals("*") ? null : m.group(1), List.copyOf(parts));
        }
    }

    /**
     * One selector of a rule with its declarations (property → value, last one wins inside the rule). {@code steps}
     * alternates compounds and combinators (" ", ">", "+", "~"), left to right.
     */
    record Rule(int order, String selector, Map<String, String> decls, List<Object> steps, int specificity) {

        boolean stateful() {
            return STATES.stream().anyMatch(selector::contains);
        }

        boolean pseudoElement() {
            return selector.contains("::");
        }

        static Rule of(int order, String selector, Map<String, String> decls) {
            List<Object> steps = new ArrayList<>();
            int ids = 0, classes = 0, types = 0;
            for (String token : selector.split(" ")) {
                if (token.matches("[>+~]")) {
                    steps.set(steps.size() - 1, token);
                    continue;
                }
                Compound c = Compound.parse(token, selector);
                if (c.tag() != null) {
                    types++;
                }
                for (String part : c.parts()) {
                    if (part.startsWith("#")) {
                        ids++;
                    } else if (part.startsWith("::")) {
                        types++;
                    } else {
                        classes++;
                    }
                }
                steps.add(c);
                steps.add(" ");
            }
            steps.remove(steps.size() - 1);
            return new Rule(order, selector, decls, List.copyOf(steps), ids * 10_000 + classes * 100 + types);
        }
    }

    private Css() {
    }

    /** Every rule of the stylesheet, one per selector, in source order. */
    static List<Rule> rules(String css) {
        List<Rule> out = new ArrayList<>();
        Matcher r = RULE.matcher(Tokens.rules(css));
        int order = 0;
        while (r.find()) {
            String selectors = r.group(1).trim();
            if (selectors.startsWith("@") || selectors.matches("[0-9%, ]+|from|to")) {
                continue;   // @keyframes steps
            }
            Map<String, String> decls = new LinkedHashMap<>();
            for (Matcher d = DECL.matcher(r.group(2)); d.find(); ) {
                decls.put(d.group(1), d.group(2).trim());
            }
            for (String selector : selectors.split(",")) {
                out.add(Rule.of(order++, selector.trim().replaceAll("\\s+", " ").replaceAll(" ?([>+~]) ?", " $1 "), Map.copyOf(decls)));
            }
        }
        return out;
    }

    /** True when the selector matches the element at rest (no state, not a pseudo-element). */
    static boolean matches(Rule rule, Dom.Element e) {
        if (rule.stateful() || rule.pseudoElement()) {
            return false;
        }
        return matchFrom(rule.steps(), rule.steps().size() - 1, e);
    }

    private static boolean matchFrom(List<Object> steps, int i, Dom.Element e) {
        if (!compound((Compound) steps.get(i), e)) {
            return false;
        }
        if (i == 0) {
            return true;
        }
        switch ((String) steps.get(i - 1)) {
            case ">" -> {
                return e.parent() != null && matchFrom(steps, i - 2, e.parent());
            }
            case "+" -> {
                Dom.Element prev = e.previousSibling();
                return prev != null && matchFrom(steps, i - 2, prev);
            }
            case "~" -> {
                for (Dom.Element s = e.previousSibling(); s != null; s = s.previousSibling()) {
                    if (matchFrom(steps, i - 2, s)) {
                        return true;
                    }
                }
                return false;
            }
            default -> {
                for (Dom.Element a = e.parent(); a != null; a = a.parent()) {
                    if (matchFrom(steps, i - 2, a)) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    private static boolean compound(Compound c, Dom.Element e) {
        if (c.tag() != null && !c.tag().equals(e.tag())) {
            return false;
        }
        for (String part : c.parts()) {
            boolean ok = switch (part.charAt(0)) {
                case '.' -> e.classes().contains(part.substring(1));
                case '#' -> part.substring(1).equals(e.attrs().get("id"));
                case '[' -> attribute(part.substring(1, part.length() - 1), e);
                default -> pseudoClass(part, e);
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean attribute(String expr, Dom.Element e) {
        int eq = expr.indexOf('=');
        if (eq < 0) {
            return e.attrs().containsKey(expr.trim());
        }
        String value = expr.substring(eq + 1).trim().replaceAll("^[\"']|[\"']$", "");
        return value.equals(e.attrs().get(expr.substring(0, eq).trim()));
    }

    private static boolean pseudoClass(String pseudo, Dom.Element e) {
        List<Dom.Element> siblings = e.parent() == null ? List.of(e) : e.parent().children();
        return switch (pseudo) {
            case ":first-child" -> siblings.get(0) == e;
            case ":last-child" -> siblings.get(siblings.size() - 1) == e;
            case ":first-of-type" -> siblings.stream().filter(s -> s.tag().equals(e.tag())).findFirst().orElse(null) == e;
            // the state a page is rendered in: a radio or checkbox the server marked checked
            case ":checked" -> e.attrs().containsKey("checked");
            // likewise a control the server rendered disabled (it renders none: upload.js disables the tray's ends, D4)
            case ":disabled" -> e.attrs().containsKey("disabled");
            default -> {
                if (pseudo.startsWith(":has(") && pseudo.endsWith(")")) {
                    Compound inner = Compound.parse(pseudo.substring(5, pseudo.length() - 1), pseudo);
                    yield descendants(e).stream().anyMatch(d -> compound(inner, d));
                }
                Matcher nth = NTH.matcher(pseudo);
                if (nth.matches()) {
                    int n = Integer.parseInt(nth.group(1));
                    yield siblings.size() >= n && siblings.get(n - 1) == e;
                }
                throw new IllegalArgumentException("pseudo-class the matcher cannot read: " + pseudo);
            }
        };
    }

    private static List<Dom.Element> descendants(Dom.Element e) {
        List<Dom.Element> out = new ArrayList<>();
        for (Dom.Element c : e.children()) {
            out.add(c);
            out.addAll(descendants(c));
        }
        return out;
    }

    /**
     * The rule that wins any of {@code properties} on the element itself, if any: an {@code !important} declaration first,
     * then specificity, then source order — the cascade a browser applies (D4's {@code [hidden]} rule needs the first).
     */
    static Optional<Rule> winner(List<Rule> rules, Dom.Element e, String... properties) {
        List<String> props = List.of(properties);
        return rules.stream().filter(r -> props.stream().anyMatch(r.decls()::containsKey)).filter(r -> matches(r, e))
                .max(Comparator.comparing((Rule r) -> important(r, props)).thenComparingInt(Rule::specificity).thenComparingInt(Rule::order));
    }

    private static boolean important(Rule r, List<String> props) {
        return props.stream().map(r.decls()::get).anyMatch(v -> v != null && v.replace(" ", "").endsWith("!important"));
    }

    /** A declared value without its {@code !important}. */
    static String bare(String value) {
        return value.replaceAll("\\s*!\\s*important$", "").trim();
    }

    /** The value the rule gives the first of {@code properties} it declares. */
    static String valueOf(Rule rule, String... properties) {
        for (String p : properties) {
            if (rule.decls().containsKey(p)) {
                return rule.decls().get(p);
            }
        }
        throw new IllegalStateException(rule.selector() + " declares none of " + List.of(properties));
    }
}
