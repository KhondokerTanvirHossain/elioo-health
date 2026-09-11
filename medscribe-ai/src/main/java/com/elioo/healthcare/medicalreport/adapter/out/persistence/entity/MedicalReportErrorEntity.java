package com.elioo.healthcare.medicalreport.adapter.out.persistence.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Entity for tracking processing errors.
 *
 * <p>Architecture: Persistence Entity in Hexagonal Architecture</p>
 * <ul>
 *   <li>Maps to medical_report_error table in PostgreSQL</li>
 *   <li>Multiple records per failed report (one per error)</li>
 *   <li>Stores complete error details for debugging and analytics</li>
 * </ul>
 *
 * <p>Error Severity Levels:</p>
 * <ul>
 *   <li>ERROR - Critical error that stopped processing</li>
 *   <li>WARNING - Non-critical issue, processing continued</li>
 *   <li>INFO - Informational message</li>
 * </ul>
 *
 * <p>Common Error Codes:</p>
 * <ul>
 *   <li>IMAGE_VALIDATION_FAILED - Image quality too low</li>
 *   <li>OCR_PROCESSING_FAILED - Text extraction failed</li>
 *   <li>ENTITY_DETECTION_FAILED - Medical NLP failed</li>
 *   <li>ICD10_INFERENCE_FAILED - Diagnosis code mapping failed</li>
 *   <li>RXNORM_INFERENCE_FAILED - Medication code mapping failed</li>
 *   <li>CLINICAL_INSIGHTS_FAILED - AI analysis failed</li>
 *   <li>RATE_LIMIT_EXCEEDED - AWS service rate limit</li>
 *   <li>TIMEOUT - Processing exceeded time limit</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("medical_report_error")
public class MedicalReportErrorEntity {

    /**
     * Unique identifier for this error record.
     * Generated UUID.
     */
    @Id
    @Column("id")
    private String id;

    /**
     * Reference to the report process that encountered this error.
     * Foreign key to medical_report_process.report_id.
     */
    @Column("report_id")
    private String reportId;

    /**
     * Reference to the specific stage that encountered this error.
     * Foreign key to medical_report_process_stage.id (nullable).
     * Null if error is not stage-specific.
     */
    @Column("stage_id")
    private String stageId;

    /**
     * Type of processing stage where error occurred.
     * One of: IMAGE_VALIDATION, OCR_PROCESSING, etc.
     * Denormalized for easy querying without join.
     */
    @Column("stage")
    private String stage;

    /**
     * Error code for programmatic error handling.
     * Examples: OCR_PROCESSING_FAILED, RATE_LIMIT_EXCEEDED
     */
    @Column("error_code")
    private String errorCode;

    /**
     * Human-readable error message.
     * Should be clear and actionable.
     */
    @Column("error_message")
    private String errorMessage;

    /**
     * Full stack trace for debugging.
     * Contains complete exception stack trace.
     * May be very long (TEXT field).
     */
    @Column("stack_trace")
    private String stackTrace;

    /**
     * Whether this error is retryable.
     * True for transient errors (network issues, rate limits, timeouts).
     * False for permanent errors (invalid data, configuration issues).
     */
    @Column("is_retryable")
    @Builder.Default
    private Boolean isRetryable = false;

    /**
     * Timestamp when error occurred.
     */
    @Column("occurred_at")
    private LocalDateTime occurredAt;

    /**
     * Error severity level.
     * One of: ERROR, WARNING, INFO
     */
    @Column("severity")
    private String severity;

    /**
     * Check if this is a critical error.
     */
    public boolean isCritical() {
        return "ERROR".equals(severity);
    }

    /**
     * Check if this error should trigger retry logic.
     */
    public boolean shouldRetry() {
        return Boolean.TRUE.equals(isRetryable);
    }

    /**
     * Get short error summary (first 100 chars of message).
     */
    public String getShortMessage() {
        if (errorMessage == null) {
            return null;
        }
        return errorMessage.length() > 100
                ? errorMessage.substring(0, 97) + "..."
                : errorMessage;
    }
}
