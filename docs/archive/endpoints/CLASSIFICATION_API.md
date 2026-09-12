# Classification API - Clinical Data Classification and Medical Coding

## Overview

The Classification API uses AI-powered medical natural language processing to understand and classify clinical terms from OCR-extracted text. It identifies medical entities (medications, conditions, lab results, symptoms, etc.) and maps them to standard medical coding systems (ICD-10, LOINC, SNOMED CT, RxNorm, CPT).

**Base URL:** `http://localhost:8086/api/v1/medical-report`

**Authentication:** JWT Bearer Token (future implementation)

**Content-Type:** `application/json`

---

## Endpoint Details

### Classify Medical Entities

**Endpoint:** `POST /api/v1/medical-report/classify`

**Description:** Analyzes extracted medical text to identify and classify clinical entities with the comprehension level of a highly trained physician, outputting structured JSON with medical codes.

**Use Cases:**
1. Convert OCR text into structured clinical data for EHR integration
2. Automatically code diagnoses and procedures for billing
3. Extract medication information for prescription management
4. Identify lab test results for clinical decision support
5. Build searchable medical knowledge graphs from unstructured documents

---

## Request Specification

### Request Headers

| Header | Type | Required | Description |
|--------|------|----------|-------------|
| Content-Type | String | Yes | Must be `application/json` |
| Authorization | String | No (Future) | `Bearer {jwt_token}` |
| X-Request-ID | String | No | Unique request identifier for tracing |
| X-Clinical-Context | String | No | Additional clinical context for better classification |

### Request Body

```json
{
  "reportId": "string",
  "extractedData": [
    {
      "testName": "string",
      "testValue": "string",
      "unit": "string",
      "referenceRange": "string"
    }
  ],
  "rawText": "string",
  "classificationOptions": {
    "includeCodeSystems": ["string"],
    "confidenceThreshold": number,
    "maxEntitiesPerType": number,
    "enableRelationshipExtraction": boolean
  },
  "clinicalContext": {
    "patientAge": number,
    "patientGender": "string",
    "medicalHistory": ["string"]
  }
}
```

### Request Parameters

#### Root Level Parameters

| Parameter | Type | Required | Constraints | Description |
|-----------|------|----------|-------------|-------------|
| reportId | String | **Yes** | - Pattern: `^RPT-[A-Z0-9]{8}$`<br>- Must be valid report ID from OCR step | Report identifier from OCR processing |
| extractedData | Array | **Yes** | - Min items: 1<br>- Max items: 500 | Array of test results from OCR |
| rawText | String | No | - Max length: 50,000 chars | Complete OCR extracted text for context |
| classificationOptions | Object | No | - | Configuration for classification process |
| clinicalContext | Object | No | - | Patient context to improve classification accuracy |

#### Extracted Data Item

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| testName | String | **Yes** | Name of medical test or observation |
| testValue | String | No | Measured or observed value |
| unit | String | No | Unit of measurement |
| referenceRange | String | No | Normal value range |
| additionalContext | String | No | Any additional context |

#### Classification Options

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| includeCodeSystems | Array[String] | No | `["ICD10", "LOINC", "SNOMED", "RXNORM"]` | Which coding systems to include |
| confidenceThreshold | Double | No | `0.70` | Minimum confidence for entity recognition (0.0-1.0) |
| maxEntitiesPerType | Integer | No | `100` | Maximum entities to extract per type |
| enableRelationshipExtraction | Boolean | No | `true` | Extract relationships between entities (e.g., medication→condition) |
| detectNegation | Boolean | No | `true` | Detect negated findings ("no fever", "denies chest pain") |
| temporalContext | Boolean | No | `true` | Extract temporal information ("since last week", "chronic") |

#### Code Systems (Enum)

| Value | Description |
|-------|-------------|
| `ICD10` | International Classification of Diseases, 10th Revision |
| `ICD11` | International Classification of Diseases, 11th Revision |
| `LOINC` | Logical Observation Identifiers Names and Codes (lab/clinical observations) |
| `SNOMED` | SNOMED CT (Systematized Nomenclature of Medicine -- Clinical Terms) |
| `RXNORM` | RxNorm (normalized medication names) |
| `CPT` | Current Procedural Terminology (procedures and services) |
| `NDC` | National Drug Code (medications) |

