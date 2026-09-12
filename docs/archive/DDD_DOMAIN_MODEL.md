# Domain-Driven Design (DDD) - MedScribe AI Domain Model

## Table of Contents
1. [Domain Overview](#domain-overview)
2. [Bounded Contexts](#bounded-contexts)
3. [Context Map](#context-map)
4. [Aggregates and Entities](#aggregates-and-entities)
5. [Value Objects](#value-objects)
6. [Domain Events](#domain-events)
7. [Domain Services](#domain-services)
8. [Ubiquitous Language](#ubiquitous-language)

---

## Domain Overview

MedScribe AI is a **medical document intelligence platform** that transforms unstructured medical documents into actionable clinical insights. The core domain revolves around four key capabilities:

1. **Document Digitization** - Extract text from medical documents
2. **Clinical Understanding** - Classify and map medical terminology
3. **Historical Visualization** - Display chronological medical history
4. **Intelligent Insights** - Summarize and provide clinical recommendations

### Business Context

**Primary Users:**
- **Healthcare Providers**: Doctors, nurses, specialists who need quick access to patient information
- **Patients**: Individuals managing their health records and understanding their medical journey

**Core Business Value:**
- Eliminate manual data entry errors
- Accelerate clinical decision-making
- Improve patient engagement and health literacy
- Enable data-driven healthcare insights

---

## Bounded Contexts

Based on the business requirements, we identify **5 Bounded Contexts**:

### 1. Document Processing Context
**Responsibility**: Extract and digitize medical documents

**Core Concepts:**
- Medical Document (Prescription, Lab Report, Radiology Report, Discharge Summary, etc.)
- OCR Processing
- Text Extraction
- Document Classification

**Business Rules:**
- Documents must be validated before processing
- OCR confidence must meet minimum threshold (>70%)
- Support for multiple languages (English, Bangla)
- Handle multiple document formats (PDF, JPG, PNG)

**Key Aggregates:**
- `MedicalDocument` (Aggregate Root)

---

### 2. Clinical Data Context
**Responsibility**: Understand, classify, and map clinical terminology

**Core Concepts:**
- Clinical Entity (Medication, Condition, Lab Result, Vital Sign, Diagnosis)
- Medical Coding (ICD-10, LOINC, SNOMED CT, RxNorm, CPT)
- Clinical Classification
- Entity Relationships

**Business Rules:**
- All clinical terms must be validated against medical ontologies
- Multiple codes can map to a single entity
- Confidence scores must be tracked for all classifications
- Support physician-level comprehension of medical terminology

**Key Aggregates:**
- `ClinicalRecord` (Aggregate Root)
- `MedicalEntity` (Entity within aggregate)

---

### 3. Patient Timeline Context
**Responsibility**: Organize and visualize patient's medical history chronologically

**Core Concepts:**
- Medical Event (Lab Result, Visit, Diagnosis, Prescription, Procedure)
- Timeline
- Abnormal Value Detection
- Filtering and Search

**Business Rules:**
- Events must be ordered chronologically
- Abnormal values must be flagged based on reference ranges
- Critical values require immediate highlighting
- Support filtering by: date range, test type, doctor, condition, abnormal status
- Track data source provenance

**Key Aggregates:**
- `PatientTimeline` (Aggregate Root)
- `MedicalEvent` (Entity within aggregate)

---

### 4. Clinical Intelligence Context
**Responsibility**: Generate insights, summaries, and recommendations

**Core Concepts:**
- Clinical Summary
- Health Insights
- Trend Analysis
- Risk Assessment
- Clinical Recommendations

**Business Rules:**
- Summaries must be evidence-based
- Risk assessments must include severity levels
- Recommendations must be categorized (immediate action, monitoring, lifestyle)
- Support both provider and patient language levels
- Include confidence scores for AI-generated content

**Key Aggregates:**
- `ClinicalInsight` (Aggregate Root)
- `HealthSummary` (Aggregate Root)

---

### 5. Patient Context (Shared Kernel)
**Responsibility**: Manage patient identity and demographics

**Core Concepts:**
- Patient Identity
- Demographics
- Medical History
- Consent and Authorization

**Business Rules:**
- Patient identity must be unique and verified
- PHI (Protected Health Information) must be encrypted
- Audit trail for all data access
- HIPAA compliance mandatory

**Key Aggregates:**
- `Patient` (Aggregate Root)

---

## Context Map

### Relationship Between Bounded Contexts

```
┌──────────────────────────┐
│   Patient Context        │
│   (Shared Kernel)        │
└────────┬─────────────────┘
         │
         │ shares patient identity
         │
         ├──────────────────────────────────────────────┐
         │                                               │
         ▼                                               ▼
┌─────────────────────┐                      ┌──────────────────────┐
│ Document Processing │ ───(downstream)────> │  Clinical Data       │
│      Context        │   publishes events   │     Context          │
└─────────────────────┘                      └──────────┬───────────┘
         │                                               │
         │                                               │
         │ publishes                              publishes
         │ document events                         clinical events
         │                                               │
         ▼                                               ▼
┌─────────────────────┐                      ┌──────────────────────┐
│ Patient Timeline    │ <──(anti-corruption) │ Clinical Intelligence│
│     Context         │     layer            │      Context         │
└─────────────────────┘                      └──────────────────────┘
         ▲                                               ▲
         │                                               │
         └───────────────────────┬───────────────────────┘
                     consumes clinical data
```

**Integration Patterns:**

1. **Document Processing → Clinical Data**: **Customer-Supplier** (Downstream)
   - Document Processing publishes `DocumentExtracted` events
   - Clinical Data consumes and classifies extracted text

2. **Clinical Data → Patient Timeline**: **Published Language**
   - Clinical Data publishes structured medical entities
   - Patient Timeline subscribes and builds chronological view

3. **Clinical Data → Clinical Intelligence**: **Anti-Corruption Layer**
   - Clinical Intelligence translates clinical entities into insights
   - Uses domain translation layer to maintain independence

4. **Patient Context**: **Shared Kernel**
   - All contexts depend on patient identity
   - Shared patient demographics and consent data

---

## Aggregates and Entities

### 1. Document Processing Context

#### Aggregate: MedicalDocument (Root)

**Purpose**: Represents a medical document going through the digitization process

```
MedicalDocument (Aggregate Root)
├── DocumentId (Identity)
├── PatientId (Foreign Reference)
├── DocumentType (Value Object)
├── DocumentMetadata (Value Object)
├── ProcessingStatus (Value Object)
├── OcrResult (Entity)
│   ├── ExtractedText
│   ├── Confidence
│   ├── Language
│   └── ProcessedAt
└── DocumentImage (Value Object)
```

**Invariants:**
- DocumentId must be unique
- PatientId must be valid and exist
- DocumentType must be one of the supported types
- OCR Confidence must be between 0.0 and 1.0
- ProcessingStatus transitions: UPLOADED → PROCESSING → COMPLETED/FAILED

**Entity: OcrResult**
```java
class OcrResult {
    String extractedText;
    Double confidence;
    Language language;
    Instant processedAt;
    List<ExtractedField> fields;
}
```

**Value Objects:**
- `DocumentType`: PRESCRIPTION, LAB_REPORT, RADIOLOGY_REPORT, DISCHARGE_SUMMARY, OPERATION_NOTE
- `ProcessingStatus`: UPLOADED, PROCESSING, COMPLETED, FAILED
- `DocumentMetadata`: createdBy, createdAt, documentDate, source

---

### 2. Clinical Data Context

#### Aggregate: ClinicalRecord (Root)

**Purpose**: Contains all classified medical entities from a document

```
ClinicalRecord (Aggregate Root)
├── RecordId (Identity)
├── DocumentId (Foreign Reference)
├── PatientId (Foreign Reference)
├── ClassificationStatus (Value Object)
├── MedicalEntities (Collection of Entities)
│   ├── MedicalEntity
│   │   ├── EntityId
│   │   ├── EntityType (MEDICATION, CONDITION, LAB_RESULT, VITAL_SIGN, etc.)
│   │   ├── EntityText
│   │   ├── Confidence
│   │   ├── MedicalCodes (Value Object Collection)
│   │   │   ├── ICD10Code
│   │   │   ├── LOINCCode
│   │   │   ├── SNOMEDCode
│   │   │   └── RxNormCode
│   │   └── Attributes (Collection)
│   │       ├── EntityAttribute
│   │       │   ├── Type (TEST_VALUE, DOSAGE, FREQUENCY, etc.)
│   │       │   ├── Value
│   │       │   └── Unit
├── ClassifiedAt (Timestamp)
└── ClassificationMetadata (Value Object)
```

**Invariants:**
- RecordId must be unique
- Each MedicalEntity must have at least one MedicalCode
- EntityType must be recognized medical category
- Confidence score must be between 0.0 and 1.0
- Attributes must be valid for their EntityType

**Entity: MedicalEntity**
```java
class MedicalEntity {
    EntityId entityId;
    EntityType type;
    String text;
    Double confidence;
    List<MedicalCode> codes;
    List<EntityAttribute> attributes;

    void addCode(MedicalCode code);
    void addAttribute(EntityAttribute attr);
    boolean isHighConfidence(); // confidence > 0.85
}
```

**Value Objects:**
- `EntityType`: MEDICATION, CONDITION, LAB_RESULT, VITAL_SIGN, DIAGNOSIS, PROCEDURE, SYMPTOM, ALLERGY
- `MedicalCode`: codeSystem, code, display, version
- `EntityAttribute`: type, value, unit, relationshipType

---

### 3. Patient Timeline Context

#### Aggregate: PatientTimeline (Root)

**Purpose**: Chronological view of patient's medical history

```
PatientTimeline (Aggregate Root)
├── TimelineId (Identity)
├── PatientId (Foreign Reference)
├── MedicalEvents (Ordered Collection of Entities)
│   ├── MedicalEvent
│   │   ├── EventId
│   │   ├── EventType (Value Object)
│   │   ├── EventDate (Timestamp)
│   │   ├── EventSource (Value Object)
│   │   ├── ClinicalData (Value Object)
│   │   │   ├── TestName
│   │   │   ├── TestValue
│   │   │   ├── Unit
│   │   │   ├── ReferenceRange
│   │   │   └── Status (NORMAL, ABNORMAL, CRITICAL)
│   │   ├── Provider (Value Object)
│   │   └── Metadata (Value Object)
├── TimelineStatistics (Value Object)
│   ├── TotalEvents
│   ├── AbnormalEventsCount
│   ├── CriticalEventsCount
│   └── DateRange
└── LastUpdated (Timestamp)
```

**Invariants:**
- TimelineId must be unique per patient
- Events must be ordered chronologically (ascending by eventDate)
- EventDate cannot be in the future
- Status determination must be based on reference ranges
- No duplicate events (same eventType, date, and value)

**Entity: MedicalEvent**
```java
class MedicalEvent {
    EventId eventId;
    EventType eventType;
    Instant eventDate;
    EventSource source;
    ClinicalData data;
    Provider provider;

    boolean isAbnormal();
    boolean isCritical();
    boolean matchesFilter(TimelineFilter filter);
}
```

**Business Methods:**
- `addEvent(MedicalEvent event)`: Add event maintaining chronological order
- `filterEvents(TimelineFilter filter)`: Filter by date, type, status
- `getAbnormalEvents()`: Get all abnormal/critical events
- `getEventsByDateRange(start, end)`: Date range query
- `getEventsByType(EventType type)`: Filter by event type

**Value Objects:**
- `EventType`: LAB_RESULT, VITAL_SIGN, DIAGNOSIS, PRESCRIPTION, PROCEDURE, VISIT, IMAGING
- `EventSource`: documentId, sourceSystem, recordedBy
- `ClinicalData`: testName, value, unit, referenceRange, status
- `Provider`: providerId, name, specialty

---

### 4. Clinical Intelligence Context

#### Aggregate: ClinicalInsight (Root)

**Purpose**: AI-generated clinical insights and recommendations

```
ClinicalInsight (Aggregate Root)
├── InsightId (Identity)
├── PatientId (Foreign Reference)
├── RecordId (Foreign Reference to ClinicalRecord)
├── GeneratedAt (Timestamp)
├── InsightType (Value Object)
├── Summary (Value Object)
│   ├── PatientFriendlySummary
│   ├── ClinicalSummary
│   └── KeyHighlights
├── KeyFindings (Collection of Value Objects)
│   ├── Finding
│   │   ├── FindingText
│   │   ├── Severity (LOW, MODERATE, HIGH, CRITICAL)
│   │   ├── Interpretation
│   │   ├── ClinicalEvidence
│   │   └── NormalRange
├── Recommendations (Collection of Value Objects)
│   ├── Recommendation
│   │   ├── Category (IMMEDIATE_ACTION, DIAGNOSTIC_TESTS, MEDICATION, MONITORING, LIFESTYLE)
│   │   ├── Priority (LOW, MEDIUM, HIGH)
│   │   ├── RecommendationText
│   │   └── Rationale
├── RiskAssessment (Value Object)
│   ├── RiskLevel (LOW, MODERATE, HIGH, CRITICAL)
│   ├── RequiresImmediateAttention (Boolean)
│   └── RiskFactors (List)
└── Confidence (Value Object)
    ├── OverallConfidence (0.0-1.0)
    └── MethodologyUsed (AI_MODEL, RULE_BASED, HYBRID)
```

**Invariants:**
- InsightId must be unique
- Summary must exist for both patient and provider audiences
- Severity levels must align with clinical standards
- Recommendations must have rationale
- Confidence score must be between 0.0 and 1.0
- High-risk findings must have HIGH or CRITICAL severity

**Business Methods:**
- `generateSummary(ClinicalRecord record, PatientContext context)`: Create summary
- `assessRisk()`: Calculate risk level from findings
- `prioritizeRecommendations()`: Order by priority and urgency
- `translateForPatient()`: Convert clinical language to patient-friendly
- `requiresUrgentAction()`: Check if immediate attention needed

**Value Objects:**
- `InsightType`: DIAGNOSTIC_INSIGHT, TREND_ANALYSIS, RISK_ASSESSMENT, TREATMENT_SUGGESTION
- `Severity`: LOW, MODERATE, HIGH, CRITICAL
- `RecommendationCategory`: IMMEDIATE_ACTION, DIAGNOSTIC_TESTS, MEDICATION, MONITORING, LIFESTYLE
- `Priority`: LOW, MEDIUM, HIGH

---

#### Aggregate: HealthSummary (Root)

**Purpose**: Comprehensive patient health summary over time

```
HealthSummary (Aggregate Root)
├── SummaryId (Identity)
├── PatientId (Foreign Reference)
├── SummaryPeriod (Value Object)
│   ├── StartDate
│   └── EndDate
├── Conditions (Collection of Value Objects)
│   ├── ChronicCondition
│   │   ├── Condition
│   │   ├── DiagnosedDate
│   │   ├── Status (ACTIVE, RESOLVED, MANAGED)
│   │   └── LastReviewed
├── Medications (Collection of Value Objects)
│   ├── CurrentMedication
│   │   ├── MedicationName
│   │   ├── Dosage
│   │   ├── Frequency
│   │   └── StartDate
├── LabTrends (Collection of Value Objects)
│   ├── LabTrend
│   │   ├── TestName
│   │   ├── TrendDirection (IMPROVING, WORSENING, STABLE)
│   │   ├── Values (Time series)
│   │   └── ClinicalSignificance
├── VitalsTrends (Collection of Value Objects)
├── HealthMetrics (Value Object)
│   ├── AbnormalTestsCount
│   ├── CriticalAlertsCount
│   ├── MedicationAdherence
│   └── LastVisitDate
└── GeneratedAt (Timestamp)
```

**Invariants:**
- One active HealthSummary per patient
- SummaryPeriod must have valid date range
- Trends must have at least 2 data points
- All conditions must be clinically valid
- TrendDirection must be calculated from actual values

**Business Methods:**
- `updateWithNewData(MedicalEvent event)`: Incorporate new events
- `identifyTrends()`: Detect patterns in lab/vital values
- `flagDeterioratingConditions()`: Alert on worsening trends
- `calculateHealthScore()`: Overall health metric

---

### 5. Patient Context (Shared Kernel)

#### Aggregate: Patient (Root)

**Purpose**: Patient identity and core demographics

```
Patient (Aggregate Root)
├── PatientId (Identity)
├── PersonalInfo (Value Object)
│   ├── FullName
│   ├── DateOfBirth
│   ├── Gender
│   └── ContactInfo
├── MedicalProfile (Value Object)
│   ├── BloodType
│   ├── Allergies (List)
│   ├── ChronicConditions (List)
│   └── FamilyHistory (List)
├── ConsentRecords (Collection of Value Objects)
│   ├── Consent
│   │   ├── ConsentType
│   │   ├── GrantedDate
│   │   ├── ExpiryDate
│   │   └── Scope
└── SecurityInfo (Value Object)
    ├── EncryptionStatus
    └── AccessLog
```

**Invariants:**
- PatientId must be globally unique
- DateOfBirth must be in the past
- All PHI must be encrypted at rest
- Consent must be valid for data access
- Access must be logged for HIPAA compliance

---

## Value Objects

### Common Value Objects Across Contexts

#### 1. IdentityValue Objects
```java
@Value
class PatientId {
    String id;

    static PatientId generate() { return new PatientId(UUID.randomUUID().toString()); }
    static PatientId from(String id) { return new PatientId(id); }
}

@Value
class DocumentId {
    String id;
}

@Value
class RecordId {
    String id;
}
```

#### 2. Clinical Value Objects
```java
@Value
class TestResult {
    String testName;
    String testValue;
    String unit;
    String referenceRange;
    TestStatus status;

    boolean isAbnormal() { return status != TestStatus.NORMAL; }
    boolean isCritical() { return status == TestStatus.CRITICAL; }
}

enum TestStatus {
    NORMAL, ABNORMAL, CRITICAL
}

@Value
class MedicalCode {
    CodeSystem system;
    String code;
    String display;
    String version;
}

enum CodeSystem {
    ICD10, ICD11, LOINC, SNOMED_CT, RXNORM, CPT
}
```

#### 3. Time-related Value Objects
```java
@Value
class DateRange {
    LocalDate startDate;
    LocalDate endDate;

    boolean contains(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    long daysBetween() {
        return ChronoUnit.DAYS.between(startDate, endDate);
    }
}

@Value
class ProcessingTimestamp {
    Instant timestamp;
    String processedBy;
    Duration processingDuration;
}
```

#### 4. Confidence and Quality Value Objects
```java
@Value
class ConfidenceScore {
    Double value; // 0.0 to 1.0

    boolean isHighConfidence() { return value >= 0.85; }
    boolean isMediumConfidence() { return value >= 0.70 && value < 0.85; }
    boolean isLowConfidence() { return value < 0.70; }

    static ConfidenceScore of(double value) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("Confidence must be between 0.0 and 1.0");
        }
        return new ConfidenceScore(value);
    }
}

@Value
class OcrQuality {
    ConfidenceScore confidence;
    Language language;
    Integer characterCount;
    Boolean containsHandwriting;
}
```

---

## Domain Events

Domain events represent significant occurrences in the business domain that other parts of the system care about.

### Document Processing Context Events

```java
// Published when document upload completes
class DocumentUploadedEvent implements DomainEvent {
    DocumentId documentId;
    PatientId patientId;
    DocumentType documentType;
    Instant uploadedAt;
    String uploadedBy;
}

// Published when OCR completes successfully
class DocumentTextExtractedEvent implements DomainEvent {
    DocumentId documentId;
    PatientId patientId;
    String extractedText;
    ConfidenceScore confidence;
    Language language;
    Instant extractedAt;
}

// Published when OCR fails
class DocumentExtractionFailedEvent implements DomainEvent {
    DocumentId documentId;
    PatientId patientId;
    String errorReason;
    Instant failedAt;
}
```

### Clinical Data Context Events

```java
// Published when classification completes
class ClinicalDataClassifiedEvent implements DomainEvent {
    RecordId recordId;
    DocumentId documentId;
    PatientId patientId;
    Integer entitiesCount;
    List<EntityType> entityTypes;
    Instant classifiedAt;
}

// Published when new medical entity is identified
class MedicalEntityIdentifiedEvent implements DomainEvent {
    RecordId recordId;
    PatientId patientId;
    EntityType entityType;
    String entityText;
    List<MedicalCode> codes;
    ConfidenceScore confidence;
    Instant identifiedAt;
}

// Published when critical finding detected
class CriticalFindingDetectedEvent implements DomainEvent {
    RecordId recordId;
    PatientId patientId;
    String findingDescription;
    Severity severity;
    String clinicalSignificance;
    Instant detectedAt;
    boolean requiresUrgentAction;
}
```

### Patient Timeline Context Events

```java
// Published when new event added to timeline
class MedicalEventAddedEvent implements DomainEvent {
    TimelineId timelineId;
    PatientId patientId;
    EventId eventId;
    EventType eventType;
    Instant eventDate;
    boolean isAbnormal;
}

// Published when abnormal value detected
class AbnormalValueDetectedEvent implements DomainEvent {
    TimelineId timelineId;
    PatientId patientId;
    EventId eventId;
    TestResult testResult;
    Instant detectedAt;
}

// Published when timeline is updated
class TimelineUpdatedEvent implements DomainEvent {
    TimelineId timelineId;
    PatientId patientId;
    Integer newEventsCount;
    Instant updatedAt;
}
```

### Clinical Intelligence Context Events

```java
// Published when insight is generated
class ClinicalInsightGeneratedEvent implements DomainEvent {
    InsightId insightId;
    PatientId patientId;
    RecordId recordId;
    InsightType insightType;
    RiskLevel riskLevel;
    boolean requiresImmediateAttention;
    Instant generatedAt;
}

// Published when high-risk assessment made
class HighRiskAssessmentEvent implements DomainEvent {
    InsightId insightId;
    PatientId patientId;
    List<String> riskFactors;
    RiskLevel riskLevel;
    List<Recommendation> urgentRecommendations;
    Instant assessedAt;
}

// Published when health summary updated
class HealthSummaryUpdatedEvent implements DomainEvent {
    SummaryId summaryId;
    PatientId patientId;
    List<String> significantChanges;
    Instant updatedAt;
}
```

---

## Domain Services

Domain services encapsulate domain logic that doesn't naturally fit within an entity or value object.

### 1. ClinicalClassificationService

**Responsibility**: Classify medical text and map to standard codes

```java
interface ClinicalClassificationService {
    ClinicalRecord classifyText(String text, DocumentId documentId, PatientId patientId);

    List<MedicalCode> mapToStandardCodes(MedicalEntity entity);

    ConfidenceScore calculateClassificationConfidence(MedicalEntity entity);
}
```

**Business Rules:**
- Must use medical ontologies (ICD-10, LOINC, SNOMED CT, RxNorm)
- Requires minimum confidence threshold (70%)
- Must handle ambiguous terms with multiple interpretations

---

### 2. AbnormalValueDetectionService

**Responsibility**: Determine if test values are abnormal based on reference ranges

```java
interface AbnormalValueDetectionService {
    TestStatus evaluateTestResult(String testValue, String referenceRange, PatientContext context);

    boolean isCritical(TestResult testResult);

    String interpretResult(TestResult testResult, ClinicalContext context);
}
```

**Business Rules:**
- Must parse various reference range formats
- Consider patient demographics (age, gender) in evaluation
- Flag critical values requiring immediate attention

---

### 3. TimelineSortingService

**Responsibility**: Maintain chronological ordering of medical events

```java
interface TimelineSortingService {
    List<MedicalEvent> sortChronologically(List<MedicalEvent> events);

    void insertInOrder(PatientTimeline timeline, MedicalEvent newEvent);

    List<MedicalEvent> filterAndSort(PatientTimeline timeline, TimelineFilter filter);
}
```

**Business Rules:**
- Events with same timestamp sorted by event type priority
- Maintain stable sort for consistent ordering

---

### 4. ClinicalInsightGenerationService

**Responsibility**: Generate AI-powered clinical insights and recommendations

```java
interface ClinicalInsightGenerationService {
    ClinicalInsight generateInsight(ClinicalRecord record, PatientContext context);

    RiskAssessment assessRisk(List<Finding> findings, PatientProfile profile);

    List<Recommendation> generateRecommendations(ClinicalInsight insight);

    String translateToPatientLanguage(String clinicalText);
}
```

**Business Rules:**
- Insights must be evidence-based
- Risk assessment must follow clinical guidelines
- Patient language must be clear and non-technical

---

### 5. TrendAnalysisService

**Responsibility**: Identify patterns and trends in medical data over time

```java
interface TrendAnalysisService {
    LabTrend analyzeLabTrend(String testName, List<TestResult> historicalResults);

    TrendDirection determineTrendDirection(List<Double> values);

    boolean isSignificantChange(TestResult current, TestResult previous);
}
```

**Business Rules:**
- Requires minimum 3 data points for trend analysis
- Statistical significance must be calculated
- Consider clinically meaningful differences, not just statistical

---

## Ubiquitous Language

### Terms and Definitions

| Term | Definition | Context |
|------|------------|---------|
| **Medical Document** | A scanned or digital document containing medical information (prescription, lab report, etc.) | Document Processing |
| **OCR (Optical Character Recognition)** | Technology to extract text from images | Document Processing |
| **Extraction** | Process of converting image to text | Document Processing |
| **Clinical Entity** | A medical concept extracted from text (medication, condition, test, etc.) | Clinical Data |
| **Medical Code** | Standardized code from ontology (ICD-10, LOINC, SNOMED CT, etc.) | Clinical Data |
| **Classification** | Process of identifying and categorizing medical entities | Clinical Data |
| **Mapping** | Process of assigning standard codes to medical entities | Clinical Data |
| **Medical Event** | An occurrence in patient's medical history (lab result, visit, diagnosis, etc.) | Patient Timeline |
| **Timeline** | Chronological view of patient's medical events | Patient Timeline |
| **Abnormal Value** | Test result outside normal reference range | Patient Timeline |
| **Critical Value** | Test result requiring immediate medical attention | Patient Timeline |
| **Reference Range** | Normal value range for a medical test | Clinical Data, Timeline |
| **Clinical Insight** | AI-generated understanding of medical data | Clinical Intelligence |
| **Health Summary** | Comprehensive overview of patient's health status | Clinical Intelligence |
| **Finding** | Significant clinical observation from analysis | Clinical Intelligence |
| **Recommendation** | Suggested clinical action based on findings | Clinical Intelligence |
| **Risk Assessment** | Evaluation of patient's health risk level | Clinical Intelligence |
| **Trend** | Pattern of change in medical values over time | Clinical Intelligence |
| **Confidence Score** | Measure of certainty in AI classification (0.0-1.0) | All Contexts |
| **Patient Context** | Patient's demographic and medical background information | Patient |
| **PHI (Protected Health Information)** | Sensitive patient data requiring HIPAA compliance | Patient |
| **Provenance** | Origin and source of medical data | All Contexts |

### Action Verbs (Ubiquitous Language)

| Verb | Meaning | Example |
|------|---------|---------|
| **Extract** | Pull text from medical document image | "Extract text from prescription" |
| **Classify** | Identify medical entity type | "Classify 'Metformin' as MEDICATION" |
| **Map** | Assign standard codes | "Map condition to ICD-10 code" |
| **Detect** | Identify abnormal or critical values | "Detect abnormal creatinine level" |
| **Flag** | Mark for attention | "Flag critical values for review" |
| **Assess** | Evaluate risk or severity | "Assess patient's cardiovascular risk" |
| **Trend** | Analyze pattern over time | "Trend blood pressure readings" |
| **Summarize** | Generate concise overview | "Summarize patient's lab results" |
| **Recommend** | Suggest clinical action | "Recommend liver function follow-up" |
| **Interpret** | Explain clinical significance | "Interpret elevated bilirubin" |

---

## Business Rules Summary

### Cross-Cutting Business Rules

1. **Data Quality Rules**
   - All automated processes must include confidence scores
   - Low confidence data must be flagged for human review
   - Source provenance must be tracked for all data

2. **Privacy and Security Rules**
   - All PHI must be encrypted at rest and in transit
   - Access must be logged for audit trail
   - HIPAA compliance mandatory for all operations
   - Patient consent required for data processing

3. **Clinical Safety Rules**
   - Critical values must trigger immediate alerts
   - High-risk assessments must be reviewed by providers
   - All AI-generated content must include confidence scores
   - Clinical recommendations must be evidence-based

4. **Temporal Rules**
   - All events must have timestamps
   - Historical data must be immutable (append-only)
   - Chronological ordering must be maintained

5. **Integration Rules**
   - Each bounded context has clear boundaries
   - Cross-context communication via domain events
   - Anti-corruption layers for external systems
   - Eventual consistency acceptable between contexts

---

## Implementation Guidelines

### Aggregate Design Principles

1. **Small Aggregates**: Keep aggregates focused and cohesive
2. **Consistency Boundaries**: Transactions only within aggregate boundaries
3. **Reference by Identity**: Aggregates reference each other by ID, not direct references
4. **Eventual Consistency**: Use domain events for cross-aggregate updates
5. **Invariant Protection**: Aggregates enforce their business rules

### Event Sourcing Considerations

For critical business processes, consider event sourcing:
- Patient Timeline (full audit trail of medical events)
- Clinical Intelligence (track how insights were generated)
- Document Processing (reproducible extraction pipeline)

### Repository Patterns

Each aggregate root should have a repository:
```java
interface MedicalDocumentRepository {
    Optional<MedicalDocument> findById(DocumentId id);
    void save(MedicalDocument document);
    List<MedicalDocument> findByPatient(PatientId patientId);
}

interface ClinicalRecordRepository {
    Optional<ClinicalRecord> findById(RecordId id);
    void save(ClinicalRecord record);
    List<ClinicalRecord> findByDocument(DocumentId documentId);
}

interface PatientTimelineRepository {
    Optional<PatientTimeline> findByPatient(PatientId patientId);
    void save(PatientTimeline timeline);
}
```

---

## Conclusion

This domain model provides a comprehensive foundation for implementing MedScribe AI following Domain-Driven Design principles. The bounded contexts provide clear separation of concerns, while the aggregates and entities capture the essential business logic and rules.

**Key Takeaways:**
1. **5 Bounded Contexts** aligned with business capabilities
2. **Clear Aggregate boundaries** protecting business invariants
3. **Rich Value Objects** encapsulating business logic
4. **Domain Events** enabling loose coupling between contexts
5. **Ubiquitous Language** shared between developers and domain experts

**Next Steps:**
1. Implement aggregate roots and repositories
2. Define domain event publishers and subscribers
3. Create integration tests validating business rules
4. Develop anti-corruption layers for external systems
5. Establish continuous collaboration with domain experts
