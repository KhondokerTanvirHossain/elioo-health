# Quick Start Guide - Phase 3 Features

## Prerequisites

1. **PostgreSQL Database Running**
   ```bash
   # Default connection (from application.properties):
   # Host: localhost
   # Port: 5432
   # Database: pfh
   # Schema: pfh
   # Username: pfh
   # Password: pfh
   ```

2. **Application Built**
   ```bash
   ./gradlew build
   ```

## Step 1: Database Setup

### Option A: Auto-Migration (Recommended)

The Flyway migration script will run automatically on application startup:

```bash
./gradlew bootRun
```

Check logs for migration success:
```
[INFO] Flyway: Successfully applied 1 migration to schema "pfh"
[INFO] Flyway: Migration V1__create_medical_report_tables.sql completed successfully
```

### Option B: Manual Migration

If auto-migration is disabled, run manually:

```sql
-- Connect to PostgreSQL
psql -U pfh -d pfh

-- Run the migration script
\i medscribe-ai/src/main/resources/db/migration/V1__create_medical_report_tables.sql

-- Verify tables created
\dt
-- Should show:
--   medical_report_process
--   medical_report_process_stage
--   medical_report_error
--   medical_report_result
```

## Step 2: Start Application

```bash
./gradlew bootRun

# Or if already built:
java -jar medscribe-ai/build/libs/medscribe-ai-1.0.0.jar
```

Check application is running:
```bash
curl http://localhost:8086/actuator/health
```

Expected response:
```json
{"status":"UP"}
```

## Step 3: Process a Medical Report

### Using cURL

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/process \
  -H "Content-Type: application/json" \
  -d '{
    "imageBase64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    "patientContext": {
      "patientId": "P001",
      "age": 45,
      "gender": "MALE",
      "medicalHistory": ["Diabetes Type 2"],
      "currentMedications": ["Metformin 500mg"],
      "allergies": [],
      "vitalSigns": {},
      "lifestyle": {}
    },
    "workflowOptions": {
      "language": "en",
      "targetAudience": "PATIENT",
      "skipValidation": false
    }
  }'
```

### Using REST Client (IntelliJ/VSCode)

Open [query-api-tests.http](../query-api-tests.http) and execute the requests.

### Expected Response

```json
{
  "reportId": "RPT-A1B2C3D4",
  "patientId": "P001",
  "processingStatus": "COMPLETED",
  "processingTimeMs": 145000,
  "timestamp": "2024-01-15T10:02:30",
  "workflow": {
    "completedStages": 10,
    "failedStages": 0
  },
  "ocrResults": { ... },
  "entityDetection": { ... },
  "medicalCodes": { ... },
  "clinicalInsights": { ... }
}
```

**Note the `reportId`** - you'll need it for query APIs.

## Step 4: Verify Database Persistence

### Check Process Record

```sql
-- Connect to database
psql -U pfh -d pfh

-- View the created process
SELECT report_id, patient_id, status, completed_stages, processing_time_ms
FROM medical_report_process
ORDER BY created_at DESC
LIMIT 1;
```

Expected output:
```
 report_id    | patient_id | status    | completed_stages | processing_time_ms
--------------+------------+-----------+------------------+-------------------
 RPT-A1B2C3D4 | P001       | COMPLETED | 10               | 145000
```

### Check Stages

```sql
-- View all stages for the report
SELECT stage, status, duration_ms, confidence_score
FROM medical_report_process_stage
WHERE report_id = 'RPT-A1B2C3D4'
ORDER BY started_at;
```

Expected output:
```
 stage                 | status    | duration_ms | confidence_score
-----------------------+-----------+-------------+-----------------
 IMAGE_VALIDATION      | COMPLETED | 5000        | 0.98
 OCR_PROCESSING        | COMPLETED | 55000       | 0.95
 ENTITY_DETECTION      | COMPLETED | 30000       | 0.92
 ICD10_INFERENCE       | COMPLETED | 15000       | NULL
 RXNORM_INFERENCE      | COMPLETED | 10000       | NULL
 CLINICAL_INSIGHTS     | COMPLETED | 20000       | NULL
 ...
```

### Check Results

```sql
-- View all results for the report
SELECT result_type, confidence_score, test_count, entity_count, risk_level
FROM medical_report_result
WHERE report_id = 'RPT-A1B2C3D4';
```

Expected output:
```
 result_type         | confidence_score | test_count | entity_count | risk_level
---------------------+------------------+------------+--------------+-----------
 OCR                 | 0.95             | 15         | NULL         | NULL
 CLASSIFICATION      | 0.92             | NULL       | 45           | NULL
 ICD10               | 0.88             | NULL       | NULL         | NULL
 RXNORM              | 0.90             | NULL       | NULL         | NULL
 CLINICAL_INSIGHTS   | NULL             | NULL       | NULL         | HIGH
 RISK_ASSESSMENT     | NULL             | NULL       | NULL         | HIGH
 RECOMMENDATIONS     | NULL             | NULL       | NULL         | NULL
