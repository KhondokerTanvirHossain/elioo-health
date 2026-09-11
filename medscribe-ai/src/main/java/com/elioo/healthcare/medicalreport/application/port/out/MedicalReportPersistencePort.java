package com.elioo.healthcare.medicalreport.application.port.out;

import com.elioo.healthcare.medicalreport.domain.MasterProcessingRequest;
import com.elioo.healthcare.medicalreport.domain.MasterProcessingResponse;
import com.elioo.healthcare.medicalreport.domain.ProcessingStage;
import com.elioo.healthcare.medicalreport.domain.ProcessingStatus;
import com.elioo.healthcare.medicalreport.dto.ClassificationResponse;
import com.elioo.healthcare.medicalreport.dto.OcrResponse;
import com.elioo.healthcare.medicalreport.dto.SuggestionsResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Outbound port for medical report persistence operations.
 *
 * <p>Architecture: Outbound Port (Driven Port) in Hexagonal Architecture</p>
 * <ul>
 *   <li>Defines contract for persistence operations</li>
 *   <li>Used by orchestration service to save processing data</li>
 *   <li>Implemented by persistence adapter</li>
 *   <li>Business layer depends on this interface, not implementation</li>
 * </ul>
 *
 * <p>Port Categories:</p>
 * <ul>
 *   <li>Process Management - Create, update, complete process records</li>
 *   <li>Stage Management - Track individual stage execution</li>
 *   <li>Error Management - Record and retrieve errors</li>
 *   <li>Result Management - Store and query results</li>
 *   <li>Analytics - Query aggregated data</li>
 * </ul>
 *
 * <p>Usage Pattern:</p>
 * <pre>
 * // In orchestration service
 * return persistencePort.createProcess(request)
 *     .flatMap(process -> {
 *         return persistencePort.createStage(reportId, ProcessingStage.OCR_PROCESSING)
 *             .flatMap(stage -> {
 *                 return persistencePort.startStage(stage.getId())
 *                     .then(performOcr(context))
 *                     .flatMap(result -> persistencePort.completeStage(stage.getId(), result, confidence));
 *             });
 *     });
 * </pre>
 */
public interface MedicalReportPersistencePort {

    // ========================================
    // Process Management Operations
    // ========================================

    /**
     * Create a new process record at the start of processing.
     *
     * @param request The master processing request
     * @return Mono of the created process with generated report ID
     */
    Mono<ProcessRecord> createProcess(MasterProcessingRequest request);

    /**
     * Update the status of a process.
     *
     * @param reportId The report identifier
     * @param status The new processing status
     * @return Mono of the updated process
     */
    Mono<ProcessRecord> updateProcessStatus(String reportId, ProcessingStatus status);

    /**
     * Mark a process as complete with final response data.
     *
     * @param reportId The report identifier
     * @param response The complete processing response
     * @return Mono of the completed process
     */
    Mono<ProcessRecord> completeProcess(String reportId, MasterProcessingResponse response);

    /**
     * Mark a process as failed with error message.
     *
     * @param reportId The report identifier
     * @param errorMessage The error message
     * @return Mono of the failed process
     */
    Mono<ProcessRecord> failProcess(String reportId, String errorMessage);

    /**
     * Find a process by report ID.
     *
     * @param reportId The report identifier
     * @return Mono of the process, or empty if not found
     */
    Mono<ProcessRecord> findProcessByReportId(String reportId);

    /**
     * Find all processes for a specific patient.
     *
     * @param patientId The patient identifier
     * @return Flux of processes for the patient
     */
    Flux<ProcessRecord> findProcessesByPatientId(String patientId);

    // ========================================
    // Stage Management Operations
    // ========================================

    /**
     * Create a new stage record before starting stage execution.
     *
     * @param reportId The report identifier
     * @param stage The processing stage type
     * @return Mono of the created stage with generated ID
     */
    Mono<StageRecord> createStage(String reportId, ProcessingStage stage);

    /**
     * Mark a stage as started (sets started_at timestamp).
     *
     * @param stageId The stage identifier
     * @return Mono of the started stage
     */
    Mono<StageRecord> startStage(String stageId);

    /**
     * Mark a stage as completed with output data.
     *
     * @param stageId The stage identifier
     * @param outputData The stage output data (will be serialized to JSON)
     * @param confidenceScore The confidence score (0.0 to 1.0)
     * @return Mono of the completed stage
     */
    Mono<StageRecord> completeStage(String stageId, Object outputData, Double confidenceScore);

    /**
     * Mark a stage as failed with error details.
     *
     * @param stageId The stage identifier
     * @param errorMessage The error message
     * @param isRetryable Whether the error is retryable
     * @return Mono of the failed stage
     */
    Mono<StageRecord> failStage(String stageId, String errorMessage, Boolean isRetryable);

    /**
     * Find all stages for a specific report.
     *
     * @param reportId The report identifier
     * @return Flux of stages ordered by start time
     */
    Flux<StageRecord> findStagesByReportId(String reportId);

    /**
     * Find a specific stage by report and stage type.
     *
     * @param reportId The report identifier
     * @param stage The processing stage type
     * @return Mono of the stage, or empty if not found
     */
    Mono<StageRecord> findStageByReportIdAndStage(String reportId, ProcessingStage stage);

    // ========================================
    // Error Management Operations
    // ========================================

    /**
     * Record an error that occurred during processing.
     *
     * @param reportId The report identifier
     * @param stage The processing stage where error occurred
     * @param error The exception/throwable
     * @return Mono of the created error record
     */
    Mono<ErrorRecord> recordError(String reportId, ProcessingStage stage, Throwable error);

