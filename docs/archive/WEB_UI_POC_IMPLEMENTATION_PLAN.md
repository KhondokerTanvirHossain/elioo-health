# MedScribe AI - Web UI PoC Implementation Plan

## Executive Summary

**Objective:** Create a simple, fast, and professional web UI to demonstrate the medical report processing workflow as a Proof of Concept.

**Technology Choice:** Single HTML file with Vanilla JavaScript + Tailwind CSS

**Implementation Time:** ~4.5 hours

**Deployment:** Zero-config - drop file in `src/main/resources/static/`, Spring Boot serves it automatically

---

## User Preferences (Selected Options)

### 1. Visual Style
**Selected: Option A - Clean Medical Dashboard**
- Color scheme: Blue/White professional
- Medical-grade appearance
- Trust-inspiring design
- Clear hierarchy and readability

### 2. Results Display Layout
**Selected: Option B - Accordion**
- All results on one scrollable page
- Expand/collapse each section independently
- Good for overview and detailed inspection
- Easy to see all available data at a glance

### 3. Testing Convenience
**Selected: Yes - Include "Try Sample Report" Button**
- Pre-loaded test data for quick demo
- Sample medical report image included
- One-click demonstration capability
- Reduces barrier for stakeholders to try the system

### 4. Image Upload Options
**Selected: File Upload + Drag-and-Drop**
- Traditional file browser button
- Modern drag-and-drop zone
- Visual feedback on hover
- File type validation (images only)

---

## Technical Architecture

### File Structure
```
medscribe-ai/
├── docs/
│   └── WEB_UI_POC_IMPLEMENTATION_PLAN.md (this file)
├── src/main/resources/static/
│   ├── index.html                        (Main UI - single file)
│   └── assets/
│       └── sample-blood-test.jpg         (Sample medical report for testing)
```

### Technology Stack
- **HTML5** - Semantic markup
- **Tailwind CSS** (via CDN) - Utility-first CSS framework
- **Vanilla JavaScript** - No frameworks, pure ES6+
- **Fetch API** - HTTP requests to backend
- **Spring Boot Static Resources** - Automatic serving from `/static/`

---

## UI Design Specification

### Color Palette (Clean Medical Dashboard)
```css
Primary Blue:     #2563EB (blue-600)
Light Blue:       #DBEAFE (blue-100)
Success Green:    #10B981 (green-500)
Warning Yellow:   #F59E0B (amber-500)
Danger Red:       #EF4444 (red-500)
Background Gray:  #F9FAFB (gray-50)
White:            #FFFFFF
Text Dark:        #1F2937 (gray-800)
Text Light:       #6B7280 (gray-500)
Border:           #E5E7EB (gray-200)
```

### Layout Structure

