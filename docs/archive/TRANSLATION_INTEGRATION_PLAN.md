# Translation Integration Plan for MedScribe AI

## Overview

This document outlines the comprehensive plan for integrating translation capabilities into MedScribe AI to handle mixed Bangla/English medical documents. After OCR extraction, Bangla text will be translated to English before database storage and AWS Comprehend Medical processing.

## Problem Statement

1. **GCP Vision OCR** now supports Bangla + English extraction perfectly
2. **AWS Comprehend Medical** only understands English text
3. **Database** should store everything in English for consistency
4. **Current Flow**: OCR (Bangla+English) → Classification (fails on Bangla) → Database (mixed languages)
5. **Desired Flow**: OCR (Bangla+English) → Translation (Bangla→English) → Classification (English only) → Database (English only)

## Current Architecture Analysis

### Workflow Flow (10-Stage Pipeline)
```
MasterProcessingRequest
    ↓
Stage 1: Image Validation (OcrPort.validateImageQuality)
    ↓
Stage 2: OCR Processing (OcrPort.extractMedicalData + extractRawText)
    ↓
    ├── ocrExtractedData: List<TestResult> (may contain Bangla test names/values)
    └── ocrRawText: String (mixed Bangla + English text)
    ↓
Stage 3: Entity Detection (MedicalClassificationPort.classifyMedicalEntities)
    ↓                      ⚠️ Uses ocrRawText - FAILS ON BANGLA
Stage 4-5: Medical Coding (ICD-10, RxNorm) - uses ocrRawText
    ↓
Stage 6-10: Clinical Insights (Bedrock)
    ↓
Database Persistence (MedicalReportResultEntity)
```

### Key Data Structures Affected
- `ProcessingContext.ocrRawText` - Raw text from OCR (Bangla+English)
- `ProcessingContext.ocrExtractedData` - List<TestResult> with testName, testValue, unit
- `TestResult.testName` - May be in Bangla (e.g., "রক্তের গ্লুকোজ")
- `MedicalReportResultEntity.resultDataJson` - Stored in database

### Critical Files
- `MedicalReportOrchestrationService.java` - Main orchestration (lines 815-865 for OCR, 871-906 for Entity Detection)
- `ProcessingContext.java` - Context object with ocrRawText and ocrExtractedData
- `TestResult.java` - Domain object for extracted test data
- `ComprehendMedicalAdapter.java` - AWS Comprehend (requires English)
- `VisionAdapter.java` - GCP Vision OCR adapter
- `MedicalReportPersistenceAdapter.java` - Database persistence

## Translation Service Options

### Option 1: Google Cloud Translation API (RECOMMENDED)
**Pros:**
- Already using GCP (Vision) - same credentials work
- Cloud Translation Basic (v2) is simple and cost-effective
- Native reactive support possible via Mono/Flux
- $20 per 1M characters (very affordable for medical text)
- Excellent Bangla→English quality

**Cons:**
- Additional API dependency

### Option 2: AWS Translate
**Pros:**
- Already using AWS (Comprehend, Bedrock)
- Same credentials

**Cons:**
- Additional AWS service to configure
- Mixing two cloud providers' AI services

### Recommendation: **Google Cloud Translation API (v2/v3)**
- Follows existing GCP module pattern (elioo-gcp-vision)
- Can leverage existing GCP credentials
- Consistent with OCR provider (GCP Vision)

## Implementation Plan

### Phase 1: Create `elioo-gcp-translate` Module

#### 1.1 Module Structure
```
elioo-gcp-translate/
├── build.gradle
├── src/main/java/com/elioo/healthcare/gcp/translate/
│   ├── api/
│   │   └── TranslationService.java              # Interface
│   ├── config/
│   │   ├── TranslateAutoConfiguration.java      # Auto-config
│   │   └── TranslateProperties.java             # Properties
│   ├── model/
│   │   ├── TranslationRequest.java              # Request DTO
│   │   └── TranslationResponse.java             # Response DTO
│   └── service/
│       └── TranslationServiceImpl.java          # Implementation
└── src/main/resources/
    └── META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

#### 1.2 build.gradle
```gradle
dependencies {
    api project(':elioo-gcp-common')
    api 'com.google.cloud:google-cloud-translate'  // Translation API
    compileOnly 'org.springframework.boot:spring-boot-autoconfigure'
    api 'io.projectreactor:reactor-core'
}
```

#### 1.3 TranslationService Interface
```java
public interface TranslationService {
    // Translate single text
    Mono<TranslationResponse> translate(TranslationRequest request);

    // Translate batch
    Flux<TranslationResponse> translateBatch(List<TranslationRequest> requests);

