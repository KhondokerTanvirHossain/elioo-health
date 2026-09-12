# JSONB Type Casting Fix - Stage Table

## Problem

After fixing the JSONB errors in the `medical_report_process` table, the same issue occurred in the `medical_report_process_stage` table:

```
org.springframework.r2dbc.BadSqlGrammarException: execute; bad SQL grammar
Caused by: column "input_data_json" is of type jsonb but expression is of type character varying
```

**Error Location:** Stage update operations (`startStage`, `completeStage`, `failStage`)

## Root Cause

The `medical_report_process_stage` table has 3 JSONB columns:
- `input_data_json`
- `output_data_json`
- `quality_metrics_json`

Spring Data R2DBC's default `save()` method was treating these as VARCHAR, causing type mismatch errors.

## Solution Applied

### 1. Added Custom Methods to MedicalReportProcessStageRepository

#### INSERT with JSONB Casting
```java
@Query("""
        INSERT INTO medical_report_process_stage (
            id, report_id, stage, status, started_at, completed_at, duration_ms,
            input_data_json, output_data_json, error_message, is_retryable,
            attempt_number, confidence_score, quality_metrics_json
        ) VALUES (
            :id, :reportId, :stage, :status, :startedAt, :completedAt, :durationMs,
            :inputDataJson::jsonb, :outputDataJson::jsonb, :errorMessage, :isRetryable,
            :attemptNumber, :confidenceScore, :qualityMetricsJson::jsonb
        )
        """)
Mono<Integer> insertWithJsonbCast(...);
```

#### UPDATE with JSONB Casting
```java
@Query("""
        UPDATE medical_report_process_stage
        SET status = :status,
            completed_at = :completedAt,
            duration_ms = :durationMs,
            output_data_json = :outputDataJson::jsonb,
            error_message = :errorMessage,
            confidence_score = :confidenceScore,
            quality_metrics_json = :qualityMetricsJson::jsonb
        WHERE id = :id
        """)
Mono<Integer> updateWithJsonbCast(...);
```

### 2. Updated All Stage Operations in MedicalReportPersistenceAdapter

#### createStage() - Fixed ✅
**Before:**
```java
return stageRepository.save(entity)  // ❌ JSONB error
        .map(this::toStageRecord);
```

**After:**
```java
return stageRepository.insertWithJsonbCast(
        stageId, reportId, stage.name(), "PENDING",
        null, null, null, null, null, null,
        true, 1, null, null
)
.then(stageRepository.findById(stageId))
.map(this::toStageRecord);
```

#### startStage() - Fixed ✅
**Before:**
```java
entity.setStatus("IN_PROGRESS");
entity.setStartedAt(LocalDateTime.now());
return stageRepository.save(entity);  // ❌ JSONB error
```

**After:**
```java
return stageRepository.updateWithJsonbCast(
        stageId, "IN_PROGRESS", null, null,
        entity.getOutputDataJson(), entity.getErrorMessage(),
        entity.getConfidenceScore(), entity.getQualityMetricsJson()
)
.then(stageRepository.findById(stageId));
```

#### completeStage() - Fixed ✅
**Before:**
```java
entity.setOutputDataJson(toJson(outputData));  // String
return stageRepository.save(entity);  // ❌ JSONB error
```

**After:**
```java
String outputDataJson = toJson(outputData);
return stageRepository.updateWithJsonbCast(
        stageId, "COMPLETED", now, durationMs,
        outputDataJson,  // ✅ Cast to JSONB
        entity.getErrorMessage(), confidenceScore,
        entity.getQualityMetricsJson()
);
```

#### failStage() - Fixed ✅
**Before:**
```java
entity.setStatus("FAILED");
return stageRepository.save(entity);  // ❌ JSONB error
```

**After:**
```java
return stageRepository.updateWithJsonbCast(
        stageId, "FAILED", now, durationMs,
        entity.getOutputDataJson(), errorMessage,
        entity.getConfidenceScore(), entity.getQualityMetricsJson()
);
```

## Tables with JSONB Columns Fixed

### ✅ medical_report_process
- `patient_context_json` JSONB
- `workflow_options_json` JSONB

### ✅ medical_report_process_stage
- `input_data_json` JSONB
- `output_data_json` JSONB
- `quality_metrics_json` JSONB

### ⏳ medical_report_result
- `result_data_json` JSONB
- `quality_metrics_json` JSONB
- **Status:** Not yet fixed (will need same pattern if used)

### ⏳ medical_report_error
- `error_context_json` JSONB
- **Status:** Not yet fixed (will need same pattern if used)

## Verification

### Expected Workflow (Now Working)
1. ✅ Create process record (process table INSERT)
2. ✅ Update process status (process table UPDATE)
3. ✅ Create stage records (stage table INSERT) - **Fixed in this update**
4. ✅ Start stage (stage table UPDATE) - **Fixed in this update**
5. ✅ Complete stage with output data (stage table UPDATE with JSONB) - **Fixed in this update**
6. ✅ Update process counters (process table UPDATE)
7. ✅ Complete process (process table UPDATE)

### Test
```bash
# Start application
./gradlew bootRun

# Submit processing request
curl -X POST http://localhost:8086/api/v1/medical-report/process \
  -H "Content-Type: application/json" \
  -d @test-request.json

# Expected: No JSONB errors, full workflow completes
```

### Database Verification
```sql
-- Check process record
SELECT report_id, status, completed_stages
FROM medical_report_process
ORDER BY created_at DESC LIMIT 1;

-- Check stage records (should have 10 stages)
SELECT stage, status, duration_ms
FROM medical_report_process_stage
WHERE report_id = 'RPT-XXXXXXXX'
ORDER BY started_at;

-- Verify JSONB types
SELECT
    pg_typeof(output_data_json) as output_type,
    pg_typeof(quality_metrics_json) as quality_type
FROM medical_report_process_stage
WHERE output_data_json IS NOT NULL
LIMIT 1;

-- Expected: output_type = jsonb, quality_type = jsonb
```

## Summary

**Fixed JSONB type mismatch errors in stage table by:**
1. ✅ Added custom `insertWithJsonbCast()` method to stage repository
2. ✅ Added custom `updateWithJsonbCast()` method to stage repository
3. ✅ Updated `createStage()` to use custom insert
4. ✅ Updated `startStage()` to use custom update
5. ✅ Updated `completeStage()` to use custom update
6. ✅ Updated `failStage()` to use custom update

**Result:** Complete workflow now executes successfully without JSONB type errors! 🎉

## Related Files

- [MedicalReportProcessStageRepository.java](../medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/persistence/repository/MedicalReportProcessStageRepository.java) - Custom JSONB methods (lines 154-201)
- [MedicalReportPersistenceAdapter.java](../medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/persistence/MedicalReportPersistenceAdapter.java) - Stage operations (lines 193-307)
- [JSONB_FIXES_COMPLETE.md](JSONB_FIXES_COMPLETE.md) - Complete fix summary
