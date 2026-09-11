# Elioo AWS Bedrock - Health API Design

## Overview

The `elioo-aws-bedrock` library will provide **two layers of abstraction**:

1. **Low-Level API**: Direct Bedrock model invocation (generic, use-case agnostic)
2. **High-Level Health API**: Clinical/health-specific operations with pre-built prompt engineering

This design allows:
- Multiple health applications to reuse clinical insight generation logic
- Flexibility to use low-level API for custom use cases
- Separation of AWS infrastructure concerns from business logic

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Application Layer (medscribe-ai, other health apps)        │
│  - Business-specific domain models                           │
│  - Application-specific ports/adapters                       │
└────────────────────┬────────────────────────────────────────┘
                     │ depends on
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  elioo-aws-bedrock Library                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ High-Level Health API (NEW)                            │ │
│  │ - BedrockHealthService (interface)                     │ │
│  │ - BedrockHealthServiceImpl (default implementation)    │ │
│  │ - Health-specific DTOs (generic, reusable)             │ │
│  │ - Prompt templates for clinical operations             │ │
│  └────────────────────┬───────────────────────────────────┘ │
│                       │ uses                                 │
│                       ▼                                      │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Low-Level Bedrock API (Existing)                       │ │
│  │ - BedrockClient                                        │ │
│  │ - Model invocation, streaming, error handling          │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                     │ uses
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  AWS Bedrock Service (Claude 3)                              │
└─────────────────────────────────────────────────────────────┘
```

---

## High-Level Health API Design

### 1. Core Interface

```java
package com.elioo.aws.bedrock.health;

import reactor.core.publisher.Mono;

/**
 * High-level interface for health/clinical operations using AWS Bedrock.
 *
 * This interface provides pre-built prompt engineering and response parsing
 * for common clinical AI tasks.
 *
 * Implementations use BedrockClient internally but shield applications from
 * low-level model invocation details.
 */
public interface BedrockHealthService {

    /**
     * Generate comprehensive clinical insights from medical data.
     *
     * @param request Contains medical data, patient context, and options
     * @return Clinical insights including findings, recommendations, risk assessment
     */
    Mono<ClinicalInsightResponse> generateClinicalInsights(ClinicalInsightRequest request);

    /**
     * Generate a patient-friendly or provider-friendly summary.
     *
     * @param request Medical data and summary options (audience, length, focus)
     * @return Formatted summary text
     */
    Mono<SummaryResponse> generateSummary(SummaryRequest request);

    /**
     * Assess clinical risk across multiple organ systems.
     *
     * @param request Medical data and risk assessment options
     * @return Risk scores and explanations for different risk categories
     */
    Mono<RiskAssessmentResponse> assessRisk(RiskAssessmentRequest request);

    /**
     * Generate evidence-based clinical recommendations.
     *
     * @param request Medical findings and recommendation options
     * @return Categorized recommendations with evidence levels
     */
    Mono<RecommendationResponse> generateRecommendations(RecommendationRequest request);

    /**
     * Analyze temporal trends in medical test results.
     *
     * @param request Historical test data and trend analysis options
     * @return Trend analysis with patterns, predictions, and alerts
     */
    Mono<TrendAnalysisResponse> analyzeTrends(TrendAnalysisRequest request);

    /**
     * Generate patient education materials.
     *
     * @param request Medical topic and educational content options
     * @return Educational content with appropriate reading level
     */
    Mono<EducationalContentResponse> generateEducationalContent(EducationalContentRequest request);

