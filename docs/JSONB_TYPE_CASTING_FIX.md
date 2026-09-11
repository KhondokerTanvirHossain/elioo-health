# JSONB Type Casting Fix for Spring Data R2DBC

## Problem

When trying to persist data to PostgreSQL using Spring Data R2DBC, you encountered this error:

```
org.springframework.r2dbc.BadSqlGrammarException: execute; bad SQL grammar
column "patient_context_json" is of type jsonb but expression is of type character varying
```

**Root Cause:** Spring Data R2DBC's default `save()` method doesn't automatically cast Java `String` to PostgreSQL `JSONB` type when inserting data.

## Solution

Added custom insert/update methods in the repository with explicit `::jsonb` casting.

### Step 1: Add Custom Repository Methods

**File:** `MedicalReportProcessRepository.java`

```java
@Query("""
        INSERT INTO medical_report_process (
            report_id, patient_id, status, created_at, updated_at, completed_at, processing_time_ms,
            image_base64, patient_context_json, workflow_options_json,
            completed_stages, failed_stages, total_stages, created_by, error_message
        ) VALUES (
            :reportId, :patientId, :status, :createdAt, :updatedAt, :completedAt, :processingTimeMs,
            :imageBase64, :patientContextJson::jsonb, :workflowOptionsJson::jsonb,
            :completedStages, :failedStages, :totalStages, :createdBy, :errorMessage
        )
        """)
Mono<Integer> insertWithJsonbCast(
        String reportId, String patientId, String status,
        LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime completedAt,
        Long processingTimeMs, String imageBase64, String patientContextJson,
        String workflowOptionsJson, Integer completedStages, Integer failedStages,
        Integer totalStages, String createdBy, String errorMessage
);
```

**Key Point:** The `::jsonb` cast tells PostgreSQL to treat the String parameter as JSONB:
```sql
:patientContextJson::jsonb  -- Cast String to JSONB
```

### Step 2: Update Persistence Adapter

**File:** `MedicalReportPersistenceAdapter.java`

**Before (using default save - causes error):**
```java
MedicalReportProcessEntity entity = MedicalReportProcessEntity.builder()
        .reportId(generateReportId())
        .patientContextJson(toJson(request.getPatientContext()))
        // ...
        .build();

return processRepository.save(entity)  // ❌ Fails with type mismatch
        .map(this::toProcessRecord);
```

**After (using custom insert with JSONB cast):**
```java
String reportId = generateReportId();
String patientContextJson = toJson(request.getPatientContext());
String workflowOptionsJson = toJson(request.getWorkflowOptions());

return processRepository.insertWithJsonbCast(
        reportId, patientId, status,
        now, now, null, null,
        imageBase64, patientContextJson, workflowOptionsJson,  // ✅ Explicit JSONB cast
        0, 0, 10, null, null
)
.then(processRepository.findById(reportId))
.map(this::toProcessRecord);
```

**Fixed UPDATE operations (updateProcessStatus, completeProcess, failProcess):**
```java
@Override
@Transactional
public Mono<ProcessRecord> updateProcessStatus(String reportId, ProcessingStatus status) {
    return processRepository.findById(reportId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Process not found: " + reportId)))
            .flatMap(entity -> {
                LocalDateTime now = LocalDateTime.now();
                // Use custom update with JSONB casting
                return processRepository.updateWithJsonbCast(
                        reportId,
                        entity.getPatientId(),
                        status.name(),
                        now,
                        entity.getCompletedAt(),
                        entity.getProcessingTimeMs(),
                        entity.getPatientContextJson(),  // ✅ Explicit JSONB cast
                        entity.getWorkflowOptionsJson(), // ✅ Explicit JSONB cast
                        entity.getCompletedStages(),
                        entity.getFailedStages(),
                        entity.getErrorMessage()
                );
            })
            .then(processRepository.findById(reportId))
            .map(this::toProcessRecord);
}
```

### Step 3: R2DBC Configuration (Optional)

Created `R2dbcConfiguration.java` to enable R2DBC repositories:

```java
@Configuration
@EnableR2dbcRepositories(basePackages = "com.elioo.healthcare")
public class R2dbcConfiguration extends AbstractR2dbcConfiguration {
    // Configuration for R2DBC
}
```

## Why This Happens

### PostgreSQL JSONB Type

PostgreSQL has a native `JSONB` type which is different from `VARCHAR` or `TEXT`:

