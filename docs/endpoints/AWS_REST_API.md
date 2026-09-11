# MedScribe AI - AWS REST API Reference

- Base URL (local): `http://localhost:8086`
- Base URL (remote example): `http://15.206.148.124:8086`


---

## AWS Textract

### Analyze Document (Tables + Forms)
- **POST** `/api/aws/textract/analyze-document`
- Extracts tables and form fields from an image.
- Request:
```json
{
  "imageBase64": "<base64_image>",
  "featureTypes": ["TABLES", "FORMS"]
}
```
- Example `curl`:
```bash
curl --location 'http://15.206.148.124:8086/api/aws/textract/analyze-document' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: {{token}}' \
  --data '{
    "imageBase64": "{imageBase64}",
    "featureTypes": ["TABLES", "FORMS"]
  }'
```
- Success Response (sample):
```json
{
  "blocks": [
    {
      "id": "6e12a975-8679-4ef8-ae0e-923cfaf47a34",
      "blockType": "PAGE",
      "relationships": [
        {
          "type": "CHILD",
          "ids": ["e8e633a0-0a23-48bc-8412-ffebfc368ea0", "..."]
        }
      ],
      "geometry": {"boundingBox": {"width": 0.999969, "height": 1, "left": 0, "top": 0}}
    },
    {
      "id": "e8e633a0-0a23-48bc-8412-ffebfc368ea0",
      "blockType": "LINE",
      "text": "A.WE",
      "confidence": 24.414461,
      "geometry": {"boundingBox": {"width": 0.048868548, "height": 0.024661245, "left": 0.90871555, "top": 0.12442305}}
    },
    {
      "id": "01329cf4-f551-4d60-aca4-c54c488d6e00",
      "blockType": "LINE",
      "text": "WE",
      "confidence": 92.30608
    }
    // ... full block list, tables, keyValueSets, and geometry omitted for brevity ...
  ],
  "pageCount": 1,
  "documentPages": 1,
  "metadata": {
    "pages": 1,
    "analyzeDocumentModelVersion": "1.0"
  }
}
```

### Detect Text (Simple OCR)
- **POST** `/api/aws/textract/detect-text`
- Basic text extraction from an image.
- Request:
```json
{
  "imageBase64": "<base64_image>"
}
```
- Example `curl`:
```bash
curl --location 'http://15.206.148.124:8086/api/aws/textract/detect-text' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: {{token}}' \
  --data '{
    "imageBase64": "{imageBase64}"
  }'
```
- Success Response (sample):
```json
{
  "length": 1459,
  "text": "A.WE\nWE\nSAMORITA HOSPITAL LIMITED.\nS\nCERTIFIED\n8.\nACTS.\n? layle\nISO\n9001:2015\nCOO\nCOMPANY\n89/1, Panthapath, Dhaka-1215, Phone : 48117618-9; 8142748, Hotline : 10674\nFax : 880-2-48117616, E-mail: samoritabd@gmail.com\nDIAGNOSTIC (LAB) DIVISION\nBIOCHEMISTRY REPORT\nInvoice No : I2411104460\nReceived Date & Time : 21/11/24 - 06:46 PM Delivery Date & Time : 21/11/24 - 08:00 PM\nPatient Name : S. M. FARUQ AHMED HCM-4\nAge: 59 Y\nGender : Male\nReferred By : SAMORITA HOSPITAL LTD\nAdmission No : 2404/25\nSample : BLOOD\nLAB. No : 12411681621\nAN\nTests : S.LFT, S. Electrolytes, S.Creatinine, S.Albumin, Ammonia Blood\nReport No : 12411649237\nD.No:-147\nEstimations are carried out by fully Automated Clinical Chemistry Analyzer Konelab 60,Thermo,Finland\nTest\nResult\nReference Value\nSerum Creatinine\n135.0 µmol/L\nMale : 59-104\nFemale : 45-84\nS. Electrolytes\nSodium\n138.0 mmol/L\n136-148\nPotassium\n3.5 mmol/L\n3.5-5.2\nChloride\n100.0 mmol/L\n98-108\nT-CO2 Content (Venous)\n26.0 mmol/L\nMale:25-29, Female:23-27\nAnion Gap\n15.5 mmol/L\n7.0-21.0\nLFT\nBilirubin\n82.0 µmol/L\nAdult upto:19.0, Newborn:227\nAST (SGOT)\n65.0 U/L\nMale Upto:42, Female Upto:32\nALT (SGPT)\n28.0 U/L\nMale Upto:42, Female Upto:32\nAlkaline Phosphate\n74.0 U/L\nMale : 53-128, Female :42-98\nAlbumin\n31.0 g/L\n35-57\nBlood Ammonia\n244.0 µg/dL\nAdult: 19-54 µg/d!\nNeonates: 109-182 µg/d\nDR. Dosi NASIM RAYHAN\nChecked by\nPankaj Joydhar\nMBBS, MPH, PhD. (Path)\nMedical Technologist\nConsultant Pathologist\nSamoritaHospital Ltd.\n"
}
```

