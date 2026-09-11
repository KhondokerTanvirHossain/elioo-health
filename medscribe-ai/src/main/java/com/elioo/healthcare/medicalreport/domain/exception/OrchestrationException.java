package com.elioo.healthcare.medicalreport.domain.exception;

import com.elioo.healthcare.medicalreport.domain.ProcessingStage;

/**
 * Exception thrown during orchestration workflow.
 *
 * <p>This exception is used to signal failures during the master orchestration workflow.
 * It carries context about which stage failed and whether the error is retryable.</p>
 *
 * <p>Usage Examples:</p>
 * <pre>{@code
 * // Critical stage failure (image validation)
 * throw new OrchestrationException(
 *     ProcessingStage.IMAGE_VALIDATION,
 *     "Image quality too low for OCR processing"
 * );
 *
 * // Retryable failure (AWS throttling)
 * throw new OrchestrationException(
 *     ProcessingStage.ICD10_INFERENCE,
 *     "AWS Comprehend Medical rate limit exceeded",
 *     cause,
 *     true
 * );
 * }</pre>
 */
public class OrchestrationException extends RuntimeException {

    private final ProcessingStage stage;
    private final boolean retryable;

    /**
     * Create exception for a failed stage.
     *
     * @param stage The stage that failed
     * @param message Error description
     */
    public OrchestrationException(ProcessingStage stage, String message) {
        this(stage, message, null, false);
    }

    /**
     * Create exception for a failed stage with cause.
     *
     * @param stage The stage that failed
     * @param message Error description
     * @param cause Original exception that caused the failure
     */
    public OrchestrationException(ProcessingStage stage, String message, Throwable cause) {
        this(stage, message, cause, false);
    }

    /**
     * Create exception with full context.
     *
     * @param stage The stage that failed
     * @param message Error description
     * @param cause Original exception that caused the failure
     * @param retryable Whether the error is transient and retryable
     */
    public OrchestrationException(ProcessingStage stage, String message, Throwable cause, boolean retryable) {
        super(buildMessage(stage, message), cause);
        this.stage = stage;
        this.retryable = retryable;
    }

    /**
     * Get the stage that failed.
     *
     * @return Failed stage
     */
    public ProcessingStage getStage() {
        return stage;
    }

    /**
     * Check if error is retryable.
     * Retryable errors are typically transient issues like network timeouts or rate limiting.
     *
     * @return true if error is retryable
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * Check if the failed stage is critical.
     * Critical stage failures mean the workflow cannot continue.
     *
     * @return true if failed stage is critical
     */
    public boolean isCritical() {
        return stage != null && stage.isCritical();
    }

    /**
     * Get recommended retry delay in milliseconds.
     * Uses exponential backoff based on retry attempt number.
     *
     * @param attemptNumber Current retry attempt (1-indexed)
     * @return Delay in milliseconds before next retry
     */
    public long getRetryDelayMs(int attemptNumber) {
        if (!retryable) {
            return 0;
        }
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s (max)
        return Math.min(1000L * (long) Math.pow(2, attemptNumber - 1), 16000L);
    }

    /**
     * Build formatted error message.
     */
    private static String buildMessage(ProcessingStage stage, String message) {
        String stageInfo = stage != null
                ? String.format("[%s]", stage.getDisplayName())
                : "[Unknown Stage]";

        return String.format("%s %s", stageInfo, message);
    }

    /**
     * Get error code for this exception.
     * Can be used for internationalization or client-side error handling.
     *
     * @return Error code
     */
    public String getErrorCode() {
        if (stage == null) {
            return "ORCHESTRATION_ERROR";
        }

        return switch (stage) {
            case IMAGE_VALIDATION -> "IMAGE_VALIDATION_FAILED";
            case OCR_PROCESSING -> "OCR_PROCESSING_FAILED";
            case TRANSLATION -> "TRANSLATION_FAILED";
            case ENTITY_DETECTION -> "ENTITY_DETECTION_FAILED";
            case ICD10_INFERENCE -> "ICD10_INFERENCE_FAILED";
            case RXNORM_INFERENCE -> "RXNORM_INFERENCE_FAILED";
            case SNOMEDCT_INFERENCE -> "SNOMEDCT_INFERENCE_FAILED";
            case CLINICAL_INSIGHTS -> "CLINICAL_INSIGHTS_FAILED";
            case PATIENT_SUMMARY -> "PATIENT_SUMMARY_FAILED";
            case RISK_ASSESSMENT -> "RISK_ASSESSMENT_FAILED";
            case RECOMMENDATIONS -> "RECOMMENDATIONS_FAILED";
            case EDUCATIONAL_CONTENT -> "EDUCATIONAL_CONTENT_FAILED";
        };
    }

    @Override
    public String toString() {
        return String.format("OrchestrationException{stage=%s, critical=%s, retryable=%s, message='%s'}",
                stage != null ? stage.name() : "null",
                isCritical(),
                retryable,
                getMessage());
    }
}
