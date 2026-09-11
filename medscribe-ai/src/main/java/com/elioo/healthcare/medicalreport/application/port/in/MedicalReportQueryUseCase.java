package com.elioo.healthcare.medicalreport.application.port.in;

import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort.*;
import com.elioo.healthcare.medicalreport.domain.MasterProcessingResponse;
import com.elioo.healthcare.medicalreport.domain.ProcessingStage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Inbound port for querying medical report processing data.
 *
 * <p>Architecture: Inbound Port (Driving Port) in Hexagonal Architecture</p>
 * <ul>
 *   <li>Defines query operations for retrieving processing data</li>
 *   <li>Used by web handlers to provide query APIs</li>
 *   <li>Implemented by query service in application layer</li>
 * </ul>
 *
 * <p>Query Categories:</p>
 * <ul>
 *   <li>Process Queries - Get status and details of processing workflows</li>
 *   <li>Stage Queries - Get stage-level execution details</li>
 *   <li>Error Queries - Retrieve error information for debugging</li>
 *   <li>Result Queries - Get processing results and analysis</li>
 *   <li>Analytics Queries - Get aggregated statistics and patterns</li>
 *   <li>Patient Queries - Get all reports for a specific patient</li>
 * </ul>
 */
public interface MedicalReportQueryUseCase {

    // ==================== Process Queries ====================

    /**
     * Get processing status and summary for a report.
     *
     * @param reportId The report identifier
     * @return Mono of processing status summary
     */
    Mono<ProcessingStatusSummary> getProcessingStatus(String reportId);

    /**
     * Get complete processing details including all stages and results.
     *
     * @param reportId The report identifier
     * @return Mono of complete processing details
     */
    Mono<ProcessingDetails> getProcessingDetails(String reportId);

    /**
     * Get all reports for a specific patient.
     *
     * @param patientId The patient identifier
     * @return Flux of processing status summaries
     */
    Flux<ProcessingStatusSummary> getPatientReports(String patientId);

    /**
     * Get recent failed reports for monitoring.
     *
     * @param since Timestamp to search from
     * @return Flux of failed processing summaries
     */
    Flux<ProcessingStatusSummary> getRecentFailures(LocalDateTime since);

    // ==================== Result Queries ====================

    /**
     * Get OCR results for a report.
     *
     * @param reportId The report identifier
     * @return Mono of OCR result
     */
    Mono<ResultRecord> getOcrResults(String reportId);

    /**
     * Get classification results for a report.
     *
     * @param reportId The report identifier
     * @return Mono of classification result
     */
    Mono<ResultRecord> getClassificationResults(String reportId);

    /**
     * Get clinical insights for a report.
     *
     * @param reportId The report identifier
     * @return Mono of clinical insights result
     */
    Mono<ResultRecord> getClinicalInsights(String reportId);

    /**
     * Get ICD-10 code results for a report.
     *
     * @param reportId The report identifier
     * @return Mono of ICD-10 result
     */
    Mono<ResultRecord> getIcd10Results(String reportId);

    /**
     * Get RxNorm code results for a report.
     *
     * @param reportId The report identifier
     * @return Mono of RxNorm result
     */
    Mono<ResultRecord> getRxNormResults(String reportId);

    /**
     * Get SNOMED-CT code results for a report.
     *
     * @param reportId The report identifier
     * @return Mono of SNOMED-CT result
     */
    Mono<ResultRecord> getSnomedCtResults(String reportId);

    /**
     * Get risk assessment results for a report.
     *
     * @param reportId The report identifier
     * @return Mono of risk assessment result
     */
    Mono<ResultRecord> getRiskAssessment(String reportId);

    /**
     * Get recommendations for a report.
     *
     * @param reportId The report identifier
     * @return Mono of recommendations result
     */
    Mono<ResultRecord> getRecommendations(String reportId);

    /**
     * Get educational content for a report.
     *
     * @param reportId The report identifier
     * @return Mono of educational content result
     */
    Mono<ResultRecord> getEducationalContent(String reportId);

    /**
     * Get all results for a report.
     *
     * @param reportId The report identifier
     * @return Flux of all results
     */
    Flux<ResultRecord> getAllResults(String reportId);

    /**
     * Get high-risk reports for immediate attention.
     *
     * @return Flux of high-risk reports
     */
    Flux<ResultRecord> getHighRiskReports();

    // ==================== Error Queries ====================

    /**
     * Get all errors for a specific report.
     *
     * @param reportId The report identifier
     * @return Flux of error records
     */
    Flux<ErrorRecord> getReportErrors(String reportId);

    /**
     * Get recent errors for monitoring.
     *
     * @param since Timestamp to search from
     * @return Flux of recent errors
     */
    Flux<ErrorRecord> getRecentErrors(LocalDateTime since);

    // ==================== Analytics Queries ====================

    /**
     * Get stage performance statistics.
     *
     * @param since Timestamp to analyze from
     * @return Flux of stage statistics
     */
    Flux<StageStatistics> getStageStatistics(LocalDateTime since);

    /**
     * Get error patterns for debugging.
     *
     * @param since Timestamp to analyze from
     * @return Flux of error patterns
     */
    Flux<ErrorPattern> getErrorPatterns(LocalDateTime since);

    /**
     * Get processing metrics for monitoring dashboard.
     *
     * @param since Timestamp to calculate from
     * @return Mono of processing metrics
     */
    Mono<ProcessingMetrics> getProcessingMetrics(LocalDateTime since);

    // ==================== DTOs ====================

    /**
     * Processing status summary (lightweight for list views).
     */
    record ProcessingStatusSummary(
            String reportId,
            String patientId,
            String status,
            LocalDateTime createdAt,
            LocalDateTime completedAt,
            Long processingTimeMs,
            Integer completedStages,
            Integer failedStages,
            Integer totalStages,
            String errorMessage,
            List<StageStatus> stages
    ) {}

    /**
     * Individual stage status for progress tracking.
     */
    record StageStatus(
            String stageName,
            String status,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            Long durationMs
    ) {}

    /**
     * Complete processing details (includes stages, errors, results).
     */
    record ProcessingDetails(
            ProcessRecord process,
            Flux<StageRecord> stages,
            Flux<ErrorRecord> errors,
            Flux<ResultRecord> results,
            MasterProcessingResponse response
    ) {}

    /**
     * Processing metrics for monitoring dashboard.
     */
    record ProcessingMetrics(
            Long totalProcessed,
            Long completedCount,
            Long failedCount,
            Long partialSuccessCount,
            Double successRate,
            Double averageProcessingTimeMs,
            Map<ProcessingStage, StageMetrics> stageMetrics,
            Map<String, Long> errorCounts
    ) {}

    /**
     * Stage-level metrics.
     */
    record StageMetrics(
            ProcessingStage stage,
            Long totalCount,
            Long successCount,
            Long failedCount,
            Double successRate,
            Double avgDurationMs
    ) {}
}
