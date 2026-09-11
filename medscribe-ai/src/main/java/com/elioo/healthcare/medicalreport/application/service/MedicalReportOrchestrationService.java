package com.elioo.healthcare.medicalreport.application.service;

import com.elioo.healthcare.medicalreport.application.port.in.MedicalReportOrchestrationUseCase;
import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalReportPersistencePort;
import com.elioo.healthcare.medicalreport.application.port.out.OcrPort;
import com.elioo.healthcare.medicalreport.application.port.out.TranslationPort;
import com.elioo.healthcare.medicalreport.domain.*;
import com.elioo.healthcare.medicalreport.domain.exception.OrchestrationException;
import com.elioo.healthcare.medicalreport.dto.ClassificationResponse;
import com.elioo.healthcare.medicalreport.dto.OcrResponse;
import com.elioo.healthcare.medicalreport.dto.SuggestionsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of master orchestration use case.
 * Coordinates the 10-step workflow for complete medical report processing.
 *
 * <p>Architecture: Application Service in Hexagonal Architecture</p>
 * <ul>
 *   <li>Implements inbound port ({@link MedicalReportOrchestrationUseCase})</li>
 *   <li>Depends on outbound ports (OcrPort, ClassificationPort, InsightPort)</li>
 *   <li>Contains business logic for workflow orchestration</li>
 *   <li>No direct dependencies on infrastructure (AWS, web, database)</li>
 * </ul>
 *
 * <p>Workflow Design:</p>
 * <ul>
 *   <li>Sequential execution for dependent stages</li>
 *   <li>Parallel execution where possible (ICD-10 + RxNorm)</li>
 *   <li>Fail-fast for critical stages</li>
 *   <li>Continue-on-error for non-critical stages</li>
 *   <li>Comprehensive error tracking and reporting</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalReportOrchestrationService implements MedicalReportOrchestrationUseCase {

    private final OcrPort ocrPort;
    private final TranslationPort translationPort;
    private final MedicalClassificationPort classificationPort;
    private final ClinicalInsightPort clinicalInsightPort;
    private final MedicalReportPersistencePort persistencePort;
    private final reactor.core.scheduler.Scheduler medicalReportScheduler;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private static final Duration WORKFLOW_TIMEOUT = Duration.ofMinutes(10);
    private static final int TOTAL_STAGES = 10;
    private static final int MAX_TEXT_LENGTH = 20000; // AWS Comprehend Medical limit

    @Override
    public Mono<MasterProcessingResponse> processCompleteMedicalReport(MasterProcessingRequest request) {
        log.info("Starting master orchestration for patient: {}", request.getPatientContext().getPatientId());

        LocalDateTime startTime = LocalDateTime.now();

        // 1️⃣ Create process record in database
        return persistencePort.createProcess(request)
                .doOnSuccess(process -> log.info("Created process record: {}", process.reportId()))
                .flatMap(processRecord -> {
                    // Initialize processing context with generated report ID
                    ProcessingContext context = ProcessingContext.builder()
                            .reportId(processRecord.reportId())
                            .request(request)
                            .startTime(startTime)
                            .build();

                    // 2️⃣ Update status to IN_PROGRESS
                    return persistencePort.updateProcessStatus(processRecord.reportId(), ProcessingStatus.IN_PROGRESS)
                            .then(Mono.just(context))
                            // 3️⃣ Execute workflow pipeline with persistence at each stage
                            .flatMap(this::validateImageWithPersistence)
                            .flatMap(this::performOcrWithPersistence)
                            .flatMap(this::translateTextWithPersistence)
                            .flatMap(this::detectEntitiesWithPersistence)
                            .flatMap(this::inferMedicalCodesWithPersistence)
                            .flatMap(this::generateClinicalInsightsWithPersistence)
                            // 4️⃣ Build response and save final results
                            .flatMap(ctx -> buildAndSaveResponse(ctx, startTime))
                            .timeout(WORKFLOW_TIMEOUT)
                            .doOnSuccess(response -> log.info("Master orchestration completed for report: {} in {}ms",
                                    processRecord.reportId(), response.getProcessingTimeMs()))
                            .doOnError(error -> log.error("Master orchestration failed for report: {}",
                                    processRecord.reportId(), error))
                            // 5️⃣ Handle errors and save to database
                            .onErrorResume(error -> handleWorkflowErrorWithPersistence(context, error, startTime));
                });
    }

    @Override
    public Flux<ProcessingProgress> processWithProgressUpdates(MasterProcessingRequest request) {
        // TODO Phase 3: Implement streaming progress updates
        log.warn("Progress streaming not yet implemented in Phase 2");
        return Flux.error(new UnsupportedOperationException(
                "Progress streaming will be implemented in Phase 3. " +
                "Use processCompleteMedicalReport() for now."));
    }

    // ==================== Background Processing Methods (Phase 2) ====================

    /**
     * Initiates medical report processing and returns report ID immediately.
     *
     * <p>This method performs quick validation and creates a database record,
     * then triggers background processing in a separate scheduler thread.
     * The HTTP response returns immediately with the report ID, allowing the
     * frontend to poll for status updates.
     *
     * <p><b>Execution Flow:</b>
     * <ol>
     *   <li>Quick validation (< 500ms) - format and size checks</li>
     *   <li>Create database process record with PENDING status</li>
     *   <li>Update status to IN_PROGRESS</li>
     *   <li>Trigger background processing via subscribeOn(medicalReportScheduler)</li>
     *   <li>Return report ID immediately (detached from background work)</li>
     * </ol>
     *
     * <p><b>Background Processing:</b> The full 10-stage workflow executes
     * in the background on the custom medical report scheduler. All results
     * are persisted to the database as each stage completes, allowing the
     * frontend to retrieve partial results via the query API.
     *
     * @param request the master processing request with image and patient context
     * @return Mono containing the report ID (resolves in < 1 second)
     * @throws com.elioo.healthcare.core.util.exception.AppException if quick validation fails
     */
    public Mono<String> initiateProcessing(MasterProcessingRequest request) {
        log.info("Initiating background processing for patient: {}", request.getPatientContext().getPatientId());

        // Step 1: Quick validation (< 500ms)
        return Mono.fromCallable(() -> quickValidate(request.getImageBase64()))
                .flatMap(valid -> {
                    if (!valid) {
                        return Mono.error(new IllegalArgumentException(
                                "Image validation failed: invalid format or size (max 10MB)"));
                    }

                    // Step 2: Create database record with PENDING status
                    return persistencePort.createProcess(request);
                })
                .flatMap(processRecord -> {
                    String reportId = processRecord.reportId();
                    log.info("Created process record: {}", reportId);

                    // Step 3: Update to IN_PROGRESS
                    return persistencePort.updateProcessStatus(reportId, ProcessingStatus.IN_PROGRESS)
                            .thenReturn(reportId);
                })
                .flatMap(reportId -> {
                    // Step 4: Trigger background processing (fire-and-forget)
                    executeBackgroundProcessing(reportId)
                            .subscribeOn(medicalReportScheduler)  // Move to background scheduler
                            .doOnSuccess(response -> log.info("Background processing completed for report: {}", reportId))
                            .doOnError(error -> log.error("Background processing failed for report: {}", reportId, error))
                            .subscribe();  // Detached subscription - doesn't block HTTP response

                    // Step 5: Return report ID immediately
                    log.info("Report ID {} returned to client, background processing initiated", reportId);
                    return Mono.just(reportId);
                });
    }

    /**
     * Executes the full 10-stage workflow in background.
     *
     * <p>This is the refactored version of the original processCompleteMedicalReport method.
     * It loads the process from the database, reconstructs the request, and runs the complete
     * workflow with persistence at each stage.
     *
     * <p><b>Difference from original:</b> This method is called from a background thread
     * (via subscribeOn) and loads data from the database rather than receiving it as a parameter.
     *
     * @param reportId the report ID to process
     * @return Mono containing the complete processing response
     */
    private Mono<MasterProcessingResponse> executeBackgroundProcessing(String reportId) {
        log.info("Starting background workflow execution for report: {}", reportId);

        LocalDateTime startTime = LocalDateTime.now();

        // Load process from database
        return persistencePort.findProcessByReportId(reportId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Process not found: " + reportId)))
                .flatMap(processRecord -> {
                    // Reconstruct request from stored data
                    MasterProcessingRequest request = reconstructRequestFromDB(processRecord);

                    // Build context
                    ProcessingContext context = ProcessingContext.builder()
                            .reportId(reportId)
                            .request(request)
                            .startTime(startTime)
                            .build();

                    // Execute existing workflow (same as original processCompleteMedicalReport)
                    return Mono.just(context)
                            .flatMap(this::validateImageWithPersistence)
                            .flatMap(this::performOcrWithPersistence)
                            .flatMap(this::translateTextWithPersistence)  // Translation step added
                            .flatMap(this::detectEntitiesWithPersistence)
                            .flatMap(this::inferMedicalCodesWithPersistence)
                            .flatMap(this::generateClinicalInsightsWithPersistence)
                            .flatMap(ctx -> buildAndSaveResponse(ctx, startTime))
                            .timeout(WORKFLOW_TIMEOUT)
                            .doOnSuccess(response -> log.info("Background workflow completed for report: {} in {}ms",
                                    reportId, response.getProcessingTimeMs()))
                            .doOnError(error -> log.error("Background workflow failed for report: {}", reportId, error))
                            .onErrorResume(error -> handleWorkflowErrorWithPersistence(context, error, startTime));
                });
    }

    /**
     * Quick validation before queuing background job.
     *
     * <p>Performs lightweight checks (< 500ms):
     * <ul>
     *   <li>Not null/empty</li>
     *   <li>Valid base64 format</li>
     *   <li>Size within limits (< 10MB)</li>
     * </ul>
     *
     * <p><b>Note:</b> Deep validation (image quality, readability) happens
     * in Stage 1 (IMAGE_VALIDATION) of the background workflow.
     *
     * @param imageBase64 the base64-encoded image data
     * @return true if validation passes, false otherwise
     */
    private boolean quickValidate(String imageBase64) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            log.warn("Quick validation failed: image is null or blank");
            return false;
        }

        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(imageBase64);
            if (decoded.length > 10 * 1024 * 1024) {  // 10MB limit
                log.warn("Quick validation failed: image size {} bytes exceeds 10MB limit", decoded.length);
                return false;
            }
            log.debug("Quick validation passed: image size {} bytes", decoded.length);
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("Quick validation failed: invalid base64 format - {}", e.getMessage());
            return false;
        }
    }

    /**
     * Reconstructs MasterProcessingRequest from database record.
     *
     * <p>The database stores the request data as JSONB fields. This method
     * deserializes those fields back into domain objects so the workflow
     * can execute with the original request context.
     *
     * @param processRecord the process record from database
     * @return reconstructed MasterProcessingRequest
     */
    private MasterProcessingRequest reconstructRequestFromDB(
            MedicalReportPersistencePort.ProcessRecord processRecord) {

        log.debug("Reconstructing request from database for report: {}", processRecord.reportId());

        MasterProcessingRequest request = new MasterProcessingRequest();
        request.setImageBase64(processRecord.imageBase64());
        request.setPatientContext(parseJson(processRecord.patientContextJson(), MasterProcessingRequest.PatientContext.class));
        request.setWorkflowOptions(parseJson(processRecord.workflowOptionsJson(), WorkflowOptions.class));

        return request;
    }

    /**
     * Parses JSON string to typed object.
     *
     * <p>Helper method for deserializing JSONB fields from database.
     * Returns null if JSON is null/empty or parsing fails.
     *
     * @param json the JSON string
     * @param clazz the target class type
     * @param <T> the type parameter
     * @return the deserialized object, or null if parsing fails
     */
    private <T> T parseJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("Failed to parse JSON to {}: {}", clazz.getSimpleName(), e.getMessage(), e);
            return null;
        }
    }

    // ==================== Workflow Stages with Persistence ====================

    /**
     * Stage 1: Validate image quality with database persistence.
     */
    private Mono<ProcessingContext> validateImageWithPersistence(ProcessingContext context) {
        return persistencePort.createStage(context.getReportId(), ProcessingStage.IMAGE_VALIDATION)
                .flatMap(stageRecord -> {
                    return persistencePort.startStage(stageRecord.id())
                            .then(validateImage(context))
                            .flatMap(ctx -> {
                                // Save success to database
                                return persistencePort.completeStage(
                                        stageRecord.id(),
                                        ctx.getImageValidation(),
                                        ctx.getImageValidation() != null ? ctx.getImageValidation().qualityScore() : null
                                ).thenReturn(ctx);
                            })
                            .onErrorResume(error -> {
                                // Save failure to database
                                return persistencePort.failStage(stageRecord.id(), error.getMessage(), isRetryableError(error))
                                        .then(persistencePort.recordError(context.getReportId(), ProcessingStage.IMAGE_VALIDATION, error))
                                        .then(Mono.error(error));
                            });
                });
    }

    /**
     * Stage 2: Perform OCR extraction with database persistence.
     */
    private Mono<ProcessingContext> performOcrWithPersistence(ProcessingContext context) {
        return persistencePort.createStage(context.getReportId(), ProcessingStage.OCR_PROCESSING)
                .flatMap(stageRecord -> {
                    return persistencePort.startStage(stageRecord.id())
                            .then(performOcr(context))
                            .flatMap(ctx -> {
                                // Save OCR results to database
                                Map<String, Object> ocrOutput = Map.of(
                                        "extractedData", ctx.getOcrExtractedData(),
                                        "rawText", ctx.getOcrRawText() != null ? ctx.getOcrRawText() : "",
                                        "testCount", ctx.getOcrExtractedData() != null ? ctx.getOcrExtractedData().size() : 0
                                );
                                return persistencePort.completeStage(stageRecord.id(), ocrOutput, ctx.getOcrConfidence())
                                        .then(persistencePort.saveResult(
                                                context.getReportId(),
                                                "OCR",
                                                OcrResponse.builder()
                                                        .reportId(context.getReportId())
                                                        .patientId(context.getRequest().getPatientContext().getPatientId())
                                                        .extractedData(ctx.getOcrExtractedData())
                                                        .rawText(ctx.getOcrRawText())
                                                        .confidence(ctx.getOcrConfidence())
                                                        .processedAt(LocalDateTime.now())
                                                        .build(),
                                                ctx.getOcrConfidence(),
                                                Map.of("testCount", ctx.getOcrExtractedData() != null ? ctx.getOcrExtractedData().size() : 0)
                                        ))
                                        .thenReturn(ctx);
                            })
                            .onErrorResume(error -> {
                                return persistencePort.failStage(stageRecord.id(), error.getMessage(), isRetryableError(error))
                                        .then(persistencePort.recordError(context.getReportId(), ProcessingStage.OCR_PROCESSING, error))
                                        .then(Mono.error(error));
                            });
                });
    }

    /**
     * Stage 2.5: Translate text with database persistence.
     */
    private Mono<ProcessingContext> translateTextWithPersistence(ProcessingContext context) {
        return persistencePort.createStage(context.getReportId(), ProcessingStage.TRANSLATION)
                .flatMap(stageRecord -> {
                    return persistencePort.startStage(stageRecord.id())
                            .then(translateText(context))
                            .flatMap(ctx -> {
                                // Save translation results to database
                                Map<String, Object> translationOutput = Map.of(
                                        "translatedRawText", ctx.getTranslatedRawText() != null ? ctx.getTranslatedRawText() : "",
                                        "originalLanguage", ctx.getOriginalLanguage() != null ? ctx.getOriginalLanguage() : "unknown",
                                        "wasTranslated", ctx.isWasTranslated()
                                );
                                return persistencePort.completeStage(stageRecord.id(), translationOutput, 1.0)
                                        .thenReturn(ctx);
                            })
                            .onErrorResume(error -> {
                                log.warn("[{}] Translation failed, continuing with original text. Error: {}",
                                        context.getReportId(), error.getMessage());
                                // Non-critical stage: continue with original text on failure
                                context.addWarning(ProcessingStage.TRANSLATION,
                                        "Translation failed: " + error.getMessage() + ". Using original text.");
                                return persistencePort.failStage(stageRecord.id(), error.getMessage(), isRetryableError(error))
                                        .then(persistencePort.recordError(context.getReportId(), ProcessingStage.TRANSLATION, error))
                                        .thenReturn(context); // Continue workflow
                            });
                });
    }

    /**
     * Stage 3: Detect medical entities with database persistence.
     */
    private Mono<ProcessingContext> detectEntitiesWithPersistence(ProcessingContext context) {
        return persistencePort.createStage(context.getReportId(), ProcessingStage.ENTITY_DETECTION)
                .flatMap(stageRecord -> {
                    return persistencePort.startStage(stageRecord.id())
                            .then(detectEntities(context))
                            .flatMap(ctx -> {
                                // Save classification results to database
                                return persistencePort.completeStage(
                                                stageRecord.id(),
                                                ctx.getClassificationResult(),
                                                ctx.getClassificationResult() != null ? ctx.getClassificationResult().overallConfidence() : null
                                        )
                                        .then(persistencePort.saveResult(
                                                context.getReportId(),
                                                "CLASSIFICATION",
                                                buildClassificationResponse(ctx),
                                                ctx.getClassificationResult() != null ? ctx.getClassificationResult().overallConfidence() : null,
                                                Map.of("entityCount", ctx.getClassificationResult() != null ? ctx.getClassificationResult().entities().size() : 0)
                                        ))
                                        .thenReturn(ctx);
                            })
                            .onErrorResume(error -> {
                                return persistencePort.failStage(stageRecord.id(), error.getMessage(), isRetryableError(error))
                                        .then(persistencePort.recordError(context.getReportId(), ProcessingStage.ENTITY_DETECTION, error))
                                        .then(Mono.error(error));
                            });
                });
    }

    /**
     * Stages 4-6: Infer medical codes with database persistence.
     * Creates stage records for each code inference so UI can track progress.
     */
    private Mono<ProcessingContext> inferMedicalCodesWithPersistence(ProcessingContext context) {
        WorkflowOptions options = getWorkflowOptions(context);
        List<String> requestedCodeSystems = options.getRequestedCodeSystems() != null
                ? options.getRequestedCodeSystems()
                : List.of("ICD10", "RXNORM", "SNOMEDCT");

        // Execute each code inference with its own stage record (sequentially for UI visibility)
        Mono<ProcessingContext> pipeline = Mono.just(context);

        // Stage 4: ICD-10 inference with persistence
        if (requestedCodeSystems.contains("ICD10")) {
            pipeline = pipeline.flatMap(ctx -> inferIcd10WithPersistence(ctx));
        }

        // Stage 5: RxNorm inference with persistence
        if (requestedCodeSystems.contains("RXNORM")) {
            pipeline = pipeline.flatMap(ctx -> inferRxNormWithPersistence(ctx));
        }

        // Stage 6: SNOMED-CT inference with persistence
        if (requestedCodeSystems.contains("SNOMEDCT")) {
            pipeline = pipeline.flatMap(ctx -> inferSnomedCtWithPersistence(ctx));
        }

        return pipeline;
    }

    /**
     * Stage 4: Infer ICD-10 codes with database stage tracking.
     */
    private Mono<ProcessingContext> inferIcd10WithPersistence(ProcessingContext context) {
        return persistencePort.createStage(context.getReportId(), ProcessingStage.ICD10_INFERENCE)
                .flatMap(stageRecord -> persistencePort.startStage(stageRecord.id())
                        .then(inferIcd10Codes(context))
                        .flatMap(codes -> {
                            context.setIcd10Codes(codes);
                            if (!codes.isEmpty()) {
                                return persistencePort.completeStage(stageRecord.id(), codes, calculateAverageCodeConfidence(codes))
                                        .then(persistencePort.saveResult(
                                                context.getReportId(),
                                                "ICD10",
                                                codes,
                                                calculateAverageCodeConfidence(codes),
                                                Map.of("codeCount", codes.size())
                                        ))
                                        .thenReturn(context);
                            } else {
                                return persistencePort.completeStage(stageRecord.id(), codes, null)
                                        .thenReturn(context);
                            }
                        })
                        .onErrorResume(error -> {
                            log.warn("[{}] ICD-10 inference failed: {}", context.getReportId(), error.getMessage());
                            return persistencePort.failStage(stageRecord.id(), error.getMessage(), isRetryableError(error))
                                    .thenReturn(context); // Non-critical, continue
                        }));
    }

    /**
     * Stage 5: Infer RxNorm codes with database stage tracking.
     */
    private Mono<ProcessingContext> inferRxNormWithPersistence(ProcessingContext context) {
        return persistencePort.createStage(context.getReportId(), ProcessingStage.RXNORM_INFERENCE)
                .flatMap(stageRecord -> persistencePort.startStage(stageRecord.id())
                        .then(inferRxNormCodes(context))
                        .flatMap(codes -> {
                            context.setRxnormCodes(codes);
                            if (!codes.isEmpty()) {
                                return persistencePort.completeStage(stageRecord.id(), codes, calculateAverageCodeConfidence(codes))
                                        .then(persistencePort.saveResult(
                                                context.getReportId(),
                                                "RXNORM",
                                                codes,
                                                calculateAverageCodeConfidence(codes),
                                                Map.of("codeCount", codes.size())
                                        ))
                                        .thenReturn(context);
                            } else {
                                return persistencePort.completeStage(stageRecord.id(), codes, null)
                                        .thenReturn(context);
                            }
                        })
                        .onErrorResume(error -> {
                            log.warn("[{}] RxNorm inference failed: {}", context.getReportId(), error.getMessage());
                            return persistencePort.failStage(stageRecord.id(), error.getMessage(), isRetryableError(error))
                                    .thenReturn(context); // Non-critical, continue
                        }));
    }

    /**
     * Stage 6: Infer SNOMED-CT codes with database stage tracking.
     */
    private Mono<ProcessingContext> inferSnomedCtWithPersistence(ProcessingContext context) {
        return persistencePort.createStage(context.getReportId(), ProcessingStage.SNOMEDCT_INFERENCE)
                .flatMap(stageRecord -> persistencePort.startStage(stageRecord.id())
                        .then(inferSnomedCtCodes(context))
                        .flatMap(codes -> {
                            context.setSnomedctCodes(codes);
                            if (!codes.isEmpty()) {
                                return persistencePort.completeStage(stageRecord.id(), codes, calculateAverageCodeConfidence(codes))
                                        .then(persistencePort.saveResult(
                                                context.getReportId(),
                                                "SNOMEDCT",
                                                codes,
                                                calculateAverageCodeConfidence(codes),
                                                Map.of("codeCount", codes.size())
                                        ))
                                        .thenReturn(context);
                            } else {
                                return persistencePort.completeStage(stageRecord.id(), codes, null)
                                        .thenReturn(context);
                            }
                        })
                        .onErrorResume(error -> {
                            log.warn("[{}] SNOMED-CT inference failed: {}", context.getReportId(), error.getMessage());
                            return persistencePort.failStage(stageRecord.id(), error.getMessage(), isRetryableError(error))
                                    .thenReturn(context); // Non-critical, continue
                        }));
    }

    /**
     * Stage 7+: Generate clinical insights with database persistence.
     * Includes: Clinical Insights, Patient Summary, Risk Assessment, Recommendations, Educational Content.
     */
    private Mono<ProcessingContext> generateClinicalInsightsWithPersistence(ProcessingContext context) {
        return persistencePort.createStage(context.getReportId(), ProcessingStage.CLINICAL_INSIGHTS)
                .flatMap(stageRecord -> {
                    return persistencePort.startStage(stageRecord.id())
                            .then(generateClinicalInsights(context))
                            .flatMap(ctx -> {
                                if (ctx.getClinicalInsights() != null) {
                                    // Save clinical insights
                                    ClinicalInsightPort.ClinicalInsightResult insights = ctx.getClinicalInsights();

                                    // Determine risk level
                                    String riskLevel = insights.riskAssessment() != null
                                            ? insights.riskAssessment().overallRisk()
                                            : "UNKNOWN";

                                    SuggestionsResponse suggestionsResponse = buildSuggestionsResponse(context, insights);

                                    return persistencePort.completeStage(stageRecord.id(), insights, null)
                                            .then(persistencePort.saveResult(
                                                    context.getReportId(),
                                                    "CLINICAL_INSIGHTS",
                                                    suggestionsResponse,
                                                    null,
                                                    Map.of("riskLevel", riskLevel)
                                            ))
                                            .then(saveRiskAssessment(context, insights.riskAssessment()))
                                            .then(saveRecommendations(context, insights.aiSuggestions()))
                                            .then(generateAndSaveEducationalContent(context, insights))
                                            .thenReturn(ctx);
                                } else {
                                    return persistencePort.failStage(stageRecord.id(), "No insights generated", false)
                                            .thenReturn(ctx);
                                }
                            })
                            .onErrorResume(error -> {
                                return persistencePort.failStage(stageRecord.id(), error.getMessage(), isRetryableError(error))
                                        .then(persistencePort.recordError(context.getReportId(), ProcessingStage.CLINICAL_INSIGHTS, error))
                                        .thenReturn(context); // Non-critical, return context
                            });
                });
    }

    /**
     * Save risk assessment as separate result.
     */
    private Mono<Void> saveRiskAssessment(ProcessingContext context, ClinicalInsightPort.RiskAssessment assessment) {
        if (assessment == null) return Mono.empty();

        String riskLevel = assessment.overallRisk() != null ? assessment.overallRisk() : "UNKNOWN";

        return persistencePort.saveResult(
                context.getReportId(),
                "RISK_ASSESSMENT",
                assessment,
                null,
                Map.of("riskLevel", riskLevel)
        ).then();
    }

    /**
     * Save recommendations as separate result.
     */
    private Mono<Void> saveRecommendations(ProcessingContext context, List<ClinicalInsightPort.Recommendation> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) return Mono.empty();

        return persistencePort.saveResult(
                context.getReportId(),
                "RECOMMENDATIONS",
                recommendations,
                null,
                Map.of("recommendationCount", recommendations.size())
        ).then();
    }

    /**
     * Save educational content as separate result.
     */
    private Mono<Void> saveEducationalContent(ProcessingContext context, ClinicalInsightPort.EducationalContent educationalContent) {
        if (educationalContent == null) return Mono.empty();

        return persistencePort.saveResult(
                context.getReportId(),
                "EDUCATIONAL_CONTENT",
                educationalContent,
                null,
                Map.of("topic", educationalContent.topic() != null ? educationalContent.topic() : "General Health")
        ).then();
    }

    /**
     * Generate educational content based on clinical insights and save it.
     * Topic is dynamically determined from highest-risk findings.
     */
    private Mono<Void> generateAndSaveEducationalContent(ProcessingContext context, ClinicalInsightPort.ClinicalInsightResult insights) {
        // Extract topic from highest-risk findings or summary
        final String topic = extractEducationalTopic(insights);

        // Generate educational content
        return clinicalInsightPort.generateEducationalContent(topic, "INTERMEDIATE")
                .flatMap(educationalContent -> {
                    log.info("[{}] Educational content generated for topic: {}", context.getReportId(), topic);
                    return saveEducationalContent(context, educationalContent);
                })
                .onErrorResume(error -> {
                    log.warn("[{}] Failed to generate educational content: {}",
                            context.getReportId(), error.getMessage());
                    // Non-critical - continue without educational content
                    return Mono.empty();
                });
    }

    /**
     * Extract the most relevant educational topic from clinical insights.
     * Prioritizes critical/high-risk findings.
     */
    private String extractEducationalTopic(ClinicalInsightPort.ClinicalInsightResult insights) {
        // 1. Try to get topic from critical/high severity findings
        if (insights.keyFindings() != null && !insights.keyFindings().isEmpty()) {
            // Find the highest severity finding
            var criticalFinding = insights.keyFindings().stream()
                    .filter(f -> "CRITICAL".equalsIgnoreCase(f.severity()) || "HIGH".equalsIgnoreCase(f.severity()))
                    .findFirst();

            if (criticalFinding.isPresent()) {
                String finding = criticalFinding.get().finding();
                if (finding != null && !finding.isBlank()) {
                    log.debug("Using critical/high finding as educational topic: {}", finding);
                    return finding;
                }
            }

            // If no critical/high findings, use the first finding
            var firstFinding = insights.keyFindings().stream().findFirst();
            if (firstFinding.isPresent() && firstFinding.get().finding() != null) {
                log.debug("Using first finding as educational topic: {}", firstFinding.get().finding());
                return firstFinding.get().finding();
            }
        }

        // 2. Try to extract from risk assessment
        if (insights.riskAssessment() != null && insights.riskAssessment().riskFactors() != null
                && !insights.riskAssessment().riskFactors().isEmpty()) {
            String firstRiskFactor = insights.riskAssessment().riskFactors().get(0);
            if (firstRiskFactor != null && !firstRiskFactor.isBlank()) {
                log.debug("Using risk factor as educational topic: {}", firstRiskFactor);
                return firstRiskFactor;
            }
        }

        // 3. Fallback to first sentence of summary
        if (insights.summary() != null && !insights.summary().isBlank()) {
            String summary = insights.summary();
            int periodIndex = summary.indexOf('.');
            if (periodIndex > 0 && periodIndex < 100) {
                String topic = summary.substring(0, periodIndex);
                log.debug("Using summary sentence as educational topic: {}", topic);
                return topic;
            }
        }

        // 4. Final fallback
        log.debug("Using default educational topic");
        return "Understanding Your Medical Report";
    }

    private ClassificationResponse buildClassificationResponse(ProcessingContext context) {
        if (context.getClassificationResult() == null) {
            return null;
        }

        MedicalClassificationPort.ClassificationResult result = context.getClassificationResult();

        List<ClassificationResponse.MedicalEntity> entities = result.entities() != null
                ? result.entities().stream()
                .map(entity -> ClassificationResponse.MedicalEntity.builder()
                        .Id(entity.id())
                        .Text(entity.text())
                        .Category(entity.category())
                        .Type(entity.type())
                        .Score(entity.score())
                        .Attributes(entity.attributes() != null
                                ? entity.attributes().stream()
                                .map(attr -> ClassificationResponse.EntityAttribute.builder()
                                        .Type(attr.type())
                                        .Score(attr.score())
                                        .RelationshipScore(attr.relationshipScore())
                                        .RelationshipType(null)
                                        .Id(attr.id())
                                        .Text(attr.text())
                                        .Category(null)
                                        .Traits(List.of())
                                        .build())
                                .toList()
                                : List.of())
                        .build())
                .toList()
                : List.of();

        Map<String, List<MedicalClassificationPort.MedicalCode>> codes = result.medicalCodes();
        List<String> icd10 = codes != null && codes.containsKey("ICD10")
                ? codes.get("ICD10").stream().map(MedicalClassificationPort.MedicalCode::code).toList()
                : List.of();
        List<String> loinc = codes != null && codes.containsKey("LOINC")
                ? codes.get("LOINC").stream().map(MedicalClassificationPort.MedicalCode::code).toList()
                : List.of();
        List<String> snomed = codes != null && codes.containsKey("SNOMED")
                ? codes.get("SNOMED").stream().map(MedicalClassificationPort.MedicalCode::code).toList()
                : List.of();

        ClassificationResponse.MedicalCodes medicalCodes = ClassificationResponse.MedicalCodes.builder()
                .ICD10(icd10)
                .LOINC(loinc)
                .SNOMED(snomed)
                .build();

        return ClassificationResponse.builder()
                .reportId(context.getReportId())
                .classificationResult(ClassificationResponse.ComprehendMedicalResult.builder()
                        .Entities(entities)
                        .build())
                .medicalCodes(medicalCodes)
                .processedAt(LocalDateTime.now())
                .build();
    }

    private SuggestionsResponse buildSuggestionsResponse(ProcessingContext context, ClinicalInsightPort.ClinicalInsightResult insights) {
        if (insights == null) {
            return null;
        }

        List<SuggestionsResponse.KeyFinding> keyFindings = insights.keyFindings() != null
                ? insights.keyFindings().stream()
                .map(kf -> SuggestionsResponse.KeyFinding.builder()
                        .finding(kf.finding())
                        .severity(parseSeverity(kf.severity()))
                        .interpretation(kf.interpretation())
                        .normalRange(null)
                        .build())
                .toList()
                : List.of();

        List<SuggestionsResponse.AiSuggestion> aiSuggestions = insights.aiSuggestions() != null
                ? insights.aiSuggestions().stream()
                .map(rec -> SuggestionsResponse.AiSuggestion.builder()
                        .category(parseSuggestionCategory(rec.category()))
                        .priority(parsePriority(rec.priority()))
                        .recommendation(rec.recommendation())
                        .rationale(rec.rationale())
                        .build())
                .toList()
                : List.of();

        Severity riskLevel = parseSeverity(insights.riskLevel());

        return SuggestionsResponse.builder()
                .reportId(context.getReportId())
                .summary(insights.summary())
                .keyFindings(keyFindings)
                .aiSuggestions(aiSuggestions)
                .insight(insights.summary())
                .riskLevel(riskLevel)
                .requiresImmediateAttention(insights.requiresImmediateAttention())
                .generatedAt(LocalDateTime.now())
                .confidenceScore(null)
                .build();
    }

    private Severity parseSeverity(String severity) {
        if (severity == null || severity.isBlank()) return null;
        try {
            return Severity.valueOf(severity.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private SuggestionCategory parseSuggestionCategory(String category) {
        if (category == null || category.isBlank()) return null;
        try {
            return SuggestionCategory.valueOf(category.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Priority parsePriority(String priority) {
        if (priority == null || priority.isBlank()) return null;
        try {
            return Priority.valueOf(priority.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Build response and save final results to database.
     */
    private Mono<MasterProcessingResponse> buildAndSaveResponse(ProcessingContext context, LocalDateTime startTime) {
        MasterProcessingResponse response = buildMasterResponse(context, startTime);

        return persistencePort.completeProcess(context.getReportId(), response)
                .doOnSuccess(processRecord -> log.info("[{}] Process completed successfully. Status: {}",
                        context.getReportId(), processRecord.status()))
                .thenReturn(response);
    }

    /**
     * Handle workflow error and save to database.
     */
    private Mono<MasterProcessingResponse> handleWorkflowErrorWithPersistence(
            ProcessingContext context, Throwable error, LocalDateTime startTime) {
        log.error("[{}] Workflow failed with error: {}", context.getReportId(), error.getMessage(), error);

        return persistencePort.failProcess(context.getReportId(), error.getMessage())
                .then(persistencePort.recordError(context.getReportId(), ProcessingStage.OCR_PROCESSING, error))
                .then(handleWorkflowError(context, error, startTime));
    }

    /**
     * Calculate average confidence score from medical codes.
     */
    private Double calculateAverageCodeConfidence(List<MedicalClassificationPort.MedicalCode> codes) {
        if (codes == null || codes.isEmpty()) return null;

        return codes.stream()
                .mapToDouble(MedicalClassificationPort.MedicalCode::score)
                .average()
                .orElse(0.0);
    }

    // ==================== Original Workflow Stages (Without Persistence) ====================

    /**
     * Stage 1: Validate image quality.
     * Critical stage - must succeed for workflow to continue.
     */
    private Mono<ProcessingContext> validateImage(ProcessingContext context) {
        log.debug("[{}] Stage 1: Validating image quality", context.getReportId());

        WorkflowOptions options = getWorkflowOptions(context);
        if (Boolean.TRUE.equals(options.getSkipValidation())) {
            log.info("[{}] Skipping image validation as requested", context.getReportId());
            context.markStageCompleted(ProcessingStage.IMAGE_VALIDATION);
            return Mono.just(context);
        }

        return ocrPort.validateImageQuality(context.getRequest().getImageBase64())
                .map(validationResult -> {
                    context.setImageValidation(validationResult);

                    if (!validationResult.isValid()) {
                        throw new OrchestrationException(
                                ProcessingStage.IMAGE_VALIDATION,
                                "Image validation failed: " + validationResult.reason()
                        );
                    }

                    if (validationResult.qualityScore() < 0.70) {
                        context.addWarning(
                                ProcessingStage.IMAGE_VALIDATION,
                                String.format("Image quality is low (%.2f). OCR results may be less accurate.",
                                        validationResult.qualityScore())
                        );
                    }

                    context.markStageCompleted(ProcessingStage.IMAGE_VALIDATION);
                    log.info("[{}] Image validation completed. Quality score: {}",
                            context.getReportId(), validationResult.qualityScore());
                    return context;
                })
                .onErrorResume(error -> handleCriticalStageError(
                        context, ProcessingStage.IMAGE_VALIDATION, error));
    }

    /**
     * Stage 2: Perform OCR extraction.
     * Critical stage - must succeed for workflow to continue.
     */
    private Mono<ProcessingContext> performOcr(ProcessingContext context) {
        log.debug("[{}] Stage 2: Performing OCR extraction", context.getReportId());

        WorkflowOptions options = getWorkflowOptions(context);
        Map<String, Object> processingOptions = new HashMap<>();
        processingOptions.put("language", options.getLanguage());
        processingOptions.put("includeRawText", options.getIncludeRawText());

        return ocrPort.extractMedicalData(
                        context.getRequest().getImageBase64(),
                        "BLOOD_TEST", // TODO: Make configurable based on report type
                        processingOptions
                )
                .collectList()
                .zipWith(ocrPort.extractRawText(
                        context.getRequest().getImageBase64(),
                        options.getLanguage()
                ))
                .map(tuple -> {
                    List<TestResult> extractedData = tuple.getT1();
                    String rawText = tuple.getT2();

                    if (extractedData.isEmpty()) {
                        throw new OrchestrationException(
                                ProcessingStage.OCR_PROCESSING,
                                "No medical data could be extracted from image. " +
                                "The image may not contain a medical report or the quality is too low."
                        );
                    }

                    context.setOcrExtractedData(extractedData);
                    context.setOcrRawText(rawText);
                    context.setOcrConfidence(calculateOverallConfidence(extractedData));

                    // Add warning if overall confidence is low
                    if (context.getOcrConfidence() < 0.80) {
                        context.addWarning(
                                ProcessingStage.OCR_PROCESSING,
                                String.format("OCR confidence is below 80%% (%.2f). Manual review recommended for critical values.",
                                        context.getOcrConfidence())
                        );
                    }

                    context.markStageCompleted(ProcessingStage.OCR_PROCESSING);
                    log.info("[{}] OCR completed. Extracted {} test results with {:.2f}% confidence",
                            context.getReportId(), extractedData.size(), context.getOcrConfidence() * 100);
                    return context;
                })
                .onErrorResume(error -> handleCriticalStageError(
                        context, ProcessingStage.OCR_PROCESSING, error));
    }

    /**
     * Stage 2.5: Translate non-English text to English.
     * Non-critical stage - workflow continues with original text on failure.
     */
    private Mono<ProcessingContext> translateText(ProcessingContext context) {
        log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());
        log.info("[{}] Stage 2.5: TRANSLATION - Starting translation stage", context.getReportId());
        log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());

        // Check if OCR text is empty
        if (context.getOcrRawText() == null || context.getOcrRawText().isBlank()) {
            log.warn("[{}] TRANSLATION: No OCR text to translate, skipping translation stage", context.getReportId());
            context.setTranslatedRawText(context.getOcrRawText());
            context.setTranslatedExtractedData(context.getOcrExtractedData());
            context.setWasTranslated(false);
            context.markStageCompleted(ProcessingStage.TRANSLATION);
            return Mono.just(context);
        }

        // Log OCR text details
        String ocrTextPreview = context.getOcrRawText().length() > 200
                ? context.getOcrRawText().substring(0, 200) + "..."
                : context.getOcrRawText();
        log.info("[{}] TRANSLATION: OCR text length={} chars", context.getReportId(), context.getOcrRawText().length());
        log.info("[{}] TRANSLATION: OCR text preview: \"{}\"", context.getReportId(), ocrTextPreview);
        log.info("[{}] TRANSLATION: Checking if text contains non-English content...", context.getReportId());

        // Check if text contains non-English content
        return translationPort.containsNonEnglish(context.getOcrRawText())
                .flatMap(hasNonEnglish -> {
                    if (!hasNonEnglish) {
                        // Text is already English, skip translation
                        log.info("[{}] TRANSLATION: 🇬🇧 Text is already ENGLISH - skipping translation", context.getReportId());
                        log.info("[{}] TRANSLATION: wasTranslated=false, originalLanguage=en", context.getReportId());
                        context.setTranslatedRawText(context.getOcrRawText());
                        context.setTranslatedExtractedData(context.getOcrExtractedData());
                        context.setOriginalLanguage("en");
                        context.setWasTranslated(false);
                        context.markStageCompleted(ProcessingStage.TRANSLATION);
                        return Mono.just(context);
                    }

                    log.info("[{}] TRANSLATION: 🌐 Non-English content DETECTED - proceeding with translation", context.getReportId());
                    log.info("[{}] TRANSLATION: Starting parallel translation of raw text and test results...", context.getReportId());

                    // Translate raw text and test results in parallel
                    Mono<String> translatedRawTextMono = translationPort.translateMixedText(context.getOcrRawText())
                            .doOnSubscribe(s -> log.info("[{}] TRANSLATION: Translating raw text ({} chars)...", context.getReportId(), context.getOcrRawText().length()));

                    int testResultCount = context.getOcrExtractedData() != null ? context.getOcrExtractedData().size() : 0;
                    Mono<List<TestResult>> translatedTestResultsMono = context.getOcrExtractedData() != null
                            ? translationPort.translateTestResults(context.getOcrExtractedData()).collectList()
                                    .doOnSubscribe(s -> log.info("[{}] TRANSLATION: Translating {} test results...", context.getReportId(), testResultCount))
                            : Mono.just(List.of());

                    return Mono.zip(translatedRawTextMono, translatedTestResultsMono)
                            .map(tuple -> {
                                String translatedRawText = tuple.getT1();
                                List<TestResult> translatedTestResults = tuple.getT2();

                                context.setTranslatedRawText(translatedRawText);
                                context.setTranslatedExtractedData(translatedTestResults);
                                context.setOriginalLanguage("bn"); // Assuming Bangla for now
                                context.setWasTranslated(true);
                                context.markStageCompleted(ProcessingStage.TRANSLATION);

                                // Log translation result details
                                String translatedPreview = translatedRawText.length() > 200
                                        ? translatedRawText.substring(0, 200) + "..."
                                        : translatedRawText;
                                log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());
                                log.info("[{}] TRANSLATION: ✅ COMPLETED SUCCESSFULLY", context.getReportId());
                                log.info("[{}] TRANSLATION: Raw text: {} chars → {} chars", context.getReportId(),
                                        context.getOcrRawText().length(), translatedRawText.length());
                                log.info("[{}] TRANSLATION: Test results translated: {}", context.getReportId(), translatedTestResults.size());
                                log.info("[{}] TRANSLATION: wasTranslated=true, originalLanguage=bn", context.getReportId());
                                log.info("[{}] TRANSLATION: Translated text preview: \"{}\"", context.getReportId(), translatedPreview);
                                log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());

                                return context;
                            });
                })
                .onErrorResume(error -> {
                    // Non-critical stage: continue with original text on failure
                    log.error("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());
                    log.error("[{}] TRANSLATION: ❌ FAILED - continuing with original text", context.getReportId());
                    log.error("[{}] TRANSLATION: Error: {}", context.getReportId(), error.getMessage(), error);
                    log.error("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());
                    context.setTranslatedRawText(context.getOcrRawText());
                    context.setTranslatedExtractedData(context.getOcrExtractedData());
                    context.setWasTranslated(false);
                    context.addWarning(ProcessingStage.TRANSLATION,
                            "Translation failed: " + error.getMessage() + ". Using original text.");
                    context.markStageCompleted(ProcessingStage.TRANSLATION);
                    return Mono.just(context);
                });
    }

    /**
     * Stage 3: Detect medical entities.
     * Critical stage - must succeed for workflow to continue.
     */
    private Mono<ProcessingContext> detectEntities(ProcessingContext context) {
        log.debug("[{}] Stage 3: Detecting medical entities", context.getReportId());

        WorkflowOptions options = getWorkflowOptions(context);

        // Use translated text if available, otherwise use original OCR text
        String textForClassification = context.getTranslatedRawText() != null
                ? context.getTranslatedRawText()
                : context.getOcrRawText();

        log.debug("[{}] Using {} text for entity detection",
                context.getReportId(),
                context.getTranslatedRawText() != null ? "translated" : "original");

        MedicalClassificationPort.ClassificationRequest classificationRequest =
                new MedicalClassificationPort.ClassificationRequest(
                        textForClassification,
                        "en", // Always use English after translation
                        options.getRequestedCodeSystems(),
                        options.getConfidenceThreshold(),
                        Boolean.TRUE.equals(options.getIncludeEntityRelationships()),
                        Map.of()
                );

        return classificationPort.classifyMedicalEntities(classificationRequest)
                .map(classificationResult -> {
                    context.setClassificationResult(classificationResult);
                    context.markStageCompleted(ProcessingStage.ENTITY_DETECTION);

                    log.info("[{}] Entity detection completed. Found {} entities with {:.2f}% confidence",
                            context.getReportId(),
                            classificationResult.entities().size(),
                            classificationResult.overallConfidence() * 100);

                    if (classificationResult.entities().isEmpty()) {
                        context.addWarning(
                                ProcessingStage.ENTITY_DETECTION,
                                "No medical entities detected. Text may not contain medical information."
                        );
                    }

                    return context;
                })
                .onErrorResume(error -> handleCriticalStageError(
                        context, ProcessingStage.ENTITY_DETECTION, error));
    }

    /**
     * Stages 4-6: Infer medical codes (ICD-10, RxNorm, and SNOMED-CT in parallel).
     * Non-critical stages - can fail with partial success.
     */
    private Mono<ProcessingContext> inferMedicalCodes(ProcessingContext context) {
        log.debug("[{}] Stages 4-6: Inferring medical codes (parallel)", context.getReportId());

        WorkflowOptions options = getWorkflowOptions(context);
        List<String> requestedCodeSystems = options.getRequestedCodeSystems() != null
                ? options.getRequestedCodeSystems()
                : List.of("ICD10", "RXNORM", "SNOMEDCT");

        // Parallel execution of code inference
        Mono<List<MedicalClassificationPort.MedicalCode>> icd10Mono =
                requestedCodeSystems.contains("ICD10")
                        ? inferIcd10Codes(context)
                        : Mono.just(List.of());

        Mono<List<MedicalClassificationPort.MedicalCode>> rxnormMono =
                requestedCodeSystems.contains("RXNORM")
                        ? inferRxNormCodes(context)
                        : Mono.just(List.of());

        Mono<List<MedicalClassificationPort.MedicalCode>> snomedctMono =
                requestedCodeSystems.contains("SNOMEDCT")
                        ? inferSnomedCtCodes(context)
                        : Mono.just(List.of());

        return Mono.zip(icd10Mono, rxnormMono, snomedctMono)
                .map(tuple -> {
                    context.setIcd10Codes(tuple.getT1());
                    context.setRxnormCodes(tuple.getT2());
                    context.setSnomedctCodes(tuple.getT3());

                    log.info("[{}] Medical code inference completed. ICD-10: {}, RxNorm: {}, SNOMED-CT: {}",
                            context.getReportId(), tuple.getT1().size(), tuple.getT2().size(), tuple.getT3().size());

                    return context;
                });
    }

    /**
     * Stage 4: Infer ICD-10 diagnosis codes.
     */
    private Mono<List<MedicalClassificationPort.MedicalCode>> inferIcd10Codes(ProcessingContext context) {
        log.debug("[{}] Stage 4: Inferring ICD-10 codes", context.getReportId());

        return classificationPort.mapToMedicalCodes(context.getOcrRawText(), List.of("ICD10"))
                .doOnSuccess(codes -> {
                    context.markStageCompleted(ProcessingStage.ICD10_INFERENCE);
                    log.info("[{}] ICD-10 inference completed. Found {} codes",
                            context.getReportId(), codes.size());
                })
                .onErrorResume(error -> handleNonCriticalError(
                        context, ProcessingStage.ICD10_INFERENCE, error));
    }

    /**
     * Stage 5: Infer RxNorm medication codes.
     */
    private Mono<List<MedicalClassificationPort.MedicalCode>> inferRxNormCodes(ProcessingContext context) {
        log.debug("[{}] Stage 5: Inferring RxNorm codes", context.getReportId());

        return classificationPort.mapToMedicalCodes(context.getOcrRawText(), List.of("RXNORM"))
                .doOnSuccess(codes -> {
                    context.markStageCompleted(ProcessingStage.RXNORM_INFERENCE);
                    log.info("[{}] RxNorm inference completed. Found {} codes",
                            context.getReportId(), codes.size());
                })
                .onErrorResume(error -> handleNonCriticalError(
                        context, ProcessingStage.RXNORM_INFERENCE, error));
    }

    /**
     * Stage 6: Infer SNOMED-CT codes.
     */
    private Mono<List<MedicalClassificationPort.MedicalCode>> inferSnomedCtCodes(ProcessingContext context) {
        log.debug("[{}] Stage 6: Inferring SNOMED-CT codes", context.getReportId());

        return classificationPort.mapToMedicalCodes(context.getOcrRawText(), List.of("SNOMEDCT"))
                .doOnSuccess(codes -> {
                    context.markStageCompleted(ProcessingStage.SNOMEDCT_INFERENCE);
                    log.info("[{}] SNOMED-CT inference completed. Found {} codes",
                            context.getReportId(), codes.size());
                })
                .onErrorResume(error -> handleNonCriticalError(
                        context, ProcessingStage.SNOMEDCT_INFERENCE, error));
    }

    /**
     * Stages 7-11: Generate clinical insights (summary, risk, recommendations, education).
     * Non-critical stages - can fail with partial success.
     */
    private Mono<ProcessingContext> generateClinicalInsights(ProcessingContext context) {
        log.debug("[{}] Stages 6-10: Generating clinical insights", context.getReportId());

        // Build insight request
        ClinicalInsightPort.InsightRequest insightRequest = buildInsightRequest(context);

        return clinicalInsightPort.generateClinicalInsights(insightRequest)
                .map(insightResult -> {
                    context.setClinicalInsights(insightResult);

                    // Mark all insight-related stages as completed
                    context.markStageCompleted(ProcessingStage.CLINICAL_INSIGHTS);
                    context.markStageCompleted(ProcessingStage.PATIENT_SUMMARY);
                    context.markStageCompleted(ProcessingStage.RISK_ASSESSMENT);
                    context.markStageCompleted(ProcessingStage.RECOMMENDATIONS);

                    if (insightResult.educationalContent() != null) {
                        context.markStageCompleted(ProcessingStage.EDUCATIONAL_CONTENT);
                    }

                    log.info("[{}] Clinical insights generated successfully. " +
                                    "Summary length: {} chars, Key findings: {}, Recommendations: {}",
                            context.getReportId(),
                            insightResult.summary() != null ? insightResult.summary().length() : 0,
                            insightResult.keyFindings() != null ? insightResult.keyFindings().size() : 0,
                            insightResult.aiSuggestions() != null ? insightResult.aiSuggestions().size() : 0);

                    return context;
                })
                .onErrorResume(error -> {
                    log.warn("[{}] Clinical insights generation failed: {}",
                            context.getReportId(), error.getMessage());
                    context.markStageFailed(ProcessingStage.CLINICAL_INSIGHTS,
                            error.getMessage(), isRetryableError(error));
                    return Mono.just(context);
                });
    }

    // ==================== Helper Methods ====================

    /**
     * Build clinical insight request from context.
     */
    private ClinicalInsightPort.InsightRequest buildInsightRequest(ProcessingContext context) {
        MasterProcessingRequest request = context.getRequest();
        WorkflowOptions options = getWorkflowOptions(context);

        // Convert domain PatientContext to ClinicalInsightPort.PatientContext
        ClinicalInsightPort.PatientContext patientContext = new ClinicalInsightPort.PatientContext(
                request.getPatientContext().getPatientId(),
                request.getPatientContext().getAge(),
                request.getPatientContext().getGender(),
                request.getPatientContext().getMedicalHistory(),
                request.getPatientContext().getCurrentMedications(),
                request.getPatientContext().getAllergies(),
                convertVitalSigns(request.getPatientContext().getVitalSigns()),
                request.getPatientContext().getLifestyle()
        );

        Map<String, Object> extractedData = buildSanitizedExtractedData(context);
        Map<String, Object> classificationResult = buildSanitizedClassificationResult(context);

        return new ClinicalInsightPort.InsightRequest(
                context.getReportId(),
                extractedData,
                classificationResult,
                patientContext,
                buildSummaryOptions(options),
                buildRiskOptions(options),
                Map.of()
        );
    }

    private ClinicalInsightPort.SummaryOptions buildSummaryOptions(WorkflowOptions options) {
        return new ClinicalInsightPort.SummaryOptions(
                options.getTargetAudience(),
                options.getLanguage(),
                Boolean.TRUE.equals(options.getIncludeEducationalContent()),
                "STANDARD"
        );
    }

    private ClinicalInsightPort.RiskAssessmentOptions buildRiskOptions(WorkflowOptions options) {
        return new ClinicalInsightPort.RiskAssessmentOptions(
                options.getRiskAssessmentCategories(),
                true,
                "12_MONTHS"
        );
    }

    private Map<String, Object> convertVitalSigns(Map<String, Object> vitalSigns) {
        // Already in correct format, just return as-is
        // The PatientContext now accepts Map<String, Object> for flexibility
        return vitalSigns != null ? vitalSigns : Map.of();
    }

    /**
     * Preprocess and sanitize OCR-extracted data for the LLM prompt.
     * - Deduplicate by normalized test name
     * - Keep only value/unit/status/referenceRange
     * - Drop confidence and nulls to shrink payload
     */
    private Map<String, Object> buildSanitizedExtractedData(ProcessingContext context) {
        if (context.getOcrExtractedData() == null || context.getOcrExtractedData().isEmpty()) {
            return Map.of();
        }

        Map<String, Map<String, Object>> compact = new LinkedHashMap<>();
        context.getOcrExtractedData().forEach(testResult -> {
            if (testResult.getTestName() == null || testResult.getTestValue() == null) return;

            String normalizedName = testResult.getTestName().trim().toLowerCase(Locale.ROOT);
            if (normalizedName.isEmpty()) return;

            // If duplicate appears, keep the first occurrence (order preserved)
            compact.putIfAbsent(normalizedName, Map.of(
                    "testName", testResult.getTestName().trim(),
                    "value", testResult.getTestValue(),
                    "unit", testResult.getUnit(),
                    "status", testResult.getStatus() != null ? testResult.getStatus().name() : "UNKNOWN",
                    "referenceRange", testResult.getReferenceRange() != null ? testResult.getReferenceRange() : ""
            ));
        });

        return Map.of("labs", new ArrayList<>(compact.values()));
    }

    /**
     * Preprocess classification output:
     * - Drop PHI categories
     * - Keep lean entity fields (text/category/type/attributes:text+type)
     * - Keep medical codes without scores
     */
    private Map<String, Object> buildSanitizedClassificationResult(ProcessingContext context) {
        if (context.getClassificationResult() == null) {
            return Map.of();
        }

        List<Map<String, Object>> sanitizedEntities = new ArrayList<>();
        context.getClassificationResult().entities().forEach(entity -> {
            if (entity.category() != null && entity.category().equalsIgnoreCase("PROTECTED_HEALTH_INFORMATION")) {
                return; // strip PHI
            }
            Map<String, Object> entityMap = new HashMap<>();
            entityMap.put("text", entity.text());
            entityMap.put("category", entity.category());
            entityMap.put("type", entity.type());

            if (entity.attributes() != null && !entity.attributes().isEmpty()) {
                List<Map<String, String>> attrs = entity.attributes().stream()
                        .filter(attr -> attr.text() != null && attr.type() != null)
                        .map(attr -> Map.of(
                                "type", attr.type(),
                                "text", attr.text()
                        ))
                        .toList();
                if (!attrs.isEmpty()) {
                    entityMap.put("attributes", attrs);
                }
            }

            sanitizedEntities.add(entityMap);
        });

        Map<String, List<MedicalClassificationPort.MedicalCode>> medicalCodes = context.getClassificationResult().medicalCodes();
        Map<String, Object> leanCodes = new HashMap<>();
        if (medicalCodes != null && !medicalCodes.isEmpty()) {
            medicalCodes.forEach((system, codes) -> {
                if (codes == null || codes.isEmpty()) return;
                List<Map<String, String>> leanList = codes.stream()
                        .map(code -> Map.of(
                                "code", code.code(),
                                "description", code.description(),
                                "system", code.codeSystem()
                        ))
                        .toList();
                leanCodes.put(system, leanList);
            });
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entities", sanitizedEntities);
        if (!leanCodes.isEmpty()) {
            result.put("medicalCodes", leanCodes);
        }
        return result;
    }

    /**
     * Build master response from context.
     */
    private MasterProcessingResponse buildMasterResponse(ProcessingContext context, LocalDateTime startTime) {
        long processingTimeMs = Duration.between(startTime, LocalDateTime.now()).toMillis();

        return MasterProcessingResponse.builder()
                .reportId(context.getReportId())
                .patientId(context.getRequest().getPatientContext().getPatientId())
                .processingStatus(context.getProcessingStatus())
                .processingTimeMs(processingTimeMs)
                .timestamp(LocalDateTime.now())
                .workflow(buildWorkflowSummary(context))
                .imageValidation(buildImageValidationResult(context))
                .ocrResults(buildOcrResults(context))
                .entityDetection(buildEntityDetectionResult(context))
                .medicalCodes(buildMedicalCodesResult(context))
                .clinicalInsights(buildClinicalInsightsResult(context))
                .metadata(buildMetadata())
                .errors(context.getErrors())
                .warnings(context.getWarnings())
                .build();
    }

    private MasterProcessingResponse.WorkflowSummary buildWorkflowSummary(ProcessingContext context) {
        return MasterProcessingResponse.WorkflowSummary.builder()
                .completedStages(context.getCompletedStages())
                .failedStages(context.getFailedStages())
                .skippedStages(List.of())
                .build();
    }

    private MasterProcessingResponse.ImageValidationResult buildImageValidationResult(ProcessingContext context) {
        if (context.getImageValidation() == null) return null;

        return MasterProcessingResponse.ImageValidationResult.builder()
                .isValid(context.getImageValidation().isValid())
                .qualityScore(context.getImageValidation().qualityScore())
                .message(context.getImageValidation().reason())
                .metrics(context.getImageValidation().metrics())
                .build();
    }

    private MasterProcessingResponse.OcrResults buildOcrResults(ProcessingContext context) {
        if (context.getOcrExtractedData() == null) return null;

        return MasterProcessingResponse.OcrResults.builder()
                .extractedData(context.getOcrExtractedData())
                .rawText(context.getOcrRawText())
                .overallConfidence(context.getOcrConfidence())
                .testCount(context.getOcrExtractedData().size())
                .build();
    }

    private MasterProcessingResponse.EntityDetectionResult buildEntityDetectionResult(ProcessingContext context) {
        if (context.getClassificationResult() == null) return null;

        MedicalClassificationPort.ClassificationResult result = context.getClassificationResult();
        return MasterProcessingResponse.EntityDetectionResult.builder()
                .entities(result.entities())
//                .relationships(result.relationships())
                .entityCount(result.entities().size())
//                .modelVersion(result.metadata().get("modelVersion") != null
//                        ? result.metadata().get("modelVersion").toString()
//                        : "unknown")
                .build();
    }

    private MasterProcessingResponse.MedicalCodesResult buildMedicalCodesResult(ProcessingContext context) {
        List<MasterProcessingResponse.MedicalCode> icd10 = convertMedicalCodes(
                context.getIcd10Codes(), "DIAGNOSIS");
        List<MasterProcessingResponse.MedicalCode> rxnorm = convertMedicalCodes(
                context.getRxnormCodes(), "MEDICATION");
        List<MasterProcessingResponse.MedicalCode> snomedct = convertMedicalCodes(
                context.getSnomedctCodes(), "CLINICAL_FINDING");

        return MasterProcessingResponse.MedicalCodesResult.builder()
                .icd10(icd10)
                .rxnorm(rxnorm)
                .snomedct(snomedct)
                .totalCodes(icd10.size() + rxnorm.size() + snomedct.size())
                .build();
    }

    private List<MasterProcessingResponse.MedicalCode> convertMedicalCodes(
            List<MedicalClassificationPort.MedicalCode> codes, String category) {
        if (codes == null) return List.of();

        return codes.stream()
                .map(code -> MasterProcessingResponse.MedicalCode.builder()
                        .code(code.code())
                        .description(code.description())
                        .score(code.score())
                        .category(category)
                        .build())
                .collect(Collectors.toList());
    }

    private MasterProcessingResponse.ClinicalInsightsResult buildClinicalInsightsResult(ProcessingContext context) {
        if (context.getClinicalInsights() == null) return null;

        ClinicalInsightPort.ClinicalInsightResult insights = context.getClinicalInsights();

        return MasterProcessingResponse.ClinicalInsightsResult.builder()
                .summary(insights.summary())
                .keyFindings(convertKeyFindings(insights.keyFindings()))
                .riskAssessment(convertRiskAssessment(insights.riskAssessment()))
                .recommendations(convertRecommendations(insights.aiSuggestions()))
                .actionPlan(convertActionPlan(insights.actionPlan()))
                .educationalContent(convertEducationalContent(insights.educationalContent()))
                .build();
    }

    private List<MasterProcessingResponse.KeyFinding> convertKeyFindings(
            List<ClinicalInsightPort.KeyFinding> findings) {
        if (findings == null) return List.of();

        return findings.stream()
                .map(finding -> MasterProcessingResponse.KeyFinding.builder()
                        .id(UUID.randomUUID().toString())
                        .finding(finding.finding())
                        .severity(finding.severity())
                        .interpretation(finding.interpretation())
                        .clinicalSignificance(finding.clinicalSignificance())
                        .relatedTests(finding.relatedTests())
                        .build())
                .collect(Collectors.toList());
    }

    private MasterProcessingResponse.RiskAssessment convertRiskAssessment(
            ClinicalInsightPort.RiskAssessment assessment) {
        if (assessment == null) return null;

        Map<String, MasterProcessingResponse.CategoryRisk> categoryRisks = new HashMap<>();
        if (assessment.categoryRisks() != null) {
            assessment.categoryRisks().forEach((category, risk) -> {
                categoryRisks.put(category, MasterProcessingResponse.CategoryRisk.builder()
                        .level(risk.level())
                        .score(risk.score())
                        .description(risk.description())
                        .contributors(risk.contributors())
                        .build());
            });
        }

        return MasterProcessingResponse.RiskAssessment.builder()
                .overallRiskLevel(assessment.overallRisk())
                .categoryRisks(categoryRisks)
                .riskFactors(assessment.riskFactors())
                .protectiveFactors(assessment.protectiveFactors())
                .overallAssessment(assessment.assessment())
                .requiresImmediateAttention(assessment.overallRisk() != null &&
                        (assessment.overallRisk().equals("HIGH") || assessment.overallRisk().equals("CRITICAL")))
                .build();
    }

    private List<MasterProcessingResponse.Recommendation> convertRecommendations(
            List<ClinicalInsightPort.Recommendation> recommendations) {
        if (recommendations == null) return List.of();

        return recommendations.stream()
                .map(rec -> MasterProcessingResponse.Recommendation.builder()
                        .id(UUID.randomUUID().toString())
                        .category(rec.category())
                        .priority(rec.priority())
                        .recommendation(rec.recommendation())
                        .rationale(rec.rationale())
                        .evidenceLevel(rec.evidenceLevel())
                        .timeframe(rec.timeframe())
                        .prerequisites(rec.prerequisites())
                        .build())
                .collect(Collectors.toList());
    }

    private MasterProcessingResponse.ActionPlan convertActionPlan(ClinicalInsightPort.ActionPlan plan) {
        if (plan == null) return null;

        return MasterProcessingResponse.ActionPlan.builder()
                .immediateActions(convertActions(plan.immediateActions()))
                .shortTermActions(convertActions(plan.shortTermActions()))
                .longTermActions(convertActions(plan.longTermActions()))
                .totalActions((plan.immediateActions() != null ? plan.immediateActions().size() : 0) +
                        (plan.shortTermActions() != null ? plan.shortTermActions().size() : 0) +
                        (plan.longTermActions() != null ? plan.longTermActions().size() : 0))
                .build();
    }

    private List<MasterProcessingResponse.Action> convertActions(List<ClinicalInsightPort.Action> actions) {
        if (actions == null) return List.of();

        return actions.stream()
                .map(action -> MasterProcessingResponse.Action.builder()
                        .action(action.action())
                        .priority(action.priority())
                        .timeframe(action.timeframe())
                        .category(action.category())
                        .status("PENDING")
                        .build())
                .collect(Collectors.toList());
    }

    private List<MasterProcessingResponse.EducationalContent> convertEducationalContent(
            ClinicalInsightPort.EducationalContent content) {
        if (content == null) return List.of();

        return List.of(MasterProcessingResponse.EducationalContent.builder()
                .topic(content.topic())
                .content(content.content())
                .keyPoints(content.keyPoints())
                .resources(content.resources())
                .build());
    }

    private MasterProcessingResponse.MetadataResult buildMetadata() {
        return MasterProcessingResponse.MetadataResult.builder()
                .apiVersion("1.0")
                .processingDate(LocalDateTime.now())
                .modelVersions(Map.of(
                        "textract", "1.0",
                        "comprehendMedical", "3.0.0",
                        "bedrock", "claude-3-5-sonnet-20241022-v2:0"
                ))
                .costs(Map.of(
                        "textract", "$0.015",
                        "comprehendMedical", "$0.012",
                        "bedrock", "$0.045",
                        "total", "$0.072"
                ))
                .build();
    }

    private Double calculateOverallConfidence(List<TestResult> testResults) {
        if (testResults == null || testResults.isEmpty()) return 0.0;

        return testResults.stream()
                .filter(tr -> tr.getConfidence() != null)
                .mapToDouble(TestResult::getConfidence)
                .average()
                .orElse(0.0);
    }

    private String generateReportId() {
        return "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private WorkflowOptions getWorkflowOptions(ProcessingContext context) {
        WorkflowOptions options = context.getRequest().getWorkflowOptions();
        return options != null ? options : new WorkflowOptions();
    }

    // ==================== Error Handling ====================

    /**
     * Handle critical stage error (must fail workflow).
     */
    private Mono<ProcessingContext> handleCriticalStageError(
            ProcessingContext context, ProcessingStage stage, Throwable error) {
        log.error("[{}] Critical stage {} failed: {}",
                context.getReportId(), stage, error.getMessage(), error);

        context.markStageFailed(stage, error.getMessage(), isRetryableError(error));

        return Mono.error(new OrchestrationException(stage, "Critical stage failed", error));
    }

    /**
     * Handle non-critical stage error (continue workflow with partial success).
     */
    private Mono<List<MedicalClassificationPort.MedicalCode>> handleNonCriticalError(
            ProcessingContext context, ProcessingStage stage, Throwable error) {
        log.warn("[{}] Non-critical stage {} failed: {}",
                context.getReportId(), stage, error.getMessage());

        context.markStageFailed(stage, error.getMessage(), isRetryableError(error));

        return Mono.just(List.of());
    }

    /**
     * Handle overall workflow error.
     */
    private Mono<MasterProcessingResponse> handleWorkflowError(
            ProcessingContext context, Throwable error, LocalDateTime startTime) {
        log.error("[{}] Workflow failed: {}", context.getReportId(), error.getMessage(), error);

        // If it's an orchestration exception, we already logged details
        if (!(error instanceof OrchestrationException)) {
            context.markStageFailed(ProcessingStage.OCR_PROCESSING,
                    "Unexpected error: " + error.getMessage(), false);
        }

        long processingTimeMs = Duration.between(startTime, LocalDateTime.now()).toMillis();

        return Mono.just(MasterProcessingResponse.builder()
                .reportId(context.getReportId())
                .patientId(context.getRequest().getPatientContext().getPatientId())
                .processingStatus(context.getProcessingStatus())
                .processingTimeMs(processingTimeMs)
                .timestamp(LocalDateTime.now())
                .workflow(buildWorkflowSummary(context))
                .imageValidation(buildImageValidationResult(context))
                .ocrResults(buildOcrResults(context))
                .entityDetection(buildEntityDetectionResult(context))
                .medicalCodes(buildMedicalCodesResult(context))
                .clinicalInsights(buildClinicalInsightsResult(context))
                .metadata(buildMetadata())
                .errors(context.getErrors())
                .warnings(context.getWarnings())
                .build());
    }

    /**
     * Check if error is retryable (transient).
     */
    private boolean isRetryableError(Throwable error) {
        String message = error.getMessage();
        if (message == null) return false;

        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("timeout") ||
                lowerMessage.contains("throttl") ||
                lowerMessage.contains("rate limit") ||
                lowerMessage.contains("unavailable") ||
                lowerMessage.contains("temporarily") ||
                lowerMessage.contains("connection");
    }

    // ==================== Multi-Image Processing Methods ====================

    /**
     * Initiates multi-image medical report processing and returns report ID immediately.
     *
     * <p>This method performs quick validation of all images and creates a database record,
     * then triggers background processing in a separate scheduler thread for the multi-image workflow.
     * The HTTP response returns immediately with the report ID, allowing the
     * frontend to poll for status updates.
     *
     * <p><b>Execution Flow:</b>
     * <ol>
     *   <li>Quick validation (< 500ms) - format and size checks for all images</li>
     *   <li>Create database process record with PENDING status</li>
     *   <li>Update status to IN_PROGRESS</li>
     *   <li>Trigger background multi-image processing via subscribeOn(medicalReportScheduler)</li>
     *   <li>Return report ID immediately (detached from background work)</li>
     * </ol>
     *
     * <p><b>Background Processing:</b> The 6-stage multi-image workflow executes
     * in the background on the custom medical report scheduler. All results
     * are persisted to the database as each stage completes, allowing the
     * frontend to retrieve partial results via the query API.
     *
     * @param multiImageRequest the multi-image request with images and patient context
     * @return Mono containing the report ID (resolves in < 1 second)
     * @throws IllegalArgumentException if quick validation fails
     */
    @Override
    public Mono<String> initiateMultiImageProcessing(com.elioo.healthcare.medicalreport.dto.MultiImageRequest multiImageRequest) {
        log.info("Initiating multi-image background processing for {} images, patient: {}",
                multiImageRequest.getImages().size(),
                multiImageRequest.getPatientContext().getPatientId());

        // Step 1: Quick validation of all images (< 500ms)
        return Mono.fromCallable(() -> {
            for (String image : multiImageRequest.getImages()) {
                if (!quickValidate(image)) {
                    throw new IllegalArgumentException(
                            "Image validation failed: invalid format or size (max 10MB per image)");
                }
            }
            return true;
        })
                .flatMap(valid -> {
                    // Step 2: Create database record with PENDING status
                    // Convert MultiImageRequest to MasterProcessingRequest for persistence
                    MasterProcessingRequest masterRequest = convertToMasterRequest(multiImageRequest);
                    return persistencePort.createProcess(masterRequest);
                })
                .flatMap(processRecord -> {
                    String reportId = processRecord.reportId();
                    log.info("Created multi-image process record: {}", reportId);

                    // Step 3: Update to IN_PROGRESS
                    return persistencePort.updateProcessStatus(reportId, ProcessingStatus.IN_PROGRESS)
                            .thenReturn(reportId);
                })
                .flatMap(reportId -> {
                    // Step 4: Trigger background multi-image processing (fire-and-forget)
                    executeBackgroundMultiImageProcessing(reportId)
                            .subscribeOn(medicalReportScheduler)  // Move to background scheduler
                            .doOnSuccess(response -> log.info("Multi-image background processing completed for report: {}", reportId))
                            .doOnError(error -> log.error("Multi-image background processing failed for report: {}", reportId, error))
                            .subscribe();  // Detached subscription - doesn't block HTTP response

                    // Step 5: Return report ID immediately
                    log.info("Report ID {} returned to client, multi-image background processing initiated", reportId);
                    return Mono.just(reportId);
                });
    }

    /**
     * Executes the full 6-stage multi-image workflow in background.
     *
     * <p>This method loads the process from the database, reconstructs the multi-image request,
     * and runs the complete multi-image workflow with persistence at each stage.
     *
     * @param reportId the report ID to process
     * @return Mono containing the complete processing response
     */
    private Mono<MasterProcessingResponse> executeBackgroundMultiImageProcessing(String reportId) {
        log.info("Starting multi-image background workflow execution for report: {}", reportId);

        // Load process from database
        return persistencePort.findProcessByReportId(reportId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Process not found: " + reportId)))
                .flatMap(processRecord -> {
                    // Reconstruct multi-image request from stored data
                    com.elioo.healthcare.medicalreport.dto.MultiImageRequest multiImageRequest =
                            reconstructMultiImageRequestFromDB(processRecord);

                    // Execute multi-image workflow WITH PERSISTENCE
                    return processMultipleImagesWithPersistence(reportId, multiImageRequest)
                            .doOnSuccess(response -> log.info("Multi-image background workflow completed for report: {} in {}ms",
                                    reportId, response.getProcessingTimeMs()))
                            .doOnError(error -> {
                                log.error("Multi-image background workflow failed for report: {}", reportId, error);
                                // Save error to database
                                persistencePort.updateProcessStatus(reportId, ProcessingStatus.FAILED)
                                        .subscribe();
                            });
                });
    }

    /**
     * Convert MultiImageRequest to MasterProcessingRequest for persistence.
     * Stores all images as a JSON array in the imageBase64 field.
     *
     * <p>Note: Current database schema only supports single imageBase64 TEXT field.
     * Multi-image data is stored as a JSON array string in this field.
     * The format is: ["image1_base64", "image2_base64", "image3_base64"]</p>
     */
    private MasterProcessingRequest convertToMasterRequest(com.elioo.healthcare.medicalreport.dto.MultiImageRequest multiImageRequest) {
        try {
            // Serialize list of images to JSON array string
            String imagesJson = objectMapper.writeValueAsString(multiImageRequest.getImages());

            return MasterProcessingRequest.builder()
                    .imageBase64(imagesJson)  // Store all images as JSON array
                    .patientContext(multiImageRequest.getPatientContext())
                    .workflowOptions(multiImageRequest.getWorkflowOptions())
                    .build();
        } catch (Exception e) {
            log.error("Failed to serialize images to JSON", e);
            throw new IllegalArgumentException("Failed to serialize multi-image request: " + e.getMessage(), e);
        }
    }

    /**
     * Reconstruct MultiImageRequest from database process record.
     * Deserializes JSON array from imageBase64 field back to list of images.
     */
    private com.elioo.healthcare.medicalreport.dto.MultiImageRequest reconstructMultiImageRequestFromDB(
            MedicalReportPersistencePort.ProcessRecord processRecord) {
        try {
            MasterProcessingRequest masterRequest = reconstructRequestFromDB(processRecord);

            // Deserialize JSON array string back to list of images
            String imagesJson = masterRequest.getImageBase64();
            List<String> images;

            if (imagesJson.startsWith("[")) {
                // Multi-image format (JSON array)
                images = objectMapper.readValue(imagesJson,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            } else {
                // Single image format (backward compatibility)
                images = List.of(imagesJson);
            }

            return com.elioo.healthcare.medicalreport.dto.MultiImageRequest.builder()
                    .images(images)
                    .patientContext(masterRequest.getPatientContext())
                    .workflowOptions(masterRequest.getWorkflowOptions())
                    .build();
        } catch (Exception e) {
            log.error("Failed to deserialize images from JSON", e);
            throw new IllegalArgumentException("Failed to reconstruct multi-image request: " + e.getMessage(), e);
        }
    }

    /**
     * Process multiple medical report images as a single cohesive analysis (Internal/Synchronous).
     *
     * <p><b>INTERNAL USE ONLY</b> - This method is used by the async background processor.
     * External callers should use {@link #initiateMultiImageProcessing} instead.</p>
     *
     * <p>Workflow:</p>
     * <ol>
     *   <li>Validate all images in parallel (quick check)</li>
     *   <li>OCR all images in parallel</li>
     *   <li>Concatenate raw text from all OCR results</li>
     *   <li>Validate total text length does not exceed AWS Comprehend Medical limit (20,000 chars)</li>
     *   <li>Merge test results using confidence-based deduplication</li>
     *   <li>Run classification on concatenated text</li>
     *   <li>Generate clinical insights on merged results</li>
     * </ol>
     *
     * <p>Error Handling:</p>
     * <ul>
     *   <li>If ALL images fail validation or OCR: workflow fails</li>
     *   <li>If SOME images fail: continues with partial results, includes warnings</li>
     *   <li>If concatenated text exceeds 20K chars: fails fast with clear error</li>
     * </ul>
     *
     * @param multiImageRequest Request with multiple images and patient context
     * @return Mono of aggregated processing response
     */
    @Override
    public Mono<MasterProcessingResponse> processMultipleImages(com.elioo.healthcare.medicalreport.dto.MultiImageRequest multiImageRequest) {
        log.info("Starting multi-image processing for {} images, patient: {}",
                multiImageRequest.getImages().size(),
                multiImageRequest.getPatientContext().getPatientId());

        LocalDateTime startTime = LocalDateTime.now();
        String reportId = generateReportId();

        MultiImageProcessingContext context = MultiImageProcessingContext.builder()
                .reportId(reportId)
                .totalImages(multiImageRequest.getImages().size())
                .imageOcrResults(new ArrayList<>())
                .build();

        return Mono.just(context)
                .flatMap(ctx -> validateAllImagesInParallel(ctx, multiImageRequest.getImages()))
                .flatMap(ctx -> ocrAllImagesInParallel(ctx, multiImageRequest))
                .flatMap(this::validateTextLengthLimit)
                .flatMap(this::mergeTestResults)
                .flatMap(ctx -> runClassificationOnMergedData(ctx, multiImageRequest))
                .flatMap(ctx -> generateInsightsOnMergedData(ctx, multiImageRequest))
                .flatMap(ctx -> buildMultiImageResponse(ctx, startTime, multiImageRequest))
                .timeout(WORKFLOW_TIMEOUT)
                .doOnSuccess(response -> log.info("Multi-image processing completed for report: {} in {}ms",
                        reportId, response.getProcessingTimeMs()))
                .onErrorResume(error -> handleMultiImageError(context, error, startTime, multiImageRequest));
    }

    /**
     * Process multiple medical report images with database persistence (used by background processing).
     *
     * @param reportId The report ID from the database
     * @param multiImageRequest Request with multiple images and patient context
     * @return Mono of aggregated processing response
     */
    private Mono<MasterProcessingResponse> processMultipleImagesWithPersistence(
            String reportId,
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest multiImageRequest) {

        log.info("Starting multi-image processing WITH PERSISTENCE for report: {}, {} images",
                reportId, multiImageRequest.getImages().size());

        LocalDateTime startTime = LocalDateTime.now();

        MultiImageProcessingContext context = MultiImageProcessingContext.builder()
                .reportId(reportId)
                .totalImages(multiImageRequest.getImages().size())
                .imageOcrResults(new ArrayList<>())
                .imageErrors(new ArrayList<>())
                .build();

        return Mono.just(context)
                // Stage 1: Validate images (no persistence needed)
                .flatMap(ctx -> validateAllImagesInParallel(ctx, multiImageRequest.getImages()))

                // Stage 2: OCR with persistence
                .flatMap(ctx -> ocrAllImagesInParallelWithPersistence(ctx, multiImageRequest))

                // Stage 3: Merge test results
                .flatMap(this::validateTextLengthLimit)
                .flatMap(this::mergeTestResults)

                // Stage 3.5: Translation with persistence (translate merged text if non-English)
                .flatMap(this::translateMergedTextWithPersistence)

                // Stage 4: Classification/Entity Detection with persistence
                .flatMap(ctx -> runClassificationOnMergedDataWithPersistence(ctx, multiImageRequest))

                // Stages 5-7: Medical Code Inference with persistence (ICD-10, RxNorm, SNOMED-CT)
                .flatMap(this::inferMedicalCodesForMultiImageWithPersistence)

                // Stage 8: Clinical insights with persistence
                .flatMap(ctx -> generateInsightsOnMergedDataWithPersistence(ctx, multiImageRequest))

                // Stage 9: Build and save final response
                .flatMap(ctx -> buildAndSaveMultiImageResponse(ctx, startTime, multiImageRequest))

                .timeout(Duration.ofMinutes(15))
                .doOnSuccess(response -> {
                    log.info("Multi-image processing WITH PERSISTENCE completed for report: {} in {}ms",
                            reportId, response.getProcessingTimeMs());
                    // Update process status to COMPLETED
                    persistencePort.updateProcessStatus(reportId, ProcessingStatus.COMPLETED)
                            .subscribe();
                })
                .onErrorResume(error -> handleMultiImageErrorWithPersistence(context, error, startTime, multiImageRequest));
    }

    /**
     * Stage 1: Validate all images in parallel.
     */
    private Mono<MultiImageProcessingContext> validateAllImagesInParallel(
            MultiImageProcessingContext context,
            List<String> images) {

        log.debug("[{}] Validating {} images in parallel", context.getReportId(), images.size());

        return Flux.fromIterable(images)
                .index()
                .flatMap(tuple -> {
                    long index = tuple.getT1();
                    String imageBase64 = tuple.getT2();

                    return ocrPort.validateImageQuality(imageBase64)
                            .map(result -> Map.entry((int) index, result))
                            .onErrorResume(error -> {
                                log.warn("[{}] Image {} validation failed: {}",
                                        context.getReportId(), index, error.getMessage());

                                context.getImageErrors().add(
                                        MultiImageProcessingContext.ImageProcessingError.builder()
                                                .imageIndex((int) index)
                                                .stage("VALIDATION")
                                                .errorMessage(error.getMessage())
                                                .isRecoverable(false)
                                                .build()
                                );

                                return Mono.just(Map.entry((int) index, (OcrPort.ImageQualityResult) null));
                            });
                })
                .collectList()
                .map(validationResults -> {
                    long failedCount = validationResults.stream()
                            .filter(entry -> entry.getValue() == null || !entry.getValue().isValid())
                            .count();

                    if (failedCount == images.size()) {
                        throw new OrchestrationException(
                                ProcessingStage.IMAGE_VALIDATION,
                                "All images failed validation. Cannot proceed."
                        );
                    }

                    if (failedCount > 0) {
                        log.warn("[{}] {} out of {} images failed validation. Continuing with {} valid images.",
                                context.getReportId(), failedCount, images.size(), images.size() - failedCount);
                    }

                    context.setFailedImagesCount((int) failedCount);

                    log.info("[{}] Image validation completed. Success: {}, Failed: {}",
                            context.getReportId(), images.size() - failedCount, failedCount);

                    return context;
                });
    }

    /**
     * Stage 2: OCR all images in parallel.
     */
    private Mono<MultiImageProcessingContext> ocrAllImagesInParallel(
            MultiImageProcessingContext context,
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest request) {

        log.debug("[{}] Processing OCR for {} images in parallel",
                context.getReportId(), request.getImages().size());

        WorkflowOptions options = request.getWorkflowOptions() != null
                ? request.getWorkflowOptions()
                : new WorkflowOptions();

        Map<String, Object> processingOptions = new HashMap<>();
        processingOptions.put("language", options.getLanguage());
        processingOptions.put("includeRawText", true);

        return Flux.fromIterable(request.getImages())
                .index()
                .flatMap(tuple -> {
                    long index = tuple.getT1();
                    String imageBase64 = tuple.getT2();

                    // Skip if this image failed validation
                    boolean hasValidationError = context.getImageErrors().stream()
                            .anyMatch(err -> err.getImageIndex() == index &&
                                    err.getStage().equals("VALIDATION"));

                    if (hasValidationError) {
                        log.debug("[{}] Skipping OCR for image {} (failed validation)",
                                context.getReportId(), index);
                        return Mono.just(MultiImageProcessingContext.ImageOcrResult.builder()
                                .imageIndex((int) index)
                                .success(false)
                                .errorMessage("Skipped due to validation failure")
                                .build());
                    }

                    // Process OCR for this image
                    return Mono.zip(
                                    ocrPort.extractMedicalData(imageBase64, "BLOOD_TEST", processingOptions)
                                            .collectList(),
                                    ocrPort.extractRawText(imageBase64, options.getLanguage())
                            )
                            .map(ocrData -> {
                                List<TestResult> extractedData = ocrData.getT1();
                                String rawText = ocrData.getT2();

                                double confidence = calculateOverallConfidence(extractedData);

                                return MultiImageProcessingContext.ImageOcrResult.builder()
                                        .imageIndex((int) index)
                                        .extractedData(extractedData)
                                        .rawText(rawText)
                                        .confidence(confidence)
                                        .success(true)
                                        .build();
                            })
                            .onErrorResume(error -> {
                                log.error("[{}] OCR failed for image {}: {}",
                                        context.getReportId(), index, error.getMessage(), error);

                                context.getImageErrors().add(
                                        MultiImageProcessingContext.ImageProcessingError.builder()
                                                .imageIndex((int) index)
                                                .stage("OCR")
                                                .errorMessage(error.getMessage())
                                                .isRecoverable(true)
                                                .build()
                                );

                                return Mono.just(MultiImageProcessingContext.ImageOcrResult.builder()
                                        .imageIndex((int) index)
                                        .success(false)
                                        .errorMessage(error.getMessage())
                                        .build());
                            });
                })
                .collectList()
                .map(ocrResults -> {
                    context.setImageOcrResults(ocrResults);

                    long successCount = ocrResults.stream()
                            .filter(MultiImageProcessingContext.ImageOcrResult::isSuccess)
                            .count();
                    context.setSuccessfulImagesCount((int) successCount);
                    context.setFailedImagesCount((int) (ocrResults.size() - successCount));

                    // Concatenate raw text from successful OCR results
                    String concatenatedText = ocrResults.stream()
                            .filter(MultiImageProcessingContext.ImageOcrResult::isSuccess)
                            .map(MultiImageProcessingContext.ImageOcrResult::getRawText)
                            .filter(text -> text != null && !text.isBlank())
                            .collect(Collectors.joining("\n\n"));

                    context.setConcatenatedRawText(concatenatedText);
                    context.setTotalTextLength(concatenatedText.length());

                    // Fail if ALL images failed OCR
                    if (successCount == 0) {
                        throw new OrchestrationException(
                                ProcessingStage.OCR_PROCESSING,
                                "All images failed OCR processing. No medical data extracted."
                        );
                    }

                    log.info("[{}] OCR completed. Success: {}, Failed: {}, Total text length: {} chars",
                            context.getReportId(), successCount, ocrResults.size() - successCount,
                            concatenatedText.length());

                    return context;
                });
    }

    /**
     * Stage 2 (WITH PERSISTENCE): OCR all images in parallel and save results to database.
     */
    private Mono<MultiImageProcessingContext> ocrAllImagesInParallelWithPersistence(
            MultiImageProcessingContext context,
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest request) {

        log.debug("[{}] Processing OCR WITH PERSISTENCE for {} images in parallel",
                context.getReportId(), request.getImages().size());

        // Create OCR stage in database
        return persistencePort.createStage(context.getReportId(), ProcessingStage.OCR_PROCESSING)
                .flatMap(stageRecord -> {
                    // Start the stage
                    return persistencePort.startStage(stageRecord.id())
                            .then(Mono.defer(() -> {
                                // Perform OCR (reuse existing method)
                                return ocrAllImagesInParallel(context, request)
                                        .flatMap(ctx -> {
                                            // Save OCR results to database
                                            List<TestResult> allExtractedData = ctx.getImageOcrResults().stream()
                                                    .filter(MultiImageProcessingContext.ImageOcrResult::isSuccess)
                                                    .flatMap(result -> result.getExtractedData().stream())
                                                    .toList();

                                            double overallConfidence = ctx.getImageOcrResults().stream()
                                                    .filter(MultiImageProcessingContext.ImageOcrResult::isSuccess)
                                                    .mapToDouble(MultiImageProcessingContext.ImageOcrResult::getConfidence)
                                                    .average()
                                                    .orElse(0.0);

                                            Map<String, Object> ocrOutput = Map.of(
                                                    "totalImages", ctx.getTotalImages(),
                                                    "successfulImages", ctx.getSuccessfulImagesCount(),
                                                    "failedImages", ctx.getFailedImagesCount(),
                                                    "extractedTests", allExtractedData.size(),
                                                    "totalTextLength", ctx.getTotalTextLength()
                                            );

                                            // Complete stage
                                            return persistencePort.completeStage(stageRecord.id(), ocrOutput, overallConfidence)
                                                    .then(persistencePort.saveResult(
                                                            context.getReportId(),
                                                            "OCR",
                                                            OcrResponse.builder()
                                                                    .reportId(context.getReportId())
                                                                    .extractedData(allExtractedData)
                                                                    .rawText(ctx.getConcatenatedRawText())
                                                                    .confidence(overallConfidence)
                                                                    .processedAt(LocalDateTime.now())
                                                                    .build(),
                                                            overallConfidence,
                                                            Map.of(
                                                                    "totalImages", ctx.getTotalImages(),
                                                                    "successfulImages", ctx.getSuccessfulImagesCount(),
                                                                    "totalTextLength", ctx.getTotalTextLength()
                                                            )
                                                    ))
                                                    .thenReturn(ctx);
                                        });
                            }));
                })
                .doOnSuccess(ctx -> log.info("[{}] OCR WITH PERSISTENCE completed. {} tests extracted from {} images",
                        context.getReportId(),
                        ctx.getImageOcrResults().stream()
                                .filter(MultiImageProcessingContext.ImageOcrResult::isSuccess)
                                .mapToLong(r -> r.getExtractedData() != null ? r.getExtractedData().size() : 0)
                                .sum(),
                        ctx.getSuccessfulImagesCount()));
    }

    /**
     * Stage 3: Validate total text length (fail-fast if exceeded).
     */
    private Mono<MultiImageProcessingContext> validateTextLengthLimit(MultiImageProcessingContext context) {
        log.debug("[{}] Validating total text length: {} chars",
                context.getReportId(), context.getTotalTextLength());

        if (context.getTotalTextLength() > MAX_TEXT_LENGTH) {
            return Mono.error(new OrchestrationException(
                    ProcessingStage.OCR_PROCESSING,
                    String.format(
                            "Total text length (%d chars) exceeds maximum limit (%d chars). " +
                                    "Please reduce number of images or image resolution.",
                            context.getTotalTextLength(),
                            MAX_TEXT_LENGTH
                    )
            ));
        }

        log.info("[{}] Text length validation passed: {} / {} chars",
                context.getReportId(), context.getTotalTextLength(), MAX_TEXT_LENGTH);

        return Mono.just(context);
    }

    /**
     * Stage 4: Merge test results from all images using confidence-based deduplication.
     */
    private Mono<MultiImageProcessingContext> mergeTestResults(MultiImageProcessingContext context) {
        log.debug("[{}] Merging test results from {} successful images",
                context.getReportId(), context.getSuccessfulImagesCount());

        // Collect all test results from successful OCR operations
        List<TestResult> allTestResults = context.getImageOcrResults().stream()
                .filter(MultiImageProcessingContext.ImageOcrResult::isSuccess)
                .flatMap(result -> result.getExtractedData().stream())
                .toList();

        // Merge using confidence-based deduplication
        Map<String, TestResult> mergedMap = new LinkedHashMap<>();

        for (TestResult testResult : allTestResults) {
            String normalizedTestName = testResult.getTestName().trim().toLowerCase();

            TestResult existing = mergedMap.get(normalizedTestName);

            if (existing == null) {
                // First occurrence - add it
                mergedMap.put(normalizedTestName, testResult);
            } else {
                // Duplicate found - keep the one with higher confidence
                double existingConfidence = existing.getConfidence() != null ? existing.getConfidence() : 0.0;
                double currentConfidence = testResult.getConfidence() != null ? testResult.getConfidence() : 0.0;

                if (currentConfidence > existingConfidence) {
                    log.debug("[{}] Replacing duplicate test '{}': old confidence={}, new confidence={}",
                            context.getReportId(), testResult.getTestName(),
                            existingConfidence, currentConfidence);
                    mergedMap.put(normalizedTestName, testResult);
                }
            }
        }

        List<TestResult> mergedResults = new ArrayList<>(mergedMap.values());
        context.setMergedTestResults(mergedResults);

        log.info("[{}] Test result merging completed. Total tests before merge: {}, after merge: {}",
                context.getReportId(), allTestResults.size(), mergedResults.size());

        return Mono.just(context);
    }

    /**
     * Stage 3.5: Translate merged text with database persistence.
     *
     * <p>This stage translates the merged/concatenated text from non-English (e.g., Bangla)
     * to English for better downstream processing by AWS Comprehend Medical and Bedrock.</p>
     *
     * <p><b>Non-critical stage:</b> If translation fails, the workflow continues
     * with the original text. A warning is logged but processing is not aborted.</p>
     *
     * @param context the multi-image processing context with merged test results
     * @return Mono of context with translated text (or original if translation was skipped/failed)
     */
    private Mono<MultiImageProcessingContext> translateMergedTextWithPersistence(MultiImageProcessingContext context) {
        log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());
        log.info("[{}] Stage 3.5: TRANSLATION - Starting multi-image translation stage", context.getReportId());
        log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());

        // Check if merged text is empty
        if (context.getConcatenatedRawText() == null || context.getConcatenatedRawText().isBlank()) {
            log.warn("[{}] TRANSLATION: No merged text to translate, skipping translation stage", context.getReportId());
            context.setTranslatedRawText(context.getConcatenatedRawText());
            context.setTranslatedTestResults(context.getMergedTestResults());
            context.setWasTranslated(false);
            return Mono.just(context);
        }

        // Log merged text details
        String textPreview = context.getConcatenatedRawText().length() > 200
                ? context.getConcatenatedRawText().substring(0, 200) + "..."
                : context.getConcatenatedRawText();
        log.info("[{}] TRANSLATION: Merged text length={} chars", context.getReportId(), context.getConcatenatedRawText().length());
        log.info("[{}] TRANSLATION: Merged text preview: \"{}\"", context.getReportId(), textPreview);
        log.info("[{}] TRANSLATION: Checking if text contains non-English content...", context.getReportId());

        // Create translation stage in database
        return persistencePort.createStage(context.getReportId(), ProcessingStage.TRANSLATION)
                .flatMap(stageRecord -> {
                    return persistencePort.startStage(stageRecord.id())
                            .then(translateMergedText(context))
                            .flatMap(ctx -> {
                                // Save translation results to stage output
                                Map<String, Object> translationOutput = Map.of(
                                        "translatedRawText", ctx.getTranslatedRawText() != null ? ctx.getTranslatedRawText() : "",
                                        "originalLanguage", ctx.getOriginalLanguage() != null ? ctx.getOriginalLanguage() : "unknown",
                                        "wasTranslated", ctx.isWasTranslated(),
                                        "originalTextLength", ctx.getConcatenatedRawText() != null ? ctx.getConcatenatedRawText().length() : 0,
                                        "translatedTextLength", ctx.getTranslatedRawText() != null ? ctx.getTranslatedRawText().length() : 0
                                );

                                // Also save translation data as a result record (if translation was performed)
                                Mono<Void> saveResultMono = Mono.empty();
                                if (ctx.isWasTranslated()) {
                                    Map<String, Object> translationResultData = Map.of(
                                            "originalLanguage", ctx.getOriginalLanguage(),
                                            "targetLanguage", "en",
                                            "originalText", ctx.getConcatenatedRawText(),
                                            "translatedText", ctx.getTranslatedRawText(),
                                            "originalTestResults", ctx.getMergedTestResults() != null ? ctx.getMergedTestResults() : List.of(),
                                            "translatedTestResults", ctx.getTranslatedTestResults() != null ? ctx.getTranslatedTestResults() : List.of(),
                                            "wasTranslated", true
                                    );
                                    saveResultMono = persistencePort.saveResult(
                                            ctx.getReportId(),
                                            "TRANSLATION",
                                            translationResultData,
                                            1.0 // confidence
                                    ).then();
                                }

                                return persistencePort.completeStage(stageRecord.id(), translationOutput, 1.0)
                                        .then(saveResultMono)
                                        .thenReturn(ctx);
                            })
                            .onErrorResume(error -> {
                                log.warn("[{}] TRANSLATION: Failed, continuing with original text. Error: {}",
                                        context.getReportId(), error.getMessage());
                                // Non-critical stage: continue with original text on failure
                                context.setTranslatedRawText(context.getConcatenatedRawText());
                                context.setTranslatedTestResults(context.getMergedTestResults());
                                context.setWasTranslated(false);
                                return persistencePort.failStage(stageRecord.id(), error.getMessage(), isRetryableError(error))
                                        .then(persistencePort.recordError(context.getReportId(), ProcessingStage.TRANSLATION, error))
                                        .thenReturn(context); // Continue workflow
                            });
                });
    }

    /**
     * Core translation logic for multi-image merged text.
     *
     * @param context the multi-image processing context
     * @return Mono of context with translated text
     */
    private Mono<MultiImageProcessingContext> translateMergedText(MultiImageProcessingContext context) {
        // Check if text contains non-English content
        return translationPort.containsNonEnglish(context.getConcatenatedRawText())
                .flatMap(hasNonEnglish -> {
                    if (!hasNonEnglish) {
                        // Text is already English, skip translation
                        log.info("[{}] TRANSLATION: 🇬🇧 Text is already ENGLISH - skipping translation", context.getReportId());
                        log.info("[{}] TRANSLATION: wasTranslated=false, originalLanguage=en", context.getReportId());
                        context.setTranslatedRawText(context.getConcatenatedRawText());
                        context.setTranslatedTestResults(context.getMergedTestResults());
                        context.setOriginalLanguage("en");
                        context.setWasTranslated(false);
                        return Mono.just(context);
                    }

                    log.info("[{}] TRANSLATION: 🌐 Non-English content DETECTED - proceeding with translation", context.getReportId());
                    log.info("[{}] TRANSLATION: Starting parallel translation of raw text and test results...", context.getReportId());

                    // Translate raw text and test results in parallel
                    Mono<String> translatedRawTextMono = translationPort.translateMixedText(context.getConcatenatedRawText())
                            .doOnSubscribe(s -> log.info("[{}] TRANSLATION: Translating merged raw text ({} chars)...",
                                    context.getReportId(), context.getConcatenatedRawText().length()));

                    int testResultCount = context.getMergedTestResults() != null ? context.getMergedTestResults().size() : 0;
                    Mono<List<TestResult>> translatedTestResultsMono = context.getMergedTestResults() != null
                            ? translationPort.translateTestResults(context.getMergedTestResults()).collectList()
                                    .doOnSubscribe(s -> log.info("[{}] TRANSLATION: Translating {} merged test results...",
                                            context.getReportId(), testResultCount))
                            : Mono.just(List.of());

                    return Mono.zip(translatedRawTextMono, translatedTestResultsMono)
                            .map(tuple -> {
                                String translatedRawText = tuple.getT1();
                                List<TestResult> translatedTestResults = tuple.getT2();

                                context.setTranslatedRawText(translatedRawText);
                                context.setTranslatedTestResults(translatedTestResults);
                                context.setOriginalLanguage("bn"); // Assuming Bangla for now
                                context.setWasTranslated(true);

                                // Log translation result details
                                String translatedPreview = translatedRawText.length() > 200
                                        ? translatedRawText.substring(0, 200) + "..."
                                        : translatedRawText;
                                log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());
                                log.info("[{}] TRANSLATION: ✅ COMPLETED SUCCESSFULLY", context.getReportId());
                                log.info("[{}] TRANSLATION: Raw text: {} chars → {} chars", context.getReportId(),
                                        context.getConcatenatedRawText().length(), translatedRawText.length());
                                log.info("[{}] TRANSLATION: Test results translated: {}", context.getReportId(), translatedTestResults.size());
                                log.info("[{}] TRANSLATION: wasTranslated=true, originalLanguage=bn", context.getReportId());
                                log.info("[{}] TRANSLATION: Translated text preview: \"{}\"", context.getReportId(), translatedPreview);
                                log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());

                                return context;
                            });
                })
                .onErrorResume(error -> {
                    // Non-critical stage: continue with original text on failure
                    log.error("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());
                    log.error("[{}] TRANSLATION: ❌ FAILED - continuing with original text", context.getReportId());
                    log.error("[{}] TRANSLATION: Error: {}", context.getReportId(), error.getMessage(), error);
                    log.error("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());
                    context.setTranslatedRawText(context.getConcatenatedRawText());
                    context.setTranslatedTestResults(context.getMergedTestResults());
                    context.setWasTranslated(false);
                    return Mono.just(context);
                });
    }

    /**
     * Stage 5: Run classification on merged/concatenated data.
     *
     * <p>Uses translated text if translation was performed, otherwise uses original text.</p>
     */
    private Mono<MultiImageProcessingContext> runClassificationOnMergedData(
            MultiImageProcessingContext context,
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest request) {

        // Use translated text if available, otherwise use original concatenated text
        String textForClassification = context.isWasTranslated() && context.getTranslatedRawText() != null
                ? context.getTranslatedRawText()
                : context.getConcatenatedRawText();

        log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());
        log.info("[{}] Stage 4: CLASSIFICATION - Input Text Selection", context.getReportId());
        log.info("[{}] CLASSIFICATION: wasTranslated={}", context.getReportId(), context.isWasTranslated());
        if (context.isWasTranslated()) {
            log.info("[{}] CLASSIFICATION: ✅ Using TRANSLATED TEXT (English) for AWS Comprehend Medical", context.getReportId());
            log.info("[{}] CLASSIFICATION: Original text length: {} chars", context.getReportId(),
                    context.getConcatenatedRawText() != null ? context.getConcatenatedRawText().length() : 0);
            log.info("[{}] CLASSIFICATION: Translated text length: {} chars", context.getReportId(),
                    textForClassification != null ? textForClassification.length() : 0);
            String preview = textForClassification != null && textForClassification.length() > 150
                    ? textForClassification.substring(0, 150) + "..."
                    : textForClassification;
            log.info("[{}] CLASSIFICATION: Translated text preview: \"{}\"", context.getReportId(), preview);
        } else {
            log.info("[{}] CLASSIFICATION: Using ORIGINAL TEXT (no translation performed)", context.getReportId());
            log.info("[{}] CLASSIFICATION: Text length: {} chars", context.getReportId(),
                    textForClassification != null ? textForClassification.length() : 0);
        }
        log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());

        WorkflowOptions options = request.getWorkflowOptions() != null
                ? request.getWorkflowOptions()
                : new WorkflowOptions();

        MedicalClassificationPort.ClassificationRequest classificationRequest =
                new MedicalClassificationPort.ClassificationRequest(
                        textForClassification,
                        options.getLanguage(),
                        options.getRequestedCodeSystems(),
                        options.getConfidenceThreshold(),
                        Boolean.TRUE.equals(options.getIncludeEntityRelationships()),
                        Map.of()
                );

        return classificationPort.classifyMedicalEntities(classificationRequest)
                .map(classificationResult -> {
                    context.getMetadata().put("classificationResult", classificationResult);

                    log.info("[{}] Classification completed on {} text. Entities found: {}",
                            context.getReportId(),
                            context.isWasTranslated() ? "translated" : "original",
                            classificationResult.entities().size());

                    return context;
                });
    }

    /**
     * Stage 4 (WITH PERSISTENCE): Run classification on merged/concatenated data and save results.
     * Note: Medical codes (ICD10, RXNORM, SNOMEDCT) are saved separately in inferMedicalCodesForMultiImageWithPersistence.
     */
    private Mono<MultiImageProcessingContext> runClassificationOnMergedDataWithPersistence(
            MultiImageProcessingContext context,
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest request) {

        log.debug("[{}] Running classification WITH PERSISTENCE on merged text ({} chars)",
                context.getReportId(), context.getTotalTextLength());

        // Create classification stage in database
        return persistencePort.createStage(context.getReportId(), ProcessingStage.ENTITY_DETECTION)
                .flatMap(stageRecord -> {
                    // Start the stage
                    return persistencePort.startStage(stageRecord.id())
                            .then(Mono.defer(() -> {
                                // Perform classification (reuse existing method)
                                return runClassificationOnMergedData(context, request)
                                        .flatMap(ctx -> {
                                            // Get classification result from metadata
                                            @SuppressWarnings("unchecked")
                                            MedicalClassificationPort.ClassificationResult classificationResult =
                                                    (MedicalClassificationPort.ClassificationResult) ctx.getMetadata().get("classificationResult");

                                            if (classificationResult == null) {
                                                return Mono.error(new IllegalStateException("Classification result not found in context"));
                                            }

                                            double confidence = classificationResult.overallConfidence();

                                            Map<String, Object> classificationOutput = Map.of(
                                                    "entitiesDetected", classificationResult.entities().size(),
                                                    "codeSystemsUsed", classificationResult.medicalCodes().keySet()
                                            );

                                            // Build result map for persistence
                                            Map<String, Object> resultMap = Map.of(
                                                    "reportId", context.getReportId(),
                                                    "entities", classificationResult.entities(),
                                                    "medicalCodes", classificationResult.medicalCodes(),
                                                    "confidence", confidence
                                            );

                                            // Complete stage and save CLASSIFICATION result only
                                            // Medical codes (ICD10, RXNORM, SNOMEDCT) are saved separately with their own stage records
                                            return persistencePort.completeStage(stageRecord.id(), classificationOutput, confidence)
                                                    .then(persistencePort.saveResult(
                                                            context.getReportId(),
                                                            "CLASSIFICATION",
                                                            resultMap,
                                                            confidence,
                                                            classificationOutput
                                                    ))
                                                    .thenReturn(ctx);
                                        });
                            }));
                })
                .doOnSuccess(ctx -> log.info("[{}] Classification WITH PERSISTENCE completed. {} entities detected",
                        context.getReportId(),
                        ((MedicalClassificationPort.ClassificationResult) ctx.getMetadata().get("classificationResult"))
                                .entities().size()));
    }

    /**
     * Stages 5-7 (WITH PERSISTENCE): Infer medical codes from classification results.
     * Creates individual stage records for ICD10, RXNORM, and SNOMEDCT so UI can track progress.
     */
    private Mono<MultiImageProcessingContext> inferMedicalCodesForMultiImageWithPersistence(MultiImageProcessingContext context) {
        log.debug("[{}] Inferring medical codes WITH PERSISTENCE from classification results", context.getReportId());

        // Get classification result from metadata
        @SuppressWarnings("unchecked")
        MedicalClassificationPort.ClassificationResult classificationResult =
                (MedicalClassificationPort.ClassificationResult) context.getMetadata().get("classificationResult");

        if (classificationResult == null) {
            log.warn("[{}] No classification result found, skipping medical code inference", context.getReportId());
            return Mono.just(context);
        }

        // Execute each code inference with its own stage record (sequentially for UI visibility)
        return Mono.just(context)
                .flatMap(ctx -> inferIcd10ForMultiImageWithPersistence(ctx, classificationResult))
                .flatMap(ctx -> inferRxNormForMultiImageWithPersistence(ctx, classificationResult))
                .flatMap(ctx -> inferSnomedCtForMultiImageWithPersistence(ctx, classificationResult));
    }

    /**
     * Stage 5: Save ICD-10 codes with database stage tracking for multi-image workflow.
     */
    private Mono<MultiImageProcessingContext> inferIcd10ForMultiImageWithPersistence(
            MultiImageProcessingContext context,
            MedicalClassificationPort.ClassificationResult classificationResult) {

        List<MedicalClassificationPort.MedicalCode> icd10Codes =
                classificationResult.medicalCodes().getOrDefault("ICD10", List.of());

        return persistencePort.createStage(context.getReportId(), ProcessingStage.ICD10_INFERENCE)
                .flatMap(stageRecord -> persistencePort.startStage(stageRecord.id())
                        .then(Mono.defer(() -> {
                            if (!icd10Codes.isEmpty()) {
                                Double confidence = calculateAverageCodeConfidence(icd10Codes);
                                return persistencePort.completeStage(stageRecord.id(), icd10Codes, confidence)
                                        .then(persistencePort.saveResult(
                                                context.getReportId(),
                                                "ICD10",
                                                icd10Codes,
                                                confidence,
                                                Map.of("codeCount", icd10Codes.size())
                                        ))
                                        .thenReturn(context);
                            } else {
                                return persistencePort.completeStage(stageRecord.id(), List.of(), null)
                                        .thenReturn(context);
                            }
                        }))
                        .onErrorResume(error -> {
                            log.warn("[{}] ICD-10 inference failed: {}", context.getReportId(), error.getMessage());
                            return persistencePort.failStage(stageRecord.id(), error.getMessage(), false)
                                    .thenReturn(context);
                        }))
                .doOnSuccess(ctx -> log.info("[{}] ICD-10 inference completed. Found {} codes",
                        context.getReportId(), icd10Codes.size()));
    }

    /**
     * Stage 6: Save RxNorm codes with database stage tracking for multi-image workflow.
     */
    private Mono<MultiImageProcessingContext> inferRxNormForMultiImageWithPersistence(
            MultiImageProcessingContext context,
            MedicalClassificationPort.ClassificationResult classificationResult) {

        List<MedicalClassificationPort.MedicalCode> rxnormCodes =
                classificationResult.medicalCodes().getOrDefault("RXNORM", List.of());

        return persistencePort.createStage(context.getReportId(), ProcessingStage.RXNORM_INFERENCE)
                .flatMap(stageRecord -> persistencePort.startStage(stageRecord.id())
                        .then(Mono.defer(() -> {
                            if (!rxnormCodes.isEmpty()) {
                                Double confidence = calculateAverageCodeConfidence(rxnormCodes);
                                return persistencePort.completeStage(stageRecord.id(), rxnormCodes, confidence)
                                        .then(persistencePort.saveResult(
                                                context.getReportId(),
                                                "RXNORM",
                                                rxnormCodes,
                                                confidence,
                                                Map.of("codeCount", rxnormCodes.size())
                                        ))
                                        .thenReturn(context);
                            } else {
                                return persistencePort.completeStage(stageRecord.id(), List.of(), null)
                                        .thenReturn(context);
                            }
                        }))
                        .onErrorResume(error -> {
                            log.warn("[{}] RxNorm inference failed: {}", context.getReportId(), error.getMessage());
                            return persistencePort.failStage(stageRecord.id(), error.getMessage(), false)
                                    .thenReturn(context);
                        }))
                .doOnSuccess(ctx -> log.info("[{}] RxNorm inference completed. Found {} codes",
                        context.getReportId(), rxnormCodes.size()));
    }

    /**
     * Stage 7: Save SNOMED-CT codes with database stage tracking for multi-image workflow.
     */
    private Mono<MultiImageProcessingContext> inferSnomedCtForMultiImageWithPersistence(
            MultiImageProcessingContext context,
            MedicalClassificationPort.ClassificationResult classificationResult) {

        List<MedicalClassificationPort.MedicalCode> snomedctCodes =
                classificationResult.medicalCodes().getOrDefault("SNOMEDCT", List.of());

        return persistencePort.createStage(context.getReportId(), ProcessingStage.SNOMEDCT_INFERENCE)
                .flatMap(stageRecord -> persistencePort.startStage(stageRecord.id())
                        .then(Mono.defer(() -> {
                            if (!snomedctCodes.isEmpty()) {
                                Double confidence = calculateAverageCodeConfidence(snomedctCodes);
                                return persistencePort.completeStage(stageRecord.id(), snomedctCodes, confidence)
                                        .then(persistencePort.saveResult(
                                                context.getReportId(),
                                                "SNOMEDCT",
                                                snomedctCodes,
                                                confidence,
                                                Map.of("codeCount", snomedctCodes.size())
                                        ))
                                        .thenReturn(context);
                            } else {
                                return persistencePort.completeStage(stageRecord.id(), List.of(), null)
                                        .thenReturn(context);
                            }
                        }))
                        .onErrorResume(error -> {
                            log.warn("[{}] SNOMED-CT inference failed: {}", context.getReportId(), error.getMessage());
                            return persistencePort.failStage(stageRecord.id(), error.getMessage(), false)
                                    .thenReturn(context);
                        }))
                .doOnSuccess(ctx -> log.info("[{}] SNOMED-CT inference completed. Found {} codes",
                        context.getReportId(), snomedctCodes.size()));
    }

    /**
     * Stage 6: Generate clinical insights on merged data.
     */
    private Mono<MultiImageProcessingContext> generateInsightsOnMergedData(
            MultiImageProcessingContext context,
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest request) {

        log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());
        log.info("[{}] Stage 5: CLINICAL INSIGHTS - Using Classification Results", context.getReportId());
        if (context.isWasTranslated()) {
            log.info("[{}] CLINICAL_INSIGHTS: ✅ Using data from TRANSLATED TEXT (English)", context.getReportId());
            log.info("[{}] CLINICAL_INSIGHTS: Classification was performed on translated text", context.getReportId());
        } else {
            log.info("[{}] CLINICAL_INSIGHTS: Using data from ORIGINAL TEXT", context.getReportId());
        }
        log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());

        ClinicalInsightPort.InsightRequest insightRequest = buildInsightRequestFromMultiImage(context, request);

        return clinicalInsightPort.generateClinicalInsights(insightRequest)
                .map(insightResult -> {
                    context.getMetadata().put("clinicalInsights", insightResult);

                    log.info("[{}] Clinical insights generated successfully",
                            context.getReportId());

                    return context;
                })
                .onErrorResume(error -> {
                    log.warn("[{}] Clinical insights generation failed: {}",
                            context.getReportId(), error.getMessage());
                    // Non-critical - continue without insights
                    return Mono.just(context);
                });
    }

    /**
     * Stage 8 (WITH PERSISTENCE): Generate clinical insights on merged data and save results.
     * After clinical insights, saves Risk Assessment, Recommendations, and Educational Content
     * with their own stage records for UI progress tracking.
     */
    private Mono<MultiImageProcessingContext> generateInsightsOnMergedDataWithPersistence(
            MultiImageProcessingContext context,
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest request) {

        log.debug("[{}] Generating clinical insights WITH PERSISTENCE on merged results",
                context.getReportId());

        // Create clinical insights stage in database
        return persistencePort.createStage(context.getReportId(), ProcessingStage.CLINICAL_INSIGHTS)
                .flatMap(stageRecord -> {
                    // Start the stage
                    return persistencePort.startStage(stageRecord.id())
                            .then(Mono.defer(() -> {
                                // Perform clinical insights generation (reuse existing method)
                                return generateInsightsOnMergedData(context, request)
                                        .flatMap(ctx -> {
                                            // Get clinical insights from metadata
                                            @SuppressWarnings("unchecked")
                                            ClinicalInsightPort.ClinicalInsightResult insightResult =
                                                    (ClinicalInsightPort.ClinicalInsightResult) ctx.getMetadata().get("clinicalInsights");

                                            if (insightResult != null) {
                                                double confidence = 0.85; // Default confidence for AI insights

                                                Map<String, Object> insightsOutput = Map.of(
                                                        "summaryGenerated", insightResult.summary() != null,
                                                        "findingsCount", insightResult.keyFindings() != null ? insightResult.keyFindings().size() : 0,
                                                        "recommendationsCount", insightResult.aiSuggestions() != null ? insightResult.aiSuggestions().size() : 0
                                                );

                                                // Complete CLINICAL_INSIGHTS stage and save result
                                                return persistencePort.completeStage(stageRecord.id(), insightsOutput, confidence)
                                                        .then(persistencePort.saveResult(
                                                                context.getReportId(),
                                                                "CLINICAL_INSIGHTS",
                                                                insightResult,
                                                                confidence,
                                                                insightsOutput
                                                        ))
                                                        // Now save Risk Assessment, Recommendations, Educational Content with stage records
                                                        .then(saveRiskAssessmentWithStage(ctx, insightResult))
                                                        .then(saveRecommendationsWithStage(ctx, insightResult))
                                                        .then(saveEducationalContentWithStage(ctx, insightResult))
                                                        .thenReturn(ctx);
                                            } else {
                                                // Insights generation failed, but non-critical
                                                return persistencePort.completeStage(stageRecord.id(),
                                                                Map.of("status", "skipped"), 0.0)
                                                        .thenReturn(ctx);
                                            }
                                        });
                            }));
                })
                .doOnSuccess(ctx -> log.info("[{}] Clinical insights WITH PERSISTENCE completed",
                        context.getReportId()));
    }

    /**
     * Stage 9: Save Risk Assessment with its own stage record.
     */
    private Mono<Void> saveRiskAssessmentWithStage(MultiImageProcessingContext context,
                                                    ClinicalInsightPort.ClinicalInsightResult insightResult) {
        ClinicalInsightPort.RiskAssessment riskAssessment = insightResult.riskAssessment();

        return persistencePort.createStage(context.getReportId(), ProcessingStage.RISK_ASSESSMENT)
                .flatMap(stageRecord -> persistencePort.startStage(stageRecord.id())
                        .then(Mono.defer(() -> {
                            if (riskAssessment == null) {
                                // No risk assessment data - create empty placeholder result and complete stage
                                log.info("[{}] No risk assessment data available, creating placeholder", context.getReportId());
                                Map<String, Object> emptyResult = Map.of(
                                        "overallRisk", "NOT_ASSESSED",
                                        "riskFactors", List.of(),
                                        "message", "Risk assessment not available for this report"
                                );
                                return persistencePort.saveResult(
                                        context.getReportId(),
                                        "RISK_ASSESSMENT",
                                        emptyResult,
                                        null,
                                        Map.of("riskLevel", "NOT_ASSESSED", "skipped", true)
                                ).then(persistencePort.completeStage(stageRecord.id(),
                                        Map.of("riskLevel", "NOT_ASSESSED", "skipped", true),
                                        null));
                            } else {
                                return persistencePort.saveResult(
                                        context.getReportId(),
                                        "RISK_ASSESSMENT",
                                        riskAssessment,
                                        null,
                                        Map.of("riskLevel", riskAssessment.overallRisk() != null ? riskAssessment.overallRisk() : "UNKNOWN")
                                ).then(persistencePort.completeStage(stageRecord.id(),
                                        Map.of("riskLevel", riskAssessment.overallRisk() != null ? riskAssessment.overallRisk() : "UNKNOWN"),
                                        null));
                            }
                        }))
                        .then()
                        .onErrorResume(error -> {
                            log.warn("[{}] Risk assessment stage failed: {}", context.getReportId(), error.getMessage());
                            return persistencePort.failStage(stageRecord.id(), error.getMessage(), false).then();
                        }))
                .doOnSuccess(v -> log.info("[{}] Risk Assessment stage completed", context.getReportId()));
    }

    /**
     * Stage 10: Save Recommendations with its own stage record.
     */
    private Mono<Void> saveRecommendationsWithStage(MultiImageProcessingContext context,
                                                     ClinicalInsightPort.ClinicalInsightResult insightResult) {
        List<ClinicalInsightPort.Recommendation> recommendations = insightResult.aiSuggestions();

        return persistencePort.createStage(context.getReportId(), ProcessingStage.RECOMMENDATIONS)
                .flatMap(stageRecord -> persistencePort.startStage(stageRecord.id())
                        .then(Mono.defer(() -> {
                            if (recommendations == null || recommendations.isEmpty()) {
                                // No recommendations data - create empty placeholder result and complete stage
                                log.info("[{}] No recommendations data available, creating placeholder", context.getReportId());
                                Map<String, Object> emptyResult = Map.of(
                                        "recommendations", List.of(),
                                        "message", "No recommendations available for this report"
                                );
                                return persistencePort.saveResult(
                                        context.getReportId(),
                                        "RECOMMENDATIONS",
                                        emptyResult,
                                        null,
                                        Map.of("recommendationCount", 0, "skipped", true)
                                ).then(persistencePort.completeStage(stageRecord.id(),
                                        Map.of("recommendationCount", 0, "skipped", true),
                                        null));
                            } else {
                                return persistencePort.saveResult(
                                        context.getReportId(),
                                        "RECOMMENDATIONS",
                                        recommendations,
                                        null,
                                        Map.of("recommendationCount", recommendations.size())
                                ).then(persistencePort.completeStage(stageRecord.id(),
                                        Map.of("recommendationCount", recommendations.size()),
                                        null));
                            }
                        }))
                        .then()
                        .onErrorResume(error -> {
                            log.warn("[{}] Recommendations stage failed: {}", context.getReportId(), error.getMessage());
                            return persistencePort.failStage(stageRecord.id(), error.getMessage(), false).then();
                        }))
                .doOnSuccess(v -> log.info("[{}] Recommendations stage completed", context.getReportId()));
    }

    /**
     * Stage 11: Generate and save Educational Content with its own stage record.
     */
    private Mono<Void> saveEducationalContentWithStage(MultiImageProcessingContext context,
                                                        ClinicalInsightPort.ClinicalInsightResult insightResult) {
        final String eduTopic = extractEducationalTopic(insightResult);

        return persistencePort.createStage(context.getReportId(), ProcessingStage.EDUCATIONAL_CONTENT)
                .flatMap(stageRecord -> persistencePort.startStage(stageRecord.id())
                        .then(clinicalInsightPort.generateEducationalContent(eduTopic, "INTERMEDIATE"))
                        .flatMap(educationalContent -> {
                            log.info("[{}] Educational content generated for multi-image", context.getReportId());
                            return persistencePort.saveResult(
                                    context.getReportId(),
                                    "EDUCATIONAL_CONTENT",
                                    educationalContent,
                                    null,
                                    Map.of("topic", educationalContent.topic() != null ? educationalContent.topic() : "General Health")
                            )
                            .then(persistencePort.completeStage(stageRecord.id(),
                                    Map.of("topic", educationalContent.topic() != null ? educationalContent.topic() : "General Health"),
                                    null));
                        })
                        .then()
                        .onErrorResume(error -> {
                            log.warn("[{}] Educational content generation failed: {}, saving placeholder", context.getReportId(), error.getMessage());
                            // Save a placeholder result so UI doesn't show 404
                            Map<String, Object> fallbackResult = Map.of(
                                    "topic", "General Health",
                                    "content", "Educational content is unavailable for this report: " + error.getMessage(),
                                    "keyPoints", List.of(),
                                    "resources", List.of(),
                                    "faqs", List.of()
                            );
                            return persistencePort.saveResult(
                                    context.getReportId(),
                                    "EDUCATIONAL_CONTENT",
                                    fallbackResult,
                                    null,
                                    Map.of("topic", "General Health", "error", true)
                            ).then(persistencePort.completeStage(stageRecord.id(),
                                    Map.of("topic", "General Health", "error", true),
                                    null))
                            .then()
                            .onErrorResume(saveError -> {
                                log.error("[{}] Failed to save educational content placeholder: {}", context.getReportId(), saveError.getMessage());
                                return persistencePort.failStage(stageRecord.id(), error.getMessage(), false).then();
                            });
                        }))
                .doOnSuccess(v -> log.info("[{}] Educational Content stage completed", context.getReportId()));
    }

    /**
     * Build insight request from multi-image context.
     */
    private ClinicalInsightPort.InsightRequest buildInsightRequestFromMultiImage(
            MultiImageProcessingContext context,
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest request) {

        // Build sanitized extracted data from merged results
        Map<String, Object> extractedData = buildSanitizedExtractedDataFromMerged(context);

        // Get classification result from metadata
        @SuppressWarnings("unchecked")
        MedicalClassificationPort.ClassificationResult classificationResult =
                (MedicalClassificationPort.ClassificationResult) context.getMetadata().get("classificationResult");

        Map<String, Object> classificationData = classificationResult != null
                ? buildSanitizedClassificationResultFromObject(classificationResult)
                : Map.of();

        // Convert patient context
        ClinicalInsightPort.PatientContext patientContext = new ClinicalInsightPort.PatientContext(
                request.getPatientContext().getPatientId(),
                request.getPatientContext().getAge(),
                request.getPatientContext().getGender(),
                request.getPatientContext().getMedicalHistory(),
                request.getPatientContext().getCurrentMedications(),
                request.getPatientContext().getAllergies(),
                convertVitalSigns(request.getPatientContext().getVitalSigns()),
                request.getPatientContext().getLifestyle()
        );

        WorkflowOptions options = request.getWorkflowOptions() != null
                ? request.getWorkflowOptions()
                : new WorkflowOptions();

        return new ClinicalInsightPort.InsightRequest(
                context.getReportId(),
                extractedData,
                classificationData,
                patientContext,
                buildSummaryOptions(options),
                buildRiskOptions(options),
                Map.of("multiImage", true, "imageCount", context.getTotalImages())
        );
    }

    /**
     * Build sanitized extracted data from merged test results.
     */
    private Map<String, Object> buildSanitizedExtractedDataFromMerged(MultiImageProcessingContext context) {
        if (context.getMergedTestResults() == null || context.getMergedTestResults().isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> testMaps = context.getMergedTestResults().stream()
                .filter(tr -> tr.getTestName() != null && tr.getTestValue() != null)
                .map(tr -> Map.of(
                        "testName", (Object) tr.getTestName().trim(),
                        "value", tr.getTestValue(),
                        "unit", tr.getUnit() != null ? tr.getUnit() : "",
                        "status", tr.getStatus() != null ? tr.getStatus().name() : "UNKNOWN",
                        "referenceRange", tr.getReferenceRange() != null ? tr.getReferenceRange() : ""
                ))
                .toList();

        return Map.of("labs", testMaps);
    }

    /**
     * Build sanitized classification result from object.
     */
    private Map<String, Object> buildSanitizedClassificationResultFromObject(
            MedicalClassificationPort.ClassificationResult classificationResult) {

        List<Map<String, Object>> sanitizedEntities = new ArrayList<>();
        classificationResult.entities().forEach(entity -> {
            if (entity.category() != null && entity.category().equalsIgnoreCase("PROTECTED_HEALTH_INFORMATION")) {
                return; // strip PHI
            }
            Map<String, Object> entityMap = new HashMap<>();
            entityMap.put("text", entity.text());
            entityMap.put("category", entity.category());
            entityMap.put("type", entity.type());

            if (entity.attributes() != null && !entity.attributes().isEmpty()) {
                List<Map<String, String>> attrs = entity.attributes().stream()
                        .filter(attr -> attr.text() != null && attr.type() != null)
                        .map(attr -> Map.of(
                                "type", attr.type(),
                                "text", attr.text()
                        ))
                        .toList();
                if (!attrs.isEmpty()) {
                    entityMap.put("attributes", attrs);
                }
            }

            sanitizedEntities.add(entityMap);
        });

        Map<String, List<MedicalClassificationPort.MedicalCode>> medicalCodes = classificationResult.medicalCodes();
        Map<String, Object> leanCodes = new HashMap<>();
        if (medicalCodes != null && !medicalCodes.isEmpty()) {
            medicalCodes.forEach((system, codes) -> {
                if (codes == null || codes.isEmpty()) return;
                List<Map<String, String>> leanList = codes.stream()
                        .map(code -> Map.of(
                                "code", code.code(),
                                "description", code.description(),
                                "system", code.codeSystem()
                        ))
                        .toList();
                leanCodes.put(system, leanList);
            });
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entities", sanitizedEntities);
        if (!leanCodes.isEmpty()) {
            result.put("medicalCodes", leanCodes);
        }
        return result;
    }


    /**
     * Build final multi-image response.
     */
    private Mono<MasterProcessingResponse> buildMultiImageResponse(
            MultiImageProcessingContext context,
            LocalDateTime startTime,
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest request) {

        long processingTimeMs = Duration.between(startTime, LocalDateTime.now()).toMillis();

        MasterProcessingResponse response = MasterProcessingResponse.builder()
                .reportId(context.getReportId())
                .patientId(request.getPatientContext().getPatientId())
                .processingStatus(ProcessingStatus.COMPLETED)
                .processingTimeMs(processingTimeMs)
                .timestamp(LocalDateTime.now())
                .ocrResults(buildMultiImageOcrResults(context))
                .entityDetection(buildEntityDetectionFromContext(context))
                .clinicalInsights(buildClinicalInsightsFromContext(context))
                .metadata(buildMultiImageMetadata(context))
                .errors(buildErrorListFromContext(context))
                .warnings(buildWarningListFromContext(context))
                .build();

        return Mono.just(response);
    }

    /**
     * Stage 6 (WITH PERSISTENCE): Build multi-image response and save to database.
     */
    private Mono<MasterProcessingResponse> buildAndSaveMultiImageResponse(
            MultiImageProcessingContext context,
            LocalDateTime startTime,
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest request) {

        log.debug("[{}] Building and saving final multi-image response", context.getReportId());

        // Build the response (reuse existing method)
        return buildMultiImageResponse(context, startTime, request)
                .flatMap(response -> {
                    // Update process status to COMPLETED
                    // Note: Individual results (OCR, CLASSIFICATION, CLINICAL_INSIGHTS) are already saved
                    return persistencePort.updateProcessStatus(
                                    context.getReportId(),
                                    ProcessingStatus.COMPLETED
                            )
                            .thenReturn(response);
                })
                .doOnSuccess(response -> log.info("[{}] Multi-image processing completed and status updated. Total time: {}ms",
                        context.getReportId(), response.getProcessingTimeMs()));
    }

    /**
     * Build OCR results from multi-image context.
     *
     * <p>If translation was performed, returns the translated data.
     * Otherwise, returns the original data.</p>
     */
    private MasterProcessingResponse.OcrResults buildMultiImageOcrResults(MultiImageProcessingContext context) {
        // Use translated data if available, otherwise use original
        List<TestResult> testResults = context.isWasTranslated() && context.getTranslatedTestResults() != null
                ? context.getTranslatedTestResults()
                : context.getMergedTestResults();

        String rawText = context.isWasTranslated() && context.getTranslatedRawText() != null
                ? context.getTranslatedRawText()
                : context.getConcatenatedRawText();

        log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());
        log.info("[{}] Building Final API Response - OCR Results", context.getReportId());
        if (context.isWasTranslated()) {
            log.info("[{}] API_RESPONSE: ✅ Returning TRANSLATED DATA (English) in ocrResults", context.getReportId());
            log.info("[{}] API_RESPONSE: Raw text length: {} chars (translated)", context.getReportId(),
                    rawText != null ? rawText.length() : 0);
            log.info("[{}] API_RESPONSE: Test results count: {} (translated)", context.getReportId(),
                    testResults != null ? testResults.size() : 0);
        } else {
            log.info("[{}] API_RESPONSE: Returning ORIGINAL DATA in ocrResults", context.getReportId());
            log.info("[{}] API_RESPONSE: Raw text length: {} chars", context.getReportId(),
                    rawText != null ? rawText.length() : 0);
            log.info("[{}] API_RESPONSE: Test results count: {}", context.getReportId(),
                    testResults != null ? testResults.size() : 0);
        }
        log.info("[{}] ═══════════════════════════════════════════════════════════════", context.getReportId());

        return MasterProcessingResponse.OcrResults.builder()
                .extractedData(testResults)
                .rawText(rawText)
                .overallConfidence(calculateAverageConfidence(context))
                .testCount(testResults != null ? testResults.size() : 0)
                .build();
    }

    /**
     * Calculate average confidence from multi-image context.
     */
    private double calculateAverageConfidence(MultiImageProcessingContext context) {
        return context.getImageOcrResults().stream()
                .filter(MultiImageProcessingContext.ImageOcrResult::isSuccess)
                .mapToDouble(r -> r.getConfidence() != null ? r.getConfidence() : 0.0)
                .average()
                .orElse(0.0);
    }

    /**
     * Build entity detection result from context.
     */
    private MasterProcessingResponse.EntityDetectionResult buildEntityDetectionFromContext(
            MultiImageProcessingContext context) {

        @SuppressWarnings("unchecked")
        MedicalClassificationPort.ClassificationResult classificationResult =
                (MedicalClassificationPort.ClassificationResult) context.getMetadata().get("classificationResult");

        if (classificationResult == null) {
            return null;
        }

        return MasterProcessingResponse.EntityDetectionResult.builder()
                .entities(classificationResult.entities())
                .entityCount(classificationResult.entities().size())
                .modelVersion("AWS Comprehend Medical 3.0.0")
                .build();
    }

    /**
     * Build clinical insights result from context.
     */
    private MasterProcessingResponse.ClinicalInsightsResult buildClinicalInsightsFromContext(
            MultiImageProcessingContext context) {

        @SuppressWarnings("unchecked")
        ClinicalInsightPort.ClinicalInsightResult insightResult =
                (ClinicalInsightPort.ClinicalInsightResult) context.getMetadata().get("clinicalInsights");

        if (insightResult == null) {
            return null;
        }

        // Use existing conversion helpers from single-image implementation
        return MasterProcessingResponse.ClinicalInsightsResult.builder()
                .summary(insightResult.summary())
                .keyFindings(convertKeyFindings(insightResult.keyFindings()))
                .riskAssessment(convertRiskAssessment(insightResult.riskAssessment()))
                .recommendations(convertRecommendations(insightResult.aiSuggestions()))
                .actionPlan(convertActionPlan(insightResult.actionPlan()))
                .educationalContent(convertEducationalContent(insightResult.educationalContent()))
                .build();
    }

    /**
     * Build metadata for multi-image response.
     *
     * <p>Includes translation information when translation was performed.</p>
     */
    private MasterProcessingResponse.MetadataResult buildMultiImageMetadata(MultiImageProcessingContext context) {
        // Build model versions map with translation info
        Map<String, String> modelVersions = new java.util.HashMap<>();
        modelVersions.put("textract", "1.0");
        modelVersions.put("comprehendMedical", "3.0.0");
        modelVersions.put("bedrock", "claude-3-5-sonnet");
        modelVersions.put("multiImage", "true");
        modelVersions.put("totalImages", String.valueOf(context.getTotalImages()));
        modelVersions.put("successfulImages", String.valueOf(context.getSuccessfulImagesCount()));
        modelVersions.put("failedImages", String.valueOf(context.getFailedImagesCount()));

        // Add translation metadata if translation was performed
        if (context.isWasTranslated()) {
            modelVersions.put("translation", "GCP Translation API v3");
            modelVersions.put("translationPerformed", "true");
            modelVersions.put("originalLanguage", context.getOriginalLanguage() != null ? context.getOriginalLanguage() : "unknown");
            modelVersions.put("targetLanguage", "en");
            if (context.getConcatenatedRawText() != null && context.getTranslatedRawText() != null) {
                modelVersions.put("originalTextLength", String.valueOf(context.getConcatenatedRawText().length()));
                modelVersions.put("translatedTextLength", String.valueOf(context.getTranslatedRawText().length()));
            }
        } else {
            modelVersions.put("translationPerformed", "false");
        }

        // Build costs map with translation cost if applicable
        Map<String, String> costs = new java.util.HashMap<>();
        costs.put("textract", "$" + String.format("%.3f", context.getSuccessfulImagesCount() * 0.015));
        costs.put("comprehendMedical", "$0.012");
        costs.put("bedrock", "$0.045");

        double totalCost = context.getSuccessfulImagesCount() * 0.015 + 0.057;
        if (context.isWasTranslated()) {
            // GCP Translation API pricing: ~$20 per 1M characters
            double translationCost = 0.0;
            if (context.getConcatenatedRawText() != null) {
                translationCost = (context.getConcatenatedRawText().length() / 1_000_000.0) * 20.0;
            }
            costs.put("translation", "$" + String.format("%.4f", translationCost));
            totalCost += translationCost;
        }
        costs.put("total", "$" + String.format("%.3f", totalCost));

        return MasterProcessingResponse.MetadataResult.builder()
                .apiVersion("1.0")
                .processingDate(LocalDateTime.now())
                .modelVersions(modelVersions)
                .costs(costs)
                .build();
    }

    /**
     * Build error list from multi-image context.
     */
    private List<MasterProcessingResponse.ProcessingError> buildErrorListFromContext(MultiImageProcessingContext context) {
        return context.getImageErrors().stream()
                .map(err -> MasterProcessingResponse.ProcessingError.builder()
                        .stage(ProcessingStage.valueOf(err.getStage()))
                        .severity(err.isRecoverable() ? "WARNING" : "ERROR")
                        .message("Image " + err.getImageIndex() + ": " + err.getErrorMessage())
                        .code(err.getStage() + "_FAILED")
                        .timestamp(LocalDateTime.now())
                        .retryable(err.isRecoverable())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Build warning list from multi-image context.
     */
    private List<MasterProcessingResponse.ProcessingWarning> buildWarningListFromContext(MultiImageProcessingContext context) {
        List<MasterProcessingResponse.ProcessingWarning> warnings = new ArrayList<>();

        if (context.getFailedImagesCount() > 0) {
            warnings.add(MasterProcessingResponse.ProcessingWarning.builder()
                    .stage(ProcessingStage.OCR_PROCESSING)
                    .severity("WARNING")
                    .message(String.format(
                            "%d out of %d images failed processing. Results are based on %d successful images.",
                            context.getFailedImagesCount(),
                            context.getTotalImages(),
                            context.getSuccessfulImagesCount()
                    ))
                    .build());
        }

        if (context.getTotalTextLength() > MAX_TEXT_LENGTH * 0.8) {
            warnings.add(MasterProcessingResponse.ProcessingWarning.builder()
                    .stage(ProcessingStage.OCR_PROCESSING)
                    .severity("INFO")
                    .message(String.format(
                            "Total text length (%d chars) is approaching the limit (%d chars). Consider using fewer images.",
                            context.getTotalTextLength(),
                            MAX_TEXT_LENGTH
                    ))
                    .build());
        }

        return warnings;
    }

    /**
     * Handle multi-image processing error.
     */
    private Mono<MasterProcessingResponse> handleMultiImageError(
            MultiImageProcessingContext context,
            Throwable error,
            LocalDateTime startTime,
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest request) {

        log.error("[{}] Multi-image workflow failed: {}",
                context.getReportId(), error.getMessage(), error);

        long processingTimeMs = Duration.between(startTime, LocalDateTime.now()).toMillis();

        // Build error warning
        List<MasterProcessingResponse.ProcessingWarning> errorWarnings = new ArrayList<>();
        errorWarnings.add(MasterProcessingResponse.ProcessingWarning.builder()
                .stage(ProcessingStage.OCR_PROCESSING)
                .severity("ERROR")
                .message(error.getMessage())
                .build());

        // Build partial response with error information
        return Mono.just(MasterProcessingResponse.builder()
                .reportId(context.getReportId())
                .patientId(request.getPatientContext().getPatientId())
                .processingStatus(ProcessingStatus.FAILED)
                .processingTimeMs(processingTimeMs)
                .timestamp(LocalDateTime.now())
                .ocrResults(context.getMergedTestResults() != null ? buildMultiImageOcrResults(context) : null)
                .metadata(buildMultiImageMetadata(context))
                .errors(buildErrorListFromContext(context))
                .warnings(errorWarnings)
                .build());
    }

    /**
     * Handle multi-image error with database persistence.
     */
    private Mono<MasterProcessingResponse> handleMultiImageErrorWithPersistence(
            MultiImageProcessingContext context,
            Throwable error,
            LocalDateTime startTime,
            com.elioo.healthcare.medicalreport.dto.MultiImageRequest request) {

        log.error("[{}] Multi-image workflow WITH PERSISTENCE failed: {}",
                context.getReportId(), error.getMessage(), error);

        // Update process status to FAILED and return error response
        // Note: We don't save error as a separate result type since it's not in the allowed list
        // The process status FAILED indicates the error, and partial results are already saved
        return persistencePort.updateProcessStatus(context.getReportId(), ProcessingStatus.FAILED)
                .then(handleMultiImageError(context, error, startTime, request))
                .onErrorResume(statusUpdateError -> {
                    // If status update fails, still return the error response
                    log.error("[{}] Failed to update status to FAILED: {}",
                            context.getReportId(), statusUpdateError.getMessage());
                    return handleMultiImageError(context, error, startTime, request);
                });
    }
}
