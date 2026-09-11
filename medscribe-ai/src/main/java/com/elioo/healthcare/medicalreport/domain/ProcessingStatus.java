package com.elioo.healthcare.medicalreport.domain;

/**
 * Status of master processing workflow.
 *
 * <p>Indicates overall success level of the orchestration workflow.</p>
 */
public enum ProcessingStatus {
    /**
     * Processing request received, not yet started.
     * Initial status when process record is created.
     * HTTP Status: 202 Accepted
     */
    PENDING("Processing request received"),

    /**
     * Processing is currently in progress.
     * One or more stages are being executed.
     * HTTP Status: 102 Processing
     */
    IN_PROGRESS("Processing in progress"),

    /**
     * All stages completed successfully.
     * All requested analysis is available in response.
     * HTTP Status: 200 OK
     */
    COMPLETED("All stages completed successfully"),

    /**
     * Some non-critical stages failed but core results available.
     * For example: OCR succeeded but ICD-10 inference failed.
     * HTTP Status: 206 Partial Content
     */
    PARTIAL_SUCCESS("Some stages failed but core results available"),

    /**
     * Critical stage failed, no usable results available.
     * For example: Image validation or OCR processing failed.
     * HTTP Status: 500 Internal Server Error
     */
    FAILED("Critical stage failed, no results available"),

    /**
     * Image validation failed.
     * Image quality too low, corrupted, or wrong format.
     * HTTP Status: 422 Unprocessable Entity
     */
    VALIDATION_FAILED("Image validation failed"),

    /**
     * Processing exceeded maximum time limit.
     * Workflow was terminated to prevent resource exhaustion.
     * HTTP Status: 504 Gateway Timeout
     */
    TIMEOUT("Processing exceeded maximum time limit");

    private final String description;

    ProcessingStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Check if status indicates success (complete or partial).
     */
    public boolean isSuccess() {
        return this == COMPLETED || this == PARTIAL_SUCCESS;
    }

    /**
     * Check if status indicates failure.
     */
    public boolean isFailure() {
        return this == FAILED || this == VALIDATION_FAILED || this == TIMEOUT;
    }

    /**
     * Get recommended HTTP status code for this processing status.
     */
    public int getHttpStatusCode() {
        return switch (this) {
            case PENDING -> 202; // Accepted
            case IN_PROGRESS -> 102; // Processing
            case COMPLETED -> 200; // OK
            case PARTIAL_SUCCESS -> 206; // Partial Content
            case VALIDATION_FAILED -> 422; // Unprocessable Entity
            case TIMEOUT -> 504; // Gateway Timeout
            case FAILED -> 500; // Internal Server Error
        };
    }
}