#### Clinical Context

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| patientAge | Integer | No | Patient's age in years |
| patientGender | String | No | `MALE`, `FEMALE`, `OTHER` |
| medicalHistory | Array[String] | No | Known conditions/diagnoses |
| currentMedications | Array[String] | No | Current medication list |
| allergies | Array[String] | No | Known allergies |

### Request Example

#### Minimal Request
```json
{
  "reportId": "RPT-8F3A2B91",
  "extractedData": [
    {
      "testName": "Serum Creatinine",
      "testValue": "135.0",
      "unit": "µmol/L",
      "referenceRange": "Male: 59-104"
    }
  ]
}
```

#### Full Request
```json
{
  "reportId": "RPT-8F3A2B91",
  "extractedData": [
    {
      "testName": "Serum Creatinine",
      "testValue": "135.0",
      "unit": "µmol/L",
      "referenceRange": "Male: 59-104, Female: 45-84"
    },
    {
      "testName": "Blood Ammonia",
      "testValue": "244.0",
      "unit": "µg/dL",
      "referenceRange": "Adult: 19-54 µg/dL"
    },
    {
      "testName": "AST (SGOT)",
      "testValue": "65.0",
      "unit": "U/L",
      "referenceRange": "Male: Upto 42"
    }
  ],
  "rawText": "BIOCHEMISTRY PANEL: Serum Creatinine 135.0 µmol/L (Ref: 59-104)...",
  "classificationOptions": {
    "includeCodeSystems": ["ICD10", "LOINC", "SNOMED"],
    "confidenceThreshold": 0.75,
    "enableRelationshipExtraction": true,
    "detectNegation": true
  },
  "clinicalContext": {
    "patientAge": 58,
    "patientGender": "MALE",
    "medicalHistory": ["Diabetes Type 2", "Hypertension"],
    "currentMedications": ["Metformin 500mg", "Lisinopril 10mg"]
  }
}
```

---

## Response Specification

### Success Response (200 OK)

```json
{
  "reportId": "string",
  "classificationResult": {
    "Entities": [
      {
        "Id": number,
        "Text": "string",
        "Category": "string",
        "Type": "string",
        "Score": number,
        "BeginOffset": number,
        "EndOffset": number,
        "Attributes": [
          {
            "Type": "string",
            "Score": number,
            "RelationshipScore": number,
            "RelationshipType": "string",
            "Id": number,
            "Text": "string",
            "Category": "string",
            "Traits": [
              {
                "Name": "string",
                "Score": number
              }
            ]
          }
        ],
        "Traits": [
          {
            "Name": "string",
            "Score": number
          }
        ]
      }
    ],
    "UnmappedAttributes": [
      {
        "Type": "string",
        "Attribute": {
          "Type": "string",
          "Score": number,
          "Id": number,
          "Text": "string"
        }
      }
    ]
  },
  "medicalCodes": {
    "ICD10": [
      {
        "code": "string",
        "description": "string",
        "category": "string",
        "entityId": number
      }
    ],
    "LOINC": [
      {
        "code": "string",
        "description": "string",
        "component": "string",
        "entityId": number
      }
    ],
    "SNOMED": [
      {
        "code": "string",
        "description": "string",
        "semanticType": "string",
        "entityId": number
      }
    ],
    "RXNORM": [
      {
        "code": "string",
        "description": "string",
        "drugForm": "string",
        "entityId": number
      }
    ]
  },
  "entitySummary": {
    "totalEntities": number,
    "entitiesByType": {
      "MEDICATION": number,
      "CONDITION": number,
      "LAB_RESULT": number,
      "VITAL_SIGN": number,
      "PROCEDURE": number,
      "SYMPTOM": number,
      "DIAGNOSIS": number
    },
    "averageConfidence": number,
    "highConfidenceCount": number,
    "lowConfidenceCount": number
  },
  "relationships": [
    {
      "sourceEntityId": number,
      "targetEntityId": number,
      "relationshipType": "string",
      "confidence": number
    }
  ],
  "processedAt": "string",
  "processingDuration": number,
  "metadata": {
    "classificationEngine": "string",
    "modelVersion": "string",
    "ontologyVersion": "string"
  }
}
```

