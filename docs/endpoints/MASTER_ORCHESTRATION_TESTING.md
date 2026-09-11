# Master Orchestration API - Testing Strategy

**Version:** 1.0
**Status:** 🚧 Planning Phase
**Coverage Goal:** 85%+ overall

---

## Table of Contents

1. [Overview](#overview)
2. [Test Pyramid](#test-pyramid)
3. [Unit Testing](#unit-testing)
4. [Integration Testing](#integration-testing)
5. [End-to-End Testing](#end-to-end-testing)
6. [Performance Testing](#performance-testing)
7. [Security Testing](#security-testing)
8. [Test Data Management](#test-data-management)
9. [CI/CD Integration](#cicd-integration)
10. [Test Execution](#test-execution)

---

## Overview

This document outlines the comprehensive testing strategy for the Master Orchestration API, covering unit tests, integration tests, end-to-end tests, performance tests, and security tests.

### Testing Goals

✅ **Reliability** - Ensure 95%+ success rate in production
✅ **Performance** - Validate < 15s average processing time
✅ **Resilience** - Test partial failure scenarios
✅ **Security** - Validate PHI protection and authentication
✅ **Cost** - Ensure cost per report stays < $0.10

---

## Test Pyramid

```
           /\
          /E2E\          10 tests (5% of tests, 10% of execution time)
         /------\        - Full workflow with real images
        /  INT   \       30 tests (20% of tests, 30% of execution time)
       /----------\      - AWS service integration
      /    UNIT    \     100 tests (75% of tests, 60% of execution time)
     /--------------\    - Business logic and transformations
```

### Test Distribution

| Test Type | Count | Coverage | Execution Time | Cost |
|-----------|-------|----------|----------------|------|
| Unit | 100 | 85%+ | 2 minutes | $0 |
| Integration | 30 | 70%+ | 5 minutes | ~$0.20 |
| E2E | 10 | Critical paths | 10 minutes | ~$0.50 |
| Performance | 5 | Load scenarios | 30 minutes | ~$2.00 |
| **Total** | **145** | **80%+** | **47 minutes** | **~$2.70** |

---

## Unit Testing

### Scope

Test business logic in isolation with mocked dependencies.

### Test Classes

#### 1. `MedicalReportOrchestrationServiceTest.java`

**Location:** `test/.../application/service/`

**Coverage Goal:** 90%+

**Test Cases:**

```java
package com.elioo.healthcare.medicalreport.application.service;

import com.elioo.healthcare.medicalreport.application.port.out.*;
import com.elioo.healthcare.medicalreport.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    // ========== Success Scenarios ==========

    @Test
    void shouldProcessCompleteWorkflowSuccessfully() {
        // Given: All stages return success
        MasterProcessingRequest request = createValidRequest();

        when(ocrPort.validateImageQuality(anyString()))
                .thenReturn(Mono.just(createValidImageQuality()));

        when(ocrPort.extractMedicalData(anyString(), anyString(), any()))
                .thenReturn(Flux.fromIterable(createMockTestResults()));

        when(ocrPort.extractRawText(anyString(), anyString()))
                .thenReturn(Mono.just("Mock raw text"));

        when(classificationPort.classifyMedicalEntities(any()))
                .thenReturn(Mono.just(createMockClassificationResult()));

        when(classificationPort.mapToMedicalCodes(anyString(), anyList()))
                .thenReturn(Mono.just(createMockMedicalCodes()));

        when(clinicalInsightPort.generateClinicalInsights(any()))
                .thenReturn(Mono.just(createMockClinicalInsights()));

        // When: processCompleteMedicalReport called
        Mono<MasterProcessingResponse> result = service.processCompleteMedicalReport(request);

        // Then: Response should have COMPLETED status
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(ProcessingStatus.COMPLETED, response.getProcessingStatus());
                    assertEquals(10, response.getWorkflow().getCompletedStages().size());
                    assertTrue(response.getWorkflow().getFailedStages().isEmpty());
                    assertNotNull(response.getOcrResults());
                    assertNotNull(response.getEntityDetection());
                    assertNotNull(response.getMedicalCodes());
                    assertNotNull(response.getClinicalInsights());
                })
                .verifyComplete();

        // Verify all services were called
        verify(ocrPort).validateImageQuality(anyString());
        verify(ocrPort).extractMedicalData(anyString(), anyString(), any());
        verify(classificationPort).classifyMedicalEntities(any());
        verify(clinicalInsightPort).generateClinicalInsights(any());
    }

    @Test
    void shouldSkipValidationWhenRequested() {
        // Given: Request with skipValidation = true
        MasterProcessingRequest request = createRequestWithOptions(
                WorkflowOptions.builder().skipValidation(true).build()
        );

        // Setup other mocks...

        // When: processCompleteMedicalReport called
        Mono<MasterProcessingResponse> result = service.processCompleteMedicalReport(request);

        // Then: Image validation should not be called
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertTrue(response.getWorkflow().getCompletedStages()
                            .contains(ProcessingStage.IMAGE_VALIDATION));
                })
                .verifyComplete();

        verify(ocrPort, never()).validateImageQuality(anyString());
    }

    // ========== Partial Success Scenarios ==========

    @Test
    void shouldHandlePartialSuccessWhenIcd10InferenceFails() {
        // Given: ICD-10 inference fails but other stages succeed
        MasterProcessingRequest request = createValidRequest();

        setupSuccessfulOcr();
        setupSuccessfulEntityDetection();

        when(classificationPort.mapToMedicalCodes(anyString(), eq(List.of("ICD10"))))
                .thenReturn(Mono.error(new RuntimeException("ICD-10 service unavailable")));

        when(classificationPort.mapToMedicalCodes(anyString(), eq(List.of("RXNORM"))))
                .thenReturn(Mono.just(createMockMedicalCodes()));

        setupSuccessfulClinicalInsights();

        // When: processCompleteMedicalReport called
        Mono<MasterProcessingResponse> result = service.processCompleteMedicalReport(request);

        // Then: Response should have PARTIAL_SUCCESS status
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(ProcessingStatus.PARTIAL_SUCCESS, response.getProcessingStatus());
                    assertTrue(response.getWorkflow().getFailedStages()
                            .contains(ProcessingStage.ICD10_INFERENCE));
                    assertTrue(response.getMedicalCodes().getIcd10().isEmpty());
                    assertFalse(response.getMedicalCodes().getRxnorm().isEmpty());
                    assertEquals(1, response.getErrors().size());
                })
                .verifyComplete();
    }

    @Test
    void shouldHandlePartialSuccessWhenMultipleNonCriticalStagesFail() {
        // Given: ICD-10 and Educational Content both fail
        // When: processCompleteMedicalReport called
        // Then: Response should still have clinical insights and recommendations
    }

    // ========== Failure Scenarios ==========

    @Test
    void shouldFailWhenImageValidationFails() {
        // Given: Image validation returns invalid
        MasterProcessingRequest request = createValidRequest();

        when(ocrPort.validateImageQuality(anyString()))
                .thenReturn(Mono.just(OcrPort.ImageQualityResult.builder()
                        .isValid(false)
                        .qualityScore(0.3)
                        .reason("Image too blurry")
                        .metrics(Map.of())
                        .build()));

        // When: processCompleteMedicalReport called
        Mono<MasterProcessingResponse> result = service.processCompleteMedicalReport(request);

        // Then: Should fail with OrchestrationException
        StepVerifier.create(result)
                .expectError(OrchestrationException.class)
                .verify();
    }

    @Test
    void shouldFailWhenOcrProcessingFails() {
        // Given: OCR extraction returns error
        MasterProcessingRequest request = createValidRequest();

        setupSuccessfulImageValidation();

        when(ocrPort.extractMedicalData(anyString(), anyString(), any()))
                .thenReturn(Flux.error(new RuntimeException("Textract service unavailable")));

        // When: processCompleteMedicalReport called
        Mono<MasterProcessingResponse> result = service.processCompleteMedicalReport(request);

        // Then: Should return error response
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(ProcessingStatus.FAILED, response.getProcessingStatus());
                    assertTrue(response.getWorkflow().getFailedStages()
                            .contains(ProcessingStage.OCR_PROCESSING));
                })
                .verifyComplete();
    }

    @Test
    void shouldFailWhenOcrReturnsNoData() {
        // Given: OCR returns empty list
        when(ocrPort.extractMedicalData(anyString(), anyString(), any()))
                .thenReturn(Flux.empty());

        // When: processCompleteMedicalReport called
        // Then: Should fail with "No medical data could be extracted"
    }

    // ========== Edge Cases ==========

    @Test
    void shouldHandleLowConfidenceOcrResults() {
        // Given: OCR returns results with low confidence (<0.70)
        // When: processCompleteMedicalReport called
        // Then: Should add warning but continue processing
    }

    @Test
    void shouldHandleTimeout() {
        // Given: OCR takes > 2 minutes
        when(ocrPort.extractMedicalData(anyString(), anyString(), any()))
                .thenReturn(Flux.fromIterable(createMockTestResults())
                        .delayElements(Duration.ofMinutes(3)));

        // When: processCompleteMedicalReport called
        // Then: Should timeout and return TIMEOUT status
    }

    @Test
    void shouldHandleRetryableErrors() {
        // Given: AWS throttling error
        when(classificationPort.mapToMedicalCodes(anyString(), anyList()))
                .thenReturn(Mono.error(new RuntimeException("Rate limit exceeded")));

        // When: processCompleteMedicalReport called
        // Then: Error should be marked as retryable
    }

    // ========== Data Transformation Tests ==========

    @Test
    void shouldCorrectlyConvertPatientContextToInsightRequest() {
        // Test DTO mapping logic
    }

    @Test
    void shouldCorrectlyBuildClinicalInsightsResult() {
        // Test response building logic
    }

    @Test
    void shouldCorrectlyCalculateOverallConfidence() {
        // Test confidence calculation
    }

    // ========== Helper Methods ==========

    private MasterProcessingRequest createValidRequest() {
        return MasterProcessingRequest.builder()
                .imageBase64("base64_encoded_image")
                .patientContext(MasterProcessingRequest.PatientContext.builder()
                        .patientId("P12345")
                        .age(59)
                        .gender("MALE")
                        .medicalHistory(List.of("Diabetes Type 2"))
                        .currentMedications(List.of("Metformin 1000mg"))
                        .build())
                .workflowOptions(WorkflowOptions.builder().build())
                .build();
    }

    private void setupSuccessfulOcr() {
        when(ocrPort.validateImageQuality(anyString()))
                .thenReturn(Mono.just(createValidImageQuality()));
        when(ocrPort.extractMedicalData(anyString(), anyString(), any()))
                .thenReturn(Flux.fromIterable(createMockTestResults()));
        when(ocrPort.extractRawText(anyString(), anyString()))
                .thenReturn(Mono.just("Mock raw text"));
    }

    // Additional helper methods...
}
```

**Test Coverage:**
- ✅ Complete workflow success
- ✅ Partial success scenarios (non-critical failures)
- ✅ Critical failure scenarios
- ✅ Edge cases (timeout, low confidence, retries)
- ✅ Data transformation logic
- ✅ Error handling

---

#### 2. `ProcessingContextTest.java`

**Test Cases:**
- ✅ Stage completion tracking
- ✅ Stage failure tracking
- ✅ Warning accumulation
- ✅ Critical failure detection

---

#### 3. `MasterProcessingRequestTest.java`

**Test Cases:**
- ✅ Validation annotations
- ✅ Builder pattern
- ✅ Default values

---

### Unit Test Execution

```bash
# Run all unit tests
./gradlew test

# Run specific test class
./gradlew test --tests MedicalReportOrchestrationServiceTest

# Run with coverage report
./gradlew test jacocoTestReport
```

---

## Integration Testing

### Scope

Test integration with real AWS services using LocalStack or AWS SDK mocks.

### Test Classes

#### 1. `MedicalReportOrchestrationIntegrationTest.java`

**Location:** `test/.../medicalreport/integration/`

**Coverage Goal:** 70%+

```java
@SpringBootTest
@Testcontainers
class MedicalReportOrchestrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest")
    ).withServices(
            LocalStackContainer.Service.TEXTRACT,
            LocalStackContainer.Service.COMPREHENDMEDICAL
    );

    @Autowired
    private MedicalReportOrchestrationUseCase orchestrationUseCase;

    @Test
    void shouldProcessRealMedicalReportWithAwsServices() {
        // Given: Real medical report image
        String imageBase64 = loadTestImage("blood_test_report_1.png");

        MasterProcessingRequest request = MasterProcessingRequest.builder()
                .imageBase64(imageBase64)
                .patientContext(createRealPatientContext())
                .build();

        // When: Process complete workflow
        Mono<MasterProcessingResponse> result = orchestrationUseCase.processCompleteMedicalReport(request);

        // Then: Verify all stages completed
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(ProcessingStatus.COMPLETED, response.getProcessingStatus());
                    assertNotNull(response.getOcrResults());
                    assertTrue(response.getOcrResults().getTestCount() > 0);
                    assertNotNull(response.getClinicalInsights());
                    assertTrue(response.getProcessingTimeMs() < 30000); // < 30s
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleAwsServiceThrottling() {
        // Test rate limiting behavior
    }

    @Test
    void shouldRetryOnTransientAwsErrors() {
        // Test retry logic
    }

    @Test
    void shouldCacheDuplicateRequests() {
        // Test caching behavior
    }
}
```

**Test Coverage:**
- ✅ Real AWS service integration
- ✅ Throttling and rate limiting
- ✅ Retry logic
- ✅ Caching behavior
- ✅ Error scenarios with real AWS errors

---

### Integration Test Execution

```bash
# Run integration tests
./gradlew integrationTest

# Run with LocalStack
docker-compose up -d localstack
./gradlew integrationTest
docker-compose down
```

---

## End-to-End Testing

### Scope

Test complete user workflows with real images and production-like environment.

### Test Cases

#### 1. Normal Blood Test Report

**Input:** Blood test report image (2480x3508px, 300 DPI)

**Expected Output:**
- ✅ OCR extracts 9 test results
- ✅ All entities detected correctly
- ✅ ICD-10 and RxNorm codes inferred
- ✅ Clinical insights generated
- ✅ Risk assessment shows HIGH risk (hepatic)
- ✅ Recommendations include URGENT actions
- ✅ Processing time < 15 seconds

---

#### 2. Low Quality Image

**Input:** Blurry, low-resolution image (800x600px, 72 DPI)

**Expected Output:**
- ✅ Image validation fails with quality score < 0.70
- ✅ Warning added about low quality
- ✅ OCR confidence < 0.80
- ✅ Warning about manual review recommended

---

#### 3. Handwritten Report

**Input:** Handwritten medical report

**Expected Output:**
- ✅ OCR extracts some data with low confidence
- ✅ Warning about handwritten text
- ✅ Clinical insights still generated with caveats

---

#### 4. Multi-page PDF

**Input:** Multi-page PDF report

**Expected Output:**
- ✅ Only first page processed
- ✅ Warning about multi-page document

---

#### 5. Non-Medical Document

**Input:** Regular text document (not a medical report)

**Expected Output:**
- ✅ OCR extracts text
- ✅ No medical entities detected
- ✅ No medical codes inferred
- ✅ Error or warning about no medical data found

---

### E2E Test Execution

```bash
# Run E2E tests
./gradlew e2eTest

# Run specific E2E test
./gradlew e2eTest --tests NormalBloodTestReportE2ETest
```

---

## Performance Testing

### Scope

Validate system performance under load.

### Test Scenarios

#### 1. Sustained Load Test

**Configuration:**
- 100 requests/minute
- Duration: 10 minutes
- Total: 1000 requests

**Success Criteria:**
- ✅ Average response time < 15 seconds
- ✅ 95th percentile < 25 seconds
- ✅ 99th percentile < 40 seconds
- ✅ Success rate > 95%
- ✅ No memory leaks

---

#### 2. Spike Test

**Configuration:**
- Baseline: 10 req/min
- Spike: 200 req/min for 2 minutes
- Return to baseline

**Success Criteria:**
- ✅ System handles spike without crashing
- ✅ Response times recover after spike
- ✅ Error rate during spike < 10%

---

#### 3. Soak Test

**Configuration:**
- 50 requests/minute
- Duration: 2 hours
- Total: 6000 requests

**Success Criteria:**
- ✅ No memory leaks
- ✅ No performance degradation over time
- ✅ Consistent response times

---

### Performance Test Tools

```bash
# Use Gatling for load testing
./gradlew gatlingRun

# Use JMeter
jmeter -n -t load_test.jmx -l results.jtl

# Use Apache Bench for quick tests
ab -n 100 -c 10 http://localhost:8086/api/v1/medical-report/process
```

---

## Security Testing

### Scope

Validate security controls and PHI protection.

### Test Cases

#### 1. Authentication

- ✅ Request without API key returns 401
- ✅ Request with invalid API key returns 401
- ✅ Request with valid API key succeeds

#### 2. Authorization

- ✅ User can only access their own reports
- ✅ Admin can access all reports

#### 3. Input Validation

- ✅ SQL injection attempts blocked
- ✅ XSS attempts sanitized
- ✅ Oversized images rejected

#### 4. Data Protection

- ✅ PHI encrypted in transit (HTTPS)
- ✅ PHI encrypted at rest
- ✅ Sensitive data masked in logs

#### 5. Rate Limiting

- ✅ Excessive requests throttled
- ✅ Rate limit headers returned

---

## Test Data Management

### Test Images

**Location:** `src/test/resources/test-images/`

**Categories:**
1. **Normal Reports** (10 images)
   - High quality blood test reports
   - Various hospital formats

2. **Edge Cases** (10 images)
   - Low quality/blurry images
   - Handwritten reports
   - Non-English text

3. **Negative Cases** (5 images)
   - Non-medical documents
   - Corrupted files
   - Empty pages

### Test Patient Data

**Location:** `src/test/resources/test-data/`

**Files:**
- `patients.json` - Sample patient contexts
- `expected_results.json` - Expected OCR outputs
- `expected_insights.json` - Expected clinical insights

---

## CI/CD Integration

### GitHub Actions Workflow

```yaml
name: Master Orchestration API Tests

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK 21
        uses: actions/setup-java@v2
        with:
          java-version: '21'
      - name: Run unit tests
        run: ./gradlew test
      - name: Upload coverage
        uses: codecov/codecov-action@v2

  integration-tests:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15-alpine
      localstack:
        image: localstack/localstack:latest
    steps:
      - uses: actions/checkout@v2
      - name: Run integration tests
        run: ./gradlew integrationTest

  e2e-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Deploy to test environment
        run: ./deploy-test.sh
      - name: Run E2E tests
        run: ./gradlew e2eTest

  performance-tests:
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    steps:
      - uses: actions/checkout@v2
      - name: Run performance tests
        run: ./gradlew gatlingRun
```

---

## Test Execution

### Local Development

```bash
# Run all tests
./gradlew test integrationTest e2eTest

# Run with coverage
./gradlew test jacocoTestReport
open build/reports/jacoco/test/html/index.html

# Run specific test suite
./gradlew test --tests "*OrchestrationServiceTest"
```

### CI/CD Pipeline

```bash
# Stage 1: Quick feedback (unit tests)
./gradlew test --parallel

# Stage 2: Integration tests
./gradlew integrationTest

# Stage 3: E2E tests (staging environment)
./gradlew e2eTest -Denv=staging

# Stage 4: Performance tests (production-like)
./gradlew gatlingRun -Denv=perf
```

---

## Test Metrics & Reporting

### Coverage Goals

| Component | Target Coverage |
|-----------|----------------|
| Service Layer | 90%+ |
| Handler Layer | 85%+ |
| Domain Objects | 80%+ |
| Overall | 85%+ |

### Quality Gates

✅ All unit tests must pass
✅ Code coverage > 85%
✅ No critical security vulnerabilities
✅ Integration tests pass on AWS
✅ E2E tests pass on staging
✅ Performance tests meet SLA targets

---

## Troubleshooting Test Failures

### Common Issues

#### 1. AWS Service Mocks Failing

**Problem:** LocalStack not starting properly

**Solution:**
```bash
docker-compose down
docker-compose up -d localstack
sleep 10  # Wait for services to start
./gradlew integrationTest
```

#### 2. Flaky Tests

**Problem:** Tests pass locally but fail in CI

**Solution:**
- Use `Awaitility` for async operations
- Increase timeouts for CI environment
- Use `@RepeatedTest` to catch intermittent failures

#### 3. Out of Memory Errors

**Problem:** Large image processing causes OOM

**Solution:**
```bash
# Increase heap size for tests
./gradlew test -Dorg.gradle.jvmargs=-Xmx4g
```

---

## Continuous Improvement

### Test Maintenance

1. **Weekly:** Review and update test data
2. **Monthly:** Analyze flaky tests and fix root causes
3. **Quarterly:** Review coverage gaps and add missing tests
4. **Annually:** Refactor test suite for maintainability

### Metrics to Track

- Test execution time trends
- Flaky test rate
- Coverage trends
- Test maintenance effort

---

**Document Version:** 1.0
**Last Updated:** 2024-01-15
**Author:** QA Team
**Status:** Ready for Implementation
