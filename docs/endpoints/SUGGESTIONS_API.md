# Suggestions API - AI-Powered Clinical Insights and Recommendations

## Overview

The Suggestions API uses advanced AI (AWS Bedrock with Claude 3) to generate intelligent clinical insights, patient-friendly summaries, risk assessments, and evidence-based recommendations from classified medical data. It provides both clinical and patient-appropriate language for different audiences.

**Base URL:** `http://localhost:8086/api/v1/medical-report`

**Authentication:** JWT Bearer Token (future implementation)

**Content-Type:** `application/json`

---

## Endpoint Details

### Generate Clinical Insights and Recommendations

**Endpoint:** `POST /api/v1/medical-report/suggestions`

**Description:** Analyzes classified medical data to generate AI-powered clinical insights, identify key findings, assess risk, and provide actionable recommendations tailored to patient context.

**Use Cases:**
1. Generate patient-friendly summary of test results for patient portals
2. Provide clinical decision support for healthcare providers
3. Identify critical findings requiring immediate attention
4. Suggest follow-up tests or treatments based on current results
5. Track health trends and generate longitudinal insights
6. Create automated SOAP notes or clinical summaries

---

## Request Specification

### Request Headers

| Header | Type | Required | Description |
|--------|------|----------|-------------|
| Content-Type | String | Yes | Must be `application/json` |
| Authorization | String | No (Future) | `Bearer {jwt_token}` |
| X-Request-ID | String | No | Unique request identifier for tracing |
| X-User-Role | String | No | `PROVIDER` or `PATIENT` - affects language complexity |

### Request Body

```json
{
  "reportId": "string",
  "patientContext": {
    "age": number,
    "gender": "string",
    "medicalHistory": ["string"],
    "currentMedications": ["string"],
    "allergies": ["string"],
    "vitalSigns": {
      "height": number,
      "weight": number,
      "bmi": number,
      "bloodPressure": "string"
    },
    "lifestyle": {
      "smoking": "string",
      "alcohol": "string",
      "exercise": "string"
    }
  },
  "includeActionPlan": boolean,
  "summaryOptions": {
    "targetAudience": "string",
    "detailLevel": "string",
    "focusAreas": ["string"],
    "language": "string",
    "includeEducationalContent": boolean
  },
  "riskAssessmentOptions": {
    "assessCardiovascular": boolean,
    "assessMetabolic": boolean,
    "assessRenal": boolean,
    "assessHepatic": boolean
  }
}
```

### Request Parameters

#### Root Level Parameters

| Parameter | Type | Required | Constraints | Description |
|-----------|------|----------|-------------|-------------|
| reportId | String | **Yes** | - Pattern: `^RPT-[A-Z0-9]{8}$`<br>- Must be classified report | Report identifier from classification step |
| patientContext | Object | No | - | Additional patient information for personalized insights |
| includeActionPlan | Boolean | No | Default: `true` | Generate detailed action plan with recommendations |
| summaryOptions | Object | No | - | Configuration for summary generation |
| riskAssessmentOptions | Object | No | - | Which risk assessments to perform |

#### Patient Context Object

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| age | Integer | No | Patient's age in years |
| gender | String | No | `MALE`, `FEMALE`, `OTHER` |
| medicalHistory | Array[String] | No | Known chronic conditions |
| currentMedications | Array[String] | No | Current medication list |
| allergies | Array[String] | No | Known allergies |
| vitalSigns | Object | No | Current vital signs |
| lifestyle | Object | No | Lifestyle factors |

#### Vital Signs Object

| Field | Type | Unit | Description |
|-------|------|------|-------------|
| height | Double | cm | Patient height |
| weight | Double | kg | Patient weight |
| bmi | Double | kg/m² | Body Mass Index |
| bloodPressure | String | mmHg | Format: "120/80" |
| heartRate | Integer | bpm | Beats per minute |
| temperature | Double | °C | Body temperature |

#### Lifestyle Object

| Field | Type | Values | Description |
|-------|------|--------|-------------|
| smoking | String | `NEVER`, `FORMER`, `CURRENT`, `UNKNOWN` | Smoking status |
| alcohol | String | `NONE`, `OCCASIONAL`, `MODERATE`, `HEAVY` | Alcohol consumption |
| exercise | String | `SEDENTARY`, `LIGHT`, `MODERATE`, `VIGOROUS` | Exercise level |
| diet | String | `POOR`, `FAIR`, `GOOD`, `EXCELLENT` | Diet quality |

#### Summary Options

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| targetAudience | String | `PATIENT` | `PATIENT`, `PROVIDER`, `BOTH` |
| detailLevel | String | `STANDARD` | `BRIEF`, `STANDARD`, `DETAILED` |
| focusAreas | Array[String] | `[]` | Specific areas to emphasize (e.g., ["CARDIOLOGY", "ENDOCRINOLOGY"]) |
| language | String | `EN` | Output language: `EN`, `BN` (Bangla) |
| includeEducationalContent | Boolean | `false` | Include health education content |

#### Risk Assessment Options

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| assessCardiovascular | Boolean | `true` | Assess cardiovascular risk (heart disease, stroke) |
| assessMetabolic | Boolean | `true` | Assess metabolic risk (diabetes, metabolic syndrome) |
| assessRenal | Boolean | `true` | Assess kidney function and renal risk |
| assessHepatic | Boolean | `true` | Assess liver function and hepatic risk |
| assessRespiratory | Boolean | `false` | Assess respiratory function |
| assessOncologic | Boolean | `false` | Assess cancer risk factors |

### Request Example

#### Minimal Request
```json
{
  "reportId": "RPT-8F3A2B91"
}
```

#### Full Request
```json
{
  "reportId": "RPT-8F3A2B91",
  "patientContext": {
    "age": 58,
    "gender": "MALE",
    "medicalHistory": [
      "Diabetes Type 2",
      "Hypertension",
      "Hyperlipidemia"
    ],
    "currentMedications": [
      "Metformin 500mg BID",
      "Lisinopril 10mg daily",
      "Atorvastatin 20mg QHS"
    ],
    "allergies": [
      "Penicillin"
    ],
    "vitalSigns": {
      "height": 175,
      "weight": 95,
      "bmi": 31.0,
      "bloodPressure": "142/88",
      "heartRate": 78
    },
    "lifestyle": {
      "smoking": "FORMER",
      "alcohol": "OCCASIONAL",
      "exercise": "LIGHT",
      "diet": "FAIR"
    }
  },
  "includeActionPlan": true,
  "summaryOptions": {
    "targetAudience": "BOTH",
    "detailLevel": "DETAILED",
    "focusAreas": ["ENDOCRINOLOGY", "NEPHROLOGY", "HEPATOLOGY"],
    "language": "EN",
    "includeEducationalContent": true
  },
  "riskAssessmentOptions": {
    "assessCardiovascular": true,
    "assessMetabolic": true,
    "assessRenal": true,
    "assessHepatic": true
  }
}
```

