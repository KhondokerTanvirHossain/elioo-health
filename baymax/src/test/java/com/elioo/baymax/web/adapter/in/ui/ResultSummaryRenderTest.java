package com.elioo.baymax.web.adapter.in.ui;

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

    private String body(Lang lang, Urgency level, GateState gate) {
        return kit.documents.summaryBody(lang, ResultFixtures.result(ResultFixtures.lab(List.of()), level,
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
    }

    @ParameterizedTest
    @EnumSource(Lang.class)
    void theBannerIsTheBundleLineInEitherLanguage(Lang lang) {
        assertThat(ResultFixtures.section(body(lang, Urgency.THIS_WEEK, GateState.RELEASED), "urgency"))
                .contains(copy.urgency(lang, Urgency.THIS_WEEK, false));
    }

    /** D27, the other direction: a routine paper carries none of the urgency lines anywhere and no status word; it is "filed". */
    @ParameterizedTest
    @EnumSource(GateState.class)
    void aRoutinePaperCarriesNoUrgencyLineAndSaysOnlyThatItWasFiled(GateState gate) throws IOException {
        String html = body(Lang.BN, Urgency.ROUTINE, gate);
        List<Map.Entry<String, String>> lines = BundleFiles.messages(Lang.BN).entrySet().stream()
                .filter(e -> e.getKey().startsWith("urgency.")).toList();
        assertThat(lines).as("urgency lines in messages_bn").hasSizeGreaterThanOrEqualTo(2);
        lines.forEach(e -> assertThat(html).as(e.getKey()).doesNotContain(Html.esc(e.getValue())));
        assertThat(html).doesNotContain("id=\"urgency\"").doesNotContain("class=\"banner");
        assertThat(html).contains(copy.t(Lang.BN, "result.filed"));
        for (String word : List.of("স্বাভাবিক", "চিন্তার কিছু নেই", "সব ঠিক")) {
            assertThat(html).as(word).doesNotContain(word);
        }
    }

    /** A paper whose level is not known (DONE but never assessed) says neither an urgency nor that it was filed. */
    @Test
    void anUnassessedPaperClaimsNeitherAnUrgencyNorThatItWasFiled() {
        String html = body(Lang.BN, null, GateState.NONE);
        assertThat(html).doesNotContain("id=\"urgency\"").doesNotContain(copy.t(Lang.BN, "result.filed"));
    }

    /** F3: pending says a person is checking (with the configured hours); the model's text is not on the page. */
    @Test
    void pendingSaysAPersonIsCheckingAndShowsNoModelText() {
        String html = body(Lang.BN, Urgency.THIS_WEEK, GateState.PENDING);
        assertThat(ResultFixtures.section(html, "gate-pending")).contains(copy.t(Lang.BN, "result.gate.pending", 2))
                .contains("২ ঘণ্টার মধ্যে").contains(copy.t(Lang.BN, "result.review.chip"));
        assertThat(html).doesNotContain("id=\"says\"").doesNotContain("রিপোর্টটি পেয়েছি।").doesNotContain("id=\"gate-withheld\"");
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

    /** The patient is named on every result, before F8 grammar exists. */
    @Test
    void thePatientIsNamedInTheHeader() {
        assertThat(body(Lang.BN, Urgency.ROUTINE, GateState.NONE)).contains("<span class=\"pchip\">Ma · ৬৪ বছর</span>");
    }
}