### Response Fields

#### Top Level

| Field | Type | Description |
|-------|------|-------------|
| reportId | String | Report identifier from request |
| classificationResult | Object | AWS Comprehend Medical format results |
| medicalCodes | Object | Mapped standard medical codes |
| entitySummary | Object | Statistical summary of entities |
| relationships | Array | Relationships between entities |
| processedAt | String (ISO 8601) | Classification completion timestamp |
| processingDuration | Integer | Processing time in milliseconds |
| metadata | Object | Classification metadata |

#### Entity Object

| Field | Type | Description |
|-------|------|-------------|
| Id | Integer | Unique entity identifier within this report |
| Text | String | The actual text of the entity as found in document |
| Category | String | High-level category (see [Entity Categories](#entity-categories)) |
| Type | String | Specific entity type (see [Entity Types](#entity-types)) |
| Score | Double | Confidence score for entity identification (0.0-1.0) |
| BeginOffset | Integer | Character offset where entity begins in source text |
| EndOffset | Integer | Character offset where entity ends in source text |
| Attributes | Array | Related attributes (values, dosages, frequencies) |
| Traits | Array | Characteristics (NEGATION, SIGN, SYMPTOM) |

### Entity Categories

| Category | Description | Examples |
|----------|-------------|----------|
| `MEDICAL_CONDITION` | Diseases, disorders, symptoms | "Diabetes", "Hypertension", "Fever" |
| `MEDICATION` | Drugs and treatments | "Metformin", "Aspirin" |
| `TEST_TREATMENT_PROCEDURE` | Tests, procedures, treatments | "Serum Creatinine", "X-ray", "Surgery" |
| `ANATOMY` | Body parts and systems | "Liver", "Heart", "Left ventricle" |
| `PROTECTED_HEALTH_INFORMATION` | PHI elements | Patient names, dates, IDs |

### Entity Types

| Type | Description | Category |
|------|-------------|----------|
| `TEST_NAME` | Name of lab or diagnostic test | TEST_TREATMENT_PROCEDURE |
| `TEST_VALUE` | Result value of test | TEST_TREATMENT_PROCEDURE |
| `TEST_UNIT` | Unit of measurement | TEST_TREATMENT_PROCEDURE |
| `GENERIC_NAME` | Generic drug name | MEDICATION |
| `BRAND_NAME` | Brand name of medication | MEDICATION |
| `DOSAGE` | Medication dosage | MEDICATION |
| `ROUTE_OR_MODE` | How medication is administered | MEDICATION |
| `FORM` | Medication form (tablet, injection) | MEDICATION |
| `FREQUENCY` | How often medication is taken | MEDICATION |
| `DX_NAME` | Diagnosis or condition name | MEDICAL_CONDITION |
| `SYMPTOM` | Clinical symptom | MEDICAL_CONDITION |
| `SIGN` | Clinical sign | MEDICAL_CONDITION |
| `PROCEDURE_NAME` | Name of procedure/treatment | TEST_TREATMENT_PROCEDURE |
| `TREATMENT_NAME` | Name of treatment | TEST_TREATMENT_PROCEDURE |
| `SYSTEM_ORGAN_SITE` | Body system or organ | ANATOMY |

#### Attribute Object

| Field | Type | Description |
|-------|------|-------------|
| Type | String | Attribute type (TEST_VALUE, DOSAGE, etc.) |
| Score | Double | Confidence for this attribute |
| RelationshipScore | Double | Confidence in relationship to parent entity |
| RelationshipType | String | How attribute relates to entity |
| Id | Integer | Unique attribute identifier |
| Text | String | Attribute text value |
| Category | String | Attribute category |
| Traits | Array | Attribute characteristics |

#### Trait Object

| Field | Type | Description |
|-------|------|-------------|
| Name | String | Trait name (NEGATION, SIGN, SYMPTOM, DIAGNOSIS, HYPOTHETICAL, LOW_CONFIDENCE) |
| Score | Double | Confidence for trait detection |

### Medical Codes Objects

#### ICD-10 Code
```json
{
  "code": "E11.9",
  "description": "Type 2 diabetes mellitus without complications",
  "category": "Endocrine, nutritional and metabolic diseases",
  "entityId": 5,
  "codeSystem": "ICD-10-CM",
  "version": "2024"
}
```

#### LOINC Code
```json
{
  "code": "2160-0",
  "description": "Creatinine [Mass/volume] in Serum or Plasma",
  "component": "Creatinine",
  "property": "MCnc",
  "timing": "Pt",
  "system": "Ser/Plas",
  "scale": "Qn",
  "entityId": 1
}
```

#### SNOMED CT Code
```json
{
  "code": "313822004",
  "description": "Creatinine level - finding",
  "semanticType": "Clinical Finding",
  "entityId": 1,
  "fullySpecifiedName": "Creatinine level (observable entity)"
}
```

#### RxNorm Code
```json
{
  "code": "6809",
  "description": "Metformin",
  "drugForm": "Oral Tablet",
  "strength": "500 MG",
  "tty": "SCD",
  "entityId": 3
}
```

### Relationship Object

| Field | Type | Description |
|-------|------|-------------|
| sourceEntityId | Integer | ID of source entity |
| targetEntityId | Integer | ID of target entity |
| relationshipType | String | Type of relationship (see [Relationship Types](#relationship-types)) |
| confidence | Double | Confidence in relationship |

#### Relationship Types

| Type | Description | Example |
|------|-------------|---------|
| `MEDICATION_FOR_CONDITION` | Medication treats condition | "Metformin" → "Diabetes" |
| `TEST_FOR_CONDITION` | Test diagnoses/monitors condition | "HbA1c" → "Diabetes" |
| `DOSAGE_FOR_MEDICATION` | Dosage amount for medication | "500mg" → "Metformin" |
| `ROUTE_FOR_MEDICATION` | Administration route for medication | "Oral" → "Metformin" |
| `FREQUENCY_FOR_MEDICATION` | Frequency for medication | "Twice daily" → "Metformin" |
| `SYMPTOM_OF_CONDITION` | Symptom is part of condition | "Polyuria" → "Diabetes" |
| `PROCEDURE_FOR_CONDITION` | Procedure treats condition | "Insulin therapy" → "Diabetes" |

### Success Response Example

```json
{
  "reportId": "RPT-8F3A2B91",
  "classificationResult": {
    "Entities": [
      {
        "Id": 1,
        "Text": "Serum Creatinine",
        "Category": "TEST_TREATMENT_PROCEDURE",
        "Type": "TEST_NAME",
        "Score": 0.98,
        "BeginOffset": 22,
        "EndOffset": 39,
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
        ],
        "Traits": []
      },
      {
        "Id": 4,
        "Text": "Blood Ammonia",
        "Category": "TEST_TREATMENT_PROCEDURE",
        "Type": "TEST_NAME",
        "Score": 0.97,
        "BeginOffset": 85,
        "EndOffset": 98,
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
          },
          {
            "Type": "TEST_UNIT",
            "Score": 0.95,
            "RelationshipScore": 0.94,
            "RelationshipType": "TEST_UNIT",
            "Id": 6,
            "Text": "µg/dL",
            "Category": "TEST_TREATMENT_PROCEDURE",
            "Traits": []
          }
        ],
        "Traits": [
          {
            "Name": "SIGN",
            "Score": 0.89
          }
        ]
      },
      {
        "Id": 7,
        "Text": "Diabetes Type 2",
        "Category": "MEDICAL_CONDITION",
        "Type": "DX_NAME",
        "Score": 0.96,
        "BeginOffset": 145,
        "EndOffset": 160,
        "Attributes": [],
        "Traits": [
          {
            "Name": "DIAGNOSIS",
            "Score": 0.94
          }
        ]
      }
    ],
    "UnmappedAttributes": []
  },
  "medicalCodes": {
    "ICD10": [
      {
        "code": "E11.9",
        "description": "Type 2 diabetes mellitus without complications",
        "category": "Endocrine, nutritional and metabolic diseases",
        "entityId": 7,
        "codeSystem": "ICD-10-CM",
        "version": "2024"
      },
      {
        "code": "R79.89",
        "description": "Other specified abnormal findings of blood chemistry",
        "category": "Symptoms, signs and abnormal clinical and laboratory findings",
        "entityId": 1,
        "codeSystem": "ICD-10-CM",
        "version": "2024"
      }
    ],
    "LOINC": [
      {
        "code": "2160-0",
        "description": "Creatinine [Mass/volume] in Serum or Plasma",
        "component": "Creatinine",
        "property": "MCnc",
        "timing": "Pt",
        "system": "Ser/Plas",
        "scale": "Qn",
        "entityId": 1
      },
      {
        "code": "16362-6",
        "description": "Ammonia [Mass/volume] in Blood",
        "component": "Ammonia",
        "property": "MCnc",
        "timing": "Pt",
        "system": "Bld",
        "scale": "Qn",
        "entityId": 4
      }
    ],
    "SNOMED": [
      {
        "code": "313822004",
        "description": "Creatinine level - finding",
        "semanticType": "Clinical Finding",
        "entityId": 1,
        "fullySpecifiedName": "Creatinine level (observable entity)"
      },
      {
        "code": "43904001",
        "description": "Ammonia measurement",
        "semanticType": "Laboratory Procedure",
        "entityId": 4,
        "fullySpecifiedName": "Ammonia measurement (procedure)"
      },
      {
        "code": "44054006",
        "description": "Diabetes mellitus type 2",
        "semanticType": "Clinical Finding",
        "entityId": 7,
        "fullySpecifiedName": "Diabetes mellitus type 2 (disorder)"
      }
    ],
    "RXNORM": []
  },
  "entitySummary": {
    "totalEntities": 3,
    "entitiesByType": {
      "TEST_NAME": 2,
      "DX_NAME": 1,
      "TEST_VALUE": 2,
      "TEST_UNIT": 2
    },
    "averageConfidence": 0.97,
    "highConfidenceCount": 3,
    "lowConfidenceCount": 0
  },
  "relationships": [
    {
      "sourceEntityId": 2,
      "targetEntityId": 1,
      "relationshipType": "TEST_VALUE",
      "confidence": 0.97
    },
    {
      "sourceEntityId": 3,
      "targetEntityId": 1,
      "relationshipType": "TEST_UNIT",
      "confidence": 0.95
    },
    {
      "sourceEntityId": 5,
      "targetEntityId": 4,
      "relationshipType": "TEST_VALUE",
      "confidence": 0.96
    },
    {
      "sourceEntityId": 6,
      "targetEntityId": 4,
      "relationshipType": "TEST_UNIT",
      "confidence": 0.94
    }
  ],
  "processedAt": "2024-01-15T14:36:45Z",
  "processingDuration": 1850,
  "metadata": {
    "classificationEngine": "AWS_Comprehend_Medical",
    "modelVersion": "2.0.5",
    "ontologyVersion": "2024-Q1"
  }
}
```

---

## Error Responses

### Error Codes

| Code | HTTP Status | Description | Resolution |
|------|-------------|-------------|------------|
| `INVALID_REQUEST` | 400 | Request validation failed | Check field constraints |
| `INVALID_REPORT_ID` | 400 | Report ID not found or invalid | Verify report ID from OCR step |
| `EMPTY_EXTRACTED_DATA` | 400 | No data provided for classification | Provide at least one test result |
| `CLASSIFICATION_FAILED` | 500 | Classification engine error | Retry or contact support |
| `LOW_CONFIDENCE_CLASSIFICATION` | 422 | All entities below confidence threshold | Review extracted data quality |
| `UNSUPPORTED_MEDICAL_TEXT` | 422 | Text doesn't contain recognizable medical terms | Verify document is medical in nature |
| `CODE_MAPPING_FAILED` | 500 | Unable to map to medical codes | Partial results may be available |
| `RATE_LIMIT_EXCEEDED` | 429 | Too many requests | Wait and retry with exponential backoff |

### Example Error Responses

#### 400 Bad Request - Invalid Report ID
```json
{
  "error": {
    "code": "INVALID_REPORT_ID",
    "message": "Report ID 'RPT-INVALID' not found",
    "details": [
      {
        "field": "reportId",
        "issue": "Report does not exist or has not been processed yet",
        "rejectedValue": "RPT-INVALID"
      }
    ],
    "timestamp": "2024-01-15T14:36:45Z",
    "path": "/api/v1/medical-report/classify"
  }
}
```

#### 422 Unprocessable Entity - Low Confidence
```json
{
  "error": {
    "code": "LOW_CONFIDENCE_CLASSIFICATION",
    "message": "All classified entities below confidence threshold (0.75)",
    "details": [
      {
        "field": "confidence",
        "issue": "No entities met minimum confidence requirement",
        "suggestion": "Lower confidence threshold or improve input data quality"
      }
    ],
    "partialResult": {
      "reportId": "RPT-8F3A2B91",
      "entitiesFound": 3,
      "highestConfidence": 0.68
    },
    "timestamp": "2024-01-15T14:36:45Z",
    "path": "/api/v1/medical-report/classify"
  }
}
```

---

## Business Rules

### 1. Entity Recognition Rules

**Confidence Thresholds:**
| Confidence Level | Range | Handling |
|-----------------|-------|----------|
| High | ≥ 0.85 | Automatic acceptance, no review needed |
| Medium | 0.70 - 0.84 | Accepted, flagged for optional review |
| Low | < 0.70 | Requires human review before use |

**Negation Detection:**
- "No fever" → Entity "fever" with NEGATION trait
- "Denies chest pain" → Entity "chest pain" with NEGATION trait
- Negated entities excluded from diagnosis lists

### 2. Medical Code Mapping Rules

**ICD-10 Mapping:**
- Conditions map to diagnosis codes
- Lab results map to findings/symptoms codes
- Procedures map to procedure codes
- Always use most specific code available

**LOINC Mapping:**
- Lab tests map to LOINC observation codes
- Includes component, property, timing, system, scale
- Prefer common vs rare LOINC codes

**SNOMED CT Mapping:**
- Comprehensive coverage of all clinical terms
- Includes semantic relationships
- Maps to concepts, not just codes

**RxNorm Mapping:**
- Medications map to semantic clinical drugs (SCD)
- Includes ingredient, strength, dose form
- Links generic and brand names

### 3. Relationship Extraction Rules

**Automatic Relationships:**
- TEST_VALUE always links to TEST_NAME
- TEST_UNIT always links to TEST_NAME or TEST_VALUE
- DOSAGE links to MEDICATION
- ROUTE_OR_MODE links to MEDICATION
- FREQUENCY links to MEDICATION

**Clinical Relationships:**
- MEDICATION_FOR_CONDITION requires explicit mention or medical knowledge
- SYMPTOM_OF_CONDITION based on medical ontology
- TEST_FOR_CONDITION requires clinical context

### 4. Quality Assurance Rules

**Minimum Requirements:**
- At least 70% confidence for entity acceptance
- Ambiguous terms require clinical context
- Conflicting information flagged for review

**PHI Handling:**
- PHI entities (names, dates, IDs) identified but not stored
- PHI stripped from responses unless explicitly requested
- Audit logging for all PHI access

### 5. Processing Limits

- Maximum 500 entities per request
- Maximum 50,000 characters of text
- Timeout: 10 seconds per request
- Rate limit: 20 requests/minute per user

---

## Usage Examples

### Example 1: Classify Lab Results

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/classify \
  -H "Content-Type: application/json" \
  -d '{
    "reportId": "RPT-8F3A2B91",
    "extractedData": [
      {
        "testName": "HbA1c",
        "testValue": "8.5",
        "unit": "%",
        "referenceRange": "< 5.7%"
      },
      {
        "testName": "Fasting Glucose",
        "testValue": "145",
        "unit": "mg/dL",
        "referenceRange": "70-100"
      }
    ],
    "clinicalContext": {
      "patientAge": 55,
      "patientGender": "MALE",
      "medicalHistory": ["Diabetes Type 2"]
    }
  }'
```

### Example 2: Classify Prescription with Medications

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/classify \
  -H "Content-Type: application/json" \
  -d '{
    "reportId": "RPT-PRESCRIPTION-001",
    "rawText": "Rx: Metformin 500mg PO BID, Lisinopril 10mg PO daily, Atorvastatin 20mg PO QHS",
    "classificationOptions": {
      "includeCodeSystems": ["RXNORM", "NDC"],
      "enableRelationshipExtraction": true
    },
    "clinicalContext": {
      "medicalHistory": ["Diabetes Type 2", "Hypertension", "Hyperlipidemia"]
    }
  }'
```

### Example 3: Classify with Low Confidence Threshold

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/classify \
  -H "Content-Type: application/json" \
  -d '{
    "reportId": "RPT-HANDWRITTEN-005",
    "extractedData": [
      {
        "testName": "BP",
        "testValue": "140/90"
      }
    ],
    "classificationOptions": {
      "confidenceThreshold": 0.60,
      "detectNegation": true
    }
  }'
```

---

## Integration Guide

### Step 1: Get Data from OCR

```javascript
// First, get OCR results
const ocrResult = await fetch('/api/v1/medical-report/ocr', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ imageBase64, patientId })
}).then(r => r.json());

console.log('OCR Report ID:', ocrResult.reportId);
```

### Step 2: Send for Classification

```javascript
// Then, classify the extracted data
const classificationResult = await fetch('/api/v1/medical-report/classify', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    reportId: ocrResult.reportId,
    extractedData: ocrResult.extractedData,
    rawText: ocrResult.rawText,
    classificationOptions: {
      includeCodeSystems: ['ICD10', 'LOINC', 'SNOMED'],
      confidenceThreshold: 0.75
    }
  })
}).then(r => r.json());

