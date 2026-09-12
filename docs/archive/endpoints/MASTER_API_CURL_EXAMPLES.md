# Master Orchestration API - cURL Examples

**Endpoint:** `POST /api/v1/medical-report/process`
**Base URL (Local):** `http://localhost:8086`
**Base URL (Remote):** `http://<OLD_SERVER_IP>:8086`

---

## Quick Start - Minimal Request

### Basic Test (Local)

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '{
    "imageBase64": "YOUR_BASE64_IMAGE_HERE",
    "patientContext": {
      "patientId": "P12345",
      "age": 59,
      "gender": "MALE"
    }
  }'
```

### Basic Test (Remote)

```bash
curl --location 'http://<OLD_SERVER_IP>:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: YOUR_API_KEY' \
  --data '{
    "imageBase64": "YOUR_BASE64_IMAGE_HERE",
    "patientContext": {
      "patientId": "P12345",
      "age": 59,
      "gender": "MALE"
    }
  }'
```

---

## Full Request with All Options

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '{
    "imageBase64": "YOUR_BASE64_IMAGE_HERE",
    "patientContext": {
      "patientId": "P12345",
      "age": 59,
      "gender": "MALE",
      "medicalHistory": [
        "Diabetes Type 2",
        "Hypertension"
      ],
      "currentMedications": [
        "Metformin 1000mg twice daily",
        "Lisinopril 10mg once daily"
      ],
      "allergies": [
        "Penicillin"
      ],
      "vitalSigns": {
        "bloodPressure": "140/90",
        "heartRate": 78,
        "weight": "85kg",
        "height": "175cm"
      },
      "lifestyle": {
        "smokingStatus": "never",
        "alcoholUse": "occasional",
        "exerciseFrequency": "moderate"
      }
    },
    "workflowOptions": {
      "skipValidation": false,
      "includeRawText": true,
      "includeEntityRelationships": true,
      "requestedCodeSystems": ["ICD10", "RXNORM"],
      "includeEducationalContent": true,
      "riskAssessmentCategories": [
        "CARDIOVASCULAR",
        "METABOLIC",
        "RENAL",
        "HEPATIC"
      ],
      "targetAudience": "PATIENT",
      "language": "en",
      "confidenceThreshold": 0.70,
      "includeActionPlan": true,
      "includeTrendAnalysis": false
    }
  }'
```

---

## Use Case Examples

### 1. Skip Image Validation (Fast Mode)

When you've already verified image quality:

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '{
    "imageBase64": "YOUR_BASE64_IMAGE_HERE",
    "patientContext": {
      "patientId": "P11111",
      "age": 35,
      "gender": "MALE"
    },
    "workflowOptions": {
      "skipValidation": true,
      "includeRawText": false
    }
  }'
```

**Performance Gain:** ~300ms faster

---

### 2. Provider Mode (Medical Terminology)

For healthcare providers who prefer medical terminology:

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '{
    "imageBase64": "YOUR_BASE64_IMAGE_HERE",
    "patientContext": {
      "patientId": "P22222",
      "age": 62,
      "gender": "MALE",
      "medicalHistory": [
        "Type 2 Diabetes Mellitus",
        "Essential Hypertension",
        "Chronic Kidney Disease Stage 3"
      ]
    },
    "workflowOptions": {
      "targetAudience": "PROVIDER",
      "includeEducationalContent": false
    }
  }'
```

**Output:** Medical terminology instead of patient-friendly language

---

### 3. ICD-10 Only (No Medications)

Get only diagnosis codes, skip medication codes:

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '{
    "imageBase64": "YOUR_BASE64_IMAGE_HERE",
    "patientContext": {
      "patientId": "P33333",
      "age": 50,
      "gender": "FEMALE"
    },
    "workflowOptions": {
      "requestedCodeSystems": ["ICD10"]
    }
  }'