```sql
-- Column definition in database
patient_context_json JSONB

-- R2DBC tries to insert as VARCHAR
INSERT INTO medical_report_process (patient_context_json)
VALUES ('{"patientId": "P001"}');  -- ❌ Type mismatch

-- Correct way with explicit cast
INSERT INTO medical_report_process (patient_context_json)
VALUES ('{"patientId": "P001"}'::jsonb);  -- ✅ Works!
```

### Spring Data R2DBC Behavior

1. **Default behavior:** R2DBC treats all String fields as `VARCHAR`
2. **Problem:** PostgreSQL won't automatically cast `VARCHAR` → `JSONB`
3. **Solution:** Explicit `::jsonb` cast in SQL query

## When to Use This Fix

**You need explicit JSONB casting when:**
- ✅ **Inserting** new records with JSONB columns
- ✅ **Updating** existing records with JSONB columns
- ✅ Using Spring Data R2DBC's default `save()` method
- ✅ JSONB columns defined in PostgreSQL schema

**You DON'T need JSONB casting when:**
- ❌ Using plain TEXT/VARCHAR columns
- ❌ Reading data from database (R2DBC handles this automatically)

## Alternative Solutions

### Option 1: Custom Converters (Complex)

Create R2DBC converters to automatically handle JSONB:

```java
@WritingConverter
public class StringToJsonConverter implements Converter<String, Json> {
    @Override
    public Json convert(String source) {
        return Json.of(source);
    }
}
```

**Issue:** Requires `io.r2dbc.postgresql.codec.Json` which may not be available in all R2DBC versions.

### Option 2: Change Column Type to TEXT (Not Recommended)

```sql
-- Change JSONB to TEXT
ALTER TABLE medical_report_process
ALTER COLUMN patient_context_json TYPE TEXT;
```

**Drawbacks:**
- ❌ Lose JSONB benefits (indexing, querying)
- ❌ No JSON validation
- ❌ Slower queries

### Option 3: Use Custom Insert Methods (✅ Recommended)

**Pros:**
- ✅ Simple to implement
- ✅ Works with all R2DBC versions
- ✅ Keeps JSONB benefits
- ✅ No additional dependencies

**Cons:**
- Need to write custom insert/update methods for each table with JSONB

## Verification

### Test the Fix

1. **Start application:**
   ```bash
   ./gradlew bootRun
   ```

2. **Submit a processing request:**
   ```bash
   curl -X POST http://localhost:8086/api/v1/medical-report/process \
     -H "Content-Type: application/json" \
     -d '{
       "imageBase64": "...",
       "patientContext": {"patientId": "P001", "age": 45},
       "workflowOptions": {"language": "en"}
     }'
   ```

3. **Check database:**
   ```sql
   SELECT report_id, patient_context_json, workflow_options_json
   FROM medical_report_process
   ORDER BY created_at DESC
   LIMIT 1;
   ```

   **Expected output:**
   ```
    report_id    | patient_context_json           | workflow_options_json
   --------------+--------------------------------+------------------------
    RPT-A1B2C3D4 | {"patientId":"P001","age":45} | {"language":"en"}
   ```

4. **Verify JSONB type:**
   ```sql
   SELECT pg_typeof(patient_context_json) as column_type
   FROM medical_report_process
   LIMIT 1;
   ```

   **Expected:** `column_type = jsonb`

## Summary

**Fixed the JSONB type mismatch error by:**
1. ✅ Added custom `insertWithJsonbCast()` method in repository
2. ✅ Added custom `updateWithJsonbCast()` method in repository
3. ✅ Used explicit `::jsonb` casting in SQL queries
4. ✅ Updated persistence adapter to use custom methods for:
   - `createProcess()` - Uses `insertWithJsonbCast()`
   - `updateProcessStatus()` - Uses `updateWithJsonbCast()`
   - `completeProcess()` - Uses `updateWithJsonbCast()`
   - `failProcess()` - Uses `updateWithJsonbCast()`
5. ✅ Kept all JSONB benefits (GIN indexes, JSON querying)

**This fix allows Spring Data R2DBC to work seamlessly with PostgreSQL JSONB columns for both INSERT and UPDATE operations!** 🎉

## Related Files

- [MedicalReportProcessRepository.java](../medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/persistence/repository/MedicalReportProcessRepository.java) - Custom insert methods
- [MedicalReportPersistenceAdapter.java](../medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/persistence/MedicalReportPersistenceAdapter.java) - Updated to use custom methods
- [R2dbcConfiguration.java](../medscribe-ai/src/main/java/com/elioo/healthcare/core/config/R2dbcConfiguration.java) - R2DBC configuration
