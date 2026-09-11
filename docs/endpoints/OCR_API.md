# OCR API - Medical Document Text Extraction

## Overview

The OCR (Optical Character Recognition) API extracts structured clinical data from medical document images. It supports multiple document types (prescriptions, lab reports, radiology reports, etc.) and multiple languages (English, Bangla).

**Base URL:** `http://localhost:8086/api/v1/medical-report`

**Authentication:** JWT Bearer Token (future implementation)

**Content-Type:** `application/json`

---

## Endpoint Details

### Extract Text from Medical Document

**Endpoint:** `POST /api/v1/medical-report/ocr`

**Description:** Processes a medical document image using OCR technology to extract plain text and structured medical test data.

**Use Cases:**
1. Healthcare provider scans a patient's lab report and needs digital extraction
2. Patient uploads prescription image for medication tracking
3. Clinic digitizes historical paper records for EHR integration
4. Automated intake process for medical documents

---

## Request Specification

### Request Headers

| Header | Type | Required | Description |
|--------|------|----------|-------------|
| Content-Type | String | Yes | Must be `application/json` |
| Authorization | String | No (Future) | `Bearer {jwt_token}` |
| X-Request-ID | String | No | Unique request identifier for tracing |
| X-Patient-Consent | String | No | Patient consent token for PHI processing |

### Request Body

```json
{
  "imageBase64": "string",
  "patientId": "string",
  "reportType": "string",
  "documentMetadata": {
    "documentDate": "string",
    "providerName": "string",
    "facilityName": "string"
  },
  "processingOptions": {
    "language": "string",
    "detectHandwriting": boolean,
    "enhanceImage": boolean
  }
}
```

### Request Parameters

#### Root Level Parameters

