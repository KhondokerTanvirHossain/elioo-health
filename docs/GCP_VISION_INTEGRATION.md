# GCP Vision API Integration

**Version:** 0.1.0
**Last Updated:** 2026-01-04
**Status:** Production Ready

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Module Structure](#module-structure)
4. [Configuration](#configuration)
5. [Usage Examples](#usage-examples)
6. [API Reference](#api-reference)
7. [Provider Switching](#provider-switching)
8. [Multi-Language Support](#multi-language-support)
9. [Error Handling](#error-handling)
10. [Performance Optimization](#performance-optimization)
11. [Deployment](#deployment)
12. [Troubleshooting](#troubleshooting)

---

## Overview

The GCP Vision API integration provides **multi-language OCR capabilities** for MedScribe AI, specifically designed to support **Bangla (Bengali) medical reports** alongside English and 50+ other languages. This integration complements the existing AWS Textract implementation, allowing seamless provider switching based on language requirements.

### Key Features

- **Multi-Language OCR**: Bangla, English, Hindi, Spanish, French, German, Japanese, Korean, Chinese, Arabic, and 50+ languages
- **Language Hints**: Improve accuracy by specifying expected languages (e.g., "bn,en" for Bangla+English)
- **Structured Text Extraction**: Page → Block → Paragraph → Word → Symbol hierarchy
- **High Accuracy**: Word-level and block-level confidence scores
- **Large Image Support**: Up to 20 MB images (vs 10 MB for AWS Textract)
- **Bounding Box Geometry**: Precise location information for all text elements
- **Image Quality Validation**: Pre-flight checks for size, format, encoding
- **Provider Independence**: Clean hexagonal architecture with OcrPort abstraction
- **Reactive Programming**: Full Project Reactor integration (Mono/Flux)
- **Spring Boot Auto-Configuration**: Zero-code activation via dependency

### When to Use GCP Vision vs AWS Textract

| Use Case | Recommended Provider | Reason |
|----------|---------------------|--------|
| **Bangla/Bengali text** | GCP Vision | AWS Textract doesn't support Bangla |
| **Hindi/Urdu text** | GCP Vision | Better accuracy for Indic scripts |
| **English-only medical reports** | AWS Textract | Optimized for medical forms/tables |
| **Mixed language (Bangla+English)** | GCP Vision | Language hint support |
| **Large images (10-20 MB)** | GCP Vision | Higher size limit |
| **Structured forms/tables** | AWS Textract | Superior form/table detection |
| **Cost-sensitive workloads** | AWS Textract | Lower per-page pricing |

---

## Architecture

### Hexagonal Architecture (Ports & Adapters)

```
┌─────────────────────────────────────────────────────────────────┐
│                     Application Layer (Business Logic)          │
│                                                                  │
│  ┌────────────────────────────────────────────────────────┐   │
│  │              OcrPort (Interface)                        │   │
│  │  - extractMedicalData()                                 │   │
│  │  - extractRawText()                                     │   │
│  │  - validateImageQuality()                               │   │
│  └────────────────────────────────────────────────────────┘   │
│                            ▲                                     │
└────────────────────────────┼─────────────────────────────────────┘
                             │ implements
            ┌────────────────┴────────────────┐
            │                                  │
            ▼                                  ▼
   ┌────────────────┐               ┌────────────────┐
   │ TextractAdapter│               │  VisionAdapter │
   │   (AWS SDK)    │               │   (GCP SDK)    │
   └────────────────┘               └────────────────┘
            │                                  │
            ▼                                  ▼
   ┌────────────────┐               ┌────────────────┐
   │ AWS Textract   │               │ Google Cloud   │
   │     API        │               │  Vision API    │
   └────────────────┘               └────────────────┘
```

**Key Principle**: Business logic depends **only** on `OcrPort` interface, never on AWS or GCP SDKs directly. This enables:
- **Provider Independence**: Switch OCR providers via configuration
- **Testability**: Mock OcrPort for unit tests
- **Flexibility**: Add new providers (Azure, Tesseract) without changing business logic
- **Anti-Corruption Layer**: Shield domain from external API changes

---

## Module Structure

The GCP integration consists of 3 Gradle modules following the same pattern as AWS modules:

### 1. elioo-gcp-common

**Purpose**: Shared GCP infrastructure for all GCP services

**Location**: `elioo-gcp-common/`

**Key Components**:
- [GcpCommonProperties.java](../elioo-gcp-common/src/main/java/com/elioo/healthcare/gcp/common/config/GcpCommonProperties.java) - Configuration properties
- [GcpCommonAutoConfiguration.java](../elioo-gcp-common/src/main/java/com/elioo/healthcare/gcp/common/config/GcpCommonAutoConfiguration.java) - Spring Boot auto-configuration
- [GcpServiceException.java](../elioo-gcp-common/src/main/java/com/elioo/healthcare/gcp/common/exception/GcpServiceException.java) - Base exception
- [GcpConfigurationException.java](../elioo-gcp-common/src/main/java/com/elioo/healthcare/gcp/common/exception/GcpConfigurationException.java) - Configuration errors
- [GcpValidationException.java](../elioo-gcp-common/src/main/java/com/elioo/healthcare/gcp/common/exception/GcpValidationException.java) - Validation errors

**Credentials Strategy** (3-tier fallback):
```java
// 1. Load from file path (production)
gcp.credentials-path=/etc/secrets/gcp-service-account.json

// 2. Load from JSON string (environment variable)
gcp.credentials-json=${GCP_CREDENTIALS_JSON}

// 3. Application Default Credentials (GCE/GKE/Cloud Run)
// Automatically detected by Google SDK
```

### 2. elioo-gcp-vision

**Purpose**: Google Cloud Vision API OCR library (domain-agnostic, reusable)

**Location**: `elioo-gcp-vision/`

**Key Components**:

**API Interface**:
- [VisionService.java](../elioo-gcp-vision/src/main/java/com/elioo/healthcare/gcp/vision/api/VisionService.java) - Service contract

**Implementation**:
- [VisionServiceImpl.java](../elioo-gcp-vision/src/main/java/com/elioo/healthcare/gcp/vision/service/VisionServiceImpl.java) - Vision API integration

**Configuration**:
- [VisionProperties.java](../elioo-gcp-vision/src/main/java/com/elioo/healthcare/gcp/vision/config/VisionProperties.java) - Vision-specific properties
- [VisionAutoConfiguration.java](../elioo-gcp-vision/src/main/java/com/elioo/healthcare/gcp/vision/config/VisionAutoConfiguration.java) - Auto-configuration

**Domain Models** (DTOs):
- [VisionOcrRequest.java](../elioo-gcp-vision/src/main/java/com/elioo/healthcare/gcp/vision/model/VisionOcrRequest.java) - Request DTO with factory methods
- [VisionOcrResponse.java](../elioo-gcp-vision/src/main/java/com/elioo/healthcare/gcp/vision/model/VisionOcrResponse.java) - Response DTO with convenience methods
- [TextBlock.java](../elioo-gcp-vision/src/main/java/com/elioo/healthcare/gcp/vision/model/TextBlock.java) - Text block (paragraph)
- [TextParagraph.java](../elioo-gcp-vision/src/main/java/com/elioo/healthcare/gcp/vision/model/TextParagraph.java) - Paragraph within block
- [TextWord.java](../elioo-gcp-vision/src/main/java/com/elioo/healthcare/gcp/vision/model/TextWord.java) - Word within paragraph
- [TextGeometry.java](../elioo-gcp-vision/src/main/java/com/elioo/healthcare/gcp/vision/model/TextGeometry.java) - Bounding box geometry
- [ImageQualityResult.java](../elioo-gcp-vision/src/main/java/com/elioo/healthcare/gcp/vision/model/ImageQualityResult.java) - Quality validation result

### 3. elioo-gcp-spring-boot-starter

**Purpose**: Aggregator module for easy consumption

**Location**: `elioo-gcp-spring-boot-starter/`

**Usage**:
```gradle
dependencies {
    implementation project(':elioo-gcp-spring-boot-starter')
}
```

This single dependency brings:
- `elioo-gcp-common`
- `elioo-gcp-vision`
- Google Cloud Vision SDK (BOM-managed version)

---

## Configuration

### Application Properties

#### Basic Configuration (application.properties)

```properties
# OCR Provider Selection
# Options: aws (default), gcp
ocr.provider=aws
```

#### GCP Profile (application-gcp.properties)

Activate with: `spring.profiles.active=gcp`

```properties
# ========================================================================
# GCP Vision API Configuration
# ========================================================================

# Provider Selection
ocr.provider=gcp

# GCP Common Configuration
gcp.project-id=${GCP_PROJECT_ID:medscribe-ai-prod}
gcp.credentials-path=${GCP_CREDENTIALS_PATH:/etc/secrets/gcp-service-account.json}

# Alternative: JSON string from environment variable
# gcp.credentials-json=${GCP_CREDENTIALS_JSON}

# Vision API Configuration
gcp.vision.enabled=true
gcp.vision.min-confidence-threshold=0.80
gcp.vision.max-image-size-mb=20
gcp.vision.default-language-hints=bn,en
gcp.vision.timeout-ms=30000

# Retry Configuration (optional)
gcp.retry.max-attempts=3
gcp.retry.backoff-base-delay-ms=1000
gcp.retry.backoff-max-delay-ms=10000
```

### Environment Variables

For containerized deployments (Docker, Kubernetes):

```bash
# GCP Project ID
export GCP_PROJECT_ID=medscribe-ai-prod

# Option 1: Service Account JSON file path
export GCP_CREDENTIALS_PATH=/etc/secrets/gcp-service-account.json

# Option 2: Service Account JSON content (base64 encoded)
export GCP_CREDENTIALS_JSON=$(cat service-account.json | base64)

# Spring Profile
export SPRING_PROFILES_ACTIVE=gcp
```

### Kubernetes ConfigMap & Secret

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: medscribe-gcp-config
data:
  application-gcp.properties: |
    ocr.provider=gcp
    gcp.project-id=medscribe-ai-prod
    gcp.vision.default-language-hints=bn,en
---
apiVersion: v1
kind: Secret
metadata:
  name: medscribe-gcp-secret
type: Opaque
data:
  gcp-service-account.json: <base64-encoded-service-account-json>
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: medscribe-ai
spec:
  template:
    spec:
      containers:
      - name: medscribe-ai
        image: medscribe-ai:latest
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "gcp"
        - name: GCP_PROJECT_ID
          valueFrom:
            configMapKeyRef:
              name: medscribe-gcp-config
              key: gcp.project-id
        - name: GCP_CREDENTIALS_PATH
          value: /etc/gcp/service-account.json
        volumeMounts:
        - name: gcp-credentials
          mountPath: /etc/gcp
          readOnly: true
      volumes:
      - name: gcp-credentials
        secret:
          secretName: medscribe-gcp-secret
```

---

## Usage Examples

### 1. Using VisionAdapter (Application Layer)

The application layer uses `OcrPort` interface - it doesn't know about GCP or AWS:

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class MedicalReportService {

    private final OcrPort ocrPort;  // Injected by Spring (VisionAdapter or TextractAdapter)

    public Mono<OcrResponse> processOcr(OcrRequest request) {
        log.info("Processing OCR for reportId: {}", request.getReportId());

        // Extract processing options
        Map<String, Object> options = new HashMap<>();
        options.put("languageHints", List.of("bn", "en"));  // Bangla + English

        // Call OCR (provider-agnostic)
        return ocrPort.extractMedicalData(
                request.getImageBase64(),
                request.getReportType(),
                options
            )
            .collectList()
            .map(testResults -> buildOcrResponse(request.getReportId(), testResults))
            .doOnSuccess(response -> log.info("OCR completed. Tests extracted: {}",
                response.getExtractedData().size()));
    }
}
```

### 2. Using VisionService Directly (Library Usage)

For non-medical use cases or custom integrations:

```java
@Service
@RequiredArgsConstructor
public class CustomDocumentService {

    private final VisionService visionService;

    // Example 1: Bangla medical report
    public Mono<String> extractBanglaMedicalReport(String imageBase64) {
        VisionOcrRequest request = VisionOcrRequest.banglaEnglish(imageBase64);

        return visionService.detectDocumentText(request)
            .map(VisionOcrResponse::fullText)
            .doOnSuccess(text -> log.info("Extracted text length: {} chars", text.length()));
    }

    // Example 2: Multi-language invoice
    public Mono<VisionOcrResponse> extractInvoice(String imageBase64, List<String> languages) {
        VisionOcrRequest request = VisionOcrRequest.builder()
            .imageBase64(imageBase64)
            .languageHints(languages)
            .includeConfidence(true)
            .includeGeometry(true)
            .build();

        return visionService.detectDocumentText(request);
    }

    // Example 3: Quality check before OCR
    public Mono<VisionOcrResponse> extractWithValidation(String imageBase64) {
        return visionService.validateImageQuality(imageBase64)
            .flatMap(quality -> {
                if (!quality.isValid()) {
                    return Mono.error(new ValidationException(quality.message()));
                }

                if (quality.qualityScore() < 0.7) {
                    log.warn("Low image quality: {}", quality.qualityScore());
                }

                return visionService.detectDocumentText(
                    VisionOcrRequest.banglaEnglish(imageBase64)
                );
            });
    }

    // Example 4: Extract with high-confidence filtering
    public Mono<List<TestResult>> extractHighConfidenceTests(String imageBase64) {
        return visionService.detectDocumentText(VisionOcrRequest.banglaEnglish(imageBase64))
            .map(response -> response.getHighConfidenceBlocks(0.90))
            .flatMapMany(Flux::fromIterable)
            .map(this::parseTestResult)
            .collectList();
    }
}
```

### 3. VisionOcrRequest Factory Methods

```java
// 1. Bangla + English (most common for Bangladesh medical reports)
VisionOcrRequest request = VisionOcrRequest.banglaEnglish(imageBase64);

// 2. English only
VisionOcrRequest request = VisionOcrRequest.englishOnly(imageBase64);

// 3. Custom languages
VisionOcrRequest request = VisionOcrRequest.withLanguages(
    imageBase64,
    List.of("hi", "en")  // Hindi + English
);

// 4. Text-only (no geometry)
VisionOcrRequest request = VisionOcrRequest.textOnly(imageBase64);

// 5. Full control
VisionOcrRequest request = new VisionOcrRequest(
    imageBase64,
    List.of("bn", "en", "hi"),  // Multiple language hints
    true,  // Include confidence scores
    true   // Include geometry (bounding boxes)
);
```

### 4. Processing VisionOcrResponse

```java
public void processOcrResponse(VisionOcrResponse response) {
    // Full text
    String fullText = response.fullText();
    log.info("Extracted text: {}", fullText);

    // Overall quality
    double avgConfidence = response.averageConfidence();
    log.info("Average confidence: {}", avgConfidence);

    // High-confidence blocks only
    List<TextBlock> highConfidenceBlocks = response.getHighConfidenceBlocks(0.90);
    log.info("High-confidence blocks: {}", highConfidenceBlocks.size());

    // Check quality threshold
    if (response.hasAcceptableQuality(0.80)) {
        log.info("OCR quality is acceptable");
    } else {
        log.warn("OCR quality is below threshold");
    }

    // Iterate through structured blocks
    for (TextBlock block : response.blocks()) {
        log.info("Block {}: {} (confidence: {})",
            block.blockIndex(),
            block.text(),
            block.confidence()
        );

        // Access paragraphs
        for (TextParagraph para : block.paragraphs()) {
            log.debug("  Paragraph: {}", para.text());

            // Access words
            for (TextWord word : para.words()) {
                log.trace("    Word: {} (confidence: {})",
                    word.text(),
                    word.confidence()
                );

                // Access geometry
                if (word.geometry() != null) {
                    log.trace("      Bounding box: {}",
                        word.geometry().boundingBox()
                    );
                }
            }
        }
    }

    // Metadata
    Map<String, Object> metadata = response.metadata();
    int pageCount = (int) metadata.get("pageCount");
    String detectedLanguage = (String) metadata.get("detectedLanguage");
    log.info("Pages: {}, Detected language: {}", pageCount, detectedLanguage);
}
```

---

## API Reference

### VisionService Interface

```java
public interface VisionService {

    /**
     * Performs DOCUMENT_TEXT_DETECTION on an image.
     *
     * @param request OCR request with image and language hints
     * @return Mono emitting OCR response with structured text
     */
    Mono<VisionOcrResponse> detectDocumentText(VisionOcrRequest request);

    /**
     * Extracts raw text without structure.
     *
     * @param imageBase64 Base64-encoded image
     * @return Mono emitting plain text
     */
    Mono<String> detectText(String imageBase64);

    /**
     * Validates image quality before OCR.
     *
     * @param imageBase64 Base64-encoded image
     * @return Mono emitting validation result
     */
    Mono<ImageQualityResult> validateImageQuality(String imageBase64);
}
```

### OcrPort Interface (Business Contract)

```java
public interface OcrPort {

    /**
     * Extract structured medical test data from image.
     *
     * @param imageBase64 Base64-encoded medical report image
     * @param reportType Type of report (BLOOD_TEST, URINE_TEST, etc.)
     * @param processingOptions Options including language hints
     * @return Flux of extracted test results
     */
    Flux<TestResult> extractMedicalData(
        String imageBase64,
        String reportType,
        Map<String, Object> processingOptions
    );

    /**
     * Extract raw text for classification.
     *
     * @param imageBase64 Base64-encoded image
     * @param processingOptions Options including language hints
     * @return Mono emitting raw text
     */
    Mono<String> extractRawText(
        String imageBase64,
        Map<String, Object> processingOptions
    );

    /**
     * Validate image quality.
     *
     * @param imageBase64 Base64-encoded image
     * @return Mono emitting validation result
     */
    Mono<Boolean> validateImageQuality(String imageBase64);

    /**
     * Get confidence score for report type.
     *
     * @param reportType Report type
     * @return Mono emitting confidence score
     */
    Mono<Double> getProcessingConfidence(String reportType);
}
```

### VisionOcrRequest (DTO)

```java
public record VisionOcrRequest(
    String imageBase64,
    List<String> languageHints,
    boolean includeConfidence,
    boolean includeGeometry
) {
    // Factory methods
    public static VisionOcrRequest banglaEnglish(String imageBase64);
    public static VisionOcrRequest englishOnly(String imageBase64);
    public static VisionOcrRequest withLanguages(String imageBase64, List<String> languages);
    public static VisionOcrRequest textOnly(String imageBase64);
}
```

### VisionOcrResponse (DTO)

```java
public record VisionOcrResponse(
    String fullText,
    List<TextBlock> blocks,
    List<PageInfo> pages,
    double averageConfidence,
    Map<String, Object> metadata
) {
    // Convenience methods
    public List<TextBlock> getHighConfidenceBlocks(double threshold);
    public boolean hasAcceptableQuality(double minConfidence);
    public int getBlockCount();
    public int getTotalWords();
}
```

### ImageQualityResult (DTO)

```java
public record ImageQualityResult(
    boolean isValid,
    double qualityScore,
    String message,
    Map<String, Object> metrics
) {
    // Factory methods
    public static ImageQualityResult valid(double qualityScore, Map<String, Object> metrics);
    public static ImageQualityResult invalid(String reason, Map<String, Object> metrics);
    public static ImageQualityResult warning(double qualityScore, String message, Map<String, Object> metrics);
}
```

---

## Provider Switching

### Configuration-Based Switching

**AWS Textract (Default)**:
```properties
ocr.provider=aws
```

**GCP Vision**:
```properties
spring.profiles.active=gcp
```

Or directly:
```properties
ocr.provider=gcp
```

### Dynamic Switching (Advanced)

For use cases requiring runtime provider selection:

```java
@Configuration
public class DynamicOcrConfig {

    @Bean
    @Primary
    public OcrPort dynamicOcrPort(
        @Qualifier("textractAdapter") OcrPort textractAdapter,
        @Qualifier("visionAdapter") OcrPort visionAdapter
    ) {
        return new DynamicOcrAdapter(textractAdapter, visionAdapter);
    }
}

@RequiredArgsConstructor
public class DynamicOcrAdapter implements OcrPort {

    private final OcrPort textractAdapter;
    private final OcrPort visionAdapter;

    @Override
    public Flux<TestResult> extractMedicalData(
        String imageBase64,
        String reportType,
        Map<String, Object> options
    ) {
        // Route based on language hint
        List<String> languageHints = (List<String>) options.get("languageHints");

        if (languageHints != null && languageHints.contains("bn")) {
            log.info("Routing to GCP Vision for Bangla support");
            return visionAdapter.extractMedicalData(imageBase64, reportType, options);
        } else {
            log.info("Routing to AWS Textract for English");
            return textractAdapter.extractMedicalData(imageBase64, reportType, options);
        }
    }

    // Implement other methods...
}
```

### Provider Comparison

| Feature | AWS Textract | GCP Vision |
|---------|-------------|------------|
| **Supported Languages** | English, Spanish, German, French, Italian, Portuguese | 50+ languages including Bangla, Hindi, Arabic |
| **Max Image Size** | 10 MB | 20 MB |
| **Form/Table Detection** | Excellent | Good |
| **Handwriting Recognition** | Good | Good |
| **Confidence Scores** | Block-level | Word-level & block-level |
| **Pricing (per 1000 pages)** | $1.50 | $1.50 |
| **Processing Speed** | ~1-2 seconds | ~1-3 seconds |
| **SDK Maturity** | Mature (AWS SDK v2) | Mature (Google SDK) |

---

## Multi-Language Support

### Supported Languages

GCP Vision supports 50+ languages with DOCUMENT_TEXT_DETECTION:

**Indic Languages**:
- `bn` - Bangla/Bengali ✅
- `hi` - Hindi
- `mr` - Marathi
- `ta` - Tamil
- `te` - Telugu
- `gu` - Gujarati
- `kn` - Kannada
- `ml` - Malayalam
- `pa` - Punjabi
- `ur` - Urdu

**European Languages**:
- `en` - English
- `es` - Spanish
- `fr` - French
- `de` - German
- `it` - Italian
- `pt` - Portuguese
- `ru` - Russian
- `pl` - Polish
- `nl` - Dutch
- `sv` - Swedish

**East Asian Languages**:
- `zh` - Chinese (Simplified & Traditional)
- `ja` - Japanese
- `ko` - Korean
- `th` - Thai
- `vi` - Vietnamese

**Middle Eastern Languages**:
- `ar` - Arabic
- `fa` - Farsi/Persian
- `he` - Hebrew
- `tr` - Turkish

**Full list**: See [Google Cloud Vision API Language Support](https://cloud.google.com/vision/docs/languages)

### Language Hint Best Practices

```java
// 1. Bangla medical report with some English terms (most common)
VisionOcrRequest request = VisionOcrRequest.withLanguages(
    imageBase64,
    List.of("bn", "en")  // Primary: Bangla, Fallback: English
);

// 2. Mixed Hindi-English prescription
VisionOcrRequest request = VisionOcrRequest.withLanguages(
    imageBase64,
    List.of("hi", "en")
);

// 3. Arabic medical report
VisionOcrRequest request = VisionOcrRequest.withLanguages(
    imageBase64,
    List.of("ar", "en")
);

// 4. Let Vision API auto-detect (not recommended for medical reports)
VisionOcrRequest request = VisionOcrRequest.withLanguages(
    imageBase64,
    List.of()  // Empty list = auto-detect
);
```

**Recommendations**:
- Always provide language hints for medical reports (improves accuracy by 15-25%)
- List primary language first, fallback languages second
- Include "en" as fallback for medical terminology
- For mixed-language reports, list all expected languages

---

## Error Handling

### Exception Hierarchy

```
GcpServiceException (Runtime)
├── GcpConfigurationException
│   ├── Invalid credentials path
│   ├── Invalid JSON credentials
│   └── Missing project ID
└── GcpValidationException
    ├── Invalid Base64 encoding
    ├── Image size too large/small
    └── Unsupported image format
```

### Handling GCP Exceptions

```java
@Service
@RequiredArgsConstructor
public class RobustOcrService {

    private final VisionService visionService;

    public Mono<VisionOcrResponse> processWithErrorHandling(String imageBase64) {
        return visionService.detectDocumentText(
                VisionOcrRequest.banglaEnglish(imageBase64)
            )
            .onErrorResume(GcpValidationException.class, e -> {
                log.error("Validation failed: {}", e.getMessage());
                return Mono.error(new BadRequestException(
                    "Invalid image: " + e.getMessage()
                ));
            })
            .onErrorResume(GcpConfigurationException.class, e -> {
                log.error("GCP configuration error: {}", e.getMessage());
                return Mono.error(new InternalServerError(
                    "OCR service misconfigured"
                ));
            })
            .onErrorResume(GcpServiceException.class, e -> {
                log.error("GCP Vision API error: {} (code: {})",
                    e.getMessage(), e.getStatusCode());

                // Retry logic or fallback
                if (e.getStatusCode() == 429) {
                    return Mono.error(new RateLimitException("OCR quota exceeded"));
                }

                return Mono.error(new ServiceUnavailableException(
                    "OCR service temporarily unavailable"
                ));
            })
            .retry(3)  // Retry transient failures
            .timeout(Duration.ofSeconds(30));
    }
}
```

### Common Error Scenarios

| Error | Cause | Solution |
|-------|-------|----------|
| **Invalid credentials** | Wrong service account JSON | Verify `gcp.credentials-path` or `gcp.credentials-json` |
| **Permission denied** | Service account lacks Vision API permission | Add `roles/cloudvision.user` role |
| **Quota exceeded** | API quota limit reached | Request quota increase or implement rate limiting |
| **Invalid image** | Corrupted Base64, wrong format | Validate image before calling API |
| **Image too large** | Image > 20 MB | Resize image or compress |
| **Timeout** | Slow network or large image | Increase `gcp.vision.timeout-ms` |

---

## Performance Optimization

### 1. Image Optimization

```java
public class ImageOptimizer {

    /**
     * Optimize image for OCR (reduce size while maintaining quality).
     */
    public String optimizeForOcr(String imageBase64) {
        byte[] imageBytes = Base64.getDecoder().decode(imageBase64);

        // 1. Check size
        double sizeMb = imageBytes.length / (1024.0 * 1024.0);

        if (sizeMb <= 5.0) {
            return imageBase64;  // Optimal size, no optimization needed
        }

        // 2. Resize if too large
        if (sizeMb > 15.0) {
            imageBytes = resizeImage(imageBytes, 0.7);  // Reduce by 30%
        }

        // 3. Convert to JPEG with quality 85
        imageBytes = convertToJpeg(imageBytes, 85);

        return Base64.getEncoder().encodeToString(imageBytes);
    }
}
```

### 2. Batch Processing

```java
@Service
@RequiredArgsConstructor
public class BatchOcrService {

    private final VisionService visionService;

    /**
     * Process multiple images in parallel (max 10 concurrent).
     */
    public Mono<List<VisionOcrResponse>> processBatch(List<String> imagesBase64) {
        return Flux.fromIterable(imagesBase64)
            .flatMap(image ->
                visionService.detectDocumentText(
                    VisionOcrRequest.banglaEnglish(image)
                )
                .onErrorResume(e -> {
                    log.error("Failed to process image", e);
                    return Mono.empty();  // Skip failed images
                }),
                10  // Max 10 concurrent API calls
            )
            .collectList();
    }
}
```

### 3. Caching

```java
@Configuration
@EnableCaching
public class OcrCacheConfig {

    @Bean
    public CacheManager cacheManager() {
        return new CaffeineCacheManager("ocr-results");
    }
}

@Service
@RequiredArgsConstructor
public class CachedOcrService {

    private final VisionService visionService;

    @Cacheable(value = "ocr-results", key = "#imageHash")
    public Mono<VisionOcrResponse> processWithCache(String imageHash, String imageBase64) {
        log.info("Cache miss for image: {}", imageHash);
        return visionService.detectDocumentText(
            VisionOcrRequest.banglaEnglish(imageBase64)
        );
    }
}
```

### 4. Reactive Backpressure

```java
@Service
@RequiredArgsConstructor
public class BackpressureOcrService {

    private final VisionService visionService;

    /**
     * Process stream with backpressure (100 images at a time).
     */
    public Flux<VisionOcrResponse> processStream(Flux<String> imageStream) {
        return imageStream
            .buffer(100)  // Process in batches of 100
            .concatMap(batch ->
                Flux.fromIterable(batch)
                    .flatMap(image ->
                        visionService.detectDocumentText(
                            VisionOcrRequest.banglaEnglish(image)
                        ),
                        10  // Max 10 concurrent per batch
                    )
            );
    }
}
```

---

## Deployment

### Local Development

```bash
# 1. Set environment variables
export GCP_PROJECT_ID=medscribe-ai-dev
export GCP_CREDENTIALS_PATH=/path/to/service-account.json
export SPRING_PROFILES_ACTIVE=gcp

# 2. Run application
./gradlew bootRun
```

### Docker

**Dockerfile**:
```dockerfile
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy application JAR
COPY medscribe-ai/build/libs/medscribe-ai-*.jar app.jar

# Create directory for GCP credentials
RUN mkdir -p /etc/gcp

# Expose port
EXPOSE 8086

# Run with GCP profile
ENTRYPOINT ["java", "-Dspring.profiles.active=gcp", "-jar", "app.jar"]
```

**Run with credentials**:
```bash
docker build -t medscribe-ai:latest .

docker run -d \
  --name medscribe-ai \
  -p 8086:8086 \
  -e GCP_PROJECT_ID=medscribe-ai-prod \
  -e GCP_CREDENTIALS_PATH=/etc/gcp/service-account.json \
  -v /path/to/service-account.json:/etc/gcp/service-account.json:ro \
  medscribe-ai:latest
```

### Google Cloud Run

```bash
# 1. Build and push image
gcloud builds submit --tag gcr.io/medscribe-ai-prod/medscribe-ai:latest

# 2. Deploy with service account
gcloud run deploy medscribe-ai \
  --image gcr.io/medscribe-ai-prod/medscribe-ai:latest \
  --platform managed \
  --region us-central1 \
  --service-account medscribe-ai@medscribe-ai-prod.iam.gserviceaccount.com \
  --set-env-vars "SPRING_PROFILES_ACTIVE=gcp,GCP_PROJECT_ID=medscribe-ai-prod"
```

**Note**: Cloud Run automatically injects Application Default Credentials, no need for JSON file.

### Google Kubernetes Engine (GKE)

```bash
# 1. Create service account secret
kubectl create secret generic gcp-credentials \
  --from-file=service-account.json=/path/to/service-account.json

# 2. Apply deployment
kubectl apply -f k8s/deployment.yaml
```

### AWS ECS with GCP Vision

```json
{
  "family": "medscribe-ai",
  "containerDefinitions": [
    {
      "name": "medscribe-ai",
      "image": "medscribe-ai:latest",
      "environment": [
        {
          "name": "SPRING_PROFILES_ACTIVE",
          "value": "gcp"
        },
        {
          "name": "GCP_PROJECT_ID",
          "value": "medscribe-ai-prod"
        },
        {
          "name": "GCP_CREDENTIALS_PATH",
          "value": "/etc/gcp/service-account.json"
        }
      ],
      "secrets": [
        {
          "name": "GCP_CREDENTIALS_JSON",
          "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789012:secret:gcp-credentials"
        }
      ]
    }
  ]
}
```

---

## Troubleshooting

### Common Issues

#### 1. "Invalid credentials" error

**Symptoms**:
```
GcpConfigurationException: Failed to load credentials from /etc/gcp/service-account.json
```

**Solutions**:
- Verify file path: `ls -la /etc/gcp/service-account.json`
- Check file permissions: `chmod 400 service-account.json`
- Validate JSON format: `cat service-account.json | jq .`
- Verify service account exists in GCP Console
- Check `gcp.credentials-path` property

#### 2. "Permission denied" error

**Symptoms**:
```
GcpServiceException: The caller does not have permission (code: 403)
```

**Solutions**:
- Verify service account has `roles/cloudvision.user` role
- Check Vision API is enabled: `gcloud services enable vision.googleapis.com`
- Wait 1-2 minutes after enabling API (propagation delay)
- Verify correct project ID: `gcloud config get-value project`

#### 3. "Quota exceeded" error

**Symptoms**:
```
GcpServiceException: Quota exceeded for quota metric 'vision.googleapis.com/requests' (code: 429)
```

**Solutions**:
- Check current quota: GCP Console → IAM & Admin → Quotas
- Request quota increase: Click quota → "Edit Quotas"
- Implement rate limiting in application
- Use batch processing to reduce API calls
- Consider caching results

#### 4. OCR returns empty text

**Symptoms**:
```
VisionOcrResponse { fullText: "", blocks: [] }
```

**Solutions**:
- Verify image is not corrupted: `base64 -d image.txt > test.jpg && open test.jpg`
- Check image format (JPEG, PNG supported)
- Ensure image contains visible text
- Try with higher resolution image
- Check language hints match document language

#### 5. Low confidence scores

**Symptoms**:
```
Average confidence: 0.45 (below threshold)
```

**Solutions**:
- Improve image quality (higher resolution, better lighting)
- Reduce image noise/compression artifacts
- Provide correct language hints
- Ensure text is horizontal (rotate if needed)
- Use original scan instead of photo of document

### Debug Mode

Enable detailed logging:

```properties
# Application properties
logging.level.com.elioo.healthcare.gcp=DEBUG
logging.level.com.google.cloud.vision=DEBUG
gcp.vision.enable-detailed-logging=true
```

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class DebugOcrService {

    private final VisionService visionService;

    public Mono<VisionOcrResponse> processWithDebug(String imageBase64) {
        return visionService.detectDocumentText(
                VisionOcrRequest.banglaEnglish(imageBase64)
            )
            .doOnSubscribe(s -> log.debug("Starting OCR request"))
            .doOnNext(response -> {
                log.debug("OCR response: blocks={}, avgConfidence={}",
                    response.blocks().size(),
                    response.averageConfidence()
                );
                log.debug("Full text preview: {}",
                    response.fullText().substring(0, Math.min(200, response.fullText().length()))
                );
            })
            .doOnError(e -> log.error("OCR failed", e));
    }
}
```

### Health Check Endpoint

```java
@RestController
@RequestMapping("/actuator/health")
@RequiredArgsConstructor
public class GcpHealthCheck {

    private final VisionService visionService;

    @GetMapping("/gcp-vision")
    public Mono<Map<String, Object>> checkVisionApi() {
        // Test with small dummy image
        String testImage = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

        return visionService.validateImageQuality(testImage)
            .map(result -> Map.of(
                "status", "UP",
                "details", Map.of(
                    "service", "Google Cloud Vision API",
                    "validation", "OK"
                )
            ))
            .onErrorReturn(Map.of(
                "status", "DOWN",
                "error", "Vision API unreachable"
            ))
            .timeout(Duration.ofSeconds(5));
    }
}
```

---

## References

- **GCP Vision API Documentation**: https://cloud.google.com/vision/docs
- **Language Support**: https://cloud.google.com/vision/docs/languages
- **Pricing**: https://cloud.google.com/vision/pricing
- **Quotas & Limits**: https://cloud.google.com/vision/quotas
- **Best Practices**: https://cloud.google.com/vision/docs/best-practices
- **Spring Boot Auto-Configuration**: https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.developing-auto-configuration

---

**Next Steps**:
1. See [GCP_QUICKSTART_GUIDE.md](GCP_QUICKSTART_GUIDE.md) for step-by-step setup instructions
2. Review [GCP_VISION_INTEGRATION_PLAN.md](GCP_VISION_INTEGRATION_PLAN.md) for architecture details
3. Check [../CLAUDE.md](../CLAUDE.md) for project overview

**Support**:
- Report issues: https://github.com/KhondokerTanvirHossain/elioo-health/-/issues
