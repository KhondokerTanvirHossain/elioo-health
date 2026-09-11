# Medical Report Query API Documentation

## Overview

The Medical Report Query API provides read-only access to processing data stored in the database. These APIs enable monitoring, analytics, and retrieval of processing results.

**Base URL:** `http://localhost:8086/api/v1/medical-report/query`

---

## API Endpoints

### 1. Process Status Queries

#### 1.1 Get Processing Status

Get processing status summary for a specific report.

**Endpoint:** `GET /status/{reportId}`

**Path Parameters:**
- `reportId` (string, required) - The unique report identifier

**Response Example:**
```json
{
  "reportId": "RPT-12345678",
  "patientId": "P001",
  "status": "COMPLETED",
  "createdAt": "2024-01-15T10:00:00",
  "completedAt": "2024-01-15T10:02:30",
  "processingTimeMs": 150000,
  "completedStages": 10,
  "failedStages": 0,
  "totalStages": 10,
  "errorMessage": null
}
```

**Status Codes:**
- `200 OK` - Successfully retrieved status
- `404 Not Found` - Report not found
- `500 Internal Server Error` - Server error

**cURL Example:**
```bash
curl --location 'http://localhost:8086/api/v1/medical-report/query/status/RPT-12345678'
```

---

#### 1.2 Get Processing Details

Get complete processing details including stages, errors, and results.

**Endpoint:** `GET /details/{reportId}`

**Path Parameters:**
- `reportId` (string, required) - The unique report identifier

**Response Example:**
```json
{
  "process": {
    "reportId": "RPT-12345678",
    "patientId": "P001",
    "status": "COMPLETED",
    "createdAt": "2024-01-15T10:00:00",
    "completedAt": "2024-01-15T10:02:30",
    "processingTimeMs": 150000
  },
  "stages": [
    {
      "id": "STG-001",
      "reportId": "RPT-12345678",
      "stage": "IMAGE_VALIDATION",
      "status": "COMPLETED",
      "startedAt": "2024-01-15T10:00:00",
      "completedAt": "2024-01-15T10:00:05",
      "durationMs": 5000,
      "confidenceScore": 0.98,
      "errorMessage": null
    },
    {
      "id": "STG-002",
      "reportId": "RPT-12345678",
      "stage": "OCR_PROCESSING",
      "status": "COMPLETED",
      "startedAt": "2024-01-15T10:00:05",
      "completedAt": "2024-01-15T10:01:00",
      "durationMs": 55000,
      "confidenceScore": 0.95,
      "errorMessage": null
    }
  ],
  "errors": [],
  "results": [
    {
      "id": "RES-001",
      "reportId": "RPT-12345678",
      "resultType": "OCR",
      "resultDataJson": "{...}",
      "confidenceScore": 0.95,
      "createdAt": "2024-01-15T10:01:00"
    }
  ]
}
```

**cURL Example:**
```bash
curl --location 'http://localhost:8086/api/v1/medical-report/query/details/RPT-12345678'
```

---

#### 1.3 Get Patient Reports

Get all reports for a specific patient.

**Endpoint:** `GET /patient/{patientId}/reports`

**Path Parameters:**
- `patientId` (string, required) - The unique patient identifier

**Response Example:**
```json
[
  {
    "reportId": "RPT-12345678",
    "patientId": "P001",
    "status": "COMPLETED",
    "createdAt": "2024-01-15T10:00:00",
    "completedAt": "2024-01-15T10:02:30",
    "processingTimeMs": 150000
  },
  {
    "reportId": "RPT-23456789",
    "patientId": "P001",
    "status": "COMPLETED",
    "createdAt": "2024-01-10T14:30:00",
    "completedAt": "2024-01-10T14:32:00",
    "processingTimeMs": 120000
  }
]
```

**cURL Example:**
```bash
curl --location 'http://localhost:8086/api/v1/medical-report/query/patient/P001/reports'
```

---

### 2. Result Queries

#### 2.1 Get OCR Results

Get OCR extraction results for a report.

**Endpoint:** `GET /results/{reportId}/ocr`

**Response Example:**
```json
{
  "id": "RES-001",
  "reportId": "RPT-12345678",
  "resultType": "OCR",
  "resultDataJson": "{\"extractedData\": [...], \"rawText\": \"...\"}",
  "confidenceScore": 0.95,
  "createdAt": "2024-01-15T10:01:00",
  "testCount": 15
}
```

**cURL Example:**
```bash
curl --location 'http://localhost:8086/api/v1/medical-report/query/results/RPT-12345678/ocr'
```

---

#### 2.2 Get Classification Results

Get medical entity classification results.

**Endpoint:** `GET /results/{reportId}/classification`

**cURL Example:**
```bash
curl --location 'http://localhost:8086/api/v1/medical-report/query/results/RPT-12345678/classification'
```