    /**
     * Record an error with custom details.
     *
     * @param reportId The report identifier
     * @param stageId The stage identifier (nullable)
     * @param stage The processing stage
     * @param errorCode The error code
     * @param errorMessage The error message
     * @param stackTrace The stack trace
     * @param isRetryable Whether error is retryable
     * @param severity The error severity (ERROR, WARNING, INFO)
     * @return Mono of the created error record
     */
    Mono<ErrorRecord> recordError(String reportId, String stageId, ProcessingStage stage,
                                   String errorCode, String errorMessage, String stackTrace,
                                   Boolean isRetryable, String severity);

    /**
     * Find all errors for a specific report.
     *
     * @param reportId The report identifier
     * @return Flux of errors for the report
     */
    Flux<ErrorRecord> findErrorsByReportId(String reportId);

    /**
     * Find recent errors for monitoring.
     *
     * @param since The timestamp to search from
     * @return Flux of recent errors
     */
    Flux<ErrorRecord> findRecentErrors(LocalDateTime since);

    // ========================================
    // Result Management Operations
    // ========================================

    /**
     * Save a processing result.
     *
     * @param reportId The report identifier
     * @param resultType The result type (OCR, CLASSIFICATION, ICD10, etc.)
     * @param resultData The result data (will be serialized to JSON)
     * @param confidenceScore The confidence score (0.0 to 1.0)
     * @return Mono of the created result record
     */
    Mono<ResultRecord> saveResult(String reportId, String resultType, Object resultData, Double confidenceScore);

    /**
     * Save a result with extracted fields for fast querying.
     *
     * @param reportId The report identifier
     * @param resultType The result type
     * @param resultData The result data
     * @param confidenceScore The confidence score
     * @param extractedFields Additional fields (testCount, entityCount, riskLevel, etc.)
     * @return Mono of the created result record
     */
    Mono<ResultRecord> saveResult(String reportId, String resultType, Object resultData,
                                   Double confidenceScore, Map<String, Object> extractedFields);

    /**
     * Find all results for a specific report.
     *
     * @param reportId The report identifier
     * @return Flux of results for the report
     */
    Flux<ResultRecord> findResultsByReportId(String reportId);

    /**
     * Find a specific result by report and type.
     *
     * @param reportId The report identifier
     * @param resultType The result type
     * @return Mono of the result, or empty if not found
     */
    Mono<ResultRecord> findResultByType(String reportId, String resultType);

    /**
     * Find high-risk reports.
     *
     * @return Flux of high-risk result records
     */
    Flux<ResultRecord> findHighRiskReports();

    // ========================================
    // Analytics Operations
    // ========================================

    /**
     * Count processes by status.
     *
     * @param status The processing status
     * @return Mono of count
     */
    Mono<Long> countProcessesByStatus(ProcessingStatus status);

    /**
     * Calculate average processing time for completed reports.
     *
     * @param since The timestamp to calculate from
     * @return Mono of average processing time in milliseconds
     */
    Mono<Double> getAverageProcessingTime(LocalDateTime since);

    /**
     * Get stage statistics for performance monitoring.
     *
     * @param since The timestamp to analyze from
     * @return Flux of stage statistics
     */
    Flux<StageStatistics> getStageStatistics(LocalDateTime since);

    /**
     * Get error patterns for debugging.
     *
     * @param since The timestamp to analyze from
     * @return Flux of error patterns
     */
    Flux<ErrorPattern> getErrorPatterns(LocalDateTime since);

    // ========================================
    // Domain Records (DTOs returned by port)
    // ========================================

    /**
     * Process record returned by persistence operations.
     */
    record ProcessRecord(
            String reportId,
            String patientId,
            ProcessingStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime completedAt,
            Long processingTimeMs,
            Integer completedStages,
            Integer failedStages,
            Integer totalStages,
            String errorMessage,
            String imageBase64,
            String patientContextJson,
            String workflowOptionsJson
    ) {}

    /**
     * Stage record returned by persistence operations.
     */
    record StageRecord(
            String id,
            String reportId,
            ProcessingStage stage,
            String status,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            Long durationMs,
            Double confidenceScore,
            String errorMessage
    ) {}

    /**
     * Error record returned by persistence operations.
     */
    record ErrorRecord(
            String id,
            String reportId,
            String stageId,
            ProcessingStage stage,
            String errorCode,
            String errorMessage,
            Boolean isRetryable,
            LocalDateTime occurredAt,
            String severity
    ) {}

    /**
     * Result record returned by persistence operations.
     */
    record ResultRecord(
            String id,
            String reportId,
            String resultType,
            String resultDataJson,
            Object resultData,
            ClassificationResponse classificationResult,
            OcrResponse ocrResult,
            SuggestionsResponse suggestionsResult,
            Double confidenceScore,
            LocalDateTime createdAt,
            Integer testCount,
            Integer entityCount,
            Integer codeCount,
            String riskLevel
    ) {}

    /**
     * Stage statistics for analytics.
     */
    record StageStatistics(
            ProcessingStage stage,
            Double avgDurationMs,
            Long totalCount,
            Long failedCount,
            Double failureRate
    ) {}

    /**
     * Error pattern for analytics.
     */
    record ErrorPattern(
            String errorCode,
            ProcessingStage stage,
            String severity,
            Long errorCount,
            Long retryableCount
    ) {}
}
