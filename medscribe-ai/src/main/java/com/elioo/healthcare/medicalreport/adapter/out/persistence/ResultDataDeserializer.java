package com.elioo.healthcare.medicalreport.adapter.out.persistence;

import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import com.elioo.healthcare.medicalreport.dto.ClassificationResponse;
import com.elioo.healthcare.medicalreport.dto.OcrResponse;
import com.elioo.healthcare.medicalreport.dto.SuggestionsResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Turns a stored {@code result_data_json} back into a typed object.
 *
 * <p>Rules: a blank document is {@code null}; a known type is parsed into its DTO with
 * tolerant enum handling (values an LLM invents map to each enum's default constant);
 * anything that still fails, and any unknown type, is returned as the generic
 * {@code Map}/{@code List} structure rather than {@code null}, so readers such as the
 * query API and the chat context always get <em>something</em> to show.</p>
 */
@Slf4j
public class ResultDataDeserializer {

    private final ObjectMapper mapper;

    public ResultDataDeserializer(ObjectMapper base) {
        this.mapper = JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        // same modules as the application mapper (JavaTimeModule etc.), plus tolerant settings above
        this.mapper.findAndRegisterModules();
    }

    public Object deserialize(String resultType, String resultDataJson) {
        if (resultDataJson == null || resultDataJson.isBlank()) {
            return null;
        }
        JavaType target = targetType(resultType);
        if (target != null) {
            try {
                return mapper.readValue(resultDataJson, target);
            } catch (Exception e) {
                log.warn("Result {} does not match its DTO ({}); returning generic structure", resultType,
                        e.getMessage() != null ? e.getMessage().split("\n")[0] : e.getClass().getSimpleName());
            }
        }
        try {
            return mapper.readValue(resultDataJson, Object.class);
        } catch (Exception e) {
            log.error("Result {} is not valid JSON: {}", resultType, e.getMessage());
            return null;
        }
    }

    private JavaType targetType(String resultType) {
        var tf = mapper.getTypeFactory();
        return switch (resultType == null ? "" : resultType) {
            case "OCR" -> tf.constructType(OcrResponse.class);
            case "TRANSLATION" -> tf.constructMapType(Map.class, String.class, Object.class);
            case "CLASSIFICATION" -> tf.constructType(ClassificationResponse.class);
            case "ICD10", "RXNORM", "SNOMEDCT" ->
                    tf.constructCollectionType(List.class, MedicalClassificationPort.MedicalCode.class);
            case "CLINICAL_INSIGHTS" -> tf.constructType(SuggestionsResponse.class);
            case "RISK_ASSESSMENT" -> tf.constructType(ClinicalInsightPort.RiskAssessment.class);
            case "RECOMMENDATIONS" -> tf.constructCollectionType(List.class, ClinicalInsightPort.Recommendation.class);
            case "EDUCATIONAL_CONTENT" -> tf.constructType(ClinicalInsightPort.EducationalContent.class);
            default -> null;
        };
    }
}