    // Detect language
    Mono<String> detectLanguage(String text);

    // Check if text contains non-English
    Mono<Boolean> containsNonEnglish(String text);
}
```

### Phase 2: Create Translation Port in medscribe-ai

#### 2.1 New Outbound Port
```java
// application/port/out/TranslationPort.java
public interface TranslationPort {
    // Translate text (Bangla → English)
    Mono<String> translateToEnglish(String text, String sourceLanguage);

    // Translate with preservation of English portions
    Mono<String> translateMixedText(String text);

    // Translate TestResult fields
    Mono<TestResult> translateTestResult(TestResult testResult);

    // Batch translate TestResults
    Flux<TestResult> translateTestResults(List<TestResult> testResults);
}
```

#### 2.2 Translation Adapter
```java
// adapter/out/gcp/TranslationAdapter.java
@Component
@ConditionalOnProperty(name = "translation.enabled", havingValue = "true")
public class TranslationAdapter implements TranslationPort {
    private final TranslationService translationService;

    // Implementation that:
    // 1. Detects Bangla portions
    // 2. Translates only non-English text
    // 3. Preserves English portions as-is
    // 4. Returns fully English text
}
```

### Phase 3: Integrate into Orchestration Pipeline

#### 3.1 Add Translation Stage (New Stage 2.5)
```
Stage 2: OCR Processing
    ↓
**Stage 2.5: Translation (NEW)**
    ├── translateMixedText(ocrRawText) → englishRawText
    └── translateTestResults(ocrExtractedData) → englishTestResults
    ↓
Stage 3: Entity Detection (uses englishRawText)
```

#### 3.2 Modify ProcessingContext
```java
public class ProcessingContext {
    // Existing
    private String ocrRawText;           // Original (may have Bangla)
    private List<TestResult> ocrExtractedData;

    // NEW - Translated versions
    private String translatedRawText;    // English only
    private List<TestResult> translatedExtractedData;  // English test names
    private String originalLanguage;     // "bn", "en", "mixed"
    private boolean wasTranslated;       // Flag for tracking
}
```

#### 3.3 Modify Orchestration Service
```java
// After OCR, before Entity Detection
private Mono<ProcessingContext> translateTextIfNeeded(ProcessingContext context) {
    // Check if translation is needed
    if (!translationEnabled || isEnglishOnly(context.getOcrRawText())) {
        context.setTranslatedRawText(context.getOcrRawText());
        context.setWasTranslated(false);
        return Mono.just(context);
    }

    // Translate raw text
    return translationPort.translateMixedText(context.getOcrRawText())
        .flatMap(translatedText -> {
            context.setTranslatedRawText(translatedText);
            context.setWasTranslated(true);

            // Translate test results
            return translationPort.translateTestResults(context.getOcrExtractedData())
                .collectList();
        })
        .map(translatedResults -> {
            context.setTranslatedExtractedData(translatedResults);
            context.markStageCompleted(ProcessingStage.TRANSLATION);
            return context;
        });
}
```

#### 3.4 Modify Entity Detection to Use Translated Text
```java
private Mono<ProcessingContext> detectEntities(ProcessingContext context) {
    // Use translated text instead of raw OCR text
    String textForClassification = context.getTranslatedRawText() != null
        ? context.getTranslatedRawText()
        : context.getOcrRawText();

    MedicalClassificationPort.ClassificationRequest request =
        new ClassificationRequest(textForClassification, "en", ...);
    // ...
}
```

### Phase 4: Database Storage

#### 4.1 Store Both Original and Translated
The database already stores as JSON, so we can include both:
```json
{
    "originalText": "রক্তের গ্লুকোজ: ৫.৫ mmol/L",
    "translatedText": "Blood Glucose: 5.5 mmol/L",
    "wasTranslated": true,
    "originalLanguage": "bn"
}
```

#### 4.2 Update Result Entity
Add fields to track translation:
```java
@Column("was_translated")
private Boolean wasTranslated;

@Column("original_language")
private String originalLanguage;
```

### Phase 5: Configuration

#### 5.1 Global Configuration (application-gcp.properties)
```properties
# Translation Configuration (Global Defaults)
translation.enabled=true
translation.default-source-language=bn
translation.target-language=en
translation.preserve-english=true
translation.batch-size=100
translation.timeout-seconds=30
translation.retry-attempts=2
```

#### 5.2 Per-Request Configuration (TranslationOptions)
```java
// New class: TranslationOptions.java
@Data
@Builder
public class TranslationOptions {
    /**
     * Enable/disable translation for this request.
     * Overrides global translation.enabled setting.
     * null = use global default
     */
    private Boolean enabled;

