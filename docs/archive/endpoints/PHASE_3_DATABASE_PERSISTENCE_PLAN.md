# Phase 3: Database Persistence & Async Processing - Detailed Plan

**Date:** December 2, 2025
**Status:** 📋 PLANNED
**Estimated Duration:** Week 3 (40-45 hours)

---

## Executive Summary

Phase 3 transforms the master orchestration API from a stateless processing pipeline into a fully tracked, database-backed system. Every processing step, result, and error will be stored in PostgreSQL, enabling:

- **Complete audit trail** for compliance and debugging
- **Historical analytics** for performance optimization
- **Resume failed workflows** for reliability
- **Foundation for async processing** in Phase 4
- **Real-time progress streaming** for long-running operations

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                  Master Orchestration API                    │
│                 (MedicalReportOrchestrationService)          │
└───────────────────────┬─────────────────────────────────────┘
                        │
                        ▼
        ┌───────────────────────────────────┐
        │   MedicalReportPersistencePort    │  ← Outbound Port
        │         (Interface)                │
        └───────────────────────────────────┘
                        │
                        ▼
        ┌───────────────────────────────────┐
        │ MedicalReportPersistenceAdapter   │  ← Adapter Implementation
        └───────────────────────────────────┘
                        │
                        ▼
        ┌───────────────────────────────────┐
        │   R2DBC Repositories (Reactive)   │
        └───────────────────────────────────┘
                        │
                        ▼