### Validate Image Quality
- **POST** `/api/aws/textract/validate-image`
- Performs quality checks to ensure the image is readable before OCR.
- Request:
```json
{
  "imageBase64": "<base64_image>"
}
```
- Example `curl`:
```bash
curl --location 'http://15.206.148.124:8086/api/aws/textract/validate-image' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: {{token}}' \
  --data '{
    "imageBase64": "{imageBase64}"
  }'
```
- Success Response (sample):
```json
{
  "isValid": true,
  "qualityScore": 0.85,
  "message": "Image quality acceptable",
  "metrics": {
    "maxAllowedMB": 10,
    "sizeInMB": "0.70",
    "minAllowedMB": 0.1,
    "sizeBytes": 735540
  }
}
```

---

## AWS Bedrock (LLM)

### Invoke Model (Generic)
- **POST** `/api/aws/bedrock/invoke-model`
- Runs a specified Bedrock model with custom prompts and decoding settings.
- Request:
```json
{
  "userPrompt": "Explain hemoglobin levels in simple terms",
  "systemPrompt": "You are a medical AI assistant providing patient-friendly explanations",
  "modelId": "us.anthropic.claude-3-5-sonnet-20241022-v2:0",
  "maxTokens": 2000,
  "temperature": 0.7
}
```
- Example `curl`:
```bash
curl --location 'http://15.206.148.124:8086/api/aws/bedrock/invoke-model' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: {{token}}' \
  --data '{
    "userPrompt": "Explain hemoglobin levels in simple terms",
    "systemPrompt": "You are a medical AI assistant providing patient-friendly explanations",
    "modelId": "us.anthropic.claude-3-5-sonnet-20241022-v2:0",
    "maxTokens": 2000,
    "temperature": 0.7
  }'
```
- Success Response (sample):
```json
{
  "content": "Let me explain hemoglobin in a simple way:\n\nHemoglobin is like a delivery service in your blood. It's a protein found in your red blood cells that:\n- Carries oxygen from your lungs to all parts of your body\n- Gives blood its red color\n- Is measured as part of a standard blood test\n\nNormal hemoglobin levels are typically:\n- Men: 13.5 to 17.5 grams per deciliter\n- Women: 12.0 to 15.5 grams per deciliter\n\nThink of hemoglobin like tiny trucks in your bloodstream:\n- When levels are too low (anemia), it's like not having enough delivery trucks - your body's tissues don't get enough oxygen\n- When levels are too high, it's like having too much traffic - blood can become too thick\n\nCommon causes of low hemoglobin include:\n- Iron deficiency\n- Poor diet\n- Blood loss\n- Certain diseases\n\nWould you like me to explain any part of this in more detail?",
  "stopReason": "end_turn",
  "usage": {
    "inputTokens": 29,
    "outputTokens": 237
  },
  "modelId": "us.anthropic.claude-3-5-sonnet-20241022-v2:0",
  "metadata": null,
  "contentLength": 847,
  "complete": true,
  "maxTokensReached": false,
  "totalTokens": 266
}
```

