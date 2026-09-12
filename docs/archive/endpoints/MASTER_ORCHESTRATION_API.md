# MedScribe AI - Master Orchestration API

**Version:** 1.0
**Status:** 🚧 In Development
**Base URL (local):** `http://localhost:8086`
**Base URL (remote):** `http://<OLD_SERVER_IP>:8086`

---

## Overview

The **Master Orchestration API** is the single entry point for complete end-to-end medical report processing. It orchestrates the entire workflow from image upload to clinical insights generation, eliminating the need for multiple API calls.

### Key Features

✅ **Single API Call** - Upload image and receive comprehensive analysis
✅ **10-Step Automated Workflow** - Fully orchestrated pipeline
✅ **Reactive & Non-blocking** - Efficient resource utilization
✅ **Partial Failure Handling** - Resilient to service interruptions
✅ **Configurable Steps** - Enable/disable specific workflow stages
✅ **Real-time Progress** - Stream processing updates (optional)
✅ **Comprehensive Results** - OCR, classification, codes, and AI insights in one response

---

## Complete Workflow Pipeline

```
┌──────────────────────────────────────────────────────────────────┐
│                    Master Orchestration Flow                      │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  1️⃣  Image Validation        → Check quality, size, format       │
│  2️⃣  OCR Processing          → Extract text (AWS Textract)       │
│  3️⃣  Entity Detection        → Identify medical entities         │
│  4️⃣  ICD-10 Inference        → Diagnosis codes                   │
│  5️⃣  RxNorm Inference        → Medication codes                  │
│  6️⃣  Clinical Insights       → AI-powered analysis               │
│  7️⃣  Patient Summary         → Human-friendly explanation        │
│  8️⃣  Risk Assessment         → Multi-organ risk evaluation       │
│  9️⃣  Recommendations         → Evidence-based suggestions        │
│  🔟 Educational Content      → Patient education materials       │
│                                                                   │
│  ✅ Master Response           → Comprehensive JSON result         │
└──────────────────────────────────────────────────────────────────┘
```

---

## API Endpoint

### Process Complete Medical Report

**Endpoint:** `POST /api/v1/medical-report/process`

**Description:** Processes a medical report image through the complete 10-step workflow and returns comprehensive analysis including OCR results, entity classification, medical codes, clinical insights, risk assessment, recommendations, and educational content.

**Headers:**
```
Content-Type: application/json
X-API-Key: <your-api-key>
```

---

## Request Structure

### Full Request Example

```json
{
  "imageBase64": "iVBORw0KGgoAAAANSUhEUgAAAAUA...",
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
    "educationalContentTopics": ["elevated creatinine", "liver dysfunction"],
    "riskAssessmentCategories": [
      "CARDIOVASCULAR",
      "METABOLIC",
      "RENAL",
      "HEPATIC"
    ],
    "targetAudience": "PATIENT",
    "language": "en",
    "confidenceThreshold": 0.75,
    "includeActionPlan": true,
    "includeTrendAnalysis": false
  }
}
```

### Request Fields

#### Root Level

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `imageBase64` | string | **Yes** | Base64-encoded medical report image (JPG, PNG, PDF) |
| `patientContext` | object | **Yes** | Patient demographic and medical history |
| `workflowOptions` | object | No | Configuration for workflow stages (defaults applied) |

#### Patient Context Object

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `patientId` | string | **Yes** | Unique patient identifier |
| `age` | integer | **Yes** | Patient age in years |
| `gender` | string | **Yes** | `MALE`, `FEMALE`, `OTHER` |
| `medicalHistory` | array[string] | No | List of known medical conditions |
| `currentMedications` | array[string] | No | Current medications with dosage |
| `allergies` | array[string] | No | Known allergies |
| `vitalSigns` | object | No | Recent vital signs measurements |
| `lifestyle` | object | No | Lifestyle factors (smoking, alcohol, exercise) |

#### Workflow Options Object

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `skipValidation` | boolean | `false` | Skip image quality validation |
| `includeRawText` | boolean | `true` | Include raw OCR text in response |
| `includeEntityRelationships` | boolean | `true` | Extract entity relationships |
| `requestedCodeSystems` | array[string] | `["ICD10", "RXNORM"]` | Medical code systems to include |
| `includeEducationalContent` | boolean | `true` | Generate educational materials |
| `educationalContentTopics` | array[string] | `[]` | Specific topics for education (auto-detected if empty) |
| `riskAssessmentCategories` | array[string] | `["CARDIOVASCULAR", "METABOLIC", "RENAL", "HEPATIC"]` | Risk categories to assess |
| `targetAudience` | string | `PATIENT` | `PATIENT` or `PROVIDER` |
| `language` | string | `en` | Response language (`en`, `bn`) |
| `confidenceThreshold` | double | `0.70` | Minimum confidence for entity detection |
| `includeActionPlan` | boolean | `true` | Generate immediate/short/long-term action plan |
| `includeTrendAnalysis` | boolean | `false` | Analyze trends from historical data (requires historical data) |

