package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.domain.Urgency;
import com.elioo.baymax.web.domain.GateState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** s10 / F1 / F3: the stored urgency first, in every gate state, at both levels; model wording only when released. */
class ResultSummaryRenderTest {

    private final WebUiTestKit kit = new WebUiTestKit();
    private final UiCopy copy = kit.copy;

    /** One value on the paper, so the items list is on the page and the order checks have it to order. */
    private static final List<Map<String, Object>> VALUES =
            List.of(ResultFixtures.value("Potassium", "6.8", "mmol/L", "3.5", "5.1", null));

    private String body(Lang lang, Urgency level, GateState gate) {
        return kit.documents.summaryBody(lang, ResultFixtures.result(ResultFixtures.lab(VALUES), level,
                level == Urgency.NOW ? List.of("value_critical:potassium|stopgap") : List.of("diagnosis_present"),
                gate, gate == GateState.RELEASED ? "রিপোর্টটি পেয়েছি।" : null, ResultFixtures.BD));
    }

    static Stream<Arguments> everyLevelInEveryGateState() {
        return Stream.of(Urgency.NOW, Urgency.THIS_WEEK)
                .flatMap(l -> Arrays.stream(GateState.values()).map(g -> arguments(l, g)));
    }

    /** D5: whatever the review state, the stored line is on the page, verbatim, and is the first section of main. */
    @ParameterizedTest(name = "{0} in {1}")
    @MethodSource("everyLevelInEveryGateState")
    void theStoredUrgencyLeadsThePageInEveryGateState(Urgency level, GateState gate) {
        String html = body(Lang.BN, level, gate);
        String banner = ResultFixtures.section(html, "urgency");
        assertThat(banner).contains(copy.urgency(Lang.BN, level, false)).doesNotContain("<button").doesNotContain("dismiss");
        assertThat(banner).contains(level == Urgency.NOW ? "class=\"banner now\"" : "class=\"banner soon\"");
        int at = html.indexOf("id=\"urgency\"");
        Matcher other = Pattern.compile("<section(?![^>]*id=\"urgency\")").matcher(html);
        int others = 0;
        while (other.find()) {
            others++;
            assertThat(other.start()).as("a section before the banner").isGreaterThan(at);
        }
        if (gate != GateState.NONE) {
            assertThat(others).as("the gate line is a section after the banner, so the order check has something to order").isPositive();
        }
        // the items are headings and divs, not sections: they come after the banner too, and they are on the page
        int items = html.indexOf("<h2>" + copy.raw(Lang.BN, "doc.values") + "</h2>");
        assertThat(items).as("the values heading").isNotNegative();
        assertThat(items).as("the items list before the banner").isGreaterThan(at);
        assertThat(html.indexOf("6.8")).as("the value before the banner").isGreaterThan(at);
    }

    /** s10/s11 and kit §5: THIS_WEEK carries the calendar, NOW the octagon, both aria-hidden beside the verbatim line. */
    @ParameterizedTest
    @EnumSource(value = Urgency.class, names = {"NOW", "THIS_WEEK"})
    void theBannerCarriesItsLevelsIconHiddenFromScreenReaders(Urgency level) {
        String banner = ResultFixtures.section(body(Lang.BN, level, GateState.RELEASED), "urgency");
        String mine = level == Urgency.NOW ? "octagon" : "calendar";
        String theirs = level == Urgency.NOW ? "calendar" : "octagon";
        assertThat(banner).contains(Icons.svg(mine, level == Urgency.NOW ? 28 : 26)).contains("aria-hidden=\"true\"");
        assertThat(banner).doesNotContain(Icons.svg(theirs, 26)).doesNotContain(Icons.svg(theirs, 28));
    }

    @ParameterizedTest
    @EnumSource(Lang.class)
    void theBannerIsTheBundleLineInEitherLanguage(Lang lang) {
        assertThat(ResultFixtures.section(body(lang, Urgency.THIS_WEEK, GateState.RELEASED), "urgency"))
                .contains(copy.urgency(lang, Urgency.THIS_WEEK, false));
    }

    /** D27, the other direction: a routine paper carries none of the urgency lines anywhere and no status word; it is "filed". */
    static Stream<Arguments> everyLanguageInEveryGateState() {
        return Arrays.stream(Lang.values()).flatMap(l -> Arrays.stream(GateState.values()).map(g -> arguments(l, g)));
    }

    /** Every urgency.* line of this language's message bundle, derived from the file and guarded against emptiness. */
    private static List<Map.Entry<String, String>> urgencyLines(Lang lang) throws IOException {
        List<Map.Entry<String, String>> lines = BundleFiles.messages(lang).entrySet().stream()
                .filter(e -> e.getKey().startsWith("urgency.")).toList();
        assertThat(lines).as("urgency lines in messages_" + lang).hasSizeGreaterThanOrEqualTo(2);
        return lines;
    }

