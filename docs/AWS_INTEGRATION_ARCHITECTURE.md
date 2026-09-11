# AWS Integration Architecture

> Domain-Driven Design and Clean Architecture approach for AWS service integration

## Table of Contents

1. [Architectural Decision](#architectural-decision)
2. [Design Principles](#design-principles)
3. [Architecture Overview](#architecture-overview)
4. [Component Details](#component-details)
5. [Configuration](#configuration)
6. [Usage Examples](#usage-examples)
7. [Testing Strategy](#testing-strategy)
8. [Deployment Guide](#deployment-guide)

---

## Architectural Decision

### The Question

When integrating AWS services (Textract, Comprehend Medical, Bedrock), should they be:

**Option A:** Separate bounded contexts with full Clean Architecture?
```
aws/
├── textract/
│   ├── domain/
│   ├── application/
│   └── adapter/
├── comprehend/
│   ├── domain/
│   └── ...
```

**Option B:** Infrastructure adapters implementing business-defined ports?
```
medicalreport/
├── domain/                    # Business entities
├── application/
│   ├── port/in/              # Use cases
│   └── port/out/             # Interfaces AWS implements
└── adapter/
    ├── in/web/               # HTTP handlers
    └── out/aws/              # AWS implementations
```

### The Answer: Option B ✅

**AWS services are infrastructure adapters, not business domains.**

### Reasoning

#### 1. **They are Technical Capabilities, Not Business Capabilities**

| Aspect | Business Domain | Infrastructure Service |
|--------|----------------|----------------------|
| **OCR Processing** | ✅ Core business capability | |
| **AWS Textract** | | ✅ Technical implementation |
| **Medical Classification** | ✅ Core business capability | |
| **AWS Comprehend Medical** | | ✅ Technical implementation |
| **Clinical Insights** | ✅ Core business capability | |
| **AWS Bedrock** | | ✅ Technical implementation |

**Example:**
- "Extract medical data from documents" = **Business requirement**
- "Use AWS Textract" = **Implementation detail**

#### 2. **Follows Dependency Rule (Clean Architecture)**

```
┌─────────────────────────────────────────────┐
│           Domain Layer (Innermost)          │
│         - TestResult                        │
│         - TestStatus                        │
│         - Pure business logic               │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│         Application Layer                   │
│    ┌────────────────────────────────────┐   │
│    │  Port/In (Use Cases)              │   │
│    │  - What the business needs        │   │
│    └────────────────────────────────────┘   │
│    ┌────────────────────────────────────┐   │
│    │  Port/Out (Interfaces)            │   │
│    │  - OcrPort                         │   │
│    │  - MedicalClassificationPort       │   │
│    │  - ClinicalInsightPort             │   │
│    └────────────────────────────────────┘   │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│         Adapter Layer (Outermost)           │
│    ┌────────────────────────────────────┐   │
│    │  AWS Implementations               │   │
│    │  - TextractAdapter                 │   │
│    │  - ComprehendMedicalAdapter        │   │
│    │  - BedrockAdapter                  │   │
│    └────────────────────────────────────┘   │
│                                             │
│  Can also have:                             │
│  - AzureOcrAdapter                          │
│  - GoogleHealthNlpAdapter                   │
│  - MockOcrAdapter (for testing)             │
└─────────────────────────────────────────────┘
```

**Key Point:** Dependencies point inward. Business logic never depends on AWS.

#### 3. **Enables Anti-Corruption Layer Pattern**

The adapters act as an **Anti-Corruption Layer** between your domain and AWS:

```java
// AWS returns complex structure
DetectEntitiesV2Response awsResponse = comprehendMedicalClient.detectEntitiesV2(...);

// Adapter translates to domain objects
List<MedicalEntity> domainEntities = mapToMedicalEntities(awsResponse);

// Business logic works with clean domain objects, never AWS types
ClassificationResult result = new ClassificationResult(domainEntities, ...);
```

#### 4. **Provider Independence**

With this architecture, switching providers is straightforward:

```java
// Original: AWS implementation
@Component
public class TextractAdapter implements OcrPort { ... }

// New: Azure implementation (same interface)
@Component
@Primary // Override AWS adapter
public class AzureVisionAdapter implements OcrPort { ... }

// Business logic unchanged - still uses OcrPort interface
```

#### 5. **Testing Benefits**

```java
// Unit test with mock adapter
@Test
void testOcrProcessing() {
    OcrPort mockOcr = mock(OcrPort.class);
    when(mockOcr.extractMedicalData(...))
        .thenReturn(Flux.just(testResult));

    // Test business logic without AWS
    service.processDocument(request);
}
```

---

## Design Principles

### 1. **Hexagonal Architecture (Ports & Adapters)**

```
        ┌─────────────────────────────────┐
        │      Web Layer (HTTP)          │
        │    ─────────────────           │
        │   │ MedicalReportHandler │      │
        │    ─────────────────           │
        └──────────────┬──────────────────┘
                       │ calls
        ┌──────────────▼──────────────────┐
        │    Application Layer            │
        │   ┌──────────────────────┐      │
        │   │ MedicalReportService │      │
        │   └──────────┬───────────┘      │
        │              │ uses              │
        │   ┌──────────▼───────────┐      │
        │   │    OcrPort (interface)│     │
        │   │ MedicalClassificationPort│  │
        │   │ ClinicalInsightPort    │    │
        │   └────────────────────────┘    │
        └──────────────┬──────────────────┘
                       │ implemented by
        ┌──────────────▼──────────────────┐
        │    Adapter Layer (AWS)          │
        │   ┌──────────────────────┐      │
        │   │  TextractAdapter     │      │
        │   │ ComprehendMedicalAdapter│   │
        │   │  BedrockAdapter      │      │
        │   └──────────────────────┘      │
        └─────────────────────────────────┘
```

### 2. **Dependency Inversion Principle**

High-level modules (business logic) don't depend on low-level modules (AWS SDK).
Both depend on abstractions (port interfaces).

```java
// ❌ WRONG: Business depends on AWS
public class MedicalReportService {
    private TextractAsyncClient textractClient; // Direct AWS dependency!
}

// ✅ CORRECT: Business depends on abstraction
public class MedicalReportService {
    private OcrPort ocrPort; // Interface dependency
    // Could be AWS, Azure, Google, or Mock - service doesn't know or care
}
```

### 3. **Single Responsibility Principle**

Each adapter has one responsibility:

- **TextractAdapter**: Translate between OCR business operations and AWS Textract API
- **ComprehendMedicalAdapter**: Translate between classification business operations and AWS Comprehend Medical API
- **BedrockAdapter**: Translate between AI insight business operations and AWS Bedrock API

---

## Architecture Overview

### Package Structure

```
com.elioo.healthcare.medicalreport/
│
├── domain/                              # Business Entities (Framework-agnostic)
│   ├── TestResult.java
│   ├── TestStatus.java
│   ├── ReportType.java
│   └── ... (other domain objects)
│
├── application/                         # Business Logic Layer
│   ├── port/
│   │   ├── in/                         # Inbound Ports (Use Cases)
│   │   │   └── MedicalReportUseCase.java
│   │   │
│   │   └── out/                        # Outbound Ports (Driven Interfaces)
│   │       ├── OcrPort.java            ← Business defines WHAT it needs
│   │       ├── MedicalClassificationPort.java
│   │       └── ClinicalInsightPort.java
│   │
│   └── service/                        # Use Case Implementations
│       └── MedicalReportService.java   (uses ports, not AWS directly)
│
└── adapter/                            # Infrastructure Layer
    ├── in/                            # Inbound Adapters (Driving)
    │   └── web/
    │       ├── MedicalReportHandler.java
    │       └── MedicalReportRouter.java
    │
    └── out/                           # Outbound Adapters (Driven)
        ├── aws/                       ← AWS implementations
        │   ├── TextractAdapter.java    (implements OcrPort)
        │   ├── ComprehendMedicalAdapter.java (implements MedicalClassificationPort)
        │   └── BedrockAdapter.java     (implements ClinicalInsightPort)
        │
        └── persistence/
            ├── MedicalReportEntity.java
            ├── MedicalReportRepository.java
            └── MedicalReportPersistenceAdapter.java
```

### Configuration Layer

```
com.elioo.healthcare.core/
└── config/
    └── AwsConfig.java                 # AWS SDK client beans
```

---

## Component Details

### 1. Outbound Ports (Interfaces)

#### OcrPort.java

```java
/**
 * Outbound port for OCR operations.
 * Defines WHAT the business needs, not HOW it's implemented.
 *
 * Possible implementations:
 * - AWS Textract
 * - Google Cloud Vision
 * - Azure Computer Vision
 * - Tesseract OCR
 */
public interface OcrPort {
    Flux<TestResult> extractMedicalData(String imageBase64, String reportType, Map<String, Object> options);
    Mono<String> extractRawText(String imageBase64, String language);
    Mono<ImageQualityResult> validateImageQuality(String imageBase64);
    Mono<Double> getProcessingConfidence(String reportType);
}
```

**Key Characteristics:**
- Framework-agnostic (uses Project Reactor, but no AWS types)
- Returns domain objects (`TestResult`, not AWS `Block`)
- Focused on business capabilities, not technical details

#### MedicalClassificationPort.java

```java
/**
 * Outbound port for medical entity classification.
 *
 * Possible implementations:
 * - AWS Comprehend Medical
 * - Azure Health Text Analytics
 * - Google Cloud Healthcare NLP
 * - Custom ML models
 */
public interface MedicalClassificationPort {
    Mono<ClassificationResult> classifyMedicalEntities(ClassificationRequest request);
    Mono<List<MedicalCode>> mapToMedicalCodes(String medicalTerm, List<String> codeSystems);
    Mono<List<EntityRelationship>> extractRelationships(List<MedicalEntity> entities);
    Mono<ValidationResult> validateClassification(ClassificationResult result);
}
```

#### ClinicalInsightPort.java

```java
/**
 * Outbound port for AI-powered clinical insights.
 *
 * Possible implementations:
 * - AWS Bedrock (Claude, Llama, etc.)
 * - Azure OpenAI
 * - Google Vertex AI
 * - OpenAI API
 */
public interface ClinicalInsightPort {
    Mono<ClinicalInsightResult> generateClinicalInsights(InsightRequest request);
    Mono<String> generateSummary(Map<String, Object> medicalData, String targetAudience);
    Mono<RiskAssessment> assessRisk(RiskAssessmentRequest request);
    Mono<List<Recommendation>> generateRecommendations(List<KeyFinding> findings, PatientContext context);
}
```

### 2. AWS Adapters (Implementations)

#### TextractAdapter.java

**Responsibilities:**
1. Transform business requests into AWS Textract API calls
2. Parse AWS responses into domain objects
3. Handle AWS-specific errors
4. Provide OCR-specific business logic (status determination, confidence scoring)

**Key Methods:**

```java
@Override
public Flux<TestResult> extractMedicalData(String imageBase64, String reportType, Map<String, Object> options) {
    // 1. Convert base64 to AWS format
    byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
    SdkBytes imageData = SdkBytes.fromByteArray(imageBytes);

    // 2. Build AWS request
    AnalyzeDocumentRequest request = AnalyzeDocumentRequest.builder()
            .document(Document.builder().bytes(imageData).build())
            .featureTypes(FeatureType.TABLES, FeatureType.FORMS)
            .build();

    // 3. Call AWS asynchronously
    return Mono.fromFuture(textractClient.analyzeDocument(request))
            // 4. Transform AWS response to domain objects
            .flatMapMany(response -> extractTestResultsFromBlocks(response.blocks(), reportType));
}
```

**Anti-Corruption Layer in Action:**

```java
// AWS model
Block block = textractResponse.blocks().get(0);
String awsText = block.text();
Double awsConfidence = block.confidence();

// Transform to domain model
TestResult domainResult = TestResult.builder()
        .testName(parseTestName(awsText))
        .testValue(parseTestValue(awsText))
        .status(determineStatus(value, range))  // Business logic
        .confidence(awsConfidence / 100.0)      // Normalize AWS format
        .build();
```

#### ComprehendMedicalAdapter.java

**Responsibilities:**
1. Detect medical entities using AWS Comprehend Medical
2. Map entities to medical codes (ICD-10, LOINC, SNOMED, RxNorm)
3. Extract entity relationships
4. Validate classification quality

**Key Methods:**

```java
@Override
public Mono<ClassificationResult> classifyMedicalEntities(ClassificationRequest request) {
    return detectEntitiesV2(request.text())
            .flatMap(entitiesResponse -> {
                List<MedicalEntity> entities = mapToMedicalEntities(entitiesResponse);

                Mono<List<EntityRelationship>> relationships =
                    request.extractRelationships()
                        ? extractRelationships(entities)
                        : Mono.just(List.of());

                Mono<Map<String, List<MedicalCode>>> codes =
                    inferCodesForSystemsrequested(request.text(), request.requestedCodeSystems());

                return Mono.zip(relationships, codes)
                        .map(tuple -> new ClassificationResult(
                                entities,
                                tuple.getT1(),
                                tuple.getT2(),
                                calculateOverallConfidence(entities),
                                buildMetadata(entitiesResponse)
                        ));
            });
}
```

#### BedrockAdapter.java

**Responsibilities:**
1. Generate clinical insights using AWS Bedrock (Claude models)
2. Build structured prompts from business data
3. Parse AI responses into domain objects
4. Handle streaming responses (if needed)

**Key Methods:**

```java
@Override
public Mono<ClinicalInsightResult> generateClinicalInsights(InsightRequest request) {
    String prompt = buildInsightPrompt(request);

    return invokeClaudeModel(prompt, SYSTEM_PROMPT)
            .map(response -> parseClinicalInsightResponse(response, request.reportId()));
}

private Mono<String> invokeClaudeModel(String userPrompt, String systemPrompt) {
    // Build Claude request payload
    Map<String, Object> requestBody = Map.of(
            "anthropic_version", "bedrock-2023-05-31",
            "max_tokens", maxTokens,
            "temperature", temperature,
            "system", systemPrompt,
            "messages", List.of(Map.of("role", "user", "content", userPrompt))
    );

    String jsonPayload = objectMapper.writeValueAsString(requestBody);

    InvokeModelRequest invokeRequest = InvokeModelRequest.builder()
            .modelId(modelId)
            .body(SdkBytes.fromString(jsonPayload, StandardCharsets.UTF_8))
            .build();

    return Mono.fromFuture(bedrockClient.invokeModel(invokeRequest))
            .map(this::extractResponseContent);
}
```

### 3. AWS Configuration

#### AwsConfig.java

**Responsibilities:**
1. Create AWS SDK client beans
2. Configure credentials (static or IAM roles)
3. Set AWS region
4. Provide shared dependencies (ObjectMapper)

```java
@Configuration
public class AwsConfig {

    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        // Use static credentials if provided, otherwise IAM roles
        if (accessKeyId != null && !accessKeyId.isBlank()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)
            );
        }
        return DefaultCredentialsProvider.create();
    }

    @Bean
    public TextractAsyncClient textractAsyncClient(AwsCredentialsProvider credentialsProvider) {
        return TextractAsyncClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(credentialsProvider)
                .build();
    }

    // Similar beans for ComprehendMedicalAsyncClient, BedrockRuntimeAsyncClient, S3AsyncClient
}
```

---

## Configuration

### Application Properties

#### application-aws.properties

```properties
# AWS Region
aws.region=us-east-1

# AWS Credentials (optional - uses IAM roles if not specified)
aws.access-key-id=
aws.secret-access-key=

# Bedrock Configuration
aws.bedrock.model-id=anthropic.claude-3-5-sonnet-20241022-v2:0
aws.bedrock.max-tokens=4096
aws.bedrock.temperature=0.7

# S3 Configuration
aws.s3.bucket-name=medscribe-ai-documents
aws.s3.document-prefix=medical-reports/
```

### Environment-Specific Configuration

**Local Development:**
```properties
spring.profiles.active=local,aws
```

**Production (ECS/EC2 with IAM roles):**
```properties
spring.profiles.active=prod,aws
# No credentials needed - uses IAM role
```

**Testing:**
```properties
spring.profiles.active=test
# Uses mock adapters, no real AWS calls
```

---

## Usage Examples

### Service Layer Usage

```java
@Service
@RequiredArgsConstructor
public class MedicalReportService {

    // Depend on interface, not AWS adapter
    private final OcrPort ocrPort;
    private final MedicalClassificationPort classificationPort;
    private final ClinicalInsightPort insightPort;

    public Mono<OcrResponse> processOcr(OcrRequest request) {
        return ocrPort.extractMedicalData(
                        request.getImageBase64(),
                        request.getReportType(),
                        request.getProcessingOptions()
                )
                .collectList()
                .map(testResults -> OcrResponse.builder()
                        .reportId(generateReportId())
                        .extractedData(testResults)
                        .confidence(calculateAverageConfidence(testResults))
                        .build());
    }

    public Mono<ClassificationResponse> classifyReport(ClassificationRequest request) {
        String combinedText = buildTextFromTestResults(request.getExtractedData());

        MedicalClassificationPort.ClassificationRequest classificationRequest =
                new MedicalClassificationPort.ClassificationRequest(
                        combinedText,
                        "en",
                        List.of("ICD10", "LOINC", "SNOMED"),
                        0.70,
                        true,
                        Map.of()
                );

        return classificationPort.classifyMedicalEntities(classificationRequest)
                .map(result -> buildClassificationResponse(request.getReportId(), result));
    }
}
```

---

## Testing Strategy

### 1. Unit Tests with Mock Adapters

```java
@Test
void testOcrProcessing() {
    // Arrange
    OcrPort mockOcr = mock(OcrPort.class);
    MedicalReportService service = new MedicalReportService(mockOcr, null, null);

    TestResult mockResult = TestResult.builder()
            .testName("Glucose")
            .testValue("120")
            .status(TestStatus.NORMAL)
            .build();

    when(mockOcr.extractMedicalData(any(), any(), any()))
            .thenReturn(Flux.just(mockResult));

    // Act
    OcrResponse response = service.processOcr(request).block();

    // Assert
    assertThat(response.getExtractedData()).hasSize(1);
    assertThat(response.getExtractedData().get(0).getTestName()).isEqualTo("Glucose");
}
```

### 2. Integration Tests with Testcontainers (LocalStack)

```java
@SpringBootTest
@Testcontainers
class AwsIntegrationTest {

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:latest"))
            .withServices(LocalStackContainer.Service.TEXTRACT);

    @Test
    void testRealAwsIntegration() {
        // Test with LocalStack or real AWS (if configured)
    }
}
```

### 3. Contract Tests

```java
@Test
void ocrPortContract() {
    // Verify all implementations of OcrPort follow the contract
    assertThat(textractAdapter).isInstanceOf(OcrPort.class);
    assertThat(azureVisionAdapter).isInstanceOf(OcrPort.class);
}
```

---

## Deployment Guide

### 1. Local Development

```bash
# Use AWS CLI configuration
aws configure

# Run application
./gradlew bootRun --args='--spring.profiles.active=local,aws'
```

### 2. Docker Container (EC2/ECS)

```dockerfile
FROM openjdk:21-jdk-slim

# Application will use IAM role attached to EC2/ECS task
ENV SPRING_PROFILES_ACTIVE=prod,aws

COPY build/libs/medscribe-ai-0.0.1-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]
```

**ECS Task IAM Role Policy:**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "textract:DetectDocumentText",
        "textract:AnalyzeDocument"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "comprehendmedical:DetectEntitiesV2",
        "comprehendmedical:InferICD10CM",
        "comprehendmedical:InferRxNorm",
        "comprehendmedical:InferSNOMEDCT"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "bedrock:InvokeModel"
      ],
      "Resource": "arn:aws:bedrock:*::foundation-model/anthropic.claude-3-5-sonnet-*"
    }
  ]
}
```

### 3. Kubernetes

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: medscribe-config
data:
  application.properties: |
    spring.profiles.active=prod,aws
    aws.region=us-east-1
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: medscribe-ai
spec:
  template:
    spec:
      serviceAccountName: medscribe-sa  # Service account with IAM role
      containers:
      - name: medscribe
        image: medscribe-ai:latest
        envFrom:
        - configMapRef:
            name: medscribe-config
```

---

## Benefits of This Architecture

### ✅ 1. **Provider Independence**

Switch from AWS to Azure or Google Cloud by implementing new adapters:

```java
@Component
@Primary // Override AWS
public class AzureHealthTextAnalyticsAdapter implements MedicalClassificationPort {
    // Same interface, different implementation
}
```

### ✅ 2. **Testability**

Mock adapters for fast unit tests, no AWS calls needed:

```java
@TestConfiguration
class TestConfig {
    @Bean
    @Primary
    public OcrPort mockOcrPort() {
        return mock(OcrPort.class);
    }
}
```

### ✅ 3. **Cost Optimization**

Easily switch between providers based on cost:

```java
@Configuration
public class CostOptimizedConfig {

    @Bean
    public OcrPort ocrPort() {
        // Use cheaper provider for simple documents
        if (documentType.isSimple()) {
            return new TesseractAdapter(); // Free, local
        }
        return new TextractAdapter(); // AWS, paid
    }
}
```

### ✅ 4. **Compliance & Data Residency**

Route based on data location requirements:

```java
if (patient.isEuResident()) {
    return euRegionAwsAdapter; // EU region
} else {
    return usRegionAwsAdapter; // US region
}
```

### ✅ 5. **Business Logic Protection**

AWS SDK changes don't affect business logic:

```java
// AWS SDK v2 → v3 migration only affects adapters
// Business logic unchanged
```

---

## Summary

**AWS services are infrastructure adapters, NOT business domains.**

This architecture:
- ✅ Follows Clean Architecture dependency rules
- ✅ Enables provider independence
- ✅ Improves testability
- ✅ Protects business logic from technical changes
- ✅ Implements Anti-Corruption Layer pattern
- ✅ Supports Hexagonal Architecture (Ports & Adapters)

**Key Takeaway:** The business defines WHAT it needs (ports), AWS provides HOW to do it (adapters).
