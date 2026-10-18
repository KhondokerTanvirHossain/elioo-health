package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A3 turns dark mode on, so the wordmark must follow the theme. The raster wordmark's "medi" was fixed navy ink, about
 * 1.66:1 on the dark --c-bg and 1.50:1 on --c-surface: nearly invisible on every top bar in a dark phone. A picture
 * cannot be measured by TokenContrastTest, so the wordmark is drawn as text (s01, s03, kit §2): "Medi" in the theme's
 * ink, which TokenContrastTest measures on every page, and "oo" in --c-brand-green, the logotype it exempts.
 */
class WordmarkTest {

    @Test
    void everyTopBarDrawsTheWordmarkAsTextInTheThemesInk() {
        Map<String, String> pages = RenderedPages.all();
        List<String> failures = new ArrayList<>();
        int bars = 0;
        for (Map.Entry<String, String> page : pages.entrySet()) {
            String html = page.getValue();
            if (html.contains("src=\"/assets/medioo.")) {
                failures.add(page.getKey() + ": a raster wordmark, which cannot follow the theme");
            }
            Dom dom = Dom.parse(html);
            for (Dom.Element top : dom.all) {
                if (!top.classes().contains("top")) {
                    continue;
                }
                bars++;
                List<Dom.Element> marks = dom.all.stream().filter(e -> e.classes().contains("wm-text") && inside(e, top)).toList();
                if (marks.size() != 1) {
                    failures.add(page.getKey() + ": " + marks.size() + " text wordmarks in the top bar");
                    continue;
                }
                Dom.Element mark = marks.get(0);
                String text = mark.children().stream().map(Dom.Element::ownText).reduce("", String::concat);
                String ink = mark.children().isEmpty() ? "-" : TokenContrastTest.colour(mark.children().get(0)).token();
                String oo = mark.children().size() < 2 ? "-" : TokenContrastTest.colour(mark.children().get(1)).token();
                if (!text.equals("Medioo") || !ink.equals("--c-text") || !oo.equals("--c-brand-green")) {
                    failures.add(page.getKey() + ": wordmark \"" + text + "\" in " + ink + " / " + oo);
                }
            }
        }
        assertThat(bars).as("top bars on the rendered pages").isGreaterThan(10);
        assertThat(failures).isEmpty();
    }

    private static boolean inside(Dom.Element e, Dom.Element ancestor) {
        for (Dom.Element x = e.parent(); x != null; x = x.parent()) {
            if (x == ancestor) {
                return true;
            }
        }
        return false;
    }
}