console.log('Total Entities:', classificationResult.entitySummary.totalEntities);
console.log('ICD-10 Codes:', classificationResult.medicalCodes.ICD10.length);
```

### Step 3: Process Classification Results

```javascript
function processClassificationResults(result) {
  // Extract all medications
  const medications = result.classificationResult.Entities.filter(
    e => e.Category === 'MEDICATION'
  );

  // Extract all conditions
  const conditions = result.classificationResult.Entities.filter(
    e => e.Category === 'MEDICAL_CONDITION' && e.Type === 'DX_NAME'
  );

  // Get ICD-10 codes for billing
  const icd10Codes = result.medicalCodes.ICD10.map(c => c.code);

  // Flag low confidence entities for review
  const lowConfidenceEntities = result.classificationResult.Entities.filter(
    e => e.Score < 0.75
  );

  return {
    medications,
    conditions,
    icd10Codes,
    needsReview: lowConfidenceEntities.length > 0,
    lowConfidenceEntities
  };
}

const processed = processClassificationResults(classificationResult);
console.log('Found medications:', processed.medications.length);
console.log('Found conditions:', processed.conditions.length);
console.log('Needs review:', processed.needsReview);
```

---

## Performance Metrics

### Expected Response Times

| Data Size | Expected Time |
|-----------|---------------|
| 1-10 entities | 1-2 seconds |
| 11-50 entities | 2-4 seconds |
| 51-100 entities | 4-6 seconds |
| 101-500 entities | 6-10 seconds |

### Throughput
- **Target**: < 3 seconds per request (95th percentile)
- **Maximum**: 5 seconds per request
- **Concurrent Requests**: Up to 20 simultaneous classifications

---

## FAQ

**Q: What's the difference between Category and Type?**
A: Category is high-level (MEDICATION, MEDICAL_CONDITION), Type is specific (GENERIC_NAME, DX_NAME, SYMPTOM).

**Q: How accurate is the classification?**
A: Average confidence >95% for clear, typed medical text. Handwritten or ambiguous text may be lower.

**Q: Can it understand abbreviations?**
A: Yes, common medical abbreviations are understood (BP→Blood Pressure, Hx→History, Rx→Prescription).

**Q: What if a term maps to multiple codes?**
A: All relevant codes are returned. Use clinical context to select the most appropriate.

**Q: How are relationships determined?**
A: Based on syntactic proximity, medical knowledge graphs, and clinical guidelines.

**Q: Can it detect drug-drug interactions?**
A: Not directly, but provides RxNorm codes that can be checked against interaction databases.

**Q: What languages are supported?**
A: Currently English. Bangla medical terminology support is planned.

---

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2024-01-15 | Initial Classification API release |
| 1.1.0 | TBD | Add AWS Comprehend Medical integration |
| 1.2.0 | TBD | Add relationship extraction |
| 1.3.0 | TBD | Add Bangla medical term support |

---

## Support

For technical support or questions:
- **Email**: healthcare-team@elioo.health
- **GitLab Issues**: https://github.com/KhondokerTanvirHossain/elioo-health/issues
