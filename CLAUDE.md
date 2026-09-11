# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is **MedScribe AI**, a Spring Boot WebFlux reactive application built following **Hexagonal Architecture** (Ports & Adapters) principles for intelligent medical document processing. The application automates medical report analysis using AWS AI services including Textract for OCR, Comprehend Medical for entity classification, and Bedrock (Claude 3) for AI-powered insights and recommendations.

**Technology Stack:**
- Java 21
- Spring Boot 3.4.2 with WebFlux (reactive)
- Spring Data R2DBC with PostgreSQL
- **AWS Services:** Textract (OCR), Comprehend Medical (medical NLP), Bedrock (Claude 3 for AI insights), S3 (document storage)
- **AWS SDK v2:** Async clients for non-blocking integration
- Gradle 8.12.1 (Build Tool)
- Lombok for boilerplate reduction
- ModelMapper for object mapping
- Firebase Admin SDK for push notifications
- Distributed tracing with Zipkin/Brave
- Spring Boot Admin Client for monitoring
- Testcontainers for integration testing

## Build and Run Commands

### Local Development

```bash
# Build the project
./gradlew build

# Build without tests
./gradlew build -x test

# Run tests
./gradlew test

# Run the application locally
./gradlew bootRun

# Clean build artifacts
./gradlew clean
```

### Docker

```bash
# Build Docker image
docker build -t medscribe-ai:v1 .

# Run Docker container
docker run -dit --rm -p 8086:8086 medscribe-ai:v1
```

### Setup Requirements

**Database Setup:**
```bash
# PostgreSQL database required
# Default local connection: localhost:5432/pfh
# Username: pfh
# Password: pfh
# Schema: pfh
```

**Application Profiles:**
Configure in `src/main/resources/application.properties`:
```properties
spring.profiles.active=local2
```

Available profiles:
- `local` / `local2`: Local development
- `dev`: Development environment
- `staging`: Staging environment
- `prod`: Production environment

### Health Check

```bash
curl --location 'localhost:8086/actuator/health'
```

Or via the configured actuator base path:
```bash
curl --location 'localhost:8086/api/v1/notification/pfh/actuator/health'
```

## Architecture

### Hexagonal Architecture (Ports & Adapters)

This codebase follows **Hexagonal Architecture** principles with clear separation of concerns:

**Core Layers:**
- **Domain**: Business entities and value objects (pure business logic, framework-independent)
- **Application**: Use cases, business logic services, and ports (interfaces)
  - **In Ports** (driving): Use case interfaces in `application/port/in`
  - **Out Ports** (driven): Gateway/persistence interfaces in `application/port/out`
- **Adapters**: Implementation of ports
  - **In Adapters** (driving): Web handlers and routers in `adapter/in/web`
  - **Out Adapters** (driven): Persistence and external service implementations in `adapter/out`

### Module Structure

The application is organized into feature modules under `com.elioo.healthcare`:

#### 1. **core** - Shared Infrastructure

**base**: Generic CRUD functionality following DRY principles
- [BaseService.java](src/main/java/com/elioo/healthcare/core/base/application/service/BaseService.java): Abstract service with CRUD operations
- [BaseHandler.java](src/main/java/com/elioo/healthcare/core/base/adapter/in/web/handler/BaseHandler.java): Generic HTTP handler
- [BaseRouter.java](src/main/java/com/elioo/healthcare/core/base/adapter/in/web/router/BaseRouter.java): Router configuration helper
- [BaseRepository.java](src/main/java/com/elioo/healthcare/core/base/adapter/out/persistence/repository/BaseRepository.java): R2DBC repository extensions
- [BasePersistenceAdapter.java](src/main/java/com/elioo/healthcare/core/base/adapter/out/persistence/BasePersistenceAdapter.java): Persistence layer abstraction
- [BaseDomain.java](src/main/java/com/elioo/healthcare/core/base/domain/BaseDomain.java): Base domain object
- [BaseEntity.java](src/main/java/com/elioo/healthcare/core/base/adapter/out/persistence/entity/BaseEntity.java): Base persistence entity

**config**: Configuration classes
- [AwsConfig.java](src/main/java/com/elioo/healthcare/core/config/AwsConfig.java): AWS SDK clients configuration (Textract, Comprehend Medical, Bedrock, S3)
- [FirebaseConfig.java](src/main/java/com/elioo/healthcare/core/config/FirebaseConfig.java): Firebase Admin SDK configuration
- [ModelMapperConfig.java](src/main/java/com/elioo/healthcare/core/config/ModelMapperConfig.java): ModelMapper bean configuration