---

## Response Structure

### Success Response (200 OK)

```json
{
  "reportId": "RPT-ABC12345",
  "patientId": "P12345",
  "processingStatus": "COMPLETED",
  "processingTimeMs": 8450,
  "timestamp": "2024-01-15T10:30:45.123Z",

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
    "qualityScore": 0.92,
    "message": "Image quality acceptable for OCR processing",
    "metrics": {
      "sizeInMB": "0.85",
      "dimensions": "2480x3508",
      "format": "PNG",
      "dpi": 300
    }
  },

  "ocrResults": {
    "extractedData": [
      {
        "testName": "Serum Creatinine",
        "testValue": "135.0",
        "unit": "µmol/L",
        "referenceRange": "Male: 59-104, Female: 45-84",
        "status": "ABNORMAL",
        "confidence": 0.98
      },
      {
        "testName": "Blood Ammonia",
        "testValue": "244.0",
        "unit": "µg/dL",
        "referenceRange": "Adult: 19-54 µg/dL",
        "status": "CRITICAL",
        "confidence": 0.97
      },
      {
        "testName": "Bilirubin",
        "testValue": "82.0",
        "unit": "µmol/L",
        "referenceRange": "Adult upto: 19.0",
        "status": "ABNORMAL",
        "confidence": 0.96
      },
      {
        "testName": "AST (SGOT)",
        "testValue": "65.0",
        "unit": "U/L",
        "referenceRange": "Male: Upto 42",
        "status": "ABNORMAL",
        "confidence": 0.95
      },
      {
        "testName": "Albumin",
        "testValue": "31.0",
        "unit": "g/L",
        "referenceRange": "35-57",
        "status": "ABNORMAL",
        "confidence": 0.94
      }
    ],
    "rawText": "SAMORITA HOSPITAL LIMITED\nBIOCHEMISTRY REPORT\nPatient Name: S. M. FARUQ AHMED\nAge: 59 Y\nGender: Male\n...",
    "overallConfidence": 0.96,
    "testCount": 9
  },

  "entityDetection": {
    "entities": [
      {
        "id": 1,
        "text": "Serum Creatinine",
        "category": "TEST_TREATMENT_PROCEDURE",
        "type": "TEST_NAME",
        "score": 0.98,
        "beginOffset": 245,
        "endOffset": 261,
        "attributes": [
          {
            "type": "TEST_VALUE",
            "text": "135.0",
            "score": 0.99,
            "relationshipScore": 0.97
          },
          {
            "type": "TEST_UNIT",
            "text": "µmol/L",
            "score": 0.98,
            "relationshipScore": 0.95
          }
        ],
        "traits": [
          {
            "name": "SIGN",
            "score": 0.89
          }
        ]
      },
      {
        "id": 2,
        "text": "elevated creatinine",
        "category": "MEDICAL_CONDITION",
        "type": "DX_NAME",
        "score": 0.86,
        "traits": [
          {
            "name": "DIAGNOSIS",
            "score": 0.75
          }
        ]
      }
    ],
    "relationships": [
      {
        "id": 1,
        "type": "TEST_VALUE",
        "score": 0.97,
        "sourceId": 1,
        "targetId": 2
      }
    ],
    "entityCount": 25,
    "modelVersion": "3.0.0"
  },

  "medicalCodes": {
    "icd10": [
      {
        "code": "N17.9",
        "description": "Acute kidney failure, unspecified",
        "score": 0.85,
        "category": "DIAGNOSIS"
      },
      {
        "code": "K76.82",
        "description": "Hepatic encephalopathy",
        "score": 0.82,
        "category": "DIAGNOSIS"
      },
      {
        "code": "R79.89",
        "description": "Other specified abnormal findings of blood chemistry",
        "score": 0.78,
        "category": "FINDING"
      }
    ],
    "rxnorm": [
      {
        "code": "6809",
        "description": "metformin",
        "score": 0.92,
        "category": "MEDICATION"
      }
    ],
    "totalCodes": 4
  },

  "clinicalInsights": {
    "summary": "Your recent blood tests show concerning results related to liver and kidney function. The elevated ammonia (244 µg/dL) and bilirubin (82 µmol/L) indicate your liver is not processing waste products effectively, while the elevated creatinine (135 µmol/L) suggests reduced kidney function. Low albumin (31 g/L) indicates decreased protein production by the liver. These findings require immediate medical attention.",

    "keyFindings": [
      {
        "id": "finding_1",
        "finding": "Critically Elevated Blood Ammonia (244.0 µg/dL)",
        "severity": "CRITICAL",
        "interpretation": "Your blood ammonia level is significantly elevated (normal range: 19-54 µg/dL). This indicates your liver may not be processing waste products effectively. High ammonia can affect brain function and requires immediate medical attention.",
        "clinicalSignificance": "This finding suggests hepatic encephalopathy risk and requires urgent evaluation by a hepatologist.",
        "normalRange": "19-54 µg/dL",
        "percentageDeviation": "+352%",
        "relatedTests": ["LFT", "PT/INR", "Hepatitis panel"],
        "critical": true
      },
      {
        "id": "finding_2",
        "finding": "Elevated Bilirubin (82.0 µmol/L)",
        "severity": "HIGH",
        "interpretation": "High bilirubin suggests liver dysfunction or bile flow issues. This can cause yellowing of skin and eyes (jaundice).",
        "clinicalSignificance": "Indicates hepatic dysfunction or cholestasis requiring investigation.",
        "normalRange": "Up to 19.0 µmol/L",
        "percentageDeviation": "+332%",
        "relatedTests": ["Direct bilirubin", "Alkaline phosphatase", "Liver ultrasound"],
        "critical": false
      },
      {
        "id": "finding_3",
        "finding": "Elevated Serum Creatinine (135.0 µmol/L)",
        "severity": "MODERATE",
        "interpretation": "Your kidney function shows mild impairment. Creatinine is a waste product that kidneys normally filter out. Elevated levels suggest your kidneys aren't working at full capacity.",
        "clinicalSignificance": "May indicate acute kidney injury (AKI) or chronic kidney disease (CKD). Given diabetes history, nephropathy screening recommended.",
        "normalRange": "Male: 59-104 µmol/L",
        "percentageDeviation": "+30%",
        "relatedTests": ["eGFR", "BUN", "Urine albumin/creatinine ratio"],
        "critical": false
      },
      {
        "id": "finding_4",
        "finding": "Low Albumin (31.0 g/L)",
        "severity": "MODERATE",
        "interpretation": "Low albumin indicates your liver may not be producing enough protein, which can affect fluid balance and overall health.",
        "clinicalSignificance": "Suggests hepatic synthetic dysfunction or malnutrition. Can lead to edema and ascites.",
        "normalRange": "35-57 g/L",
        "percentageDeviation": "-11%",
        "relatedTests": ["Total protein", "Pre-albumin", "Nutritional assessment"],
        "critical": false
      },
      {
        "id": "finding_5",
        "finding": "Elevated AST (65.0 U/L)",
        "severity": "MODERATE",
        "interpretation": "This liver enzyme is elevated, suggesting liver cell damage or inflammation.",
        "clinicalSignificance": "Hepatocellular injury. AST:ALT ratio may help determine cause.",
        "normalRange": "Male: Up to 42 U/L",
        "percentageDeviation": "+55%",
        "relatedTests": ["ALT", "GGT", "Viral hepatitis markers"],
        "critical": false
      }
    ],

    "riskAssessment": {
      "overallRiskLevel": "HIGH",
      "categoryRisks": {
        "hepatic": {
          "level": "CRITICAL",
          "score": 0.92,
          "description": "Severely elevated ammonia and bilirubin with low albumin indicate significant liver dysfunction. Hepatic encephalopathy risk is high.",
          "contributors": [
            "Blood ammonia 352% above normal",
            "Bilirubin 332% above normal",
            "Low albumin (synthetic dysfunction)",
            "Elevated liver enzymes"
          ]
        },
        "renal": {
          "level": "MODERATE",
          "score": 0.65,
          "description": "Elevated creatinine indicates reduced kidney function, possibly acute kidney injury or early chronic kidney disease.",
          "contributors": [
            "Creatinine 30% above normal",
            "Diabetes Type 2 history",
            "Hypertension (additional risk factor)"
          ]
        },
        "metabolic": {
          "level": "MODERATE",
          "score": 0.60,
          "description": "Existing diabetes with organ dysfunction increases metabolic risk.",
          "contributors": [
            "Diabetes Type 2",
            "Multi-organ involvement",
            "Age 59 years"
          ]
        },
        "cardiovascular": {
          "level": "MODERATE",
          "score": 0.55,
          "description": "Hypertension and metabolic factors contribute to cardiovascular risk.",
          "contributors": [
            "Hypertension",
            "Age 59 years",
            "Male gender"
          ]
        }
      },
      "riskFactors": [
        "Severely elevated blood ammonia",
        "Significant liver dysfunction",
        "Reduced kidney function",
        "Diabetes Type 2",
        "Hypertension",
        "Male, age 59"
      ],
      "protectiveFactors": [
        "Currently on appropriate diabetes medication",
        "Currently on appropriate blood pressure medication",
        "No smoking history"
      ],
      "overallAssessment": "Patient presents with high-risk findings indicating significant hepatic and renal dysfunction. The critically elevated ammonia level poses immediate risk of hepatic encephalopathy. Urgent medical evaluation and intervention required.",
      "requiresImmediateAttention": true
    },

    "recommendations": [
      {
        "id": "rec_1",
        "category": "IMMEDIATE_ACTION",
        "priority": "URGENT",
        "recommendation": "Go to the nearest emergency department immediately or call emergency services",
        "rationale": "Critically elevated ammonia levels (244 µg/dL) pose risk of hepatic encephalopathy (confusion, altered consciousness). Immediate medical evaluation is essential.",
        "evidenceLevel": "HIGH",
        "timeframe": "Immediate (within 1 hour)",
        "prerequisites": []
      },
      {
        "id": "rec_2",
        "category": "IMMEDIATE_ACTION",
        "priority": "URGENT",
        "recommendation": "Bring all current medications and recent medical records to the emergency department",
        "rationale": "Complete medication history is crucial for proper evaluation and treatment planning.",
        "evidenceLevel": "HIGH",
        "timeframe": "Immediate",
        "prerequisites": []
      },
      {
        "id": "rec_3",
        "category": "DIAGNOSTIC_TESTS",
        "priority": "HIGH",
        "recommendation": "Complete liver function panel, hepatitis screening, coagulation studies (PT/INR), and liver ultrasound or CT scan",
        "rationale": "These tests will help determine the underlying cause and extent of liver dysfunction, guide treatment decisions.",
        "evidenceLevel": "HIGH",
        "timeframe": "Within 24 hours",
        "prerequisites": ["Emergency department evaluation"]
      },
      {
        "id": "rec_4",
        "category": "DIAGNOSTIC_TESTS",
        "priority": "HIGH",
        "recommendation": "Complete kidney function assessment including eGFR, BUN, and urine analysis",
        "rationale": "Comprehensive kidney evaluation needed given elevated creatinine and diabetes history.",
        "evidenceLevel": "HIGH",
        "timeframe": "Within 24 hours",
        "prerequisites": ["Emergency department evaluation"]
      },
      {
        "id": "rec_5",
        "category": "MEDICATION",
        "priority": "HIGH",
        "recommendation": "Expect treatment with lactulose or rifaximin to reduce blood ammonia levels",
        "rationale": "Standard therapy for elevated ammonia to prevent hepatic encephalopathy. Lactulose reduces ammonia production and absorption; rifaximin is a non-absorbable antibiotic that reduces ammonia-producing bacteria.",
        "evidenceLevel": "HIGH",
        "timeframe": "Within 24 hours",
        "prerequisites": ["Physician evaluation"]
      },
      {
        "id": "rec_6",
        "category": "SPECIALIST_REFERRAL",
        "priority": "HIGH",
        "recommendation": "Urgent consultation with hepatologist (liver specialist)",
        "rationale": "Specialized care needed for significant liver dysfunction to determine cause and appropriate management.",
        "evidenceLevel": "HIGH",
        "timeframe": "Within 48 hours",
        "prerequisites": ["Emergency stabilization"]
      },
      {
        "id": "rec_7",
        "category": "SPECIALIST_REFERRAL",
        "priority": "MEDIUM",
        "recommendation": "Follow-up with nephrologist (kidney specialist) for kidney function evaluation",
        "rationale": "Elevated creatinine in diabetic patient warrants nephrology evaluation for diabetic nephropathy screening.",
        "evidenceLevel": "MODERATE",
        "timeframe": "Within 1-2 weeks",
        "prerequisites": ["Emergency stabilization"]
      },
      {
        "id": "rec_8",
        "category": "MONITORING",
        "priority": "HIGH",
        "recommendation": "Close monitoring with repeat blood tests including ammonia, liver function, and kidney function every 24-72 hours initially",
        "rationale": "Tracking trends in organ function is crucial for treatment adjustment and prognosis.",
        "evidenceLevel": "HIGH",
        "timeframe": "Ongoing",
        "prerequisites": ["Physician oversight"]
      },
      {
        "id": "rec_9",
        "category": "LIFESTYLE",
        "priority": "HIGH",
        "recommendation": "Reduce dietary protein intake temporarily (40-60g/day)",
        "rationale": "Lower protein diet can help reduce ammonia production. Should be done under medical supervision to avoid malnutrition.",
        "evidenceLevel": "MODERATE",
        "timeframe": "Start immediately after physician consultation",
        "prerequisites": ["Dietitian consultation", "Physician approval"]
      },
      {
        "id": "rec_10",
        "category": "LIFESTYLE",
        "priority": "URGENT",
        "recommendation": "Avoid alcohol completely",
        "rationale": "Alcohol can worsen liver damage and is contraindicated in liver dysfunction.",
        "evidenceLevel": "HIGH",
        "timeframe": "Immediate and ongoing",
        "prerequisites": []
      },
      {
        "id": "rec_11",
        "category": "LIFESTYLE",
        "priority": "HIGH",
        "recommendation": "Ensure adequate hydration with 2-3 liters of water daily (unless contraindicated)",
        "rationale": "Proper hydration supports kidney function and helps prevent further deterioration.",
        "evidenceLevel": "MODERATE",
        "timeframe": "Ongoing",
        "prerequisites": ["Physician approval (check for fluid restrictions)"]
      },
      {
        "id": "rec_12",
        "category": "MEDICATION_REVIEW",
        "priority": "HIGH",
        "recommendation": "Review all current medications with physician for potential hepatotoxic or nephrotoxic drugs",
        "rationale": "Some medications can worsen liver or kidney function and may need to be adjusted or discontinued.",
        "evidenceLevel": "HIGH",
        "timeframe": "Within 24 hours",
        "prerequisites": ["Physician evaluation"]
      }
    ],

    "actionPlan": {
      "immediateActions": [
        {
          "action": "Go to nearest emergency department",
          "priority": "URGENT",
          "timeframe": "Immediate (within 1 hour)",
          "category": "MEDICAL_CARE",
          "status": "PENDING"
        },
        {
          "action": "Bring all medications and medical records",
          "priority": "URGENT",
          "timeframe": "Immediate",
          "category": "PREPARATION",
          "status": "PENDING"
        },
        {
          "action": "Stop alcohol consumption completely",
          "priority": "URGENT",
          "timeframe": "Immediate",
          "category": "LIFESTYLE",
          "status": "PENDING"
        }
      ],
      "shortTermActions": [
        {
          "action": "Complete comprehensive liver function tests and imaging",
          "priority": "HIGH",
          "timeframe": "Within 24 hours",
          "category": "DIAGNOSTIC",
          "status": "PENDING"
        },
        {
          "action": "Complete kidney function assessment",
          "priority": "HIGH",
          "timeframe": "Within 24 hours",
          "category": "DIAGNOSTIC",
          "status": "PENDING"
        },
        {
          "action": "Consultation with hepatologist",
          "priority": "HIGH",
          "timeframe": "Within 48 hours",
          "category": "SPECIALIST",
          "status": "PENDING"
        },
        {
          "action": "Start ammonia-lowering therapy (lactulose/rifaximin)",
          "priority": "HIGH",
          "timeframe": "Within 24 hours",
          "category": "TREATMENT",
          "status": "PENDING"
        },
        {
          "action": "Review and adjust current medications",
          "priority": "HIGH",
          "timeframe": "Within 24-48 hours",
          "category": "MEDICATION",
          "status": "PENDING"
        }
      ],
      "longTermActions": [
        {
          "action": "Regular monitoring of liver and kidney function (weekly initially, then monthly)",
          "priority": "HIGH",
          "timeframe": "Ongoing",
          "category": "MONITORING",
          "status": "PENDING"
        },
        {
          "action": "Follow-up with nephrologist for diabetic nephropathy screening",
          "priority": "MEDIUM",
          "timeframe": "Within 1-2 weeks",
          "category": "SPECIALIST",
          "status": "PENDING"
        },
        {
          "action": "Dietary modifications under dietitian guidance (protein restriction, balanced nutrition)",
          "priority": "MEDIUM",
          "timeframe": "Ongoing",
          "category": "LIFESTYLE",
          "status": "PENDING"
        },
        {
          "action": "Optimize diabetes control with endocrinologist",
          "priority": "MEDIUM",
          "timeframe": "Within 2-4 weeks",
          "category": "CHRONIC_DISEASE_MANAGEMENT",
          "status": "PENDING"
        },
        {
          "action": "Continue hypertension management",
          "priority": "MEDIUM",
          "timeframe": "Ongoing",
          "category": "CHRONIC_DISEASE_MANAGEMENT",
          "status": "PENDING"
        }
      ],
      "totalActions": 15
    },

    "educationalContent": [
      {
        "topic": "Understanding Elevated Ammonia Levels",
        "content": "Ammonia is a waste product that comes from the breakdown of proteins in your body. A healthy liver converts ammonia into urea, which is then removed by your kidneys through urine. When your liver isn't working properly, ammonia can build up in your blood.\n\nHigh ammonia levels can affect your brain function, leading to a condition called hepatic encephalopathy. Symptoms may include confusion, drowsiness, difficulty concentrating, tremors, or changes in behavior. This is why your elevated ammonia level (244 µg/dL, compared to normal 19-54 µg/dL) requires immediate medical attention.",
        "keyPoints": [
          "Ammonia is a waste product from protein breakdown",
          "The liver normally converts ammonia to urea for elimination",
          "High levels can cause confusion and altered mental state",
          "Immediate treatment can lower ammonia levels and prevent complications",
          "Treatment typically includes medications like lactulose or rifaximin"
        ],
        "resources": [
          "American Liver Foundation: www.liverfoundation.org",
          "National Institute of Diabetes and Digestive and Kidney Diseases: www.niddk.nih.gov"
        ]
      },
      {
        "topic": "Understanding Elevated Creatinine and Kidney Function",
        "content": "Creatinine is a waste product from your normal muscle use. Your kidneys filter creatinine from your blood and remove it through urine. When creatinine levels are high (135 µmol/L vs normal 59-104 µmol/L for men), it usually means your kidneys aren't filtering as effectively as they should.\n\nIn people with diabetes, high blood sugar can damage the small blood vessels in the kidneys over time, leading to diabetic nephropathy (kidney disease). Early detection and treatment can slow or prevent further kidney damage.",
        "keyPoints": [
          "Creatinine is a waste product filtered by healthy kidneys",
          "High levels indicate reduced kidney function",
          "Diabetes is a leading cause of chronic kidney disease",
          "Early intervention can protect remaining kidney function",
          "Regular monitoring is essential for diabetes patients"
        ],
        "resources": [
          "National Kidney Foundation: www.kidney.org",
          "American Diabetes Association: www.diabetes.org"
        ]
      },
      {
        "topic": "Liver Dysfunction: Causes, Symptoms, and Management",
        "content": "Your liver performs over 500 vital functions, including filtering toxins, producing proteins, and aiding digestion. When the liver isn't functioning properly, it can lead to various problems:\n\n**Bilirubin**: Normally processed by the liver. High levels (82 µmol/L vs normal up to 19 µmol/L) cause jaundice (yellowing of skin and eyes).\n\n**Albumin**: A protein made by the liver. Low levels (31 g/L vs normal 35-57 g/L) can cause swelling in legs and abdomen.\n\n**Liver Enzymes (AST)**: Released when liver cells are damaged. Elevated levels indicate liver inflammation or injury.\n\nCommon causes of liver dysfunction include viral hepatitis, alcohol use, fatty liver disease, certain medications, and autoimmune conditions. Your doctor will run tests to determine the specific cause in your case.",
        "keyPoints": [
          "The liver performs over 500 essential functions",
          "Multiple test abnormalities suggest significant liver dysfunction",
          "Many causes of liver problems are treatable if caught early",
          "Avoid alcohol and certain medications that can harm the liver",
          "Follow-up with a liver specialist (hepatologist) is important"
        ],
        "resources": [
          "American Liver Foundation: www.liverfoundation.org",
          "MedlinePlus Liver Diseases: medlineplus.gov/liverdiseases.html"
        ]
      }
    ]
  },

  "metadata": {
    "apiVersion": "1.0",
    "processingDate": "2024-01-15T10:30:45.123Z",
    "modelVersions": {
      "textract": "1.0",
      "comprehendMedical": "3.0.0",
      "bedrock": "claude-3-5-sonnet-20241022-v2:0"
    },
    "costs": {
      "textract": "$0.015",
      "comprehendMedical": "$0.012",
      "bedrock": "$0.045",
      "total": "$0.072"
    }
  },

  "errors": [],
  "warnings": [
    {
      "stage": "OCR_PROCESSING",
      "severity": "INFO",
      "message": "Some text had low confidence (<0.80). Manual review recommended for critical values."
    }
  ]
}
```

