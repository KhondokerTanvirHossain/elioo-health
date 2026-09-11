package com.elioo.healthcare.medicalreport.adapter.out.persistence;

import com.elioo.healthcare.medicalreport.adapter.out.persistence.entity.MedicalReportErrorEntity;
import com.elioo.healthcare.medicalreport.adapter.out.persistence.entity.MedicalReportProcessEntity;
import com.elioo.healthcare.medicalreport.adapter.out.persistence.entity.MedicalReportProcessStageEntity;
import com.elioo.healthcare.medicalreport.adapter.out.persistence.entity.MedicalReportResultEntity;
import com.elioo.healthcare.medicalreport.adapter.out.persistence.repository.MedicalReportErrorRepository;
import com.elioo.healthcare.medicalreport.adapter.out.persistence.repository.MedicalReportProcessRepository;
import com.elioo.healthcare.medicalreport.adapter.out.persistence.repository.MedicalReportProcessStageRepository;
import com.elioo.healthcare.medicalreport.adapter.out.persistence.repository.MedicalReportResultRepository;
import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort;
import com.elioo.healthcare.medicalreport.domain.MasterProcessingRequest;
import com.elioo.healthcare.medicalreport.domain.MasterProcessingResponse;
import com.elioo.healthcare.medicalreport.domain.ProcessingStage;
import com.elioo.healthcare.medicalreport.domain.ProcessingStatus;
import com.elioo.healthcare.medicalreport.domain.TestResult;
import com.elioo.healthcare.medicalreport.domain.exception.OrchestrationException;
import com.elioo.healthcare.medicalreport.dto.ClassificationResponse;
import com.elioo.healthcare.medicalreport.dto.OcrResponse;
import com.elioo.healthcare.medicalreport.dto.SuggestionsResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence adapter for medical report processing.
 *
 * <p>Architecture: Outbound Adapter in Hexagonal Architecture</p>
 * <ul>
 *   <li>Implements {@link MedicalReportPersistencePort} interface</li>
 *   <li>Maps between domain objects and database entities</li>
 *   <li>Handles JSON serialization/deseriization</li>
 *   <li>Manages transactions with @Transactional</li>
 * </ul>
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Domain to Entity mapping</li>
 *   <li>Entity to Domain mapping</li>
 *   <li>JSON conversion (Object ↔ String)</li>
 *   <li>Database operations via repositories</li>
 *   <li>Error handling and logging</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MedicalReportPersistenceAdapter implements MedicalReportPersistencePort {

    private final MedicalReportProcessRepository processRepository;
    private final MedicalReportProcessStageRepository stageRepository;
    private final MedicalReportErrorRepository errorRepository;
    private final MedicalReportResultRepository resultRepository;
    private final ObjectMapper objectMapper;

    // ========================================
    // Process Management Implementation
    // ========================================

    @Override
    @Transactional
    public Mono<ProcessRecord> createProcess(MasterProcessingRequest request) {
        log.debug("Creating process record for patient: {}", request.getPatientContext().getPatientId());

        String reportId = generateReportId();
        String patientId = request.getPatientContext().getPatientId();
        String status = ProcessingStatus.PENDING.name();
        LocalDateTime now = LocalDateTime.now();
        String patientContextJson = toJson(request.getPatientContext());
        String workflowOptionsJson = toJson(request.getWorkflowOptions());
        String imageBase64 = request.getImageBase64();

        // Use custom insert with JSONB casting to avoid type mismatch error
        return processRepository.insertWithJsonbCast(
                reportId, patientId, status,
                now, now, null, null,
                imageBase64, patientContextJson, workflowOptionsJson,
                0, 0, 10, null, null
        )
        .then(processRepository.findById(reportId))
        .doOnSuccess(saved -> log.info("Created process record: {}", reportId))
        .map(this::toProcessRecord);
    }

    @Override
    @Transactional
    public Mono<ProcessRecord> updateProcessStatus(String reportId, ProcessingStatus status) {
        log.debug("Updating process status: {} -> {}", reportId, status);

        return processRepository.findById(reportId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Process not found: " + reportId)))
                .flatMap(entity -> {
                    LocalDateTime now = LocalDateTime.now();
                    // Use custom update with JSONB casting to avoid type mismatch error
                    return processRepository.updateWithJsonbCast(
                            reportId,
                            entity.getPatientId(),
                            status.name(),
                            now,
                            entity.getCompletedAt(),
                            entity.getProcessingTimeMs(),
                            entity.getPatientContextJson(),
                            entity.getWorkflowOptionsJson(),
                            entity.getCompletedStages(),
                            entity.getFailedStages(),
                            entity.getErrorMessage()
                    );
                })
                .then(processRepository.findById(reportId))
                .map(this::toProcessRecord);
    }

    @Override
    @Transactional
    public Mono<ProcessRecord> completeProcess(String reportId, MasterProcessingResponse response) {
        log.debug("Completing process: {}", reportId);

        return processRepository.findById(reportId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Process not found: " + reportId)))
                .flatMap(entity -> {
                    LocalDateTime now = LocalDateTime.now();
                    // Use custom update with JSONB casting to avoid type mismatch error
                    return processRepository.updateWithJsonbCast(
                            reportId,
                            entity.getPatientId(),
                            response.getProcessingStatus().name(),
                            now,
                            now, // completedAt
                            response.getProcessingTimeMs(),
                            entity.getPatientContextJson(),
                            entity.getWorkflowOptionsJson(),
                            response.getWorkflow().getCompletedStages().size(),
                            response.getWorkflow().getFailedStages().size(),
                            entity.getErrorMessage()
                    );
                })
                .then(processRepository.findById(reportId))
                .doOnSuccess(saved -> log.info("Completed process: {} with status: {}", reportId, response.getProcessingStatus()))
                .map(this::toProcessRecord);
    }

    @Override
    @Transactional
    public Mono<ProcessRecord> failProcess(String reportId, String errorMessage) {
        log.debug("Failing process: {} - {}", reportId, errorMessage);

        return processRepository.findById(reportId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Process not found: " + reportId)))
                .flatMap(entity -> {
                    LocalDateTime now = LocalDateTime.now();
                    Long processingTimeMs = Duration.between(entity.getCreatedAt(), now).toMillis();
                    // Use custom update with JSONB casting to avoid type mismatch error
                    return processRepository.updateWithJsonbCast(
                            reportId,
                            entity.getPatientId(),
                            ProcessingStatus.FAILED.name(),
                            now,
                            now, // completedAt
                            processingTimeMs,
                            entity.getPatientContextJson(),
                            entity.getWorkflowOptionsJson(),
                            entity.getCompletedStages(),
                            entity.getFailedStages(),
                            errorMessage
                    );
                })
                .then(processRepository.findById(reportId))
                .map(this::toProcessRecord);
    }

    @Override
    public Mono<ProcessRecord> findProcessByReportId(String reportId) {
        return processRepository.findById(reportId)
                .map(this::toProcessRecord);
    }

    @Override
    public Flux<ProcessRecord> findProcessesByPatientId(String patientId) {
        return processRepository.findByPatientId(patientId)
                .map(this::toProcessRecord);
    }

    // ========================================
    // Stage Management Implementation
    // ========================================

    @Override
    @Transactional
    public Mono<StageRecord> createStage(String reportId, ProcessingStage stage) {
        log.debug("Creating stage record: {} - {}", reportId, stage);

        String stageId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        // Use custom insert with JSONB casting
        // Note: started_at is set to current time even for PENDING status because it's NOT NULL in schema
        return stageRepository.insertWithJsonbCast(
                stageId,
                reportId,
                stage.name(),
                "PENDING",
                now,  // startedAt - required by NOT NULL constraint
                null, // completedAt
                null, // durationMs
                null, // inputDataJson
                null, // outputDataJson
                null, // errorMessage
                true, // isRetryable
                1,    // attemptNumber
                null, // confidenceScore
                null  // qualityMetricsJson
        )
        .then(stageRepository.findById(stageId))
        .map(this::toStageRecord);
    }

    @Override
    @Transactional
    public Mono<StageRecord> startStage(String stageId) {
        log.debug("Starting stage: {}", stageId);

        return stageRepository.findById(stageId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Stage not found: " + stageId)))
                .flatMap(entity -> {
                    // Use custom update with JSONB casting
                    return stageRepository.updateWithJsonbCast(
                            stageId,
                            "IN_PROGRESS",
                            null, // completedAt
                            null, // durationMs
                            entity.getOutputDataJson(),
                            entity.getErrorMessage(),
                            entity.getConfidenceScore(),
                            entity.getQualityMetricsJson()
                    );
                })
                .then(stageRepository.findById(stageId))
                .map(this::toStageRecord);
    }

    @Override
    @Transactional
    public Mono<StageRecord> completeStage(String stageId, Object outputData, Double confidenceScore) {
        log.debug("Completing stage: {}", stageId);

        return stageRepository.findById(stageId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Stage not found: " + stageId)))
                .flatMap(entity -> {
                    LocalDateTime now = LocalDateTime.now();
                    Long durationMs = entity.calculateDuration();
                    String outputDataJson = toJson(outputData);

                    // Use custom update with JSONB casting
                    return stageRepository.updateWithJsonbCast(
                            stageId,
                            "COMPLETED",
                            now, // completedAt
                            durationMs,
                            outputDataJson,
                            entity.getErrorMessage(),
                            confidenceScore,
                            entity.getQualityMetricsJson()
                    )
                    .then(Mono.just(entity.getReportId()));
                })
                .flatMap(reportId -> {
                    // Update process counters
                    return incrementCompletedStages(reportId)
                            .then(stageRepository.findById(stageId));
                })
                .map(this::toStageRecord);
    }

    @Override
    @Transactional
    public Mono<StageRecord> failStage(String stageId, String errorMessage, Boolean isRetryable) {
        log.debug("Failing stage: {} - {}", stageId, errorMessage);

        return stageRepository.findById(stageId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Stage not found: " + stageId)))
                .flatMap(entity -> {
                    LocalDateTime now = LocalDateTime.now();
                    Long durationMs = entity.calculateDuration();

                    // Use custom update with JSONB casting
                    return stageRepository.updateWithJsonbCast(
                            stageId,
                            "FAILED",
                            now, // completedAt
                            durationMs,
                            entity.getOutputDataJson(),
                            errorMessage,
                            entity.getConfidenceScore(),
                            entity.getQualityMetricsJson()
                    )
                    .then(Mono.just(entity.getReportId()));
                })
                .flatMap(reportId -> {
                    // Update process counters
                    return incrementFailedStages(reportId)
                            .then(stageRepository.findById(stageId));
                })
                .map(this::toStageRecord);
    }

    @Override
    public Flux<StageRecord> findStagesByReportId(String reportId) {
        return stageRepository.findByReportIdOrderByStartedAt(reportId)
                .map(this::toStageRecord);
    }

    @Override
    public Mono<StageRecord> findStageByReportIdAndStage(String reportId, ProcessingStage stage) {
        return stageRepository.findByReportIdAndStage(reportId, stage.name())
                .map(this::toStageRecord);
    }

    // ========================================
    // Error Management Implementation
    // ========================================

    @Override
    @Transactional
    public Mono<ErrorRecord> recordError(String reportId, ProcessingStage stage, Throwable error) {
        log.debug("Recording error for report: {} - stage: {}", reportId, stage);

        String errorCode = determineErrorCode(stage, error);
        String stackTrace = getStackTrace(error);
        boolean isRetryable = isRetryableError(error);

        return recordError(reportId, null, stage, errorCode, error.getMessage(),
                stackTrace, isRetryable, "ERROR");
    }

    @Override
    @Transactional
    public Mono<ErrorRecord> recordError(String reportId, String stageId, ProcessingStage stage,
                                          String errorCode, String errorMessage, String stackTrace,
                                          Boolean isRetryable, String severity) {
        String errorId = UUID.randomUUID().toString();
        String stageName = stage != null ? stage.name() : null;
        LocalDateTime occurredAt = LocalDateTime.now();

        // Use explicit INSERT to avoid "Row with Id does not exist" error
        return errorRepository.insertError(
                errorId,
                reportId,
                stageId,
                stageName,
                errorCode,
                errorMessage,
                stackTrace,
                isRetryable,
                occurredAt,
                severity
        )
        .then(errorRepository.findById(errorId))
        .doOnSuccess(saved -> log.info("Recorded error: {} - {}", errorCode, errorMessage))
        .map(this::toErrorRecord);
    }

    @Override
    public Flux<ErrorRecord> findErrorsByReportId(String reportId) {
        return errorRepository.findByReportIdOrderByOccurredAt(reportId)
                .map(this::toErrorRecord);
    }

    @Override
    public Flux<ErrorRecord> findRecentErrors(LocalDateTime since) {
        return errorRepository.findRecentErrors(since)
                .map(this::toErrorRecord);
    }

    // ========================================
    // Result Management Implementation
    // ========================================

    @Override
    @Transactional
    public Mono<ResultRecord> saveResult(String reportId, String resultType, Object resultData, Double confidenceScore) {
        return saveResult(reportId, resultType, resultData, confidenceScore, Map.of());
    }

    @Override
    @Transactional
    public Mono<ResultRecord> saveResult(String reportId, String resultType, Object resultData,
                                          Double confidenceScore, Map<String, Object> extractedFields) {
        log.info("Saving result: {} - {} - {}", reportId, resultType, resultData);

        String resultId = UUID.randomUUID().toString();
        String resultDataJson = toJson(resultData);
        LocalDateTime createdAt = LocalDateTime.now();
        Integer testCount = (Integer) extractedFields.get("testCount");
        Integer entityCount = (Integer) extractedFields.get("entityCount");
        Integer codeCount = (Integer) extractedFields.get("codeCount");
        String riskLevel = (String) extractedFields.get("riskLevel");

        // Use custom insert with JSONB casting
        return resultRepository.insertWithJsonbCast(
                resultId, reportId, resultType, resultDataJson, confidenceScore,
                createdAt, testCount, entityCount, codeCount, riskLevel
        )
        .then(resultRepository.findById(resultId))
        .doOnSuccess(saved -> log.info("Saved result: {} - {}", reportId, resultType))
        .map(this::toResultRecord);
    }

    @Override
    public Flux<ResultRecord> findResultsByReportId(String reportId) {
        return resultRepository.findByReportIdOrderByCreatedAt(reportId)
                .map(this::toResultRecord);
    }

    @Override
    public Mono<ResultRecord> findResultByType(String reportId, String resultType) {
        return resultRepository.findByReportIdAndResultType(reportId, resultType)
                .map(this::toResultRecord);
    }

    @Override
    public Flux<ResultRecord> findHighRiskReports() {
        return resultRepository.findHighRiskReports()
                .map(this::toResultRecord);
    }

    // ========================================
    // Analytics Implementation
    // ========================================

    @Override
    public Mono<Long> countProcessesByStatus(ProcessingStatus status) {
        return processRepository.countByStatus(status.name());
    }

    @Override
    public Mono<Double> getAverageProcessingTime(LocalDateTime since) {
        return processRepository.calculateAverageProcessingTime(since);
    }

    @Override
    public Flux<StageStatistics> getStageStatistics(LocalDateTime since) {
        return stageRepository.getStageStatistics(since)
                .map(stat -> new StageStatistics(
                        ProcessingStage.valueOf(stat.stage()),
                        stat.avgDuration(),
                        stat.totalCount(),
                        stat.failedCount(),
                        stat.getFailureRate()
                ));
    }

    @Override
    public Flux<ErrorPattern> getErrorPatterns(LocalDateTime since) {
        return errorRepository.getErrorPatterns(since)
                .map(pattern -> new ErrorPattern(
                        pattern.errorCode(),
                        pattern.stage() != null ? ProcessingStage.valueOf(pattern.stage()) : null,
                        pattern.severity(),
                        pattern.errorCount(),
                        pattern.retryableCount()
                ));
    }

    // ========================================
    // Helper Methods
    // ========================================

    private String generateReportId() {
        return "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String toJson(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON", e);
            return null;
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize JSON to object", e);
            return null;
        }
    }

    private Mono<Void> incrementCompletedStages(String reportId) {
        return processRepository.findById(reportId)
                .flatMap(entity -> {
                    Integer newCompletedStages = (entity.getCompletedStages() != null ? entity.getCompletedStages() : 0) + 1;
                    LocalDateTime now = LocalDateTime.now();

                    // Use custom update with JSONB casting
                    return processRepository.updateWithJsonbCast(
                            reportId,
                            entity.getPatientId(),
                            entity.getStatus(),
                            now,
                            entity.getCompletedAt(),
                            entity.getProcessingTimeMs(),
                            entity.getPatientContextJson(),
                            entity.getWorkflowOptionsJson(),
                            newCompletedStages,
                            entity.getFailedStages(),
                            entity.getErrorMessage()
                    );
                })
                .then();
    }

    private Mono<Void> incrementFailedStages(String reportId) {
        return processRepository.findById(reportId)
                .flatMap(entity -> {
                    Integer newFailedStages = (entity.getFailedStages() != null ? entity.getFailedStages() : 0) + 1;
                    LocalDateTime now = LocalDateTime.now();

                    // Use custom update with JSONB casting
                    return processRepository.updateWithJsonbCast(
                            reportId,
                            entity.getPatientId(),
                            entity.getStatus(),
                            now,
                            entity.getCompletedAt(),
                            entity.getProcessingTimeMs(),
                            entity.getPatientContextJson(),
                            entity.getWorkflowOptionsJson(),
                            entity.getCompletedStages(),
                            newFailedStages,
                            entity.getErrorMessage()
                    );
                })
                .then();
    }

    private String determineErrorCode(ProcessingStage stage, Throwable error) {
        if (error instanceof OrchestrationException oe) {
            return oe.getErrorCode();
        }
        return stage.name() + "_FAILED";
    }

    private String getStackTrace(Throwable error) {
        if (error == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(error.toString()).append("\n");
        for (StackTraceElement element : error.getStackTrace()) {
            sb.append("\tat ").append(element.toString()).append("\n");
        }
        if (error.getCause() != null) {
            sb.append("Caused by: ").append(getStackTrace(error.getCause()));
        }
        return sb.toString();
    }

    private boolean isRetryableError(Throwable error) {
        if (error instanceof OrchestrationException oe) {
            return oe.isRetryable();
        }
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("timeout") ||
               lowerMessage.contains("rate limit") ||
               lowerMessage.contains("throttl") ||
               lowerMessage.contains("unavailable");
    }

    // ========================================
    // Mapping Methods (Entity → Domain Record)
    // ========================================

    private ProcessRecord toProcessRecord(MedicalReportProcessEntity entity) {
        return new ProcessRecord(
                entity.getReportId(),
                entity.getPatientId(),
                ProcessingStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCompletedAt(),
                entity.getProcessingTimeMs(),
                entity.getCompletedStages(),
                entity.getFailedStages(),
                entity.getTotalStages(),
                entity.getErrorMessage(),
                entity.getImageBase64(),
                entity.getPatientContextJson(),
                entity.getWorkflowOptionsJson()
        );
    }

    private StageRecord toStageRecord(MedicalReportProcessStageEntity entity) {
        return new StageRecord(
                entity.getId(),
                entity.getReportId(),
                ProcessingStage.valueOf(entity.getStage()),
                entity.getStatus(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getDurationMs(),
                entity.getConfidenceScore(),
                entity.getErrorMessage()
        );
    }

    private ErrorRecord toErrorRecord(MedicalReportErrorEntity entity) {
        return new ErrorRecord(
                entity.getId(),
                entity.getReportId(),
                entity.getStageId(),
                entity.getStage() != null ? ProcessingStage.valueOf(entity.getStage()) : null,
                entity.getErrorCode(),
                entity.getErrorMessage(),
                entity.getIsRetryable(),
                entity.getOccurredAt(),
                entity.getSeverity()
        );
    }

    private ResultRecord toResultRecord(MedicalReportResultEntity entity) {
        Object resultData = deserializeResultData(entity.getResultType(), entity.getResultDataJson());

        return new ResultRecord(
                entity.getId(),
                entity.getReportId(),
                entity.getResultType(),
                entity.getResultDataJson(),
                resultData,
                resultData instanceof ClassificationResponse classification ? classification : null,
                resultData instanceof OcrResponse ocr ? ocr : null,
                resultData instanceof SuggestionsResponse suggestions ? suggestions : null,
                entity.getConfidenceScore(),
                entity.getCreatedAt(),
                entity.getTestCount(),
                entity.getEntityCount(),
                entity.getCodeCount(),
                entity.getRiskLevel()
        );
    }

    private Object deserializeResultData(String resultType, String resultDataJson) {
        if (resultDataJson == null || resultDataJson.isBlank()) {
            return null;
        }

        try {
            return switch (resultType) {
                case "OCR" -> deserializeOcrResult(resultDataJson);
                case "TRANSLATION" -> objectMapper.readValue(resultDataJson,
                        objectMapper.getTypeFactory().constructMapType(java.util.Map.class, String.class, Object.class));
                case "CLASSIFICATION" -> objectMapper.readValue(resultDataJson, ClassificationResponse.class);
                case "ICD10", "RXNORM" -> objectMapper.readValue(resultDataJson,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, MedicalClassificationPort.MedicalCode.class));
                case "CLINICAL_INSIGHTS" -> objectMapper.readValue(resultDataJson, SuggestionsResponse.class);
                case "RISK_ASSESSMENT" -> objectMapper.readValue(resultDataJson, ClinicalInsightPort.RiskAssessment.class);
                case "RECOMMENDATIONS" -> objectMapper.readValue(resultDataJson,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ClinicalInsightPort.Recommendation.class));
                case "EDUCATIONAL_CONTENT" -> objectMapper.readValue(resultDataJson, ClinicalInsightPort.EducationalContent.class);
                default -> {
                    log.warn("Unknown result type: {}, cannot deserialize", resultType);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.error("Failed to deserialize resultDataJson for type: {}", resultType, e);
            return null;
        }
    }

    private Object deserializeOcrResult(String resultDataJson) throws JsonProcessingException {
        try {
            return objectMapper.readValue(resultDataJson, OcrResponse.class);
        } catch (MismatchedInputException e) {
            List<TestResult> legacyData = objectMapper.readValue(resultDataJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, TestResult.class));
            log.warn("Deserialized legacy OCR result format (array of TestResult), wrapping into OcrResponse");
            return OcrResponse.builder()
                    .extractedData(legacyData)
                    .build();
        }
    }
}