┌───────────────────────────────────────────────────────────┐
│                  PostgreSQL Database                       │
│  ┌────────────────────────────────────────────────────┐   │
│  │  medical_report_process                            │   │
│  │  • Main process tracking (1 record per request)    │   │
│  └────────────────────────────────────────────────────┘   │
│  ┌────────────────────────────────────────────────────┐   │
│  │  medical_report_process_stage                      │   │
│  │  • Stage-level tracking (10 records per request)   │   │
│  └────────────────────────────────────────────────────┘   │
│  ┌────────────────────────────────────────────────────┐   │
│  │  medical_report_error                              │   │
│  │  • Error tracking (N records per failure)          │   │
│  └────────────────────────────────────────────────────┘   │
│  ┌────────────────────────────────────────────────────┐   │
│  │  medical_report_result                             │   │
│  │  • Result storage (5-10 records per request)       │   │
│  └────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────┘
```

---

## Database Schema

### Table 1: `medical_report_process`
**Purpose:** Track overall processing workflow

| Column | Type | Description |
|--------|------|-------------|
| `report_id` | VARCHAR(50) PK | Unique report identifier (RPT-XXXXXXXX) |
| `patient_id` | VARCHAR(50) | Patient identifier |
| `status` | VARCHAR(30) | PENDING, IN_PROGRESS, COMPLETED, FAILED, PARTIAL_SUCCESS |
| `created_at` | TIMESTAMP | When request received |
| `updated_at` | TIMESTAMP | Last update time |
| `completed_at` | TIMESTAMP | When processing finished |
| `processing_time_ms` | BIGINT | Total processing duration |
| `image_base64` | TEXT | Original image (consider S3 for large images) |
| `patient_context_json` | JSONB | Patient demographics, history, medications |
| `workflow_options_json` | JSONB | Workflow configuration |
| `completed_stages` | INTEGER | Count of successful stages |
| `failed_stages` | INTEGER | Count of failed stages |
| `total_stages` | INTEGER | Total stages (default 10) |
| `created_by` | VARCHAR(100) | User/system that initiated |
| `error_message` | TEXT | Top-level error if failed |

**Indexes:**
- `idx_patient_id` on `patient_id` - Query by patient
- `idx_status` on `status` - Filter by status
- `idx_created_at` on `created_at` - Time-based queries
- GIN index on `patient_context_json` - JSON queries

---

### Table 2: `medical_report_process_stage`
**Purpose:** Track each processing stage (10 per report)

| Column | Type | Description |
|--------|------|-------------|
| `id` | VARCHAR(50) PK | UUID for stage |
| `report_id` | VARCHAR(50) FK | Reference to process |
| `stage` | VARCHAR(50) | IMAGE_VALIDATION, OCR_PROCESSING, etc. |
| `status` | VARCHAR(30) | PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED |
| `started_at` | TIMESTAMP | When stage started |
| `completed_at` | TIMESTAMP | When stage finished |
| `duration_ms` | BIGINT | Stage processing time |
| `input_data_json` | JSONB | Input to this stage |
| `output_data_json` | JSONB | Output from this stage |
| `error_message` | TEXT | Error details if failed |
| `is_retryable` | BOOLEAN | Can this stage be retried? |
| `attempt_number` | INTEGER | Retry counter (default 1) |
| `confidence_score` | DECIMAL(5,4) | Quality confidence (0.0-1.0) |
| `quality_metrics_json` | JSONB | Stage-specific metrics |

**Indexes:**
- `idx_report_id` on `report_id` - Get all stages for report
- `idx_stage` on `stage` - Filter by stage type
- `idx_status` on `status` - Filter by status
- `idx_started_at` on `started_at` - Time-based analysis
- GIN index on `output_data_json` - JSON queries

**Example Data:**
```json
{
  "id": "stage-001",
  "report_id": "RPT-ABC123",
  "stage": "OCR_PROCESSING",
  "status": "COMPLETED",
  "duration_ms": 7250,
  "output_data_json": {
    "extractedTests": 9,
    "overallConfidence": 0.96,
    "testsData": [...]
  },
  "confidence_score": 0.96
}
```

---

### Table 3: `medical_report_error`
**Purpose:** Track all errors for debugging and analytics

| Column | Type | Description |
|--------|------|-------------|
| `id` | VARCHAR(50) PK | UUID for error |
| `report_id` | VARCHAR(50) FK | Reference to process |
| `stage_id` | VARCHAR(50) FK | Reference to stage (nullable) |
| `stage` | VARCHAR(50) | Which stage failed |
| `error_code` | VARCHAR(100) | OCR_PROCESSING_FAILED, etc. |
| `error_message` | TEXT | Human-readable error |
| `stack_trace` | TEXT | Full stack trace |
| `is_retryable` | BOOLEAN | Transient error? |
| `occurred_at` | TIMESTAMP | When error occurred |
| `severity` | VARCHAR(20) | ERROR, WARNING, INFO |

**Indexes:**
- `idx_report_id` on `report_id` - Get all errors for report
- `idx_error_code` on `error_code` - Group by error type
- `idx_occurred_at` on `occurred_at` - Time-based analysis

**Example Data:**
```json
{
  "id": "err-001",
  "report_id": "RPT-ABC123",
  "stage": "OCR_PROCESSING",
  "error_code": "OCR_PROCESSING_FAILED",
  "error_message": "AWS Textract rate limit exceeded",
  "is_retryable": true,
  "severity": "ERROR"
}
```

---

### Table 4: `medical_report_result`
**Purpose:** Store all processing results

| Column | Type | Description |
|--------|------|-------------|
| `id` | VARCHAR(50) PK | UUID for result |
| `report_id` | VARCHAR(50) FK | Reference to process |
| `result_type` | VARCHAR(50) | OCR, CLASSIFICATION, ICD10, RXNORM, INSIGHTS |
| `result_data_json` | JSONB | Complete result data |
| `confidence_score` | DECIMAL(5,4) | Overall confidence |
| `created_at` | TIMESTAMP | When result saved |
| `test_count` | INTEGER | For OCR results |
| `entity_count` | INTEGER | For classification results |
| `code_count` | INTEGER | For medical codes |
| `risk_level` | VARCHAR(20) | LOW, MODERATE, HIGH, CRITICAL |

**Indexes:**
- `idx_report_id` on `report_id` - Get all results for report
- `idx_result_type` on `result_type` - Filter by type
- `idx_created_at` on `created_at` - Time-based queries
- `idx_risk_level` on `risk_level` - Filter high-risk reports
- GIN index on `result_data_json` - JSON queries

**Example Data:**
```json
{
  "id": "res-001",
  "report_id": "RPT-ABC123",
  "result_type": "ICD10",
  "result_data_json": [
    {"code": "E11.9", "description": "Type 2 diabetes mellitus", "confidence": 0.95},
    {"code": "N17.9", "description": "Acute kidney failure", "confidence": 0.88}
  ],
  "code_count": 2,
  "confidence_score": 0.915
}
```

---

## Implementation Tasks

### Task 3.1: Database Schema (8 hours)
- ✅ Create 4 entity classes with R2DBC annotations
- ✅ Write SQL migration script (Flyway/Liquibase)
- ✅ Add JSONB support for PostgreSQL
- ✅ Create indexes for performance

### Task 3.2: Persistence Layer (10 hours)
- ✅ Create 4 R2DBC repositories
- ✅ Create persistence port interface (outbound)
- ✅ Implement persistence adapter
- ✅ Add JSON serialization/deserialization
- ✅ Handle transactions

### Task 3.3: Service Integration (8 hours)
- ✅ Inject persistence port into orchestration service
- ✅ Save process record at start
- ✅ Save each stage (start, complete, fail)
- ✅ Save all results at end
- ✅ Record all errors
- ✅ Update process status

### Task 3.4: Query APIs (6 hours)
- ✅ Create query handler for reports
- ✅ Implement GET endpoints:
  - `/api/v1/medical-report/reports/{reportId}`
  - `/api/v1/medical-report/patients/{patientId}/reports`
  - `/api/v1/medical-report/reports/failed`
  - `/api/v1/medical-report/reports/statistics`

### Task 3.5: Progress Streaming (4 hours)
- ✅ Implement SSE endpoint
- ✅ Stream stage updates from database
- ✅ `/api/v1/medical-report/process/{reportId}/progress`

### Task 3.6: Data Retention (3 hours)
- ✅ Scheduled cleanup jobs
- ✅ Archive old reports
- ✅ Remove large binary data

### Task 3.7: Testing (6 hours)
- ✅ Unit tests for repositories
- ✅ Integration tests with Testcontainers
- ✅ End-to-end tests with database
- ✅ Verify data integrity

---

## Code Examples

### Orchestration Service Integration

**Before (Phase 2):**
```java
public Mono<MasterProcessingResponse> processCompleteMedicalReport(MasterProcessingRequest request) {
    return Mono.just(context)
            .flatMap(this::validateImage)
            .flatMap(this::performOcr)
            .flatMap(this::detectEntities)
            // ... no database persistence
            .map(this::buildResponse);
}
```

**After (Phase 3):**
```java
public Mono<MasterProcessingResponse> processCompleteMedicalReport(MasterProcessingRequest request) {
    // 1️⃣ Create process record
    return persistencePort.createProcess(request)
            .flatMap(process -> {
                return Mono.just(context)
                        // 2️⃣ Each stage saves to DB
                        .flatMap(this::validateImageWithPersistence)
                        .flatMap(this::performOcrWithPersistence)
                        .flatMap(this::detectEntitiesWithPersistence)
                        // 3️⃣ Save final results
                        .flatMap(ctx -> buildAndSaveResponse(ctx));
            });
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
                            return persistencePort.failStage(stage.getId(), error.getMessage(), true)
                                    .then(persistencePort.recordError(context.getReportId(), stage, error))
                                    .then(Mono.error(error));
                        });
            });
}
```

---

## Query Examples

### Get Report Details
```bash
GET /api/v1/medical-report/reports/RPT-ABC123