### Invoke Claude (Convenience)
- **POST** `/api/aws/bedrock/invoke-claude`
- Shortcut wrapper around Claude with sensible defaults.
- Request:
```json
{
  "userPrompt": "What does elevated creatinine mean?",
  "systemPrompt": "Explain in patient-friendly language"
}
```
- Example `curl`:
```bash
curl --location 'http://15.206.148.124:8086/api/aws/bedrock/invoke-claude' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: {{token}}' \
  --data '{
    "userPrompt": "What does elevated creatinine mean?",
    "systemPrompt": "Explain in patient-friendly language"
  }'
```
- Success Response (sample):
```json
{
  "content": "Elevated creatinine means there are higher than normal levels of a waste product called creatinine in your blood. Here's a simple explanation:\n\nCreatinine is:\n- A waste product from your muscles' normal wear and tear\n- Normally filtered out by your kidneys\n- Released into your urine\n\nWhen creatinine levels are high, it usually indicates:\n- Your kidneys aren't filtering waste properly\n- There might be a problem with kidney function\n\nCommon causes include:\n- Dehydration\n- Kidney disease or damage\n- Some medications\n- High-protein diets\n- Intense exercise\n\nNormal creatinine levels are typically:\n- Men: 0.7-1.3 mg/dL\n- Women: 0.6-1.1 mg/dL\n\nIf you have elevated creatinine, your doctor will likely:\n- Order additional tests\n- Check your kidney function\n- Identify the underlying cause\n- Recommend appropriate treatment",
  "stopReason": "end_turn",
  "usage": {
    "inputTokens": 22,
    "outputTokens": 218
  },
  "modelId": "us.anthropic.claude-3-5-sonnet-20241022-v2:0",
  "metadata": null,
  "contentLength": 822,
  "complete": true,
  "maxTokensReached": false,
  "totalTokens": 240
}
```

---

## AWS Bedrock Health

