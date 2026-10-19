package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Principle 6: "কাগজে লেখা" and "Medioo-র কথা" never look alike — on every page, and in the CSS that draws them. */
class PaperIsNeverMediooTest {

    private static final Pattern PAPER = Pattern.compile("<(figure|div) class=\"paper\"[^>]*>(.*?)</\\1>", Pattern.DOTALL);
    private static final Pattern MEDIOO = Pattern.compile("<(section|p|div) [^>]*(?:class=\"bubble\"|id=\"says\")[^>]*>(.*?)</\\1>", Pattern.DOTALL);

    /** Derived over every page the router serves (RenderedPages), guarded against finding no paper block at all. */
    @Test
    void everyPaperBlockCarriesItsLabelAndItsCropAndNoMediooBlockCarriesEither() {
        UiCopy copy = new UiCopy();
        String label = copy.t(Lang.BN, "paper.label");
        List<String> wrong = new ArrayList<>();
        int papers = 0;
        int medioo = 0;
        for (Map.Entry<String, String> page : RenderedPages.allBangla().entrySet()) {
            for (Matcher m = PAPER.matcher(page.getValue()); m.find(); ) {
                papers++;
                if (!m.group(2).contains(label) || !m.group(2).contains("<img class=\"crop")) {
                    wrong.add(page.getKey() + " a paper block without its label or crop");
                }
            }
            for (Matcher m = MEDIOO.matcher(page.getValue()); m.find(); ) {
                medioo++;
                if (m.group(2).contains("<img class=\"crop") || m.group(2).contains(label)) {
                    wrong.add(page.getKey() + " Medioo's words carry a crop or the paper label");
                }
            }
        }
        assertThat(papers).as("paper blocks found on the fixture pages").isPositive();
        assertThat(medioo).as("Medioo blocks found on the fixture pages").isPositive();
        assertThat(wrong).isEmpty();
    }

    @Test
    void thePaperAndMediooStylesDifferInBackgroundAndTypeface() {
        String css = Tokens.rules(new WebUiTestKit().servedText("/assets/baymax.css"));
        String paper = rule(css, ".paper"), says = rule(css, ".says"), bubble = rule(css, ".bubble");
        assertThat(prop(paper, "background")).as("the paper block paints its own background").isNotEqualTo("(inherited)");
        for (String medioo : List.of(says, bubble)) {
            assertThat(prop(medioo, "background")).isNotEqualTo(prop(paper, "background"));
            assertThat(prop(medioo, "font-family")).isNotEqualTo(prop(paper, "font-family"));
        }
    }

    /** The one rule whose selector is exactly this one; a second such rule would make "the" rule ambiguous. */
    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(selector) + "\\{([^}]*)}").matcher(css);
        assertThat(m.find()).as("a rule for " + selector).isTrue();
        String body = m.group(1);
        assertThat(m.find()).as("exactly one rule for " + selector).isFalse();
        return body;
    }

    private static String prop(String rule, String name) {
        Matcher m = Pattern.compile("(?<![-a-z])" + name + "\\s*:\\s*([^;]+)").matcher(rule);
        return m.find() ? m.group(1).trim() : "(inherited)";
    }
}