### Partial Success Response (206 Partial Content)

When some workflow stages fail but others succeed:

```json
{
  "reportId": "RPT-ABC12345",
  "processingStatus": "PARTIAL_SUCCESS",
  "processingTimeMs": 7200,

  "workflow": {
    "completedStages": [
      "IMAGE_VALIDATION",
      "OCR_PROCESSING",
      "ENTITY_DETECTION",
      "ICD10_INFERENCE",
      "CLINICAL_INSIGHTS"
    ],
    "failedStages": [
      "RXNORM_INFERENCE"
    ],
    "skippedStages": []
  },

  "ocrResults": { /* ... OCR data ... */ },
  "entityDetection": { /* ... Entity data ... */ },
  "medicalCodes": {
    "icd10": [ /* ... ICD-10 codes ... */ ],
    "rxnorm": []  // Empty due to failure
  },
  "clinicalInsights": { /* ... Clinical insights data ... */ },

  "errors": [
    {
      "stage": "RXNORM_INFERENCE",
      "severity": "ERROR",
      "message": "AWS Comprehend Medical InferRxNorm API throttling limit reached",
      "code": "RATE_LIMIT_EXCEEDED",
      "timestamp": "2024-01-15T10:30:42.345Z",
      "retryable": true
    }
  ],
  "warnings": []
}
```

