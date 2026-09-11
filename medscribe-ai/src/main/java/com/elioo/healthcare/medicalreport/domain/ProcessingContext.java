package com.elioo.healthcare.medicalreport.domain;

import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import com.elioo.healthcare.medicalreport.application.port.out.OcrPort;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable context object passed through orchestration pipeline.
 * Accumulates results from each workflow stage.
 *
 * <p>Design Pattern: Context Object / Accumulator Pattern</p>
 * <p>This object is passed through the reactive chain, collecting results from each stage.
 * Each stage reads from the context and adds its results to it.</p>
 *
 * <p>Benefits:</p>
 * <ul>
 *   <li>Single source of truth for workflow state</li>
 *   <li>Immutable design ensures thread-safety</li>
 *   <li>Easy to debug - all intermediate results available</li>
 *   <li>Supports partial success - failed stages tracked separately</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingContext {

    // ==================== Workflow Metadata ====================

    /**
     * Unique identifier for this processing request.
     */
    private String reportId;

    /**
     * Original request from client.
     */
    private MasterProcessingRequest request;

    /**
     * Workflow start timestamp.
     */
    private LocalDateTime startTime;

    // ==================== Stage Results ====================

    /**
     * Image validation result from Stage 1.
     */
    private OcrPort.ImageQualityResult imageValidation;

    /**
     * OCR extracted test results from Stage 2.
     */
    private List<TestResult> ocrExtractedData;

    /**
     * OCR raw text from Stage 2.
     */
    private String ocrRawText;

    /**
     * Overall OCR confidence score.
     */
    private Double ocrConfidence;

    /**
     * Translated raw text from Stage 2.5 (English only).
     * If translation was not needed or disabled, this will be null.
     */
    private String translatedRawText;

    /**
     * Translated test results from Stage 2.5 (English test names).
     * If translation was not needed or disabled, this will be null.
     */
    private List<TestResult> translatedExtractedData;

    /**
     * Original language detected in OCR text.
     * Examples: "bn" (Bangla), "en" (English), "mixed" (multiple languages)
     */
    private String originalLanguage;

    /**
     * Whether translation was performed on the OCR text.
     * false = text was already English or translation disabled
     * true = text was translated from another language
     */
    @Builder.Default
    private boolean wasTranslated = false;

    /**
     * Entity detection and classification result from Stage 3.
     */
    private MedicalClassificationPort.ClassificationResult classificationResult;

    /**
     * ICD-10 diagnosis codes from Stage 4.
     */
    private List<MedicalClassificationPort.MedicalCode> icd10Codes;

    /**
     * RxNorm medication codes from Stage 5.
     */
    private List<MedicalClassificationPort.MedicalCode> rxnormCodes;

    /**
     * SNOMED-CT codes from Stage 6.
     */
    private List<MedicalClassificationPort.MedicalCode> snomedctCodes;

    /**
     * Clinical insights from Stages 7-11.
     */
    private ClinicalInsightPort.ClinicalInsightResult clinicalInsights;

    // ==================== Stage Tracking ====================

    /**
     * List of successfully completed stages.
     */
    @Builder.Default
    private List<ProcessingStage> completedStages = new ArrayList<>();

    /**
     * List of failed stages.
     */
    @Builder.Default
    private List<ProcessingStage> failedStages = new ArrayList<>();

    /**
     * List of errors encountered during processing.
     */
    @Builder.Default
    private List<MasterProcessingResponse.ProcessingError> errors = new ArrayList<>();

    /**
     * List of warnings (non-fatal issues).
     */
    @Builder.Default
    private List<MasterProcessingResponse.ProcessingWarning> warnings = new ArrayList<>();

    // ==================== Helper Methods ====================

    /**
     * Mark a stage as successfully completed.
     *
     * @param stage The completed stage
     */
    public void markStageCompleted(ProcessingStage stage) {
        if (!this.completedStages.contains(stage)) {
            this.completedStages.add(stage);
        }
    }

    /**
     * Mark a stage as failed and record the error.
     *
     * @param stage The failed stage
     * @param errorMessage Error description
     * @param retryable Whether the error is transient and retryable
     */
    public void markStageFailed(ProcessingStage stage, String errorMessage, boolean retryable) {
        if (!this.failedStages.contains(stage)) {
            this.failedStages.add(stage);
        }

        this.errors.add(MasterProcessingResponse.ProcessingError.builder()
                .stage(stage)
                .severity(stage.isCritical() ? "CRITICAL" : "ERROR")
                .message(errorMessage)
                .timestamp(LocalDateTime.now())
                .retryable(retryable)
                .build());
    }

    /**
     * Add a warning (non-fatal issue) to the context.
     *
     * @param stage The stage that generated the warning
     * @param message Warning description
     */
    public void addWarning(ProcessingStage stage, String message) {
        this.warnings.add(MasterProcessingResponse.ProcessingWarning.builder()
                .stage(stage)
                .severity("WARNING")
                .message(message)
                .build());
    }

    /**
     * Check if any critical stage has failed.
     * Critical failures mean no usable results are available.
     *
     * @return true if a critical stage failed
     */
    public boolean hasCriticalFailure() {
        return failedStages.stream()
                .anyMatch(ProcessingStage::isCritical);
    }

    /**
     * Check if workflow has any failures (critical or non-critical).
     *
     * @return true if any stage failed
     */
    public boolean hasAnyFailure() {
        return !failedStages.isEmpty();
    }

    /**
     * Get processing status based on completed and failed stages.
     *
     * @return Current processing status
     */
    public ProcessingStatus getProcessingStatus() {
        if (hasCriticalFailure()) {
            // Check if it's a validation failure specifically
            if (failedStages.contains(ProcessingStage.IMAGE_VALIDATION)) {
                return ProcessingStatus.VALIDATION_FAILED;
            }
            return ProcessingStatus.FAILED;
        }

        if (hasAnyFailure()) {
            return ProcessingStatus.PARTIAL_SUCCESS;
        }

        return ProcessingStatus.COMPLETED;
    }

    /**
     * Get count of completed stages.
     *
     * @return Number of completed stages
     */
    public int getCompletedStageCount() {
        return completedStages.size();
    }

    /**
     * Get count of failed stages.
     *
     * @return Number of failed stages
     */
    public int getFailedStageCount() {
        return failedStages.size();
    }

    /**
     * Check if a specific stage was completed.
     *
     * @param stage The stage to check
     * @return true if stage completed successfully
     */
    public boolean isStageCompleted(ProcessingStage stage) {
        return completedStages.contains(stage);
    }

    /**
     * Check if a specific stage failed.
     *
     * @param stage The stage to check
     * @return true if stage failed
     */
    public boolean isStageFailed(ProcessingStage stage) {
        return failedStages.contains(stage);
    }
}