| Parameter | Type | Required | Constraints | Description |
|-----------|------|----------|-------------|-------------|
| imageBase64 | String | **Yes** | - Max size: 10MB<br>- Valid base64 encoding<br>- Supported formats: JPG, PNG, PDF | Base64 encoded medical document image |
| patientId | String | **Yes** | - Pattern: `^[A-Z0-9-]{5,50}$`<br>- Must be valid patient ID | Unique patient identifier |
| reportType | String | No | - Enum: See [Report Types](#report-types) | Type of medical document |
| documentMetadata | Object | No | - | Additional document information |
| processingOptions | Object | No | - | OCR processing configuration |

#### Report Types (Enum)

| Value | Description |
|-------|-------------|
| `BLOOD_TEST` | Blood/Lab test report |
| `URINE_TEST` | Urinalysis report |
| `RADIOLOGY` | X-ray, CT, MRI reports |
| `PATHOLOGY` | Tissue/cell analysis reports |
| `CARDIOLOGY` | ECG, Echo reports |
| `PRESCRIPTION` | Medication prescription |
| `DISCHARGE_SUMMARY` | Hospital discharge document |
| `OPERATION_NOTE` | Surgical procedure notes |
| `CONSULTATION_NOTE` | Doctor's consultation notes |
| `OTHER` | Other medical documents |

#### Document Metadata Object

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| documentDate | String (ISO 8601) | No | Date when document was created |
| providerName | String | No | Healthcare provider name |
| facilityName | String | No | Medical facility name |
| departmentName | String | No | Department (e.g., "Cardiology") |

#### Processing Options Object

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| language | String | No | `"EN"` | Document language: `"EN"`, `"BN"` (Bangla), `"MULTI"` |
| detectHandwriting | Boolean | No | `true` | Enable handwriting detection |
| enhanceImage | Boolean | No | `true` | Apply image enhancement before OCR |
| confidenceThreshold | Double | No | `0.70` | Minimum confidence to accept result (0.0-1.0) |

### Request Example

#### Minimal Request
```json
{
  "imageBase64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
  "patientId": "P12345"
}
```

#### Full Request
```json
{
  "imageBase64": "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDA...[truncated for brevity]",
  "patientId": "P-2024-001234",
  "reportType": "BLOOD_TEST",
  "documentMetadata": {
    "documentDate": "2024-01-15T00:00:00Z",
    "providerName": "Dr. Sarah Johnson",
    "facilityName": "City General Hospital",
    "departmentName": "Laboratory Medicine"
  },
  "processingOptions": {
    "language": "EN",
    "detectHandwriting": true,
    "enhanceImage": true,
    "confidenceThreshold": 0.75
  }
}
```

---

## Response Specification

### Success Response (200 OK)

```json
{
  "reportId": "string",
  "patientId": "string",
  "reportType": "string",
  "extractedData": [
    {
      "testName": "string",
      "testValue": "string",
      "unit": "string",
      "referenceRange": "string",
      "status": "string",
      "confidence": number,
      "position": {
        "page": number,
        "coordinates": {
          "x": number,
          "y": number,
          "width": number,
          "height": number
        }
      }
    }
  ],
  "rawText": "string",
  "confidence": number,
  "language": "string",
  "processedAt": "string",
  "processingDuration": number,
  "metadata": {
    "ocrEngine": "string",
    "imageQuality": "string",
    "totalFields": number,
    "handwritingDetected": boolean
  }
}
```

### Response Fields

| Field | Type | Description |
|-------|------|-------------|
| reportId | String | Unique identifier for this report (e.g., "RPT-ABC12345") |
| patientId | String | Patient identifier from request |
| reportType | String | Document type classification |
| extractedData | Array | List of structured medical test results |
| rawText | String | Complete OCR extracted text |
| confidence | Double | Overall OCR confidence score (0.0-1.0) |
| language | String | Detected document language |
| processedAt | String (ISO 8601) | Processing completion timestamp |
| processingDuration | Integer | Processing time in milliseconds |
| metadata | Object | Additional processing metadata |

### Extracted Data Object

| Field | Type | Description |
|-------|------|-------------|
| testName | String | Name of the medical test (e.g., "Serum Creatinine") |
| testValue | String | Measured value (e.g., "135.0") |
| unit | String | Unit of measurement (e.g., "µmol/L") |
| referenceRange | String | Normal range (e.g., "Male: 59-104, Female: 45-84") |
| status | String | `NORMAL`, `ABNORMAL`, `CRITICAL` |
| confidence | Double | Confidence for this specific extraction (0.0-1.0) |
| position | Object | Location of this data in source document |

### Status Determination Logic

| Status | Criteria |
|--------|----------|
| `NORMAL` | Value within reference range |
| `ABNORMAL` | Value outside reference range but not critical |
| `CRITICAL` | Value dangerously outside range (e.g., >2x normal) |

### Metadata Object

| Field | Type | Description |
|-------|------|-------------|
| ocrEngine | String | OCR engine used (e.g., "AWS_Textract", "Azure_Vision") |
| imageQuality | String | `HIGH`, `MEDIUM`, `LOW` |
| totalFields | Integer | Total number of fields extracted |
| handwritingDetected | Boolean | Whether handwriting was detected |
| pageCount | Integer | Number of pages processed |

### Success Response Example

```json
{
  "reportId": "RPT-8F3A2B91",
  "patientId": "P-2024-001234",
  "reportType": "BLOOD_TEST",
  "extractedData": [
    {
      "testName": "Serum Creatinine",
      "testValue": "135.0",
      "unit": "µmol/L",
      "referenceRange": "Male: 59-104, Female: 45-84",
      "status": "ABNORMAL",
      "confidence": 0.98,
      "position": {
        "page": 1,
        "coordinates": {
          "x": 120,
          "y": 450,
          "width": 300,
          "height": 25
        }
      }
    },
    {
      "testName": "Sodium",
      "testValue": "138.0",
      "unit": "mmol/L",
      "referenceRange": "136-148",
      "status": "NORMAL",
      "confidence": 0.99,
      "position": {
        "page": 1,
        "coordinates": {
          "x": 120,
          "y": 480,
          "width": 300,
          "height": 25
        }
      }
    },
    {
      "testName": "Potassium",
      "testValue": "3.5",
      "unit": "mmol/L",
      "referenceRange": "3.5-5.2",
      "status": "NORMAL",
      "confidence": 0.97,
      "position": {
        "page": 1,
        "coordinates": {
          "x": 120,
          "y": 510,
          "width": 300,
          "height": 25
        }
      }
    },
    {
      "testName": "Blood Ammonia",
      "testValue": "244.0",
      "unit": "µg/dL",
      "referenceRange": "Adult: 19-54 µg/dL",
      "status": "CRITICAL",
      "confidence": 0.96,
      "position": {
        "page": 1,
        "coordinates": {
          "x": 120,
          "y": 690,
          "width": 300,
          "height": 25
        }
      }
    },
    {
      "testName": "Bilirubin",
      "testValue": "82.0",
      "unit": "µmol/L",
      "referenceRange": "Adult upto: 19.0",
      "status": "ABNORMAL",
      "confidence": 0.95,
      "position": {
        "page": 1,
        "coordinates": {
          "x": 120,
          "y": 540,
          "width": 300,
          "height": 25
        }
      }
    }
  ],
  "rawText": "LABORATORY REPORT\n\nPatient Name: John Doe\nMRN: P-2024-001234\nDate: 15/01/2024\n\nBIOCHEMISTRY PANEL:\n\nTest Name          Result    Unit      Reference Range\n-----------------------------------------------------------\nSerum Creatinine   135.0     µmol/L    Male: 59-104, Female: 45-84\nS. Electrolytes\nSodium             138.0     mmol/L    136-148\nPotassium          3.5       mmol/L    3.5-5.2\nChloride           100.0     mmol/L    98-108\n\nLIVER FUNCTION TEST:\nBilirubin          82.0      µmol/L    Adult upto: 19.0\nAST (SGOT)         65.0      U/L       Male: Upto 42\nALT (SGPT)         28.0      U/L       Male: Upto 42\nAlbumin            31.0      g/L       35-57\nBlood Ammonia      244.0     µg/dL     Adult: 19-54 µg/dL",
  "confidence": 0.96,
  "language": "EN",
  "processedAt": "2024-01-15T14:35:22Z",
  "processingDuration": 3420,
  "metadata": {
    "ocrEngine": "AWS_Textract",
    "imageQuality": "HIGH",
    "totalFields": 9,
    "handwritingDetected": false,
    "pageCount": 1
  }
}
```

---

## Error Responses

### 400 Bad Request

**Scenario:** Invalid request parameters

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "Invalid request parameters",
    "details": [
      {
        "field": "imageBase64",
        "issue": "Invalid base64 encoding",
        "rejectedValue": null
      }
    ],
    "timestamp": "2024-01-15T14:35:22Z",
    "path": "/api/v1/medical-report/ocr"
  }
}
```

### Error Codes

| Code | HTTP Status | Description | Resolution |
|------|-------------|-------------|------------|
| `INVALID_REQUEST` | 400 | Request validation failed | Check field constraints and format |
| `INVALID_IMAGE_FORMAT` | 400 | Unsupported image format | Use JPG, PNG, or PDF |
| `IMAGE_TOO_LARGE` | 400 | Image exceeds size limit | Reduce image size to <10MB |
| `INVALID_PATIENT_ID` | 400 | Patient ID not found | Verify patient exists |
| `INVALID_BASE64` | 400 | Base64 decoding failed | Check base64 encoding |
| `OCR_PROCESSING_FAILED` | 500 | OCR engine error | Retry or contact support |
| `LOW_CONFIDENCE` | 422 | OCR confidence below threshold | Provide higher quality image |
| `UNSUPPORTED_LANGUAGE` | 400 | Language not supported | Use EN, BN, or MULTI |
| `INTERNAL_SERVER_ERROR` | 500 | Unexpected error | Contact support with request ID |

### 422 Unprocessable Entity

**Scenario:** OCR processed but confidence below threshold

```json
{
  "error": {
    "code": "LOW_CONFIDENCE",
    "message": "OCR confidence (0.62) below threshold (0.70)",
    "details": [
      {
        "field": "confidence",
        "issue": "Image quality too low for reliable extraction",
        "suggestion": "Please provide a clearer image or adjust confidence threshold"
      }
    ],
    "partialResult": {
      "reportId": "RPT-8F3A2B91",
      "rawText": "Partial text extracted...",
      "confidence": 0.62
    },
    "timestamp": "2024-01-15T14:35:22Z",
    "path": "/api/v1/medical-report/ocr"
  }
}
```

### 500 Internal Server Error

**Scenario:** System error during processing

```json
{
  "error": {
    "code": "INTERNAL_SERVER_ERROR",
    "message": "An error occurred while processing the document",
    "requestId": "req-8f3a2b91-4c5d-6e7f-8g9h-0i1j2k3l4m5n",
    "timestamp": "2024-01-15T14:35:22Z",
    "path": "/api/v1/medical-report/ocr"
  }
}
```

---

## Business Rules

### 1. Image Quality Requirements
- **Minimum Resolution**: 300 DPI recommended
- **File Size**: Maximum 10MB
- **Formats**: JPG, PNG, PDF
- **Color**: Grayscale or color (will be auto-adjusted)

### 2. Confidence Thresholds
| Confidence Level | Range | Action |
|-----------------|-------|---------|
| High | ≥ 0.85 | Automatic acceptance |
| Medium | 0.70 - 0.84 | Accepted with flag for review |
| Low | < 0.70 | Rejected or requires manual review |

### 3. Status Determination Rules

**NORMAL:**
- Value within reference range
- No clinical concern

**ABNORMAL:**
- Value outside reference range
- Requires clinical review
- Not immediately dangerous

**CRITICAL:**
- Value severely outside range (typically >2x or <0.5x normal)
- Requires immediate attention
- May trigger automated alerts

### 4. Processing Timeout
- Maximum processing time: 30 seconds
- If timeout occurs, return 504 Gateway Timeout

### 5. PHI (Protected Health Information) Handling
- All extracted data treated as PHI
- Encrypted at rest and in transit (TLS 1.2+)
- Audit logging for all access
- HIPAA compliance mandatory

---

## Usage Examples

### Example 1: Process Blood Test Report

**Scenario:** Healthcare provider uploads patient's blood test results

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/ocr \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -d '{
    "imageBase64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAY...",
    "patientId": "P12345",
    "reportType": "BLOOD_TEST",
    "processingOptions": {
      "language": "EN",
      "confidenceThreshold": 0.80
    }
  }'
```

