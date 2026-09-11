package com.elioo.healthcare.medicalreport.adapter.out.persistence.repository;

import com.elioo.healthcare.medicalreport.adapter.out.persistence.entity.MedicalReportErrorEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * R2DBC repository for medical report error tracking.
 *
 * <p>Architecture: Repository in Hexagonal Architecture (Outbound Adapter)</p>
 * <ul>
 *   <li>Reactive database access using R2DBC</li>
 *   <li>Tracks all processing errors for debugging</li>
 *   <li>Supports error analytics and pattern detection</li>
 * </ul>
 */
@Repository
public interface MedicalReportErrorRepository extends R2dbcRepository<MedicalReportErrorEntity, String> {

    /**
     * Find all errors for a specific report.
     *
     * @param reportId Report identifier
     * @return Flux of errors ordered by occurrence time
     */
    @Query("SELECT * FROM medical_report_error " +
           "WHERE report_id = :reportId " +
           "ORDER BY occurred_at DESC")
    Flux<MedicalReportErrorEntity> findByReportIdOrderByOccurredAt(String reportId);

    /**
     * Find errors by error code.
     *
     * @param errorCode Error code (e.g., OCR_PROCESSING_FAILED)
     * @return Flux of matching errors
     */
    Flux<MedicalReportErrorEntity> findByErrorCode(String errorCode);

    /**
     * Find errors by stage.
     *
     * @param stage Stage type where error occurred
     * @return Flux of errors for that stage
     */
    Flux<MedicalReportErrorEntity> findByStage(String stage);

    /**
     * Find errors by severity.
     *
     * @param severity Error severity (ERROR, WARNING, INFO)
     * @return Flux of errors with that severity
     */
    Flux<MedicalReportErrorEntity> findBySeverity(String severity);

    /**
     * Find retryable errors.
     *
     * @return Flux of retryable errors
     */
    Flux<MedicalReportErrorEntity> findByIsRetryableTrue();

    /**
     * Find recent errors for monitoring.
     *
     * @param since Timestamp to search from
     * @return Flux of recent errors
     */
    @Query("SELECT * FROM medical_report_error " +
           "WHERE occurred_at > :since " +
           "ORDER BY occurred_at DESC")
    Flux<MedicalReportErrorEntity> findRecentErrors(LocalDateTime since);

    /**
     * Get error patterns for analytics.
     * Groups errors by error_code and stage, counts occurrences.
     *
     * @param since Timestamp to analyze from
     * @return Flux of error patterns
     */
    @Query("SELECT " +
           "  error_code, " +
           "  stage, " +
           "  severity, " +
           "  COUNT(*) as error_count, " +
           "  SUM(CASE WHEN is_retryable = true THEN 1 ELSE 0 END) as retryable_count " +
           "FROM medical_report_error " +
           "WHERE occurred_at > :since " +
           "GROUP BY error_code, stage, severity " +
           "ORDER BY error_count DESC")
    Flux<ErrorPattern> getErrorPatterns(LocalDateTime since);

    /**
     * Count errors by error code.
     *
     * @param errorCode Error code
     * @param since Timestamp to count from
     * @return Mono of count
     */
    @Query("SELECT COUNT(*) FROM medical_report_error " +
           "WHERE error_code = :errorCode AND occurred_at > :since")
    Mono<Long> countByErrorCodeSince(String errorCode, LocalDateTime since);

    /**
     * Find most common errors in last N days.
     *
     * @param limit Number of error types to return
     * @param days Number of days to look back
     * @return Flux of most common errors
     */
    @Query("SELECT error_code, COUNT(*) as count " +
           "FROM medical_report_error " +
           "WHERE occurred_at > NOW() - INTERVAL ':days days' " +
           "GROUP BY error_code " +
           "ORDER BY count DESC " +
           "LIMIT :limit")
    Flux<ErrorCount> findMostCommonErrors(int limit, int days);

    /**
     * Find errors for a specific stage in a report.
     *
     * @param reportId Report identifier
     * @param stageId Stage identifier
     * @return Flux of errors for that stage
     */
    Flux<MedicalReportErrorEntity> findByReportIdAndStageId(String reportId, String stageId);

    /**
     * Count critical errors (severity = ERROR).
     *
     * @param since Timestamp to count from
     * @return Mono of count
     */
    @Query("SELECT COUNT(*) FROM medical_report_error " +
           "WHERE severity = 'ERROR' AND occurred_at > :since")
    Mono<Long> countCriticalErrorsSince(LocalDateTime since);

    /**
     * Insert new error record using explicit INSERT statement.
     * This avoids the "Row with Id does not exist" error when using save().
     *
     * @param id Unique identifier
     * @param reportId Report identifier
     * @param stageId Stage identifier (nullable)
     * @param stage Stage type
     * @param errorCode Error code
     * @param errorMessage Error message
     * @param stackTrace Stack trace
     * @param isRetryable Whether error is retryable
     * @param occurredAt Timestamp when error occurred
     * @param severity Error severity
     * @return Mono of number of rows inserted
     */
    @Query("INSERT INTO medical_report_error " +
           "(id, report_id, stage_id, stage, error_code, error_message, stack_trace, is_retryable, occurred_at, severity) " +
           "VALUES (:id, :reportId, :stageId, :stage, :errorCode, :errorMessage, :stackTrace, :isRetryable, :occurredAt, :severity)")
    Mono<Integer> insertError(
            String id,
            String reportId,
            String stageId,
            String stage,
            String errorCode,
            String errorMessage,
            String stackTrace,
            Boolean isRetryable,
            LocalDateTime occurredAt,
            String severity
    );

    /**
     * DTO for error pattern query result.
     */
    record ErrorPattern(
            String errorCode,
            String stage,
            String severity,
            Long errorCount,
            Long retryableCount
    ) {
        public Double getRetryablePercentage() {
            if (errorCount == null || errorCount == 0) {
                return 0.0;
            }
            return (double) (retryableCount != null ? retryableCount : 0) / errorCount * 100;
        }
    }

    /**
     * DTO for error count query result.
     */
    record ErrorCount(
            String errorCode,
            Long count
    ) {}
}
