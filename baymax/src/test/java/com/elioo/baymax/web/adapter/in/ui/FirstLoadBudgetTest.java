package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §1 rule 14: a page's first load is at most 200 KB excluding crops — the HTML, the stylesheet, and every asset
 * either names, each fetched through the test client as the browser would (bytes actually served), the fonts at their
 * self-hosted size (the worst case: {@code local()} missed). Pages come from the router; a route without a fixture fails
 * in RenderedPages.
 */
class FirstLoadBudgetTest {

    static final int BUDGET = 200 * 1024;
    private static final Pattern HTML_ASSET = Pattern.compile("(?:href|src)=\"(/assets/[^\"?#]+)");
    private static final Pattern CSS_URL = Pattern.compile("url\\(");
    private static final Pattern CSS_ASSET = Pattern.compile("url\\(\\s*[\"']?(/assets/[^)\"'?#]+)[^)]*\\)");
    private static final Pattern CROP = Pattern.compile("/app/documents/[^/]+/(?:image\\?|page/)");

    @Test
    void everyPagesFirstLoadIsAtMostTwoHundredKilobytesExcludingCrops() {
        WebUiTestKit kit = new WebUiTestKit();
        String css = kit.servedText("/assets/baymax.css");
        int urls = (int) CSS_URL.matcher(css).results().count();
        List<String> cssAssets = CSS_ASSET.matcher(css).results().map(r -> r.group(1)).toList();
        assertThat(cssAssets).as("every url( in the stylesheet is an /assets/ file the budget can weigh").hasSize(urls);
        Map<String, String> pages = RenderedPages.allBangla();
        assertThat(pages).as("pages from the router").hasSizeGreaterThan(15);
        List<String> over = new ArrayList<>();
        List<String> totals = new ArrayList<>();
        for (Map.Entry<String, String> page : pages.entrySet()) {
            Map<String, Integer> parts = new LinkedHashMap<>();
            parts.put("html", page.getValue().getBytes(StandardCharsets.UTF_8).length);
            Set<String> assets = new LinkedHashSet<>(HTML_ASSET.matcher(page.getValue()).results().map(r -> r.group(1)).toList());
            if (assets.contains("/assets/baymax.css")) {
                assets.addAll(cssAssets);
            }
            for (String a : assets) {
                parts.put(a, kit.served(a).length);
            }
            assertThat(assets).as(page.getKey() + " counts the stylesheet and both fonts")
                    .contains("/assets/baymax.css", "/assets/noto-sans-bengali.woff2", "/assets/noto-sans-latin.woff2");
            int total = parts.values().stream().mapToInt(Integer::intValue).sum();
            totals.add("%,9d  %s".formatted(total, page.getKey()));
            if (total > BUDGET) {
                over.add("%s is %,d bytes: %s".formatted(page.getKey(), total, parts));
            }
            assertThat(page.getValue()).as(page.getKey() + ": crops are lazy, never part of the first load")
                    .doesNotContainPattern("<img(?![^>]*loading=\"lazy\")[^>]*src=\"" + CROP.pattern());
        }
        System.out.println("first-load totals (bytes, crops excluded):\n" + String.join("\n", totals));
        assertThat(over).isEmpty();
    }
}