### Clinical Insights
- **POST** `/api/aws/bedrock/health/clinical-insights`
- Generates clinical insights for provided lab values and patient context.
- Request:
```json
{
  "medicalData": {
    "creatinine": {"value": 135.0, "unit": "µmol/L", "status": "ABNORMAL"},
    "ammonia": {"value": 244.0, "unit": "µg/dL", "status": "CRITICAL"}
  },
  "patientContext": {
    "age": 45,
    "gender": "MALE",
    "medicalHistory": ["Diabetes Type 2"],
    "currentMedications": ["Metformin 1000mg"],
    "vitalSigns": {"bloodPressure": "140/90"},
    "additionalInfo": {}
  },
  "options": {
    "targetAudience": "PATIENT",
    "includeRiskAssessment": true,
    "includeActionPlan": true,
    "includeTrendAnalysis": false
  }
}
```
- Example `curl`:
```bash
curl --location 'http://15.206.148.124:8086/api/aws/bedrock/health/clinical-insights' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: {{token}}' \
  --data '{
    "medicalData": {
      "creatinine": { "value": 135.0, "unit": "µmol/L", "status": "ABNORMAL" },
      "ammonia": { "value": 244.0, "unit": "µg/dL", "status": "CRITICAL" }
    },
    "patientContext": {
      "age": 45,
      "gender": "MALE",
      "medicalHistory": ["Diabetes Type 2"],
      "currentMedications": ["Metformin 1000mg"],
      "vitalSigns": { "bloodPressure": "140/90" },
      "additionalInfo": {}
    },
    "options": {
      "targetAudience": "PATIENT",
      "includeRiskAssessment": true,
      "includeActionPlan": true,
      "includeTrendAnalysis": false
    }
  }'
```
- Success Response (sample):
```json
{
  "summary": "Elevated creatinine and critically high ammonia levels indicate potential kidney and liver dysfunction requiring immediate medical attention",
  "keyFindings": [
    {
      "id": "finding_1",
      "description": "Elevated creatinine level (135 µmol/L)",
      "category": "LAB_ABNORMALITY",
      "severity": "HIGH",
      "explanation": "Indicates reduced kidney function, possibly related to diabetes",
      "relatedTests": ["eGFR", "BUN"],
      "details": {"normalRange": "62-106 µmol/L", "percentageAboveNormal": "27%"},
      "critical": false
    },
    {
      "id": "finding_2",
      "description": "Critically elevated ammonia level (244 µg/dL)",
      "category": "LAB_ABNORMALITY",
      "severity": "CRITICAL",
      "explanation": "Suggests severe liver dysfunction or metabolic disorder",
      "relatedTests": ["liver function tests", "blood pH"],
      "details": {"normalRange": "15-45 µg/dL", "percentageAboveNormal": "442%"},
      "critical": true
    }
  ],
  "recommendations": [
    {
      "id": "rec_1",
      "category": "IMMEDIATE_ACTION",
      "priority": "URGENT",
      "recommendation": "Immediate emergency department evaluation",
      "rationale": "Critical ammonia level requires urgent medical intervention",
      "timeframe": "Immediate",
      "highPriority": true
    },
    {
      "id": "rec_2",
      "category": "DIAGNOSTIC_TESTS",
      "priority": "HIGH",
      "recommendation": "Complete liver function panel and kidney function tests",
      "rationale": "Evaluate extent of organ dysfunction",
      "timeframe": "Within 24 hours",
      "highPriority": true
    }
  ],
  "riskAssessment": {
    "riskScores": {
      "hepatic": {"category": "hepatic", "level": "CRITICAL", "score": 0.9, "explanation": "Severely elevated ammonia indicating liver dysfunction"},
      "renal": {"category": "renal", "level": "HIGH", "score": 0.7, "explanation": "Elevated creatinine indicating kidney dysfunction"},
      "metabolic": {"category": "metabolic", "level": "HIGH", "score": 0.7, "explanation": "Diabetes and organ dysfunction indicate metabolic derangement"}
    },
    "overallRiskLevel": "HIGH",
    "riskFactors": ["Diabetes Type 2", "Elevated blood pressure", "Kidney dysfunction", "Liver dysfunction"],
    "highRisk": true
  },
  "actionPlan": {
    "immediateActions": [
      {"action": "Go to nearest emergency department", "priority": "URGENT", "timeframe": "Immediate", "category": "MEDICAL_CARE"},
      {"action": "Bring current medication list", "priority": "HIGH", "timeframe": "Immediate", "category": "PREPARATION"}
    ],
    "shortTermActions": [
      {"action": "Complete recommended diagnostic tests", "priority": "HIGH", "timeframe": "24 hours", "category": "DIAGNOSTIC"}
    ],
    "longTermActions": [
      {"action": "Regular monitoring of kidney and liver function", "priority": "HIGH", "timeframe": "Ongoing", "category": "MONITORING"}
    ],
    "totalActions": 4
  },
  "confidence": 0.95,
  "criticalFindingsCount": 1,
  "urgentRecommendationsCount": 1
}
```

### Summary (Patient-Friendly)
- **POST** `/api/aws/bedrock/health/summary`
- Produces a brief narrative summary of key results.
- Request:
```json
{
  "medicalData": {
    "glucose": {"value": 7.2, "unit": "mmol/L", "status": "ELEVATED"}
  },
  "patientContext": {
    "age": 50,
    "gender": "FEMALE",
    "medicalHistory": [],
    "currentMedications": [],
    "vitalSigns": {},
    "additionalInfo": {}
  },
  "options": {
    "audience": "PATIENT",
    "length": "BRIEF",
    "format": "NARRATIVE"
  }
}
```
- Success Response: _Add sample response._
> Add response when available (not provided in sample).

