package com.elioo.healthcare.medicalreport.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Configuration options for workflow execution.
 * Allows clients to customize which stages to execute and how.
 *
 * <p>All fields have sensible defaults, so clients can provide minimal configuration.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowOptions {

    /**
     * Skip image quality validation stage.
     * Default: false (validation is performed).
     */
    @Builder.Default
    private Boolean skipValidation = false;

    /**
     * Include raw OCR text in response.
     * Default: true.
     */
    @Builder.Default
    private Boolean includeRawText = true;

    /**
     * Extract entity relationships during classification.
     * Default: true.
     */
    @Builder.Default
    private Boolean includeEntityRelationships = true;

    /**
     * Medical code systems to include.
     * Supported: ICD10, RXNORM, SNOMEDCT.
     * Default: [ICD10, RXNORM, SNOMEDCT].
     */
    @Builder.Default
    private List<String> requestedCodeSystems = List.of("ICD10", "RXNORM", "SNOMEDCT");

    /**
     * Generate educational content for patient.
     * Default: true.
     */
    @Builder.Default
    private Boolean includeEducationalContent = true;

    /**
     * Specific topics for educational content.
     * If empty, topics are auto-detected from findings.
     */
    private List<String> educationalContentTopics;

    /**
     * Risk categories to assess.
     * Supported: CARDIOVASCULAR, METABOLIC, RENAL, HEPATIC.
     * Default: all categories.
     */
    @Builder.Default
    private List<String> riskAssessmentCategories = List.of(
            "CARDIOVASCULAR", "METABOLIC", "RENAL", "HEPATIC"
    );

    /**
     * Target audience for clinical insights.
     * PATIENT: Patient-friendly language.
     * PROVIDER: Medical terminology.
     * Default: PATIENT.
     */
    @Builder.Default
    private String targetAudience = "PATIENT";

    /**
     * Response language.
     * Supported: en (English), bn (Bangla).
     * Default: en.
     */
    @Builder.Default
    private String language = "en";

    /**
     * Minimum confidence threshold for entity detection.
     * Range: 0.0 to 1.0.
     * Default: 0.70.
     */
    @Builder.Default
    private Double confidenceThreshold = 0.70;

    /**
     * Include prioritized action plan in response.
     * Default: true.
     */
    @Builder.Default
    private Boolean includeActionPlan = true;

    /**
     * Analyze trends from historical data.
     * Requires historicalData to be provided.
     * Default: false.
     */
    @Builder.Default
    private Boolean includeTrendAnalysis = false;

    /**
     * Historical medical data for trend analysis.
     * Format: List of previous test results with timestamps.
     */
    private Map<String, Object> historicalData;
}
