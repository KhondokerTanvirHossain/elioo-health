# JSONB Fix - Counter Increment Methods

## Problem

After fixing JSONB errors in all main CRUD operations, a new error occurred during workflow progression:

```
org.springframework.r2dbc.BadSqlGrammarException: execute; bad SQL grammar
UPDATE medical_report_process SET patient_id = $1, status = $2, created_at = $3, updated_at = $4,
completed_at = $5, processing_time_ms = $6, image_base64 = $7, patient_context_json = $8,
workflow_options_json = $9, completed_stages = $10, failed_stages = $11, total_stages = $12,
created_by = $13, error_message = $14 WHERE medical_report_process.report_id = $15

Caused by: column "patient_context_json" is of type jsonb but expression is of type character varying
```

**Error Location:** Counter increment operations after stage completion/failure

**Timing:** Error occurred after:
- ✅ Process created successfully (`RPT-72B67254`)
- ✅ Image validation passed (quality score: 0.85)
- ✅ Stages created successfully
- ❌ After stage completion when incrementing counters

## Root Cause

The error SQL shows the **default Spring Data UPDATE query** (including `created_at` which our custom methods don't update), indicating there were additional methods still using `save()`:

### Problem Methods Found

#### 1. `incrementCompletedStages()` - Line 498-506 ❌
```java
private Mono<Void> incrementCompletedStages(String reportId) {
    return processRepository.findById(reportId)
            .flatMap(entity -> {
                entity.setCompletedStages((entity.getCompletedStages() != null ? entity.getCompletedStages() : 0) + 1);
                entity.setUpdatedAt(LocalDateTime.now());
                return processRepository.save(entity);  // ❌ Causes JSONB error
            })
            .then();
}
```

#### 2. `incrementFailedStages()` - Line 508-516 ❌
```java
private Mono<Void> incrementFailedStages(String reportId) {
    return processRepository.findById(reportId)
            .flatMap(entity -> {
                entity.setFailedStages((entity.getFailedStages() != null ? entity.getFailedStages() : 0) + 1);
                entity.setUpdatedAt(LocalDateTime.now());
                return processRepository.save(entity);  // ❌ Causes JSONB error
            })
            .then();
}
```

#### 3. `saveResult()` - Line 386-408 ❌
```java
public Mono<ResultRecord> saveResult(String reportId, String resultType, Object resultData,
                                      Double confidenceScore, Map<String, Object> extractedFields) {
    MedicalReportResultEntity entity = MedicalReportResultEntity.builder()
            .id(UUID.randomUUID().toString())
            .reportId(reportId)
            .resultType(resultType)
            .resultDataJson(toJson(resultData))  // JSONB column
            // ...
            .build();

    return resultRepository.save(entity);  // ❌ Causes JSONB error
}
```

**Why This Wasn't Caught Earlier:**
These methods are called **indirectly** by `completeStage()` and `failStage()`, so they only execute when stages actually complete or fail during workflow execution. The previous fixes only tested up to stage creation.

## Solution Applied

### Fix 1: Update `incrementCompletedStages()` ✅

**File:** `MedicalReportPersistenceAdapter.java` (lines 498-520)

**BEFORE:**
```java
private Mono<Void> incrementCompletedStages(String reportId) {
    return processRepository.findById(reportId)
            .flatMap(entity -> {
                entity.setCompletedStages((entity.getCompletedStages() != null ? entity.getCompletedStages() : 0) + 1);
                entity.setUpdatedAt(LocalDateTime.now());
                return processRepository.save(entity);
            })
            .then();
}
```

**AFTER:**
```java
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
                        newCompletedStages,  // Incremented value
                        entity.getFailedStages(),
                        entity.getErrorMessage()
                );
            })
            .then();
}
```

### Fix 2: Update `incrementFailedStages()` ✅

**File:** `MedicalReportPersistenceAdapter.java` (lines 522-544)

**BEFORE:**
```java
private Mono<Void> incrementFailedStages(String reportId) {
    return processRepository.findById(reportId)
            .flatMap(entity -> {
                entity.setFailedStages((entity.getFailedStages() != null ? entity.getFailedStages() : 0) + 1);
                entity.setUpdatedAt(LocalDateTime.now());
                return processRepository.save(entity);
            })
            .then();
}
```

**AFTER:**
```java
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
                        newFailedStages,  // Incremented value
                        entity.getErrorMessage()
                );
            })
            .then();
}
```

### Fix 3: Add Custom JSONB Method to Result Repository ✅

**File:** `MedicalReportResultRepository.java` (lines 183-212)

**Added:**
```java
/**
 * Custom insert with explicit JSONB casting.
 * Required because Spring Data R2DBC doesn't automatically cast String to JSONB.
 */
@Query("""
        INSERT INTO medical_report_result (
            id, report_id, result_type, result_data_json, confidence_score,
            created_at, test_count, entity_count, code_count, risk_level
        ) VALUES (
            :id, :reportId, :resultType, :resultDataJson::jsonb, :confidenceScore,
            :createdAt, :testCount, :entityCount, :codeCount, :riskLevel
        )
        """)
Mono<Integer> insertWithJsonbCast(
        String id, String reportId, String resultType, String resultDataJson,
        Double confidenceScore, LocalDateTime createdAt, Integer testCount,
        Integer entityCount, Integer codeCount, String riskLevel
);
```

### Fix 4: Update `saveResult()` Method ✅

**File:** `MedicalReportPersistenceAdapter.java` (lines 386-408)

**BEFORE:**
```java
public Mono<ResultRecord> saveResult(String reportId, String resultType, Object resultData,
                                      Double confidenceScore, Map<String, Object> extractedFields) {
    log.debug("Saving result: {} - {}", reportId, resultType);

    MedicalReportResultEntity entity = MedicalReportResultEntity.builder()
            .id(UUID.randomUUID().toString())
            .reportId(reportId)
            .resultType(resultType)
            .resultDataJson(toJson(resultData))
            .confidenceScore(confidenceScore)
            .createdAt(LocalDateTime.now())
            .testCount((Integer) extractedFields.get("testCount"))
            .entityCount((Integer) extractedFields.get("entityCount"))
            .codeCount((Integer) extractedFields.get("codeCount"))
            .riskLevel((String) extractedFields.get("riskLevel"))
            .build();

    return resultRepository.save(entity)
            .doOnSuccess(saved -> log.info("Saved result: {} - {}", reportId, resultType))
            .map(this::toResultRecord);
}
```

**AFTER:**
```java
public Mono<ResultRecord> saveResult(String reportId, String resultType, Object resultData,
                                      Double confidenceScore, Map<String, Object> extractedFields) {
    log.debug("Saving result: {} - {}", reportId, resultType);

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
```

## Complete Status: All JSONB Operations Fixed

### ✅ Process Table (`medical_report_process`)
- JSONB Columns: `patient_context_json`, `workflow_options_json`
- ✅ `createProcess()` - Uses `insertWithJsonbCast()`
- ✅ `updateProcessStatus()` - Uses `updateWithJsonbCast()`
- ✅ `completeProcess()` - Uses `updateWithJsonbCast()`
- ✅ `failProcess()` - Uses `updateWithJsonbCast()`
- ✅ `incrementCompletedStages()` - Uses `updateWithJsonbCast()`
- ✅ `incrementFailedStages()` - Uses `updateWithJsonbCast()`

### ✅ Stage Table (`medical_report_process_stage`)
- JSONB Columns: `input_data_json`, `output_data_json`, `quality_metrics_json`
- ✅ `createStage()` - Uses `insertWithJsonbCast()`
- ✅ `startStage()` - Uses `updateWithJsonbCast()`
- ✅ `completeStage()` - Uses `updateWithJsonbCast()`
- ✅ `failStage()` - Uses `updateWithJsonbCast()`

### ✅ Result Table (`medical_report_result`)
- JSONB Columns: `result_data_json`
- ✅ `saveResult()` - Uses `insertWithJsonbCast()`

### ✅ Error Table (`medical_report_error`)
- JSONB Columns: **None** (uses TEXT columns)
- ✅ `recordError()` - Can safely use `save()` (no JSONB columns)

## Verification Strategy

### Code Verification ✅
```bash
# Verified no more save() calls on JSONB tables
grep -n "processRepository\.save" MedicalReportPersistenceAdapter.java
# No matches ✅

grep -n "stageRepository\.save" MedicalReportPersistenceAdapter.java
# No matches ✅

grep -n "resultRepository\.save" MedicalReportPersistenceAdapter.java
# No matches ✅

# Only errorRepository.save remains (safe - no JSONB columns)
grep -n "errorRepository\.save" MedicalReportPersistenceAdapter.java
# Line 359 - SAFE (error table has no JSONB columns) ✅
```

### Compilation Verification ✅
```bash
./gradlew compileJava

BUILD SUCCESSFUL in 1s
5 actionable tasks: 1 executed, 4 up-to-date
```

### Expected Workflow Test
```bash
# Start application
./gradlew bootRun

# Submit processing request (from rest.http)
POST http://localhost:8086/api/v1/medical-report/process
Content-Type: application/json

{
  "imageBase64": "...",
  "patientContext": {
    "patientId": "P001",
    "age": 45,
    "gender": "MALE",
    "medicalHistory": ["Hypertension"]
  },
  "workflowOptions": {
    "language": "en",
    "includeInsights": true
  }
}

# Expected Success Path:
# 1. ✅ Create process record (INSERT with JSONB cast)
# 2. ✅ Update process status to IN_PROGRESS (UPDATE with JSONB cast)
# 3. ✅ Create 10 stage records (INSERT with JSONB cast, started_at set)
# 4. ✅ Validate image quality
# 5. ✅ Complete IMAGE_VALIDATION stage (UPDATE with JSONB cast)
# 6. ✅ Increment completed_stages counter (UPDATE with JSONB cast) - FIXED!
# 7. ✅ Process remaining stages (all using JSONB-safe methods)
# 8. ✅ Save results (INSERT with JSONB cast) - FIXED!
# 9. ✅ Complete process (UPDATE with JSONB cast)
# 10. ✅ Return final response with all stage details
```

## Files Modified

### Repositories (Added Custom JSONB Methods)

1. **[MedicalReportResultRepository.java](../medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/persistence/repository/MedicalReportResultRepository.java:183-212)**
   - Added `insertWithJsonbCast()` method for result table

### Persistence Adapter (Updated to Use Custom Methods)

2. **[MedicalReportPersistenceAdapter.java](../medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/persistence/MedicalReportPersistenceAdapter.java)**
   - Updated `incrementCompletedStages()` (lines 498-520) - Uses `updateWithJsonbCast()`
   - Updated `incrementFailedStages()` (lines 522-544) - Uses `updateWithJsonbCast()`
   - Updated `saveResult()` (lines 386-408) - Uses `insertWithJsonbCast()`

## Summary

**Fixed the final JSONB errors by:**
1. ✅ Identified counter increment methods still using `save()`
2. ✅ Updated `incrementCompletedStages()` to use custom JSONB-safe UPDATE
3. ✅ Updated `incrementFailedStages()` to use custom JSONB-safe UPDATE
4. ✅ Added custom INSERT method to result repository with JSONB casting
5. ✅ Updated `saveResult()` to use custom JSONB-safe INSERT
6. ✅ Verified no remaining `save()` calls on JSONB tables
7. ✅ Code compiles successfully

**Result:** ALL database operations now use JSONB-safe methods! The complete workflow should now execute without any type mismatch errors. 🎉

## Related Documentation

- [JSONB_FIXES_COMPLETE.md](JSONB_FIXES_COMPLETE.md) - Complete summary of all JSONB fixes
- [JSONB_TYPE_CASTING_FIX.md](JSONB_TYPE_CASTING_FIX.md) - Original JSONB fix documentation
- [JSONB_STAGE_TABLE_FIX.md](JSONB_STAGE_TABLE_FIX.md) - Stage table fixes
- [STAGE_STARTED_AT_NOT_NULL_FIX.md](STAGE_STARTED_AT_NOT_NULL_FIX.md) - NOT NULL constraint fix