### Risk Assessment (Cardiovascular)
- **POST** `/api/aws/bedrock/health/risk-assessment`
- Calculates risk based on labs, vitals, history, and lifestyle.
- Request:
```json
{
  "medicalData": {
    "cholesterol": {"value": 6.5, "unit": "mmol/L"},
    "bloodPressure": {"systolic": 145, "diastolic": 95},
    "glucose": {"value": 6.8, "unit": "mmol/L"}
  },
  "patientContext": {
    "age": 55,
    "gender": "MALE",
    "medicalHistory": ["Hypertension", "Prediabetes"],
    "currentMedications": ["Lisinopril 10mg"],
    "vitalSigns": {"weight": "92kg", "height": "175cm"},
    "additionalInfo": {"smokingStatus": "former", "exerciseFrequency": "sedentary"}
  },
  "options": {
    "focusAreas": ["CARDIOVASCULAR", "METABOLIC"],
    "includePreventionStrategies": true,
    "timeHorizon": 12
  }
}
```
- Example `curl`:
```bash
curl --location 'http://15.206.148.124:8086/api/aws/bedrock/health/risk-assessment' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: {{token}}' \
  --data '{
    "medicalData": {
      "cholesterol": { "value": 6.5, "unit": "mmol/L" },
      "bloodPressure": { "systolic": 145, "diastolic": 95 },
      "glucose": { "value": 6.8, "unit": "mmol/L" }
    },
    "patientContext": {
      "age": 55,
      "gender": "MALE",
      "medicalHistory": ["Hypertension", "Prediabetes"],
      "currentMedications": ["Lisinopril 10mg"],
      "vitalSigns": { "weight": "92kg", "height": "175cm" },
      "additionalInfo": { "smokingStatus": "former", "exerciseFrequency": "sedentary" }
    },
    "options": {
      "focusAreas": ["CARDIOVASCULAR", "METABOLIC"],
      "includePreventionStrategies": true,
      "timeHorizon": 12
    }
  }'
```
- Success Response (sample):
```json
{
  "riskAssessment": {
    "riskScores": {
      "cardiovascular": {
        "category": "cardiovascular",
        "level": "HIGH",
        "score": 0.75,
        "explanation": "Elevated BP (145/95), high cholesterol (6.5 mmol/L), and existing HTN indicate increased CVD risk. ASCVD risk score suggests elevated 10-year risk.",
        "elevated": true
      },
      "metabolic": {
        "category": "metabolic",
        "level": "MODERATE",
        "score": 0.6,
        "explanation": "Prediabetes with FBG 6.8 mmol/L, BMI ~30 kg/m2 indicating obesity, increasing risk for T2DM progression"
      },
      "renal": {
        "category": "renal",
        "level": "MODERATE",
        "score": 0.5,
        "explanation": "Hypertension and metabolic risk factors may impact renal function. Current ACEi therapy provides some protection."
      }
    },
    "overallRiskLevel": "HIGH",
    "riskFactors": ["Hypertension", "Hypercholesterolemia", "Prediabetes", "Obesity", "Male gender", "Age >55"],
    "highRisk": true
  },
  "preventionStrategies": [
    "Optimize BP control - consider increasing lisinopril or adding second agent",
    "Initiate statin therapy for primary prevention",
    "Lifestyle modification focusing on diet and exercise",
    "Regular glucose monitoring and diabetes prevention program",
    "Smoking cessation if applicable",
    "Regular cardiovascular risk assessment every 3-6 months"
  ],
  "overallAssessment": "Patient presents with multiple cardiovascular and metabolic risk factors requiring aggressive risk factor modification. Primary focus should be on BP control, lipid management, and diabetes prevention through both pharmacological and lifestyle interventions. Close monitoring recommended.",
  "elevatedRisk": true
}
```

