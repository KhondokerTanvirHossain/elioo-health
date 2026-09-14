package com.elioo.baymax.extraction.application.service;

import com.elioo.baymax.extraction.domain.ExtractionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtractionJsonReaderTest {

    private final ExtractionJsonReader reader = new ExtractionJsonReader(new ObjectMapper());

    private static final String VALID = """
            {
              "document_type": "lab_report",
              "patient_hint": "Rahim",
              "document_date": "2026-03-14",
              "facility": "Popular Diagnostic",
              "values": [
                {"name": "HbA1c", "canonical_name": "hba1c", "value": "8.2", "unit": "%",
                 "ref_low": "4.0", "ref_high": "5.6", "flag": "high",
                 "source_span": {"page": 1, "start": 10, "end": 20}}
              ],
              "medicines": [],
              "follow_up": [],
              "free_text_summary": "A diabetes panel.",
              "confidence": {"overall": 0.91, "values": 0.9, "medicines": 1.0, "follow_up": 1.0}
            }
            """;

    @Test
    void readsAValidReply() {
        ExtractionResult result = reader.read(VALID);

        assertThat(result.documentType()).isEqualTo("lab_report");
        assertThat(result.documentDate()).isEqualTo("2026-03-14");
        assertThat(result.valuesOrEmpty()).hasSize(1);
        assertThat(result.valuesOrEmpty().get(0).sourceSpan().isUsable()).isTrue();
        assertThat(result.confidence().overallOrZero()).isEqualTo(0.91);
        assertThat(result.hasCriticalValue()).isFalse();
        assertThat(result.itemCount()).isEqualTo(1);
    }

    @Test
    void acceptsAReplyWrappedInAMarkdownFence() {
        assertThat(reader.read("```json\n" + VALID + "\n```").documentType()).isEqualTo("lab_report");
    }

    @Test
    void rejectsTextThatIsNotJson() {
        assertThatThrownBy(() -> reader.read("I could not read this document."))
                .isInstanceOf(ExtractionJsonReader.InvalidExtractionException.class);
    }

    @Test
    void rejectsAnUnknownDocumentType() {
        String bad = VALID.replace("\"lab_report\"", "\"xray\"");

        assertThatThrownBy(() -> reader.read(bad))
                .isInstanceOf(ExtractionJsonReader.InvalidExtractionException.class)
                .hasMessageContaining("shape");
    }

    @Test
    void rejectsAValueWithNoSourceSpan() {
        String bad = """
                {"document_type":"lab_report",
                 "values":[{"name":"HbA1c","value":"8.2"}],
                 "medicines":[],"follow_up":[],"confidence":{"overall":0.9}}
                """;

        assertThatThrownBy(() -> reader.read(bad))
                .isInstanceOf(ExtractionJsonReader.InvalidExtractionException.class);
    }

    @Test
    void rejectsAConfidenceOutsideZeroToOne() {
        assertThatThrownBy(() -> reader.read(VALID.replace("0.91", "1.4")))
                .isInstanceOf(ExtractionJsonReader.InvalidExtractionException.class);
    }

    @Test
    void rejectsAMalformedDate() {
        assertThatThrownBy(() -> reader.read(VALID.replace("2026-03-14", "14 March 2026")))
                .isInstanceOf(ExtractionJsonReader.InvalidExtractionException.class);
    }

    @Test
    void theValidationMessageNamesTheProblemWithoutQuotingPatientData() {
        try {
            reader.read(VALID.replace("\"lab_report\"", "\"xray\""));
        } catch (ExtractionJsonReader.InvalidExtractionException e) {
            assertThat(e.getMessage()).doesNotContain("Rahim").doesNotContain("8.2");
        }
    }

    @Test
    void lowestSectionIsTheWorstReportedSection() {
        ExtractionResult result = reader.read(VALID.replace("\"values\": 0.9", "\"values\": 0.4"));

        assertThat(result.confidence().lowestSection()).isEqualTo(0.4);
    }
}