### Error Response (400 Bad Request)

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "Image validation failed",
    "details": {
      "stage": "IMAGE_VALIDATION",
      "reason": "Image size exceeds maximum allowed (10MB)",
      "imageSize": "12.5MB",
      "maxAllowedSize": "10MB"
    },
    "timestamp": "2024-01-15T10:30:45.123Z"
  }
}
```

### Error Response (500 Internal Server Error)

```json
{
  "error": {
    "code": "PROCESSING_FAILED",
    "message": "Critical workflow failure during OCR processing",
    "details": {
      "stage": "OCR_PROCESSING",
      "reason": "AWS Textract service unavailable",
      "awsErrorCode": "ServiceUnavailable"
    },
    "timestamp": "2024-01-15T10:30:45.123Z",
    "retryAfter": 30
  }
}
```

---

## cURL Example

### Basic Request

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: your-api-key' \
  --data '{
    "imageBase64": "iVBORw0KGgoAAAANSUhEUgAAAAUA...",
    "patientContext": {
      "patientId": "P12345",
      "age": 59,
      "gender": "MALE",
      "medicalHistory": ["Diabetes Type 2", "Hypertension"],
      "currentMedications": ["Metformin 1000mg", "Lisinopril 10mg"]
    },
    "workflowOptions": {
      "targetAudience": "PATIENT",
      "includeEducationalContent": true
    }
  }'
```

