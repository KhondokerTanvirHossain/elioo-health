package com.elioo.healthcare.medicalreport.domain;

/**
 * Enumeration of workflow processing stages.
 *
 * <p>Each stage represents a step in the master orchestration workflow.</p>
 * <p>Stages are marked as critical or non-critical:
 * <ul>
 *   <li>Critical stages MUST succeed for workflow to continue</li>
 *   <li>Non-critical stages can fail with partial success</li>
 * </ul>
 */
public enum ProcessingStage {
    /**
     * Stage 1: Validate image quality, format, and size.
     * Critical: Yes - Poor quality images cannot be processed reliably.
     */
    IMAGE_VALIDATION("Image Validation", true),

    /**
     * Stage 2: Extract text and structured data from image using OCR.
     * Critical: Yes - No data extraction means no analysis possible.
     */
    OCR_PROCESSING("OCR Processing", true),

    /**
     * Stage 2.5: Translate non-English text to English.
     * Critical: No - Workflow can continue with original language, but AWS services need English.
     */
    TRANSLATION("Translation", false),

    /**
     * Stage 3: Detect medical entities (tests, medications, conditions).
     * Critical: Yes - Entity detection is required for downstream processing.
     */
    ENTITY_DETECTION("Entity Detection", true),

    /**
     * Stage 4: Infer ICD-10 diagnosis codes.
     * Critical: No - Workflow can continue without medical codes.
     */
    ICD10_INFERENCE("ICD-10 Inference", false),

    /**
     * Stage 5: Infer RxNorm medication codes.
     * Critical: No - Workflow can continue without medication codes.
     */
    RXNORM_INFERENCE("RxNorm Inference", false),

    /**
     * Stage 6: Infer SNOMED-CT clinical terminology codes.
     * Critical: No - Workflow can continue without SNOMED codes.
     */
    SNOMEDCT_INFERENCE("SNOMED-CT Inference", false),

    /**
     * Stage 7: Generate comprehensive clinical insights using AI.
     * Critical: No - But highly valuable for end users.
     */
    CLINICAL_INSIGHTS("Clinical Insights", false),

    /**
     * Stage 7: Generate patient-friendly summary.
     * Critical: No - Part of clinical insights generation.
     */
    PATIENT_SUMMARY("Patient Summary", false),

    /**
     * Stage 8: Assess clinical risk across multiple categories.
     * Critical: No - Part of clinical insights generation.
     */
    RISK_ASSESSMENT("Risk Assessment", false),

    /**
     * Stage 9: Generate evidence-based recommendations.
     * Critical: No - Part of clinical insights generation.
     */
    RECOMMENDATIONS("Recommendations", false),

    /**
     * Stage 10: Generate educational content for patient understanding.
     * Critical: No - Optional enhancement for patient education.
     */
    EDUCATIONAL_CONTENT("Educational Content", false);

    private final String displayName;
    private final boolean critical;

    /**
     * Stages that are persisted as rows in medical_report_process_stage. PATIENT_SUMMARY,
     * RISK_ASSESSMENT, RECOMMENDATIONS and EDUCATIONAL_CONTENT are sub-steps of
     * CLINICAL_INSIGHTS and only tracked in memory, so progress is measured against this list.
     */
    public static java.util.List<ProcessingStage> persistedStages() {
        return java.util.List.of(IMAGE_VALIDATION, OCR_PROCESSING, TRANSLATION, ENTITY_DETECTION,
                ICD10_INFERENCE, RXNORM_INFERENCE, SNOMEDCT_INFERENCE, CLINICAL_INSIGHTS);
    }

    /** Number of persisted stages in the given list (null-safe). */
    public static int countPersisted(java.util.Collection<ProcessingStage> stages) {
        if (stages == null) return 0;
        java.util.List<ProcessingStage> persisted = persistedStages();
        return (int) stages.stream().filter(persisted::contains).count();
    }

    ProcessingStage(String displayName, boolean critical) {
        this.displayName = displayName;
        this.critical = critical;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isCritical() {
        return critical;
    }

    /**
     * Get human-readable description of the stage.
     */
    public String getDescription() {
        return switch (this) {
            case IMAGE_VALIDATION -> "Validates image quality and format before processing";
            case OCR_PROCESSING -> "Extracts text and structured medical data from image";
            case TRANSLATION -> "Translates non-English text to English for AWS processing";
            case ENTITY_DETECTION -> "Identifies medical entities like tests, medications, and conditions";
            case ICD10_INFERENCE -> "Maps findings to ICD-10 diagnosis codes";
            case RXNORM_INFERENCE -> "Maps medications to RxNorm standardized codes";
            case SNOMEDCT_INFERENCE -> "Maps clinical concepts to SNOMED-CT terminology codes";
            case CLINICAL_INSIGHTS -> "Generates AI-powered clinical analysis and insights";
            case PATIENT_SUMMARY -> "Creates patient-friendly summary of findings";
            case RISK_ASSESSMENT -> "Evaluates clinical risk across multiple organ systems";
            case RECOMMENDATIONS -> "Provides evidence-based clinical recommendations";
            case EDUCATIONAL_CONTENT -> "Generates educational materials for patient understanding";
        };
    }
}