```

**Performance Gain:** Skips RxNorm inference, ~1-2s faster

---

### 4. Cardiovascular Risk Focus

Focus risk assessment on cardiovascular health only:

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '{
    "imageBase64": "YOUR_BASE64_IMAGE_HERE",
    "patientContext": {
      "patientId": "P44444",
      "age": 68,
      "gender": "MALE",
      "medicalHistory": [
        "Coronary Artery Disease",
        "Hyperlipidemia"
      ],
      "currentMedications": [
        "Atorvastatin 40mg",
        "Aspirin 81mg"
      ]
    },
    "workflowOptions": {
      "riskAssessmentCategories": ["CARDIOVASCULAR"]
    }
  }'
```

**Output:** Detailed cardiovascular risk analysis

---

### 5. RxNorm Only (Medication Codes)

Get only medication codes, skip diagnosis codes:

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '{
    "imageBase64": "YOUR_BASE64_IMAGE_HERE",
    "patientContext": {
      "patientId": "P55555",
      "age": 42,
      "gender": "FEMALE"
    },
    "workflowOptions": {
      "requestedCodeSystems": ["RXNORM"]
    }
  }'
```

---

### 6. No Educational Content (Faster Response)

Skip educational content generation:

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '{
    "imageBase64": "YOUR_BASE64_IMAGE_HERE",
    "patientContext": {
      "patientId": "P66666",
      "age": 55,
      "gender": "MALE"
    },
    "workflowOptions": {
      "includeEducationalContent": false,
      "includeActionPlan": false
    }
  }'
```

**Performance Gain:** ~2-3s faster

---

## Testing with Real Image File

### Convert Image to Base64

```bash
# On macOS/Linux
base64 -i /path/to/blood-test-report.png | tr -d '\n' > image_base64.txt

# On Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\blood-test-report.png")) > image_base64.txt
```

### Use Base64 in Request

```bash
# Store base64 in variable
IMAGE_BASE64=$(cat image_base64.txt)

# Make request
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data "{
    \"imageBase64\": \"$IMAGE_BASE64\",
    \"patientContext\": {
      \"patientId\": \"P12345\",
      \"age\": 59,
      \"gender\": \"MALE\"
    }
  }"
```

---

## Response Examples

### Success Response (200 OK)

```json
{
  "reportId": "RPT-ABC12345",
  "patientId": "P12345",
  "processingStatus": "COMPLETED",
  "processingTimeMs": 12450,
  "timestamp": "2025-12-02T10:30:45.123Z",
  "workflow": {
    "completedStages": [
      "IMAGE_VALIDATION",
      "OCR_PROCESSING",
      "ENTITY_DETECTION",
      "ICD10_INFERENCE",
      "RXNORM_INFERENCE",
      "CLINICAL_INSIGHTS",
      "PATIENT_SUMMARY",
      "RISK_ASSESSMENT",
      "RECOMMENDATIONS",
      "EDUCATIONAL_CONTENT"
    ],
    "failedStages": [],
    "skippedStages": []
  },
  "imageValidation": {
    "isValid": true,
    "qualityScore": 0.92
  },
  "ocrResults": {
    "extractedData": [...],
    "overallConfidence": 0.96,
    "testCount": 9
  },
  "medicalCodes": {
    "icd10": [...],
    "rxnorm": [...],
    "totalCodes": 5
  },
  "clinicalInsights": {
    "summary": "...",
    "keyFindings": [...],
    "riskAssessment": {...},
    "recommendations": [...]
  }
}
```

### Partial Success Response (206 Partial Content)

```json
{
  "reportId": "RPT-XYZ78901",
  "processingStatus": "PARTIAL_SUCCESS",
  "processingTimeMs": 11200,
  "workflow": {
    "completedStages": [
      "IMAGE_VALIDATION",
      "OCR_PROCESSING",
      "ENTITY_DETECTION",
      "RXNORM_INFERENCE",
      "CLINICAL_INSIGHTS"
    ],
    "failedStages": [
      "ICD10_INFERENCE"
    ]
  },
  "errors": [{
    "stage": "ICD10_INFERENCE",
    "severity": "ERROR",
    "message": "AWS Comprehend Medical rate limit exceeded",
    "retryable": true
  }]
}
```

