package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D12: script-src 'self' and style-src 'self' block inline script, a {@code <style>} element, style="", on* handlers
 * and javascript: URLs. A page that relied on one would break silently in the browser and pass every server test, so
 * every page the router serves is read here.
 */
class NoInlineCodeTest {

    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_ELEMENT = Pattern.compile("<style\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_ATTR = Pattern.compile("<[^>]*\\sstyle\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern HANDLER = Pattern.compile("<[^>]*\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_URL = Pattern.compile("<[^>]*=\\s*[\"']?\\s*javascript:", Pattern.CASE_INSENSITIVE);

    @Test
    void noPageCarriesInlineScriptStyleOrHandlers() {
        Map<String, String> pages = RenderedPages.all();
        assertThat(pages).as("pages rendered from the router's routes").hasSizeGreaterThan(10);
        List<String> found = new ArrayList<>();
        pages.forEach((where, html) -> {
            INLINE_SCRIPT.matcher(html).results().forEach(r -> found.add(where + " inline script " + r.group()));
            STYLE_ELEMENT.matcher(html).results().forEach(r -> found.add(where + " style element " + r.group()));
            STYLE_ATTR.matcher(html).results().forEach(r -> found.add(where + " style attribute " + r.group()));
            HANDLER.matcher(html).results().forEach(r -> found.add(where + " handler " + r.group()));
            JS_URL.matcher(html).results().forEach(r -> found.add(where + " javascript: URL " + r.group()));
        });
        assertThat(found).isEmpty();
    }
}
