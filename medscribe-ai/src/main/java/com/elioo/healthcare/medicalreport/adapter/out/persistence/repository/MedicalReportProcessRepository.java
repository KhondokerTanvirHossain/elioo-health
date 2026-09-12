package com.elioo.healthcare.medicalreport.adapter.out.persistence.repository;

import com.elioo.healthcare.medicalreport.adapter.out.persistence.entity.MedicalReportProcessEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * R2DBC repository for medical report process tracking.
 *
 * <p>Architecture: Repository in Hexagonal Architecture (Outbound Adapter)</p>
 * <ul>
 *   <li>Reactive database access using R2DBC</li>
 *   <li>Non-blocking queries return Mono/Flux</li>
 *   <li>Supports complex queries with @Query annotation</li>
 * </ul>
 *
 * <p>Common Queries:</p>
 * <ul>
 *   <li>Find by patient ID - Get all reports for a patient</li>
 *   <li>Find by status - Filter by processing status</li>
 *   <li>Find by date range - Time-based queries</li>
 *   <li>Find recent failures - Debugging and monitoring</li>
 * </ul>
 */
@Repository
public interface MedicalReportProcessRepository extends R2dbcRepository<MedicalReportProcessEntity, String> {

    /**
     * Find all reports for a specific patient.
     *
     * @param patientId Patient identifier
     * @return Flux of report processes for the patient
     */
    Flux<MedicalReportProcessEntity> findByPatientId(String patientId);

    /**
     * Find all reports with a specific status.
     *
     * @param status Processing status (COMPLETED, FAILED, etc.)
     * @return Flux of report processes with the status
     */
    Flux<MedicalReportProcessEntity> findByStatus(String status);

    /**
     * Find reports created within a date range.
     *
     * @param start Start timestamp (inclusive)
     * @param end End timestamp (inclusive)
     * @return Flux of report processes in the date range
     */
    Flux<MedicalReportProcessEntity> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /**
     * Find recent failed reports for debugging.
     *
     * @param since Timestamp to search from
     * @return Flux of failed reports since the given time
     */
    @Query("SELECT * FROM medical_report_process " +
           "WHERE status = 'FAILED' AND created_at > :since " +
           "ORDER BY created_at DESC")
    Flux<MedicalReportProcessEntity> findRecentFailures(LocalDateTime since);

    /**
     * Find reports by patient and status.
     *
     * @param patientId Patient identifier
     * @param status Processing status
     * @return Flux of matching reports
     */
    Flux<MedicalReportProcessEntity> findByPatientIdAndStatus(String patientId, String status);

    /**
     * Count reports by status.
     *
     * @param status Processing status
     * @return Mono of count
     */
    Mono<Long> countByStatus(String status);

    /**
     * Find reports with processing time exceeding threshold (slow reports).
     *
     * @param thresholdMs Minimum processing time in milliseconds
     * @return Flux of slow reports
     */
    @Query("SELECT * FROM medical_report_process " +
           "WHERE processing_time_ms > :thresholdMs " +
           "ORDER BY processing_time_ms DESC")
    Flux<MedicalReportProcessEntity> findSlowReports(Long thresholdMs);

    /**
     * Calculate average processing time for completed reports.
     *
     * @param since Timestamp to calculate from
     * @return Mono of average processing time in milliseconds
     */
    @Query("SELECT AVG(processing_time_ms) FROM medical_report_process " +
           "WHERE status IN ('COMPLETED', 'PARTIAL_SUCCESS') " +
           "AND completed_at > :since")
    Mono<Double> calculateAverageProcessingTime(LocalDateTime since);

    /**
     * Find reports with partial success (some stages failed).
     *
     * @return Flux of reports with partial success
     */
    @Query("SELECT * FROM medical_report_process " +
           "WHERE status = 'PARTIAL_SUCCESS' " +
           "ORDER BY created_at DESC")
    Flux<MedicalReportProcessEntity> findPartialSuccesses();