### Minimal Request (All Defaults)

```bash
curl --location 'http://localhost:8086/api/v1/medical-report/process' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: your-api-key' \
  --data '{
    "imageBase64": "iVBORw0KGgoAAAANSUhEUgAAAAUA...",
    "patientContext": {
      "patientId": "P12345",
      "age": 59,
      "gender": "MALE"
    }
  }'
```

---

## Status Codes

| Code | Status | Description |
|------|--------|-------------|
| 200 | OK | Request completed successfully with all workflow stages completed |
| 206 | Partial Content | Request partially successful; some stages failed but core results available |
| 400 | Bad Request | Invalid request (missing fields, invalid image, etc.) |
| 401 | Unauthorized | Missing or invalid API key |
| 413 | Payload Too Large | Image size exceeds maximum allowed (10MB) |
| 422 | Unprocessable Entity | Image validation failed (corrupted, wrong format, poor quality) |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | Critical processing failure (OCR or classification failure) |
| 503 | Service Unavailable | AWS service temporarily unavailable |

---

## Processing Time Estimates

| Workflow Stage | Typical Duration |
|----------------|------------------|
| Image Validation | 100-300ms |
| OCR Processing | 2-5 seconds |
| Entity Detection | 1-3 seconds |
| ICD-10 Inference | 1-2 seconds |
| RxNorm Inference | 1-2 seconds |
| Clinical Insights | 3-8 seconds |
| Patient Summary | 2-4 seconds |
| Risk Assessment | 2-4 seconds |
| Recommendations | 2-4 seconds |
| Educational Content | 2-5 seconds |
| **Total (Sequential)** | **16-37 seconds** |
| **Total (Optimized/Parallel)** | **8-15 seconds** |

