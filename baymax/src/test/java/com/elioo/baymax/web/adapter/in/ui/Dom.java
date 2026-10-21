package com.elioo.baymax.web.adapter.in.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The element tree of a page the web UI renders: tags, attributes, classes, parents and each element's own text. Our
 * pages are generated, so a small tokenizer is enough; it does not repair markup, it reports it: {@link #unclosed}
 * lists every close tag that matched no open element and every element still open at the end, so a test can refuse a
 * tree built from broken markup instead of measuring the wrong nesting.
 */
public final class Dom {

    private static final Pattern TOKEN = Pattern.compile(
            "<!--.*?-->|<!(?i:doctype)[^>]*>|<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>|([^<]+)", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?");
    private static final Set<String> VOID = Set.of("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr");

    public static final class Element {
        private final String tag;
        private final Map<String, String> attrs;
        private final Element parent;
        private final List<Element> children = new ArrayList<>();
        private final StringBuilder ownText = new StringBuilder();

        Element(String tag, Map<String, String> attrs, Element parent) {
            this.tag = tag;
            this.attrs = attrs;
            this.parent = parent;
        }

        public String tag() {
            return tag;
        }

        public Map<String, String> attrs() {
            return attrs;
        }

        Element parent() {
            return parent;
        }

        List<Element> children() {
            return children;
        }

        public Set<String> classes() {
            String c = attrs.getOrDefault("class", "").trim();
            return c.isEmpty() ? Set.of() : new java.util.LinkedHashSet<>(List.of(c.split("\\s+")));
        }

        /** The element's own text nodes (not its children's), with entities for spaces read as spaces. */
        String ownText() {
            return ownText.toString().replace("&nbsp;", " ").replace("\u00a0", " ").trim();
        }

        Element previousSibling() {
            if (parent == null) {
                return null;
            }
            int i = parent.children.indexOf(this);
            return i > 0 ? parent.children.get(i - 1) : null;
        }

        /** The opening tag as written, for a failure message. */
        String describe() {
            StringBuilder b = new StringBuilder("<").append(tag);
            attrs.forEach((k, v) -> {
                if (Set.of("class", "type", "href", "id").contains(k)) {
                    b.append(' ').append(k).append("=\"").append(v).append('"');
                }
            });
            return b.append('>').append(ownText().length() > 40 ? ownText().substring(0, 40) + "…" : ownText()).toString();
        }
    }

    final Element root = new Element("#document", Map.of(), null);
    public final List<Element> all = new ArrayList<>();
    final List<String> unclosed = new ArrayList<>();

    private Dom() {
    }

    public static Dom parse(String html) {
        Dom dom = new Dom();
        Deque<Element> open = new ArrayDeque<>();
        open.push(dom.root);
        Matcher t = TOKEN.matcher(html);
        while (t.find()) {
            if (t.group(4) != null) {
                open.peek().ownText.append(t.group(4));
                continue;
            }
            if (t.group(2) == null) {
                continue;   // comment or doctype
            }
            String tag = t.group(2).toLowerCase();
            if (t.group(1).equals("/")) {
                if (open.stream().noneMatch(e -> e.tag.equals(tag))) {
                    dom.unclosed.add("</" + tag + "> closes nothing");
                    continue;
                }
                while (!open.peek().tag.equals(tag)) {
                    dom.unclosed.add("<" + open.pop().tag + "> never closed");
                }
                open.pop();
                continue;
            }
            Map<String, String> attrs = new LinkedHashMap<>();
            String rest = t.group(3);
            for (Matcher a = ATTR.matcher(rest); a.find(); ) {
                String v = a.group(2) != null ? a.group(2) : a.group(3) != null ? a.group(3) : a.group(4) != null ? a.group(4) : "";
                attrs.put(a.group(1).toLowerCase(), v);
            }
            Element e = new Element(tag, attrs, open.peek());
            open.peek().children.add(e);
            dom.all.add(e);
            if (!VOID.contains(tag) && !rest.trim().endsWith("/")) {
                open.push(e);
            }
        }
        while (open.size() > 1) {
            dom.unclosed.add("<" + open.pop().tag + "> never closed");
        }
        return dom;
    }
}