    /**
     * Clear image data for old reports (data retention).
     * Sets image_base64 to NULL for reports older than cutoff date.
     *
     * @param cutoff Timestamp cutoff
     * @return Mono of number of records updated
     */
    @Query("UPDATE medical_report_process " +
           "SET image_base64 = NULL " +
           "WHERE created_at < :cutoff AND image_base64 IS NOT NULL")
    Mono<Integer> clearImageDataOlderThan(LocalDateTime cutoff);

    /**
     * Custom insert with explicit JSONB casting.
     * Required because Spring Data R2DBC doesn't automatically cast String to JSONB.
     *
     * @param reportId Report ID
     * @param patientId Patient ID
     * @param status Processing status
     * @param createdAt Creation timestamp
     * @param updatedAt Update timestamp
     * @param completedAt Completion timestamp
     * @param processingTimeMs Processing time in milliseconds
     * @param imageBase64 Base64 encoded image
     * @param patientContextJson Patient context as JSON string
     * @param workflowOptionsJson Workflow options as JSON string
     * @param completedStages Number of completed stages
     * @param failedStages Number of failed stages
     * @param totalStages Total number of stages
     * @param createdBy Creator user
     * @param errorMessage Error message if any
     * @return Mono of number of rows inserted
     */
    @Query("""
            INSERT INTO medical_report_process (
                report_id, patient_id, status, created_at, updated_at, completed_at, processing_time_ms,
                image_base64, patient_context_json, workflow_options_json,
                completed_stages, failed_stages, total_stages, created_by, error_message
            ) VALUES (
                :reportId, :patientId, :status, :createdAt, :updatedAt, :completedAt, :processingTimeMs,
                :imageBase64, :patientContextJson::jsonb, :workflowOptionsJson::jsonb,
                :completedStages, :failedStages, :totalStages, :createdBy, :errorMessage
            )
            """)
    Mono<Integer> insertWithJsonbCast(
            String reportId, String patientId, String status,
            LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime completedAt,
            Long processingTimeMs, String imageBase64, String patientContextJson,
            String workflowOptionsJson, Integer completedStages, Integer failedStages,
            Integer totalStages, String createdBy, String errorMessage
    );

    /**
     * Custom update with explicit JSONB casting.
     *
     * @param reportId Report ID
     * @param patientId Patient ID
     * @param status Processing status
     * @param updatedAt Update timestamp
     * @param completedAt Completion timestamp
     * @param processingTimeMs Processing time
     * @param patientContextJson Patient context JSON
     * @param workflowOptionsJson Workflow options JSON
     * @param completedStages Completed stages count
     * @param failedStages Failed stages count
     * @param errorMessage Error message
     * @return Mono of number of rows updated
     */
    @Query("""
            UPDATE medical_report_process
            SET patient_id = :patientId,
                status = :status,
                updated_at = :updatedAt,
                completed_at = :completedAt,
                processing_time_ms = :processingTimeMs,
                patient_context_json = :patientContextJson::jsonb,
                workflow_options_json = :workflowOptionsJson::jsonb,
                completed_stages = :completedStages,
                failed_stages = :failedStages,
                error_message = :errorMessage
            WHERE report_id = :reportId
            """)
    Mono<Integer> updateWithJsonbCast(
            String reportId, String patientId, String status,
            LocalDateTime updatedAt, LocalDateTime completedAt, Long processingTimeMs,
            String patientContextJson, String workflowOptionsJson,
            Integer completedStages, Integer failedStages, String errorMessage
    );

    /** Atomic increment (no read-modify-write): safe when stages complete concurrently. */
    @org.springframework.data.r2dbc.repository.Modifying
    @Query("UPDATE medical_report_process SET completed_stages = COALESCE(completed_stages, 0) + 1, updated_at = CURRENT_TIMESTAMP WHERE report_id = :reportId")
    Mono<Integer> incrementCompletedStages(String reportId);

    /** Atomic increment (no read-modify-write): safe when stages fail concurrently. */
    @org.springframework.data.r2dbc.repository.Modifying
    @Query("UPDATE medical_report_process SET failed_stages = COALESCE(failed_stages, 0) + 1, updated_at = CURRENT_TIMESTAMP WHERE report_id = :reportId")
    Mono<Integer> incrementFailedStages(String reportId);
}