### Validation Error (422 Unprocessable Entity)

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Image validation failed: Image quality too low",
  "details": {
    "timestamp": "2025-12-02T10:30:45",
    "errorType": "OrchestrationException"
  }
}
```

### Processing Error (500 Internal Server Error)

```json
{
  "code": "OCR_PROCESSING_FAILED",
  "message": "No medical data could be extracted from image",
  "details": {
    "timestamp": "2025-12-02T10:30:45",
    "errorType": "OrchestrationException"
  }
}
```

---

## Advanced Usage

### Pretty Print JSON Response

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '...' | jq '.'
```

### Save Response to File

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '...' \
  --output response.json
```

### Check HTTP Status Code

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '...' \
  --write-out '%{http_code}' \
  --silent --output /dev/null
```

### Full Request with Timing

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '...' \
  --write-out '\nHTTP Status: %{http_code}\nTime: %{time_total}s\n' \
  | jq '.'
```

---

## Batch Processing Script

### Process Multiple Images

```bash
#!/bin/bash

for image in *.png; do
  echo "Processing $image..."

  # Convert to base64
  IMAGE_BASE64=$(base64 -i "$image" | tr -d '\n')

  # Make request
  curl --location 'http://localhost:8086/api/v1/medical-report/process' \
    --header 'Content-Type: application/json' \
    --silent \
    --data "{
      \"imageBase64\": \"$IMAGE_BASE64\",
      \"patientContext\": {
        \"patientId\": \"P-${image%.png}\",
        \"age\": 50,
        \"gender\": \"MALE\"
      }
    }" \
    --output "results/${image%.png}_result.json"

  echo "✅ $image processed"
  sleep 2  # Rate limiting
done
```

---

## Health Check Before Processing

### Check if Service is Running

```bash
curl --location 'http://localhost:8086/actuator/health' | jq '.'
```

**Expected Response:**
```json
{
  "status": "UP"
}
```

---

## Performance Testing

### Measure Processing Time

```bash
time curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '...' \
  --output response.json
```

### Concurrent Requests (Load Test)

```bash
# Run 10 concurrent requests
for i in {1..10}; do
  curl --location 'http://localhost:8086/api/v1/medical-report/process' \
    --header 'Content-Type: application/json' \
    --data '...' \
    --output "response_$i.json" &
done
wait
```

---

## Troubleshooting

### Enable Verbose Output

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '...' \
  --verbose
```

### Check Request Headers

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '...' \
  --include
```

### Debug Connection Issues

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --data '...' \
  --trace-ascii debug.txt
```

---

## Environment Variables

### Setup Environment

```bash
# Create .env file
cat > .env << 'EOF'
BASE_URL=http://localhost:8086
API_KEY=your-api-key-here
EOF

# Source environment
source .env

# Use in curl
curl --location "$BASE_URL/api/v1/medical-report/process" \
  --header "X-API-Key: $API_KEY" \
  --header 'Content-Type: application/json' \
  --data '...'
```

---

## Expected Response Times

| Scenario | Time Range | Notes |
|----------|------------|-------|
| Minimal Request | 10-15s | Basic processing |
| Full Request | 15-20s | All features enabled |
| Skip Validation | 9-14s | Saves ~1s |
| No Educational Content | 8-12s | Saves ~3s |
| Provider Mode | 12-18s | Similar to patient mode |
| ICD-10 Only | 9-14s | Skips RxNorm |
| RxNorm Only | 9-14s | Skips ICD-10 |

---

## Quick Reference

### Minimal Request Template

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/process \
  -H 'Content-Type: application/json' \
  -d '{
    "imageBase64": "BASE64_HERE",
    "patientContext": {
      "patientId": "P12345",
      "age": 59,
      "gender": "MALE"
    }
  }'
```

### Fast Mode Template

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/process \
  -H 'Content-Type: application/json' \
  -d '{
    "imageBase64": "BASE64_HERE",
    "patientContext": {
      "patientId": "P12345",
      "age": 59,
      "gender": "MALE"
    },
    "workflowOptions": {
      "skipValidation": true,
      "includeEducationalContent": false
    }
  }'
```

---

**API Status:** ✅ LIVE
**Documentation:** See [MASTER_ORCHESTRATION_API.md](./MASTER_ORCHESTRATION_API.md)
**Support:** See [PHASE_2_COMPLETION.md](./PHASE_2_COMPLETION.md)