*Note: Times vary based on image complexity, text length, and AWS service load. Parallel processing significantly reduces total time.*

---

## Cost Breakdown

Based on AWS pricing (us-east-1 region):

| Service | Operation | Cost per Request |
|---------|-----------|------------------|
| AWS Textract | Analyze Document (Tables) | $0.015 |
| AWS Comprehend Medical | Detect Entities V2 | $0.004 |
| AWS Comprehend Medical | Infer ICD-10-CM | $0.004 |
| AWS Comprehend Medical | Infer RxNorm | $0.004 |
| AWS Bedrock | Claude 3.5 Sonnet (avg 5 calls) | $0.045 |
| **Total per Report** | | **~$0.072** |

*Costs shown are estimates. Actual costs vary based on document complexity and text length. BedrockHealthService caching can reduce costs by up to 50% for repeated analyses.*

---

## Rate Limits

| Service | Rate Limit | Burst Limit |
|---------|------------|-------------|
| Textract | 10 TPS | 20 TPS |
| Comprehend Medical | 20 TPS | 40 TPS |
| Bedrock | 200 RPM | - |
| **Master API** | 5 requests/sec/user | 10 requests/sec/user |

*Throttling errors will result in automatic retry with exponential backoff (up to 3 retries).*

---

## Security Considerations