**filters**: Web filters for request/response processing
- [IWebFilter.java](src/main/java/com/elioo/healthcare/core/filters/IWebFilter.java): Custom web filter interface
- [MDCKeys.java](src/main/java/com/elioo/healthcare/core/filters/MDCKeys.java): MDC (Mapped Diagnostic Context) keys for logging
- [HeaderNames.java](src/main/java/com/elioo/healthcare/core/filters/HeaderNames.java): HTTP header name constants

**util**: Common utilities and exception handling
- [GlobalWebExceptionHandler.java](src/main/java/com/elioo/healthcare/core/util/exception/GlobalWebExceptionHandler.java): Centralized exception handler
- [AppException.java](src/main/java/com/elioo/healthcare/core/util/exception/AppException.java): Custom application exception
- [ErrorCode.java](src/main/java/com/elioo/healthcare/core/util/exception/ErrorCode.java): Error code enumeration
- [ErrorBody.java](src/main/java/com/elioo/healthcare/core/util/exception/ErrorBody.java): Error response structure
- [CommonFunctions.java](src/main/java/com/elioo/healthcare/core/util/CommonFunctions.java): Utility functions
- [TracerUtil.java](src/main/java/com/elioo/healthcare/core/util/TracerUtil.java): Distributed tracing utilities
- [Status.java](src/main/java/com/elioo/healthcare/core/util/Status.java): Status enumeration

**routes**: Route name constants
- [RouteNames.java](src/main/java/com/elioo/healthcare/core/routes/RouteNames.java): Centralized route path definitions

#### 2. **medicalreport** - Medical Report Processing Feature (3-Step Workflow)

**Domain Layer:**
- Enums: [TestStatus.java](src/main/java/com/elioo/healthcare/medicalreport/domain/TestStatus.java), [ReportType.java](src/main/java/com/elioo/healthcare/medicalreport/domain/ReportType.java), [Severity.java](src/main/java/com/elioo/healthcare/medicalreport/domain/Severity.java), [Gender.java](src/main/java/com/elioo/healthcare/medicalreport/domain/Gender.java), [SuggestionCategory.java](src/main/java/com/elioo/healthcare/medicalreport/domain/SuggestionCategory.java), [Priority.java](src/main/java/com/elioo/healthcare/medicalreport/domain/Priority.java)
- Value Objects: [TestResult.java](src/main/java/com/elioo/healthcare/medicalreport/domain/TestResult.java)

**Application Layer (Ports):**
- Outbound Ports (Interfaces for external services):
  - [OcrPort.java](src/main/java/com/elioo/healthcare/medicalreport/application/port/out/OcrPort.java): OCR operations contract
  - [MedicalClassificationPort.java](src/main/java/com/elioo/healthcare/medicalreport/application/port/out/MedicalClassificationPort.java): Classification operations contract
  - [ClinicalInsightPort.java](src/main/java/com/elioo/healthcare/medicalreport/application/port/out/ClinicalInsightPort.java): AI insights operations contract

**DTOs:**
- OCR: [OcrRequest.java](src/main/java/com/elioo/healthcare/medicalreport/dto/OcrRequest.java) / [OcrResponse.java](src/main/java/com/elioo/healthcare/medicalreport/dto/OcrResponse.java)
- Classification: [ClassificationRequest.java](src/main/java/com/elioo/healthcare/medicalreport/dto/ClassificationRequest.java) / [ClassificationResponse.java](src/main/java/com/elioo/healthcare/medicalreport/dto/ClassificationResponse.java)
- Suggestions: [SuggestionsRequest.java](src/main/java/com/elioo/healthcare/medicalreport/dto/SuggestionsRequest.java) / [SuggestionsResponse.java](src/main/java/com/elioo/healthcare/medicalreport/dto/SuggestionsResponse.java)

**Adapter In (Web Layer):**
- Handler: [MedicalReportHandler.java](src/main/java/com/elioo/healthcare/medicalreport/adapter/in/handler/MedicalReportHandler.java)
- Router: [MedicalReportRouter.java](src/main/java/com/elioo/healthcare/medicalreport/adapter/in/router/MedicalReportRouter.java)

**Adapter Out (AWS Integration Layer):**
- [TextractAdapter.java](src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/TextractAdapter.java): Implements `OcrPort` using AWS Textract
- [ComprehendMedicalAdapter.java](src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/ComprehendMedicalAdapter.java): Implements `MedicalClassificationPort` using AWS Comprehend Medical
- [BedrockAdapter.java](src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/BedrockAdapter.java): Implements `ClinicalInsightPort` using AWS Bedrock (Claude 3)

**Endpoints:**
- `POST /api/v1/medical-report/ocr` - Extract structured data from medical test images
- `POST /api/v1/medical-report/classify` - Classify medical entities with AWS Comprehend Medical
- `POST /api/v1/medical-report/suggestions` - Generate AI-powered insights and recommendations

#### 3. **hello** - Simple Hello World API for Testing