Response:
{
  "reportId": "RPT-ABC123",
  "patientId": "P12345",
  "status": "COMPLETED",
  "processingTimeMs": 12450,
  "stages": [
    {
      "stage": "IMAGE_VALIDATION",
      "status": "COMPLETED",
      "durationMs": 320,
      "confidenceScore": 0.85
    },
    {
      "stage": "OCR_PROCESSING",
      "status": "COMPLETED",
      "durationMs": 7250,
      "confidenceScore": 0.96
    }
    // ... 8 more stages
  ],
  "results": {
    "ocr": { "testCount": 9 },
    "classification": { "entityCount": 42 },
    "icd10": { "codeCount": 3 },
    "rxnorm": { "codeCount": 2 }
  }
}
```

### Get Patient History
```bash
GET /api/v1/medical-report/patients/P12345/reports?limit=10

Response:
{
  "reports": [
    {
      "reportId": "RPT-ABC123",
      "status": "COMPLETED",
      "createdAt": "2025-12-02T10:30:00Z",
      "processingTimeMs": 12450,
      "testCount": 9,
      "riskLevel": "MODERATE"
    },
    {
      "reportId": "RPT-XYZ789",
      "status": "FAILED",
      "createdAt": "2025-12-01T15:20:00Z",
      "errorMessage": "OCR processing failed"
    }
  ]
}
```

### Get Statistics
```bash
GET /api/v1/medical-report/reports/statistics?since=30d

