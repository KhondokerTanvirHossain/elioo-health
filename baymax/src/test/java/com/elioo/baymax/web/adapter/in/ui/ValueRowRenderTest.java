package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.domain.ValueVerdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ValueRow (spec §9, F2): a row's look comes only from its stored verdict; "inside" is never shown unproven. */
class ValueRowRenderTest {

    private final UiCopy copy = new UiCopy();

    /** Assert the class AND the chip per kind, not that "a colour exists". */
    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "null", value = {"INSIDE,5.1,null,value.chip.inside", "UNKNOWN,Trace,null,value.chip.unknown",
            "OUTSIDE,8.4,high,value.chip.outside.high", "CRITICAL,13.0,high,value.chip.critical.high",
            "OUTSIDE,3.1,low,value.chip.outside.low", "CRITICAL,1.0,low,value.chip.critical.low"})
    void aRowsClassAndChipComeFromItsStoredVerdict(ValueVerdict.Kind kind, String value, String side, String chipKey) {
        String cls = kind.name().toLowerCase(Locale.ROOT);
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("HbA1c", value, "%", "4.0", "6.0", cls, side));
        assertThat(row).startsWith("<a class=\"vrow " + cls + "\"");
        assertThat(row).contains("<span class=\"chip " + cls + "\">" + copy.t(Lang.BN, chipKey) + "</span>");
    }

    /**
     * Derived over the enums: a kind or a side added later has a chip in both languages or this fails. OUTSIDE and
     * CRITICAL are rendered only with a side (kindOf makes a sideless one UNKNOWN), so they are checked with each side.
     */
    @Test
    void everyVerdictKindHasARendering() {
        assertThat(ValueVerdict.Kind.values()).isNotEmpty().allSatisfy(k -> {
            boolean sided = k == ValueVerdict.Kind.OUTSIDE || k == ValueVerdict.Kind.CRITICAL;
            List<String> sides = sided ? Arrays.stream(ValueVerdict.Side.values()).map(ValueVerdict.Side::dbValue).toList()
                    : Collections.singletonList(null);
            assertThat(sides).isNotEmpty();
            for (String side : sides) {
                String key = ValueRows.chipKey(k, side);
                assertThat(key).isNotBlank();
                for (Lang lang : Lang.values()) {
                    assertThat(copy.t(lang, key)).as(lang + " " + key).isNotBlank();
                }
            }
        });
    }

    /** F2(b) on the page: every shape the code cannot place is UNKNOWN — no bar, no pin, no teal, never "normal". */
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {"> 89|60|120", "130 / 80|90|140", "1,23|0.5|1.5",
            "Positive|null|null", "250|null|<100", "250|null|null"})
    void aValueTheCodeCouldNotPlaceHasNoBarAndNoTeal(String value, String low, String high) {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("LDL", value, "mg/dL", low, high, "unknown"));
        assertThat(row).doesNotContain("<svg").doesNotContain("inside").doesNotContain("স্বাভাবিক")
                .contains(copy.t(Lang.BN, "value.chip.unknown"));
    }

    /**
     * The same shapes with a stored INSIDE: the demotion must not depend on the value parsing. A stored "inside" the code
     * cannot place (a comparator, a pair, a comma decimal, a word, a one-sided or missing range) renders UNKNOWN.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {"> 89|60|120", "130 / 80|90|140", "1,23|0.5|1.5",
            "Positive|null|null", "250|null|<100", "250|null|null"})
    void aStoredInsideOnAShapeTheCodeCannotPlaceRendersUnknown(String value, String low, String high) {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("LDL", value, "mg/dL", low, high, "inside"));
        assertThat(row).startsWith("<a class=\"vrow unknown\"").doesNotContain("chip inside").doesNotContain("<svg")
                .contains("<span class=\"chip unknown\">" + copy.t(Lang.BN, "value.chip.unknown") + "</span>");
    }

    /** A row stored before F2 (verdict NULL) is UNKNOWN — the safe direction — never INSIDE. */
    @Test
    void aRowWithNoStoredVerdictIsUnknown() {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", null));
        assertThat(row).startsWith("<a class=\"vrow unknown\"").doesNotContain("<svg");
    }

    /** A verdict word the page does not know (a later migration, a typo) is UNKNOWN, never a guess. */
    @Test
    void anUnrecognisedStoredVerdictIsUnknown() {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", "normal"));
        assertThat(row).startsWith("<a class=\"vrow unknown\"").doesNotContain("<svg");
    }

    /** The hard rule, both directions: a stored INSIDE the code cannot prove renders UNKNOWN — no bar, no teal, no "inside". */
    @Test
    void aStoredInsideCodeCannotProveRendersUnknown() {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("K", "6.4", "mmol/L", "3.5", "5.1", "inside"));
        assertThat(row).startsWith("<a class=\"vrow unknown\"").contains("<span class=\"chip unknown\">")
                .doesNotContain("<svg").doesNotContain(copy.t(Lang.BN, "value.chip.inside"));
    }

    @Test
    void aProvenInsideRendersInside() {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("Creatinine", "1.0", "mg/dL", "0.7", "1.3", "inside"));
        assertThat(row).startsWith("<a class=\"vrow inside\"").contains("<span class=\"chip inside\">" + copy.t(Lang.BN, "value.chip.inside") + "</span>")
                .contains("<svg class=\"bar\"").contains("<circle class=\"pin inside\" cx=\"50.0\"");
    }

    /** The side is the stored verdict's (never re-judged on the page), even where the bar cannot be drawn. */
    @Test
    void theStoredSideIsTheChipsSideEvenWithoutABar() {
        Map<String, Object> v = ResultFixtures.value("Hb", "5.0", "g/dL", "12.0", null, "critical");
        v.put("verdict_side", "low");
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, v);
        assertThat(row).startsWith("<a class=\"vrow critical\"").contains(copy.t(Lang.BN, "value.chip.critical.low")).doesNotContain("<svg");
    }

    /**
     * B1's rule (V19 observation_verdict_side_matches_kind): a stored OUTSIDE or CRITICAL with no side is a row the
     * database cannot hold, and the page never guesses its direction. It renders UNKNOWN. The dangerous cases are the
     * ones with a two-sided range, where a pin exists that could have supplied a side; the "> 50" case has none.
     */
    @ParameterizedTest(name = "{0} {1} in {2}–{3}")
    @CsvSource({"outside,8.4,4.0,6.0", "outside,3.1,4.0,6.0", "critical,13.0,4.0,6.0", "critical,1.0,4.0,6.0",
            "outside,> 50,0,20"})
    void anOutsideOrCriticalWithNoStoredSideIsUnknownNeverAGuessedDirection(String verdict, String value, String low, String high) {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("ESR", value, "mm/hr", low, high, verdict));
        assertThat(row).startsWith("<a class=\"vrow unknown\"")
                .contains("<span class=\"chip unknown\">" + copy.t(Lang.BN, "value.chip.unknown") + "</span>")
                .doesNotContain("▲").doesNotContain("▼").doesNotContain("<svg");
    }

    /** The same rule against a side the page does not know (a typo, a later migration): no direction, so UNKNOWN. */
    @Test
    void anUnrecognisedStoredSideIsUnknown() {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("HbA1c", "8.4", "%", "4.0", "6.0", "outside", "up"));
        assertThat(row).startsWith("<a class=\"vrow unknown\"").doesNotContain("<svg").doesNotContain("▲");
    }

    /** A pin that would contradict the verdict (an OUTSIDE inside the printed band) is not drawn: the bar tells no other story. */
    @Test
    void aBarThatWouldContradictTheVerdictIsNotDrawn() {
        Map<String, Object> v = ResultFixtures.value("TSH", "5.0", "mIU/L", "4.0", "6.0", "outside");
        v.put("verdict_side", "high");
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, v);
        assertThat(row).startsWith("<a class=\"vrow outside\"").contains(copy.t(Lang.BN, "value.chip.outside.high")).doesNotContain("<svg");
    }

    /**
     * The bar never tells a different story from the chip: a stored side the pin disagrees with (the verdict says above,
     * the paper's numbers put the pin below) draws no bar, and the chip keeps the stored side — the page never re-judges.
     */
    @Test
    void aBarOnTheOtherSideFromTheStoredVerdictIsNotDrawn() {
        Map<String, Object> v = ResultFixtures.value("Hb", "9.0", "g/dL", "12.0", "15.5", "outside");
        v.put("verdict_side", "high");
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, v);
        assertThat(row).startsWith("<a class=\"vrow outside\"")
                .contains("<span class=\"chip outside\">" + copy.t(Lang.BN, "value.chip.outside.high") + "</span>")
                .doesNotContain("<svg");
    }

    /** The other direction: a stored side the pin agrees with draws the bar, the pin beyond the band on that side. */
    @Test
    void aBarOnTheStoredVerdictsSideIsDrawn() {
        Map<String, Object> v = ResultFixtures.value("Hb", "9.0", "g/dL", "12.0", "15.5", "outside");
        v.put("verdict_side", "low");
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, v);
        assertThat(row).contains("<span class=\"chip outside\">" + copy.t(Lang.BN, "value.chip.outside.low") + "</span>")
                .contains("<svg class=\"bar\"").contains("<circle class=\"pin outside\" cx=\"2.0\"");
    }

    /** Values keep the printed digits; the range text is the paper's text; the crop is the stored crop_key (DR-12). */
    @Test
    void theRowShowsThePaperAsPrintedBesideItsStoredCrop() {
        Map<String, Object> v = ResultFixtures.value("Creatinine", "1.8", "mg/dL", "0.7", "1.3", "outside", "high");
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, v);
        assertThat(row).contains("<span class=\"val\">1.8</span>").contains("<span class=\"range\">রিপোর্টে লেখা সীমা 0.7–1.3</span>")
                .contains("src=\"" + PageChrome.imageHref(ResultFixtures.DOC, (String) v.get("crop_key")) + "\"");
        assertThat(row).doesNotContain("১.৮");
    }

    /** No number without its proof: a value map with no crop fails the page loudly rather than show a bare number. */
    @Test
    void aValueWithoutItsCropIsNeverShown() {
        Map<String, Object> v = ResultFixtures.value("Creatinine", "1.8", "mg/dL", "0.7", "1.3", "outside", "high");
        v.remove("crop_key");
        assertThatThrownBy(() -> ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, v))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("without its crop");
    }

    @Test
    void everythingOnARowIsEscaped() {
        Map<String, Object> v = ResultFixtures.value("<b>x</b>", "<i>1</i>", "\"u\"", "0.7", "1.3", "unknown");
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, v);
        assertThat(row).doesNotContain("<b>").doesNotContain("<i>").contains("&lt;b&gt;x&lt;/b&gt;").contains("&lt;i&gt;1&lt;/i&gt;");
    }
}