- Handler: [HelloWorldHandler.java](src/main/java/com/elioo/healthcare/hello/adapter/in/handler/HelloWorldHandler.java)
- Router: [HelloWorldRouter.java](src/main/java/com/elioo/healthcare/hello/adapter/in/router/HelloWorldRouter.java)
- Endpoint: `GET /api/hello`

### Base Classes and Inheritance Pattern

The codebase uses a sophisticated inheritance pattern to promote code reuse and consistency:

#### BaseService
Generic CRUD operations with validation, pagination, filtering, and transaction support.

**Provides:**
- `getById(String id)`: Retrieve single entity
- `create(T domain)`: Create single entity
- `createList(List<T> domains)`: Batch create
- `update(String id, T domain)`: Update entity
- `getAllDataAsList()`: Fetch all as list
- `getAllDataWithPaginationAndSorting(...)`: Paginated fetch

**Hook Methods** (can be overridden):
- `businessValidation(T domain)`: Custom validation logic
- `updatedDomainObject(T existing, T updated)`: Merge logic
- `afterCreate(T domain)`: Post-create actions
- `afterCreateList(List<T> domains)`: Post-batch-create actions

#### BasePersistenceAdapter
Generic persistence layer implementation that:
- Handles domain-entity mapping with ModelMapper
- Provides dynamic query building via [DynamicCriteriaBuilder.java](src/main/java/com/elioo/healthcare/core/base/adapter/out/persistence/criteria/DynamicCriteriaBuilder.java)
- Implements common persistence port interfaces

#### BaseRepository / GenericRepository
R2DBC repository extensions that provide:
- Custom query building for complex filters
- Dynamic criteria and pagination support
- Reactive database operations

#### BaseHandler
Generic HTTP handler with standard CRUD endpoints:
- `GET /{id}`: Get by ID
- `GET /list`: Get all as list
- `GET /page`: Get paginated results
- `POST /create`: Create single entity
- `POST /create-list`: Batch create
- `PATCH /{id}`: Update entity

Can be extended with custom routes (see [MedicalReportRouter](src/main/java/com/elioo/healthcare/medicalreport/adapter/in/router/MedicalReportRouter.java))

### Reactive Programming Patterns

All operations return `Mono<T>` or `Flux<T>` from Project Reactor:
- **Database Operations**: Fully non-blocking via Spring Data R2DBC
- **Web Layer**: Uses Spring WebFlux with functional routing (`RouterFunction`)
- **Service Layer**: Reactive streams throughout
- **Error Handling**: Uses reactive operators:
  - `onErrorResume()`: Fallback handling
  - `doOnError()`: Side-effect on error
  - `switchIfEmpty()`: Default values

### Key Design Patterns

