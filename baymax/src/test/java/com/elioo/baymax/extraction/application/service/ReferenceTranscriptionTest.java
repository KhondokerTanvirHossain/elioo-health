package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.extraction.domain.ExtractionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-29: extraction transcribes references, it never computes them.
 *
 * <p>The schema gave a value only {@code ref_low}, {@code ref_high} and {@code flag}, so a reference printed
 * as a word had nowhere to go — and the model filled the numeric fields anyway. lab2's urine routine came
 * back with "Nil" carrying {@code ref_low 0, ref_high 2}: a range that is not on the page, and one that then
 * drove urgency with nothing to mark it as invented.
 *
 * <p>Both directions matter and are tested here. A word reference must reach {@code ref_text} with the
 * numeric fields null; a printed numeric range must still populate them. A rule that only stopped
 * fabrication would also stop every real range.
 */
class ReferenceTranscriptionTest {

    private final ObjectMapper json = new ObjectMapper();

    private ExtractionResult.Value valueFrom(String body) throws Exception {
        return json.readValue(body, ExtractionResult.Value.class);
    }

    /** lab2, the fabrication case: "Nil" is the printed reference, and 0–2 was never on the page. */
    @Test
    void aWordReferenceIsTranscribedAndLeavesTheNumericFieldsNull() throws Exception {
        ExtractionResult.Value v = valueFrom("""
                {"name":"RBC (Isomorphic)","value":"Nil","unit":null,
                 "ref_low":null,"ref_high":null,"ref_text":"Nil","flag":null}
                """);

        assertThat(v.refText()).as("the page printed a word; it is kept verbatim").isEqualTo("Nil");
        assertThat(v.refLow()).as("no numeric range was printed, so none is invented").isNull();
        assertThat(v.refHigh()).isNull();
    }

    /** The other direction: a real printed range must still be read as numbers. */
    @Test
    void aPrintedNumericRangeStillPopulatesRefLowAndRefHigh() throws Exception {
        ExtractionResult.Value v = valueFrom("""
                {"name":"S-POTASSIUM","value":"6.4","unit":"mmol/L",
                 "ref_low":"3.5","ref_high":"5.1","ref_text":"3.5 - 5.1","flag":"H"}
                """);

        assertThat(v.refLow()).isEqualTo("3.5");
        assertThat(v.refHigh()).isEqualTo("5.1");
        assertThat(v.flag()).as("H is printed on the page, so it is a flag").isEqualTo("H");
    }

    /** lab3: "Upto 37" has no lower bound. A zero must never be invented to fill the gap. */
    @Test
    void aOneSidedReferenceKeepsItsWordingAndInventsNoLowerBound() throws Exception {
        ExtractionResult.Value v = valueFrom("""
                {"name":"SGPT (ALT)","value":"55.32","unit":"U/L",
                 "ref_low":null,"ref_high":"37","ref_text":"Upto 37","flag":null}
                """);

        assertThat(v.refLow()).as("the page prints no lower bound").isNull();
        assertThat(v.refHigh()).isEqualTo("37");
        assertThat(v.refText()).isEqualTo("Upto 37");
    }

    /** lab6: a tiered reference records the printed band; the tiers are not per-value ranges. */
    @Test
    void aTieredReferenceRecordsTheBandItFallsIn() throws Exception {
        ExtractionResult.Value v = valueFrom("""
                {"name":"Total Cholesterol","value":"215","unit":"mg/dL",
                 "ref_low":null,"ref_high":"200","band":"Borderline High",
                 "ref_text":"<200 Desirable / 200-239 Borderline High / >240 High","flag":null}
                """);

        assertThat(v.band()).isEqualTo("Borderline High");
        assertThat(v.refText()).contains("Desirable").contains("Borderline High");
    }

    /**
     * DR-30's shape, read here so part 2 has somewhere to land: each printed range is transcribed with its
     * qualifier, and code — not the model — decides which applies.
     */
    @Test
    void severalPrintedRangesAreAllTranscribedWithTheirQualifiers() throws Exception {
        ExtractionResult.Value v = valueFrom("""
                {"name":"Haemoglobin","value":"12.9","unit":"g/dL",
                 "ref_low":null,"ref_high":null,
                 "ref_text":"Male: 13.0-18.0, Female: 11.5-16.5",
                 "ranges":[{"low":"13.0","high":"18.0","qualifier":"Male"},
                           {"low":"11.5","high":"16.5","qualifier":"Female"}],
                 "flag":null}
                """);

        assertThat(v.ranges()).hasSize(2);
        assertThat(v.ranges().get(0).qualifier()).isEqualTo("Male");
        assertThat(v.ranges().get(1).qualifier()).isEqualTo("Female");
        assertThat(v.refLow()).as("the selected range is filled by code, not by the model").isNull();
    }

    /** A value with no reference at all: nothing invented, and nothing to judge it against. */
    @Test
    void noPrintedReferenceLeavesEveryReferenceFieldNull() throws Exception {
        ExtractionResult.Value v = valueFrom("""
                {"name":"SGPT (ALT)","value":"55.32","unit":"U/L",
                 "ref_low":null,"ref_high":null,"ref_text":null,"flag":null}
                """);

        assertThat(v.refLow()).isNull();
        assertThat(v.refHigh()).isNull();
        assertThat(v.refText()).isNull();
    }
}