```

## Step 5: Query Processing Data

### Get Processing Status

```bash
curl http://localhost:8086/api/v1/medical-report/query/status/RPT-A1B2C3D4
```

Response:
```json
{
  "reportId": "RPT-A1B2C3D4",
  "patientId": "P001",
  "status": "COMPLETED",
  "createdAt": "2024-01-15T10:00:00",
  "completedAt": "2024-01-15T10:02:30",
  "processingTimeMs": 145000,
  "completedStages": 10,
  "failedStages": 0,
  "totalStages": 10
}
```

### Get Complete Details

```bash
curl http://localhost:8086/api/v1/medical-report/query/details/RPT-A1B2C3D4
```

Returns: Process + all stages + all errors + all results

### Get OCR Results

```bash
curl http://localhost:8086/api/v1/medical-report/query/results/RPT-A1B2C3D4/ocr
```

Returns: OCR result with extracted test data

### Get Clinical Insights

```bash
curl http://localhost:8086/api/v1/medical-report/query/results/RPT-A1B2C3D4/insights
```

Returns: AI-generated clinical insights and recommendations

### Get All Patient Reports

```bash
curl http://localhost:8086/api/v1/medical-report/query/patient/P001/reports
```

Returns: List of all reports for patient P001

## Step 6: Monitor with Analytics APIs

### Get Processing Metrics (Last 7 Days)

```bash
curl "http://localhost:8086/api/v1/medical-report/query/analytics/metrics?since=2024-01-01T00:00:00"
```

Returns:
```json
{
  "totalProcessed": 50,
  "completedCount": 45,
  "failedCount": 3,
  "partialSuccessCount": 2,
  "successRate": 94.0,
  "averageProcessingTimeMs": 145000.0,
  "stageMetrics": { ... },
  "errorCounts": { ... }
}
```

### Get Stage Statistics

```bash
curl "http://localhost:8086/api/v1/medical-report/query/analytics/stage-stats?since=2024-01-01T00:00:00"
```

Returns: Performance stats for each processing stage

### Get Error Patterns

```bash
curl "http://localhost:8086/api/v1/medical-report/query/analytics/error-patterns?since=2024-01-01T00:00:00"
```

Returns: Common errors grouped by error code and stage

### Get High-Risk Reports

```bash
curl http://localhost:8086/api/v1/medical-report/query/high-risk
```

Returns: All reports flagged as HIGH or CRITICAL risk

## Troubleshooting

### Database Connection Failed

**Error:** `Connection refused to localhost:5432`

**Solution:**
1. Verify PostgreSQL is running: `pg_isready`
2. Check connection settings in [application-local.properties](../medscribe-ai/src/main/resources/application-local.properties)
3. Test connection: `psql -U pfh -d pfh -h localhost`

### Migration Failed

**Error:** `Flyway migration failed`

**Solution:**
1. Check if tables already exist: `psql -U pfh -d pfh -c "\dt"`
2. Drop existing tables if needed (CAUTION - data loss):
   ```sql
   DROP TABLE IF EXISTS medical_report_result CASCADE;
   DROP TABLE IF EXISTS medical_report_error CASCADE;
   DROP TABLE IF EXISTS medical_report_process_stage CASCADE;
   DROP TABLE IF EXISTS medical_report_process CASCADE;
   ```
3. Restart application to trigger migration

### Query Returns 404

**Error:** `404 Not Found` when querying report

**Solution:**
1. Verify report exists in database:
   ```sql
   SELECT report_id FROM medical_report_process WHERE report_id = 'RPT-A1B2C3D4';
   ```
2. Check you're using the correct report ID (case-sensitive)
3. Verify application is running on correct port (8086)

### Empty Results

**Error:** Query returns empty array

**Solution:**
1. Verify data was persisted during processing
2. Check date range in analytics queries
3. Verify no errors in application logs

## Next Steps

1. **Process Multiple Reports** - Test with different patient data and report types
2. **Monitor Performance** - Use analytics APIs to track processing metrics
3. **Test Error Scenarios** - Simulate failures to verify error tracking
4. **Integration Testing** - Run comprehensive tests with Testcontainers
5. **Deploy to Staging** - Test in staging environment with real data

## API Documentation

- **Complete API Docs:** [QUERY_API_DOCUMENTATION.md](QUERY_API_DOCUMENTATION.md)
- **Phase 3 Summary:** [PHASE_3_COMPLETION_SUMMARY.md](PHASE_3_COMPLETION_SUMMARY.md)
- **REST Client Tests:** [query-api-tests.http](../query-api-tests.http)

## Support

- **Issues:** Check application logs in `/tmp/logs/`
- **Database:** Query tables directly with SQL for debugging
- **API:** Use REST client tools (Postman, Insomnia, VS Code REST Client)