Response:
{
  "completedCount": 1247,
  "failedCount": 23,
  "avgProcessingTimeMs": 11825,
  "stageStats": [
    {
      "stage": "OCR_PROCESSING",
      "avgDurationMs": 7150,
      "failureRate": 0.018
    }
    // ... 9 more stages
  ]
}
```

---

## Database Queries for Analytics

### Find Slow Reports
```sql
SELECT report_id, processing_time_ms, created_at
FROM medical_report_process
WHERE processing_time_ms > 20000
ORDER BY processing_time_ms DESC
LIMIT 10;
```

### Find Error Patterns
```sql
SELECT error_code, COUNT(*) as count, stage
FROM medical_report_error
WHERE occurred_at > NOW() - INTERVAL '7 days'
GROUP BY error_code, stage
ORDER BY count DESC;
```

### Average Stage Durations
```sql
SELECT stage, AVG(duration_ms) as avg_duration
FROM medical_report_process_stage
WHERE status = 'COMPLETED'
  AND started_at > NOW() - INTERVAL '30 days'
GROUP BY stage
ORDER BY avg_duration DESC;
```

### High-Risk Reports
```sql
SELECT r.report_id, r.patient_id, res.risk_level
FROM medical_report_process r
JOIN medical_report_result res ON r.report_id = res.report_id
WHERE res.result_type = 'RISK_ASSESSMENT'
  AND res.risk_level IN ('HIGH', 'CRITICAL')
ORDER BY r.created_at DESC
LIMIT 20;
```

---

## Benefits Summary

| Benefit | Description | Impact |
|---------|-------------|--------|
| **Audit Trail** | Every stage tracked with timestamps | ✅ Compliance ready |
| **Debugging** | See exactly where/why failures occur | ⏱️ Faster issue resolution |
| **Analytics** | Historical data for optimization | 📊 Data-driven decisions |
| **Resume Jobs** | Retry failed stages without reprocessing | 💰 Cost savings |
| **Async Foundation** | Ready for queue-based processing | 🚀 Scalability |
| **Progress Tracking** | Real-time updates for UI | 👍 Better UX |
| **Cost Analysis** | Track AWS costs per report | 💵 Budget control |

---

## Testing Strategy

### Unit Tests
- Repository CRUD operations
- JSON serialization/deserialization
- Port interface implementations

### Integration Tests (Testcontainers)
```java
@Testcontainers
@SpringBootTest
class PersistenceIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Test
    void shouldSaveCompleteWorkflow() {
        // Test end-to-end persistence
    }
}
```

### Database Tests
- Schema migrations
- Index performance
- JSONB query performance
- Foreign key constraints
- Cascade deletes

---

## Migration Plan

1. **Create tables** - Run Flyway migration
2. **Deploy code** - Add persistence layer (non-breaking)
3. **Verify data** - Check tables populated correctly
4. **Enable queries** - Activate query APIs
5. **Monitor** - Watch database performance
6. **Optimize** - Add indexes if needed

---

## Acceptance Criteria

✅ All 4 tables created with indexes
✅ Persistence layer implemented and tested
✅ Orchestration service saves all stages
✅ Query APIs return correct data
✅ Progress streaming works via SSE
✅ Error tracking captures all failures
✅ Integration tests pass with Testcontainers
✅ No performance degradation (< 100ms overhead)

---

**Next Phase:** Phase 4 - Async Processing with Message Queue