---

## Response Specification

### Success Response (200 OK)

```json
{
  "reportId": "string",
  "summaries": {
    "patientSummary": "string",
    "clinicalSummary": "string",
    "executiveSummary": "string"
  },
  "keyFindings": [
    {
      "findingId": "string",
      "finding": "string",
      "severity": "string",
      "category": "string",
      "interpretation": "string",
      "clinicalSignificance": "string",
      "normalRange": "string",
      "actualValue": "string",
      "deviationPercent": number,
      "trendDirection": "string",
      "associatedConditions": ["string"],
      "relatedTests": ["string"]
    }
  ],
  "aiSuggestions": [
    {
      "suggestionId": "string",
      "category": "string",
      "priority": "string",
      "recommendation": "string",
      "rationale": "string",
      "evidenceLevel": "string",
      "estimatedImpact": "string",
      "timeframe": "string",
      "prerequisites": ["string"],
      "contraindications": ["string"]
    }
  ],
  "riskAssessment": {
    "overallRiskLevel": "string",
    "requiresImmediateAttention": boolean,
    "cardiovascularRisk": {
      "riskLevel": "string",
      "riskScore": number,
      "riskFactors": ["string"],
      "protectiveFactors": ["string"],
      "recommendations": ["string"]
    },
    "metabolicRisk": {
      "riskLevel": "string",
      "riskScore": number,
      "riskFactors": ["string"],
      "recommendations": ["string"]
    },
    "renalRisk": {
      "riskLevel": "string",
      "stage": "string",
      "eGFR": number,
      "recommendations": ["string"]
    },
    "hepaticRisk": {
      "riskLevel": "string",
      "indicators": ["string"],
      "recommendations": ["string"]
    }
  },
  "trendAnalysis": {
    "trendsIdentified": number,
    "trends": [
      {
        "testName": "string",
        "direction": "string",
        "changePercent": number,
        "clinicalSignificance": "string",
        "recommendation": "string"
      }
    ]
  },
  "actionPlan": {
    "immediateActions": [
      {
        "action": "string",
        "priority": "string",
        "timeframe": "string",
        "responsible": "string"
      }
    ],
    "shortTermActions": [],
    "longTermActions": []
  },
  "educationalContent": [
    {
      "topic": "string",
      "content": "string",
      "resources": ["string"]
    }
  ],
  "generatedAt": "string",
  "confidenceScore": number,
  "metadata": {
    "aiModel": "string",
    "modelVersion": "string",
    "processingDuration": number,
    "dataSourcesUsed": ["string"],
    "guidelinesReferenced": ["string"]
  }
}
```

### Response Fields

#### Top Level

| Field | Type | Description |
|-------|------|-------------|
| reportId | String | Report identifier |
| summaries | Object | Different summary versions for different audiences |
| keyFindings | Array | Significant clinical observations |
| aiSuggestions | Array | AI-generated recommendations |
| riskAssessment | Object | Comprehensive risk evaluation |
| trendAnalysis | Object | Temporal trends in lab values |
| actionPlan | Object | Prioritized action items |
| educationalContent | Array | Health education materials (if requested) |
| generatedAt | String (ISO 8601) | Generation timestamp |
| confidenceScore | Double | Overall AI confidence (0.0-1.0) |
| metadata | Object | Generation metadata |

#### Summaries Object

| Field | Type | Description |
|-------|------|-------------|
| patientSummary | String | Patient-friendly explanation (6th-8th grade reading level) |
| clinicalSummary | String | Medical professional summary with clinical terminology |
| executiveSummary | String | Brief overview highlighting key points |

#### Key Finding Object

