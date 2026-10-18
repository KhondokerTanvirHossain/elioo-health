package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /** A key defined twice — in one file or across parts — is a defect Properties hides by keeping the last one. */
    @Test
    void noKeyIsDefinedTwiceInAnyPartOrAcrossParts() throws IOException {
        Map<String, List<String>> where = new TreeMap<>();
        int files = 0;
        for (Lang lang : Lang.values()) {
            for (String part : UiCopy.PARTS) {
                files++;
                BundleFiles.keys(part, lang).forEach((key, line) ->
                        where.computeIfAbsent(lang.code + ":" + key, k -> new ArrayList<>()).add(part + "_" + lang.code + ":" + line));
                BundleFiles.duplicatesWithin(part, lang).forEach(d -> where.computeIfAbsent(lang.code + ":" + d, k -> new ArrayList<>()).add(part + " (twice)"));
            }
        }
        assertThat(files).isEqualTo(UiCopy.PARTS.size() * Lang.values().length);
        assertThat(where).as("keys read").hasSizeGreaterThan(300);   // bn + en
        assertThat(where.entrySet().stream().filter(e -> e.getValue().size() > 1).map(e -> e.getKey() + " at " + e.getValue()).toList())
                .isEmpty();
    }

    /** Every key exists in both languages, across all parts, with the same placeholders. */
    @Test
    void everyKeyExistsInBothLanguagesWithTheSamePlaceholders() {
        assertThat(copy.keys(Lang.BN)).hasSizeGreaterThan(150).isEqualTo(copy.keys(Lang.EN));
        Pattern slot = Pattern.compile("\\{\\d+}");
        for (String key : copy.keys(Lang.BN)) {
            assertThat(slot.matcher(copy.raw(Lang.EN, key)).results().map(MatchResult::group).sorted().toList()).as(key)
                    .isEqualTo(slot.matcher(copy.raw(Lang.BN, key)).results().map(MatchResult::group).sorted().toList());
        }
    }

    /** A bundle file on the classpath that UiCopy does not load would be copy that silently never reaches a page. */
    @Test
    void everyBundlePartOnDiskIsLoaded() throws IOException {
        Set<String> onDisk;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/baymax"))) {
            onDisk = files.map(p -> p.getFileName().toString()).filter(n -> BundleFiles.PART_FILE.matcher(n).matches())
                    .map(n -> n.substring(0, n.lastIndexOf('_'))).collect(Collectors.toCollection(TreeSet::new));
        }
        assertThat(onDisk).hasSizeGreaterThanOrEqualTo(7).isEqualTo(new TreeSet<>(UiCopy.PARTS));
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

    /** Rule 13, both directions: a count is Bangla on a Bangla page; text (a value from the paper) never is. */
    @Test
    void aNumberArgumentIsBanglaOnABanglaPageAndTextIsNeverConverted() {
        assertThat(copy.t(Lang.BN, "patients.years", 60)).isEqualTo("৬০ বছর");
        assertThat(copy.t(Lang.BN, "patients.docs", 10L)).isEqualTo("নথি: ১০");
        assertThat(copy.t(Lang.BN, "patients.docs", "8.2")).isEqualTo("নথি: 8.2");
        assertThat(copy.t(Lang.EN, "patients.years", 60)).isEqualTo("60 years");
        assertThat(copy.date(Lang.BN, java.time.LocalDate.parse("2026-01-05"))).isEqualTo("৫ জানুয়ারি ২০২৬");
        assertThat(copy.date(Lang.EN, java.time.LocalDate.parse("2026-01-05"))).isEqualTo("2026-01-05");
        assertThat(copy.date(Lang.BN, "<b>")).isEqualTo("&lt;b&gt;");   // not a date: shown as written, escaped
    }
}
