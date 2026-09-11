package com.elioo.healthcare.medicalreport.adapter.out.persistence;

import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import com.elioo.healthcare.medicalreport.domain.Priority;
import com.elioo.healthcare.medicalreport.domain.SuggestionCategory;
import com.elioo.healthcare.medicalreport.dto.SuggestionsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultDataDeserializerTest {

    private final ResultDataDeserializer deserializer = new ResultDataDeserializer(new ObjectMapper());

    @Test
    void blankJsonGivesNull() {
        assertThat(deserializer.deserialize("OCR", null)).isNull();
        assertThat(deserializer.deserialize("OCR", "  ")).isNull();
    }

    @Test
    void snomedCodesDeserializeLikeIcd10() {
        String json = "[{\"code\":\"61685007\",\"description\":\"Lower limb structure\",\"codeSystem\":\"SNOMED-CT\",\"score\":0.78}]";
        Object result = deserializer.deserialize("SNOMEDCT", json);
        assertThat(result).isInstanceOf(List.class);
        assertThat(((List<?>) result).get(0)).isInstanceOf(MedicalClassificationPort.MedicalCode.class);
    }

    @Test
    void unknownEnumValuesFromTheModelFallBackToDefaults() {
        String json = """
                {"reportId":"RPT-1","summary":"s","riskLevel":"SEVERE",
                 "aiSuggestions":[{"category":"PHYSIOTHERAPY","priority":"critical","recommendation":"stretch"}]}
                """;
        Object result = deserializer.deserialize("CLINICAL_INSIGHTS", json);
        assertThat(result).isInstanceOf(SuggestionsResponse.class);
        SuggestionsResponse r = (SuggestionsResponse) result;
        assertThat(r.getAiSuggestions().get(0).getCategory()).isEqualTo(SuggestionCategory.OTHER);
        assertThat(r.getAiSuggestions().get(0).getPriority()).isEqualTo(Priority.UNKNOWN);
        assertThat(r.getRiskLevel()).isEqualTo(com.elioo.healthcare.medicalreport.domain.Severity.UNKNOWN);
    }

    @Test
    void shapeMismatchFallsBackToGenericStructureInsteadOfNull() {
        // the old failure placeholder: content was a list, the record wants a string
        String json = "{\"topic\":\"General Health\",\"content\":[],\"message\":\"failed\"}";
        Object result = deserializer.deserialize("EDUCATIONAL_CONTENT", json);
        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("topic")).isEqualTo("General Health");
    }

    @Test
    void wellFormedEducationalContentStaysTyped() {
        String json = "{\"topic\":\"Anemia\",\"content\":\"text\",\"keyPoints\":[],\"resources\":[],\"faqs\":[]}";
        assertThat(deserializer.deserialize("EDUCATIONAL_CONTENT", json)).isInstanceOf(ClinicalInsightPort.EducationalContent.class);
    }

    @Test
    void unknownResultTypeIsStillReadable() {
        assertThat(deserializer.deserialize("SOMETHING_NEW", "{\"a\":1}")).isInstanceOf(Map.class);
        assertThat(deserializer.deserialize("SOMETHING_NEW", "not json")).isNull();
    }
}
