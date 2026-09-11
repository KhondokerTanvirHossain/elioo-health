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
 * Entity for tracking individual processing stages.
 *
 * <p>Architecture: Persistence Entity in Hexagonal Architecture</p>
 * <ul>
 *   <li>Maps to medical_report_process_stage table in PostgreSQL</li>
 *   <li>One record per stage per report (10 stages = 10 records)</li>
 *   <li>Tracks start time, end time, duration, status, and results</li>
 * </ul>
 *
 * <p>Stage Types:</p>
 * <ul>
 *   <li>IMAGE_VALIDATION - Image quality check</li>
 *   <li>OCR_PROCESSING - Text extraction</li>
 *   <li>ENTITY_DETECTION - Medical entity classification</li>
 *   <li>ICD10_INFERENCE - Diagnosis code mapping</li>
 *   <li>RXNORM_INFERENCE - Medication code mapping</li>
 *   <li>CLINICAL_INSIGHTS - AI-powered analysis</li>
 *   <li>PATIENT_SUMMARY - Patient-friendly summary</li>
 *   <li>RISK_ASSESSMENT - Risk evaluation</li>
 *   <li>RECOMMENDATIONS - Clinical recommendations</li>
 *   <li>EDUCATIONAL_CONTENT - Patient education</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("medical_report_process_stage")
public class MedicalReportProcessStageEntity {

    /**
     * Unique identifier for this stage instance.
     * Generated UUID.
     */
    @Id
    @Column("id")
    private String id;

    /**
     * Reference to the parent report process.
     * Foreign key to medical_report_process.report_id.
     */
    @Column("report_id")
    private String reportId;

    /**
     * Type of processing stage.
     * One of: IMAGE_VALIDATION, OCR_PROCESSING, ENTITY_DETECTION, etc.
     */
    @Column("stage")
    private String stage;

    /**
     * Current status of this stage.
     * One of: PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED
     */
    @Column("status")
    private String status;

    /**
     * Timestamp when stage processing started.
     */
    @Column("started_at")
    private LocalDateTime startedAt;

    /**
     * Timestamp when stage processing completed (success or failure).
     */
    @Column("completed_at")
    private LocalDateTime completedAt;

    /**
     * Duration of stage processing in milliseconds.
     * Calculated as (completedAt - startedAt).
     */
    @Column("duration_ms")
    private Long durationMs;

    /**
     * Input data to this stage as JSON string.
     * Stored as JSONB in PostgreSQL.
     * Contains the data passed to this stage for processing.
     */
    @Column("input_data_json")
    private String inputDataJson;

    /**
     * Output data from this stage as JSON string.
     * Stored as JSONB in PostgreSQL.
     * Contains the results produced by this stage.
     */
    @Column("output_data_json")
    private String outputDataJson;

    /**
     * Error message if stage failed.
     * Contains detailed error information for debugging.
     */
    @Column("error_message")
    private String errorMessage;

    /**
     * Whether this error is retryable.
     * True for transient errors (rate limits, timeouts).
     * False for permanent errors (invalid data, missing fields).
     */
    @Column("is_retryable")
    private Boolean isRetryable;

    /**
     * Retry attempt number.
     * 1 for first attempt, 2+ for retries.
     */
    @Column("attempt_number")
    @Builder.Default
    private Integer attemptNumber = 1;

    /**
     * Confidence score for this stage's output (0.0 to 1.0).
     * Represents quality/reliability of the results.
     */
    @Column("confidence_score")
    private Double confidenceScore;

    /**
     * Stage-specific quality metrics as JSON string.
     * Stored as JSONB in PostgreSQL.
     * Contains metrics like accuracy, precision, recall, etc.
     */
    @Column("quality_metrics_json")
    private String qualityMetricsJson;

    /**
     * Check if stage is complete (success or failure).
     */
    public boolean isComplete() {
        return completedAt != null;
    }

    /**
     * Check if stage completed successfully.
     */
    public boolean isSuccess() {
        return "COMPLETED".equals(status);
    }

    /**
     * Check if stage failed.
     */
    public boolean isFailed() {
        return "FAILED".equals(status);
    }

    /**
     * Calculate duration from timestamps if not set.
     */
    public Long calculateDuration() {
        if (startedAt != null && completedAt != null) {
            return java.time.Duration.between(startedAt, completedAt).toMillis();
        }
        return durationMs;
    }
}
