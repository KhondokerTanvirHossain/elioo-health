# MedScribe AI - Medical Report Processing API Documentation

## Overview

This document describes the three-step medical report processing workflow:

1. **OCR Processing** - Extract structured data from medical test report images
2. **Medical Entity Classification** - Extract and classify medical entities using AWS Comprehend Medical
3. **AI-Powered Suggestions** - Generate insights, summaries, and recommendations

---

## Base URL

```
http://localhost:8086/api/v1/medical-report
```

---

## API Endpoints

### 1. OCR Processing API

Extract structured medical test data from images (supports Bangla + English).

**Endpoint:** `POST /api/v1/medical-report/ocr`

**Request:**

```json
{
  "imageBase64": "base64_encoded_image_string",
  "patientId": "P12345",
  "reportType": "BLOOD_TEST"
}
```

**Request Parameters:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| imageBase64 | String | Yes | Base64 encoded medical report image |
| patientId | String | Yes | Patient identifier |
| reportType | Enum | No | Type of report (BLOOD_TEST, URINE_TEST, RADIOLOGY, etc.) |

**Response:** `200 OK`

```json
{
  "reportId": "RPT-2024-001",
  "patientId": "P12345",
  "extractedData": [
    {
      "testName": "Serum Creatinine",
      "testValue": "135.0",
      "unit": "µmol/L",
      "referenceRange": "Male: 59-104, Female: 45-84",
      "status": "ABNORMAL"
    },
    {
      "testName": "Sodium",
      "testValue": "138.0",
      "unit": "mmol/L",
      "referenceRange": "136-148",
      "status": "NORMAL"
    },
    {
      "testName": "Potassium",
      "testValue": "3.5",
      "unit": "mmol/L",
      "referenceRange": "3.5-5.2",
      "status": "NORMAL"
    },
    {
      "testName": "Blood Ammonia",
      "testValue": "244.0",
      "unit": "µg/dL",
      "referenceRange": "Adult: 19-54 µg/dL",
      "status": "ABNORMAL"
    }
  ],
  "rawText": "Original OCR extracted text...",
  "confidence": 0.96,
  "processedAt": "2024-01-15T10:30:00Z"
}
```

**Response Fields:**

| Field | Type | Description |
|-------|------|-------------|
| reportId | String | Unique report identifier |
| patientId | String | Patient identifier |
| extractedData | Array | List of extracted test results |
| extractedData[].testName | String | Name of the medical test |
| extractedData[].testValue | String | Measured value |
| extractedData[].unit | String | Unit of measurement |
| extractedData[].referenceRange | String | Normal range for the test |
| extractedData[].status | Enum | NORMAL, ABNORMAL, CRITICAL |
| rawText | String | Full OCR extracted text |
| confidence | Double | OCR confidence score (0.0-1.0) |
| processedAt | DateTime | Processing timestamp |

**Error Responses:**

- `400 Bad Request` - Invalid image format or missing required fields
- `500 Internal Server Error` - OCR processing failed

---

### 2. Medical Entity Classification API

Classify extracted medical data using AWS Comprehend Medical to identify test names, values, and medical entities.

**Endpoint:** `POST /api/v1/medical-report/classify`

**Request:**

```json
{
  "reportId": "RPT-2024-001",
  "extractedData": [
    {
      "testName": "Serum Creatinine",
      "testValue": "135.0",
      "unit": "µmol/L",
      "referenceRange": "Male: 59-104, Female: 45-84"
    }
  ]
}
```

**Request Parameters:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| reportId | String | Yes | Report identifier from OCR step |
| extractedData | Array | Yes | Array of test results to classify |

**Response:** `200 OK`

```json
{
  "reportId": "RPT-2024-001",
  "classificationResult": {
    "Entities": [
      {
        "Id": 1,
        "Text": "Serum Creatinine",
        "Category": "TEST_TREATMENT_PROCEDURE",
        "Type": "TEST_NAME",
        "Score": 0.98,
        "Attributes": [
          {
            "Type": "TEST_VALUE",
            "Score": 0.99,
            "RelationshipScore": 0.97,
            "RelationshipType": "TEST_VALUE",
            "Id": 2,
            "Text": "135.0",
            "Category": "TEST_TREATMENT_PROCEDURE",
            "Traits": []
          },
          {
            "Type": "TEST_UNIT",
            "Score": 0.96,
            "RelationshipScore": 0.95,
            "RelationshipType": "TEST_UNIT",
            "Id": 3,
            "Text": "µmol/L",
            "Category": "TEST_TREATMENT_PROCEDURE",
            "Traits": []
          }
        ]
      },
      {
        "Id": 4,
        "Text": "Blood Ammonia",
        "Category": "TEST_TREATMENT_PROCEDURE",
        "Type": "TEST_NAME",
        "Score": 0.97,
        "Attributes": [
          {
            "Type": "TEST_VALUE",
            "Score": 0.98,
            "RelationshipScore": 0.96,
            "RelationshipType": "TEST_VALUE",
            "Id": 5,
            "Text": "244.0",
            "Category": "TEST_TREATMENT_PROCEDURE",
            "Traits": []
          }
        ]
      }
    ]
  },
  "medicalCodes": {
    "ICD10": ["R79.89", "N17.9"],
    "LOINC": ["2160-0", "16362-6"],
    "SNOMED": ["313822004", "43904001"]
  },
  "processedAt": "2024-01-15T10:31:00Z"
}
```