### Example 2: Process Bangla Prescription

**Scenario:** Patient uploads Bangla prescription for medication tracking

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/ocr \
  -H "Content-Type: application/json" \
  -d '{
    "imageBase64": "data:image/jpeg;base64,/9j/4AAQSkZJRg...",
    "patientId": "P67890",
    "reportType": "PRESCRIPTION",
    "processingOptions": {
      "language": "BN",
      "detectHandwriting": true
    }
  }'
```

### Example 3: Batch Processing with Metadata

**Scenario:** Clinic digitizes historical records

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/ocr \
  -H "Content-Type: application/json" \
  -d '{
    "imageBase64": "/9j/4AAQSkZJRgABAQAAAQABAAD...",
    "patientId": "P-HIST-2020-5432",
    "reportType": "LAB_REPORT",
    "documentMetadata": {
      "documentDate": "2020-06-15T00:00:00Z",
      "providerName": "Dr. Ahmed Khan",
      "facilityName": "Dhaka Medical College",
      "departmentName": "Pathology"
    },
    "processingOptions": {
      "language": "MULTI",
      "enhanceImage": true
    }
  }'
```

---

## Performance Metrics

### Expected Response Times

| Image Size | Quality | Expected Time |
|-----------|---------|---------------|
| < 1MB | High | 2-4 seconds |
| 1-5MB | High | 4-8 seconds |
| 5-10MB | High | 8-12 seconds |
| Any | Low | +2-3 seconds (enhancement) |

