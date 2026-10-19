package com.elioo.baymax.web.adapter.in.ui;

import com.elioo.baymax.outbound.domain.ValueVerdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ValueRow (spec §9, F2): a row's look comes only from its stored verdict; "inside" is never shown unproven. */
class ValueRowRenderTest {

    private final UiCopy copy = new UiCopy();

    /** Assert the class AND the chip per kind, not that "a colour exists". */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"INSIDE,5.1,value.chip.inside", "UNKNOWN,Trace,value.chip.unknown",
            "OUTSIDE,8.4,value.chip.outside.high", "CRITICAL,13.0,value.chip.critical.high"})
    void aRowsClassAndChipComeFromItsStoredVerdict(ValueVerdict.Kind kind, String value, String chipKey) {
        String cls = kind.name().toLowerCase(Locale.ROOT);
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("HbA1c", value, "%", "4.0", "6.0", cls));
        assertThat(row).startsWith("<a class=\"vrow " + cls + "\"");
        assertThat(row).contains("<span class=\"chip " + cls + "\">" + copy.t(Lang.BN, chipKey) + "</span>");
    }

    /** Derived over the enum: a kind added later has a chip in both languages or this fails. */
    @Test
    void everyVerdictKindHasARendering() {
        assertThat(ValueVerdict.Kind.values()).isNotEmpty().allSatisfy(k -> {
            String key = ValueRows.chipKey(k, null, OptionalDouble.empty());
            assertThat(key).isNotBlank();
            for (Lang lang : Lang.values()) {
                assertThat(copy.t(lang, key)).as(lang + " " + key).isNotBlank();
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

    /** No stored side and no bar to read one from: the chip names the kind and claims no direction. */
    @Test
    void anOutsideRowWithNoSideAndNoBarClaimsNoDirection() {
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, ResultFixtures.value("ESR", "> 50", "mm/hr", "0", "20", "outside"));
        assertThat(row).contains("<span class=\"chip outside\">" + copy.t(Lang.BN, "value.chip.outside") + "</span>")
                .doesNotContain("▲").doesNotContain("▼").doesNotContain("<svg");
    }

    /** A pin that would contradict the verdict (an OUTSIDE inside the printed band) is not drawn: the bar tells no other story. */
    @Test
    void aBarThatWouldContradictTheVerdictIsNotDrawn() {
        Map<String, Object> v = ResultFixtures.value("TSH", "5.0", "mIU/L", "4.0", "6.0", "outside");
        v.put("verdict_side", "high");
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, v);
        assertThat(row).startsWith("<a class=\"vrow outside\"").contains(copy.t(Lang.BN, "value.chip.outside.high")).doesNotContain("<svg");
    }

    /** Values keep the printed digits; the range text is the paper's text; the crop is the stored crop_key (DR-12). */
    @Test
    void theRowShowsThePaperAsPrintedBesideItsStoredCrop() {
        Map<String, Object> v = ResultFixtures.value("Creatinine", "1.8", "mg/dL", "0.7", "1.3", "outside");
        String row = ValueRows.row(copy, Lang.BN, ResultFixtures.DOC, v);
        assertThat(row).contains(">1.8<").contains("রিপোর্টে লেখা সীমা 0.7–1.3")
                .contains("src=\"" + PageChrome.imageHref(ResultFixtures.DOC, (String) v.get("crop_key")) + "\"");
        assertThat(row).doesNotContain("১.৮");
    }

    /** No number without its proof: a value map with no crop fails the page loudly rather than show a bare number. */
    @Test
    void aValueWithoutItsCropIsNeverShown() {
        Map<String, Object> v = ResultFixtures.value("Creatinine", "1.8", "mg/dL", "0.7", "1.3", "outside");
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