---

#### 2.3 Get Clinical Insights

Get AI-generated clinical insights and recommendations.

**Endpoint:** `GET /results/{reportId}/insights`

**cURL Example:**
```bash
curl --location 'http://localhost:8086/api/v1/medical-report/query/results/RPT-12345678/insights'
```

---

#### 2.4 Get All Results

Get all result types for a report.

**Endpoint:** `GET /results/{reportId}/all`

**Response Example:**
```json
[
  {
    "id": "RES-001",
    "reportId": "RPT-12345678",
    "resultType": "OCR",
    "confidenceScore": 0.95
  },
  {
    "id": "RES-002",
    "reportId": "RPT-12345678",
    "resultType": "CLASSIFICATION",
    "confidenceScore": 0.92
  },
  {
    "id": "RES-003",
    "reportId": "RPT-12345678",
    "resultType": "CLINICAL_INSIGHTS",
    "confidenceScore": null
  }
]
```

**cURL Example:**
```bash
curl --location 'http://localhost:8086/api/v1/medical-report/query/results/RPT-12345678/all'
```

---

#### 2.5 Get High Risk Reports

Get all reports flagged as high risk or critical.

**Endpoint:** `GET /high-risk`

**Response Example:**
```json
[
  {
    "id": "RES-005",
    "reportId": "RPT-99999999",
    "resultType": "RISK_ASSESSMENT",
    "riskLevel": "HIGH",
    "createdAt": "2024-01-15T11:00:00"
  },
  {
    "id": "RES-006",
    "reportId": "RPT-88888888",
    "resultType": "RISK_ASSESSMENT",
    "riskLevel": "CRITICAL",
    "createdAt": "2024-01-15T12:00:00"
  }
]
```

**cURL Example:**
```bash
curl --location 'http://localhost:8086/api/v1/medical-report/query/high-risk'
```

---

### 3. Error Queries

#### 3.1 Get Report Errors

Get all errors that occurred during processing of a report.

**Endpoint:** `GET /errors/{reportId}`

**Response Example:**
```json
[
  {
    "id": "ERR-001",
    "reportId": "RPT-12345678",
    "stageId": "STG-003",
    "stage": "ICD10_INFERENCE",
    "errorCode": "ICD10_INFERENCE_FAILED",
    "errorMessage": "AWS Comprehend Medical API rate limit exceeded",
    "isRetryable": true,
    "occurredAt": "2024-01-15T10:01:30",
    "severity": "WARNING"
  }
]
```

**cURL Example:**
```bash
curl --location 'http://localhost:8086/api/v1/medical-report/query/errors/RPT-12345678'
```

---

#### 3.2 Get Recent Errors

Get recent errors for monitoring and alerting.

**Endpoint:** `GET /errors/recent`

**Query Parameters:**
- `since` (ISO-8601 timestamp, optional) - Get errors after this timestamp
  - Default: Last 24 hours
  - Format: `2024-01-15T00:00:00`

**Response Example:**
```json
[
  {
    "id": "ERR-001",
    "reportId": "RPT-12345678",
    "errorCode": "OCR_PROCESSING_TIMEOUT",
    "errorMessage": "OCR processing timed out after 60 seconds",
    "isRetryable": true,
    "occurredAt": "2024-01-15T10:30:00",
    "severity": "ERROR"
  }
]
```

**cURL Example:**
```bash
curl --location 'http://localhost:8086/api/v1/medical-report/query/errors/recent?since=2024-01-15T00:00:00'
```

---

### 4. Analytics Queries

#### 4.1 Get Processing Metrics

Get aggregated processing metrics for monitoring dashboard.

**Endpoint:** `GET /analytics/metrics`

**Query Parameters:**
- `since` (ISO-8601 timestamp, optional) - Calculate metrics from this timestamp
  - Default: Last 7 days
  - Format: `2024-01-15T00:00:00`

**Response Example:**
```json
{
  "totalProcessed": 1250,
  "completedCount": 1100,
  "failedCount": 50,
  "partialSuccessCount": 100,
  "successRate": 96.0,
  "averageProcessingTimeMs": 145000.0,
  "stageMetrics": {
    "IMAGE_VALIDATION": {
      "stage": "IMAGE_VALIDATION",
      "totalCount": 1250,
      "successCount": 1240,
      "failedCount": 10,
      "successRate": 99.2,
      "avgDurationMs": 5000.0
    },
    "OCR_PROCESSING": {
      "stage": "OCR_PROCESSING",
      "totalCount": 1240,
      "successCount": 1200,
      "failedCount": 40,
      "successRate": 96.8,
      "avgDurationMs": 55000.0
    }
  },
  "errorCounts": {
    "OCR_PROCESSING_TIMEOUT": 25,
    "IMAGE_VALIDATION_FAILED": 10,
    "ICD10_INFERENCE_FAILED": 15
  }
}
```

