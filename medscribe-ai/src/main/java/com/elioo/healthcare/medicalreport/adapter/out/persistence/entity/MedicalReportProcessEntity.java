package com.elioo.healthcare.medicalreport.adapter.out.persistence.entity;

import com.elioo.healthcare.medicalreport.domain.ProcessingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Entity for tracking overall medical report processing workflow.
 *
 * <p>Architecture: Persistence Entity in Hexagonal Architecture</p>
 * <ul>
 *   <li>Maps to medical_report_process table in PostgreSQL</li>
 *   <li>Tracks complete lifecycle of a report processing request</li>
 *   <li>One record per report processing request</li>
 * </ul>
 *
 * <p>Table Schema:</p>
 * <ul>
 *   <li>Primary Key: report_id</li>
 *   <li>Foreign Key: patient_id (reference to patient)</li>
 *   <li>JSONB fields: patient_context_json, workflow_options_json</li>
 *   <li>Indexes: patient_id, status, created_at</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("medical_report_process")
public class MedicalReportProcessEntity {

    /**
     * Unique report identifier (e.g., RPT-ABC12345).
     * Primary key for the table.
     */
    @Id
    @Column("report_id")
    private String reportId;

    /**
     * Patient identifier from patient context.
     * Used to query all reports for a specific patient.
     */
    @Column("patient_id")
    private String patientId;

    /**
     * Current processing status.
     * One of: PENDING, IN_PROGRESS, COMPLETED, FAILED, PARTIAL_SUCCESS
     */
    @Column("status")
    private String status;

    /**
     * Timestamp when the processing request was received.
     */
    @Column("created_at")
    private LocalDateTime createdAt;

    /**
     * Timestamp of last update to this record.
     */
    @Column("updated_at")
    private LocalDateTime updatedAt;

    /**
     * Timestamp when processing completed (success or failure).
     */
    @Column("completed_at")
    private LocalDateTime completedAt;

    /**
     * Total processing time in milliseconds.
     * Calculated as (completedAt - createdAt).
     */
    @Column("processing_time_ms")
    private Long processingTimeMs;

    /**
     * Original base64-encoded image data.
     * NOTE: Consider moving to S3 for large images to reduce database size.
     */
    @Column("image_base64")
    private String imageBase64;

    /**
     * Patient context as JSON string.
     * Stored as JSONB in PostgreSQL for efficient querying.
     * Contains: demographics, medical history, medications, allergies, etc.
     */
    @Column("patient_context_json")
    private String patientContextJson;

    /**
     * Workflow options as JSON string.
     * Stored as JSONB in PostgreSQL.
     * Contains: skipValidation, includeRawText, requestedCodeSystems, etc.
     */
    @Column("workflow_options_json")
    private String workflowOptionsJson;

    /**
     * Count of successfully completed stages.
     * Updated as stages complete.
     */
    @Column("completed_stages")
    @Builder.Default
    private Integer completedStages = 0;

    /**
     * Count of failed stages.
     * Updated when stages fail.
     */
    @Column("failed_stages")
    @Builder.Default
    private Integer failedStages = 0;

    /**
     * Total number of stages in the workflow.
     * Default is 10 stages.
     */
    @Column("total_stages")
    @Builder.Default
    private Integer totalStages = 10;

    /**
     * User or system that initiated this processing request.
     * Optional field for audit purposes.
     */
    @Column("created_by")
    private String createdBy;

    /**
     * Top-level error message if processing failed.
     * Contains the main error that caused the failure.
     */
    @Column("error_message")
    private String errorMessage;

    /**
     * Check if processing is complete (success or failure).
     */
    public boolean isComplete() {
        return completedAt != null;
    }

    /**
     * Check if processing was successful.
     */
    public boolean isSuccess() {
        return "COMPLETED".equals(status) || "PARTIAL_SUCCESS".equals(status);
    }

    /**
     * Calculate progress percentage.
     */
    public double getProgressPercentage() {
        if (totalStages == null || totalStages == 0) {
            return 0.0;
        }
        return (double) (completedStages != null ? completedStages : 0) / totalStages * 100;
    }
}