    @ParameterizedTest(name = "{0} in {1}")
    @MethodSource("everyLanguageInEveryGateState")
    void aRoutinePaperCarriesNoUrgencyLineAndSaysOnlyThatItWasFiled(Lang lang, GateState gate) throws IOException {
        String html = body(lang, Urgency.ROUTINE, gate);
        urgencyLines(lang).forEach(e -> assertThat(html).as(e.getKey()).doesNotContain(Html.esc(e.getValue())));
        assertThat(html).doesNotContain("id=\"urgency\"").doesNotContain("class=\"banner");
        assertThat(html).contains(copy.t(lang, "result.filed"));
        if (lang == Lang.BN) {
            for (String word : List.of("স্বাভাবিক", "চিন্তার কিছু নেই", "সব ঠিক")) {
                assertThat(html).as(word).doesNotContain(word);
            }
        }
    }

    /** A paper whose level is not known (DONE but never assessed) says neither an urgency nor that it was filed. */
    @ParameterizedTest
    @EnumSource(Lang.class)
    void anUnassessedPaperClaimsNeitherAnUrgencyNorThatItWasFiled(Lang lang) throws IOException {
        String html = body(lang, null, GateState.NONE);
        urgencyLines(lang).forEach(e -> assertThat(html).as(e.getKey()).doesNotContain(Html.esc(e.getValue())));
        assertThat(html).doesNotContain("id=\"urgency\"").doesNotContain("class=\"banner")
                .doesNotContain(copy.t(lang, "result.filed"));
    }

    /** F3: pending says a person is checking (with the configured hours); the model's text is not on the page. */
    @Test
    void pendingSaysAPersonIsCheckingAndShowsNoModelText() {
        String html = body(Lang.BN, Urgency.THIS_WEEK, GateState.PENDING);
        assertThat(ResultFixtures.section(html, "gate-pending")).contains(copy.t(Lang.BN, "result.gate.pending", 2))
                .contains("২ ঘণ্টার মধ্যে").contains(copy.t(Lang.BN, "result.review.chip"));
        // no model text here is the ResultView constructor's rule (ResultServiceTest): the view cannot carry it, so no
        // assertion on this page could fail over it; what the renderer decides is which block it draws
        assertThat(html).doesNotContain("id=\"says\"").doesNotContain("id=\"gate-withheld\"");
    }

    /** F3: a withheld explanation is never described as being checked — that would be a promise nobody keeps. */
    @ParameterizedTest
    @EnumSource(value = GateState.class, names = {"REJECTED", "FAILED_SAFETY"})
    void aWithheldExplanationSaysSoAndNeverThatItIsBeingChecked(GateState gate) {
        String html = body(Lang.BN, Urgency.NOW, gate);
        assertThat(ResultFixtures.section(html, "gate-withheld")).contains(copy.t(Lang.BN, "result.gate.withheld"));
        assertThat(html).doesNotContain(copy.t(Lang.BN, "result.review.chip")).doesNotContain("id=\"says\"")
                .doesNotContain("id=\"gate-pending\"");
    }

    /** Principle 6: the released wording is labelled as Medioo's, and carries no crop (a crop means "the paper says"). */
    @Test
    void theReleasedWordingIsLabelledAsMediooSWordsAndCarriesNoCrop() {
        String html = body(Lang.BN, Urgency.THIS_WEEK, GateState.RELEASED);
        String says = ResultFixtures.section(html, "says");
        assertThat(says).contains(copy.t(Lang.BN, "result.says.label")).contains("রিপোর্টটি পেয়েছি।").doesNotContain("<img");
        assertThat(html).doesNotContain("id=\"gate-pending\"").doesNotContain("id=\"gate-withheld\"");
    }

