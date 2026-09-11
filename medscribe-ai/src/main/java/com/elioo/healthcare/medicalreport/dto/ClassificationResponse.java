package com.elioo.healthcare.medicalreport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassificationResponse {
    private String reportId;
    private ComprehendMedicalResult classificationResult;
    private MedicalCodes medicalCodes;
    private LocalDateTime processedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComprehendMedicalResult {
        private List<MedicalEntity> Entities;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MedicalEntity {
        private Integer Id;
        private String Text;
        private String Category;
        private String Type;
        private Double Score;
        private List<EntityAttribute> Attributes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityAttribute {
        private String Type;
        private Double Score;
        private Double RelationshipScore;
        private String RelationshipType;
        private Integer Id;
        private String Text;
        private String Category;
        private List<Map<String, Object>> Traits;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MedicalCodes {
        private List<String> ICD10;
        private List<String> LOINC;
        private List<String> SNOMED;
    }
}