**Response Fields:**

| Field | Type | Description |
|-------|------|-------------|
| reportId | String | Report identifier |
| classificationResult | Object | AWS Comprehend Medical response |
| classificationResult.Entities | Array | Identified medical entities |
| classificationResult.Entities[].Id | Integer | Entity identifier |
| classificationResult.Entities[].Text | String | Entity text |
| classificationResult.Entities[].Category | String | Entity category |
| classificationResult.Entities[].Type | String | Entity type (TEST_NAME, MEDICATION, etc.) |
| classificationResult.Entities[].Score | Double | Confidence score |
| classificationResult.Entities[].Attributes | Array | Related attributes (value, unit) |
| medicalCodes | Object | Mapped medical coding systems |
| medicalCodes.ICD10 | Array | ICD-10 diagnostic codes |
| medicalCodes.LOINC | Array | LOINC test codes |
| medicalCodes.SNOMED | Array | SNOMED CT codes |
| processedAt | DateTime | Processing timestamp |

**Error Responses:**

- `400 Bad Request` - Invalid report ID or data format
- `404 Not Found` - Report not found
- `500 Internal Server Error` - Classification failed

---

### 3. AI Medical Suggestions API

Generate AI-powered insights, summaries, and medical recommendations based on classified test results.

**Endpoint:** `POST /api/v1/medical-report/suggestions`

**Request:**

```json
{
  "reportId": "RPT-2024-001",
  "patientContext": {
    "age": 45,
    "gender": "MALE",
    "medicalHistory": ["Diabetes Type 2", "Hypertension"]
  },
  "includeActionPlan": true
}
```

**Request Parameters:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| reportId | String | Yes | Report identifier |
| patientContext | Object | No | Additional patient information for context |
| patientContext.age | Integer | No | Patient age |
| patientContext.gender | Enum | No | MALE, FEMALE, OTHER |
| patientContext.medicalHistory | Array | No | Known medical conditions |
| includeActionPlan | Boolean | No | Generate detailed action plan (default: true) |

**Response:** `200 OK`

```json
{
  "reportId": "RPT-2024-001",
  "summary": "Your recent blood tests show some concerning results, particularly related to your liver function and body chemistry. The levels of certain substances in your blood suggest your liver may not be working as well as it should. This could be affecting how your body processes waste products and maintains its chemical balance.",
  "keyFindings": [
    {
      "finding": "Elevated Serum Creatinine (135.0 µmol/L)",
      "severity": "MODERATE",
      "interpretation": "Your kidney function shows mild impairment. Creatinine is a waste product that kidneys normally filter out.",
      "normalRange": "Male: 59-104 µmol/L"
    },
    {
      "finding": "Significantly Elevated Blood Ammonia (244.0 µg/dL)",
      "severity": "HIGH",
      "interpretation": "This indicates your liver may not be processing waste products effectively. Elevated ammonia can affect brain function.",
      "normalRange": "19-54 µg/dL"
    },
    {
      "finding": "Elevated Bilirubin (82.0 µmol/L)",
      "severity": "HIGH",
      "interpretation": "High bilirubin suggests liver dysfunction or bile flow issues. This can cause yellowing of skin and eyes (jaundice).",
      "normalRange": "Up to 19.0 µmol/L"
    },
    {
      "finding": "Elevated AST (65.0 U/L)",
      "severity": "MODERATE",
      "interpretation": "This liver enzyme is elevated, suggesting liver cell damage or inflammation.",
      "normalRange": "Male: Up to 42 U/L"
    },
    {
      "finding": "Low Albumin (31.0 g/L)",
      "severity": "MODERATE",
      "interpretation": "Low albumin indicates your liver may not be producing enough protein, which can affect fluid balance.",
      "normalRange": "35-57 g/L"
    }
  ],
  "aiSuggestions": [
    {
      "category": "IMMEDIATE_ACTION",
      "priority": "HIGH",
      "recommendation": "See a liver specialist (hepatologist) as soon as possible - within 24-48 hours",
      "rationale": "The combination of elevated ammonia, bilirubin, and liver enzymes with low albumin suggests significant liver dysfunction requiring immediate medical evaluation."
    },
    {
      "category": "DIAGNOSTIC_TESTS",
      "priority": "HIGH",
      "recommendation": "Additional tests needed: Complete liver panel, Hepatitis screening, Liver ultrasound or CT scan, Coagulation studies (PT/INR)",
      "rationale": "These tests will help determine the cause and extent of liver dysfunction."
    },
    {
      "category": "MEDICATION",
      "priority": "HIGH",
      "recommendation": "Your doctor may start you on lactulose or rifaximin to reduce blood ammonia levels",
      "rationale": "Elevated ammonia can lead to hepatic encephalopathy (confusion, altered mental state), which needs prompt treatment."
    },
    {
      "category": "MONITORING",
      "priority": "MEDIUM",
      "recommendation": "Close monitoring with repeat blood tests in 24-72 hours",
      "rationale": "Tracking trends in liver function and ammonia levels is crucial for treatment adjustment."
    },
    {
      "category": "LIFESTYLE",
      "priority": "MEDIUM",
      "recommendation": "Reduce protein intake temporarily, avoid alcohol completely, stay well hydrated",
      "rationale": "Reducing protein can help lower ammonia production; alcohol can worsen liver damage."
    }
  ],
  "insight": "These results indicate significant liver dysfunction that requires immediate medical attention. The good news is that many liver problems can be treated effectively if caught early and managed appropriately. It's crucial to follow up with your doctor or a liver specialist promptly to determine the underlying cause and start appropriate treatment.",
  "riskLevel": "HIGH",
  "requiresImmediateAttention": true,
  "generatedAt": "2024-01-15T10:32:00Z",
  "confidenceScore": 0.94
}
```