### Recommendations
- **POST** `/api/aws/bedrock/health/recommendations`
- Generates lifestyle/medication/follow-up recommendations based on findings.
- Request:
```json
{
  "medicalFindings": {
    "finding_1": {"description": "Elevated creatinine", "severity": "MODERATE"},
    "finding_2": {"description": "High blood ammonia", "severity": "HIGH"}
  },
  "patientContext": {
    "age": 45,
    "gender": "MALE",
    "medicalHistory": ["Diabetes Type 2"],
    "currentMedications": ["Metformin"],
    "vitalSigns": {},
    "additionalInfo": {}
  },
  "options": {
    "includeLifestyle": true,
    "includeMedication": true,
    "includeFollowUp": true,
    "evidenceBasedOnly": true
  }
}
```
- Success Response: _Add sample response._
```json
{
  "title": "Understanding High Creatinine Levels: What You Need to Know",
  "content": "Creatinine is a waste product that comes from your normal muscle use. Healthy kidneys filter creatinine from your blood. When creatinine levels are high, it usually means your kidneys aren't working as well as they should. As a 45-year-old male, it's important to understand that men typically have higher creatinine levels than women because they usually have more muscle mass.",
  "keyTakeaways": [
    "High creatinine is often a sign that your kidneys need attention",
    "Regular exercise and a healthy diet can help maintain normal creatinine levels",
    "See your doctor if your creatinine levels are high to find out why"
  ],
  "faqs": [
    {"question": "What causes high creatinine levels?", "answer": "Common causes include dehydration, eating lots of meat, intense exercise, certain medications, or kidney problems."},
    {"question": "What are normal creatinine levels for men?", "answer": "For adult men, normal creatinine levels are usually between 0.7 to 1.3 milligrams per deciliter (mg/dL)."},
    {"question": "How can I lower my creatinine levels naturally?", "answer": "Drink plenty of water, eat less red meat, exercise moderately, and maintain a healthy weight. Always talk to your doctor before making major diet or lifestyle changes."},
    {"question": "When should I be concerned about high creatinine?", "answer": "See your doctor if you have high creatinine along with symptoms like fatigue, swelling in your legs, or changes in urination."}
  ],
  "additionalResources": [
    "National Kidney Foundation: www.kidney.org",
    "American Association of Kidney Patients: www.aakp.org",
    "MedlinePlus - Creatinine Blood Test: www.medlineplus.gov/lab-tests/creatinine-test"
  ],
  "contentLength": 378
}
```
> Add response when available (not provided in sample).

### Trend Analysis
- **POST** `/api/aws/bedrock/health/trend-analysis`
- Detects anomalies and forecasts trends in time-series labs.
- Request:
```json
{
  "timeSeriesData": [
    {"timestamp": "2024-01-01T00:00:00Z", "testName": "creatinine", "value": 100.0, "unit": "µmol/L", "metadata": {}},
    {"timestamp": "2024-02-01T00:00:00Z", "testName": "creatinine", "value": 110.0, "unit": "µmol/L", "metadata": {}},
    {"timestamp": "2024-03-01T00:00:00Z", "testName": "creatinine", "value": 135.0, "unit": "µmol/L", "metadata": {}}
  ],
  "patientContext": {
    "age": 45,
    "gender": "MALE",
    "medicalHistory": [],
    "currentMedications": [],
    "vitalSigns": {},
    "additionalInfo": {}
  },
  "options": {
    "includeAnomalyDetection": true,
    "includePredictions": true,
    "includeSeasonalAnalysis": false
  }
}
```
- Success Response: _Add sample response._
> Add response when available (not provided in sample).

### Educational Content
- **POST** `/api/aws/bedrock/health/educational-content`
- Produces patient-facing educational content for a topic.
- Request:
```json
{
  "topic": "high creatinine levels",
  "patientContext": {
    "age": 45,
    "gender": "MALE",
    "medicalHistory": [],
    "currentMedications": [],
    "vitalSigns": {},
    "additionalInfo": {}
  },
  "options": {
    "readingLevel": "SIMPLE",
    "format": "FAQ",
    "maxWords": 500,
    "includeActionItems": true
  }
}
```
- Success Response: _Add sample response._

---

## AWS Comprehend Medical

