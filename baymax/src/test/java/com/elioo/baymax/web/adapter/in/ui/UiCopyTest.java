package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The two bundles are one set of strings in two languages: same keys, same placeholders, nothing left behind. */
class UiCopyTest {

    private final UiCopy copy = new UiCopy();

    @Test
    void bothLanguagesCarryExactlyTheSameKeys() {
        assertThat(copy.keys(Lang.EN)).containsExactlyInAnyOrderElementsOf(copy.keys(Lang.BN));
        assertThat(copy.keys(Lang.BN)).isNotEmpty();
    }

    @Test
    void placeholdersMatchAcrossLanguages() {
        Pattern ph = Pattern.compile("\\{\\d}");
        for (String key : copy.keys(Lang.BN)) {
            assertThat(holes(ph, copy.t(Lang.EN, key))).as(key).isEqualTo(holes(ph, copy.t(Lang.BN, key)));
        }
    }

    private static List<String> holes(Pattern ph, String s) {
        Matcher m = ph.matcher(s);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        while (m.find()) {
            out.add(m.group());
        }
        java.util.Collections.sort(out);
        return out;
    }

    @Test
    void placeholdersAreReplacedAndArgumentsEscaped() {
        assertThat(copy.t(Lang.BN, "patients.docs", "<b>")).isEqualTo("নথি: &lt;b&gt;");
        assertThat(copy.t(Lang.EN, "timeline.counts", 1, 2, 3)).isEqualTo("Values 1 · Medicines 2 · Follow-ups 3");
    }

    /**
     * BMX-6b safety constraints on the landing page, as they stand after the PO approved the storytelling design
     * (2026-09-19): no medical claim (cure/treat), nothing about where data is hosted, no blanket "safe/secure".
     * The design deliberately shows the three urgency marks and sample explanations as illustrations of the
     * product, so those words are allowed here; the "doesn't diagnose" line is a disclaimer, not a claim.
     */
    @Test
    void landingCopyMakesNoMedicalOrHostingClaim() {
        for (Lang lang : Lang.values()) {
            for (String key : copy.keys(lang)) {
                if (!key.startsWith("landing.") && !key.equals("app.tagline")) {
                    continue;
                }
                String s = copy.t(lang, key).toLowerCase();
                assertThat(s).as(lang + " " + key)
                        .doesNotContain("hosted").doesNotContain("server").doesNotContain("aws").doesNotContain("bangladesh-hosted")
                        .doesNotContain(" safe").doesNotContain("secure").doesNotContain("নিরাপদ")
                        .doesNotContain("cure").doesNotContain("treat").doesNotContain("সারিয়ে");
            }
        }
    }

    @Test
    void theDisclaimerSaysNotMedicalAdviceInBothLanguages() {
        assertThat(copy.t(Lang.EN, "disclaimer")).contains("not medical advice").contains("doctor");
        assertThat(copy.t(Lang.BN, "disclaimer")).contains("চিকিৎসা পরামর্শ নয়").contains("ডাক্তার");
    }
}
