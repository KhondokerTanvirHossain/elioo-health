package com.elioo.healthcare.medicalreport.application.service;

import com.elioo.healthcare.core.util.RetryUtil;
import com.elioo.healthcare.medicalreport.application.port.in.MedicalReportQueryUseCase;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort.*;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import com.elioo.healthcare.medicalreport.domain.MasterProcessingResponse;
import com.elioo.healthcare.medicalreport.domain.ProcessingStage;
import com.elioo.healthcare.medicalreport.domain.ProcessingStatus;
import com.elioo.healthcare.medicalreport.dto.ClassificationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of medical report query use case.
 * Provides read-only access to processing data.
 *
 * <p>Architecture: Application Service in Hexagonal Architecture</p>
 * <ul>
 *   <li>Implements inbound port (MedicalReportQueryUseCase)</li>
 *   <li>Depends on outbound port (MedicalReportPersistencePort)</li>
 *   <li>Contains query logic and data aggregation</li>
 *   <li>No direct dependencies on infrastructure</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalReportQueryService implements MedicalReportQueryUseCase {

    private final MedicalReportPersistencePort persistencePort;

    // ==================== Process Queries ====================

    @Override
    public Mono<ProcessingStatusSummary> getProcessingStatus(String reportId) {
        log.debug("Getting processing status for report: {}", reportId);

        return persistencePort.findProcessByReportId(reportId)
                .flatMap(process ->
                    persistencePort.findStagesByReportId(reportId)
                            .collectList()
                            .map(stages -> toProcessingStatusSummary(process, stages))
                )
                .doOnSuccess(summary -> log.info("Retrieved processing status for report: {} - Status: {} with {} stages",
                        reportId, summary.status(), summary.stages().size()));
    }

    @Override
    public Mono<ProcessingDetails> getProcessingDetails(String reportId) {
        log.debug("Getting complete processing details for report: {}", reportId);

        return persistencePort.findProcessByReportId(reportId)
                .flatMap(process -> {
                    Flux<StageRecord> stages = persistencePort.findStagesByReportId(reportId);
                    Flux<ErrorRecord> errors = persistencePort.findErrorsByReportId(reportId);
                    Flux<ResultRecord> results = persistencePort.findResultsByReportId(reportId);

                    // TODO: Reconstruct MasterProcessingResponse from database records
                    // For now, return null for response
                    return Mono.just(new ProcessingDetails(process, stages, errors, results, null));
                })
                .doOnSuccess(details -> log.info("Retrieved complete processing details for report: {}", reportId));
    }

    @Override
    public Flux<ProcessingStatusSummary> getPatientReports(String patientId) {
        log.debug("Getting all reports for patient: {}", patientId);

        return persistencePort.findProcessesByPatientId(patientId)
                .flatMap(process ->
                    persistencePort.findStagesByReportId(process.reportId())
                            .collectList()
                            .map(stages -> toProcessingStatusSummary(process, stages))
                )
                .doOnComplete(() -> log.info("Retrieved reports for patient: {}", patientId));
    }

    @Override
    public Flux<ProcessingStatusSummary> getRecentFailures(LocalDateTime since) {
        log.debug("Getting recent failures since: {}", since);

        // Use ProcessingStatus enum to filter failed statuses
        return Flux.concat(
                persistencePort.countProcessesByStatus(ProcessingStatus.FAILED)
                        .flatMapMany(count -> {
                            if (count > 0) {
                                // Find processes with FAILED status created after 'since'
                                // We need a custom query that filters by status and date
                                // For now, we'll get all processes and filter
                                return Flux.empty(); // TODO: Add query to persistence port
                            }
                            return Flux.empty();
                        })
        );
    }

    // ==================== Result Queries ====================

    @Override
    public Mono<ResultRecord> getOcrResults(String reportId) {
        log.debug("Getting OCR results for report: {}", reportId);
        return persistencePort.findResultByType(reportId, "OCR")
                .transform(RetryUtil.retryOnEmpty(3, Duration.ofMillis(500),
                    "OCR results for " + reportId));
    }

    @Override
    public Mono<ResultRecord> getClassificationResults(String reportId) {
        log.debug("Getting classification results for report: {}", reportId);
        Mono<ResultRecord> classificationMono = persistencePort.findResultByType(reportId, "CLASSIFICATION")
                .transform(RetryUtil.retryOnEmpty(3, Duration.ofMillis(500),
                    "Classification results for " + reportId));
        Mono<List<MedicalClassificationPort.MedicalCode>> icdMono = persistencePort.findResultByType(reportId, "ICD10")
                .map(ResultRecord::resultData)
                .filter(data -> data instanceof List<?>)
                .map(data -> (List<MedicalClassificationPort.MedicalCode>) data)
                .defaultIfEmpty(List.of());
        Mono<List<MedicalClassificationPort.MedicalCode>> rxMono = persistencePort.findResultByType(reportId, "RXNORM")
                .map(ResultRecord::resultData)
                .filter(data -> data instanceof List<?>)
                .map(data -> (List<MedicalClassificationPort.MedicalCode>) data)
                .defaultIfEmpty(List.of());

        return Mono.zip(classificationMono, icdMono, rxMono)
                .map(tuple -> enrichClassificationResult(tuple.getT1(), tuple.getT2(), tuple.getT3()))
                .switchIfEmpty(classificationMono);
    }

    @Override
    public Mono<ResultRecord> getIcd10Results(String reportId) {
        log.debug("Getting ICD10 results for report: {}", reportId);
        return persistencePort.findResultByType(reportId, "ICD10")
                .transform(RetryUtil.retryOnEmpty(3, Duration.ofMillis(500),
                    "ICD10 results for " + reportId));
    }

    @Override
    public Mono<ResultRecord> getRxNormResults(String reportId) {
        log.debug("Getting RXNORM results for report: {}", reportId);
        return persistencePort.findResultByType(reportId, "RXNORM")
                .transform(RetryUtil.retryOnEmpty(3, Duration.ofMillis(500),
                    "RxNorm results for " + reportId));
    }

    @Override
    public Mono<ResultRecord> getSnomedCtResults(String reportId) {
        log.debug("Getting SNOMEDCT results for report: {}", reportId);
        return persistencePort.findResultByType(reportId, "SNOMEDCT")
                .transform(RetryUtil.retryOnEmpty(3, Duration.ofMillis(500),
                    "SNOMED-CT results for " + reportId));
    }

    @Override
    public Mono<ResultRecord> getRiskAssessment(String reportId) {
        log.debug("Getting risk assessment for report: {}", reportId);
        return persistencePort.findResultByType(reportId, "RISK_ASSESSMENT")
                .transform(RetryUtil.retryOnEmpty(3, Duration.ofMillis(500),
                    "Risk assessment for " + reportId));
    }

    @Override
    public Mono<ResultRecord> getRecommendations(String reportId) {
        log.debug("Getting recommendations for report: {}", reportId);
        return persistencePort.findResultByType(reportId, "RECOMMENDATIONS")
                .transform(RetryUtil.retryOnEmpty(3, Duration.ofMillis(500),
                    "Recommendations for " + reportId));
    }

    @Override
    public Mono<ResultRecord> getEducationalContent(String reportId) {
        log.debug("Getting educational content for report: {}", reportId);
        return persistencePort.findResultByType(reportId, "EDUCATIONAL_CONTENT")
                .transform(RetryUtil.retryOnEmpty(3, Duration.ofMillis(500),
                    "Educational content for " + reportId));
    }

    @Override
    public Mono<ResultRecord> getClinicalInsights(String reportId) {
        log.debug("Getting clinical insights for report: {}", reportId);
        return persistencePort.findResultByType(reportId, "CLINICAL_INSIGHTS")
                .transform(RetryUtil.retryOnEmpty(3, Duration.ofMillis(500),
                    "Clinical insights for " + reportId));
    }

    @Override
    public Flux<ResultRecord> getAllResults(String reportId) {
        log.debug("Getting all results for report: {}", reportId);
        Mono<List<MedicalClassificationPort.MedicalCode>> icdMono = persistencePort.findResultByType(reportId, "ICD10")
                .map(ResultRecord::resultData)
                .filter(data -> data instanceof List<?>)
                .map(data -> (List<MedicalClassificationPort.MedicalCode>) data)
                .defaultIfEmpty(List.of());
        Mono<List<MedicalClassificationPort.MedicalCode>> rxMono = persistencePort.findResultByType(reportId, "RXNORM")
                .map(ResultRecord::resultData)
                .filter(data -> data instanceof List<?>)
                .map(data -> (List<MedicalClassificationPort.MedicalCode>) data)
                .defaultIfEmpty(List.of());

        return Mono.zip(icdMono, rxMono)
                .flatMapMany(codes -> persistencePort.findResultsByReportId(reportId)
                        .map(result -> {
                            if ("CLASSIFICATION".equals(result.resultType())) {
                                return enrichClassificationResult(result, codes.getT1(), codes.getT2());
                            }
                            return result;
                        }));
    }

    @Override
    public Flux<ResultRecord> getHighRiskReports() {
        log.debug("Getting high-risk reports");
        return persistencePort.findHighRiskReports();
    }

    // ==================== Error Queries ====================

    @Override
    public Flux<ErrorRecord> getReportErrors(String reportId) {
        log.debug("Getting errors for report: {}", reportId);
        return persistencePort.findErrorsByReportId(reportId);
    }

    @Override
    public Flux<ErrorRecord> getRecentErrors(LocalDateTime since) {
        log.debug("Getting recent errors since: {}", since);
        return persistencePort.findRecentErrors(since);
    }

    // ==================== Analytics Queries ====================

    @Override
    public Flux<StageStatistics> getStageStatistics(LocalDateTime since) {
        log.debug("Getting stage statistics since: {}", since);
        return persistencePort.getStageStatistics(since);
    }

    @Override
    public Flux<ErrorPattern> getErrorPatterns(LocalDateTime since) {
        log.debug("Getting error patterns since: {}", since);
        return persistencePort.getErrorPatterns(since);
    }

    @Override
    public Mono<ProcessingMetrics> getProcessingMetrics(LocalDateTime since) {
        log.debug("Calculating processing metrics since: {}", since);

        // Get counts for each status
        Mono<Long> totalMono = Mono.just(0L); // TODO: Add count all query
        Mono<Long> completedMono = persistencePort.countProcessesByStatus(ProcessingStatus.COMPLETED);
        Mono<Long> failedMono = persistencePort.countProcessesByStatus(ProcessingStatus.FAILED);
        Mono<Long> partialMono = persistencePort.countProcessesByStatus(ProcessingStatus.PARTIAL_SUCCESS);
        Mono<Double> avgTimeMono = persistencePort.getAverageProcessingTime(since);

        // Get stage statistics
        Flux<StageStatistics> stageStatsFlux = persistencePort.getStageStatistics(since);

        // Get error patterns
        Flux<ErrorPattern> errorPatternsFlux = persistencePort.getErrorPatterns(since);

        return Mono.zip(completedMono, failedMono, partialMono, avgTimeMono)
                .flatMap(tuple -> {
                    long completed = tuple.getT1();
                    long failed = tuple.getT2();
                    long partial = tuple.getT3();
                    double avgTime = tuple.getT4();
                    long total = completed + failed + partial;

                    double successRate = total > 0 ? ((double) (completed + partial) / total) * 100 : 0.0;

                    // Collect stage metrics
                    Mono<Map<ProcessingStage, StageMetrics>> stageMetricsMono = stageStatsFlux
                            .collectMap(
                                    StageStatistics::stage,
                                    stat -> new StageMetrics(
                                            stat.stage(),
                                            stat.totalCount(),
                                            stat.totalCount() - stat.failedCount(),
                                            stat.failedCount(),
                                            100.0 - (stat.failureRate() * 100),
                                            stat.avgDurationMs()
                                    )
                            );

                    // Collect error counts
                    Mono<Map<String, Long>> errorCountsMono = errorPatternsFlux
                            .collectMap(ErrorPattern::errorCode, ErrorPattern::errorCount);

                    return Mono.zip(stageMetricsMono, errorCountsMono)
                            .map(metricsData -> new ProcessingMetrics(
                                    total,
                                    completed,
                                    failed,
                                    partial,
                                    successRate,
                                    avgTime,
                                    metricsData.getT1(),
                                    metricsData.getT2()
                            ));
                })
                .doOnSuccess(metrics -> log.info("Calculated processing metrics: Total={}, Success Rate={:.2f}%",
                        metrics.totalProcessed(), metrics.successRate()));
    }

    // ==================== Helper Methods ====================

    private ResultRecord enrichClassificationResult(ResultRecord record,
                                                    List<MedicalClassificationPort.MedicalCode> icdCodes,
                                                    List<MedicalClassificationPort.MedicalCode> rxCodes) {
        if (record == null || record.resultData() == null) {
            return record;
        }

        ClassificationResponse classificationResponse = null;
        if (record.resultData() instanceof ClassificationResponse cr) {
            classificationResponse = cr;
        }

        if (classificationResponse == null) {
            return record;
        }

        ClassificationResponse.MedicalCodes medicalCodes = classificationResponse.getMedicalCodes() != null
                ? classificationResponse.getMedicalCodes()
                : new ClassificationResponse.MedicalCodes();

        if (medicalCodes.getICD10() == null || medicalCodes.getICD10().isEmpty()) {
            medicalCodes.setICD10(mapCodes(icdCodes));
        }
        if (medicalCodes.getSNOMED() == null || medicalCodes.getSNOMED().isEmpty()) {
            // No SNOMED inference yet
        }
        if (medicalCodes.getLOINC() == null || medicalCodes.getLOINC().isEmpty()) {
            // No LOINC inference yet
        }

        ClassificationResponse enriched = ClassificationResponse.builder()
                .reportId(classificationResponse.getReportId())
                .classificationResult(classificationResponse.getClassificationResult())
                .medicalCodes(medicalCodes)
                .processedAt(classificationResponse.getProcessedAt())
                .build();

        return new ResultRecord(
                record.id(),
                record.reportId(),
                record.resultType(),
                record.resultDataJson(),
                enriched,
                enriched,
                record.ocrResult(),
                record.suggestionsResult(),
                record.confidenceScore(),
                record.createdAt(),
                record.testCount(),
                record.entityCount(),
                record.codeCount(),
                record.riskLevel()
        );
    }

    private List<String> mapCodes(List<MedicalClassificationPort.MedicalCode> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return codes.stream()
                .map(MedicalClassificationPort.MedicalCode::code)
                .toList();
    }

    /**
     * Convert ProcessRecord to ProcessingStatusSummary.
     */
    private ProcessingStatusSummary toProcessingStatusSummary(ProcessRecord process, List<StageRecord> stageRecords) {
        List<MedicalReportQueryUseCase.StageStatus> stages = stageRecords.stream()
                .map(stage -> new MedicalReportQueryUseCase.StageStatus(
                        stage.stage().name(),
                        stage.status(),
                        stage.startedAt(),
                        stage.completedAt(),
                        stage.durationMs()
                ))
                .toList();

        return new ProcessingStatusSummary(
                process.reportId(),
                process.patientId(),
                process.status().name(),
                process.createdAt(),
                process.completedAt(),
                process.processingTimeMs(),
                process.completedStages(),
                process.failedStages(),
                process.totalStages(),
                process.errorMessage(),
                stages
        );
    }
}
