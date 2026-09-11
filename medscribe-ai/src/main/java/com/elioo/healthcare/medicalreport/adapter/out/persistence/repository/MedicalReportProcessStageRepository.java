package com.elioo.healthcare.medicalreport.adapter.out.persistence.repository;

import com.elioo.healthcare.medicalreport.adapter.out.persistence.entity.MedicalReportProcessStageEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * R2DBC repository for medical report processing stages.
 *
 * <p>Architecture: Repository in Hexagonal Architecture (Outbound Adapter)</p>
 * <ul>
 *   <li>Reactive database access using R2DBC</li>
 *   <li>Tracks individual stages (10 per report)</li>
 *   <li>Supports analytics queries for performance monitoring</li>
 * </ul>
 */
@Repository
public interface MedicalReportProcessStageRepository extends R2dbcRepository<MedicalReportProcessStageEntity, String> {

    /**
     * Find all stages for a specific report.
     *
     * @param reportId Report identifier
     * @return Flux of stages ordered by start time
     */
    @Query("SELECT * FROM medical_report_process_stage " +
           "WHERE report_id = :reportId " +
           "ORDER BY started_at ASC")
    Flux<MedicalReportProcessStageEntity> findByReportIdOrderByStartedAt(String reportId);

    /**
     * Find stages by report and stage type.
     *
     * @param reportId Report identifier
     * @param stage Stage type (e.g., OCR_PROCESSING)
     * @return Mono of the stage
     */
    Mono<MedicalReportProcessStageEntity> findByReportIdAndStage(String reportId, String stage);

    /**
     * Find all stages of a specific type.
     *
     * @param stage Stage type
     * @return Flux of stages
     */
    Flux<MedicalReportProcessStageEntity> findByStage(String stage);

    /**
     * Find stages by status.
     *
     * @param status Stage status (COMPLETED, FAILED, etc.)
     * @return Flux of stages
     */
    Flux<MedicalReportProcessStageEntity> findByStatus(String status);

    /**
     * Calculate average duration for a specific stage type.
     *
     * @param stage Stage type
     * @param since Timestamp to calculate from
     * @return Mono of average duration in milliseconds
     */
    @Query("SELECT AVG(duration_ms) FROM medical_report_process_stage " +
           "WHERE stage = :stage " +
           "AND status = 'COMPLETED' " +
           "AND started_at > :since")
    Mono<Double> calculateAverageDuration(String stage, LocalDateTime since);

    /**
     * Calculate failure rate for a specific stage type.
     *
     * @param stage Stage type
     * @param since Timestamp to calculate from
     * @return Mono of failure rate (0.0 to 1.0)
     */
    @Query("SELECT CAST(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS DECIMAL) / COUNT(*) " +
           "FROM medical_report_process_stage " +
           "WHERE stage = :stage AND started_at > :since")
    Mono<Double> calculateFailureRate(String stage, LocalDateTime since);

    /**
     * Find slowest stages (by duration).
     *
     * @param limit Number of records to return
     * @return Flux of slowest stages
     */
    @Query("SELECT * FROM medical_report_process_stage " +
           "WHERE status = 'COMPLETED' AND duration_ms IS NOT NULL " +
           "ORDER BY duration_ms DESC " +
           "LIMIT :limit")
    Flux<MedicalReportProcessStageEntity> findSlowestStages(int limit);

    /**
     * Find failed stages that are retryable.
     *
     * @return Flux of retryable failed stages
     */
    @Query("SELECT * FROM medical_report_process_stage " +
           "WHERE status = 'FAILED' AND is_retryable = true " +
           "ORDER BY started_at DESC")
    Flux<MedicalReportProcessStageEntity> findRetryableFailures();

    /**
     * Count stages by status for a specific report.
     *
     * @param reportId Report identifier
     * @param status Stage status
     * @return Mono of count
     */
    Mono<Long> countByReportIdAndStatus(String reportId, String status);

    /**
     * Get stage statistics for analytics.
     *
     * @param since Timestamp to calculate from
     * @return Flux of stage statistics (stage, avg_duration, count, failure_rate)
     */
    @Query("SELECT " +
           "  stage, " +
           "  AVG(duration_ms) as avg_duration, " +
           "  COUNT(*) as total_count, " +
           "  SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) as failed_count " +
           "FROM medical_report_process_stage " +
           "WHERE started_at > :since " +
           "GROUP BY stage " +
           "ORDER BY avg_duration DESC")
    Flux<StageStatistics> getStageStatistics(LocalDateTime since);

    /**
     * Custom insert with explicit JSONB casting.
     * Required because Spring Data R2DBC doesn't automatically cast String to JSONB.
     *
     * @param id Stage ID
     * @param reportId Report ID
     * @param stage Stage name
     * @param status Stage status
     * @param startedAt Start timestamp
     * @param completedAt Completion timestamp
     * @param durationMs Duration in milliseconds
     * @param inputDataJson Input data as JSON string
     * @param outputDataJson Output data as JSON string
     * @param errorMessage Error message if any
     * @param isRetryable Whether stage can be retried
     * @param attemptNumber Attempt number
     * @param confidenceScore Confidence score
     * @param qualityMetricsJson Quality metrics as JSON string
     * @return Mono of number of rows inserted
     */
    @Query("""
            INSERT INTO medical_report_process_stage (
                id, report_id, stage, status, started_at, completed_at, duration_ms,
                input_data_json, output_data_json, error_message, is_retryable,
                attempt_number, confidence_score, quality_metrics_json
            ) VALUES (
                :id, :reportId, :stage, :status, :startedAt, :completedAt, :durationMs,
                :inputDataJson::jsonb, :outputDataJson::jsonb, :errorMessage, :isRetryable,
                :attemptNumber, :confidenceScore, :qualityMetricsJson::jsonb
            )
            """)
    Mono<Integer> insertWithJsonbCast(
            String id, String reportId, String stage, String status,
            LocalDateTime startedAt, LocalDateTime completedAt, Long durationMs,
            String inputDataJson, String outputDataJson, String errorMessage,
            Boolean isRetryable, Integer attemptNumber, Double confidenceScore,
            String qualityMetricsJson
    );

    /**
     * Custom update with explicit JSONB casting.
     *
     * @param id Stage ID
     * @param status Stage status
     * @param completedAt Completion timestamp
     * @param durationMs Duration in milliseconds
     * @param outputDataJson Output data as JSON string
     * @param errorMessage Error message if any
     * @param confidenceScore Confidence score
     * @param qualityMetricsJson Quality metrics as JSON string
     * @return Mono of number of rows updated
     */
    @Query("""
            UPDATE medical_report_process_stage
            SET status = :status,
                completed_at = :completedAt,
                duration_ms = :durationMs,
                output_data_json = :outputDataJson::jsonb,
                error_message = :errorMessage,
                confidence_score = :confidenceScore,
                quality_metrics_json = :qualityMetricsJson::jsonb
            WHERE id = :id
            """)
    Mono<Integer> updateWithJsonbCast(
            String id, String status, LocalDateTime completedAt, Long durationMs,
            String outputDataJson, String errorMessage, Double confidenceScore,
            String qualityMetricsJson
    );

    /**
     * DTO for stage statistics query result.
     */
    record StageStatistics(
            String stage,
            Double avgDuration,
            Long totalCount,
            Long failedCount
    ) {
        public Double getFailureRate() {
            if (totalCount == null || totalCount == 0) {
                return 0.0;
            }
            return (double) (failedCount != null ? failedCount : 0) / totalCount;
        }
    }
}
