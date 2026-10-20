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
 * in RenderedPages; each weighed route has a {@code #heaviest} fixture sized from the service's own limits or from the
 * largest labelled documents (PageFixtures). Anything else a page fetches on load fails {@link #unweighed}, and a page's
 * scripts together are at most {@link #SCRIPT_BUDGET}. Bytes are counted uncompressed, as the test client serves them.
 */
class FirstLoadBudgetTest {

    static final int BUDGET = 200 * 1024;
    /** Spec §1 rule 14: JS at most 40 KB (and only on enhanced pages), per page, every script it names summed. */
    static final int SCRIPT_BUDGET = 40 * 1024;
    /**
     * Pages measured over {@link #BUDGET} and put to the PO, by the ending of their key: never a page that fits (an entry
     * whose page comes under the budget fails, so it is removed the day the page is fixed), never more than
     * {@link #KNOWN_OVER_CEILING} while the decision is open, and each must name exactly one rendered page. Found by D1's
     * heaviest fixtures (2026-10-20): s17 draws every value of a paper, about 900 bytes a row, so a lab report of more
     * than about 42 values goes over; the fixture holds 58 (twice the testset's largest, 29). Options for the PO: response
     * compression (the HTML is the only part not already compressed), a lighter row, or s17 paged by section.
     */
    static final Map<String, String> KNOWN_OVER = Map.of(
            "/all #heaviest bn", "PO decision open: a 58-value lab report's document detail (s17) is over 200 KB");
    static final int KNOWN_OVER_CEILING = BUDGET + BUDGET / 10;
    private static final Pattern HTML_ASSET = Pattern.compile("(?:href|src)=\"(/assets/[^\"?#]+)");
    /** Every element that makes the browser fetch something as the page loads, and the attributes that name it. */
    private static final Pattern FETCHING_TAG = Pattern.compile("<(?:img|source|script|link|video|audio|track|iframe|embed|object|input)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FETCHING_ATTR = Pattern.compile("\\s(src|srcset|href|poster|data)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_URL = Pattern.compile("\\sstyle\\s*=\\s*[\"'][^\"']*url\\(", Pattern.CASE_INSENSITIVE);
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
        for (String known : KNOWN_OVER.keySet()) {
            assertThat(pages.keySet().stream().filter(k -> k.endsWith(known)))
                    .as("KNOWN_OVER '" + known + "' names exactly one rendered page").hasSize(1);
        }
        for (Map.Entry<String, String> page : pages.entrySet()) {
            String known = KNOWN_OVER.keySet().stream().filter(page.getKey()::endsWith).findFirst().orElse(null);
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
            assertThat(unweighed(page.getValue(), assets)).as(page.getKey() + ": everything the page fetches on load is an /assets/ file"
                    + " this budget weighs, or a crop").isEmpty();
            int scripts = parts.entrySet().stream().filter(e -> e.getKey().endsWith(".js")).mapToInt(Map.Entry::getValue).sum();
            if (scripts > SCRIPT_BUDGET) {
                over.add("%s carries %,d bytes of script (at most %,d)".formatted(page.getKey(), scripts, SCRIPT_BUDGET));
            }
            int total = parts.values().stream().mapToInt(Integer::intValue).sum();
            totals.add("%,9d  %s%s".formatted(total, page.getKey(), known == null ? "" : "  [KNOWN_OVER: " + KNOWN_OVER.get(known) + "]"));
            if (known != null && total <= BUDGET) {
                over.add("%s is %,d bytes, within the budget: remove it from KNOWN_OVER".formatted(page.getKey(), total));
            } else if (known != null && total > KNOWN_OVER_CEILING) {
                over.add("%s is %,d bytes, past the %,d ceiling while its decision is open: %s".formatted(page.getKey(), total,
                        KNOWN_OVER_CEILING, parts));
            } else if (known == null && total > BUDGET) {
                over.add("%s is %,d bytes: %s".formatted(page.getKey(), total, parts));
            }
            // the attribute only: crops are outside the budget by definition, and a lazy image already in view still loads
            assertThat(page.getValue()).as(page.getKey() + ": every crop <img> carries loading=\"lazy\"")
                    .doesNotContainPattern("<img(?![^>]*loading=\"lazy\")[^>]*src=\"" + CROP.pattern());
        }
        System.out.println("first-load totals (bytes, crops excluded):\n" + String.join("\n", totals));
        assertThat(over).isEmpty();
    }

    /**
     * Every URL the page's fetching elements name — any quoting, srcset candidates included — that is neither an /assets/
     * file in {@code weighed} nor a crop; and any inline style that fetches. What this returns is load the budget missed.
     */
    static List<String> unweighed(String html, Set<String> weighed) {
        List<String> missed = new ArrayList<>();
        FETCHING_TAG.matcher(html).results().map(m -> m.group()).forEach(tag -> FETCHING_ATTR.matcher(tag).results().forEach(a -> {
            String value = a.group(2) != null ? a.group(2) : a.group(3) != null ? a.group(3) : a.group(4);
            List<String> urls = a.group(1).equalsIgnoreCase("srcset")
                    ? java.util.Arrays.stream(value.split(",")).map(c -> c.strip().split("\\s+")[0]).filter(u -> !u.isEmpty()).toList()
                    : List.of(value);
            for (String url : urls) {
                String path = url.replaceAll("[?#].*$", "");
                if (!weighed.contains(path) && !CROP.matcher(url).lookingAt()) {
                    missed.add(a.group(1) + "=" + url);
                }
            }
        }));
        STYLE_URL.matcher(html).results().forEach(m -> missed.add("inline style url(: " + m.group()));
        return missed;
    }
}
