# Master Orchestration API - Implementation Plan

**Version:** 1.0
**Status:** 🚧 Planning Phase
**Estimated Duration:** 3-4 weeks
**Complexity:** High

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture Decisions](#architecture-decisions)
3. [Phase 1: Foundation (Week 1)](#phase-1-foundation-week-1)
4. [Phase 2: Core Orchestration (Week 2)](#phase-2-core-orchestration-week-2)
5. [Phase 3: Advanced Features (Week 3)](#phase-3-advanced-features-week-3)
6. [Phase 4: Production Readiness (Week 4)](#phase-4-production-readiness-week-4)
7. [Testing Strategy](#testing-strategy)
8. [Deployment Strategy](#deployment-strategy)
9. [Monitoring & Observability](#monitoring--observability)
10. [Risk Management](#risk-management)

---

## Overview

This document provides a comprehensive, phase-by-phase implementation plan for the Master Orchestration API that processes medical reports through a complete 10-step workflow.

### Goals

✅ Single API endpoint for complete medical report processing
✅ Orchestrated workflow leveraging existing AWS service adapters
✅ Reactive, non-blocking implementation using Project Reactor
✅ Comprehensive error handling with partial success support
✅ Production-ready with monitoring and observability
✅ Fully tested with unit, integration, and end-to-end tests

### Key Principles

1. **Reuse Existing Code**: Leverage existing `TextractAdapter`, `ComprehendMedicalAdapter`, and `BedrockAdapter`
2. **Hexagonal Architecture**: Follow existing port/adapter pattern
3. **Reactive Programming**: Use `Mono` and `Flux` throughout
4. **Fail-Safe Design**: Partial success over complete failure
5. **Observability**: Comprehensive logging, metrics, and tracing

---

## Architecture Decisions

### 1. Orchestration Pattern

**Decision:** Use **Saga Pattern** with reactive chains

**Rationale:**
- Each workflow stage is independent and can be compensated
- Reactive chains (`flatMap`, `Mono.zip`) provide natural orchestration
- Partial failures can be handled gracefully

### 2. Error Handling Strategy

**Decision:** **Continue-on-Error** for non-critical stages

**Critical Stages** (must succeed):
- Image Validation
- OCR Processing

**Non-Critical Stages** (can fail with partial success):
- ICD-10 Inference
- RxNorm Inference
- Educational Content

### 3. Performance Optimization

**Decision:** **Parallel Execution** where dependencies allow

**Parallel Groups:**
1. ICD-10 + RxNorm Inference (both use same text)
2. Summary + Risk Assessment + Recommendations (all use same clinical data)

**Sequential Dependencies:**
- Image Validation → OCR
- OCR → Entity Detection
- Entity Detection → Medical Codes + Clinical Insights

### 4. State Management

**Decision:** **Immutable Context Object** passed through pipeline

**Rationale:**
- Thread-safe
- Easy to debug
- Clear data flow
- Supports partial results

---

## Phase 1: Foundation (Week 1)

### Goal
Create all DTOs, domain objects, and port interfaces required for orchestration.

---

### Task 1.1: Create Domain DTOs

**Priority:** 🔴 High
**Estimated Time:** 4 hours

#### Files to Create

##### 1. `MasterProcessingRequest.java`
**Location:** `medicalreport/domain/`

```java
package com.elioo.healthcare.medicalreport.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for master orchestration API.
 * Contains image data, patient context, and workflow configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterProcessingRequest {

    @NotBlank(message = "Image data is required")
    private String imageBase64;

    @NotNull(message = "Patient context is required")
    private PatientContext patientContext;

    private WorkflowOptions workflowOptions;

    /**
     * Patient demographic and medical history.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PatientContext {

        @NotBlank(message = "Patient ID is required")
        private String patientId;

        @NotNull(message = "Age is required")
        @Min(value = 0, message = "Age must be positive")
        @Max(value = 150, message = "Age must be realistic")
        private Integer age;

        @NotBlank(message = "Gender is required")
        @Pattern(regexp = "MALE|FEMALE|OTHER", message = "Gender must be MALE, FEMALE, or OTHER")
        private String gender;

        private List<String> medicalHistory;
        private List<String> currentMedications;
        private List<String> allergies;
        private Map<String, Object> vitalSigns;
        private Map<String, Object> lifestyle;
    }
}
```

##### 2. `WorkflowOptions.java`
**Location:** `medicalreport/domain/`

```java
package com.elioo.healthcare.medicalreport.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Configuration options for workflow execution.
 * Allows clients to customize which stages to execute and how.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowOptions {

    @Builder.Default
    private Boolean skipValidation = false;

    @Builder.Default
    private Boolean includeRawText = true;

    @Builder.Default
    private Boolean includeEntityRelationships = true;

    @Builder.Default
    private List<String> requestedCodeSystems = List.of("ICD10", "RXNORM");

    @Builder.Default
    private Boolean includeEducationalContent = true;

    private List<String> educationalContentTopics;

    @Builder.Default
    private List<String> riskAssessmentCategories = List.of(
        "CARDIOVASCULAR", "METABOLIC", "RENAL", "HEPATIC"
    );

    @Builder.Default
    private String targetAudience = "PATIENT"; // PATIENT or PROVIDER

    @Builder.Default
    private String language = "en";

    @Builder.Default
    private Double confidenceThreshold = 0.70;

    @Builder.Default
    private Boolean includeActionPlan = true;

    @Builder.Default
    private Boolean includeTrendAnalysis = false;

    private Map<String, Object> historicalData; // For trend analysis
}
```

##### 3. `MasterProcessingResponse.java`
**Location:** `medicalreport/domain/`

```java
package com.elioo.healthcare.medicalreport.domain;

import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import com.elioo.healthcare.medicalreport.application.port.out.OcrPort;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive response from master orchestration API.
 * Contains results from all workflow stages.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterProcessingResponse {

    private String reportId;
    private String patientId;
    private ProcessingStatus processingStatus;
    private Long processingTimeMs;
    private LocalDateTime timestamp;

    private WorkflowSummary workflow;
    private ImageValidationResult imageValidation;
    private OcrResults ocrResults;
    private EntityDetectionResult entityDetection;
    private MedicalCodesResult medicalCodes;
    private ClinicalInsightsResult clinicalInsights;
    private MetadataResult metadata;

    private List<ProcessingError> errors;
    private List<ProcessingWarning> warnings;

    /**
     * Workflow execution summary.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkflowSummary {
        private List<ProcessingStage> completedStages;
        private List<ProcessingStage> failedStages;
        private List<ProcessingStage> skippedStages;
    }

    /**
     * Image validation result.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageValidationResult {
        private Boolean isValid;
        private Double qualityScore;
        private String message;
        private Map<String, Object> metrics;
    }

    /**
     * OCR processing results.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OcrResults {
        private List<TestResult> extractedData;
        private String rawText;
        private Double overallConfidence;
        private Integer testCount;
    }

    /**
     * Entity detection results.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityDetectionResult {
        private List<MedicalClassificationPort.MedicalEntity> entities;
        private List<MedicalClassificationPort.EntityRelationship> relationships;
        private Integer entityCount;
        private String modelVersion;
    }

    /**
     * Medical codes (ICD-10, RxNorm, etc.).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MedicalCodesResult {
        private List<MedicalCode> icd10;
        private List<MedicalCode> rxnorm;
        private Integer totalCodes;
    }

    /**
     * Medical code with metadata.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MedicalCode {
        private String code;
        private String description;
        private Double score;
        private String category;
    }

    /**
     * Clinical insights from AI.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClinicalInsightsResult {
        private String summary;
        private List<KeyFinding> keyFindings;
        private RiskAssessment riskAssessment;
        private List<Recommendation> recommendations;
        private ActionPlan actionPlan;
        private List<EducationalContent> educationalContent;
    }

    /**
     * Key finding from analysis.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeyFinding {
        private String id;
        private String finding;
        private String severity;
        private String interpretation;
        private String clinicalSignificance;
        private String normalRange;
        private String percentageDeviation;
        private List<String> relatedTests;
        private Boolean critical;
    }

    /**
     * Risk assessment result.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskAssessment {
        private String overallRiskLevel;
        private Map<String, CategoryRisk> categoryRisks;
        private List<String> riskFactors;
        private List<String> protectiveFactors;
        private String overallAssessment;
        private Boolean requiresImmediateAttention;
    }

    /**
     * Category-specific risk.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryRisk {
        private String level;
        private Double score;
        private String description;
        private List<String> contributors;
    }

    /**
     * Recommendation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recommendation {
        private String id;
        private String category;
        private String priority;
        private String recommendation;
        private String rationale;
        private String evidenceLevel;
        private String timeframe;
        private List<String> prerequisites;
    }

    /**
     * Action plan.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionPlan {
        private List<Action> immediateActions;
        private List<Action> shortTermActions;
        private List<Action> longTermActions;
        private Integer totalActions;
    }

    /**
     * Single action item.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        private String action;
        private String priority;
        private String timeframe;
        private String category;
        private String status;
    }

    /**
     * Educational content.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EducationalContent {
        private String topic;
        private String content;
        private List<String> keyPoints;
        private List<String> resources;
    }

    /**
     * Processing metadata.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetadataResult {
        private String apiVersion;
        private LocalDateTime processingDate;
        private Map<String, String> modelVersions;
        private Map<String, String> costs;
    }

    /**
     * Processing error.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingError {
        private ProcessingStage stage;
        private String severity;
        private String message;
        private String code;
        private LocalDateTime timestamp;
        private Boolean retryable;
    }

    /**
     * Processing warning.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingWarning {
        private ProcessingStage stage;
        private String severity;
        private String message;
    }
}
```

##### 4. `ProcessingStage.java`
**Location:** `medicalreport/domain/`

```java
package com.elioo.healthcare.medicalreport.domain;

/**
 * Enumeration of workflow processing stages.
 */
public enum ProcessingStage {
    IMAGE_VALIDATION("Image Validation", true),
    OCR_PROCESSING("OCR Processing", true),
    ENTITY_DETECTION("Entity Detection", true),
    ICD10_INFERENCE("ICD-10 Inference", false),
    RXNORM_INFERENCE("RxNorm Inference", false),
    CLINICAL_INSIGHTS("Clinical Insights", false),
    PATIENT_SUMMARY("Patient Summary", false),
    RISK_ASSESSMENT("Risk Assessment", false),
    RECOMMENDATIONS("Recommendations", false),
    EDUCATIONAL_CONTENT("Educational Content", false);

    private final String displayName;
    private final boolean critical;

    ProcessingStage(String displayName, boolean critical) {
        this.displayName = displayName;
        this.critical = critical;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isCritical() {
        return critical;
    }
}
```

##### 5. `ProcessingStatus.java`
**Location:** `medicalreport/domain/`

```java
package com.elioo.healthcare.medicalreport.domain;

/**
 * Status of master processing workflow.
 */
public enum ProcessingStatus {
    COMPLETED("All stages completed successfully"),
    PARTIAL_SUCCESS("Some stages failed but core results available"),
    FAILED("Critical stage failed, no results available"),
    VALIDATION_FAILED("Image validation failed"),
    TIMEOUT("Processing exceeded maximum time limit");

    private final String description;

    ProcessingStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
```

##### 6. `ProcessingContext.java`
**Location:** `medicalreport/domain/`

```java
package com.elioo.healthcare.medicalreport.domain;

import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import com.elioo.healthcare.medicalreport.application.port.out.OcrPort;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable context object passed through orchestration pipeline.
 * Accumulates results from each stage.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingContext {

    private String reportId;
    private MasterProcessingRequest request;
    private LocalDateTime startTime;

    // Stage results
    private OcrPort.ImageQualityResult imageValidation;
    private List<TestResult> ocrExtractedData;
    private String ocrRawText;
    private Double ocrConfidence;

    private MedicalClassificationPort.ClassificationResult classificationResult;

    private List<MedicalClassificationPort.MedicalCode> icd10Codes;
    private List<MedicalClassificationPort.MedicalCode> rxnormCodes;

    private ClinicalInsightPort.ClinicalInsightResult clinicalInsights;

    // Stage tracking
    @Builder.Default
    private List<ProcessingStage> completedStages = new ArrayList<>();

    @Builder.Default
    private List<ProcessingStage> failedStages = new ArrayList<>();

    @Builder.Default
    private List<MasterProcessingResponse.ProcessingError> errors = new ArrayList<>();

    @Builder.Default
    private List<MasterProcessingResponse.ProcessingWarning> warnings = new ArrayList<>();

    /**
     * Mark a stage as completed.
     */
    public void markStageCompleted(ProcessingStage stage) {
        this.completedStages.add(stage);
    }

    /**
     * Mark a stage as failed.
     */
    public void markStageFailed(ProcessingStage stage, String errorMessage, boolean retryable) {
        this.failedStages.add(stage);
        this.errors.add(MasterProcessingResponse.ProcessingError.builder()
                .stage(stage)
                .severity(stage.isCritical() ? "CRITICAL" : "ERROR")
                .message(errorMessage)
                .timestamp(LocalDateTime.now())
                .retryable(retryable)
                .build());
    }

    /**
     * Add a warning.
     */
    public void addWarning(ProcessingStage stage, String message) {
        this.warnings.add(MasterProcessingResponse.ProcessingWarning.builder()
                .stage(stage)
                .severity("WARNING")
                .message(message)
                .build());
    }

    /**
     * Check if a critical stage has failed.
     */
    public boolean hasCriticalFailure() {
        return failedStages.stream()
                .anyMatch(ProcessingStage::isCritical);
    }
}
```

**Deliverables:**
- ✅ 6 domain classes created
- ✅ Comprehensive validation annotations
- ✅ Builder pattern for easy object construction
- ✅ Complete JavaDoc documentation

---

### Task 1.2: Create Use Case Port Interface

**Priority:** 🔴 High
**Estimated Time:** 1 hour

#### Files to Create

##### 1. `MedicalReportOrchestrationUseCase.java`
**Location:** `medicalreport/application/port/in/`

```java
package com.elioo.healthcare.medicalreport.application.port.in;

import com.elioo.healthcare.medicalreport.domain.MasterProcessingRequest;
import com.elioo.healthcare.medicalreport.domain.MasterProcessingResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Inbound port (use case) for master medical report orchestration.
 * This interface defines the contract for processing complete medical reports.
 *
 * <p>Implementation Note: This is a driving port in hexagonal architecture.</p>
 * <p>The web adapter (handler) depends on this interface, not on the service implementation.</p>
 */
public interface MedicalReportOrchestrationUseCase {

    /**
     * Process a complete medical report through the 10-step workflow.
     * Returns a comprehensive response containing all analysis results.
     *
     * @param request Master processing request with image and patient context
     * @return Mono containing complete processing results
     */
    Mono<MasterProcessingResponse> processCompleteMedicalReport(MasterProcessingRequest request);

    /**
     * Process a medical report with real-time progress updates.
     * Emits progress events as each workflow stage completes.
     *
     * @param request Master processing request with image and patient context
     * @return Flux of processing progress events
     */
    Flux<ProcessingProgress> processWithProgressUpdates(MasterProcessingRequest request);

    /**
     * Progress event emitted during processing.
     */
    record ProcessingProgress(
            String reportId,
            ProcessingStage currentStage,
            Integer completedStages,
            Integer totalStages,
            Double progressPercentage,
            String message,
            Long elapsedMs
    ) {}
}
```

**Deliverables:**
- ✅ Use case interface created
- ✅ Two methods: batch and streaming
- ✅ Clean interface following hexagonal principles

---

### Task 1.3: Create Exception Classes

**Priority:** 🟡 Medium
**Estimated Time:** 1 hour

#### Files to Create

##### 1. `OrchestrationException.java`
**Location:** `medicalreport/domain/exception/`

```java
package com.elioo.healthcare.medicalreport.domain.exception;

import com.elioo.healthcare.medicalreport.domain.ProcessingStage;

/**
 * Exception thrown during orchestration workflow.
 */
public class OrchestrationException extends RuntimeException {

    private final ProcessingStage stage;
    private final boolean retryable;

    public OrchestrationException(ProcessingStage stage, String message) {
        this(stage, message, null, false);
    }

    public OrchestrationException(ProcessingStage stage, String message, Throwable cause) {
        this(stage, message, cause, false);
    }

    public OrchestrationException(ProcessingStage stage, String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.stage = stage;
        this.retryable = retryable;
    }

    public ProcessingStage getStage() {
        return stage;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean isCritical() {
        return stage != null && stage.isCritical();
    }
}
```

**Deliverables:**
- ✅ Custom exception class
- ✅ Stage and retryability tracking
- ✅ Critical failure detection

---

### Task 1.4: Update Router Configuration

**Priority:** 🟡 Medium
**Estimated Time:** 30 minutes

#### Files to Update

##### 1. `MedicalReportRouter.java`
**Location:** `medicalreport/adapter/in/router/`

```java
// Add new route for master orchestration
@Bean
public RouterFunction<ServerResponse> masterOrchestrationRoutes(
        MedicalReportOrchestrationHandler handler) {
    return RouterFunctions.route()
            .POST("/api/v1/medical-report/process", handler::processCompleteMedicalReport)
            .build();
}
```

**Deliverables:**
- ✅ New route added
- ✅ Integration with existing router

---

### Phase 1 Acceptance Criteria

✅ All domain DTOs created and validated
✅ Use case interface defined
✅ Exception classes created
✅ Router configuration updated
✅ Code compiles without errors
✅ JavaDoc documentation complete

---

## Phase 2: Core Orchestration (Week 2)

### Goal
Implement the orchestration service with sequential workflow execution.

---

### Task 2.1: Create Orchestration Service

**Priority:** 🔴 High
**Estimated Time:** 12 hours

#### Files to Create

##### 1. `MedicalReportOrchestrationService.java`
**Location:** `medicalreport/application/service/`

```java
package com.elioo.healthcare.medicalreport.application.service;

import com.elioo.healthcare.medicalreport.application.port.in.MedicalReportOrchestrationUseCase;
import com.elioo.healthcare.medicalreport.application.port.out.ClinicalInsightPort;
import com.elioo.healthcare.medicalreport.application.port.out.MedicalClassificationPort;
import com.elioo.healthcare.medicalreport.application.port.out.OcrPort;
import com.elioo.healthcare.medicalreport.domain.*;
import com.elioo.healthcare.medicalreport.domain.exception.OrchestrationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalReportOrchestrationService implements MedicalReportOrchestrationUseCase {

    private final OcrPort ocrPort;
    private final MedicalClassificationPort classificationPort;
    private final ClinicalInsightPort clinicalInsightPort;

    private static final Duration WORKFLOW_TIMEOUT = Duration.ofMinutes(2);

    @Override
    public Mono<MasterProcessingResponse> processCompleteMedicalReport(MasterProcessingRequest request) {
        log.info("Starting master orchestration for patient: {}", request.getPatientContext().getPatientId());

        String reportId = generateReportId();
        LocalDateTime startTime = LocalDateTime.now();

        // Initialize processing context
        ProcessingContext context = ProcessingContext.builder()
                .reportId(reportId)
                .request(request)
                .startTime(startTime)
                .build();

        // Execute workflow pipeline
        return Mono.just(context)
                .flatMap(this::validateImage)
                .flatMap(this::performOcr)
                .flatMap(this::detectEntities)
                .flatMap(this::inferMedicalCodes)
                .flatMap(this::generateClinicalInsights)
                .map(ctx -> buildMasterResponse(ctx, startTime))
                .timeout(WORKFLOW_TIMEOUT)
                .doOnSuccess(response -> log.info("Master orchestration completed for report: {}", reportId))
                .doOnError(error -> log.error("Master orchestration failed for report: {}", reportId, error))
                .onErrorResume(error -> handleWorkflowError(context, error, startTime));
    }

    @Override
    public Flux<ProcessingProgress> processWithProgressUpdates(MasterProcessingRequest request) {
        // TODO: Implement in Phase 3
        return Flux.error(new UnsupportedOperationException("Streaming not yet implemented"));
    }

    // ==================== Workflow Stages ====================

    /**
     * Stage 1: Validate image quality.
     */
    private Mono<ProcessingContext> validateImage(ProcessingContext context) {
        log.debug("Stage 1: Validating image for report: {}", context.getReportId());

        WorkflowOptions options = context.getRequest().getWorkflowOptions();
        if (options != null && Boolean.TRUE.equals(options.getSkipValidation())) {
            log.info("Skipping image validation as requested");
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
                                "Image quality is low (" + validationResult.qualityScore() + "). OCR results may be less accurate."
                        );
                    }

                    context.markStageCompleted(ProcessingStage.IMAGE_VALIDATION);
                    log.info("Image validation completed. Quality score: {}", validationResult.qualityScore());
                    return context;
                })
                .onErrorResume(error -> handleStageError(context, ProcessingStage.IMAGE_VALIDATION, error));
    }

    /**
     * Stage 2: Perform OCR extraction.
     */
    private Mono<ProcessingContext> performOcr(ProcessingContext context) {
        log.debug("Stage 2: Performing OCR for report: {}", context.getReportId());

        WorkflowOptions options = context.getRequest().getWorkflowOptions();
        Map<String, Object> processingOptions = new HashMap<>();
        if (options != null) {
            processingOptions.put("language", options.getLanguage());
            processingOptions.put("includeRawText", options.getIncludeRawText());
        }

        return ocrPort.extractMedicalData(
                        context.getRequest().getImageBase64(),
                        "BLOOD_TEST", // TODO: Make configurable
                        processingOptions
                )
                .collectList()
                .zipWith(ocrPort.extractRawText(
                        context.getRequest().getImageBase64(),
                        options != null ? options.getLanguage() : "en"
                ))
                .map(tuple -> {
                    List<TestResult> extractedData = tuple.getT1();
                    String rawText = tuple.getT2();

                    if (extractedData.isEmpty()) {
                        throw new OrchestrationException(
                                ProcessingStage.OCR_PROCESSING,
                                "No medical data could be extracted from image"
                        );
                    }

                    context.setOcrExtractedData(extractedData);
                    context.setOcrRawText(rawText);
                    context.setOcrConfidence(calculateOverallConfidence(extractedData));
                    context.markStageCompleted(ProcessingStage.OCR_PROCESSING);

                    log.info("OCR completed. Extracted {} test results", extractedData.size());
                    return context;
                })
                .onErrorResume(error -> handleStageError(context, ProcessingStage.OCR_PROCESSING, error));
    }

    /**
     * Stage 3: Detect medical entities.
     */
    private Mono<ProcessingContext> detectEntities(ProcessingContext context) {
        log.debug("Stage 3: Detecting entities for report: {}", context.getReportId());

        WorkflowOptions options = context.getRequest().getWorkflowOptions();

        MedicalClassificationPort.ClassificationRequest classificationRequest =
                new MedicalClassificationPort.ClassificationRequest(
                        context.getOcrRawText(),
                        options != null ? options.getLanguage() : "en",
                        options != null ? options.getRequestedCodeSystems() : List.of("ICD10", "RXNORM"),
                        options != null ? options.getConfidenceThreshold() : 0.70,
                        options != null && Boolean.TRUE.equals(options.getIncludeEntityRelationships()),
                        Map.of()
                );

        return classificationPort.classifyMedicalEntities(classificationRequest)
                .map(classificationResult -> {
                    context.setClassificationResult(classificationResult);
                    context.markStageCompleted(ProcessingStage.ENTITY_DETECTION);
                    log.info("Entity detection completed. Found {} entities",
                            classificationResult.entities().size());
                    return context;
                })
                .onErrorResume(error -> handleStageError(context, ProcessingStage.ENTITY_DETECTION, error));
    }

    /**
     * Stage 4-5: Infer medical codes (ICD-10 and RxNorm in parallel).
     */
    private Mono<ProcessingContext> inferMedicalCodes(ProcessingContext context) {
        log.debug("Stage 4-5: Inferring medical codes for report: {}", context.getReportId());

        WorkflowOptions options = context.getRequest().getWorkflowOptions();
        List<String> requestedCodeSystems = options != null && options.getRequestedCodeSystems() != null
                ? options.getRequestedCodeSystems()
                : List.of("ICD10", "RXNORM");

        // Parallel execution of code inference
        Mono<List<MedicalClassificationPort.MedicalCode>> icd10Mono =
                requestedCodeSystems.contains("ICD10")
                ? classificationPort.mapToMedicalCodes(context.getOcrRawText(), List.of("ICD10"))
                        .doOnSuccess(codes -> context.markStageCompleted(ProcessingStage.ICD10_INFERENCE))
                        .onErrorResume(error -> handleNonCriticalError(context, ProcessingStage.ICD10_INFERENCE, error))
                : Mono.just(List.of());

        Mono<List<MedicalClassificationPort.MedicalCode>> rxnormMono =
                requestedCodeSystems.contains("RXNORM")
                ? classificationPort.mapToMedicalCodes(context.getOcrRawText(), List.of("RXNORM"))
                        .doOnSuccess(codes -> context.markStageCompleted(ProcessingStage.RXNORM_INFERENCE))
                        .onErrorResume(error -> handleNonCriticalError(context, ProcessingStage.RXNORM_INFERENCE, error))
                : Mono.just(List.of());

        return Mono.zip(icd10Mono, rxnormMono)
                .map(tuple -> {
                    context.setIcd10Codes(tuple.getT1());
                    context.setRxnormCodes(tuple.getT2());
                    log.info("Medical code inference completed. ICD-10: {}, RxNorm: {}",
                            tuple.getT1().size(), tuple.getT2().size());
                    return context;
                });
    }

    /**
     * Stage 6-10: Generate clinical insights (summary, risk, recommendations, education).
     */
    private Mono<ProcessingContext> generateClinicalInsights(ProcessingContext context) {
        log.debug("Stage 6-10: Generating clinical insights for report: {}", context.getReportId());

        // Build insight request
        ClinicalInsightPort.InsightRequest insightRequest = buildInsightRequest(context);

        return clinicalInsightPort.generateClinicalInsights(insightRequest)
                .map(insightResult -> {
                    context.setClinicalInsights(insightResult);
                    context.markStageCompleted(ProcessingStage.CLINICAL_INSIGHTS);
                    context.markStageCompleted(ProcessingStage.PATIENT_SUMMARY);
                    context.markStageCompleted(ProcessingStage.RISK_ASSESSMENT);
                    context.markStageCompleted(ProcessingStage.RECOMMENDATIONS);

                    if (insightResult.educationalContent() != null) {
                        context.markStageCompleted(ProcessingStage.EDUCATIONAL_CONTENT);
                    }

                    log.info("Clinical insights generated successfully");
                    return context;
                })
                .onErrorResume(error -> handleNonCriticalError(context, ProcessingStage.CLINICAL_INSIGHTS, error));
    }

    // ==================== Helper Methods ====================

    private ClinicalInsightPort.InsightRequest buildInsightRequest(ProcessingContext context) {
        MasterProcessingRequest request = context.getRequest();
        WorkflowOptions options = request.getWorkflowOptions();

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

        // Build extracted data map
        Map<String, Object> extractedData = new HashMap<>();
        context.getOcrExtractedData().forEach(testResult -> {
            extractedData.put(testResult.getTestName(), Map.of(
                    "value", testResult.getTestValue(),
                    "unit", testResult.getUnit(),
                    "status", testResult.getStatus().name()
            ));
        });

        // Build classification result map
        Map<String, Object> classificationResult = new HashMap<>();
        if (context.getClassificationResult() != null) {
            classificationResult.put("entities", context.getClassificationResult().entities());
            classificationResult.put("medicalCodes", context.getClassificationResult().medicalCodes());
        }

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
                options != null ? options.getTargetAudience() : "PATIENT",
                options != null ? options.getLanguage() : "en",
                options != null && Boolean.TRUE.equals(options.getIncludeEducationalContent()),
                "STANDARD"
        );
    }

    private ClinicalInsightPort.RiskAssessmentOptions buildRiskOptions(WorkflowOptions options) {
        return new ClinicalInsightPort.RiskAssessmentOptions(
                options != null ? options.getRiskAssessmentCategories() : List.of("CARDIOVASCULAR", "METABOLIC", "RENAL", "HEPATIC"),
                true,
                "12_MONTHS"
        );
    }

    private Map<String, Double> convertVitalSigns(Map<String, Object> vitalSigns) {
        if (vitalSigns == null) return Map.of();

        Map<String, Double> converted = new HashMap<>();
        vitalSigns.forEach((key, value) -> {
            if (value instanceof Number) {
                converted.put(key, ((Number) value).doubleValue());
            }
        });
        return converted;
    }

    private MasterProcessingResponse buildMasterResponse(ProcessingContext context, LocalDateTime startTime) {
        long processingTimeMs = Duration.between(startTime, LocalDateTime.now()).toMillis();

        // Determine processing status
        ProcessingStatus status;
        if (context.hasCriticalFailure()) {
            status = ProcessingStatus.FAILED;
        } else if (!context.getFailedStages().isEmpty()) {
            status = ProcessingStatus.PARTIAL_SUCCESS;
        } else {
            status = ProcessingStatus.COMPLETED;
        }

        return MasterProcessingResponse.builder()
                .reportId(context.getReportId())
                .patientId(context.getRequest().getPatientContext().getPatientId())
                .processingStatus(status)
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
                .relationships(result.relationships())
                .entityCount(result.entities().size())
                .modelVersion(result.metadata().get("modelVersion").toString())
                .build();
    }

    private MasterProcessingResponse.MedicalCodesResult buildMedicalCodesResult(ProcessingContext context) {
        List<MasterProcessingResponse.MedicalCode> icd10 = convertMedicalCodes(context.getIcd10Codes(), "DIAGNOSIS");
        List<MasterProcessingResponse.MedicalCode> rxnorm = convertMedicalCodes(context.getRxnormCodes(), "MEDICATION");

        return MasterProcessingResponse.MedicalCodesResult.builder()
                .icd10(icd10)
                .rxnorm(rxnorm)
                .totalCodes(icd10.size() + rxnorm.size())
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
                .toList();
    }

    private MasterProcessingResponse.ClinicalInsightsResult buildClinicalInsightsResult(ProcessingContext context) {
        if (context.getClinicalInsights() == null) return null;

        ClinicalInsightPort.ClinicalInsightResult insights = context.getClinicalInsights();

        // Convert library DTOs to response DTOs
        List<MasterProcessingResponse.KeyFinding> keyFindings = convertKeyFindings(insights.keyFindings());
        MasterProcessingResponse.RiskAssessment riskAssessment = convertRiskAssessment(insights.riskAssessment());
        List<MasterProcessingResponse.Recommendation> recommendations = convertRecommendations(insights.aiSuggestions());
        MasterProcessingResponse.ActionPlan actionPlan = convertActionPlan(insights.actionPlan());
        List<MasterProcessingResponse.EducationalContent> educationalContent = convertEducationalContent(insights.educationalContent());

        return MasterProcessingResponse.ClinicalInsightsResult.builder()
                .summary(insights.summary())
                .keyFindings(keyFindings)
                .riskAssessment(riskAssessment)
                .recommendations(recommendations)
                .actionPlan(actionPlan)
                .educationalContent(educationalContent)
                .build();
    }

    // Additional conversion methods...
    // (truncated for brevity - full implementation would include all conversion methods)

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
        return testResults.stream()
                .mapToDouble(TestResult::getConfidence)
                .average()
                .orElse(0.0);
    }

    private String generateReportId() {
        return "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // ==================== Error Handling ====================

    private Mono<ProcessingContext> handleStageError(
            ProcessingContext context, ProcessingStage stage, Throwable error) {
        log.error("Stage {} failed for report: {}", stage, context.getReportId(), error);

        context.markStageFailed(stage, error.getMessage(), isRetryableError(error));

        if (stage.isCritical()) {
            return Mono.error(new OrchestrationException(stage, "Critical stage failed", error));
        }

        // Non-critical failure - continue workflow
        return Mono.just(context);
    }

    private Mono<List<MedicalClassificationPort.MedicalCode>> handleNonCriticalError(
            ProcessingContext context, ProcessingStage stage, Throwable error) {
        log.warn("Non-critical stage {} failed for report: {}", stage, context.getReportId(), error);
        context.markStageFailed(stage, error.getMessage(), isRetryableError(error));
        return Mono.just(List.of());
    }

    private Mono<MasterProcessingResponse> handleWorkflowError(
            ProcessingContext context, Throwable error, LocalDateTime startTime) {
        log.error("Workflow failed for report: {}", context.getReportId(), error);

        ProcessingStatus status;
        if (error instanceof OrchestrationException oe && oe.isCritical()) {
            status = ProcessingStatus.FAILED;
        } else {
            status = ProcessingStatus.PARTIAL_SUCCESS;
        }

        long processingTimeMs = Duration.between(startTime, LocalDateTime.now()).toMillis();

        return Mono.just(MasterProcessingResponse.builder()
                .reportId(context.getReportId())
                .patientId(context.getRequest().getPatientContext().getPatientId())
                .processingStatus(status)
                .processingTimeMs(processingTimeMs)
                .timestamp(LocalDateTime.now())
                .workflow(buildWorkflowSummary(context))
                .errors(context.getErrors())
                .warnings(context.getWarnings())
                .build());
    }

    private boolean isRetryableError(Throwable error) {
        // Check if error is retryable (network issues, throttling, etc.)
        String message = error.getMessage();
        return message != null && (
                message.contains("timeout") ||
                message.contains("throttl") ||
                message.contains("rate limit") ||
                message.contains("unavailable")
        );
    }
}
```

**Key Features:**
- ✅ Complete orchestration logic
- ✅ Sequential workflow execution
- ✅ Comprehensive error handling
- ✅ Partial success support
- ✅ Immutable context pattern
- ✅ Detailed logging

**Deliverables:**
- ✅ Service implementation complete
- ✅ All 10 workflow stages implemented
- ✅ Error handling for critical and non-critical failures
- ✅ Response building logic

---

### Task 2.2: Create Handler

**Priority:** 🔴 High
**Estimated Time:** 2 hours

#### Files to Create

##### 1. `MedicalReportOrchestrationHandler.java`
**Location:** `medicalreport/adapter/in/handler/`

```java
package com.elioo.healthcare.medicalreport.adapter.in.handler;

import com.elioo.healthcare.medicalreport.application.port.in.MedicalReportOrchestrationUseCase;
import com.elioo.healthcare.medicalreport.domain.MasterProcessingRequest;
import com.elioo.healthcare.medicalreport.domain.MasterProcessingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Web handler for master orchestration API.
 *
 * <p>Architecture: Inbound Adapter (Driving Adapter) in Hexagonal Architecture</p>
 * <ul>
 *   <li>Converts HTTP requests to domain objects</li>
 *   <li>Delegates to use case (inbound port)</li>
 *   <li>Converts domain responses to HTTP responses</li>
 *   <li>No business logic - pure adapter</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MedicalReportOrchestrationHandler {

    private final MedicalReportOrchestrationUseCase orchestrationUseCase;

    /**
     * Handle master processing request.
     * POST /api/v1/medical-report/process
     */
    public Mono<ServerResponse> processCompleteMedicalReport(ServerRequest request) {
        return request.bodyToMono(MasterProcessingRequest.class)
                .flatMap(masterRequest -> {
                    log.info("Received master processing request for patient: {}",
                            masterRequest.getPatientContext().getPatientId());

                    return orchestrationUseCase.processCompleteMedicalReport(masterRequest);
                })
                .flatMap(response -> {
                    // Choose appropriate HTTP status based on processing status
                    return switch (response.getProcessingStatus()) {
                        case COMPLETED -> ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(response);
                        case PARTIAL_SUCCESS -> ServerResponse.status(206) // 206 Partial Content
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(response);
                        case VALIDATION_FAILED -> ServerResponse.status(422) // 422 Unprocessable Entity
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(response);
                        case FAILED, TIMEOUT -> ServerResponse.status(500)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(response);
                    };
                })
                .onErrorResume(this::handleError);
    }

    private Mono<ServerResponse> handleError(Throwable error) {
        log.error("Error processing master request", error);

        ErrorResponse errorResponse = new ErrorResponse(
                "PROCESSING_FAILED",
                error.getMessage(),
                Map.of("stage", "UNKNOWN")
        );

        return ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(errorResponse);
    }

    private record ErrorResponse(String code, String message, Map<String, Object> details) {}
}
```

**Deliverables:**
- ✅ Handler created
- ✅ HTTP status mapping
- ✅ Error handling

---

### Task 2.3: Integration Testing

**Priority:** 🟡 Medium
**Estimated Time:** 4 hours

#### Files to Create

##### 1. `MedicalReportOrchestrationServiceTest.java`
**Location:** `test/.../medicalreport/application/service/`

```java
// Unit tests with mocked ports
@ExtendWith(MockitoExtension.class)
class MedicalReportOrchestrationServiceTest {

    @Mock
    private OcrPort ocrPort;

    @Mock
    private MedicalClassificationPort classificationPort;

    @Mock
    private ClinicalInsightPort clinicalInsightPort;

    @InjectMocks
    private MedicalReportOrchestrationService service;

    @Test
    void shouldProcessCompleteWorkflowSuccessfully() {
        // Given: All stages return success
        // When: processCompleteMedicalReport called
        // Then: Response should have COMPLETED status
    }

    @Test
    void shouldHandlePartialSuccessWhenNonCriticalStageFails() {
        // Given: ICD-10 inference fails
        // When: processCompleteMedicalReport called
        // Then: Response should have PARTIAL_SUCCESS status with ICD-10 codes empty
    }

    @Test
    void shouldFailWhenCriticalStageFails() {
        // Given: OCR processing fails
        // When: processCompleteMedicalReport called
        // Then: Error should be thrown
    }

    // Additional test cases...
}
```

**Deliverables:**
- ✅ Unit tests with 80%+ coverage
- ✅ Integration test with real AWS services
- ✅ Error scenario tests

---

### Phase 2 Acceptance Criteria

✅ Orchestration service fully implemented
✅ All 10 workflow stages working
✅ Handler created and integrated
✅ Unit tests passing with 80%+ coverage
✅ Integration test with real AWS services passing
✅ Error handling validated with test cases

---

## Phase 3: Database Persistence & Async Processing (Week 3)

### Goal
Store all processing steps and results in PostgreSQL database to enable:
1. Complete audit trail of all processing stages
2. Historical data analysis and reporting
3. Resume failed workflows
4. Future async processing support
5. Data retention and compliance

---

### Task 3.1: Database Schema Design & Implementation

**Priority:** 🔴 High
**Estimated Time:** 8 hours

#### 3.1.1: Create Domain Entities

**File:** `MedicalReportProcess.java`
```java
@Table("medical_report_process")
public class MedicalReportProcessEntity {
    @Id
    private String reportId;              // Primary key
    private String patientId;             // Foreign key to patient
    private ProcessingStatus status;      // PENDING, IN_PROGRESS, COMPLETED, FAILED, PARTIAL_SUCCESS
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private Long processingTimeMs;

    // Request data
    private String imageBase64;           // Original image (consider moving to S3)
    private String patientContextJson;    // JSON of patient context
    private String workflowOptionsJson;   // JSON of workflow options

    // Summary counters
    private Integer completedStages;
    private Integer failedStages;
    private Integer totalStages;

    // Metadata
    private String createdBy;
    private String errorMessage;          // If failed
}
```

**File:** `MedicalReportProcessStage.java`
```java
@Table("medical_report_process_stage")
public class MedicalReportProcessStageEntity {
    @Id
    private String id;                    // UUID
    private String reportId;              // Foreign key to medical_report_process
    private ProcessingStage stage;        // IMAGE_VALIDATION, OCR_PROCESSING, etc.
    private StageStatus status;           // PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;

    // Stage-specific data
    private String inputDataJson;         // Input to this stage
    private String outputDataJson;        // Output from this stage (JSONB)
    private String errorMessage;          // If failed
    private Boolean isRetryable;
    private Integer attemptNumber;        // Retry counter

    // Quality metrics
    private Double confidenceScore;
    private String qualityMetricsJson;    // Stage-specific quality metrics
}
```

**File:** `MedicalReportError.java`
```java
@Table("medical_report_error")
public class MedicalReportErrorEntity {
    @Id
    private String id;
    private String reportId;              // Foreign key
    private String stageId;               // Foreign key (nullable)
    private ProcessingStage stage;
    private String errorCode;             // OCR_PROCESSING_FAILED, etc.
    private String errorMessage;
    private String stackTrace;            // Full stack trace for debugging
    private Boolean isRetryable;
    private LocalDateTime occurredAt;
    private String severity;              // ERROR, WARNING, INFO
}
```

**File:** `MedicalReportResult.java`
```java
@Table("medical_report_result")
public class MedicalReportResultEntity {
    @Id
    private String id;
    private String reportId;              // Foreign key
    private String resultType;            // OCR, CLASSIFICATION, ICD10, RXNORM, INSIGHTS, etc.
    private String resultDataJson;        // Complete result as JSONB
    private Double confidenceScore;
    private LocalDateTime createdAt;

    // Searchable extracted fields (for querying)
    private Integer testCount;            // For OCR results
    private Integer entityCount;          // For classification results
    private Integer codeCount;            // For medical codes
    private String riskLevel;             // For risk assessment (LOW, MODERATE, HIGH, CRITICAL)
}
```

#### 3.1.2: Create Database Migration Scripts

**File:** `V1__create_medical_report_tables.sql`
```sql
-- Main process tracking table
CREATE TABLE medical_report_process (
    report_id VARCHAR(50) PRIMARY KEY,
    patient_id VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    processing_time_ms BIGINT,

    image_base64 TEXT,
    patient_context_json JSONB,
    workflow_options_json JSONB,

    completed_stages INTEGER DEFAULT 0,
    failed_stages INTEGER DEFAULT 0,
    total_stages INTEGER DEFAULT 10,

    created_by VARCHAR(100),
    error_message TEXT,

    INDEX idx_patient_id (patient_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
);

-- Stage-level tracking
CREATE TABLE medical_report_process_stage (
    id VARCHAR(50) PRIMARY KEY,
    report_id VARCHAR(50) NOT NULL REFERENCES medical_report_process(report_id) ON DELETE CASCADE,
    stage VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    duration_ms BIGINT,

    input_data_json JSONB,
    output_data_json JSONB,
    error_message TEXT,
    is_retryable BOOLEAN DEFAULT FALSE,
    attempt_number INTEGER DEFAULT 1,

    confidence_score DECIMAL(5,4),
    quality_metrics_json JSONB,

    INDEX idx_report_id (report_id),
    INDEX idx_stage (stage),
    INDEX idx_status (status),
    INDEX idx_started_at (started_at)
);

-- Error tracking
CREATE TABLE medical_report_error (
    id VARCHAR(50) PRIMARY KEY,
    report_id VARCHAR(50) NOT NULL REFERENCES medical_report_process(report_id) ON DELETE CASCADE,
    stage_id VARCHAR(50) REFERENCES medical_report_process_stage(id) ON DELETE SET NULL,
    stage VARCHAR(50),
    error_code VARCHAR(100) NOT NULL,
    error_message TEXT NOT NULL,
    stack_trace TEXT,
    is_retryable BOOLEAN DEFAULT FALSE,
    occurred_at TIMESTAMP NOT NULL DEFAULT NOW(),
    severity VARCHAR(20) NOT NULL,

    INDEX idx_report_id (report_id),
    INDEX idx_error_code (error_code),
    INDEX idx_occurred_at (occurred_at)
);

-- Results storage
CREATE TABLE medical_report_result (
    id VARCHAR(50) PRIMARY KEY,
    report_id VARCHAR(50) NOT NULL REFERENCES medical_report_process(report_id) ON DELETE CASCADE,
    result_type VARCHAR(50) NOT NULL,
    result_data_json JSONB NOT NULL,
    confidence_score DECIMAL(5,4),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    test_count INTEGER,
    entity_count INTEGER,
    code_count INTEGER,
    risk_level VARCHAR(20),

    INDEX idx_report_id (report_id),
    INDEX idx_result_type (result_type),
    INDEX idx_created_at (created_at),
    INDEX idx_risk_level (risk_level)
);

-- GIN index for JSONB fields (for fast JSON querying)
CREATE INDEX idx_process_patient_context ON medical_report_process USING GIN (patient_context_json);
CREATE INDEX idx_stage_output_data ON medical_report_process_stage USING GIN (output_data_json);
CREATE INDEX idx_result_data ON medical_report_result USING GIN (result_data_json);
```

---

### Task 3.2: Persistence Layer Implementation

**Priority:** 🔴 High
**Estimated Time:** 10 hours

#### 3.2.1: Create Repositories

**File:** `MedicalReportProcessRepository.java`
```java
public interface MedicalReportProcessRepository extends R2dbcRepository<MedicalReportProcessEntity, String> {
    Flux<MedicalReportProcessEntity> findByPatientId(String patientId);
    Flux<MedicalReportProcessEntity> findByStatus(ProcessingStatus status);
    Flux<MedicalReportProcessEntity> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    @Query("SELECT * FROM medical_report_process WHERE status = 'FAILED' AND created_at > :since")
    Flux<MedicalReportProcessEntity> findRecentFailures(LocalDateTime since);
}
```

**File:** `MedicalReportProcessStageRepository.java`
**File:** `MedicalReportErrorRepository.java`
**File:** `MedicalReportResultRepository.java`

#### 3.2.2: Create Persistence Ports (Outbound)

**File:** `MedicalReportProcessPersistencePort.java`
```java
public interface MedicalReportProcessPersistencePort {
    // Process tracking
    Mono<MedicalReportProcess> createProcess(MasterProcessingRequest request);
    Mono<MedicalReportProcess> updateProcessStatus(String reportId, ProcessingStatus status);
    Mono<MedicalReportProcess> completeProcess(String reportId, MasterProcessingResponse response);
    Mono<MedicalReportProcess> failProcess(String reportId, String errorMessage);
    Mono<MedicalReportProcess> findByReportId(String reportId);

    // Stage tracking
    Mono<ProcessStage> createStage(String reportId, ProcessingStage stage);
    Mono<ProcessStage> startStage(String stageId);
    Mono<ProcessStage> completeStage(String stageId, Object output, Double confidence);
    Mono<ProcessStage> failStage(String stageId, String errorMessage, Boolean retryable);
    Flux<ProcessStage> findStagesByReportId(String reportId);

    // Error tracking
    Mono<ProcessError> recordError(String reportId, ProcessingStage stage, Throwable error);
    Flux<ProcessError> findErrorsByReportId(String reportId);

    // Result storage
    Mono<ProcessResult> saveResult(String reportId, String resultType, Object resultData, Double confidence);
    Flux<ProcessResult> findResultsByReportId(String reportId);
    Mono<ProcessResult> findResultByType(String reportId, String resultType);

    // Analytics queries
    Mono<Long> countProcessesByStatus(ProcessingStatus status);
    Mono<Double> getAverageProcessingTime(LocalDateTime since);
    Flux<StageStatistics> getStageStatistics(LocalDateTime since);
}
```

#### 3.2.3: Implement Persistence Adapter

**File:** `MedicalReportProcessPersistenceAdapter.java`
- Map domain objects to entities
- Implement all port methods
- Handle transactions with `@Transactional`
- Serialize/deserialize JSON fields

---

### Task 3.3: Integrate Persistence into Orchestration Service

**Priority:** 🔴 High
**Estimated Time:** 8 hours

#### Changes to `MedicalReportOrchestrationService.java`

**Add persistence calls at each stage:**

```java
@Override
public Mono<MasterProcessingResponse> processCompleteMedicalReport(MasterProcessingRequest request) {
    String reportId = generateReportId();
    LocalDateTime startTime = LocalDateTime.now();

    // 1️⃣ Create process record in database
    return persistencePort.createProcess(request)
            .flatMap(process -> {
                ProcessingContext context = ProcessingContext.builder()
                        .reportId(reportId)
                        .request(request)
                        .startTime(startTime)
                        .build();

                return Mono.just(context)
                        .flatMap(this::validateImageWithPersistence)      // Save stage data
                        .flatMap(this::performOcrWithPersistence)         // Save stage data
                        .flatMap(this::detectEntitiesWithPersistence)     // Save stage data
                        .flatMap(this::inferMedicalCodesWithPersistence)  // Save stage data
                        .flatMap(this::generateInsightsWithPersistence)   // Save stage data
                        .flatMap(ctx -> buildAndSaveResponse(ctx, startTime));
            })
            .timeout(WORKFLOW_TIMEOUT)
            .onErrorResume(error -> handleWorkflowErrorWithPersistence(reportId, error, startTime));
}

private Mono<ProcessingContext> validateImageWithPersistence(ProcessingContext context) {
    return persistencePort.createStage(context.getReportId(), ProcessingStage.IMAGE_VALIDATION)
            .flatMap(stage -> {
                return persistencePort.startStage(stage.getId())
                        .then(validateImage(context))
                        .flatMap(ctx -> {
                            // Save success
                            return persistencePort.completeStage(
                                    stage.getId(),
                                    ctx.getImageValidation(),
                                    ctx.getImageValidation().qualityScore()
                            ).thenReturn(ctx);
                        })
                        .onErrorResume(error -> {
                            // Save failure
                            return persistencePort.failStage(stage.getId(), error.getMessage(), isRetryableError(error))
                                    .then(persistencePort.recordError(context.getReportId(), ProcessingStage.IMAGE_VALIDATION, error))
                                    .then(Mono.error(error));
                        });
            });
}

private Mono<MasterProcessingResponse> buildAndSaveResponse(ProcessingContext ctx, LocalDateTime startTime) {
    MasterProcessingResponse response = buildMasterResponse(ctx, startTime);

    // Save final response and all results
    return persistencePort.completeProcess(ctx.getReportId(), response)
            .then(saveAllResults(ctx))
            .thenReturn(response);
}

private Mono<Void> saveAllResults(ProcessingContext ctx) {
    return Flux.concat(
            persistencePort.saveResult(ctx.getReportId(), "OCR", ctx.getOcrExtractedData(), calculateAvgConfidence(ctx.getOcrExtractedData())),
            persistencePort.saveResult(ctx.getReportId(), "CLASSIFICATION", ctx.getClassificationResult(), ctx.getClassificationResult().overallConfidence()),
            persistencePort.saveResult(ctx.getReportId(), "ICD10", ctx.getIcd10Codes(), null),
            persistencePort.saveResult(ctx.getReportId(), "RXNORM", ctx.getRxnormCodes(), null),
            persistencePort.saveResult(ctx.getReportId(), "CLINICAL_INSIGHTS", ctx.getClinicalInsights(), null)
    ).then();
}
```

---

### Task 3.4: Parallel Processing Optimization (Already Implemented)

**Priority:** 🟡 Medium
**Estimated Time:** 2 hours

**Status:** ✅ Already implemented in Phase 2
- ICD-10 + RxNorm inference run in parallel using `Mono.zip`
- Expected performance improvement: ~2 seconds saved per request

**Future optimization:**
- Run Summary + Risk + Recommendations in parallel (Phase 4)

---

### Task 3.5: Progress Streaming with Database Updates

**Priority:** 🟢 Low
**Estimated Time:** 4 hours

Implement `processWithProgressUpdates()` method with Server-Sent Events (SSE).

**File:** `MedicalReportOrchestrationService.java`
```java
@Override
public Flux<ProcessingProgress> processWithProgressUpdates(MasterProcessingRequest request) {
    String reportId = generateReportId();

    return Flux.create(sink -> {
        processCompleteMedicalReport(request)
                .doOnNext(response -> {
                    // Stream progress updates from database
                    persistencePort.findStagesByReportId(reportId)
                            .map(stage -> new ProcessingProgress(
                                    reportId,
                                    stage.getStage(),
                                    stage.getStatus(),
                                    calculateProgress(reportId)
                            ))
                            .subscribe(sink::next);
                })
                .doOnError(sink::error)
                .doFinally(signal -> sink.complete())
                .subscribe();
    });
}
```

**New Endpoint:**
```java
@GetMapping(value = "/api/v1/medical-report/process/{reportId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ProcessingProgress> streamProgress(@PathVariable String reportId) {
    return orchestrationService.streamProgressForReport(reportId);
}
```

---

### Task 3.6: Query & Analytics APIs

**Priority:** 🟡 Medium
**Estimated Time:** 6 hours

Create REST APIs for querying stored data.

**File:** `MedicalReportQueryHandler.java`
```java
@Component
@RequiredArgsConstructor
public class MedicalReportQueryHandler {

    // Get report by ID with all details
    public Mono<ServerResponse> getReportById(ServerRequest request) {
        String reportId = request.pathVariable("reportId");
        return persistencePort.findByReportId(reportId)
                .flatMap(this::enrichWithAllData)
                .flatMap(report -> ServerResponse.ok().bodyValue(report));
    }

    // Get all reports for a patient
    public Mono<ServerResponse> getPatientReports(ServerRequest request) {
        String patientId = request.pathVariable("patientId");
        return persistencePort.findByPatientId(patientId)
                .collectList()
                .flatMap(reports -> ServerResponse.ok().bodyValue(reports));
    }

    // Get failed reports for investigation
    public Mono<ServerResponse> getFailedReports(ServerRequest request) {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        return persistencePort.findRecentFailures(since)
                .collectList()
                .flatMap(reports -> ServerResponse.ok().bodyValue(reports));
    }

    // Get processing statistics
    public Mono<ServerResponse> getStatistics(ServerRequest request) {
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        return Mono.zip(
                persistencePort.countProcessesByStatus(ProcessingStatus.COMPLETED),
                persistencePort.countProcessesByStatus(ProcessingStatus.FAILED),
                persistencePort.getAverageProcessingTime(since),
                persistencePort.getStageStatistics(since).collectList()
        ).map(tuple -> Map.of(
                "completedCount", tuple.getT1(),
                "failedCount", tuple.getT2(),
                "avgProcessingTimeMs", tuple.getT3(),
                "stageStats", tuple.getT4()
        ))
        .flatMap(stats -> ServerResponse.ok().bodyValue(stats));
    }
}
```

**New Routes:**
```java
GET  /api/v1/medical-report/reports/{reportId}           - Get report details
GET  /api/v1/medical-report/patients/{patientId}/reports - Get patient reports
GET  /api/v1/medical-report/reports/failed               - Get recent failures
GET  /api/v1/medical-report/reports/statistics           - Get processing stats
GET  /api/v1/medical-report/process/{reportId}/progress  - Stream progress (SSE)
```

---

### Task 3.7: Data Retention & Cleanup

**Priority:** 🟢 Low
**Estimated Time:** 3 hours

Implement scheduled cleanup for old data.

**File:** `DataCleanupScheduler.java`
```java
@Component
@RequiredArgsConstructor
public class DataCleanupScheduler {

    @Scheduled(cron = "0 0 2 * * ?") // 2 AM daily
    public void cleanupOldImageData() {
        // Remove base64 images older than 30 days (keep metadata)
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        processRepository.clearImageDataOlderThan(cutoff);
    }

    @Scheduled(cron = "0 0 3 * * ?") // 3 AM daily
    public void archiveOldReports() {
        // Archive completed reports older than 90 days
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        processRepository.archiveCompletedReports(cutoff);
    }
}
```

---

### Phase 3 Acceptance Criteria

✅ Database schema created and migrated
✅ All 4 tables created with proper indexes
✅ Persistence layer fully implemented
✅ Orchestration service saves all stages and results
✅ Query APIs working for report retrieval
✅ Progress streaming endpoint functional
✅ Data retention policies implemented
✅ All tests passing (unit + integration)

**Database Verification:**
```sql
-- Should have data in all 4 tables after processing
SELECT COUNT(*) FROM medical_report_process;           -- Should have records
SELECT COUNT(*) FROM medical_report_process_stage;     -- Should have 10 stages per report
SELECT COUNT(*) FROM medical_report_error;             -- Should have errors from failures
SELECT COUNT(*) FROM medical_report_result;            -- Should have results per report
```

---

### Phase 3 Benefits

1. **Complete Audit Trail**: Every stage tracked with timestamps and results
2. **Debugging**: Easy to see exactly where and why a process failed
3. **Analytics**: Query historical data for insights and trends
4. **Resume Failed Jobs**: Can retry failed stages (future enhancement)
5. **Async Processing**: Foundation for queue-based async processing (Phase 4)
6. **Compliance**: Data retention and audit logs for regulatory requirements
7. **Performance Monitoring**: Track stage durations and bottlenecks
8. **Cost Analysis**: Calculate AWS costs per report from processing metrics

---

## Phase 4: Production Readiness (Week 4)

### Goal
Production deployment, monitoring, and documentation.

---

### Task 4.1: Comprehensive Testing

**Priority:** 🔴 High
**Estimated Time:** 8 hours

1. End-to-end tests with real medical reports
2. Load testing (100 requests/minute)
3. Failure scenario testing
4. Security testing

---

### Task 4.2: Monitoring & Observability

**Priority:** 🔴 High
**Estimated Time:** 4 hours

1. Add Micrometer metrics
2. Configure distributed tracing
3. Set up alerting
4. Create dashboards

---

### Task 4.3: Documentation

**Priority:** 🟡 Medium
**Estimated Time:** 4 hours

1. Update API documentation
2. Create runbook for operations
3. Write troubleshooting guide
4. Document cost optimization strategies

---

### Phase 4 Acceptance Criteria

✅ All tests passing (unit, integration, e2e)
✅ Load testing validates 100 req/min throughput
✅ Monitoring dashboards operational
✅ Documentation complete
✅ Production deployment successful

---

## Testing Strategy

### Test Pyramid

```
        /\
       /E2E\        10 tests
      /------\
     /  INT   \     30 tests
    /----------\
   /    UNIT    \   100 tests
  /--------------\
```

### Test Coverage Goals

- Unit Tests: 85%+
- Integration Tests: 70%+
- E2E Tests: Critical paths

---

## Deployment Strategy

### Phase 1: Canary Deployment

1. Deploy to 10% of traffic
2. Monitor for 24 hours
3. Check error rates, latency, costs

### Phase 2: Gradual Rollout

1. 25% → 50% → 75% → 100%
2. Monitor at each stage
3. Rollback plan ready

### Phase 3: Full Production

1. 100% traffic
2. Continuous monitoring
3. Cost optimization

---

## Monitoring & Observability

### Key Metrics

1. **Performance**
   - Processing time per stage
   - Total workflow duration
   - Throughput (reports/minute)

2. **Reliability**
   - Success rate
   - Partial success rate
   - Error rate by stage

3. **Cost**
   - AWS service costs per report
   - Monthly cost trends

4. **Business**
   - Reports processed per day
   - Average confidence scores
   - Critical findings detected

---

## Risk Management

### Risks & Mitigation

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| AWS service outage | High | Low | Implement circuit breaker, fallback to cached results |
| Cost overrun | High | Medium | Set budget alerts, optimize API calls |
| Poor performance | Medium | Medium | Parallel processing, caching |
| Data quality issues | High | Medium | Comprehensive validation, quality checks |

---

## Success Criteria

✅ API processes 100+ reports/day reliably
✅ Average processing time < 15 seconds
✅ Success rate > 95%
✅ Cost per report < $0.10
✅ Zero critical security vulnerabilities
✅ Documentation complete and accurate

---

## Next Steps

1. **Review and Approve** this implementation plan
2. **Set Up Development Environment** with all dependencies
3. **Start Phase 1** - Foundation (Week 1)
4. **Daily Stand-ups** to track progress
5. **Weekly Demos** to stakeholders

---

**Document Version:** 1.0
**Last Updated:** 2024-01-15
**Author:** Development Team
**Status:** Ready for Review