### Throughput
- **Target**: < 8 seconds per document (95th percentile)
- **Maximum**: 10 seconds per document
- **Concurrent Requests**: Up to 10 simultaneous OCR processes

---

## Integration Guide

### Step 1: Encode Image to Base64

```javascript
// JavaScript Example
function encodeImageToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result.split(',')[1]);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

// Usage
const imageFile = document.getElementById('fileInput').files[0];
const base64Image = await encodeImageToBase64(imageFile);
```

```python
# Python Example
import base64

def encode_image_to_base64(image_path):
    with open(image_path, "rb") as image_file:
        return base64.b64encode(image_file.read()).decode('utf-8')

# Usage
image_base64 = encode_image_to_base64("lab_report.jpg")
```

### Step 2: Send Request

```javascript
// JavaScript (Fetch API)
async function processDocument(patientId, imageBase64, reportType) {
  const response = await fetch('http://localhost:8086/api/v1/medical-report/ocr', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer YOUR_JWT_TOKEN'
    },
    body: JSON.stringify({
      imageBase64,
      patientId,
      reportType
    })
  });

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.error.message);
  }

  return await response.json();
}

// Usage
const result = await processDocument('P12345', base64Image, 'BLOOD_TEST');
console.log('Report ID:', result.reportId);
console.log('Extracted Tests:', result.extractedData.length);
```

