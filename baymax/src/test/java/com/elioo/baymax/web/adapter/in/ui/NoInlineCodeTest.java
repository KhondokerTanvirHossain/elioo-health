package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D12: script-src 'self' and style-src 'self' block inline script, a {@code <style>} element, style="", on* handlers
 * and javascript: URLs. A page that relied on one would break silently in the browser and pass every server test, so
 * every page the router serves is read here.
 *
 * <p>Read through {@link Dom}, element by element and attribute by attribute, never by a regex over the page: a
 * {@code >} inside a quoted value ends a regex's tag early and hides every attribute after it, and {@code data-src=}
 * reads as {@code src=}. A page whose markup does not parse cleanly is refused rather than half-read.</p>
 */
class NoInlineCodeTest {

    @Test
    void noPageCarriesInlineScriptStyleOrHandlers() {
        Map<String, String> pages = RenderedPages.all();
        assertThat(pages).as("pages rendered from the router's routes").hasSizeGreaterThan(10);
        List<String> found = new ArrayList<>();
        pages.forEach((where, html) -> {
            Dom dom = Dom.parse(html);
            dom.unclosed.forEach(u -> found.add(where + " markup " + u));
            assertThat(dom.all).as(where + " elements parsed").isNotEmpty();
            for (Dom.Element e : dom.all) {
                found.addAll(inlineCodeIn(where, e));
            }
        });
        assertThat(found).isEmpty();
    }

    /** What CSP would block on one element: each finding names the page, the rule, the tag and the attribute. */
    static List<String> inlineCodeIn(String where, Dom.Element e) {
        List<String> found = new ArrayList<>();
        if (e.tag().equals("script") && !e.attrs().containsKey("src")) {
            found.add(where + " inline script <script> " + e.attrs().keySet());
        }
        if (e.tag().equals("style")) {
            found.add(where + " style element <style>");
        }
        e.attrs().forEach((name, value) -> {
            if (name.equals("style")) {
                found.add(where + " style attribute <" + e.tag() + " style=\"" + value + "\">");
            }
            if (name.startsWith("on")) {
                found.add(where + " handler <" + e.tag() + " " + name + "=\"" + value + "\">");
            }
            // Browsers drop tabs, newlines and leading control characters from a URL before reading its scheme.
            if (value.replaceAll("[\\s\\x00-\\x1f]", "").toLowerCase().startsWith("javascript:")) {
                found.add(where + " javascript: URL <" + e.tag() + " " + name + "=\"" + value + "\">");
            }
        });
        return found;
    }
}