**Response Fields:**

| Field | Type | Description |
|-------|------|-------------|
| reportId | String | Report identifier |
| summary | String | Patient-friendly summary of results |
| keyFindings | Array | List of significant findings |
| keyFindings[].finding | String | Description of the finding |
| keyFindings[].severity | Enum | LOW, MODERATE, HIGH, CRITICAL |
| keyFindings[].interpretation | String | Plain language explanation |
| keyFindings[].normalRange | String | Reference range for comparison |
| aiSuggestions | Array | AI-generated recommendations |
| aiSuggestions[].category | Enum | IMMEDIATE_ACTION, DIAGNOSTIC_TESTS, MEDICATION, MONITORING, LIFESTYLE |
| aiSuggestions[].priority | Enum | LOW, MEDIUM, HIGH |
| aiSuggestions[].recommendation | String | Specific recommendation |
| aiSuggestions[].rationale | String | Explanation for the recommendation |
| insight | String | Overall clinical insight |
| riskLevel | Enum | LOW, MODERATE, HIGH, CRITICAL |
| requiresImmediateAttention | Boolean | Whether urgent medical attention is needed |
| generatedAt | DateTime | Generation timestamp |
| confidenceScore | Double | AI confidence in suggestions (0.0-1.0) |

**Error Responses:**

- `400 Bad Request` - Invalid report ID
- `404 Not Found` - Report not found or not classified yet
- `500 Internal Server Error` - AI generation failed

---

## Complete Workflow Example

### Step 1: OCR Processing

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/ocr \
  -H "Content-Type: application/json" \
  -d '{
    "imageBase64": "iVBORw0KGgoAAAANSUhEUgAA...",
    "patientId": "P12345",
    "reportType": "BLOOD_TEST"
  }'
```

**Response:**
```json
{
  "reportId": "RPT-2024-001",
  "extractedData": [...]
}
```

### Step 2: Medical Classification

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/classify \
  -H "Content-Type: application/json" \
  -d '{
    "reportId": "RPT-2024-001",
    "extractedData": [...]
  }'
```

**Response:**
```json
{
  "reportId": "RPT-2024-001",
  "classificationResult": {...},
  "medicalCodes": {...}
}
```

### Step 3: Generate Suggestions

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/suggestions \
  -H "Content-Type: application/json" \
  -d '{
    "reportId": "RPT-2024-001",
    "patientContext": {
      "age": 45,
      "gender": "MALE"
    }
  }'
```

**Response:**
```json
{
  "reportId": "RPT-2024-001",
  "summary": "...",
  "keyFindings": [...],
  "aiSuggestions": [...]
}
```

---

## Status Codes

| Code | Description |
|------|-------------|
| 200 | Success |
| 400 | Bad Request - Invalid input |
| 404 | Not Found - Resource doesn't exist |
| 500 | Internal Server Error |
| 503 | Service Unavailable - AWS service error |

---

## Rate Limits

- OCR API: 10 requests/minute
- Classification API: 20 requests/minute
- Suggestions API: 15 requests/minute

---

## Data Retention

- OCR results: 90 days
- Classification results: 90 days
- AI suggestions: 90 days
- Raw images: 30 days (encrypted)

---

## Security & Compliance

- All endpoints require JWT authentication
- PHI data is encrypted at rest and in transit
- HIPAA-compliant logging (no PHI in logs)
- Audit trail for all data access

---

## Support

For API support or questions:
- **Email**: healthcare-team@elioo.health
- **GitLab Issues**: https://github.com/KhondokerTanvirHossain/elioo-health/issues
