# SNOMED CT Integration Plan for MedScribe AI

## Executive Summary

This document outlines the implementation plan for adding SNOMED CT (Systematized Nomenclature of Medicine - Clinical Terms) support to the MedScribe AI medical report processing pipeline. The implementation follows the existing patterns established for ICD-10-CM and RxNorm code inference.

**Status:** The SNOMED CT API is already implemented in the `elioo-aws-comprehend-medical` library module. This plan focuses on wiring it into the main application's orchestration workflow with proper persistence.

---

## Table of Contents

1. [Current Architecture](#current-architecture)
2. [What Already Exists](#what-already-exists)
3. [What Needs to be Added](#what-needs-to-be-added)
4. [Detailed Implementation Plan](#detailed-implementation-plan)
5. [Files to Modify](#files-to-modify)
6. [Implementation Order](#implementation-order)
7. [Testing Plan](#testing-plan)
8. [API Endpoint](#api-endpoint)

---

## Current Architecture

### Medical Code Processing Flow

```
┌──────────────────────────────────────────────────────────────────────┐
│                    CURRENT WORKFLOW (10 Stages)                       │
├──────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  Stage 1: IMAGE_VALIDATION                                           │
│  Stage 2: OCR_PROCESSING                                             │
│  Stage 2.5: TRANSLATION (if non-English)                             │
│  Stage 3: ENTITY_DETECTION                                           │
│  Stage 4: ICD10_INFERENCE  ←── Persisted as "ICD10"                  │
│  Stage 5: RXNORM_INFERENCE ←── Persisted as "RXNORM"                 │
│  Stage 6: SNOMEDCT_INFERENCE ←── TO BE ADDED, persisted as "SNOMEDCT"│
│  Stage 7-10: CLINICAL_INSIGHTS, RISK_ASSESSMENT, RECOMMENDATIONS...  │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

### Query API Pattern

```
GET /api/v1/medical-report/query/results/{reportId}/ocr            ← Exists
GET /api/v1/medical-report/query/results/{reportId}/classification ← Exists
GET /api/v1/medical-report/query/results/{reportId}/icd10          ← Exists
GET /api/v1/medical-report/query/results/{reportId}/rxnorm         ← Exists
GET /api/v1/medical-report/query/results/{reportId}/snomedct       ← TO BE ADDED
```

---

## What Already Exists

### 1. Library Module: `elioo-aws-comprehend-medical`

The SNOMED CT API is **fully implemented** in the library:

**File:** `elioo-aws-comprehend-medical/src/main/java/com/elioo/healthcare/aws/comprehendmedical/api/ComprehendMedicalService.java`
```java
Mono<List<MedicalCode>> inferSNOMEDCTCodes(String text);
```

**File:** `elioo-aws-comprehend-medical/src/main/java/com/elioo/healthcare/aws/comprehendmedical/service/ComprehendMedicalServiceImpl.java`
```java
@Override
public Mono<List<MedicalCode>> inferSNOMEDCTCodes(String text) {
    return validateText(text)
        .then(Mono.fromFuture(() -> {
            InferSnomedctRequest request = InferSnomedctRequest.builder()
                .text(text)
                .build();
            return comprehendMedicalClient.inferSNOMEDCT(request);
        }))
        .map(response -> response.entities().stream()
            .flatMap(entity -> entity.snomedctConcepts().stream()
                .map(concept -> new MedicalCode(
                    concept.code(),
                    concept.description(),
                    "SNOMED-CT",
                    concept.score()
                ))
            )
            .toList()
        );
}
```

**File:** `elioo-aws-comprehend-medical/src/main/java/com/elioo/healthcare/aws/comprehendmedical/model/MedicalCode.java`
```java
public boolean isSNOMEDCT() {
    return "SNOMED-CT".equalsIgnoreCase(codeSystem);
}
```

### 2. Adapter (Blocked - Needs Wiring)

**File:** `medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/ComprehendMedicalAdapter.java`

**Current Code (Lines 203-207) - INCORRECT:**
```java
case "SNOMED", "SNOMED-CT" -> {
    // Note: AWS Comprehend Medical doesn't have a direct InferSNOMEDCT API
    log.warn("SNOMED-CT inference not directly supported by AWS Comprehend Medical");
    codeMonos.add(Mono.just(Map.of("SNOMED", List.of())));
}
```

**The comment is WRONG** - AWS Comprehend Medical DOES support SNOMED CT via `inferSNOMEDCT()`. This needs to be fixed.

---

## What Needs to be Added

### Summary of Changes

| Component | Change Type | Description |
|-----------|-------------|-------------|
| ComprehendMedicalAdapter | **FIX** | Wire SNOMED CT call (replace empty list with actual API call) |
| ProcessingContext | **ADD** | Add `snomedctCodes` field |
| MasterProcessingResponse | **ADD** | Add `snomedct` to `MedicalCodesResult` |
| WorkflowOptions | **UPDATE** | Add "SNOMEDCT" to default code systems |
| MedicalReportOrchestrationService | **ADD** | Add SNOMED CT persistence stage |
| MedicalReportQueryUseCase | **ADD** | Add `getSnomedCtResults()` method |
| MedicalReportQueryService | **ADD** | Implement `getSnomedCtResults()` |
| MedicalReportQueryHandler | **ADD** | Add HTTP handler for SNOMED CT endpoint |
| MedicalReportQueryRouter | **ADD** | Add route for `/results/{reportId}/snomedct` |

---

## Detailed Implementation Plan

### Step 1: Fix ComprehendMedicalAdapter

**File:** `medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/ComprehendMedicalAdapter.java`

**Location:** Lines 203-207 in `mapCodeSystemsToMedicalCodes()` method

**Before:**
```java
case "SNOMED", "SNOMED-CT" -> {
    log.warn("SNOMED-CT inference not directly supported by AWS Comprehend Medical");
    codeMonos.add(Mono.just(Map.of("SNOMED", List.of())));
}
```

**After:**
```java
case "SNOMED", "SNOMED-CT", "SNOMEDCT" -> codeMonos.add(
    comprehendMedicalService.inferSNOMEDCTCodes(text)
        .map(codes -> Map.of("SNOMEDCT", mapLibraryMedicalCodes(codes)))
        .doOnSuccess(codes -> log.info("Inferred {} SNOMED-CT codes",
            codes.get("SNOMEDCT").size()))
        .onErrorResume(error -> {
            log.warn("SNOMED-CT inference failed: {}", error.getMessage());
            return Mono.just(Map.of("SNOMEDCT", List.of()));
        })
);
```

---

### Step 2: Add SNOMED Field to ProcessingContext

**File:** `medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/domain/ProcessingContext.java`

**Add field:**
```java
private List<MedicalClassificationPort.MedicalCode> snomedctCodes;
```

---

### Step 3: Add SNOMED to MasterProcessingResponse

**File:** `medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/domain/MasterProcessingResponse.java`

**Update `MedicalCodesResult` class:**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public static class MedicalCodesResult {
    private List<MedicalCode> icd10;
    private List<MedicalCode> rxnorm;
    private List<MedicalCode> snomedct;  // ADD THIS
    private Integer totalCodes;
}
```

---

### Step 4: Update WorkflowOptions Defaults

**File:** `medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/domain/WorkflowOptions.java`

**Update default code systems:**
```java
private List<String> requestedCodeSystems = List.of("ICD10", "RXNORM", "SNOMEDCT");
```

---

### Step 5: Add SNOMED Persistence to Orchestration Service

**File:** `medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/application/service/MedicalReportOrchestrationService.java`

#### 5.1 Update Single-Image Workflow

**Location:** `inferMedicalCodesWithPersistence()` method (~line 440)

**Update the parallel code inference to include SNOMED:**
```java
private Mono<ProcessingContext> inferMedicalCodesWithPersistence(ProcessingContext context) {
    return inferMedicalCodes(context)
            .flatMap(ctx -> {
                // Save ICD-10 codes
                Mono<Void> saveIcd10 = ctx.getIcd10Codes() != null && !ctx.getIcd10Codes().isEmpty()
                        ? persistencePort.saveResult(
                                context.getReportId(),
                                "ICD10",
                                ctx.getIcd10Codes(),
                                calculateAverageCodeConfidence(ctx.getIcd10Codes()),
                                Map.of("codeCount", ctx.getIcd10Codes().size())
                        ).then()
                        : Mono.empty();

                // Save RxNorm codes
                Mono<Void> saveRxNorm = ctx.getRxnormCodes() != null && !ctx.getRxnormCodes().isEmpty()
                        ? persistencePort.saveResult(
                                context.getReportId(),
                                "RXNORM",
                                ctx.getRxnormCodes(),
                                calculateAverageCodeConfidence(ctx.getRxnormCodes()),
                                Map.of("codeCount", ctx.getRxnormCodes().size())
                        ).then()
                        : Mono.empty();

                // Save SNOMED-CT codes (NEW)
                Mono<Void> saveSnomedct = ctx.getSnomedctCodes() != null && !ctx.getSnomedctCodes().isEmpty()
                        ? persistencePort.saveResult(
                                context.getReportId(),
                                "SNOMEDCT",
                                ctx.getSnomedctCodes(),
                                calculateAverageCodeConfidence(ctx.getSnomedctCodes()),
                                Map.of("codeCount", ctx.getSnomedctCodes().size())
                        ).then()
                        : Mono.empty();

                return Mono.when(saveIcd10, saveRxNorm, saveSnomedct).thenReturn(ctx);
            });
}
```

#### 5.2 Update `inferMedicalCodes()` Method

**Location:** ~line 1055

**Update to include SNOMED CT inference:**
```java
private Mono<ProcessingContext> inferMedicalCodes(ProcessingContext context) {
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

    // ADD SNOMED CT
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
                        context.getReportId(),
                        tuple.getT1().size(),
                        tuple.getT2().size(),
                        tuple.getT3().size());

                return context;
            });
}
```

#### 5.3 Add SNOMED CT Inference Method

**Add new method after `inferRxNormCodes()`:**
```java
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
```

#### 5.4 Update Multi-Image Workflow

**Location:** `runClassificationOnMergedDataWithPersistence()` (~line 2500)

**Add SNOMED CT extraction after RxNorm:**
```java
// Extract and save SNOMEDCT codes separately
List<MedicalClassificationPort.MedicalCode> snomedctCodes =
    classificationResult.medicalCodes().getOrDefault("SNOMEDCT", List.of());

Mono<Void> saveSnomedct = !snomedctCodes.isEmpty()
    ? persistencePort.saveResult(
        context.getReportId(),
        "SNOMEDCT",
        snomedctCodes,
        calculateAverageCodeConfidence(snomedctCodes),
        Map.of("codeCount", snomedctCodes.size())
    ).then()
    : Mono.empty();

// Save all in parallel
return Mono.when(saveIcd10, saveRxNorm, saveSnomedct);
```

#### 5.5 Update Response Building

**Location:** `buildMedicalCodesResult()` (~line 1378)

```java
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
```

---

### Step 6: Add ProcessingStage Enum Value

**File:** `medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/domain/ProcessingStage.java`

**Add new stage after RXNORM_INFERENCE:**
```java
SNOMEDCT_INFERENCE(6, "SNOMED-CT Code Inference", false),
```

---

### Step 7: Add Query Use Case Method

**File:** `medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/application/port/in/MedicalReportQueryUseCase.java`

**Add after `getRxNormResults()`:**
```java
/**
 * Get SNOMED-CT code results for a report.
 *
 * @param reportId The report identifier
 * @return Mono of SNOMED-CT result
 */
Mono<ResultRecord> getSnomedCtResults(String reportId);
```

---

### Step 8: Implement Query Service Method

**File:** `medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/application/service/MedicalReportQueryService.java`

**Add implementation:**
```java
@Override
public Mono<ResultRecord> getSnomedCtResults(String reportId) {
    log.debug("Getting SNOMED-CT results for report: {}", reportId);
    return persistencePort.findResultByType(reportId, "SNOMEDCT")
            .retryWhen(RetryUtil.retryOnEmpty())
            .doOnSuccess(result -> log.info("Retrieved SNOMED-CT results for report: {}", reportId));
}
```

---

### Step 9: Add Query Handler

**File:** `medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/in/handler/MedicalReportQueryHandler.java`

**Add after `getRxNormResults()`:**
```java
/**
 * GET /results/{reportId}/snomedct
 * Get SNOMED-CT code results for a report.
 */
public Mono<ServerResponse> getSnomedCtResults(ServerRequest request) {
    String reportId = request.pathVariable("reportId");
    log.info("GET /results/{}/snomedct - Getting SNOMED-CT results", reportId);

    return queryUseCase.getSnomedCtResults(reportId)
            .flatMap(result -> ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(result))
            .switchIfEmpty(ServerResponse.notFound().build())
            .onErrorResume(this::handleError);
}
```

---

### Step 10: Add Query Route

**File:** `medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/in/router/MedicalReportQueryRouter.java`

**Add route in results section:**
```java
.GET("/results/{reportId}/snomedct", queryHandler::getSnomedCtResults)
```

---

## Implementation Order

Execute in this sequence to avoid compilation errors:

1. **ProcessingStage** - Add `SNOMEDCT_INFERENCE` enum value
2. **ProcessingContext** - Add `snomedctCodes` field
3. **MasterProcessingResponse** - Add `snomedct` to response
4. **WorkflowOptions** - Update default code systems
5. **ComprehendMedicalAdapter** - Wire SNOMED CT API call
6. **MedicalReportOrchestrationService** - Add persistence and inference logic
7. **MedicalReportQueryUseCase** - Add interface method
8. **MedicalReportQueryService** - Add implementation
9. **MedicalReportQueryHandler** - Add HTTP handler
10. **MedicalReportQueryRouter** - Add route

---

## Testing Plan

### 1. Unit Tests

Test the adapter correctly calls SNOMED CT:
```java
@Test
void testSnomedCtCodeInference() {
    // Given
    String medicalText = "Patient diagnosed with Type 2 diabetes mellitus";

    // When
    List<MedicalCode> codes = comprehendMedicalService.inferSNOMEDCTCodes(medicalText).block();

    // Then
    assertThat(codes).isNotEmpty();
    assertThat(codes.get(0).codeSystem()).isEqualTo("SNOMED-CT");
}
```

### 2. Integration Tests

```http
### Process a medical report
POST http://localhost:8086/api/v1/medical-report/process-multi-image
Content-Type: application/json

{
  "images": ["base64_encoded_image_data"],
  "patientContext": {
    "patientId": "P12345",
    "age": 45,
    "gender": "MALE"
  },
  "workflowOptions": {
    "requestedCodeSystems": ["ICD10", "RXNORM", "SNOMEDCT"]
  }
}

### Wait for processing, then query SNOMED-CT results
GET http://localhost:8086/api/v1/medical-report/query/results/{reportId}/snomedct
```

### 3. Expected Response

```json
{
  "id": "uuid-123",
  "reportId": "RPT-ABC12345",
  "resultType": "SNOMEDCT",
  "resultDataJson": "[{\"code\":\"44054006\",\"description\":\"Type 2 diabetes mellitus\",\"codeSystem\":\"SNOMED-CT\",\"score\":0.95}]",
  "confidenceScore": 0.95,
  "createdAt": "2024-01-15T10:30:00"
}
```

---

## API Endpoint

### GET /api/v1/medical-report/query/results/{reportId}/snomedct

**Description:** Retrieve SNOMED-CT codes extracted from a medical report.

**Path Parameters:**
- `reportId` (required): The unique report identifier (e.g., `RPT-ABC12345`)

**Response:**
```json
{
  "id": "result-uuid",
  "reportId": "RPT-ABC12345",
  "resultType": "SNOMEDCT",
  "resultData": [
    {
      "code": "44054006",
      "description": "Type 2 diabetes mellitus",
      "codeSystem": "SNOMED-CT",
      "score": 0.95
    },
    {
      "code": "38341003",
      "description": "Hypertensive disorder",
      "codeSystem": "SNOMED-CT",
      "score": 0.89
    }
  ],
  "confidenceScore": 0.92,
  "createdAt": "2024-01-15T10:30:00",
  "metadata": {
    "codeCount": 2
  }
}
```

**Error Responses:**
- `404 Not Found`: Report or SNOMED-CT results not found
- `500 Internal Server Error`: Processing error

---

## Summary

This implementation adds SNOMED CT support by:
1. **Wiring** the existing library implementation to the adapter
2. **Adding** a new processing stage for SNOMED CT inference
3. **Persisting** SNOMED CT codes to the database
4. **Exposing** a query API endpoint for retrieving results

The changes follow the exact patterns established for ICD-10 and RxNorm, ensuring consistency and maintainability.