    /**
     * Custom prompt execution with structured response parsing.
     *
     * Allows applications to use custom prompts while benefiting from
     * the library's response parsing and error handling.
     *
     * @param prompt Custom prompt text
     * @param responseClass Expected response type
     * @return Parsed response
     */
    <T> Mono<T> executeCustomPrompt(String prompt, Class<T> responseClass);
}
```

---

### 2. Generic Health DTOs

These DTOs are **domain-agnostic** and can be used across multiple health applications.

#### 2.1 Clinical Insight Request/Response

```java
package com.elioo.aws.bedrock.health.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicalInsightRequest {

    /**
     * Structured medical data (test results, classifications, etc.)
     */
    private Map<String, Object> medicalData;

    /**
     * Patient demographics and history
     */
    private PatientContext patientContext;

    /**
     * Options for insight generation
     */
    private InsightOptions options;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientContext {
    private Integer age;
    private String gender; // or enum
    private List<String> medicalHistory;
    private List<String> currentMedications;
    private Map<String, String> vitalSigns;
    private Map<String, Object> additionalContext;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsightOptions {
    private TargetAudience targetAudience; // PATIENT, PROVIDER, RESEARCHER
    private boolean includeRiskAssessment;
    private boolean includeRecommendations;
    private boolean includeEducationalContent;
    private String focusArea; // "cardiovascular", "metabolic", etc.
    private Integer maxRecommendations;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicalInsightResponse {

    /**
     * Executive summary of findings
     */
    private String summary;

    /**
     * Key clinical findings with severity levels
     */
    private List<ClinicalFinding> keyFindings;

    /**
     * Evidence-based recommendations
     */
    private List<ClinicalRecommendation> recommendations;

    /**
     * Risk assessment across organ systems
     */
    private RiskAssessment riskAssessment;

    /**
     * Action plan with prioritized steps
     */
    private ActionPlan actionPlan;

    /**
     * AI confidence score (0.0 - 1.0)
     */
    private Double confidence;

    /**
     * Additional metadata
     */
    private Map<String, Object> metadata;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicalFinding {
    private String id;
    private String description;
    private String category; // e.g., "LAB_ABNORMALITY", "RISK_FACTOR"
    private String severity; // "LOW", "MODERATE", "HIGH", "CRITICAL"
    private String explanation;
    private List<String> relatedTests;
    private Map<String, Object> details;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicalRecommendation {
    private String id;
    private String category; // "IMMEDIATE_ACTION", "DIAGNOSTIC_TESTS", "MEDICATION", etc.
    private String priority; // "URGENT", "HIGH", "MEDIUM", "LOW"
    private String recommendation;
    private String rationale;
    private String evidenceLevel; // "A", "B", "C" (evidence-based medicine)
    private String timeframe; // "Immediate", "Within 24 hours", "1-2 weeks", etc.
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessment {
    private Map<String, RiskScore> riskScores; // e.g., "cardiovascular", "metabolic"
    private String overallRiskLevel; // "LOW", "MODERATE", "HIGH"
    private List<String> riskFactors;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskScore {
    private String category;
    private String level; // "LOW", "MODERATE", "HIGH", "CRITICAL"
    private Double score; // Numerical score if available
    private String explanation;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionPlan {
    private List<ActionItem> immediateActions;
    private List<ActionItem> shortTermActions; // 1-2 weeks
    private List<ActionItem> longTermActions; // 1+ months
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionItem {
    private String action;
    private String priority;
    private String timeframe;
    private String category;
}

public enum TargetAudience {
    PATIENT,        // Patient-friendly language
    PROVIDER,       // Medical professional terminology
    RESEARCHER      // Scientific/research context
}
```

#### 2.2 Summary Request/Response

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryRequest {
    private Map<String, Object> medicalData;
    private PatientContext patientContext;
    private SummaryOptions options;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryOptions {
    private TargetAudience targetAudience;
    private SummaryLength length; // BRIEF, STANDARD, DETAILED
    private String focusArea; // Optional: focus on specific aspect
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryResponse {
    private String summary;
    private String title;
    private List<String> keyPoints;
}

public enum SummaryLength {
    BRIEF,      // 1-2 sentences
    STANDARD,   // 1 paragraph
    DETAILED    // Multiple paragraphs
}
```

#### 2.3 Risk Assessment Request/Response

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessmentRequest {
    private Map<String, Object> medicalData;
    private PatientContext patientContext;
    private RiskAssessmentOptions options;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessmentOptions {
    private List<String> riskCategories; // e.g., ["cardiovascular", "metabolic", "renal"]
    private boolean includePreventionStrategies;
    private Integer timeHorizon; // Risk assessment time horizon in months
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessmentResponse {
    private RiskAssessment riskAssessment;
    private List<String> preventionStrategies;
    private String overallAssessment;
}
```

#### 2.4 Recommendation Request/Response

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationRequest {
    private Map<String, Object> medicalFindings;
    private PatientContext patientContext;
    private RecommendationOptions options;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationOptions {
    private List<String> categories; // Filter by category
    private boolean includeEvidenceLevels;
    private Integer maxRecommendations;
    private String priorityFilter; // "URGENT_ONLY", "HIGH_AND_URGENT", "ALL"
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationResponse {
    private List<ClinicalRecommendation> recommendations;
    private String summary;
}
```

#### 2.5 Trend Analysis Request/Response

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendAnalysisRequest {
    private List<TimeSeriesDataPoint> historicalData;
    private PatientContext patientContext;
    private TrendAnalysisOptions options;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesDataPoint {
    private String timestamp; // ISO 8601 format
    private String testName;
    private Double value;
    private String unit;
    private Map<String, Object> metadata;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendAnalysisOptions {
    private List<String> testsToAnalyze; // Specific tests to focus on
    private boolean detectAnomalies;
    private boolean predictFutureValues;
    private Integer predictionHorizon; // Days into future
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendAnalysisResponse {
    private List<TrendPattern> patterns;
    private List<Anomaly> anomalies;
    private Map<String, Prediction> predictions;
    private String overallTrendSummary;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendPattern {
    private String testName;
    private String pattern; // "INCREASING", "DECREASING", "STABLE", "FLUCTUATING"
    private String clinicalSignificance;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Anomaly {
    private String timestamp;
    private String testName;
    private Double value;
    private String explanation;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Prediction {
    private String testName;
    private Double predictedValue;
    private String unit;
    private Double confidenceInterval;
    private String timeframe;
}
```

#### 2.6 Educational Content Request/Response

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EducationalContentRequest {
    private String topic; // e.g., "high creatinine", "diabetes management"
    private PatientContext patientContext; // Optional: personalize content
    private EducationalContentOptions options;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EducationalContentOptions {
    private ReadingLevel readingLevel; // SIMPLE, INTERMEDIATE, ADVANCED
    private ContentFormat format; // TEXT, BULLET_POINTS, FAQ
    private Integer maxLength; // Max words/characters
    private boolean includeDiagrams; // Request diagram descriptions
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EducationalContentResponse {
    private String title;
    private String content;
    private List<String> keyTakeaways;
    private List<FAQ> faqs;
    private List<String> additionalResources;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FAQ {
    private String question;
    private String answer;
}

public enum ReadingLevel {
    SIMPLE,         // 6th-8th grade
    INTERMEDIATE,   // High school
    ADVANCED        // College/medical professional
}

public enum ContentFormat {
    TEXT,           // Narrative paragraphs
    BULLET_POINTS,  // Bulleted list
    FAQ             // Question-answer format
}
```

---

### 3. Default Implementation

```java
package com.elioo.aws.bedrock.health.impl;

import com.elioo.aws.bedrock.BedrockClient;
import com.elioo.aws.bedrock.health.BedrockHealthService;
import com.elioo.aws.bedrock.health.dto.*;
import com.elioo.aws.bedrock.health.prompt.PromptTemplateEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@RequiredArgsConstructor
public class BedrockHealthServiceImpl implements BedrockHealthService {

    private final BedrockClient bedrockClient;
    private final PromptTemplateEngine promptEngine;
    private final ObjectMapper objectMapper;
    private final BedrockHealthConfiguration config;

    @Override
    public Mono<ClinicalInsightResponse> generateClinicalInsights(ClinicalInsightRequest request) {
        return Mono.fromCallable(() -> {
                    // Build structured prompt using template engine
                    String prompt = promptEngine.buildClinicalInsightPrompt(
                            request.getMedicalData(),
                            request.getPatientContext(),
                            request.getOptions()
                    );
                    return prompt;
                })
                .flatMap(prompt -> bedrockClient.invokeModel(
                        config.getDefaultModelId(),
                        prompt,
                        config.getDefaultModelParams()
                ))
                .map(response -> parseResponse(response, ClinicalInsightResponse.class))
                .doOnSuccess(response -> log.info("Generated clinical insights with {} findings",
                        response.getKeyFindings().size()))
                .onErrorResume(e -> {
                    log.error("Error generating clinical insights", e);
                    return Mono.error(new BedrockHealthServiceException("Failed to generate clinical insights", e));
                });
    }

    @Override
    public Mono<SummaryResponse> generateSummary(SummaryRequest request) {
        return Mono.fromCallable(() ->
                        promptEngine.buildSummaryPrompt(
                                request.getMedicalData(),
                                request.getPatientContext(),
                                request.getOptions()
                        ))
                .flatMap(prompt -> bedrockClient.invokeModel(
                        config.getDefaultModelId(),
                        prompt,
                        config.getDefaultModelParams()
                ))
                .map(response -> parseResponse(response, SummaryResponse.class));
    }

    @Override
    public Mono<RiskAssessmentResponse> assessRisk(RiskAssessmentRequest request) {
        return Mono.fromCallable(() ->
                        promptEngine.buildRiskAssessmentPrompt(
                                request.getMedicalData(),
                                request.getPatientContext(),
                                request.getOptions()
                        ))
                .flatMap(prompt -> bedrockClient.invokeModel(
                        config.getDefaultModelId(),
                        prompt,
                        config.getDefaultModelParams()
                ))
                .map(response -> parseResponse(response, RiskAssessmentResponse.class));
    }

    @Override
    public Mono<RecommendationResponse> generateRecommendations(RecommendationRequest request) {
        return Mono.fromCallable(() ->
                        promptEngine.buildRecommendationPrompt(
                                request.getMedicalFindings(),
                                request.getPatientContext(),
                                request.getOptions()
                        ))
                .flatMap(prompt -> bedrockClient.invokeModel(
                        config.getDefaultModelId(),
                        prompt,
                        config.getDefaultModelParams()
                ))
                .map(response -> parseResponse(response, RecommendationResponse.class));
    }

    @Override
    public Mono<TrendAnalysisResponse> analyzeTrends(TrendAnalysisRequest request) {
        return Mono.fromCallable(() ->
                        promptEngine.buildTrendAnalysisPrompt(
                                request.getHistoricalData(),
                                request.getPatientContext(),
                                request.getOptions()
                        ))
                .flatMap(prompt -> bedrockClient.invokeModel(
                        config.getDefaultModelId(),
                        prompt,
                        config.getDefaultModelParams()
                ))
                .map(response -> parseResponse(response, TrendAnalysisResponse.class));
    }

    @Override
    public Mono<EducationalContentResponse> generateEducationalContent(EducationalContentRequest request) {
        return Mono.fromCallable(() ->
                        promptEngine.buildEducationalContentPrompt(
                                request.getTopic(),
                                request.getPatientContext(),
                                request.getOptions()
                        ))
                .flatMap(prompt -> bedrockClient.invokeModel(
                        config.getDefaultModelId(),
                        prompt,
                        config.getDefaultModelParams()
                ))
                .map(response -> parseResponse(response, EducationalContentResponse.class));
    }

    @Override
    public <T> Mono<T> executeCustomPrompt(String prompt, Class<T> responseClass) {
        return bedrockClient.invokeModel(
                        config.getDefaultModelId(),
                        prompt,
                        config.getDefaultModelParams()
                )
                .map(response -> parseResponse(response, responseClass));
    }

    private <T> T parseResponse(String jsonResponse, Class<T> responseClass) {
        try {
            return objectMapper.readValue(jsonResponse, responseClass);
        } catch (Exception e) {
            log.error("Failed to parse Bedrock response", e);
            throw new BedrockHealthServiceException("Failed to parse response", e);
        }
    }
}
```

---

### 4. Prompt Template Engine

```java
package com.elioo.aws.bedrock.health.prompt;

import java.util.Map;

/**
 * Builds structured prompts for clinical AI operations.
 *
 * Encapsulates prompt engineering best practices for medical AI.
 */
public interface PromptTemplateEngine {

    String buildClinicalInsightPrompt(
            Map<String, Object> medicalData,
            PatientContext patientContext,
            InsightOptions options
    );

    String buildSummaryPrompt(
            Map<String, Object> medicalData,
            PatientContext patientContext,
            SummaryOptions options
    );

    String buildRiskAssessmentPrompt(
            Map<String, Object> medicalData,
            PatientContext patientContext,
            RiskAssessmentOptions options
    );

    String buildRecommendationPrompt(
            Map<String, Object> medicalFindings,
            PatientContext patientContext,
            RecommendationOptions options
    );

    String buildTrendAnalysisPrompt(
            List<TimeSeriesDataPoint> historicalData,
            PatientContext patientContext,
            TrendAnalysisOptions options
    );

    String buildEducationalContentPrompt(
            String topic,
            PatientContext patientContext,
            EducationalContentOptions options
    );
}
```

---

### 5. Configuration

```java
package com.elioo.aws.bedrock.health.config;

import lombok.Data;
import java.util.Map;

@Data
public class BedrockHealthConfiguration {

    /**
     * Default Bedrock model ID for health operations
     */
    private String defaultModelId = "anthropic.claude-3-5-sonnet-20241022-v2:0";

    /**
     * Default model invocation parameters
     */
    private Map<String, Object> defaultModelParams = Map.of(
            "max_tokens", 4096,
            "temperature", 0.7,
            "top_p", 0.9
    );

    /**
     * Prompt template configuration
     */
    private PromptTemplateConfig promptConfig;

    /**
     * Enable/disable specific features
     */
    private FeatureFlags featureFlags;
}

@Data
public class PromptTemplateConfig {
    private String systemPromptPath = "classpath:prompts/system-prompt.txt";
    private Map<String, String> customTemplates; // Override default templates
}

@Data
public class FeatureFlags {
    private boolean enableCaching = true;
    private boolean enableStreamingResponses = false;
    private boolean validateInputs = true;
}
```

---

## How Applications Use the Library

### Example 1: medscribe-ai Using High-Level API

```java
// In medscribe-ai application
@Component
@RequiredArgsConstructor
public class BedrockAdapter implements ClinicalInsightPort {

    private final BedrockHealthService bedrockHealthService; // Injected from library

    @Override
    public Mono<SuggestionsResponse> generateClinicalInsights(InsightRequest domainRequest) {
        // Map application domain objects to library DTOs
        ClinicalInsightRequest libraryRequest = ClinicalInsightRequest.builder()
                .medicalData(convertToMap(domainRequest.getExtractedData()))
                .patientContext(mapPatientContext(domainRequest.getPatientContext()))
                .options(mapOptions(domainRequest.getOptions()))
                .build();

        // Call library
        return bedrockHealthService.generateClinicalInsights(libraryRequest)
                .map(this::mapToApplicationResponse); // Map library DTO back to domain
    }

    private SuggestionsResponse mapToApplicationResponse(ClinicalInsightResponse libraryResponse) {
        // Transform library DTOs to application domain objects
        return SuggestionsResponse.builder()
                .summary(libraryResponse.getSummary())
                .keyFindings(mapFindings(libraryResponse.getKeyFindings()))
                .suggestions(mapRecommendations(libraryResponse.getRecommendations()))
                .riskAssessment(mapRiskAssessment(libraryResponse.getRiskAssessment()))
                .build();
    }
}
```

### Example 2: Another Health App Using High-Level API

```java
// In a different health application (e.g., pharmacy app)
@Service
@RequiredArgsConstructor
public class MedicationAdherenceService {

    private final BedrockHealthService bedrockHealthService;

    public Mono<EducationalContentResponse> generateMedicationGuidance(String medicationName, Patient patient) {
        EducationalContentRequest request = EducationalContentRequest.builder()
                .topic("How to take " + medicationName)
                .patientContext(PatientContext.builder()
                        .age(patient.getAge())
                        .medicalHistory(patient.getConditions())
                        .build())
                .options(EducationalContentOptions.builder()
                        .readingLevel(ReadingLevel.SIMPLE)
                        .format(ContentFormat.BULLET_POINTS)
                        .build())
                .build();

        return bedrockHealthService.generateEducationalContent(request);
    }
}
```

### Example 3: Using Low-Level API for Custom Use Case

```java
// When high-level API doesn't fit - use low-level API
@Service
@RequiredArgsConstructor
public class CustomDiagnosticService {

    private final BedrockClient bedrockClient; // Low-level API

    public Mono<String> generateCustomDifferentialDiagnosis(String symptoms) {
        String customPrompt = buildCustomPrompt(symptoms);

        return bedrockClient.invokeModel(
                "anthropic.claude-3-5-sonnet-20241022-v2:0",
                customPrompt,
                Map.of("max_tokens", 2048, "temperature", 0.5)
        );
    }
}
```

---

## Benefits of This Design

### 1. **Separation of Concerns**
- **Library**: Handles AWS integration + health domain prompts (reusable)
- **Application**: Handles business logic + application-specific domain models

### 2. **Reusability**
- Multiple health apps can use the same clinical insight generation logic
- No need to duplicate prompt engineering across applications

### 3. **Flexibility**
- Applications can use high-level API (BedrockHealthService) for common use cases
- Applications can use low-level API (BedrockClient) for custom use cases
- Applications can override/extend default implementations

### 4. **Testability**
- Applications can mock BedrockHealthService interface
- Library can be tested independently with unit tests

### 5. **Domain Independence**
- Library DTOs are generic (not tied to medscribe-ai domain)
- Applications map between library DTOs and their own domain models
- Library can evolve independently of applications

---

## Migration Path for medscribe-ai

1. **Extract generic DTOs** from medscribe-ai to library
2. **Extract prompt templates** to library
3. **Create BedrockHealthServiceImpl** in library
4. **Update BedrockAdapter** in medscribe-ai to use library
5. **Remove duplicated code** from medscribe-ai

---

## Questions to Resolve

1. **Should library DTOs use Java records or classes?**
   - Records: Immutable, concise
   - Classes: More flexible (inheritance, mutable if needed)

2. **How should we handle prompt template customization?**
   - Option A: Applications provide custom templates via configuration
   - Option B: Applications extend PromptTemplateEngine
   - Option C: Both

3. **Should the library provide Spring Boot auto-configuration?**
   - Yes: Easy integration for Spring Boot apps
   - No: Keep library framework-agnostic

4. **Should we include response caching in the library?**
   - Yes: Cache identical requests (cost optimization)
   - No: Let applications handle caching

5. **How should we version the library DTOs?**
   - Semantic versioning
   - Separate DTO versioning (v1, v2 packages)

---

## Next Steps

1. **Review this design** with team
2. **Decide on open questions** above
3. **Create library module structure**
4. **Implement BedrockHealthService interface and DTOs**
5. **Implement PromptTemplateEngine**
6. **Implement BedrockHealthServiceImpl**
7. **Write unit tests** for library
8. **Update medscribe-ai** to use library
9. **Document** usage examples