    /**
     * Source language code (e.g., "bn", "hi").
     * null = auto-detect
     */
    private String sourceLanguage;

    /**
     * Target language code.
     * Default: "en"
     */
    @Builder.Default
    private String targetLanguage = "en";

    /**
     * Preserve English text as-is (don't re-translate).
     * Default: true
     */
    @Builder.Default
    private Boolean preserveEnglish = true;
}
```

#### 5.3 Add to MasterProcessingRequest
```java
public class MasterProcessingRequest {
    // ... existing fields ...

    /**
     * Translation options for this request.
     * null = use global defaults from configuration
     */
    private TranslationOptions translationOptions;
}
```

#### 5.4 Usage in Orchestration
```java
private boolean isTranslationEnabled(ProcessingContext context) {
    TranslationOptions options = context.getRequest().getTranslationOptions();

    // Per-request override takes precedence
    if (options != null && options.getEnabled() != null) {
        return options.getEnabled();
    }

    // Fall back to global configuration
    return globalTranslationEnabled;
}
```

## Processing Stages (Updated)

| Stage | Name | Description | Status |
|-------|------|-------------|--------|
| 1 | Image Validation | Validate image quality | Existing |
| 2 | OCR Processing | Extract text (Bangla+English) | Existing |
| **2.5** | **Translation** | **Translate Bangla→English** | **NEW** |
| 3 | Entity Detection | Detect medical entities | Modified (use translated) |
| 4 | ICD-10 Coding | Infer diagnosis codes | Modified (use translated) |
| 5 | RxNorm Coding | Infer medication codes | Modified (use translated) |
| 6-10 | Clinical Insights | AI-powered analysis | Existing |

## Design Decisions (Confirmed)

| # | Question | Decision |
|---|----------|----------|
| 1 | **Original Text Preservation** | ✅ **YES** - Store both original Bangla and English translation in DB |
| 2 | **Translation Granularity** | ✅ **BOTH** - Translate raw text block AND individual TestResult fields |
| 3 | **English Detection** | ✅ **SMART** - Detect and translate only Bangla portions, preserve English as-is |
| 4 | **Error Handling** | ✅ **LENIENT** - Continue with original text and add warning if translation fails |
| 5 | **Per-Request Config** | ✅ **YES** - Configurable at both global and per-request level |

## Files to Create/Modify

### New Files
1. `elioo-gcp-translate/` - New module (entire directory)
2. `medscribe-ai/.../port/out/TranslationPort.java`
3. `medscribe-ai/.../adapter/out/gcp/TranslationAdapter.java`

### Modified Files
1. `elioo-gcp-spring-boot-starter/build.gradle` - Add translate module
2. `medscribe-ai/build.gradle` - Dependency on translate module
3. `ProcessingContext.java` - Add translated fields
4. `ProcessingStage.java` - Add TRANSLATION stage
5. `MedicalReportOrchestrationService.java` - Add translation step
6. `MedicalReportResultEntity.java` - Add translation tracking fields
7. `application-gcp.properties` - Add translation config

## Estimated Effort

| Phase | Effort | Description |
|-------|--------|-------------|
| Phase 1 | 2-3 hours | Create elioo-gcp-translate module |
| Phase 2 | 1-2 hours | Create TranslationPort and Adapter |
| Phase 3 | 2-3 hours | Integrate into orchestration pipeline |
| Phase 4 | 1 hour | Database schema and entity updates |
| Phase 5 | 30 min | Configuration |
| Testing | 2-3 hours | End-to-end testing |
| **Total** | **8-12 hours** | Complete integration |

## Dependencies

```
elioo-gcp-common
    ↑
elioo-gcp-translate (NEW)
    ↑
elioo-gcp-spring-boot-starter
    ↑
medscribe-ai
```

---

## Appendix: Google Cloud Translation API

### Gradle Dependency
```gradle
implementation 'com.google.cloud:google-cloud-translate'
```

### Basic Usage
```java
TranslationServiceClient client = TranslationServiceClient.create();
TranslateTextRequest request = TranslateTextRequest.newBuilder()
    .setParent("projects/{project-id}")
    .setSourceLanguageCode("bn")
    .setTargetLanguageCode("en")
    .addContents("রক্তের গ্লুকোজ")
    .build();
TranslateTextResponse response = client.translateText(request);
// response.getTranslations(0).getTranslatedText() → "Blood Glucose"
```

### Pricing
- $20 per 1 million characters (Cloud Translation Basic v2)
- Free tier: 500,000 characters per month
- Medical reports: ~500-2000 characters each
- Cost per report: ~$0.01-0.04

---

*Document created: 2026-01-05*
*Status: DRAFT - Pending user clarification*