1. **API Key Authentication**: Required in `X-API-Key` header
2. **HTTPS Only**: Production endpoints must use HTTPS
3. **PHI Protection**: All patient data is encrypted in transit and at rest
4. **Data Retention**: Images and reports deleted after 30 days (configurable)
5. **Audit Logging**: All requests logged for compliance (HIPAA-compliant)
6. **Rate Limiting**: Per-user rate limits to prevent abuse

---

## Best Practices

### 1. Image Quality
- **Resolution**: Minimum 150 DPI, recommended 300 DPI
- **Format**: PNG or JPEG preferred; PDF supported (first page only)
- **Size**: Under 10MB; recommended under 5MB
- **Clarity**: Ensure text is legible and not blurred

### 2. Patient Context
- **Required Fields**: Always provide `patientId`, `age`, `gender`
- **Medical History**: Include relevant chronic conditions
- **Medications**: List current medications with dosages for accurate recommendations
- **Allergies**: Include all known allergies for safety

### 3. Error Handling
- **Retry on 503**: Service unavailable errors are temporary; retry after delay
- **Handle Partial Success**: Use 206 responses; extract available data
- **Validate Before Upload**: Pre-validate image size and format client-side

### 4. Performance Optimization
- **Compress Images**: Compress images before base64 encoding
- **Reuse Connections**: Use HTTP/2 connection pooling
- **Async Processing**: For batch processing, use async/await patterns

