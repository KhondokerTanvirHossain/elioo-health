package com.elioo.healthcare.medicalreport.domain;

import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive response from master orchestration API.
 * Contains results from all workflow stages.
 *
 * <p>This is the final output of the 10-step medical report processing workflow.</p>
 * <p>Response structure supports both complete success and partial success scenarios.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterProcessingResponse {

    private String reportId;
    private String patientId;
    private ProcessingStatus processingStatus;
    private Long processingTimeMs;
    private LocalDateTime timestamp;

    private WorkflowSummary workflow;
    private ImageValidationResult imageValidation;
    private OcrResults ocrResults;
    private EntityDetectionResult entityDetection;
    private MedicalCodesResult medicalCodes;
    private ClinicalInsightsResult clinicalInsights;
    private MetadataResult metadata;

    private List<ProcessingError> errors;
    private List<ProcessingWarning> warnings;

    /**
     * Workflow execution summary.
     * Shows which stages completed, failed, or were skipped.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkflowSummary {
        private List<ProcessingStage> completedStages;
        private List<ProcessingStage> failedStages;
        private List<ProcessingStage> skippedStages;
    }

    /**
     * Image validation result from Stage 1.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageValidationResult {
        private Boolean isValid;
        private Double qualityScore;
        private String message;
        private Map<String, Object> metrics;
    }

    /**
     * OCR processing results from Stage 2.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OcrResults {
        private List<TestResult> extractedData;
        private String rawText;
        private Double overallConfidence;
        private Integer testCount;
    }

    /**
     * Entity detection results from Stage 3.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityDetectionResult {
        private List<MedicalClassificationPort.MedicalEntity> entities;
        private List<MedicalClassificationPort.EntityRelationship> relationships;
        private Integer entityCount;
        private String modelVersion;
    }

    /**
     * Medical codes from Stages 4-6 (ICD-10, RxNorm, SNOMED-CT).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MedicalCodesResult {
        private List<MedicalCode> icd10;
        private List<MedicalCode> rxnorm;
        private List<MedicalCode> snomedct;
        private Integer totalCodes;
    }

    /**
     * Medical code with metadata.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MedicalCode {
        private String code;
        private String description;
        private Double score;
        private String category;
    }

    /**
     * Clinical insights from Stages 6-10 (AI-powered analysis).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClinicalInsightsResult {
        private String summary;
        private List<KeyFinding> keyFindings;
        private RiskAssessment riskAssessment;
        private List<Recommendation> recommendations;
        private ActionPlan actionPlan;
        private List<EducationalContent> educationalContent;
    }

    /**
     * Key finding from medical data analysis.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeyFinding {
        private String id;
        private String finding;
        private String severity;
        private String interpretation;
        private String clinicalSignificance;
        private String normalRange;
        private String percentageDeviation;
        private List<String> relatedTests;
        private Boolean critical;
    }

    /**
     * Risk assessment across multiple categories.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskAssessment {
        private String overallRiskLevel;
        private Map<String, CategoryRisk> categoryRisks;
        private List<String> riskFactors;
        private List<String> protectiveFactors;
        private String overallAssessment;
        private Boolean requiresImmediateAttention;
    }

    /**
     * Category-specific risk (e.g., cardiovascular, renal).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryRisk {
        private String level;
        private Double score;
        private String description;
        private List<String> contributors;
    }

    /**
     * AI-generated recommendation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recommendation {
        private String id;
        private String category;
        private String priority;
        private String recommendation;
        private String rationale;
        private String evidenceLevel;
        private String timeframe;
        private List<String> prerequisites;
    }

    /**
     * Action plan with immediate, short-term, and long-term actions.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionPlan {
        private List<Action> immediateActions;
        private List<Action> shortTermActions;
        private List<Action> longTermActions;
        private Integer totalActions;
    }

    /**
     * Single action item with priority and timeline.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        private String action;
        private String priority;
        private String timeframe;
        private String category;
        private String status;
    }

    /**
     * Educational content for patient understanding.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EducationalContent {
        private String topic;
        private String content;
        private List<String> keyPoints;
        private List<String> resources;
    }

    /**
     * Processing metadata (versions, costs, etc.).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetadataResult {
        private String apiVersion;
        private LocalDateTime processingDate;
        private Map<String, String> modelVersions;
        private Map<String, String> costs;
    }

    /**
     * Processing error that occurred during workflow.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingError {
        private ProcessingStage stage;
        private String severity;
        private String message;
        private String code;
        private LocalDateTime timestamp;
        private Boolean retryable;
    }

    /**
     * Processing warning (non-fatal issue).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingWarning {
        private ProcessingStage stage;
        private String severity;
        private String message;
    }
}