1. **Hexagonal Architecture**: Clear separation between domain, application, and infrastructure
2. **Repository Pattern**: Separation of persistence logic
3. **Use Case Pattern**: Each business operation is a use case interface (inbound port)
4. **Template Method**: BaseService provides template with customizable hooks
5. **Dependency Injection**: Constructor-based DI throughout (using Lombok's `@RequiredArgsConstructor`)
6. **DTO Pattern**: Separate domain objects from API requests/responses
7. **Strategy Pattern**: (Planned) Feature flag strategies for conditional execution

## Configuration

### Application Profiles

Configure active profile in [application.properties:3](src/main/resources/application.properties#L3):
```properties
spring.profiles.active=local2
```

**Available Profiles:**
- `local` / `local2`: Local development (port 8086)
- `dev`: Development environment
- `staging`: Staging environment
- `prod`: Production environment

### Key Configuration Properties

**Server:**
- Port: `8086` (configured in profile-specific properties)

**Database (R2DBC + PostgreSQL):**
```properties
spring.r2dbc.url=r2dbc:postgresql://localhost:5432/pfh?schema=pfh
spring.r2dbc.username=pfh
spring.r2dbc.password=<YOUR_DB_PASSWORD>
```

**Firebase:**
```properties
firebase.config.path=classpath:firebase/firebase-adminsdk.json
```
*Note: Firebase credentials file not in repo for security*

**Actuator:**
```properties
management.endpoints.web.base-path=/api/v1/notification/pfh/actuator
management.endpoint.health.show-details=always
management.endpoints.web.exposure.include=*
```

**Distributed Tracing (Zipkin):**
```properties
management.tracing.enabled=true
management.tracing.sampling.probability=1.0
management.zipkin.tracing.endpoint=http://localhost:9411/api/v2/spans
```

**Logging:**
```properties
LOG_DIR=/tmp/logs
MAX_FILE_SIZE=15MB
MAX_HISTORY=20
LOG_LEVEL_ROOT=INFO
```
Uses Logback with Logstash encoder and Slack appender for structured logging.

**Feature Flagging:**
```properties
feature-flagging.base-url=http://localhost:8087
```

## Medical Report Processing Workflow

The application implements a **3-step workflow** for medical report processing:

### Step 1: OCR Processing
**Endpoint:** `POST /api/v1/medical-report/ocr`

**Purpose:**
- Accepts base64-encoded medical report images
- Extracts structured test data (test name, value, unit, reference range, status)
- Returns extracted data with confidence scores

**Implementation:**
- Handler: [MedicalReportHandler.processOcr()](src/main/java/com/elioo/healthcare/medicalreport/adapter/in/handler/MedicalReportHandler.java#L30)
- Currently returns mock data (TODO: AWS Textract integration)
- Supports Bangla + English text (planned)

**Request Example:**
```json
{
  "imageBase64": "base64_encoded_image...",
  "patientId": "P12345",
  "reportType": "BLOOD_TEST"
}
```

**Response Example:**
```json
{
  "reportId": "RPT-ABC123",
  "patientId": "P12345",
  "extractedData": [
    {
      "testName": "Serum Creatinine",
      "testValue": "135.0",
      "unit": "µmol/L",
      "referenceRange": "Male: 59-104, Female: 45-84",
      "status": "ABNORMAL"
    }
  ],
  "confidence": 0.96,
  "processedAt": "2024-01-15T10:30:00Z"
}
```

### Step 2: Medical Entity Classification
**Endpoint:** `POST /api/v1/medical-report/classify`

**Purpose:**
- Takes extracted test data from Step 1
- Classifies medical entities using AWS Comprehend Medical format
- Identifies test names, values, units with relationship mapping
- Maps to standard medical codes (ICD-10, LOINC, SNOMED CT)

**Implementation:**
- Handler: [MedicalReportHandler.classifyMedicalData()](src/main/java/com/elioo/healthcare/medicalreport/adapter/in/handler/MedicalReportHandler.java#L67)
- Currently returns mock classification data (TODO: AWS Comprehend Medical integration)

**Request Example:**
```json
{
  "reportId": "RPT-ABC123",
  "extractedData": [...]
}
```

**Response Example:**
```json
{
  "reportId": "RPT-ABC123",
  "classificationResult": {
    "Entities": [
      {
        "Id": 1,
        "Text": "Serum Creatinine",
        "Category": "TEST_TREATMENT_PROCEDURE",
        "Type": "TEST_NAME",
        "Score": 0.98,
        "Attributes": [...]
      }
    ]
  },
  "medicalCodes": {
    "ICD10": ["R79.89", "N17.9"],
    "LOINC": ["2160-0", "16362-6"],
    "SNOMED": ["313822004", "43904001"]
  }
}
```

### Step 3: AI-Powered Suggestions
**Endpoint:** `POST /api/v1/medical-report/suggestions`

**Purpose:**
- Analyzes classified test results
- Generates patient-friendly summaries and interpretations
- Identifies key findings with severity levels (LOW, MODERATE, HIGH, CRITICAL)
- Provides AI-generated suggestions categorized by type
- Includes risk assessment and immediate attention flags

**Implementation:**
- Handler: [MedicalReportHandler.generateSuggestions()](src/main/java/com/elioo/healthcare/medicalreport/adapter/in/handler/MedicalReportHandler.java#L94)
- Currently returns mock suggestions (TODO: AWS Bedrock Claude 3 integration)

**Suggestion Categories:**
- `IMMEDIATE_ACTION`: Urgent medical steps
- `DIAGNOSTIC_TESTS`: Additional tests needed
- `MEDICATION`: Treatment recommendations
- `MONITORING`: Follow-up requirements
- `LIFESTYLE`: Lifestyle modifications

**Request Example:**
```json
{
  "reportId": "RPT-ABC123",
  "patientContext": {
    "age": 45,
    "gender": "MALE",
    "medicalHistory": ["Diabetes Type 2"]
  },
  "includeActionPlan": true
}
```

For complete API documentation with detailed examples, see [API_DOCUMENTATION.md](API_DOCUMENTATION.md)

## AWS Service Integration Architecture

### Design Philosophy: Adapters, Not Domains

**Key Architectural Decision:** AWS services are implemented as **infrastructure adapters**, NOT as separate bounded contexts.

```
Business Layer (Domain + Application)
         │ defines interface (Port)
         ▼
┌─────────────────────────────────┐
│   OcrPort (Interface)           │  ← Business defines WHAT it needs
│   MedicalClassificationPort      │
│   ClinicalInsightPort            │
└──────────────┬──────────────────┘
               │ implemented by
         ┌─────┴─────┬─────────────┬──────────
         ▼           ▼             ▼
    AWS Textract  AWS Comprehend  AWS Bedrock  ← Infrastructure provides HOW
    (Adapter)     Medical (Adapter) (Adapter)
```

**Why this approach?**
1. **Provider Independence**: Business logic never depends on AWS
2. **Testability**: Easy to mock adapters for unit tests
3. **Flexibility**: Can switch providers (Azure, Google Cloud) by implementing same ports
4. **Anti-Corruption Layer**: Shields domain from AWS-specific data structures

For detailed architectural reasoning and examples, see [AWS_INTEGRATION_ARCHITECTURE.md](docs/AWS_INTEGRATION_ARCHITECTURE.md)

### Outbound Ports (Business-Defined Interfaces)

#### 1. OcrPort - Optical Character Recognition
**File:** [OcrPort.java](src/main/java/com/elioo/healthcare/medicalreport/application/port/out/OcrPort.java)

**Purpose:** Define what OCR capabilities the business needs, independent of provider.

**Key Methods:**
- `extractMedicalData()`: Extract structured test results from medical document images
- `extractRawText()`: Get raw text without structure (for classification)
- `validateImageQuality()`: Check if image meets quality requirements
- `getProcessingConfidence()`: Get confidence score for a specific report type

**Implementation:** [TextractAdapter.java](src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/TextractAdapter.java)

#### 2. MedicalClassificationPort - Medical NLP
**File:** [MedicalClassificationPort.java](src/main/java/com/elioo/healthcare/medicalreport/application/port/out/MedicalClassificationPort.java)

**Purpose:** Define medical entity classification and coding capabilities.

**Key Methods:**
- `classifyMedicalEntities()`: Extract entities (tests, medications, conditions, anatomy)
- `mapToMedicalCodes()`: Map terms to ICD-10, LOINC, SNOMED CT, RxNorm
- `extractRelationships()`: Find relationships between entities
- `validateClassification()`: Quality check for classification results

**Returns Domain Objects:**
```java
record ClassificationResult(
    List<MedicalEntity> entities,          // Domain objects, not AWS types
    List<EntityRelationship> relationships,
    Map<String, List<MedicalCode>> medicalCodes,
    double overallConfidence,
    Map<String, Object> metadata
)
```

**Implementation:** [ComprehendMedicalAdapter.java](src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/ComprehendMedicalAdapter.java)

#### 3. ClinicalInsightPort - AI-Powered Insights
**File:** [ClinicalInsightPort.java](src/main/java/com/elioo/healthcare/medicalreport/application/port/out/ClinicalInsightPort.java)

**Purpose:** Define AI-powered clinical insight generation capabilities.

**Key Methods:**
- `generateClinicalInsights()`: Comprehensive analysis with findings, recommendations, risk assessment
- `generateSummary()`: Patient-friendly or provider-friendly summaries
- `assessRisk()`: Cardiovascular, metabolic, renal, hepatic risk assessment
- `generateRecommendations()`: Evidence-based clinical recommendations
- `analyzeTrends()`: Temporal trend analysis for test results
- `generateEducationalContent()`: Patient education materials

**Implementation:** [BedrockAdapter.java](src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/BedrockAdapter.java)

### AWS Adapters (Infrastructure Implementations)

#### 1. TextractAdapter
**Responsibilities:**
- Transform base64 images to AWS Textract format
- Call AWS Textract Async API (non-blocking)
- Parse Textract `Block` structures (tables, forms, layout)
- Extract medical test data (test name, value, unit, reference range)
- Determine test status (NORMAL/ABNORMAL/CRITICAL) based on reference ranges
- Map AWS response to domain `TestResult` objects

**Key Features:**
- Supports both `DetectDocumentText` (simple) and `AnalyzeDocument` (structured)
- Parses tabular medical reports (most common format)
- Falls back to form-based extraction if no tables found
- Confidence scoring and quality validation

**Example Usage in Service:**
```java
@Service
@RequiredArgsConstructor
public class MedicalReportService {
    private final OcrPort ocrPort;  // Interface, not AWS!

    public Mono<OcrResponse> processOcr(OcrRequest request) {
        return ocrPort.extractMedicalData(
                request.getImageBase64(),
                request.getReportType(),
                request.getProcessingOptions()
            )
            .collectList()
            .map(this::buildResponse);
    }
}
```

#### 2. ComprehendMedicalAdapter
**Responsibilities:**
- Call AWS Comprehend Medical DetectEntitiesV2 API
- Extract medical codes using InferICD10CM and InferRxNorm APIs
- Map AWS entity categories to domain entities
- Extract entity attributes (dosage, frequency, direction)
- Identify entity traits (NEGATION, DIAGNOSIS, SIGN, SYMPTOM)
- Build entity relationships

**Supported Medical Code Systems (via AWS Comprehend Medical):**
- **ICD-10-CM**: Diagnosis codes (via `InferICD10CM` API) ✅
- **RxNorm**: Medication codes (via `InferRxNorm` API) ✅

**Note**: SNOMED-CT, LOINC, and CPT codes are not directly available through AWS Comprehend Medical APIs. For these code systems, you would need:
- Additional mapping services (e.g., UMLS API, NLM Value Set Authority Center)
- Local terminology databases (SNOMED CT, LOINC)
- Third-party medical coding services

**Anti-Corruption Layer Example:**
```java
// AWS SDK Response (external format)
DetectEntitiesV2Response awsResponse = comprehendMedicalClient.detectEntitiesV2(request);

// Adapter transforms to domain objects (internal format)
List<MedicalEntity> domainEntities = awsResponse.entities().stream()
    .map(this::mapToDomainEntity)  // Shield domain from AWS types
    .toList();

// Business logic works with clean domain objects
return new ClassificationResult(domainEntities, ...);
```

#### 3. BedrockAdapter
**Responsibilities:**
- Build structured prompts from medical data
- Invoke AWS Bedrock with Claude 3 Sonnet model
- Parse AI responses (JSON format) into domain objects
- Generate patient-friendly vs provider-friendly language
- Create evidence-based recommendations with citation levels
- Assess clinical risk across multiple organ systems

**Model Configuration:**
- **Default Model**: `anthropic.claude-3-5-sonnet-20241022-v2:0`
- **Max Tokens**: 4096 (configurable)
- **Temperature**: 0.7 (balanced between creativity and consistency)

**Prompt Engineering:**
- System prompt defines medical AI assistant role
- Structured JSON output for consistent parsing
- Context includes: extracted data, classification results, patient history
- Target audience adaptation (PATIENT vs PROVIDER)

**Example Clinical Insight Generation:**
```java
InsightRequest request = new InsightRequest(
    reportId,
    extractedData,           // From OCR step
    classificationResult,    // From classification step
    patientContext,          // Age, gender, history, medications
    summaryOptions,
    riskOptions
);

ClinicalInsightResult insights = insightPort.generateClinicalInsights(request).block();
// Contains: summary, key findings, recommendations, risk assessment, action plan
```

### AWS Configuration

#### AwsConfig.java
**File:** [AwsConfig.java](src/main/java/com/elioo/healthcare/core/config/AwsConfig.java)

**Purpose:** Configure AWS SDK clients as Spring beans.

**Beans Provided:**
1. `AwsCredentialsProvider`: Handles authentication (static keys or IAM roles)
2. `TextractAsyncClient`: For OCR operations
3. `ComprehendMedicalAsyncClient`: For medical NLP
4. `BedrockRuntimeAsyncClient`: For AI model invocation
5. `S3AsyncClient`: For document storage (future use)

**Credentials Strategy:**
```java
// 1. If keys configured → Static credentials
if (accessKeyId != null && !accessKeyId.isBlank()) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKeyId, secretAccessKey)
    );
}

// 2. Otherwise → IAM roles (EC2, ECS, Lambda)
return DefaultCredentialsProvider.create();
```

#### application-aws.properties
**File:** [application-aws.properties](src/main/resources/application-aws.properties)

```properties
# AWS Region
aws.region=us-east-1

# Credentials (optional - uses IAM roles if empty)
aws.access-key-id=
aws.secret-access-key=

# Bedrock Model Configuration
aws.bedrock.model-id=anthropic.claude-3-5-sonnet-20241022-v2:0
aws.bedrock.max-tokens=4096
aws.bedrock.temperature=0.7

# S3 Document Storage
aws.s3.bucket-name=medscribe-ai-documents
aws.s3.document-prefix=medical-reports/
```

### Testing Strategy

#### 1. Unit Tests with Mock Adapters
```java
@Test
void testOcrProcessing() {
    // Mock the port, not AWS SDK
    OcrPort mockOcr = mock(OcrPort.class);
    MedicalReportService service = new MedicalReportService(mockOcr, null, null);

    when(mockOcr.extractMedicalData(any(), any(), any()))
        .thenReturn(Flux.just(testResult));

    OcrResponse response = service.processOcr(request).block();

    assertThat(response.getExtractedData()).hasSize(1);
    // Test business logic without AWS calls
}
```

#### 2. Integration Tests with LocalStack
```java
@SpringBootTest
@Testcontainers
class AwsIntegrationTest {
    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:latest")
    ).withServices(LocalStackContainer.Service.TEXTRACT);

    // Test with LocalStack mock AWS services
}
```

#### 3. Provider Switching Test
```java
// Test that business logic works regardless of OCR provider
@ParameterizedTest
@ValueSource(classes = {TextractAdapter.class, AzureVisionAdapter.class, TesseractAdapter.class})
void testOcrProviderIndependence(Class<? extends OcrPort> adapterClass) {
    OcrPort adapter = createAdapter(adapterClass);
    // Business logic should work with any adapter
}
```

### Deployment

#### Local Development
```bash
# Use AWS CLI credentials
aws configure

# Run with AWS profile
./gradlew bootRun --args='--spring.profiles.active=local,aws'
```

#### Production (ECS/EC2 with IAM Roles)
```yaml
# ECS Task Definition
TaskRoleArn: arn:aws:iam::123456789012:role/MedScribeAiTaskRole

# IAM Policy
{
  "Effect": "Allow",
  "Action": [
    "textract:DetectDocumentText",
    "textract:AnalyzeDocument",
    "comprehendmedical:DetectEntitiesV2",
    "comprehendmedical:InferICD10CM",
    "comprehendmedical:InferRxNorm",
    "comprehendmedical:InferSNOMEDCT",
    "bedrock:InvokeModel"
  ],
  "Resource": "*"
}
```

### Gradle Dependencies

```gradle
// AWS SDK v2 - Async Clients
implementation platform('software.amazon.awssdk:bom:2.21.26')
implementation 'software.amazon.awssdk:textract'
implementation 'software.amazon.awssdk:comprehendmedical'
implementation 'software.amazon.awssdk:bedrockruntime'
implementation 'software.amazon.awssdk:s3'
```

### Benefits of This Architecture

✅ **Provider Independence**
```java
// Switch from AWS to Azure by changing one line
@Component
@Primary
public class AzureHealthTextAnalyticsAdapter implements MedicalClassificationPort {
    // Same interface, different implementation
}
```

✅ **Cost Optimization**
```java
// Route to cheaper provider based on document complexity
if (document.isSimple()) {
    return tesseractAdapter;  // Free, local OCR
} else {
    return textractAdapter;   // AWS, more accurate
}
```

✅ **Testability**
```java
// Mock adapters for fast unit tests
@TestConfiguration
class TestConfig {
    @Bean @Primary
    public OcrPort mockOcrPort() {
        return mock(OcrPort.class);
    }
}
```

For complete architectural details, examples, and deployment guides, see:
- **[AWS Integration Architecture](docs/AWS_INTEGRATION_ARCHITECTURE.md)** - Comprehensive guide
- **[DDD Domain Model](docs/DDD_DOMAIN_MODEL.md)** - Domain-driven design analysis
- **[API Documentation](docs/endpoints/)** - Detailed endpoint specs

## Testing

The project uses:
- **JUnit 5 (Jupiter)** for unit tests
- **Testcontainers** for integration tests with PostgreSQL
- **Reactor Test** (`reactor-test`) for reactive stream testing
- **WebTestClient** for integration testing web endpoints

**Test Location:** `src/test/java/com/elioo/healthcare/`

**Key Test Classes:**
- [MedscribeAiApplicationTests.java](src/test/java/com/elioo/healthcare/MedscribeAiApplicationTests.java)

**Running Tests:**
```bash
./gradlew test
```

## Adding New Features Following Hexagonal Architecture

To add a new feature module, follow this structure:

### 1. Create Package Structure
```
com.elioo.healthcare.{feature}/
├── adapter/
│   ├── in/
│   │   └── web/
│   │       ├── handler/
│   │       └── router/
│   └── out/
│       └── persistence/
│           ├── entity/
│           ├── repository/
│           └── {Feature}PersistenceAdapter.java
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   └── {Feature}UseCase.java
│   │   └── out/
│   │       └── persistence/
│   │           └── {Feature}PersistencePort.java
│   └── service/
│       └── {Feature}Service.java
└── domain/
    ├── {Feature}.java
    └── ...
```

### 2. Define Domain Entities
Create pure business objects independent of frameworks:
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyFeature {
    private String id;
    private String name;
    // business fields
}
```

### 3. Create Inbound Port (Use Case Interface)
```java
public interface MyFeatureUseCase {
    Mono<MyFeature> getById(String id);
    Mono<MyFeature> create(MyFeature feature);
}
```

### 4. Implement Service (Use Case Implementation)
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class MyFeatureService implements MyFeatureUseCase {

    private final MyFeaturePersistencePort persistencePort;

    @Override
    public Mono<MyFeature> getById(String id) {
        return persistencePort.findById(id)
            .switchIfEmpty(Mono.error(new AppException(ErrorCode.NOT_FOUND)))
            .doOnNext(feature -> log.info("Found feature: {}", feature))
            .onErrorResume(e -> handleError(e));
    }
}
```

### 5. Create Outbound Port (Persistence Interface)
```java
public interface MyFeaturePersistencePort {
    Mono<MyFeature> findById(String id);
    Mono<MyFeature> save(MyFeature feature);
}
```

### 6. Implement Persistence Adapter
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class MyFeaturePersistenceAdapter implements MyFeaturePersistencePort {

    private final MyFeatureRepository repository;
    private final ModelMapper modelMapper;

    @Override
    public Mono<MyFeature> findById(String id) {
        return repository.findById(id)
            .map(entity -> modelMapper.map(entity, MyFeature.class));
    }
}
```

### 7. Create Entity and Repository
```java
@Data
@Table("my_feature")
public class MyFeatureEntity implements Persistable<String> {
    @Id
    private String id;
    private String name;

    @Override
    public boolean isNew() {
        boolean isNull = Objects.isNull(this.id);
        this.id = isNull ? UUID.randomUUID().toString() : this.id;
        return isNull;
    }
}

public interface MyFeatureRepository extends R2dbcRepository<MyFeatureEntity, String> {
}
```

### 8. Create Web Layer (Handler and Router)
```java
@Component
@RequiredArgsConstructor
public class MyFeatureHandler {

    private final MyFeatureUseCase useCase;

    public Mono<ServerResponse> getById(ServerRequest request) {
        String id = request.pathVariable("id");
        return useCase.getById(id)
            .flatMap(feature -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(feature))
            .onErrorResume(this::handleError);
    }
}

@Configuration
public class MyFeatureRouter {

    @Bean
    public RouterFunction<ServerResponse> myFeatureRoutes(MyFeatureHandler handler) {
        return RouterFunctions.route()
            .GET("/api/v1/my-feature/{id}", handler::getById)
            .build();
    }
}
```

## Naming Conventions

- **Packages**: Feature-based modules under `com.elioo.healthcare.{feature}`
- **Classes**:
  - Domain objects: Plain nouns (e.g., `Person`, `TestResult`)
  - Enums: Plain nouns (e.g., `TestStatus`, `Severity`, `Gender`)
  - Services: `{Feature}Service` (e.g., `PersonService`)
  - Use Cases: `{Feature}UseCase` (e.g., `PersonUseCase`)
  - Handlers: `{Feature}Handler` (e.g., `PersonHandler`)
  - Routers: `{Feature}Router` (e.g., `PersonRouter`)
  - DTOs: `{Feature}Request`, `{Feature}Response` (e.g., `OcrRequest`, `OcrResponse`)
  - Entities: `{Feature}Entity` (e.g., `PersonEntity`)
  - Repositories: `{Feature}Repository` (e.g., `PersonRepository`)
  - Adapters: `{Feature}PersistenceAdapter`, `{Feature}Gateway` (e.g., `PersonPersistenceAdapter`)

## Important Notes

- **Database Conventions**: Database field names use `snake_case` (e.g., `created_at`), while Java uses `camelCase` (e.g., `createdAt`). Field conversion is handled by [CommonFunctions.java](src/main/java/com/elioo/healthcare/core/util/CommonFunctions.java)
- **Validation**: Uses Jakarta Bean Validation annotations (`@NotBlank`, `@NotNull`, etc.)
- **Exception Handling**: Centralized in [GlobalWebExceptionHandler.java](src/main/java/com/elioo/healthcare/core/util/exception/GlobalWebExceptionHandler.java)
- **Custom Exceptions**: Use [AppException.java](src/main/java/com/elioo/healthcare/core/util/exception/AppException.java) with [ErrorCode.java](src/main/java/com/elioo/healthcare/core/util/exception/ErrorCode.java) enum
- **Reactive Patterns**: Always return `Mono<T>` or `Flux<T>` in service and repository layers
- **Constructor Injection**: Prefer constructor-based DI with Lombok's `@RequiredArgsConstructor`
- **Logging**: Use SLF4J with `@Slf4j` annotation
- **Firebase**: Credentials required in `src/main/resources/firebase/firebase-adminsdk.json` (not in repo for security)

## Development Workflow

### 1. Clone Repository
```bash
git clone <repository-url>
cd medscribe-ai
```

### 2. Setup Local Environment
- Install Java 21
- Install PostgreSQL and create database `pfh` with schema `pfh`
- Configure `application-local.properties` with your database credentials

### 3. Run Application
```bash
./gradlew bootRun
```

### 4. Make Changes
- Follow hexagonal architecture principles
- Create feature packages with proper layering
- Write tests for new functionality

### 5. Test Changes
```bash
./gradlew test
```

### 6. Commit and Push
```bash
git add .
git commit -m "feat(feature): description of changes"
git push
```

## CI/CD Pipeline (Planned)

The project is designed to support CI/CD workflows:
1. **Build & Test**: Gradle builds and runs tests
2. **Docker Image**: Build and push to container registry
3. **Deploy**: Terraform/IaC for infrastructure updates

## Support and Documentation

- **Complete API Documentation**: [API_DOCUMENTATION.md](API_DOCUMENTATION.md)
- **README**: [README.md](README.md)
- **GitLab**: https://github.com/KhondokerTanvirHossain/elioo-health
- **Team**: Health Care Team