```
┌─────────────────────────────────────────────────────────────┐
│  Header (Blue - bg-blue-600)                                │
│  MedScribe AI - Medical Report Analysis                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Section 1: Upload Form (White card with shadow)           │
│  ┌──────────────────┐  ┌────────────────────────────────┐ │
│  │ Drag & Drop Zone │  │ Patient Context Form           │ │
│  │                  │  │ - Patient ID [P12345]          │ │
│  │ [📁 Browse]      │  │ - Age [59]  Gender [Male ▼]   │ │
│  │                  │  │ - Medical History              │ │
│  │ sample-test.jpg  │  │   [Diabetes Type 2, ...]       │ │
│  │ 2.3 MB           │  │ - Current Medications          │ │
│  └──────────────────┘  │   [Metformin 1000mg, ...]      │ │
│                        │                                 │ │
│  [Use Sample Data]     │ [⚙️ Workflow Options]          │ │
│                        └────────────────────────────────┘ │
│                 [Process Report 🚀]                        │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Section 2: Processing Progress (Shown during processing)  │
│  ┌────────────────────────────────────────────────────────┐│
│  │ ✅ Validation Complete                                  ││
│  │ ✅ OCR Processing Complete (294 blocks extracted)       ││
│  │ 🔄 Entity Detection in progress...                      ││
│  │ ⏳ Medical Codes (Pending)                              ││
│  │ ⏳ Clinical Insights (Pending)                          ││
│  │                                                         ││
│  │ [Progress Bar: 60% ████████████░░░░░░░░░]             ││
│  └────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Section 3: Results Dashboard (Accordion layout)           │
│  ┌────────────────────────────────────────────────────────┐│
│  │ ▼ OCR Results (Confidence: 96%) [Expanded]             ││
│  │ ┌──────────────────────────────────────────────────┐   ││
│  │ │ Test Name         Value   Unit      Range        │   ││
│  │ │ Serum Creatinine  135.0   µmol/L    59-104 🔴   │   ││
│  │ │ Sodium            138.0   mmol/L    136-145 🟢  │   ││
│  │ │ Potassium         4.2     mmol/L    3.5-5.0 🟢  │   ││
│  │ └──────────────────────────────────────────────────┘   ││
│  └────────────────────────────────────────────────────────┘│
│  ┌────────────────────────────────────────────────────────┐│
│  │ ▶ Classification Results (12 entities) [Collapsed]     ││
│  └────────────────────────────────────────────────────────┘│
│  ┌────────────────────────────────────────────────────────┐│
│  │ ▶ ICD-10 Codes (2 codes) [Collapsed]                   ││
│  └────────────────────────────────────────────────────────┘│
│  ┌────────────────────────────────────────────────────────┐│
│  │ ▶ RxNorm Codes (0 codes) [Collapsed]                   ││
│  └────────────────────────────────────────────────────────┘│
│  ┌────────────────────────────────────────────────────────┐│
│  │ ▶ Risk Assessment (Moderate Risk) [Collapsed]          ││
│  └────────────────────────────────────────────────────────┘│
│  ┌────────────────────────────────────────────────────────┐│
│  │ ▶ Recommendations (5 items) [Collapsed]                ││
│  └────────────────────────────────────────────────────────┘│
│  ┌────────────────────────────────────────────────────────┐│
│  │ ▶ Clinical Insights [Collapsed]                        ││
│  └────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

---

## API Integration Flow

### Step 1: Submit Medical Report for Processing

**Endpoint:** `POST /api/v1/medical-report/process`

**Request Body:**
```json
{
  "imageBase64": "iVBORw0KGgoAAAANSUhEUgAA...",
  "patientContext": {
    "patientId": "P12345",
    "age": 59,
    "gender": "MALE",
    "medicalHistory": ["Diabetes Type 2", "Hypertension"],
    "currentMedications": ["Metformin 1000mg", "Lisinopril 10mg"],
    "allergies": ["Penicillin"],
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
    "riskAssessmentCategories": ["CARDIOVASCULAR", "METABOLIC", "RENAL", "HEPATIC"],
    "targetAudience": "PATIENT",
    "language": "en",
    "confidenceThreshold": 0.70,
    "includeActionPlan": true,
    "includeTrendAnalysis": false
  }
}
```

**Response:**
```json
{
  "reportId": "RPT-D6FD6DD2",
  "status": "PROCESSING",
  "message": "Medical report processing started",
  "estimatedCompletionTime": "2024-01-15T10:35:00Z"
}
```

### Step 2: Poll Processing Status

**Endpoint:** `GET /api/v1/medical-report/query/status/{reportId}`

**Response (In Progress):**
```json
{
  "reportId": "RPT-D6FD6DD2",
  "patientId": "P12345",
  "status": "PROCESSING",
  "createdAt": "2024-01-15T10:30:00Z",
  "completedAt": null,
  "processingTimeMs": 15000,
  "completedStages": 2,
  "failedStages": 0,
  "totalStages": 5,
  "errorMessage": null
}
```

**Response (Completed):**
```json
{
  "reportId": "RPT-D6FD6DD2",
  "patientId": "P12345",
  "status": "COMPLETED",
  "createdAt": "2024-01-15T10:30:00Z",
  "completedAt": "2024-01-15T10:32:30Z",
  "processingTimeMs": 150000,
  "completedStages": 5,
  "failedStages": 0,
  "totalStages": 5,
  "errorMessage": null
}
```

### Step 3: Fetch All Results (Once Processing Complete)

**Endpoints:**
1. `GET /api/v1/medical-report/query/results/{reportId}/ocr`
2. `GET /api/v1/medical-report/query/results/{reportId}/classification`
3. `GET /api/v1/medical-report/query/results/{reportId}/icd10`
4. `GET /api/v1/medical-report/query/results/{reportId}/rxnorm`
5. `GET /api/v1/medical-report/query/results/{reportId}/risk-assessment`
6. `GET /api/v1/medical-report/query/results/{reportId}/recommendations`
7. `GET /api/v1/medical-report/query/results/{reportId}/insights`

---

## JavaScript Implementation Details

### Core Functions

#### 1. File Upload & Base64 Conversion
```javascript
async function handleFileUpload(file) {
  // Validate file type
  if (!file.type.startsWith('image/')) {
    showError('Please upload an image file');
    return;
  }

  // Validate file size (max 10MB)
  if (file.size > 10 * 1024 * 1024) {
    showError('File size must be less than 10MB');
    return;
  }

  // Convert to base64
  const base64 = await fileToBase64(file);
  return base64;
}