```python
# Python (requests library)
import requests

def process_document(patient_id, image_base64, report_type):
    url = "http://localhost:8086/api/v1/medical-report/ocr"
    headers = {
        "Content-Type": "application/json",
        "Authorization": "Bearer YOUR_JWT_TOKEN"
    }
    payload = {
        "imageBase64": image_base64,
        "patientId": patient_id,
        "reportType": report_type
    }

    response = requests.post(url, json=payload, headers=headers)
    response.raise_for_status()  # Raise exception for 4xx/5xx
    return response.json()

# Usage
result = process_document("P12345", image_base64, "BLOOD_TEST")
print(f"Report ID: {result['reportId']}")
print(f"Confidence: {result['confidence']}")
```

### Step 3: Handle Response

```javascript
// Check for abnormal or critical values
function checkForAbnormalValues(ocrResponse) {
  const abnormalTests = ocrResponse.extractedData.filter(
    test => test.status === 'ABNORMAL' || test.status === 'CRITICAL'
  );

  if (abnormalTests.length > 0) {
    console.warn(`Found ${abnormalTests.length} abnormal values:`);
    abnormalTests.forEach(test => {
      console.log(`- ${test.testName}: ${test.testValue} ${test.unit} (Status: ${test.status})`);
    });

    // Trigger alerts for critical values
    const criticalTests = abnormalTests.filter(t => t.status === 'CRITICAL');
    if (criticalTests.length > 0) {
      triggerCriticalAlert(criticalTests);
    }
  }
}
```

---

## Testing

### Test Cases

#### 1. Valid Blood Test Report
```bash
# Test with sample blood test image
curl -X POST http://localhost:8086/api/v1/medical-report/ocr \
  -H "Content-Type: application/json" \
  -d @test_data/blood_test_request.json
```

Expected: 200 OK with extracted test results

#### 2. Invalid Base64 Encoding
```bash
curl -X POST http://localhost:8086/api/v1/medical-report/ocr \
  -H "Content-Type: application/json" \
  -d '{
    "imageBase64": "not-valid-base64!!!",
    "patientId": "P12345"
  }'
```

Expected: 400 Bad Request with `INVALID_BASE64` error

#### 3. Missing Required Field
```bash
curl -X POST http://localhost:8086/api/v1/medical-report/ocr \
  -H "Content-Type: application/json" \
  -d '{
    "imageBase64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAY..."
  }'
```

Expected: 400 Bad Request with field validation error

---

## FAQ

**Q: What image quality is required for best results?**
A: 300 DPI or higher, clear text, minimal skew, good contrast. Use `enhanceImage: true` for sub-optimal images.

**Q: How long does OCR processing take?**
A: Typically 2-8 seconds depending on image size and complexity. Maximum 30 seconds before timeout.

**Q: Can I process multiple documents at once?**
A: Yes, make multiple parallel requests. System supports up to 10 concurrent OCR processes.

**Q: What happens if OCR confidence is low?**
A: If below threshold, you'll receive a 422 error with partial results. You can lower threshold or provide better image.

**Q: Are handwritten documents supported?**
A: Yes, with `detectHandwriting: true`. Handwritten text typically has lower confidence scores.

**Q: What languages are supported?**
A: Currently English (EN) and Bangla (BN). Use `MULTI` for mixed-language documents.

**Q: How is PHI data protected?**
A: All data encrypted in transit (TLS 1.2+) and at rest. HIPAA-compliant logging and access controls.

---

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2024-01-15 | Initial OCR API release |
| 1.1.0 | TBD | Add AWS Textract integration |
| 1.2.0 | TBD | Add Bangla language support |

---

## Support

For technical support or questions:
- **Email**: healthcare-team@elioo.health
- **GitLab Issues**: https://github.com/KhondokerTanvirHossain/elioo-health/issues