---

## Troubleshooting

### Common Issues

#### Issue: "Image validation failed"
**Solution**: Check image size (<10MB), format (PNG/JPG/PDF), and quality (>150 DPI)

#### Issue: "OCR processing failed - low confidence"
**Solution**: Improve image quality; ensure text is clear and well-lit

#### Issue: "AWS service throttling"
**Solution**: Implement exponential backoff; consider rate limit increase request

#### Issue: "Partial success with missing medical codes"
**Solution**: Expected behavior when AWS Comprehend Medical cannot infer codes; use entity detection results instead

---

## Changelog

### Version 1.0 (2024-01-15)
- Initial release
- 10-step orchestrated workflow
- Support for ICD-10 and RxNorm medical codes
- Clinical insights powered by AWS Bedrock Claude 3.5 Sonnet
- Comprehensive error handling with partial success support

---

## Next Steps

After implementing this API, see:
- [Implementation Plan](./MASTER_ORCHESTRATION_IMPLEMENTATION_PLAN.md) - Detailed phase-by-phase development plan
- [Testing Guide](./MASTER_ORCHESTRATION_TESTING.md) - Comprehensive testing strategy
- [AWS REST API Reference](./AWS_REST_API.md) - Individual AWS service endpoints

---

## Support

For questions or issues:
- **Documentation**: [GitHub Wiki](https://github.com/your-repo/wiki)
- **Issues**: [GitHub Issues](https://github.com/your-repo/issues)
- **Email**: support@medscribe-ai.com