| Field | Type | Description |
|-------|------|-------------|
| findingId | String | Unique identifier for this finding |
| finding | String | Description of the finding (e.g., "Elevated Blood Ammonia") |
| severity | String | `LOW`, `MODERATE`, `HIGH`, `CRITICAL` |
| category | String | Clinical category (see [Finding Categories](#finding-categories)) |
| interpretation | String | Plain language explanation |
| clinicalSignificance | String | Why this matters clinically |
| normalRange | String | Expected normal range |
| actualValue | String | Measured value |
| deviationPercent | Double | Percent deviation from normal (e.g., +150% means 2.5x normal) |
| trendDirection | String | `IMPROVING`, `WORSENING`, `STABLE`, `NEW` |
| associatedConditions | Array[String] | Conditions commonly associated with this finding |
| relatedTests | Array[String] | Follow-up tests to consider |

### Finding Categories

| Category | Description |
|----------|-------------|
| `METABOLIC` | Blood sugar, cholesterol, metabolic syndrome indicators |
| `RENAL` | Kidney function markers (creatinine, BUN, GFR) |
| `HEPATIC` | Liver function markers (bilirubin, enzymes, albumin) |
| `CARDIOVASCULAR` | Heart health indicators (blood pressure, lipids, cardiac enzymes) |
| `HEMATOLOGIC` | Blood cell counts, coagulation |
| `ELECTROLYTE` | Electrolyte imbalances (sodium, potassium, etc.) |
| `ENDOCRINE` | Hormone levels (thyroid, insulin, etc.) |
| `INFLAMMATORY` | Inflammatory markers (CRP, ESR) |
| `NUTRITIONAL` | Vitamin/mineral deficiencies |
| `OTHER` | Other clinical findings |

#### AI Suggestion Object

| Field | Type | Description |
|-------|------|-------------|
| suggestionId | String | Unique suggestion identifier |
| category | String | See [Suggestion Categories](#suggestion-categories) |
| priority | String | `LOW`, `MEDIUM`, `HIGH` |
| recommendation | String | Specific recommended action |
| rationale | String | Evidence-based reasoning |
| evidenceLevel | String | `GUIDELINE_BASED`, `EVIDENCE_BASED`, `EXPERT_OPINION`, `AI_INFERRED` |
| estimatedImpact | String | Expected impact: `MINOR`, `MODERATE`, `SIGNIFICANT`, `MAJOR` |
| timeframe | String | When to act: `IMMEDIATE` (< 24h), `URGENT` (< 1 week), `SHORT_TERM` (< 1 month), `ROUTINE` |
| prerequisites | Array[String] | What must be done first |
| contraindications | Array[String] | When NOT to follow this recommendation |

### Suggestion Categories

| Category | Description | Examples |
|----------|-------------|----------|
| `IMMEDIATE_ACTION` | Urgent medical intervention needed | "Seek emergency care", "Contact physician today" |
| `DIAGNOSTIC_TESTS` | Additional testing recommended | "Order hepatitis panel", "Schedule liver ultrasound" |
| `MEDICATION` | Medication changes suggested | "Consider adding lactulose", "Adjust insulin dose" |
| `MEDICATION_REVIEW` | Review current medications | "Review hepatotoxic medications", "Check drug interactions" |
| `SPECIALIST_REFERRAL` | Specialist consultation needed | "Refer to hepatologist", "Cardiology consultation" |
| `MONITORING` | Follow-up testing schedule | "Repeat labs in 48-72 hours", "Monthly HbA1c monitoring" |
| `LIFESTYLE` | Lifestyle modifications | "Reduce protein intake", "Increase physical activity" |
| `DIETARY` | Dietary recommendations | "Low-sodium diet", "Restrict alcohol" |
| `PREVENTIVE` | Preventive measures | "Flu vaccination", "Screening colonoscopy" |
| `PATIENT_EDUCATION` | Educational resources | "Diabetes self-management education" |

#### Risk Assessment Object

| Field | Type | Description |
|-------|------|-------------|
| overallRiskLevel | String | `LOW`, `MODERATE`, `HIGH`, `CRITICAL` |
| requiresImmediateAttention | Boolean | Whether urgent care is needed |
| cardiovascularRisk | Object | Heart disease/stroke risk |
| metabolicRisk | Object | Diabetes/metabolic syndrome risk |
| renalRisk | Object | Kidney disease risk |
| hepaticRisk | Object | Liver disease risk |

#### Cardiovascular Risk Detail

| Field | Type | Description |
|-------|------|-------------|
| riskLevel | String | Risk level for cardiovascular events |
| riskScore | Double | Calculated risk score (e.g., Framingham score) |
| riskFactors | Array[String] | Contributing risk factors |
| protectiveFactors | Array[String] | Protective factors present |
| recommendations | Array[String] | Risk mitigation strategies |
| tenYearRisk | Double | 10-year risk percentage (if calculable) |

#### Renal Risk Detail

| Field | Type | Description |
|-------|------|-------------|
| riskLevel | String | Risk level for kidney disease |
| stage | String | CKD stage if applicable: `1`, `2`, `3A`, `3B`, `4`, `5` |
| eGFR | Double | Estimated Glomerular Filtration Rate |
| recommendations | Array[String] | Renal protection strategies |

#### Trend Object

| Field | Type | Description |
|-------|------|-------------|
| testName | String | Name of test showing trend |
| direction | String | `IMPROVING`, `WORSENING`, `STABLE` |
| changePercent | Double | Percent change from previous value |
| clinicalSignificance | String | Whether trend is clinically meaningful |
| recommendation | String | What to do about this trend |
| historicalValues | Array | Time series of previous values |

#### Action Item Object

| Field | Type | Description |
|-------|------|-------------|
| action | String | Specific action to take |
| priority | String | `LOW`, `MEDIUM`, `HIGH`, `URGENT` |
| timeframe | String | When to complete: `< 24 hours`, `< 1 week`, `< 1 month`, etc. |
| responsible | String | Who should take action: `PATIENT`, `PROVIDER`, `SPECIALIST`, `BOTH` |
| dependencies | Array[String] | Actions that must complete first |
| successCriteria | String | How to know action is complete |

### Success Response Example

```json
{
  "reportId": "RPT-8F3A2B91",
  "summaries": {
    "patientSummary": "Your recent blood tests show some concerning results, particularly related to your liver function and body chemistry. The levels of certain substances in your blood suggest your liver may not be working as well as it should. This could be affecting how your body processes waste products and maintains its chemical balance. It's important to follow up with your doctor soon to address these findings.",
    "clinicalSummary": "58 y/o male with T2DM, HTN, HLD presents with lab findings concerning for hepatic dysfunction. Notable findings include significantly elevated ammonia (244 µg/dL, ref <54), elevated bilirubin (82 µmol/L, ref <19), elevated AST (65 U/L), and low albumin (31 g/L, ref 35-57). Findings suggest possible hepatic encephalopathy risk. Creatinine mildly elevated at 135 µmol/L. Recommend urgent hepatology consultation, ammonia-lowering therapy, and comprehensive hepatic workup including viral hepatitis panel, imaging, and coagulation studies.",
    "executiveSummary": "Critical liver function abnormalities detected requiring urgent medical attention within 24-48 hours. Key concerns: severely elevated ammonia, elevated bilirubin, low albumin. Immediate hepatology consultation recommended."
  },
  "keyFindings": [
    {
      "findingId": "FIND-001",
      "finding": "Significantly Elevated Blood Ammonia (244.0 µg/dL)",
      "severity": "CRITICAL",
      "category": "HEPATIC",
      "interpretation": "Your blood ammonia level is more than 4 times higher than normal. The liver normally removes ammonia from the blood. When the liver isn't working properly, ammonia can build up and affect brain function, potentially causing confusion or altered consciousness.",
      "clinicalSignificance": "Severe hyperammonemia indicating significant hepatic dysfunction or portosystemic shunting. Risk of hepatic encephalopathy. Requires immediate intervention.",
      "normalRange": "19-54 µg/dL (Adult)",
      "actualValue": "244.0 µg/dL",
      "deviationPercent": 350.7,
      "trendDirection": "NEW",
      "associatedConditions": [
        "Hepatic Encephalopathy",
        "Cirrhosis",
        "Acute Liver Failure",
        "Portosystemic Shunt"
      ],
      "relatedTests": [
        "Liver Function Panel",
        "Hepatitis Panel",
        "Liver Imaging (US/CT)",
        "Coagulation Studies (PT/INR)",
        "Ammonia-lowering therapy trial"
      ]
    },
    {
      "findingId": "FIND-002",
      "finding": "Elevated Total Bilirubin (82.0 µmol/L)",
      "severity": "HIGH",
      "category": "HEPATIC",
      "interpretation": "Your bilirubin level is about 4 times higher than normal. Bilirubin is a yellow substance the liver processes. High levels can cause yellowing of your skin and eyes (jaundice) and indicate your liver may not be processing bile properly.",
      "clinicalSignificance": "Marked hyperbilirubinemia suggesting hepatocellular dysfunction or cholestasis. Indicates impaired hepatic conjugation/excretion. May indicate cirrhosis, hepatitis, or biliary obstruction.",
      "normalRange": "Up to 19.0 µmol/L (Adult)",
      "actualValue": "82.0 µmol/L",
      "deviationPercent": 331.6,
      "trendDirection": "NEW",
      "associatedConditions": [
        "Cirrhosis",
        "Hepatitis",
        "Biliary Obstruction",
        "Hemolysis"
      ],
      "relatedTests": [
        "Direct/Indirect Bilirubin",
        "Hepatitis Serologies",
        "Abdominal Ultrasound",
        "MRCP if obstruction suspected"
      ]
    },
    {
      "findingId": "FIND-003",
      "finding": "Elevated AST/SGOT (65.0 U/L)",
      "severity": "MODERATE",
      "category": "HEPATIC",
      "interpretation": "Your AST enzyme level is elevated, about 1.5 times the upper normal limit. AST is an enzyme found mainly in the liver and heart. Elevated levels suggest liver cell damage or inflammation.",
      "clinicalSignificance": "Mild-moderate transaminase elevation indicating hepatocellular injury. AST:ALT ratio >2 when combined with other findings may suggest alcoholic or advanced liver disease.",
      "normalRange": "Male: Up to 42 U/L",
      "actualValue": "65.0 U/L",
      "deviationPercent": 54.8,
      "trendDirection": "NEW",
      "associatedConditions": [
        "Alcoholic Liver Disease",
        "Non-Alcoholic Fatty Liver Disease",
        "Viral Hepatitis",
        "Drug-Induced Liver Injury"
      ],
      "relatedTests": [
        "ALT (already done: 28 - normal)",
        "GGT",
        "Viral Hepatitis Panel",
        "Alcohol Use Assessment"
      ]
    },
    {
      "findingId": "FIND-004",
      "finding": "Low Serum Albumin (31.0 g/L)",
      "severity": "MODERATE",
      "category": "HEPATIC",
      "interpretation": "Your albumin level is below normal. Albumin is a protein made by the liver that helps keep fluid in your bloodstream and carries nutrients. Low levels can indicate your liver isn't producing enough protein, which can lead to swelling in your legs or abdomen.",
      "clinicalSignificance": "Hypoalbuminemia indicates decreased hepatic synthetic function, suggesting chronic liver disease or protein-losing state. May contribute to edema/ascites. Marker of disease severity.",
      "normalRange": "35-57 g/L",
      "actualValue": "31.0 g/L",
      "deviationPercent": -11.4,
      "trendDirection": "NEW",
      "associatedConditions": [
        "Cirrhosis",
        "Chronic Liver Disease",
        "Malnutrition",
        "Protein-Losing Enteropathy"
      ],
      "relatedTests": [
        "Prealbumin",
        "Total Protein",
        "Prothrombin Time (PT/INR)",
        "Nutritional Assessment"
      ]
    },
    {
      "findingId": "FIND-005",
      "finding": "Mildly Elevated Serum Creatinine (135.0 µmol/L)",
      "severity": "MODERATE",
      "category": "RENAL",
      "interpretation": "Your kidney function test shows a mild elevation. Creatinine is a waste product that kidneys filter out. Slightly elevated levels suggest your kidneys may not be filtering as efficiently as they should.",
      "clinicalSignificance": "Stage 2 CKD (mild decrease in GFR). May be related to diabetes, hypertension, or hepatorenal syndrome given hepatic findings. Requires monitoring and renal protective measures.",
      "normalRange": "Male: 59-104 µmol/L",
      "actualValue": "135.0 µmol/L",
      "deviationPercent": 29.8,
      "trendDirection": "NEW",
      "associatedConditions": [
        "Chronic Kidney Disease Stage 2",
        "Diabetic Nephropathy",
        "Hypertensive Nephropathy",
        "Hepatorenal Syndrome"
      ],
      "relatedTests": [
        "Calculate eGFR",
        "Urine Albumin/Creatinine Ratio",
        "Renal Ultrasound",
        "Cystatin C"
      ]
    }
  ],
  "aiSuggestions": [
    {
      "suggestionId": "SUG-001",
      "category": "IMMEDIATE_ACTION",
      "priority": "HIGH",
      "recommendation": "Seek urgent hepatology consultation within 24-48 hours",
      "rationale": "The combination of severely elevated ammonia (>4x normal), markedly elevated bilirubin, elevated AST, and low albumin indicates significant hepatic dysfunction with risk of hepatic encephalopathy. Early intervention with ammonia-lowering therapy and comprehensive evaluation can prevent progression to life-threatening complications.",
      "evidenceLevel": "GUIDELINE_BASED",
      "estimatedImpact": "MAJOR",
      "timeframe": "IMMEDIATE",
      "prerequisites": [],
      "contraindications": [
        "None - this is urgent regardless of other factors"
      ]
    },
    {
      "suggestionId": "SUG-002",
      "category": "MEDICATION",
      "priority": "HIGH",
      "recommendation": "Initiate ammonia-lowering therapy (e.g., Lactulose 20-30g TID-QID or Rifaximin 550mg BID)",
      "rationale": "Severely elevated ammonia (244 µg/dL) poses immediate risk of hepatic encephalopathy. Lactulose and/or rifaximin can reduce ammonia levels and prevent encephalopathy. Should be started promptly while awaiting specialist consultation.",
      "evidenceLevel": "GUIDELINE_BASED",
      "estimatedImpact": "MAJOR",
      "timeframe": "IMMEDIATE",
      "prerequisites": [
        "Rule out bowel obstruction before starting lactulose"
      ],
      "contraindications": [
        "Known hypersensitivity to lactulose or rifaximin",
        "Galactosemia (for lactulose)",
        "Bowel obstruction"
      ]
    },
    {
      "suggestionId": "SUG-003",
      "category": "DIAGNOSTIC_TESTS",
      "priority": "HIGH",
      "recommendation": "Order comprehensive hepatic workup: Hepatitis A/B/C serologies, Autoimmune markers (ANA, ASMA, LKM), Ceruloplasmin, Iron studies, AFP, Liver imaging (ultrasound or CT), PT/INR, Complete blood count",
      "rationale": "Need to identify underlying cause of hepatic dysfunction. Differential includes viral hepatitis, autoimmune hepatitis, alcoholic/non-alcoholic steatohepatitis, cirrhosis, Wilson's disease, or hepatocellular carcinoma. Imaging will assess for structural abnormalities, masses, or cirrhosis.",
      "evidenceLevel": "GUIDELINE_BASED",
      "estimatedImpact": "SIGNIFICANT",
      "timeframe": "URGENT",
      "prerequisites": [],
      "contraindications": []
    },
    {
      "suggestionId": "SUG-004",
      "category": "MEDICATION_REVIEW",
      "priority": "HIGH",
      "recommendation": "Review all current medications for hepatotoxicity; discontinue or adjust hepatotoxic medications",
      "rationale": "Patient currently on Metformin, Lisinopril, and Atorvastatin. While these are generally safe, statins can rarely cause hepatotoxicity, especially in setting of existing liver disease. Metformin should be used cautiously with reduced GFR. Review for any additional medications, supplements, or herbals that could contribute to liver injury.",
      "evidenceLevel": "GUIDELINE_BASED",
      "estimatedImpact": "MODERATE",
      "timeframe": "IMMEDIATE",
      "prerequisites": [
        "Document all medications including OTC and supplements"
      ],
      "contraindications": []
    },
    {
      "suggestionId": "SUG-005",
      "category": "MONITORING",
      "priority": "HIGH",
      "recommendation": "Repeat comprehensive metabolic panel including LFTs, ammonia, and creatinine in 48-72 hours",
      "rationale": "Close monitoring essential to assess response to ammonia-lowering therapy and track progression/improvement of hepatic and renal function. Rapid deterioration would necessitate hospitalization.",
      "evidenceLevel": "GUIDELINE_BASED",
      "estimatedImpact": "SIGNIFICANT",
      "timeframe": "URGENT",
      "prerequisites": [
        "Initiate ammonia-lowering therapy first"
      ],
      "contraindications": []
    },
    {
      "suggestionId": "SUG-006",
      "category": "LIFESTYLE",
      "priority": "MEDIUM",
      "recommendation": "Temporarily reduce dietary protein intake to 0.8-1.0 g/kg/day and avoid alcohol completely",
      "rationale": "Reducing protein intake can help lower ammonia production from protein breakdown, reducing hepatic encephalopathy risk. Complete alcohol abstinence is critical as alcohol is directly hepatotoxic and can worsen existing liver disease.",
      "evidenceLevel": "GUIDELINE_BASED",
      "estimatedImpact": "MODERATE",
      "timeframe": "IMMEDIATE",
      "prerequisites": [],
      "contraindications": [
        "Severe malnutrition (requires nutrition specialist involvement)"
      ]
    },
    {
      "suggestionId": "SUG-007",
      "category": "MONITORING",
      "priority": "MEDIUM",
      "recommendation": "Monitor for signs/symptoms of hepatic encephalopathy: confusion, altered mental status, asterixis (flapping tremor), sleep disturbances",
      "rationale": "With ammonia >200 µg/dL, risk of hepatic encephalopathy is significant. Early recognition allows for prompt intervention. Family members should be educated on warning signs.",
      "evidenceLevel": "GUIDELINE_BASED",
      "estimatedImpact": "MAJOR",
      "timeframe": "IMMEDIATE",
      "prerequisites": [
        "Patient and family education on warning signs"
      ],
      "contraindications": []
    },
    {
      "suggestionId": "SUG-008",
      "category": "SPECIALIST_REFERRAL",
      "priority": "MEDIUM",
      "recommendation": "Consider nephrology consultation for management of mild renal impairment in context of diabetes and liver disease",
      "rationale": "Elevated creatinine (135 µmol/L) in setting of diabetes, hypertension, and now hepatic dysfunction raises concern for diabetic nephropathy, hypertensive nephrosclerosis, or hepatorenal syndrome. Nephrologist can optimize renal-protective strategies.",
      "evidenceLevel": "EXPERT_OPINION",
      "estimatedImpact": "MODERATE",
      "timeframe": "SHORT_TERM",
      "prerequisites": [
        "Calculate eGFR and urine albumin/creatinine ratio"
      ],
      "contraindications": []
    },
    {
      "suggestionId": "SUG-009",
      "category": "DIETARY",
      "priority": "MEDIUM",
      "recommendation": "Ensure adequate hydration (at least 2 liters water daily unless contraindicated) and maintain balanced electrolytes",
      "rationale": "Adequate hydration supports renal function (important with elevated creatinine) and helps lactulose work effectively. Electrolyte balance important to avoid precipitating hepatic encephalopathy.",
      "evidenceLevel": "EXPERT_OPINION",
      "estimatedImpact": "MINOR",
      "timeframe": "IMMEDIATE",
      "prerequisites": [],
      "contraindications": [
        "Fluid restriction if significant ascites or heart failure present"
      ]
    },
    {
      "suggestionId": "SUG-010",
      "category": "PREVENTIVE",
      "priority": "LOW",
      "recommendation": "Ensure Hepatitis A and B vaccinations are up to date (if not already immune)",
      "rationale": "Patients with existing liver disease should be protected against hepatitis viruses which can cause severe complications in setting of compromised hepatic function.",
      "evidenceLevel": "GUIDELINE_BASED",
      "estimatedImpact": "MODERATE",
      "timeframe": "ROUTINE",
      "prerequisites": [
        "Check hepatitis serology first to determine immunity status"
      ],
      "contraindications": [
        "Acute illness",
        "Severe immunosuppression"
      ]
    }
  ],
  "riskAssessment": {
    "overallRiskLevel": "HIGH",
    "requiresImmediateAttention": true,
    "cardiovascularRisk": {
      "riskLevel": "MODERATE",
      "riskScore": 18.5,
      "tenYearRisk": 15.2,
      "riskFactors": [
        "Age 58",
        "Male gender",
        "Diabetes Type 2",
        "Hypertension (BP 142/88)",
        "Hyperlipidemia",
        "BMI 31 (Obese Class I)",
        "Former smoker"
      ],
      "protectiveFactors": [
        "On statin therapy",
        "On ACE inhibitor",
        "Quit smoking"
      ],
      "recommendations": [
        "Target BP <130/80 for diabetic patient",
        "Consider increasing statin intensity if lipids suboptimal",
        "Continue aspirin if on primary prevention",
        "Encourage regular aerobic exercise 150 min/week",
        "Weight reduction goal: lose 5-10% body weight"
      ]
    },
    "metabolicRisk": {
      "riskLevel": "HIGH",
      "riskScore": 82,
      "riskFactors": [
        "Established Diabetes Type 2",
        "BMI 31 (Obese Class I)",
        "Likely suboptimal glycemic control (need HbA1c)",
        "Metabolic syndrome present"
      ],
      "recommendations": [
        "Check HbA1c to assess diabetes control",
        "Consider diabetes medication optimization",
        "Refer to diabetes educator/nutritionist",
        "Screen for diabetic complications (retinopathy, neuropathy)",
        "Target HbA1c <7% if safely achievable"
      ]
    },
    "renalRisk": {
      "riskLevel": "MODERATE",
      "stage": "2",
      "eGFR": 52.5,
      "albuminuria": "UNKNOWN",
      "recommendations": [
        "Check urine albumin/creatinine ratio",
        "Continue ACE inhibitor (renoprotective)",
        "Optimize blood sugar control",
        "Target BP <130/80",
        "Avoid nephrotoxic medications (NSAIDs)",
        "Annual monitoring of kidney function",
        "Consider SGLT2 inhibitor for additional renoprotection"
      ]
    },
    "hepaticRisk": {
      "riskLevel": "CRITICAL",
      "indicators": [
        "Severely elevated ammonia (244 µg/dL)",
        "Markedly elevated bilirubin (82 µmol/L)",
        "Elevated AST (65 U/L)",
        "Low albumin (31 g/L) - impaired synthetic function",
        "Risk of hepatic encephalopathy"
      ],
      "estimatedSeverity": "Advanced liver disease likely present",
      "recommendations": [
        "URGENT hepatology consultation",
        "Initiate ammonia-lowering therapy immediately",
        "Complete hepatic workup (viral, autoimmune, metabolic)",
        "Liver imaging (ultrasound/CT)",
        "Monitor for hepatic encephalopathy",
        "Avoid all hepatotoxic medications and alcohol",
        "Consider evaluation for cirrhosis and complications",
        "Possible need for liver biopsy depending on workup"
      ]
    }
  },
  "trendAnalysis": {
    "trendsIdentified": 0,
    "trends": [],
    "note": "Insufficient historical data to perform trend analysis. This appears to be initial testing. Recommend establishing baseline and scheduling follow-up labs in 48-72 hours to assess response to therapy and identify early trends."
  },
  "actionPlan": {
    "immediateActions": [
      {
        "action": "Contact primary care provider or hepatologist TODAY for urgent appointment within 24-48 hours",
        "priority": "URGENT",
        "timeframe": "< 24 hours",
        "responsible": "PATIENT",
        "successCriteria": "Appointment scheduled with provider"
      },
      {
        "action": "Start ammonia-lowering therapy (Lactulose or Rifaximin) as prescribed",
        "priority": "URGENT",
        "timeframe": "< 24 hours",
        "responsible": "PROVIDER",
        "dependencies": ["Prescription written by provider"],
        "successCriteria": "Medication started, dosing instructions clear"
      },
      {
        "action": "Review all current medications with provider; discontinue any potentially hepatotoxic agents",
        "priority": "URGENT",
        "timeframe": "< 24 hours",
        "responsible": "PROVIDER",
        "successCriteria": "Medication list reviewed, necessary adjustments made"
      },
      {
        "action": "Completely avoid alcohol consumption",
        "priority": "URGENT",
        "timeframe": "Immediately and ongoing",
        "responsible": "PATIENT",
        "successCriteria": "Patient verbalizes understanding and commits to abstinence"
      },
      {
        "action": "Educate patient and family on signs of hepatic encephalopathy (confusion, altered mental status, tremor) and when to seek emergency care",
        "priority": "URGENT",
        "timeframe": "< 24 hours",
        "responsible": "PROVIDER",
        "successCriteria": "Patient and family demonstrate understanding of warning signs"
      },
      {
        "action": "Order comprehensive hepatic workup: Hepatitis panel, autoimmune markers, iron studies, AFP, liver imaging, PT/INR, CBC",
        "priority": "URGENT",
        "timeframe": "< 72 hours",
        "responsible": "PROVIDER",
        "successCriteria": "Tests ordered and scheduled"
      }
    ],
    "shortTermActions": [
      {
        "action": "Repeat labs (CMP, LFTs, ammonia, CBC) in 48-72 hours to assess treatment response",
        "priority": "HIGH",
        "timeframe": "48-72 hours",
        "responsible": "PROVIDER",
        "dependencies": ["Ammonia-lowering therapy initiated"],
        "successCriteria": "Labs drawn, results reviewed"
      },
      {
        "action": "Follow up with hepatologist for comprehensive evaluation and treatment plan",
        "priority": "HIGH",
        "timeframe": "< 1 week",
        "responsible": "BOTH",
        "dependencies": ["Initial urgent appointment completed"],
        "successCriteria": "Hepatology evaluation completed, treatment plan established"
      },
      {
        "action": "Implement low-protein diet (0.8-1.0 g/kg/day) temporarily",
        "priority": "MEDIUM",
        "timeframe": "< 1 week",
        "responsible": "PATIENT",
        "dependencies": ["Nutritionist consultation if available"],
        "successCriteria": "Patient following dietary modifications"
      },
      {
        "action": "Check HbA1c and adjust diabetes management if needed",
        "priority": "MEDIUM",
        "timeframe": "< 1 week",
        "responsible": "PROVIDER",
        "successCriteria": "HbA1c checked, diabetes medications optimized"
      },
      {
        "action": "Calculate eGFR and check urine albumin/creatinine ratio",
        "priority": "MEDIUM",
        "timeframe": "< 1 week",
        "responsible": "PROVIDER",
        "successCriteria": "Renal function fully assessed"
      }
    ],
    "longTermActions": [
      {
        "action": "Establish regular hepatology follow-up schedule based on specialist recommendations",
        "priority": "MEDIUM",
        "timeframe": "< 1 month",
        "responsible": "BOTH",
        "dependencies": ["Initial hepatology evaluation"],
        "successCriteria": "Follow-up schedule established and appointments booked"
      },
      {
        "action": "Consider nephrology referral if renal function worsens or diabetic nephropathy confirmed",
        "priority": "MEDIUM",
        "timeframe": "< 1 month",
        "responsible": "PROVIDER",
        "dependencies": ["Complete renal workup"],
        "successCriteria": "Nephrology referral made if indicated"
      },
      {
        "action": "Optimize cardiovascular risk management (blood pressure, lipids, weight, exercise)",
        "priority": "MEDIUM",
        "timeframe": "Ongoing, review monthly",
        "responsible": "BOTH",
        "successCriteria": "BP <130/80, weight trending down, regular exercise"
      },
      {
        "action": "Ensure Hepatitis A and B vaccinations if not immune",
        "priority": "LOW",
        "timeframe": "< 3 months",
        "responsible": "PROVIDER",
        "dependencies": ["Hepatitis serology results"],
        "successCriteria": "Vaccination series initiated if indicated"
      },
      {
        "action": "Annual comprehensive metabolic screening including LFTs, renal function, HbA1c, lipids",
        "priority": "MEDIUM",
        "timeframe": "Ongoing, annually",
        "responsible": "PROVIDER",
        "successCriteria": "Annual screening schedule established"
      }
    ]
  },
  "educationalContent": [
    {
      "topic": "Understanding Your Liver Function Tests",
      "content": "Your liver performs over 500 vital functions including filtering toxins, producing proteins, and helping digest food. The tests that are elevated (ammonia, bilirubin, AST) and low (albumin) tell us your liver isn't working as well as it should right now. The good news is that with proper treatment, many liver conditions can improve significantly.",
      "resources": [
        "https://medlineplus.gov/liverdiseases.html",
        "https://www.niddk.nih.gov/health-information/liver-disease"
      ]
    },
    {
      "topic": "What is Hepatic Encephalopathy?",
      "content": "When the liver can't properly remove ammonia from your blood, ammonia can build up and affect your brain function. This condition is called hepatic encephalopathy. Early signs include mild confusion, trouble concentrating, mood changes, or sleep disturbances. If you notice any of these symptoms, contact your doctor immediately. The medications prescribed (like lactulose) help reduce ammonia levels and prevent these symptoms.",
      "resources": [
        "https://www.mayoclinic.org/diseases-conditions/hepatic-encephalopathy",
        "https://www.liver.org/hepatic-encephalopathy"
      ]
    },
    {
      "topic": "Protecting Your Kidneys with Diabetes",
      "content": "Your kidneys filter waste from your blood. Diabetes and high blood pressure can damage the kidneys over time. Your creatinine is slightly elevated, which means your kidneys may not be filtering as efficiently. You can protect your kidneys by: controlling your blood sugar (target HbA1c <7%), keeping blood pressure below 130/80, taking your ACE inhibitor medication (Lisinopril) as prescribed, avoiding NSAIDs like ibuprofen, staying well hydrated, and having regular kidney function tests.",
      "resources": [
        "https://www.niddk.nih.gov/health-information/diabetes/overview/preventing-problems/kidney-disease",
        "https://www.kidney.org/atoz/content/diabetes"
      ]
    },
    {
      "topic": "Dietary Considerations for Liver Disease",
      "content": "Temporarily reducing protein intake can help lower ammonia production. Focus on high-quality proteins in smaller amounts (lean poultry, fish, eggs). Completely avoid alcohol as it's toxic to the liver. Stay well hydrated (at least 2 liters of water daily). Eat small, frequent meals rather than large meals. Choose whole grains, fruits, and vegetables. Limit sodium to help prevent fluid retention. A nutritionist can help you create a balanced meal plan that supports your liver health.",
      "resources": [
        "https://www.hepatitis.va.gov/cirrhosis/patient/diet.asp",
        "https://liverfoundation.org/for-patients/about-the-liver/health-wellness/nutrition/"
      ]
    }
  ],
  "generatedAt": "2024-01-15T14:38:12Z",
  "confidenceScore": 0.94,
  "metadata": {
    "aiModel": "AWS_Bedrock_Claude_3_Sonnet",
    "modelVersion": "3.0.5",
    "processingDuration": 4250,
    "dataSourcesUsed": [
      "Classification Results",
      "Patient Medical History",
      "Vital Signs",
      "Medication List"
    ],
    "guidelinesReferenced": [
      "AASLD Practice Guidelines for Hepatic Encephalopathy",
      "KDIGO CKD Guidelines",
      "ADA Standards of Medical Care in Diabetes",
      "ACC/AHA Cardiovascular Risk Guidelines"
    ],
    "disclaimers": [
      "AI-generated content for informational purposes only",
      "Does not replace professional medical advice",
      "Provider should review and validate all recommendations",
      "Clinical judgment should override AI suggestions when appropriate"
    ]
  }
}
```

---

## Error Responses

### Error Codes

| Code | HTTP Status | Description | Resolution |
|------|-------------|-------------|------------|
| `INVALID_REQUEST` | 400 | Request validation failed | Check field constraints |
| `INVALID_REPORT_ID` | 400 | Report ID not found | Verify report has been classified |
| `CLASSIFICATION_NOT_COMPLETE` | 400 | Report not yet classified | Complete classification step first |
| `INSUFFICIENT_DATA` | 422 | Not enough data to generate insights | Ensure classification extracted entities |
| `AI_GENERATION_FAILED` | 500 | AI model error | Retry or contact support |
| `CONFIDENCE_TOO_LOW` | 422 | AI confidence below acceptable threshold | Review input data quality |

---

## Business Rules

### 1. Severity Classification

**Severity Levels Based on Clinical Guidelines:**

| Severity | Criteria |
|----------|----------|
| LOW | Value slightly outside normal, no immediate concern |
| MODERATE | Value moderately outside normal, requires monitoring |
| HIGH | Value significantly outside normal, requires intervention |
| CRITICAL | Life-threatening value, requires immediate action |

### 2. Risk Level Determination

**Overall Risk Level Algorithm:**
```
IF any finding is CRITICAL → Overall Risk = CRITICAL
ELSE IF 3+ findings are HIGH → Overall Risk = HIGH
ELSE IF 2+ findings are HIGH OR 5+ MODERATE → Overall Risk = MODERATE
ELSE → Overall Risk = LOW
```

**Immediate Attention Triggers:**
- Any CRITICAL finding
- Ammonia >200 µg/dL
- Bilirubin >5x normal
- Creatinine >3x normal
- Any combination suggesting organ failure

### 3. Evidence Levels

| Level | Description | Trust Level |
|-------|-------------|-------------|
| GUIDELINE_BASED | From established clinical practice guidelines | Highest |
| EVIDENCE_BASED | From peer-reviewed research | High |
| EXPERT_OPINION | From medical expert consensus | Moderate |
| AI_INFERRED | AI-generated based on patterns | Review recommended |

### 4. Recommendation Prioritization

**Priority Assignment:**
1. **HIGH**: Life-threatening situations, critical findings
2. **MEDIUM**: Important but not immediately life-threatening
3. **LOW**: Preventive, long-term optimization

**Timeframes:**
- IMMEDIATE: < 24 hours
- URGENT: < 1 week
- SHORT_TERM: < 1 month
- ROUTINE: Scheduled appropriately

### 5. Language Adaptation

**Patient Summary (targetAudience: PATIENT):**
- 6th-8th grade reading level
- Avoid medical jargon
- Use analogies and simple explanations
- Focus on "what this means for you"

**Clinical Summary (targetAudience: PROVIDER):**
- Medical terminology appropriate
- Concise, precise language
- Include differential diagnoses
- Reference clinical guidelines

### 6. Confidence Threshold

- Minimum overall confidence: 0.70
- Recommendations with confidence <0.75 flagged for review
- Critical recommendations require confidence >0.85

---

## Performance Metrics

### Expected Response Times

| Complexity | Expected Time |
|-----------|---------------|
| Simple case (1-3 findings) | 2-3 seconds |
| Moderate case (4-8 findings) | 3-5 seconds |
| Complex case (9+ findings, full context) | 5-8 seconds |

### Throughput
- **Target**: < 4 seconds per request (95th percentile)
- **Maximum**: 5 seconds per request
- **Concurrent Requests**: Up to 15 simultaneous generations

---

## Integration Example

```javascript
// Complete workflow: OCR → Classification → Suggestions
async function processCompleteReport(imageBase64, patientId, patientContext) {
  try {
    // Step 1: OCR
    console.log('Step 1: Processing OCR...');
    const ocrResult = await fetch('/api/v1/medical-report/ocr', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ imageBase64, patientId })
    }).then(r => r.json());

    console.log(`✓ OCR Complete - Report ID: ${ocrResult.reportId}`);
    console.log(`  Extracted ${ocrResult.extractedData.length} test results`);

    // Step 2: Classification
    console.log('Step 2: Classifying medical entities...');
    const classification = await fetch('/api/v1/medical-report/classify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        reportId: ocrResult.reportId,
        extractedData: ocrResult.extractedData,
        rawText: ocrResult.rawText
      })
    }).then(r => r.json());

    console.log(`✓ Classification Complete`);
    console.log(`  Found ${classification.entitySummary.totalEntities} entities`);
    console.log(`  Mapped ${classification.medicalCodes.ICD10.length} ICD-10 codes`);

    // Step 3: Generate Insights
    console.log('Step 3: Generating clinical insights...');
    const suggestions = await fetch('/api/v1/medical-report/suggestions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        reportId: ocrResult.reportId,
        patientContext,
        includeActionPlan: true,
        summaryOptions: {
          targetAudience: 'BOTH',
          detailLevel: 'DETAILED',
          includeEducationalContent: true
        }
      })
    }).then(r => r.json());

    console.log(`✓ Insights Generated`);
    console.log(`  Found ${suggestions.keyFindings.length} key findings`);
    console.log(`  Generated ${suggestions.aiSuggestions.length} recommendations`);
    console.log(`  Overall Risk: ${suggestions.riskAssessment.overallRiskLevel}`);

    if (suggestions.riskAssessment.requiresImmediateAttention) {
      console.warn('⚠️  CRITICAL: Immediate medical attention required!');
      triggerCriticalAlert(suggestions);
    }

    return {
      ocr: ocrResult,
      classification,
      suggestions
    };

  } catch (error) {
    console.error('Error in report processing:', error);
    throw error;
  }
}

// Handle critical findings
function triggerCriticalAlert(suggestions) {
  const criticalFindings = suggestions.keyFindings.filter(
    f => f.severity === 'CRITICAL'
  );

  const urgentActions = suggestions.aiSuggestions.filter(
    s => s.timeframe === 'IMMEDIATE' && s.priority === 'HIGH'
  );

  // Send alert to provider
  sendProviderAlert({
    patientId: suggestions.reportId,
    criticalFindings,
    urgentActions,
    summary: suggestions.summaries.executiveSummary
  });

  // Show patient warning
  showPatientWarning({
    message: 'Your test results require immediate medical attention',
    actions: urgentActions.filter(a => a.responsible === 'PATIENT')
  });
}

// Usage
const patientContext = {
  age: 58,
  gender: 'MALE',
  medicalHistory: ['Diabetes Type 2', 'Hypertension'],
  currentMedications: ['Metformin 500mg', 'Lisinopril 10mg']
};

const results = await processCompleteReport(imageBase64, 'P12345', patientContext);

// Display patient summary
document.getElementById('patient-summary').textContent = results.suggestions.summaries.patientSummary;

// Display key findings with severity highlighting
results.suggestions.keyFindings.forEach(finding => {
  const findingElement = createFindingCard(finding);
  if (finding.severity === 'CRITICAL' || finding.severity === 'HIGH') {
    findingElement.classList.add('alert-severe');
  }
  document.getElementById('findings-list').appendChild(findingElement);
});

// Display action plan
displayActionPlan(results.suggestions.actionPlan);
```

---

## FAQ

**Q: How does the AI generate recommendations?**
A: Uses AWS Bedrock (Claude 3) trained on medical literature, clinical guidelines, and validated against expert medical knowledge.

**Q: Can I trust AI-generated recommendations?**
A: Recommendations are evidence-based and include confidence scores and evidence levels. However, they should always be reviewed by qualified healthcare providers before clinical use.

**Q: What makes a finding "critical"?**
A: Values severely outside normal range (typically >2-3x or <0.3-0.5x normal), life-threatening implications, or requiring immediate intervention.

**Q: How are risk assessments calculated?**
A: Uses validated clinical risk scores (e.g., Framingham for CV risk), combined with AI analysis of lab patterns and patient context.

**Q: Can educational content be customized?**
A: Yes, set `includeEducationalContent: true` and specify `language` and `detailLevel` in summaryOptions.

**Q: What if I disagree with a recommendation?**
A: Recommendations are suggestions, not mandates. Clinical judgment should always override AI suggestions when appropriate.

**Q: How often should insights be regenerated?**
A: Generate new insights whenever new test results are available or when patient context significantly changes.

---

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2024-01-15 | Initial Suggestions API release |
| 1.1.0 | TBD | Add AWS Bedrock Claude 3 integration |
| 1.2.0 | TBD | Add trend analysis from historical data |
| 1.3.0 | TBD | Add Bangla language support for summaries |

---

## Support

For technical support or questions:
- **Email**: healthcare-team@elioo.health
- **GitLab Issues**: https://github.com/KhondokerTanvirHossain/elioo-health/issues

---

## Legal Disclaimer

**IMPORTANT**: The AI-generated insights, recommendations, and assessments provided by this API are for informational purposes only and do not constitute medical advice, diagnosis, or treatment. Healthcare providers must review and validate all AI-generated content before clinical use. Patients should consult qualified healthcare professionals for medical advice. The system creators and operators are not liable for clinical decisions made based on AI-generated content.