function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const base64String = reader.result.split(',')[1];
      resolve(base64String);
    };
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}
```

#### 2. Process Report
```javascript
async function processReport(imageBase64, patientContext, workflowOptions) {
  try {
    showProgressSection();

    const response = await fetch('/api/v1/medical-report/process', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        imageBase64,
        patientContext,
        workflowOptions
      })
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const data = await response.json();
    return data.reportId;
  } catch (error) {
    showError('Failed to process report: ' + error.message);
    throw error;
  }
}
```

#### 3. Poll Processing Status
```javascript
async function pollProcessingStatus(reportId) {
  const maxAttempts = 60; // 5 minutes (5-second intervals)
  let attempts = 0;

  while (attempts < maxAttempts) {
    try {
      const response = await fetch(
        `/api/v1/medical-report/query/status/${reportId}`
      );
      const status = await response.json();

      updateProgressDisplay(status);

      if (status.status === 'COMPLETED') {
        return true;
      } else if (status.status === 'FAILED') {
        showError(status.errorMessage || 'Processing failed');
        return false;
      }

      // Wait 5 seconds before next poll
      await sleep(5000);
      attempts++;
    } catch (error) {
      console.error('Polling error:', error);
      attempts++;
    }
  }

  showError('Processing timeout - please try again');
  return false;
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}
```

#### 4. Fetch All Results
```javascript
async function fetchAllResults(reportId) {
  const endpoints = [
    { key: 'ocr', url: `/api/v1/medical-report/query/results/${reportId}/ocr` },
    { key: 'classification', url: `/api/v1/medical-report/query/results/${reportId}/classification` },
    { key: 'icd10', url: `/api/v1/medical-report/query/results/${reportId}/icd10` },
    { key: 'rxnorm', url: `/api/v1/medical-report/query/results/${reportId}/rxnorm` },
    { key: 'riskAssessment', url: `/api/v1/medical-report/query/results/${reportId}/risk-assessment` },
    { key: 'recommendations', url: `/api/v1/medical-report/query/results/${reportId}/recommendations` },
    { key: 'insights', url: `/api/v1/medical-report/query/results/${reportId}/insights` }
  ];

  const results = {};

  for (const endpoint of endpoints) {
    try {
      const response = await fetch(endpoint.url);
      if (response.ok) {
        results[endpoint.key] = await response.json();
      } else {
        results[endpoint.key] = null;
      }
    } catch (error) {
      console.error(`Error fetching ${endpoint.key}:`, error);
      results[endpoint.key] = null;
    }
  }

  return results;
}
```

#### 5. Render Results (Accordion)
```javascript
function renderResults(results) {
  const container = document.getElementById('results-container');

  container.innerHTML = `
    ${renderOcrAccordion(results.ocr)}
    ${renderClassificationAccordion(results.classification)}
    ${renderIcd10Accordion(results.icd10)}
    ${renderRxNormAccordion(results.rxnorm)}
    ${renderRiskAssessmentAccordion(results.riskAssessment)}
    ${renderRecommendationsAccordion(results.recommendations)}
    ${renderInsightsAccordion(results.insights)}
  `;

  // Add click handlers for accordion toggles
  setupAccordionHandlers();

  // Show results section
  document.getElementById('results-section').classList.remove('hidden');
}