    /** Everything that came from the paper, the model or the family is escaped on the served summary. */
    @Test
    void everythingOnTheSummaryIsEscaped() {
        PatientProfile named = new PatientProfile(ResultFixtures.MA.id(), ResultFixtures.MA.familyId(), "<b>Ma</b>", 64,
                PatientProfile.Sex.FEMALE, List.of(), ResultFixtures.NOW, ResultFixtures.NOW);
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab("<img src=x onerror=alert(1)>", VALUES),
                named, Urgency.THIS_WEEK, List.of(), GateState.RELEASED, "<script>alert(1)</script>", ResultFixtures.BD));
        assertThat(html).doesNotContain("<script>").doesNotContain("<img src=x").doesNotContain("<b>Ma</b>");
        assertThat(ResultFixtures.section(html, "says")).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;").contains("&lt;b&gt;Ma&lt;/b&gt;");
    }

    /** The patient is named on every result, before F8 grammar exists. */
    @Test
    void thePatientIsNamedInTheHeader() {
        assertThat(body(Lang.BN, Urgency.ROUTINE, GateState.NONE)).contains("<span class=\"pchip\">Ma · ৬৪ বছর</span>");
    }

    // ---- F2 interim (B1): the stored verdict on the value line; B2's ValueRows replaces these two ----

    /** Interim F2 line: a stored INSIDE the code cannot prove reads "কাগজে মিলিয়ে দেখুন", never "সীমার মধ্যে". */
    @Test
    void aStoredInsideCodeCannotProveReadsCheckThePaper() {
        String row = rowOf(kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(
                ResultFixtures.value("K", "6.4", "mmol/L", "3.5", "5.1", "inside"))), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD)), "K");
        assertThat(row).contains(copy.t(Lang.BN, "value.chip.unknown")).doesNotContain(copy.t(Lang.BN, "value.chip.inside"));
    }

    @Test
    void aProvenInsideReadsInside() {
        String row = rowOf(kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(
                ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", "inside"))), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD)), "Creatinine");
        assertThat(row).contains(copy.t(Lang.BN, "value.chip.inside"));
    }

    /** A value with no stored verdict yet is UNKNOWN on the page, never INSIDE, however inside its numbers look. */
    @Test
    void aValueWithNoStoredVerdictReadsCheckThePaper() {
        String row = rowOf(kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(
                ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", null))), Urgency.ROUTINE, List.of(), GateState.NONE, null, ResultFixtures.BD)), "Creatinine");
        assertThat(row).contains(copy.t(Lang.BN, "value.chip.unknown")).doesNotContain(copy.t(Lang.BN, "value.chip.inside"));
    }

    /** Outside and critical keep their side, from the stored verdict (never re-judged on the page). */
    @Test
    void aStoredOutsideOrCriticalVerdictShowsItsSide() {
        Map<String, Object> low = ResultFixtures.value("Hb", "5.0", "g/dL", "12.0", "16.0", "critical");
        low.put("verdict_side", "low");
        Map<String, Object> high = ResultFixtures.value("HbA1c", "6.5", "%", "4.0", "5.6", "outside");
        high.put("verdict_side", "high");
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(low, high)),
                Urgency.NOW, List.of("value_critical:hb|stopgap"), GateState.NONE, null, ResultFixtures.BD));
        assertThat(rowOf(html, "Hb")).contains(copy.t(Lang.BN, "value.chip.critical.low"));
        assertThat(rowOf(html, "HbA1c")).contains(copy.t(Lang.BN, "value.chip.outside.high"));
    }

    /** A stored outside or critical with no side names no direction: the page says UNKNOWN, never a guessed "▲". */
    @Test
    void aStoredOutsideOrCriticalWithNoSideReadsCheckThePaper() {
        Map<String, Object> outside = ResultFixtures.value("HbA1c", "6.5", "%", "4.0", "5.6", "outside");
        Map<String, Object> critical = ResultFixtures.value("Hb", "5.0", "g/dL", "12.0", "16.0", "critical");
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(outside, critical)),
                Urgency.NOW, List.of("value_critical:hb|stopgap"), GateState.NONE, null, ResultFixtures.BD));
        for (String name : List.of("HbA1c", "Hb")) {
            assertThat(rowOf(html, name)).as(name).contains(copy.t(Lang.BN, "value.chip.unknown"))
                    .doesNotContain(copy.t(Lang.BN, "value.chip.outside.high"))
                    .doesNotContain(copy.t(Lang.BN, "value.chip.critical.high"));
        }
    }

    /**
     * The page reads what the producer writes, not a hand-built map: an ObservationEntity with stored verdict columns,
     * through the real toView(), onto the page. A key renamed on either side turns every row into "check the paper"
     * and every critical row silent, which no hand-built fixture can see.
     */
    @Test
    void aStoredVerdictReachesThePageThroughTheRealObservationView() {
        com.elioo.baymax.extraction.adapter.out.persistence.ObservationEntity hb = new com.elioo.baymax.extraction.adapter.out.persistence.ObservationEntity();
        hb.setId(java.util.UUID.randomUUID());
        hb.setName("Hb");
        hb.setCanonicalName("hb");
        hb.setValue("5.0");
        hb.setUnit("g/dL");
        hb.setRefLow("12.0");
        hb.setRefHigh("16.0");
        hb.setCropKey("f/p/d/crop-Hb.jpg");
        hb.setVerdict("critical");
        hb.setVerdictLevel("now");
        hb.setVerdictSide("low");
        hb.setVerdictReason("value_critical:hb|stopgap");
        String html = kit.documents.summaryBody(Lang.BN, ResultFixtures.result(ResultFixtures.lab(List.of(hb.toView())),
                Urgency.NOW, List.of("value_critical:hb|stopgap"), GateState.NONE, null, ResultFixtures.BD));
        assertThat(rowOf(html, "Hb")).contains(copy.t(Lang.BN, "value.chip.critical.low"))
                .doesNotContain(copy.t(Lang.BN, "value.chip.unknown"));
    }

    /** The {@code <div class="item">} that holds this value's name: assertions read one row, never the page. */
    private static String rowOf(String html, String name) {
        Matcher m = Pattern.compile("<div class=\"item\"><div class=\"text\">" + Pattern.quote(Html.esc(name)) + ":.*?</div>").matcher(html);
        assertThat(m.find()).as("the row for " + name).isTrue();
        return m.group();
    }
}
