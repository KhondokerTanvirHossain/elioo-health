package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.extraction.domain.ExtractionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A row printed with no result must not destroy the rest of the report.
 *
 * <p>lab9 is a lab report whose panel prints many rows as name + reference range with the result column
 * blank — tests that were ordered and not performed. When the model transcribes those rows faithfully, with
 * {@code value: ""}, the schema's {@code minLength: 1} rejects the whole document and every one of the
 * other values is lost with it. The document goes FAILED: the family is told their photo could not be read,
 * for a page that was read correctly.</p>
 *
 * <p>The all-or-nothing validation is the defect. A row with no result carries no information a family
 * could be shown and no number that could be checked against a crop, so it is dropped — but it is dropped
 * on its own, the way an unlocatable crop is, and the values around it survive. The schema still refuses a
 * document that is malformed in any other way; this is one known, harmless shape being tolerated, not
 * validation being weakened.</p>
 */
class ValuelessRowsTest {

    private final ExtractionJsonReader reader = new ExtractionJsonReader(new ObjectMapper());

    /** lab9's shape: two real results, three ordered-but-not-performed rows between them. */
    private static final String WITH_BLANK_ROWS = """
            {"document_type":"lab_report","confidence":{"overall":0.9,"values":0.9},
             "values":[
               {"name":"Haemoglobin","value":"12.9","unit":"g/dL","source_span":{"page":1,"start":0,"end":11}},
               {"name":"Serum Creatinine","value":"","unit":"mg/dL","source_span":{"page":1,"start":12,"end":28}},
               {"name":"Serum Urea","value":"","unit":"mg/dL","source_span":{"page":1,"start":29,"end":39}},
               {"name":"Blood Urea Nitrogen","value":"   ","source_span":{"page":1,"start":40,"end":59}},
               {"name":"Platelet","value":"250","unit":"10^9/L","source_span":{"page":1,"start":60,"end":68}}
             ],
             "medicines":[],"follow_up":[],
             "clinical_context":{"chief_complaint":[],"history":[],"examination":[],"diagnosis":[],
               "investigations_advised":[],"advice":[],"referral":null}}
            """;

    @Test
    void aRowWithNoResultIsDroppedAndTheReportSurvives() {
        ExtractionResult result = reader.read(WITH_BLANK_ROWS);

        assertThat(result.valuesOrEmpty())
                .as("the three blank rows go; the two real results stay")
                .extracting(ExtractionResult.Value::name)
                .containsExactly("Haemoglobin", "Platelet");
    }

    /**
     * The direction that matters. Before this, three blank rows cost the family every other value on the
     * page and produced "we could not read your photo" for a page that was read correctly.
     */
    @Test
    void theWholeDocumentIsNotLostBecauseOfABlankRow() {
        ExtractionResult result = reader.read(WITH_BLANK_ROWS);

        assertThat(result.valuesOrEmpty()).hasSize(2);
        assertThat(result.valuesOrEmpty().get(1).value()).isEqualTo("250");
    }

    /** Tolerating one known shape must not turn into tolerating anything. */
    @Test
    void aGenuinelyMalformedDocumentIsStillRefused() {
        String missingName = """
                {"document_type":"lab_report","confidence":{"overall":0.9},
                 "values":[{"value":"12.9","source_span":{"page":1,"start":0,"end":4}}],
                 "medicines":[],"follow_up":[],
                 "clinical_context":{"chief_complaint":[],"history":[],"examination":[],"diagnosis":[],
                   "investigations_advised":[],"advice":[],"referral":null}}
                """;

        assertThatThrownBy(() -> reader.read(missingName))
                .isInstanceOf(ExtractionJsonReader.InvalidExtractionException.class)
                .hasMessageContaining("required");
    }

    @Test
    void aValueMissingItsSourceSpanIsStillRefused() {
        String noSpan = """
                {"document_type":"lab_report","confidence":{"overall":0.9},
                 "values":[{"name":"Haemoglobin","value":"12.9"}],
                 "medicines":[],"follow_up":[],
                 "clinical_context":{"chief_complaint":[],"history":[],"examination":[],"diagnosis":[],
                   "investigations_advised":[],"advice":[],"referral":null}}
                """;

        assertThatThrownBy(() -> reader.read(noSpan))
                .isInstanceOf(ExtractionJsonReader.InvalidExtractionException.class);
    }

    /** A report where nothing was performed reads as a report with no values, not as a failure. */
    @Test
    void aReportWhereEveryRowIsBlankReadsAsEmpty() {
        String allBlank = """
                {"document_type":"lab_report","confidence":{"overall":0.9},
                 "values":[
                   {"name":"Serum Creatinine","value":"","source_span":{"page":1,"start":0,"end":16}},
                   {"name":"Serum Urea","value":"","source_span":{"page":1,"start":17,"end":27}}
                 ],
                 "medicines":[],"follow_up":[],
                 "clinical_context":{"chief_complaint":[],"history":[],"examination":[],"diagnosis":[],
                   "investigations_advised":[],"advice":[],"referral":null}}
                """;

        assertThat(reader.read(allBlank).valuesOrEmpty()).isEmpty();
    }
}