### Detect Entities
- **POST** `/api/aws/comprehend-medical/detect-entities`
- Identifies clinical entities; optionally returns PHI if requested.
- Request:
```json
{
  "text": "Patient has elevated serum creatinine (135 µmol/L) and high blood ammonia (244 µg/dL). Started on lactulose 30ml twice daily and rifaximin 550mg.",
  "detectPhi": false
}
```
- Example `curl`:
```bash
curl --location 'http://15.206.148.124:8086/api/aws/comprehend-medical/detect-entities' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: {{token}}' \
  --data '{
    "text": "Patient has elevated serum creatinine (135 µmol/L) and high blood ammonia (244 µg/dL). Started on lactulose 30ml twice daily and rifaximin 550mg.",
    "detectPhi": false
  }'
```
- Success Response (sample):
```json
{
  "entities": [
    {
      "id": 2,
      "text": "elevated serum creatinine",
      "category": "MEDICAL_CONDITION",
      "type": "DX_NAME",
      "score": 0.8617406487464905,
      "traits": [{"name": "SIGN", "score": 0.5296252369880676}]
    },
    {
      "id": 3,
      "text": "serum creatinine",
      "category": "TEST_TREATMENT_PROCEDURE",
      "type": "TEST_NAME",
      "score": 0.8435389995574951,
      "attributes": [
        {"type": "TEST_VALUE", "text": "elevated", "score": 0.7950295805931091},
        {"type": "TEST_VALUE", "text": "135", "score": 0.7950295805931091}
      ]
    },
    {
      "id": 6,
      "text": "high blood ammonia",
      "category": "MEDICAL_CONDITION",
      "type": "DX_NAME",
      "score": 0.8921527862548828,
      "traits": [{"name": "SIGN", "score": 0.5752319693565369}]
    },
    {
      "id": 9,
      "text": "lactulose",
      "category": "MEDICATION",
      "type": "GENERIC_NAME",
      "attributes": [
        {"type": "DOSAGE", "text": "30ml"},
        {"type": "FREQUENCY", "text": "twice daily"}
      ]
    },
    {
      "id": 12,
      "text": "rifaximin",
      "category": "MEDICATION",
      "type": "GENERIC_NAME",
      "attributes": [{"type": "DOSAGE", "text": "550mg"}]
    }
  ],
  "modelVersion": "3.0.0",
  "entityCount": 6
}
```

### Infer ICD-10 Codes
- **POST** `/api/aws/comprehend-medical/infer-icd10`
- Returns ICD-10 diagnosis codes inferred from text.
- Request:
```json
{
  "text": "Acute kidney injury with elevated creatinine and hepatic encephalopathy"
}
```
- Example `curl`:
```bash
curl --location 'http://15.206.148.124:8086/api/aws/comprehend-medical/infer-icd10' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: {{token}}' \
  --data '{
    "text": "Acute kidney injury with elevated creatinine and hepatic encephalopathy"
  }'
```
- Success Response (sample):
```json
{
  "icd10Codes": [
    {"code": "N17", "description": "Acute kidney failure", "score": 0.04785579442977905},
    {"code": "N17.9", "description": "Acute kidney failure, unspecified", "score": 0.047540951520204544},
    {"code": "K76.82", "description": "Hepatic encephalopathy", "score": 0.6258893013000488},
    {"code": "K72.90", "description": "Hepatic failure, unspecified without coma", "score": 0.19240356981754303},
    {"code": "K72.91", "description": "Hepatic failure, unspecified with coma", "score": 0.18742415308952332}
  ]
}
```

### Infer RxNorm Codes
- **POST** `/api/aws/comprehend-medical/infer-rxnorm`
- Returns RxNorm medication codes inferred from text.
- Request:
```json
{
  "text": "lactulose 30ml twice daily, rifaximin 550mg, metformin 1000mg"
}
```
- Example `curl`:
```bash
curl --location 'http://15.206.148.124:8086/api/aws/comprehend-medical/infer-rxnorm' \
  --header 'Content-Type: application/json' \
  --header 'X-API-Key: {{token}}' \
  --data '{
    "text": "lactulose 30ml twice daily, rifaximin 550mg, metformin 1000mg"
  }'
```
- Success Response (sample):
```json
{
  "rxNormCodes": [
    {"code": "6218", "description": "lactulose", "score": 0.04276184365153313},
    {"code": "856664", "description": "rifaximin 550 mg oral tablet", "score": 0.9494543075561523},
    {"code": "861004", "description": "metformin hydrochloride 1000 mg oral tablet", "score": 0.9376072287559509},
    {"code": "6809", "description": "metformin", "score": 0.8340877294540405}
  ]
}
```