function renderOcrAccordion(ocrData) {
  if (!ocrData || !ocrData.resultData) return '';

  const extractedData = ocrData.resultData.extractedData || [];
  const confidence = (ocrData.confidenceScore * 100).toFixed(0);

  return `
    <div class="accordion-item mb-4 border rounded-lg">
      <button class="accordion-header w-full text-left p-4 bg-white hover:bg-gray-50 flex justify-between items-center"
              onclick="toggleAccordion('ocr')">
        <div>
          <span class="text-lg font-semibold">OCR Results</span>
          <span class="ml-2 text-sm text-gray-500">(Confidence: ${confidence}%)</span>
          <span class="ml-2 px-2 py-1 bg-blue-100 text-blue-800 text-xs rounded">${extractedData.length} tests</span>
        </div>
        <svg id="ocr-icon" class="w-5 h-5 transform transition-transform" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7"></path>
        </svg>
      </button>
      <div id="ocr-content" class="accordion-content hidden p-4 bg-gray-50">
        <table class="w-full">
          <thead>
            <tr class="border-b">
              <th class="text-left p-2">Test Name</th>
              <th class="text-left p-2">Value</th>
              <th class="text-left p-2">Unit</th>
              <th class="text-left p-2">Reference Range</th>
              <th class="text-left p-2">Status</th>
            </tr>
          </thead>
          <tbody>
            ${extractedData.map(test => `
              <tr class="border-b">
                <td class="p-2 font-medium">${test.testName}</td>
                <td class="p-2">${test.testValue}</td>
                <td class="p-2 text-sm text-gray-600">${test.unit}</td>
                <td class="p-2 text-sm text-gray-600">${test.referenceRange}</td>
                <td class="p-2">${getStatusBadge(test.status)}</td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>
    </div>
  `;
}

function getStatusBadge(status) {
  const badges = {
    'NORMAL': '<span class="px-2 py-1 bg-green-100 text-green-800 text-xs rounded">Normal</span>',
    'ABNORMAL': '<span class="px-2 py-1 bg-yellow-100 text-yellow-800 text-xs rounded">Abnormal</span>',
    'CRITICAL': '<span class="px-2 py-1 bg-red-100 text-red-800 text-xs rounded">Critical</span>'
  };
  return badges[status] || badges['NORMAL'];
}
```

---

## Sample Data Configuration

### Pre-filled Patient Context
```javascript
const SAMPLE_PATIENT_DATA = {
  patientId: "P12345",
  age: 59,
  gender: "MALE",
  medicalHistory: ["Diabetes Type 2", "Hypertension"],
  currentMedications: [
    "Metformin 1000mg twice daily",
    "Lisinopril 10mg once daily"
  ],
  allergies: ["Penicillin"],
  vitalSigns: {
    bloodPressure: "140/90",
    heartRate: 78,
    weight: "85kg",
    height: "175cm"
  },
  lifestyle: {
    smokingStatus: "never",
    alcoholUse: "occasional",
    exerciseFrequency: "moderate"
  }
};

const SAMPLE_WORKFLOW_OPTIONS = {
  skipValidation: false,
  includeRawText: true,
  includeEntityRelationships: true,
  requestedCodeSystems: ["ICD10", "RXNORM"],
  includeEducationalContent: true,
  riskAssessmentCategories: ["CARDIOVASCULAR", "METABOLIC", "RENAL", "HEPATIC"],
  targetAudience: "PATIENT",
  language: "en",
  confidenceThreshold: 0.70,
  includeActionPlan: true,
  includeTrendAnalysis: false
};
```

---

## Implementation Steps

### Phase 1: HTML Structure (30 minutes)
1. ✅ Create `index.html` in `src/main/resources/static/`
2. ✅ Add Tailwind CSS via CDN
3. ✅ Create header with title
4. ✅ Create 3 main sections with proper IDs
5. ✅ Add basic responsive container

### Phase 2: Upload Form (45 minutes)
1. ✅ Create drag-and-drop zone with visual feedback
2. ✅ Add file input (hidden, triggered by button)
3. ✅ Create patient context form fields
4. ✅ Add "Use Sample Data" button
5. ✅ Create collapsible workflow options section
6. ✅ Add form validation
7. ✅ Style with Tailwind utilities

### Phase 3: Progress Section (30 minutes)
1. ✅ Create stage indicators list
2. ✅ Add progress bar component
3. ✅ Implement polling mechanism
4. ✅ Add status icons (✅, 🔄, ⏳)
5. ✅ Show/hide based on processing state

### Phase 4: Results Display (1.5 hours)
1. ✅ Implement accordion component
2. ✅ Render OCR results table with color-coded status
3. ✅ Display classification entities
4. ✅ Show ICD-10/RxNorm codes with descriptions
5. ✅ Format risk assessment with badges
6. ✅ Display categorized recommendations
7. ✅ Show clinical insights with formatting
8. ✅ Add expand/collapse functionality

### Phase 5: API Integration (1 hour)
1. ✅ Implement file-to-base64 conversion
2. ✅ Create `processReport()` function
3. ✅ Implement status polling with timeout
4. ✅ Create `fetchAllResults()` function
5. ✅ Add comprehensive error handling
6. ✅ Implement loading states

### Phase 6: Polish & Testing (30 minutes)
1. ✅ Add loading spinners
2. ✅ Improve error messages with toast notifications
3. ✅ Add responsive design for mobile
4. ✅ Test with sample data
5. ✅ Add keyboard accessibility
6. ✅ Test all API endpoints

**Total Time: ~4.5 hours**

---

## Deployment Instructions

### Step 1: Place Files
```bash
# Copy index.html to static resources
cp index.html medscribe-ai/src/main/resources/static/

# Copy sample image (if available)
cp sample-blood-test.jpg medscribe-ai/src/main/resources/static/assets/
```

### Step 2: Build Application
```bash
cd medscribe-ai
./gradlew clean build
```

### Step 3: Run Application
```bash
./gradlew bootRun
```

### Step 4: Access UI
```
Open browser: http://localhost:8086/index.html
```

---

## Testing Checklist

### Functional Testing
- [ ] File upload via button works
- [ ] Drag-and-drop file upload works
- [ ] "Use Sample Data" button pre-fills form
- [ ] Form validation catches invalid inputs
- [ ] API call to `/process` endpoint succeeds
- [ ] Status polling updates progress section
- [ ] All 7 result endpoints fetch successfully
- [ ] Accordion expand/collapse works
- [ ] Test result status colors display correctly
- [ ] Error handling shows appropriate messages

### UI/UX Testing
- [ ] Professional medical appearance
- [ ] Responsive on mobile devices
- [ ] Loading states show during API calls
- [ ] Progress indicators update in real-time
- [ ] Hover effects work on interactive elements
- [ ] Text is readable at all screen sizes
- [ ] Colors meet accessibility contrast ratios
- [ ] Keyboard navigation works (Tab, Enter)

### Browser Compatibility
- [ ] Chrome (latest)
- [ ] Firefox (latest)
- [ ] Safari (latest)
- [ ] Edge (latest)

---

## Future Enhancements (Post-PoC)

1. **Export Results**
   - Download as PDF report
   - Export to JSON/CSV

2. **Comparison View**
   - Compare multiple reports side-by-side
   - Show trends over time

3. **Advanced Filters**
   - Filter test results by status
   - Search functionality

4. **Real-time Updates**
   - WebSocket connection for live progress
   - Server-sent events for notifications

5. **User Authentication**
   - Login/logout functionality
   - Role-based access control

6. **Multi-language Support**
   - i18n for UI labels
   - Support for Bengali medical reports

---

## Troubleshooting

### Issue: Static files not served
**Solution:** Ensure files are in `src/main/resources/static/` and Spring Boot is running

### Issue: CORS errors
**Solution:** Add CORS configuration if frontend served from different port
```java
@Configuration
public class WebConfig {
    @Bean
    public WebFluxConfigurer corsConfigurer() {
        return new WebFluxConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                    .allowedOrigins("http://localhost:8086")
                    .allowedMethods("GET", "POST", "PUT", "DELETE");
            }
        };
    }
}
```

### Issue: Base64 encoding too large
**Solution:** Check file size before upload, compress images if needed

### Issue: Polling timeout
**Solution:** Increase `maxAttempts` in polling function or optimize backend processing

---

## Success Metrics

### PoC Objectives
✅ Demonstrate end-to-end workflow (upload → process → results)
✅ Professional, trustworthy medical appearance
✅ Fast implementation (< 5 hours)
✅ Zero deployment complexity
✅ Easy for stakeholders to test

### Demo Script
1. Open `http://localhost:8086/index.html`
2. Click "Use Sample Data" button
3. Upload medical report image (or use pre-loaded sample)
4. Click "Process Report"
5. Watch real-time progress updates
6. Explore results in accordion sections
7. Highlight key findings (abnormal tests, risk assessment, recommendations)

---

## Contact & Support

**Developer:** Claude Code
**Date Created:** 2024-01-15
**Last Updated:** 2024-01-15
**Version:** 1.0 (PoC)

For issues or questions, please refer to the main project README or create an issue in the project repository.
