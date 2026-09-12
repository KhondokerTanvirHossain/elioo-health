# Google Cloud Vision API OCR Integration - Implementation Plan

**Author:** AI Assistant
**Date:** 2026-01-04
**Version:** 1.0
**Status:** Pending Review

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Problem Statement](#problem-statement)
3. [Proposed Solution](#proposed-solution)
4. [Architecture Overview](#architecture-overview)
5. [Module Structure](#module-structure)
6. [Detailed Implementation](#detailed-implementation)
7. [Provider Selection Mechanism](#provider-selection-mechanism)
8. [Configuration](#configuration)
9. [API Examples](#api-examples)
10. [Testing Strategy](#testing-strategy)
11. [Implementation Phases](#implementation-phases)
12. [File Inventory](#file-inventory)
13. [Dependencies](#dependencies)
14. [Deployment Considerations](#deployment-considerations)
15. [Risk Assessment](#risk-assessment)
16. [Success Criteria](#success-criteria)

---

## 1. Executive Summary

This document outlines a comprehensive plan to integrate **Google Cloud Vision API** as an alternative OCR provider alongside the existing **AWS Textract** implementation in the MedScribe AI platform.

### Key Goals

- **Multi-language Support:** Enable Bangla (Bengali) and other non-English language OCR that AWS Textract does not support well
- **Provider Flexibility:** Allow configurable switching between AWS and GCP via application properties
- **Architectural Consistency:** Mirror existing AWS library patterns for maintainability
- **Zero Business Logic Changes:** Leverage hexagonal architecture—no changes to orchestration service

### Implementation Scope

- **New Modules:** 3 new library modules (`elioo-gcp-common`, `elioo-gcp-vision`, `elioo-gcp-spring-boot-starter`)
- **Modified Files:** 4 files (settings.gradle, build files, TextractAdapter)
- **New Files:** 22 files total
- **Estimated Effort:** 3-5 days for full implementation + testing

---

## 2. Problem Statement

### Current Limitation

The existing OCR implementation uses **AWS Textract** exclusively:

- **Limited Language Support:** AWS Textract primarily supports English and has poor accuracy for Bangla/Bengali text
- **Single Provider Lock-in:** No flexibility to switch providers based on document language or type
- **Cost Optimization Gap:** Cannot route to cheaper providers for simple documents

### Business Impact

Medical reports in Bangladesh often contain:
- Patient names and addresses in Bangla
- Test names in Bangla
- Mixed Bangla-English content

AWS Textract's inability to accurately OCR Bangla text results in:
- Failed medical test extraction
- Incorrect patient data
- Manual intervention required

---

## 3. Proposed Solution

### Solution Overview

Add **Google Cloud Vision API** as an alternative OCR provider with the following characteristics:

| Feature | AWS Textract | GCP Vision |
|---------|-------------|------------|
| **Language Support** | English, Spanish (limited) | 50+ languages including Bangla |
| **Max Image Size** | 10 MB | 20 MB |
| **Table Detection** | Excellent | Good |
| **Handwriting** | Good | Excellent |
| **Multi-language** | Poor | Excellent |
| **Confidence Scores** | Per block | Per word/block |
| **Pricing** | ~$1.50/1000 pages | ~$1.50/1000 pages |

### Provider Selection Strategy

Use Spring Boot's `@ConditionalOnProperty` to select the active provider:

```properties
# application.properties
ocr.provider=aws    # Default to AWS Textract

# application-gcp.properties
ocr.provider=gcp    # Switch to GCP Vision
```

**No code changes required** to switch providers—just change configuration.

---

## 4. Architecture Overview

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         medscribe-ai (Application)                       │
│                                                                           │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │          MedicalReportOrchestrationService (Business Logic)         │ │
│  │                                                                      │ │
│  │                    Depends on: OcrPort (interface)                  │ │
│  │                              ↓                                       │ │
│  │                    ┌──────────────────┐                             │ │
│  │                    │  OcrPort         │  Business-defined port       │ │
│  │                    │  (interface)     │  Independent of providers    │ │
│  │                    └──────────────────┘                             │ │
│  │                         ↙          ↘                                │ │
│  │              ┌────────────┐      ┌────────────┐                     │ │
│  │              │ Textract   │      │  Vision    │  Adapters            │ │
│  │              │ Adapter    │      │  Adapter   │  (Anti-Corruption)   │ │
│  │              │ (AWS impl) │      │ (GCP impl) │                      │ │
│  │              └────────────┘      └────────────┘                     │ │
│  │              @Conditional         @Conditional                       │ │
│  │              (aws)                (gcp)                              │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                    ↓                              ↓                      │
└────────────────────┼──────────────────────────────┼──────────────────────┘
                     ↓                              ↓
        ┌────────────────────────┐    ┌──────────────────────────────┐
        │ elioo-aws-textract│    │ elioo-gcp-vision        │
        │                        │    │                              │
        │  TextractService       │    │  VisionService               │
        │  (interface)           │    │  (interface)                 │
        │       ↓                │    │       ↓                      │
        │  TextractServiceImpl   │    │  VisionServiceImpl           │
        │  (AWS SDK wrapper)     │    │  (GCP SDK wrapper)           │
        │       ↓                │    │       ↓                      │
        │  elioo-aws-common │    │  elioo-gcp-common       │
        │  (AWS credentials)     │    │  (GCP credentials)           │
        └────────────────────────┘    └──────────────────────────────┘
                 ↓                              ↓
        ┌────────────────────┐        ┌──────────────────────┐
        │  AWS SDK           │        │  Google Cloud        │
        │  TextractAsync     │        │  Vision API          │
        │  Client            │        │  ImageAnnotator      │
        └────────────────────┘        └──────────────────────┘
```

### Hexagonal Architecture Layers

1. **Domain Layer:** Business entities (`TestResult`, `TestStatus`) - unchanged
2. **Application Layer:** Use cases and ports (`OcrPort` interface) - unchanged
3. **Adapter Layer:**
   - **Inbound:** Web handlers - unchanged
   - **Outbound:** `TextractAdapter` (existing), `VisionAdapter` (new)
4. **Infrastructure Layer:** Library modules for AWS and GCP

### Key Architectural Decisions

| Decision | Rationale |
|----------|-----------|
| **Mirror AWS module structure** | Consistency, maintainability, team familiarity |
| **Separate GCP common module** | Future GCP services can reuse (NLP, Speech) |
| **Conditional bean selection** | Spring Boot idiomatic, no custom factory code |
| **Same `OcrPort` interface** | Zero business logic changes |
| **Library modules as separate projects** | Reusable across applications, independent versioning |

---

## 5. Module Structure

### 5.1 New Module: `elioo-gcp-common`

**Purpose:** Shared GCP infrastructure (credentials, region, retry config)

**Structure:**
```
elioo-gcp-common/
├── build.gradle
└── src/
    ├── main/
    │   ├── java/com/elioo/healthcare/gcp/common/
    │   │   ├── config/
    │   │   │   ├── GcpCommonAutoConfiguration.java      # Spring Boot auto-config
    │   │   │   └── GcpCommonProperties.java             # @ConfigurationProperties
    │   │   ├── exception/
    │   │   │   ├── GcpServiceException.java             # Base exception
    │   │   │   ├── GcpConfigurationException.java       # Config errors
    │   │   │   └── GcpValidationException.java          # Validation errors
    │   │   └── util/
    │   │       └── GcpJsonUtils.java                    # JSON utilities
    │   └── resources/
    │       └── META-INF/spring/
    │           └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── test/
        └── java/com/elioo/healthcare/gcp/common/
            └── config/
                └── GcpCommonAutoConfigurationTest.java
```

**Key Responsibilities:**
- GCP credentials provider (service account JSON or application default credentials)
- GCP project ID configuration
- Common retry policies
- Shared exception handling

**Dependencies:**
```gradle
api 'com.google.cloud:google-cloud-core'
api 'com.google.auth:google-auth-library-oauth2-http'
api 'io.projectreactor:reactor-core'
compileOnly 'org.springframework.boot:spring-boot-autoconfigure'
```

### 5.2 New Module: `elioo-gcp-vision`

**Purpose:** Google Cloud Vision API OCR integration

**Structure:**
```
elioo-gcp-vision/
├── build.gradle
└── src/
    ├── main/
    │   ├── java/com/elioo/healthcare/gcp/vision/
    │   │   ├── api/
    │   │   │   └── VisionService.java                   # Service interface
    │   │   ├── config/
    │   │   │   ├── VisionAutoConfiguration.java         # Auto-config
    │   │   │   └── VisionProperties.java                # Properties
    │   │   ├── model/
    │   │   │   ├── VisionOcrRequest.java                # Request DTO
    │   │   │   ├── VisionOcrResponse.java               # Response DTO
    │   │   │   ├── TextBlock.java                       # Text block (paragraph)
    │   │   │   ├── TextParagraph.java                   # Paragraph details
    │   │   │   ├── TextWord.java                        # Word details
    │   │   │   ├── TextSymbol.java                      # Symbol details
    │   │   │   ├── TextGeometry.java                    # Bounding box
    │   │   │   └── ImageQualityResult.java              # Validation result
    │   │   └── service/
    │   │       └── VisionServiceImpl.java               # Implementation
    │   └── resources/
    │       └── META-INF/spring/
    │           └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── test/
        └── java/com/elioo/healthcare/gcp/vision/
            ├── config/
            │   └── VisionAutoConfigurationTest.java
            └── service/
                └── VisionServiceImplTest.java
```

**Key Responsibilities:**
- Call Google Cloud Vision API with DOCUMENT_TEXT_DETECTION feature
- Parse Vision API response into domain DTOs
- Support language hints for multi-language OCR
- Image quality validation

**Dependencies:**
```gradle
api project(':elioo-gcp-common')
api 'com.google.cloud:google-cloud-vision'
api 'io.projectreactor:reactor-core'
compileOnly 'org.springframework.boot:spring-boot-autoconfigure'
```

### 5.3 New Module: `elioo-gcp-spring-boot-starter`

**Purpose:** Aggregator module for easy consumption

**Structure:**
```
elioo-gcp-spring-boot-starter/
└── build.gradle
```

**Dependencies:**
```gradle
api project(':elioo-gcp-common')
api project(':elioo-gcp-vision')
```

**Usage in medscribe-ai:**
```gradle
dependencies {
    implementation project(':elioo-gcp-spring-boot-starter')
}
```

This single dependency brings in all GCP modules transitively.

---

## 6. Detailed Implementation

### 6.1 GcpCommonProperties

```java
package com.elioo.healthcare.gcp.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for GCP services.
 *
 * Configuration example:
 * <pre>
 * gcp:
 *   project-id: my-gcp-project
 *   credentials-path: /path/to/service-account.json
 *   # OR
 *   credentials-json: ${GCP_CREDENTIALS_JSON}
 * </pre>
 */
@ConfigurationProperties(prefix = "gcp")
public class GcpCommonProperties {

    /**
     * GCP Project ID.
     * Required for all GCP services.
     */
    private String projectId;

    /**
     * Path to service account JSON file.
     * Optional - uses Application Default Credentials if not specified.
     */
    private String credentialsPath;

    /**
     * Service account JSON as inline string (for K8s secrets).
     * Optional - uses Application Default Credentials if not specified.
     */
    private String credentialsJson;

    /**
     * Retry configuration.
     */
    private RetryConfig retry = new RetryConfig();

    /**
     * Check if credentials are explicitly configured.
     */
    public boolean hasExplicitCredentials() {
        return (credentialsPath != null && !credentialsPath.isBlank()) ||
               (credentialsJson != null && !credentialsJson.isBlank());
    }

    // Getters and setters...

    public static class RetryConfig {
        private int maxAttempts = 3;
        private int backoffBaseDelayMs = 100;
        private int backoffMaxDelayMs = 10000;

        // Getters and setters...
    }
}
```

### 6.2 GcpCommonAutoConfiguration

```java
package com.elioo.healthcare.gcp.common.config;

import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Spring Boot auto-configuration for Google Cloud Platform.
 *
 * Provides:
 * - GCP Credentials (service account or application default)
 * - Project ID configuration
 *
 * Configuration example:
 * <pre>
 * gcp:
 *   project-id: my-project
 *   credentials-path: /path/to/service-account.json
 * </pre>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(GoogleCredentials.class)
@EnableConfigurationProperties(GcpCommonProperties.class)
public class GcpCommonAutoConfiguration {

    /**
     * Creates GCP credentials provider.
     *
     * Credential resolution order:
     * 1. If credentialsPath configured → load from file
     * 2. If credentialsJson configured → load from string
     * 3. Otherwise → Application Default Credentials (ADC)
     *
     * ADC sources (in order):
     * - GOOGLE_APPLICATION_CREDENTIALS environment variable
     * - Google Cloud SDK credentials (gcloud auth)
     * - Compute Engine/GKE service account
     */
    @Bean
    @ConditionalOnMissingBean(name = "gcpCredentials")
    public Credentials gcpCredentials(GcpCommonProperties properties) throws IOException {
        log.info("Initializing GCP credentials");

        // Strategy 1: Load from file path
        if (properties.getCredentialsPath() != null && !properties.getCredentialsPath().isBlank()) {
            log.info("Loading GCP credentials from file: {}", properties.getCredentialsPath());
            try (FileInputStream fis = new FileInputStream(properties.getCredentialsPath())) {
                return ServiceAccountCredentials.fromStream(fis);
            }
        }

        // Strategy 2: Load from JSON string (useful for K8s secrets)
        if (properties.getCredentialsJson() != null && !properties.getCredentialsJson().isBlank()) {
            log.info("Loading GCP credentials from inline JSON");
            byte[] jsonBytes = properties.getCredentialsJson().getBytes();
            try (ByteArrayInputStream bis = new ByteArrayInputStream(jsonBytes)) {
                return ServiceAccountCredentials.fromStream(bis);
            }
        }

        // Strategy 3: Application Default Credentials
        log.info("Using GCP Application Default Credentials");
        return GoogleCredentials.getApplicationDefault();
    }
}
```

### 6.3 VisionService Interface

```java
package com.elioo.healthcare.gcp.vision.api;

import com.elioo.healthcare.gcp.vision.model.ImageQualityResult;
import com.elioo.healthcare.gcp.vision.model.VisionOcrRequest;
import com.elioo.healthcare.gcp.vision.model.VisionOcrResponse;
import reactor.core.publisher.Mono;

/**
 * OCR service contract for Google Cloud Vision API.
 *
 * <p>This interface defines the contract for optical character recognition using
 * Google Cloud Vision API. All operations are reactive and return {@link Mono} types
 * for non-blocking execution.</p>
 *
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li>DOCUMENT_TEXT_DETECTION for structured documents</li>
 *   <li>Multi-language support with language hints</li>
 *   <li>Confidence scores at word and block level</li>
 *   <li>Geometry information (bounding boxes)</li>
 * </ul>
 *
 * <p><b>Implementations:</b></p>
 * <ul>
 *   <li>{@code VisionServiceImpl} - Google Cloud Vision implementation</li>
 * </ul>
 *
 * @see VisionOcrRequest
 * @see VisionOcrResponse
 * @see ImageQualityResult
 * @since 0.1.0
 */
public interface VisionService {

    /**
     * Performs DOCUMENT_TEXT_DETECTION on an image.
     *
     * <p>This method uses the DOCUMENT_TEXT_DETECTION feature which is optimized
     * for structured documents (reports, invoices, forms). It provides:</p>
     * <ul>
     *   <li>Full text annotation with page/block/paragraph/word/symbol hierarchy</li>
     *   <li>Bounding box information for all text elements</li>
     *   <li>Language detection</li>
     *   <li>Confidence scores</li>
     * </ul>
     *
     * @param request OCR request containing image and language hints
     * @return Mono emitting the OCR response with extracted text and geometry
     */
    Mono<VisionOcrResponse> detectDocumentText(VisionOcrRequest request);

    /**
     * Detects and extracts raw text from an image.
     *
     * <p>This is a simpler operation that returns only the concatenated text
     * without structure or geometry information. Faster for text-only use cases.</p>
     *
     * @param imageBase64 Base64-encoded image data
     * @return Mono emitting the extracted text as a single string
     */
    Mono<String> detectText(String imageBase64);

    /**
     * Validates image quality before OCR processing.
     *
     * <p>Checks image against quality requirements:</p>
     * <ul>
     *   <li>File size within acceptable range (< 20 MB)</li>
     *   <li>Image format supported</li>
     *   <li>Resolution adequate for OCR</li>
     * </ul>
     *
     * @param imageBase64 Base64-encoded image data
     * @return Mono emitting validation result with quality metrics
     */
    Mono<ImageQualityResult> validateImageQuality(String imageBase64);
}
```

### 6.4 VisionOcrRequest

```java
package com.elioo.healthcare.gcp.vision.model;

import java.util.List;

/**
 * Request object for Vision OCR operations.
 *
 * @param imageBase64 Base64-encoded image data
 * @param languageHints List of language hints (ISO 639-1 codes) for better accuracy.
 *                      Examples: ["en"], ["bn"], ["bn", "en"]
 * @param includeConfidence Include confidence scores in response
 * @param includeGeometry Include bounding box geometry in response
 */
public record VisionOcrRequest(
        String imageBase64,
        List<String> languageHints,
        boolean includeConfidence,
        boolean includeGeometry
) {
    /**
     * Create request with standard English language hint.
     */
    public static VisionOcrRequest standard(String imageBase64) {
        return new VisionOcrRequest(imageBase64, List.of("en"), true, true);
    }

    /**
     * Create request with custom language hints.
     */
    public static VisionOcrRequest withLanguages(String imageBase64, List<String> languages) {
        return new VisionOcrRequest(imageBase64, languages, true, true);
    }

    /**
     * Create request optimized for Bangla + English documents.
     */
    public static VisionOcrRequest banglaEnglish(String imageBase64) {
        return new VisionOcrRequest(imageBase64, List.of("bn", "en"), true, true);
    }

    /**
     * Create request for text-only extraction (no geometry).
     */
    public static VisionOcrRequest textOnly(String imageBase64) {
        return new VisionOcrRequest(imageBase64, List.of("en"), false, false);
    }
}
```

### 6.5 VisionAdapter (Application Layer)

```java
package com.elioo.healthcare.medicalreport.adapter.out.gcp;

import com.elioo.healthcare.gcp.vision.api.VisionService;
import com.elioo.healthcare.gcp.vision.model.TextBlock;
import com.elioo.healthcare.gcp.vision.model.VisionOcrRequest;
import com.elioo.healthcare.gcp.vision.model.VisionOcrResponse;
import com.elioo.healthcare.medicalreport.application.port.out.OcrPort;
import com.elioo.healthcare.medicalreport.domain.TestResult;
import com.elioo.healthcare.medicalreport.domain.TestStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Cloud Vision implementation of OcrPort using the Vision Service library.
 *
 * <p>This adapter demonstrates the power of the elioo-gcp-vision library:</p>
 * <ul>
 *   <li>✅ <b>No manual GCP SDK calls</b> - library handles it</li>
 *   <li>✅ <b>Multi-language support</b> - Bangla, English, 50+ languages</li>
 *   <li>✅ <b>Language hints</b> - Improved accuracy for non-English text</li>
 *   <li>✅ <b>Clean DTO mapping</b> - library DTOs to domain objects</li>
 *   <li>✅ <b>Reusable across applications</b> - not tied to medscribe-ai</li>
 * </ul>
 *
 * <p>Architecture: Outbound Adapter (Driven Adapter) in Hexagonal Architecture</p>
 * <ul>
 *   <li>Implements the business-defined port interface ({@link OcrPort})</li>
 *   <li>Delegates to {@link VisionService} from library</li>
 *   <li>Maps between medscribe-ai domain objects and library DTOs</li>
 *   <li>Acts as Anti-Corruption Layer between domain and library</li>
 * </ul>
 *
 * <p><b>Provider Selection:</b></p>
 * <pre>
 * # application.properties
 * ocr.provider=gcp    # Activates this adapter
 * </pre>
 *
 * @see OcrPort
 * @see VisionService
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ocr.provider", havingValue = "gcp")
public class VisionAdapter implements OcrPort {

    private final VisionService visionService;

    @Override
    public Flux<TestResult> extractMedicalData(
            String imageBase64,
            String reportType,
            Map<String, Object> processingOptions
    ) {
        log.info("Starting GCP Vision OCR extraction for report type: {}", reportType);

        // Extract language hints from processing options
        List<String> languageHints = extractLanguageHints(processingOptions);
        log.debug("Using language hints: {}", languageHints);

        // Create request with language hints
        VisionOcrRequest request = VisionOcrRequest.withLanguages(imageBase64, languageHints);

        // Call library service and map to domain objects
        return visionService.detectDocumentText(request)
                .doOnSuccess(response -> log.info("Vision OCR completed. Blocks: {}, Avg Confidence: {}",
                        response.blocks().size(), response.averageConfidence()))
                .doOnError(error -> log.error("Vision OCR failed", error))
                .flatMapMany(response -> extractTestResultsFromVisionResponse(response, reportType))
                .onErrorResume(error -> {
                    log.error("Error extracting medical data via GCP Vision", error);
                    return Flux.error(new OcrException("Failed to extract medical data", error));
                });
    }

    @Override
    public Mono<String> extractRawText(String imageBase64, String language) {
        log.info("Extracting raw text via GCP Vision in language: {}", language);

        // Map language code to language hints
        List<String> languageHints = buildLanguageHints(language);

        VisionOcrRequest request = VisionOcrRequest.withLanguages(imageBase64, languageHints);

        return visionService.detectDocumentText(request)
                .map(VisionOcrResponse::fullText)
                .doOnSuccess(text -> log.info("Raw text extracted. Length: {}", text.length()))
                .onErrorResume(error -> {
                    log.error("Error extracting raw text via GCP Vision", error);
                    return Mono.error(new OcrException("Failed to extract raw text", error));
                });
    }

    @Override
    public Mono<ImageQualityResult> validateImageQuality(String imageBase64) {
        log.info("Validating image quality via GCP Vision");

        return visionService.validateImageQuality(imageBase64)
                .map(libraryResult -> new ImageQualityResult(
                        libraryResult.isValid(),
                        libraryResult.qualityScore(),
                        libraryResult.message(),
                        libraryResult.metrics()
                ))
                .onErrorResume(error -> {
                    log.error("Error validating image quality via GCP Vision", error);
                    return Mono.error(new OcrException("Failed to validate image quality", error));
                });
    }

    @Override
    public Mono<Double> getProcessingConfidence(String reportType) {
        // GCP Vision confidence varies by report type
        // Generally similar to Textract but slightly lower for tables
        return Mono.just(switch (reportType) {
            case "BLOOD_TEST", "URINE_TEST" -> 0.88; // Good for tabular data
            case "RADIOLOGY" -> 0.72; // Lower for image-heavy reports
            case "PATHOLOGY" -> 0.78; // Medium confidence
            case "PRESCRIPTION" -> 0.82; // Good for forms
            default -> 0.68; // Default confidence
        });
    }

    // ========================================================================
    // Helper Methods: Language Hints and Text Parsing
    // ========================================================================

    /**
     * Extract language hints from processing options.
     * Defaults to Bangla + English for medical documents.
     */
    private List<String> extractLanguageHints(Map<String, Object> options) {
        if (options == null) {
            return List.of("bn", "en"); // Default: Bangla + English
        }

        Object langHints = options.get("languageHints");
        if (langHints instanceof List) {
            return ((List<?>) langHints).stream()
                    .map(Object::toString)
                    .toList();
        }

        Object lang = options.get("language");
        if (lang != null) {
            return buildLanguageHints(lang.toString());
        }

        return List.of("bn", "en"); // Default
    }

    /**
     * Build language hints from single language code.
     */
    private List<String> buildLanguageHints(String language) {
        if (language == null || language.isBlank()) {
            return List.of("bn", "en");
        }

        return switch (language.toLowerCase()) {
            case "bn", "bengali", "bangla" -> List.of("bn", "en");
            case "en", "english" -> List.of("en");
            case "hi", "hindi" -> List.of("hi", "en");
            default -> List.of(language, "en");
        };
    }

    /**
     * Extract test results from Vision OCR response.
     *
     * Strategy:
     * 1. Use full text for pattern-based parsing (same as Textract)
     * 2. Use block geometry for confidence scoring
     * 3. Support multi-language test names
     */
    private Flux<TestResult> extractTestResultsFromVisionResponse(
            VisionOcrResponse response,
            String reportType
    ) {
        log.debug("Extracting test results from Vision response with {} blocks", response.blocks().size());

        List<TestResult> results = new ArrayList<>();

        // Strategy: Parse full text using pattern matching
        String rawText = response.fullText();
        if (rawText != null && !rawText.isEmpty()) {
            results.addAll(parseTestResultsFromRawText(rawText, reportType));
            log.info("Extracted {} test results from raw text analysis", results.size());
        }

        return Flux.fromIterable(results);
    }

    /**
     * Parse test results from raw text using pattern matching.
     * (Reuse same parsing logic as TextractAdapter for consistency)
     */
    private List<TestResult> parseTestResultsFromRawText(String rawText, String reportType) {
        // TODO: Implement pattern matching logic
        // This will be identical to TextractAdapter's parsing logic
        return List.of();
    }

    /**
     * Custom exception for OCR operations.
     */
    public static class OcrException extends RuntimeException {
        public OcrException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

---

## 7. Provider Selection Mechanism

### Configuration-Based Selection

The provider selection uses Spring Boot's `@ConditionalOnProperty` annotation:

```java
// AWS Textract Adapter (existing - modified)
@Component
@ConditionalOnProperty(name = "ocr.provider", havingValue = "aws", matchIfMissing = true)
public class TextractAdapter implements OcrPort {
    private final TextractService textractService;
    // ... implementation
}

// GCP Vision Adapter (new)
@Component
@ConditionalOnProperty(name = "ocr.provider", havingValue = "gcp")
public class VisionAdapter implements OcrPort {
    private final VisionService visionService;
    // ... implementation
}
```

### Selection Logic

| Property Value | Active Adapter | Notes |
|---------------|----------------|-------|
| `ocr.provider=aws` | `TextractAdapter` | AWS Textract |
| `ocr.provider=gcp` | `VisionAdapter` | GCP Vision |
| (unset) | `TextractAdapter` | Default to AWS |

### No Business Logic Changes

The `MedicalReportOrchestrationService` depends only on `OcrPort`:

```java
@Service
public class MedicalReportOrchestrationService {
    private final OcrPort ocrPort;  // Injected by Spring - AWS or GCP

    // No changes needed - works with both providers
}
```

---

## 8. Configuration

### 8.1 Application Properties (AWS - Default)

```properties
# application.properties
ocr.provider=aws

# AWS Textract Configuration
aws.textract.enabled=true
aws.textract.min-confidence-threshold=0.80
aws.textract.max-image-size-mb=10
```

### 8.2 Application Properties (GCP)

```properties
# application-gcp.properties
ocr.provider=gcp

# GCP Common Configuration
gcp.project-id=medscribe-ai-prod
gcp.credentials-path=/etc/secrets/gcp-service-account.json
# OR for K8s/Docker:
# gcp.credentials-json=${GCP_CREDENTIALS_JSON}

# GCP Vision Configuration
gcp.vision.enabled=true
gcp.vision.min-confidence-threshold=0.80
gcp.vision.max-image-size-mb=20
gcp.vision.default-language-hints=bn,en
gcp.vision.timeout-ms=30000
```

### 8.3 Environment Variables

```bash
# Docker/K8s deployment
export GCP_PROJECT_ID=medscribe-ai-prod
export GCP_CREDENTIALS_JSON=$(cat service-account.json)
export OCR_PROVIDER=gcp

# Run application
java -jar medscribe-ai.jar --spring.profiles.active=gcp
```

---

## 9. API Examples

### 9.1 Multi-Image Processing with Language Hints

**Request:**
```http
POST /api/v1/medical-report/process-multi-image
Content-Type: application/json

{
  "images": ["base64_image1", "base64_image2"],
  "patientContext": {
    "patientId": "P12345",
    "age": 45,
    "gender": "MALE",
    "medicalHistory": ["Diabetes Type 2"]
  },
  "workflowOptions": {
    "language": "bn",
    "languageHints": ["bn", "en"],
    "includeRiskAssessment": true
  }
}
```

**Processing Flow:**
1. `VisionAdapter.validateImageQuality()` - Check image quality
2. `VisionAdapter.extractMedicalData()` - Extract with Bangla hints
3. `VisionAdapter.extractRawText()` - Get raw text with Bangla support
4. AWS Comprehend Medical - Classify entities
5. AWS Bedrock - Generate insights

**Response:**
```json
{
  "reportId": "RPT-ABC123",
  "status": "COMPLETED",
  "ocrProvider": "gcp",
  "extractedData": [
    {
      "testName": "রক্তে গ্লুকোজ",  // Blood Glucose in Bangla
      "testValue": "7.2",
      "unit": "mmol/L",
      "status": "ABNORMAL",
      "confidence": 0.92
    }
  ]
}
```

### 9.2 Direct OCR with GCP Vision

**Request:**
```http
POST /api/aws/vision/detect-document-text
Content-Type: application/json

{
  "imageBase64": "base64_encoded_image...",
  "languageHints": ["bn", "en"]
}
```

**Response:**
```json
{
  "fullText": "রক্ত পরীক্ষার রিপোর্ট\nBlood Test Report\n...",
  "blocks": [
    {
      "text": "রক্ত পরীক্ষার রিপোর্ট",
      "detectedLanguage": "bn",
      "confidence": 0.95,
      "geometry": { ... }
    }
  ],
  "averageConfidence": 0.91
}
```

---

## 10. Testing Strategy

### 10.1 Unit Tests

#### VisionServiceImplTest
```java
@Test
void testDetectDocumentText_WithBanglaHints() {
    ImageAnnotatorClient mockClient = mock(ImageAnnotatorClient.class);
    when(mockClient.batchAnnotateImages(any()))
        .thenReturn(createMockBanglaResponse());

    VisionServiceImpl service = new VisionServiceImpl(mockClient, properties);
    VisionOcrRequest request = VisionOcrRequest.banglaEnglish(TEST_IMAGE);

    VisionOcrResponse response = service.detectDocumentText(request).block();

    assertThat(response.fullText()).contains("রক্ত");
    assertThat(response.blocks()).isNotEmpty();
}
```

#### VisionAdapterTest
```java
@Test
void testExtractMedicalData_WithLanguageHints() {
    VisionService mockService = mock(VisionService.class);
    when(mockService.detectDocumentText(any()))
        .thenReturn(Mono.just(createMockResponse()));

    VisionAdapter adapter = new VisionAdapter(mockService);
    Map<String, Object> options = Map.of("language", "bn");

    List<TestResult> results = adapter.extractMedicalData(
        TEST_IMAGE, "BLOOD_TEST", options
    ).collectList().block();

    assertThat(results).isNotEmpty();
    verify(mockService).detectDocumentText(argThat(req ->
        req.languageHints().contains("bn")
    ));
}
```

### 10.2 Integration Tests

#### Provider Selection Test
```java
@SpringBootTest(properties = "ocr.provider=gcp")
class GcpProviderSelectionTest {
    @Autowired
    private OcrPort ocrPort;

    @Test
    void shouldSelectGcpVisionAdapter() {
        assertThat(ocrPort).isInstanceOf(VisionAdapter.class);
    }
}

@SpringBootTest(properties = "ocr.provider=aws")
class AwsProviderSelectionTest {
    @Autowired
    private OcrPort ocrPort;

    @Test
    void shouldSelectTextractAdapter() {
        assertThat(ocrPort).isInstanceOf(TextractAdapter.class);
    }
}
```

### 10.3 End-to-End Tests

Test multi-image processing with real Bangla medical reports:

```java
@SpringBootTest
@ActiveProfiles("gcp-integration")
class MultiImageBanglaProcessingTest {

    @Autowired
    private MedicalReportOrchestrationUseCase orchestrationService;

    @Test
    void testProcessBanglaMedicalReport() {
        MultiImageRequest request = new MultiImageRequest(
            List.of(banglaReportImage),
            patientContext,
            WorkflowOptions.builder()
                .language("bn")
                .languageHints(List.of("bn", "en"))
                .build()
        );

        String reportId = orchestrationService
            .initiateMultiImageProcessing(request)
            .block();

        // Poll for completion
        MedicalReportResponse response = waitForCompletion(reportId);

        assertThat(response.getExtractedData())
            .isNotEmpty()
            .allMatch(test -> test.getConfidence() > 0.7);
    }
}
```

---

## 11. Implementation Phases

### Phase 1: GCP Common Module (Day 1)

**Tasks:**
1. Create `elioo-gcp-common` directory structure
2. Implement `build.gradle` with dependencies
3. Implement `GcpCommonProperties`
4. Implement `GcpCommonAutoConfiguration`
5. Create exception classes
6. Add Spring Boot auto-configuration imports
7. Write unit tests

**Deliverable:** Working GCP common module with credential loading

### Phase 2: GCP Vision Module (Days 1-2)

**Tasks:**
1. Create `elioo-gcp-vision` directory structure
2. Define `VisionService` interface
3. Implement DTOs: `VisionOcrRequest`, `VisionOcrResponse`, `TextBlock`, `TextGeometry`
4. Implement `VisionServiceImpl`
5. Implement `VisionProperties` and `VisionAutoConfiguration`
6. Add Spring Boot auto-configuration imports
7. Write unit tests

**Deliverable:** Working Vision service library

### Phase 3: GCP Starter Module (Day 2)

**Tasks:**
1. Create `elioo-gcp-spring-boot-starter` directory
2. Implement `build.gradle` aggregating GCP modules

**Deliverable:** Starter module for easy consumption

### Phase 4: Application Integration (Days 3-4)

**Tasks:**
1. Update `settings.gradle` to include GCP modules
2. Update `medscribe-ai/build.gradle` to add GCP starter dependency
3. Add `@ConditionalOnProperty` to `TextractAdapter`
4. Create `VisionAdapter` implementing `OcrPort`
5. Add `application-gcp.properties`
6. Write integration tests
7. Test multi-image processing with Bangla documents

**Deliverable:** Fully integrated GCP Vision provider

### Phase 5: Documentation & Testing (Day 5)

**Tasks:**
1. Create comprehensive `docs/GCP_VISION_INTEGRATION.md`
2. Update `CLAUDE.md` with GCP documentation
3. Performance testing (AWS vs GCP)
4. Load testing
5. Final regression testing

**Deliverable:** Complete documentation and tested system

---

## 12. File Inventory

### New Files (22 total)

#### elioo-gcp-common (6 files)
- `build.gradle`
- `GcpCommonAutoConfiguration.java`
- `GcpCommonProperties.java`
- `GcpServiceException.java`
- `GcpConfigurationException.java`
- `GcpValidationException.java`

#### elioo-gcp-vision (13 files)
- `build.gradle`
- `VisionService.java`
- `VisionServiceImpl.java`
- `VisionAutoConfiguration.java`
- `VisionProperties.java`
- `VisionOcrRequest.java`
- `VisionOcrResponse.java`
- `TextBlock.java`
- `TextParagraph.java`
- `TextWord.java`
- `TextSymbol.java`
- `TextGeometry.java`
- `ImageQualityResult.java`

#### elioo-gcp-spring-boot-starter (1 file)
- `build.gradle`

#### medscribe-ai (2 files)
- `VisionAdapter.java`
- `application-gcp.properties`

### Modified Files (4 total)
- `settings.gradle` - Add GCP module includes
- `medscribe-ai/build.gradle` - Add GCP starter dependency
- `TextractAdapter.java` - Add `@ConditionalOnProperty`
- `CLAUDE.md` - Add GCP documentation section

---

## 13. Dependencies

### Gradle Dependency Tree

```
medscribe-ai
└── elioo-gcp-spring-boot-starter
    ├── elioo-gcp-common
    │   ├── com.google.cloud:google-cloud-core (26.32.0)
    │   ├── com.google.auth:google-auth-library-oauth2-http
    │   └── io.projectreactor:reactor-core
    └── elioo-gcp-vision
        ├── elioo-gcp-common (transitive)
        ├── com.google.cloud:google-cloud-vision (3.32.0)
        └── io.projectreactor:reactor-core
```

### Version Management

```gradle
// Root build.gradle
dependencyManagement {
    imports {
        mavenBom 'com.google.cloud:libraries-bom:26.32.0'
        mavenBom 'org.springframework.boot:spring-boot-dependencies:3.4.2'
    }
}
```

---

## 14. Deployment Considerations

### 14.1 GCP Service Account Setup

**Required Steps:**
1. Create GCP project: `medscribe-ai-prod`
2. Enable Vision API: `gcloud services enable vision.googleapis.com`
3. Create service account: `medscribe-ai-vision@medscribe-ai-prod.iam.gserviceaccount.com`
4. Assign roles:
   - `roles/vision.user` - Vision API access
   - `roles/logging.logWriter` - Cloud Logging (optional)
5. Generate and download JSON key

### 14.2 Docker Deployment

**Dockerfile:**
```dockerfile
FROM openjdk:21-jdk-slim

# Copy service account JSON
COPY gcp-service-account.json /etc/secrets/gcp-service-account.json

# Set environment variables
ENV GCP_CREDENTIALS_PATH=/etc/secrets/gcp-service-account.json
ENV OCR_PROVIDER=gcp

COPY medscribe-ai.jar /app/medscribe-ai.jar

ENTRYPOINT ["java", "-jar", "/app/medscribe-ai.jar"]
```

### 14.3 Kubernetes Deployment

**Secret:**
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: gcp-credentials
type: Opaque
data:
  credentials.json: <base64-encoded-service-account-json>
```

**Deployment:**
```yaml
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
        - name: OCR_PROVIDER
          value: "gcp"
        - name: GCP_PROJECT_ID
          value: "medscribe-ai-prod"
        - name: GCP_CREDENTIALS_JSON
          valueFrom:
            secretKeyRef:
              name: gcp-credentials
              key: credentials.json
```

---

## 15. Risk Assessment

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| **GCP API Quota Limits** | High | Medium | Monitor quota usage, implement rate limiting, request quota increase |
| **Cost Overrun** | Medium | Low | Set budget alerts, implement caching for duplicate images |
| **Bangla OCR Accuracy** | High | Medium | Extensive testing with real reports, fallback to AWS if confidence < threshold |
| **Network Latency** | Medium | Low | Deploy in same region as GCP Vision, implement timeout handling |
| **Service Account Leak** | Critical | Low | Use K8s secrets, rotate keys regularly, audit access logs |
| **Breaking Changes in GCP SDK** | Low | Low | Pin dependency versions, test upgrades in staging |

---

## 16. Success Criteria

### Functional Requirements

- [ ] GCP Vision adapter successfully implements all `OcrPort` methods
- [ ] Provider switching works via configuration change
- [ ] Bangla medical reports are accurately extracted (>85% accuracy)
- [ ] Multi-image processing works with GCP Vision
- [ ] Language hints improve accuracy for non-English text

### Performance Requirements

- [ ] OCR processing time < 3 seconds per image (p95)
- [ ] No performance degradation compared to AWS Textract
- [ ] Multi-image processing completes within 15 minutes timeout

### Quality Requirements

- [ ] 90% test coverage for new GCP modules
- [ ] All integration tests passing
- [ ] No regressions in existing AWS Textract flow
- [ ] Documentation complete and reviewed

### Operational Requirements

- [ ] Deployed to staging environment
- [ ] Tested with production-like Bangla medical reports
- [ ] Monitoring and logging in place
- [ ] Cost tracking enabled

---

## Questions for Review

1. **Credential Management:** Do you prefer service account JSON file or environment variable approach for production?

2. **Language Defaults:** Is defaulting to `["bn", "en"]` for all medical reports appropriate, or should it be configurable per request?

3. **Fallback Strategy:** Should we implement automatic fallback to AWS Textract if GCP Vision fails or has low confidence?

4. **Cost Optimization:** Should we implement image size-based routing (small images to GCP, large to AWS)?

5. **Testing:** Do you have sample Bangla medical reports for testing, or should we generate synthetic test data?

6. **Deployment Timeline:** What's the preferred timeline for staging deployment vs production rollout?

---

## Appendix: File Paths Reference

### Patterns to Follow
- [TextractService.java](../elioo-aws-textract/src/main/java/com/elioo/healthcare/aws/textract/api/TextractService.java)
- [TextractAutoConfiguration.java](../elioo-aws-textract/src/main/java/com/elioo/healthcare/aws/textract/config/TextractAutoConfiguration.java)
- [TextractAdapter.java](../medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/TextractAdapter.java)

### Files to Modify
- [settings.gradle](../settings.gradle) - Line 21
- [medscribe-ai/build.gradle](../medscribe-ai/build.gradle)
- [TextractAdapter.java](../medscribe-ai/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/TextractAdapter.java) - Line 41-44

---

**End of Implementation Plan**

Please review this plan and provide feedback on:
- Architecture and design decisions
- Implementation approach
- Configuration strategy
- Testing coverage
- Any concerns or suggestions