**cURL Example:**
```bash
curl --location 'http://localhost:8086/api/v1/medical-report/query/analytics/metrics?since=2024-01-01T00:00:00'
```

---

#### 4.2 Get Stage Statistics

Get performance statistics for each processing stage.

**Endpoint:** `GET /analytics/stage-stats`

**Query Parameters:**
- `since` (ISO-8601 timestamp, optional) - Analyze stages from this timestamp
  - Default: Last 7 days

**Response Example:**
```json
[
  {
    "stage": "IMAGE_VALIDATION",
    "avgDurationMs": 5000.0,
    "totalCount": 1250,
    "failedCount": 10,
    "failureRate": 0.008
  },
  {
    "stage": "OCR_PROCESSING",
    "avgDurationMs": 55000.0,
    "totalCount": 1240,
    "failedCount": 40,
    "failureRate": 0.032
  },
  {
    "stage": "ENTITY_DETECTION",
    "avgDurationMs": 30000.0,
    "totalCount": 1200,
    "failedCount": 5,
    "failureRate": 0.004
  }
]
```

**cURL Example:**
```bash
curl --location 'http://localhost:8086/api/v1/medical-report/query/analytics/stage-stats?since=2024-01-01T00:00:00'
```

---

#### 4.3 Get Error Patterns

Get error patterns for debugging and improving reliability.

**Endpoint:** `GET /analytics/error-patterns`

**Query Parameters:**
- `since` (ISO-8601 timestamp, optional) - Analyze errors from this timestamp
  - Default: Last 7 days

**Response Example:**
```json
[
  {
    "errorCode": "OCR_PROCESSING_TIMEOUT",
    "stage": "OCR_PROCESSING",
    "severity": "ERROR",
    "errorCount": 25,
    "retryableCount": 25
  },
  {
    "errorCode": "ICD10_INFERENCE_FAILED",
    "stage": "ICD10_INFERENCE",
    "severity": "WARNING",
    "errorCount": 15,
    "retryableCount": 15
  },
  {
    "errorCode": "IMAGE_VALIDATION_FAILED",
    "stage": "IMAGE_VALIDATION",
    "severity": "ERROR",
    "errorCount": 10,
    "retryableCount": 0
  }
]
```

**cURL Example:**
```bash
curl --location 'http://localhost:8086/api/v1/medical-report/query/analytics/error-patterns?since=2024-01-01T00:00:00'
```

---

## Use Cases

### Monitoring Dashboard

```bash
# Get last 24 hours metrics
curl 'http://localhost:8086/api/v1/medical-report/query/analytics/metrics?since=2024-01-15T00:00:00'

# Check recent errors
curl 'http://localhost:8086/api/v1/medical-report/query/errors/recent?since=2024-01-15T00:00:00'

# Get high-risk reports
curl 'http://localhost:8086/api/v1/medical-report/query/high-risk'
```

### Patient Portal

```bash
# Get all reports for a patient
curl 'http://localhost:8086/api/v1/medical-report/query/patient/P001/reports'

# Get specific report details
curl 'http://localhost:8086/api/v1/medical-report/query/details/RPT-12345678'

# Get clinical insights
curl 'http://localhost:8086/api/v1/medical-report/query/results/RPT-12345678/insights'
```

### Debugging and Analytics

```bash
# Get stage performance stats
curl 'http://localhost:8086/api/v1/medical-report/query/analytics/stage-stats?since=2024-01-01T00:00:00'

# Get error patterns
curl 'http://localhost:8086/api/v1/medical-report/query/analytics/error-patterns?since=2024-01-01T00:00:00'

# Get specific report errors
curl 'http://localhost:8086/api/v1/medical-report/query/errors/RPT-12345678'
```

---

## Error Responses

All endpoints return standardized error responses:

```json
{
  "errorCode": "QUERY_ERROR",
  "message": "Error description here",
  "timestamp": "2024-01-15T10:00:00"
}
```

**Common Error Codes:**
- `QUERY_ERROR` - General query error
- `REPORT_NOT_FOUND` - Report ID not found in database
- `INVALID_DATE_FORMAT` - Invalid date format in query parameter

---

## Notes

1. **Date Format:** All timestamps use ISO-8601 format: `2024-01-15T10:00:00`
2. **Pagination:** Not yet implemented (future enhancement)
3. **Caching:** Query results are not cached (reads directly from database)
4. **Performance:** Large date ranges may take longer to process
5. **JSONB Fields:** Result data is stored as JSONB and returned as JSON strings

---

## Future Enhancements

- [ ] Pagination support for large result sets
- [ ] Filtering and sorting options
- [ ] Response caching for frequently accessed data
- [ ] Export to CSV/Excel for analytics
- [ ] Grafana/Prometheus metrics integration
- [ ] Real-time updates via WebSocket
