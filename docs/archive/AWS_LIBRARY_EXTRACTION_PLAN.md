# AWS Service Library Extraction Plan - MedScribe AI

## Executive Summary

**Your Assessment**: ✅ **VALIDATED - Excellent architectural instincts!**

You correctly identified that:
1. AWS services need to be highly configurable libraries
2. Multiple bounded contexts will use the same AWS services
3. Copy-pasting adapter code across packages is an anti-pattern
4. Configuration needs to be centralized and reusable

## Finalized Design Decisions

### 1. Library Naming Convention ✅
**Decision:** Healthcare-focused naming (`com.elioo.healthcare.aws.*`)
- Aligns with existing codebase structure
- Clear healthcare domain focus
- Libraries will be healthcare-specific but with clean abstractions

### 2. Publishing Strategy ✅
**Decision:** JitPack with GitLab/GitHub support
- Primary: GitLab repository with JitPack integration
- Fallback: GitHub mirror for JitPack compatibility
- Zero infrastructure setup required
- Libraries accessible via JitPack dependency resolution

**Usage Example:**
```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    implementation 'com.elioo.healthcare:elioo-aws-textract:1.0.0'
}
```

### 3. Implementation Timeline ✅
**Decision:** Aggressive parallel extraction (2-3 weeks total)
- **Week 1:** Multi-module setup + AWS Common module
- **Week 2:** Extract all 3 services (Textract, Comprehend, Bedrock) in parallel
- **Week 3:** Integration testing, documentation, publishing to JitPack

### 4. Configuration Philosophy ✅
**Decision:** Properties-based configuration (externalized)
- Primary: Spring Boot `application-aws.properties`
- Type-safe binding via `@ConfigurationProperties` classes
- Override mechanism: Environment variables, profiles, command-line args

**Example:**
```properties
aws.textract.enabled=true
aws.textract.max-image-size-mb=10
aws.bedrock.model-id=us.anthropic.claude-3-5-sonnet-20241022-v2:0
aws.comprehend-medical.min-confidence=0.70
```

### 5. Migration Strategy ✅
**Decision:** Big Bang migration - all at once
- Extract all AWS adapters to libraries in one comprehensive PR
- Update application to use libraries immediately
- No dual maintenance of old and new implementations
- Comprehensive testing before merge to minimize risk

---

## Current State Analysis

### What We Have Now

**Single-Module Monolith Structure:**
```
medscribe-ai/ (single Gradle module)
└── src/main/java/com/elioo/healthcare/
    ├── core/                           # Shared infrastructure
    │   ├── base/                       # Generic CRUD (BaseService, etc.)
    │   ├── config/                     # AwsConfig, FirebaseConfig
    │   └── util/                       # Utilities, exception handling
    └── medicalreport/                  # Single bounded context
        └── adapter/out/aws/
            ├── BedrockAdapter.java         (651 lines)
            ├── ComprehendMedicalAdapter.java (349 lines)
            └── TextractAdapter.java        (423 lines)
```

**AWS Adapters Are:**
- ✅ Well-designed with clean port/adapter pattern
- ✅ Use reactive programming (Mono/Flux)
- ✅ Have comprehensive tests (unit + integration)
- ❌ Tightly coupled to `medicalreport` bounded context
- ❌ Cannot be reused by other bounded contexts without copy-paste
- ❌ Configuration is centralized but not modular

### Future Bounded Contexts

Your DDD documentation shows **5 planned bounded contexts**:
1. **Document Processing** (currently `medicalreport` - partially implemented)
2. **Clinical Data** (planned)
3. **Patient Timeline** (planned)
4. **Clinical Intelligence** (planned)
5. **Patient Context** (shared kernel - planned)

**Multiple contexts will need AWS services:**
- Patient Timeline → Textract, Comprehend Medical
- Clinical Intelligence → Bedrock, Comprehend Medical
- Clinical Data → Comprehend Medical, S3
- Document Processing → All three (Textract, Comprehend, Bedrock)

**Without proper library extraction:** Each context would duplicate 1,400+ lines of AWS adapter code! 🚨

---

## Recommended Multi-Module Architecture

### Project Structure

```
medscribe-platform/                              # Root project
│
├── settings.gradle                              # Defines all modules
├── build.gradle                                 # Parent configuration
│
├── elioo-aws-common/                       # Shared AWS infrastructure
│   ├── build.gradle                            # java-library plugin
│   └── src/main/java/.../aws/common/
│       ├── config/
│       │   ├── AwsCredentialsConfiguration.java
│       │   ├── AwsRetryConfiguration.java
│       │   └── AwsProperties.java
│       ├── exception/
│       │   └── AwsServiceException.java
│       └── util/
│           └── AwsResponseParser.java
│
├── elioo-aws-textract/                     # OCR library
│   ├── build.gradle                            # java-library plugin
│   └── src/main/java/.../aws/textract/
│       ├── TextractService.java                # Generic service
│       ├── TextractProperties.java             # Configuration properties
│       ├── model/                              # Generic DTOs
│       │   ├── OcrRequest.java
│       │   ├── OcrResponse.java
│       │   └── ExtractedBlock.java
│       └── autoconfigure/
│           └── TextractAutoConfiguration.java
│
├── elioo-aws-comprehend-medical/           # NLP library
│   ├── build.gradle                            # java-library plugin
│   └── src/main/java/.../aws/comprehend/
│       ├── ComprehendMedicalService.java       # Generic service
│       ├── ComprehendMedicalProperties.java
│       ├── model/
│       │   ├── EntityExtractionRequest.java
│       │   ├── EntityExtractionResponse.java
│       │   └── ExtractedEntity.java
│       └── autoconfigure/
│           └── ComprehendMedicalAutoConfiguration.java
│
├── elioo-aws-bedrock/                      # LLM library
│   ├── build.gradle                            # java-library plugin
│   └── src/main/java/.../aws/bedrock/
│       ├── BedrockService.java                 # Generic LLM service
│       ├── BedrockProperties.java
│       ├── model/
│       │   ├── LlmRequest.java
│       │   ├── LlmResponse.java
│       │   └── ModelParameters.java
│       └── autoconfigure/
│           └── BedrockAutoConfiguration.java
│
├── elioo-aws-starter/                      # Convenience starter
│   └── build.gradle                            # Dependencies only
│       # Transitively includes all AWS libraries
│
└── medscribe-ai/                                # Main application
    ├── build.gradle                            # spring-boot plugin
    └── src/main/java/.../healthcare/
        ├── medicalreport/                      # Bounded context
        │   ├── adapter/out/aws/
        │   │   ├── MedicalTextractAdapter.java  # Medical-specific parsing
        │   │   ├── MedicalComprehendAdapter.java
        │   │   └── ClinicalBedrockAdapter.java  # Medical prompts
        │   └── config/
        │       └── MedicalReportAwsConfig.java  # Override beans
        │
        ├── patienttimeline/                    # Future bounded context
        │   └── adapter/out/aws/
        │       └── TimelineTextractAdapter.java # Different parsing logic
        │
        └── clinicaldata/                       # Future bounded context
            └── config/
                └── ClinicalDataAwsConfig.java  # Different thresholds
```

### Dependency Flow

```
medscribe-ai (application)
    ↓ depends on
elioo-aws-starter
    ↓ transitively includes
├── elioo-aws-textract
├── elioo-aws-comprehend-medical
├── elioo-aws-bedrock
    ↓ all depend on
elioo-aws-common
    ↓ depends on
AWS SDK BOM (software.amazon.awssdk:bom:2.21.26)
```

---

## Library vs Application Separation

### What Goes in Library (Generic)

- ✅ AWS client creation and configuration
- ✅ Generic request/response models
- ✅ API invocation logic
- ✅ Error handling and retry logic
- ✅ Basic response parsing (JSON to objects)
- ✅ Configuration properties classes
- ✅ Auto-configuration classes

### What Stays in Application (Domain-Specific)

- ✅ Port interface implementations (adapters)
- ✅ Domain-specific parsing logic (TestResult extraction)
- ✅ Business rules (status determination, validation)
- ✅ Prompt engineering (Bedrock medical prompts)
- ✅ Domain models (TestResult, MedicalEntity)
- ✅ Use case orchestration

---

## Implementation Phases

### Phase 1: Prepare for Multi-Module (1-2 days) ✅ **COMPLETED**

**Tasks:**
1. ✅ Create new root directory structure
2. ✅ Update `settings.gradle` to define modules
3. ✅ Create parent `build.gradle` with common configuration
4. ✅ Extract common AWS dependencies to parent BOM
5. ✅ Document migration strategy

**What Was Done:**
- Created root [settings.gradle](../settings.gradle) defining `medscribe-platform` with `medscribe-ai` module
- Created parent [build.gradle](../build.gradle) with shared configuration:
  - Java 21 toolchain
  - Common dependencies (Lombok, testing frameworks)
  - AWS SDK BOM (2.21.26)
  - Spring Boot BOM (3.4.2)
- Moved existing code to [medscribe-ai/](../medscribe-ai/) subdirectory
- Created [medscribe-ai/build.gradle](../medscribe-ai/build.gradle) with application-specific dependencies
- Verified build works: `BUILD SUCCESSFUL` ✅
- Generated Spring Boot JAR: `medscribe-ai-0.0.1-SNAPSHOT.jar` (90M)

**Project Structure After Phase 1:**
```
medscribe-platform/                      # Root project
├── settings.gradle                      # Defines all modules
├── build.gradle                         # Parent configuration
├── gradlew, gradlew.bat                # Gradle wrappers (root level)
└── medscribe-ai/                       # Application module
    ├── build.gradle                    # Application dependencies
    ├── src/main/java/...              # All existing code
    └── build/libs/
        └── medscribe-ai-0.0.1-SNAPSHOT.jar  # Executable JAR
```

**Outcome:** Multi-module structure ready, existing application still builds and runs ✅

### Phase 2: Extract AWS Common Module (1 day) ✅ **COMPLETED**

**Tasks:**
1. ✅ Create `elioo-aws-common` module
2. ✅ Extract from `core/config/AwsConfig.java`:
   - Credentials configuration
   - Retry configuration
   - Common properties
3. ✅ Create base exception classes
4. ✅ Create shared utilities (JSON parsing, response parsing)

**What Was Done:**
- Created [elioo-aws-common/](../elioo-aws-common/) module with complete source structure
- Created [AwsCommonProperties.java](../elioo-aws-common/src/main/java/com/elioo/healthcare/aws/common/config/AwsCommonProperties.java):
  - Type-safe configuration for AWS region, credentials, and retry settings
  - `hasStaticCredentials()` helper method
- Created [AwsCommonAutoConfiguration.java](../elioo-aws-common/src/main/java/com/elioo/healthcare/aws/common/config/AwsCommonAutoConfiguration.java):
  - Auto-configures `AwsCredentialsProvider` (static or default chain)
  - Auto-configures AWS `Region` bean
  - Auto-configures `ObjectMapper` for JSON processing
  - Spring Boot auto-discovery via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Created base exception classes:
  - [AwsServiceException.java](../elioo-aws-common/src/main/java/com/elioo/healthcare/aws/common/exception/AwsServiceException.java) - Base class with service name, error code, status code
  - [AwsConfigurationException.java](../elioo-aws-common/src/main/java/com/elioo/healthcare/aws/common/exception/AwsConfigurationException.java) - Configuration errors
  - [AwsValidationException.java](../elioo-aws-common/src/main/java/com/elioo/healthcare/aws/common/exception/AwsValidationException.java) - Request validation errors
- Created [JsonUtils.java](../elioo-aws-common/src/main/java/com/elioo/healthcare/aws/common/util/JsonUtils.java):
  - `cleanJsonFromMarkdown()` - Strips markdown code blocks from LLM responses
  - `parseJson()` - Parses JSON with automatic markdown cleaning
  - `extractFieldOrSelf()` - Handles both direct arrays and object-wrapped responses
- Comprehensive unit tests (14 tests, all passing):
  - [JsonUtilsTest.java](../elioo-aws-common/src/test/java/com/elioo/healthcare/aws/common/util/JsonUtilsTest.java) - 9 tests
  - [AwsCommonAutoConfigurationTest.java](../elioo-aws-common/src/test/java/com/elioo/healthcare/aws/common/config/AwsCommonAutoConfigurationTest.java) - 5 tests
- Generated JARs:
  - `elioo-aws-common-0.0.1-SNAPSHOT.jar` (9.5 KB)
  - `elioo-aws-common-0.0.1-SNAPSHOT-sources.jar` (7.4 KB)
  - `elioo-aws-common-0.0.1-SNAPSHOT-javadoc.jar` (113 KB)

**Module Structure:**
```
elioo-aws-common/
├── build.gradle (library configuration with publishing)
└── src/
    ├── main/
    │   ├── java/com/elioo/healthcare/aws/common/
    │   │   ├── config/
    │   │   │   ├── AwsCommonProperties.java
    │   │   │   └── AwsCommonAutoConfiguration.java
    │   │   ├── exception/
    │   │   │   ├── AwsServiceException.java
    │   │   │   ├── AwsConfigurationException.java
    │   │   │   └── AwsValidationException.java
    │   │   └── util/
    │   │       └── JsonUtils.java
    │   └── resources/META-INF/spring/
    │       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── test/
        └── java/com/elioo/healthcare/aws/common/
            ├── config/AwsCommonAutoConfigurationTest.java
            └── util/JsonUtilsTest.java
```

**Outcome:** Foundation for all AWS libraries complete ✅

### Phase 3: Extract Textract Library (2-3 days)

**Tasks:**
1. Create `elioo-aws-textract` module
2. Extract **generic OCR logic** from `TextractAdapter.java`:
   - AWS client invocation
   - Generic block parsing
   - Image validation
3. Create generic models: `OcrRequest`, `OcrResponse`, `ExtractedBlock`
4. Create `TextractProperties` configuration class
5. Create `TextractAutoConfiguration`
6. Write comprehensive tests
7. Refactor `MedicalTextractAdapter` in application to:
   - Use library's `TextractService`
   - Keep medical-specific `TestResultParser`

**Outcome:** Reusable Textract library + medical adapter using it

### Phase 4: Extract Comprehend Medical Library (2-3 days)

**Tasks:**
1. Create `elioo-aws-comprehend-medical` module
2. Extract **generic NLP logic** from `ComprehendMedicalAdapter.java`
3. Create generic models and configuration
4. Create auto-configuration
5. Write tests
6. Refactor application adapter to use library

**Outcome:** Reusable Comprehend Medical library

### Phase 5: Extract Bedrock Library (3-4 days)

**Tasks:**
1. Create `elioo-aws-bedrock` module
2. Extract **generic LLM logic** from `BedrockAdapter.java`
3. Create generic models and configuration
4. Create auto-configuration
5. Write tests
6. Refactor application to separate library logic from medical prompts

**Outcome:** Reusable Bedrock library + medical prompt service

### Phase 6: Create Starter Module (1 day)

**Tasks:**
1. Create `elioo-aws-starter` module
2. Add dependencies to all three libraries
3. Create aggregated documentation
4. Create usage examples

**Outcome:** Single dependency for applications

### Phase 7: Refactor Main Application (1-2 days)

**Tasks:**
1. Move `medscribe-ai` into multi-module structure
2. Update dependencies to use starter
3. Verify all existing tests pass
4. Update documentation (CLAUDE.md, README.md)

**Outcome:** Application consumes libraries cleanly

### Phase 8: Publish & Document (1-2 days)

**Tasks:**
1. Setup JitPack integration
2. Publish first versions (1.0.0)
3. Create comprehensive documentation
4. Create example projects for each library

**Outcome:** Libraries ready for reuse in other services

---

## Configuration Strategy

### Library Properties (application-aws.properties)

```properties
# Infrastructure configuration
aws.region=us-east-1
aws.credentials.access-key-id=${AWS_ACCESS_KEY_ID}
aws.credentials.secret-access-key=${AWS_SECRET_ACCESS_KEY}

# Enable/disable services
aws.textract.enabled=true
aws.comprehend-medical.enabled=true
aws.bedrock.enabled=true

# Service-specific configuration
aws.textract.max-image-size-mb=10
aws.textract.timeout-seconds=30
aws.textract.retry-attempts=3

aws.comprehend-medical.max-text-length=20000
aws.comprehend-medical.batch-size=25

aws.bedrock.model-id=us.anthropic.claude-3-5-sonnet-20241022-v2:0
aws.bedrock.max-tokens=4096
aws.bedrock.temperature=0.7
```

### Bounded Context Override Examples

**Medical Report Context:**
```properties
aws.comprehend-medical.min-confidence=0.70
aws.bedrock.model-id=us.anthropic.claude-3-5-sonnet-20241022-v2:0
```

**Administrative Context:**
```properties
aws.bedrock.model-id=us.anthropic.claude-3-haiku-20240307-v1:0
aws.bedrock.temperature=0.5
```

---

## Benefits of This Approach

### Immediate Benefits

1. **No Code Duplication:** Future bounded contexts reuse libraries
2. **Consistent Behavior:** All contexts use same AWS integration
3. **Centralized Testing:** Test AWS logic once at library level
4. **Clear Boundaries:** Separation between technical infrastructure and domain logic
5. **Easier Debugging:** Issues isolated to library or application layer

### Long-Term Benefits

1. **Microservices Ready:** Future microservices just add starter dependency
2. **Independent Versioning:** Upgrade AWS SDK in library without touching apps
3. **Team Scalability:** Different teams can work on libraries vs applications
4. **Reusable Across Projects:** Other healthcare projects can use libraries
5. **Better Testing:** Mock library services in application tests
6. **Configuration Flexibility:** Enable/disable AWS services per deployment

---

## Critical Files That Will Be Modified

### New Files to Create

1. `/settings.gradle` (multi-module configuration)
2. `/elioo-aws-common/build.gradle`
3. `/elioo-aws-textract/build.gradle` + src structure
4. `/elioo-aws-comprehend-medical/build.gradle` + src structure
5. `/elioo-aws-bedrock/build.gradle` + src structure
6. `/elioo-aws-starter/build.gradle`
7. Multiple auto-configuration classes (`*AutoConfiguration.java`)
8. Multiple properties classes (`*Properties.java`)

### Files to Modify

1. `/build.gradle` (parent configuration)
2. `/medscribe-ai/build.gradle` (depends on starter)
3. `/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/TextractAdapter.java`
4. `/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/ComprehendMedicalAdapter.java`
5. `/src/main/java/com/elioo/healthcare/medicalreport/adapter/out/aws/BedrockAdapter.java`
6. `/src/main/java/com/elioo/healthcare/core/config/AwsConfig.java`
7. `/src/main/resources/application-aws.properties`
8. `/CLAUDE.md`, `/README.md`

### Files to Move

- Extract generic AWS logic from adapters → library modules
- Move `AwsConfig.java` → `elioo-aws-common`
- Keep medical-specific parsing in application

---

## Success Criteria

### Definition of Done

✅ **Functional:**
- All existing tests pass
- Medical report workflow still functional
- AWS integration tests pass
- New bounded context can use libraries without copy-paste

✅ **Architectural:**
- Clear separation: library (technical) vs application (domain)
- Multi-module Gradle structure
- Auto-configuration working
- Configuration externalized

✅ **Quality:**
- Test coverage maintained or improved
- Documentation complete
- Code review approved
- Performance benchmarks acceptable

✅ **Operational:**
- Libraries published to JitPack
- Version numbers assigned (1.0.0)
- Migration guide written
- Example usage documented

---

## Build Process & JAR Structure

### How Many JARs Will Be Created?

**Total: 5 library JARs + 1 application JAR = 6 JARs**

1. **elioo-aws-common-1.0.0.jar** (~50 KB)
   - Shared AWS configuration
   - Common exception classes
   - Utility functions

2. **elioo-aws-textract-1.0.0.jar** (~100 KB)
   - Textract service wrapper
   - OCR models and auto-configuration
   - Depends on: elioo-aws-common

3. **elioo-aws-comprehend-medical-1.0.0.jar** (~120 KB)
   - Comprehend Medical service wrapper
   - NLP models and auto-configuration
   - Depends on: elioo-aws-common

4. **elioo-aws-bedrock-1.0.0.jar** (~80 KB)
   - Bedrock service wrapper
   - LLM models and auto-configuration
   - Depends on: elioo-aws-common

5. **elioo-aws-starter-1.0.0.jar** (~5 KB)
   - No code, only dependencies (BOM style)
   - Transitively pulls in all 4 libraries above

6. **medscribe-ai-1.0.0.jar** (~15 MB - your application)
   - Spring Boot executable JAR
   - Contains all application code + dependencies
   - Includes the 5 library JARs above bundled inside

### Build Complexity Analysis

#### ✅ **Low Complexity - Standard Gradle Multi-Module**

**Why it's simple:**
- Standard Gradle multi-module pattern (well-documented)
- Each module is independent (can build separately)
- Gradle handles dependency resolution automatically
- No special build tools needed

**Build Commands:**

```bash
# Build everything (all modules)
./gradlew build

# Build specific library only
./gradlew :elioo-aws-textract:build

# Build application only (auto-builds library dependencies)
./gradlew :medscribe-ai:build

# Publish libraries to JitPack (automatic via git tag)
git tag v1.0.0
git push origin v1.0.0
# JitPack automatically builds and publishes
```

### Module Build Order

Gradle automatically determines build order based on dependencies:

```
1. elioo-aws-common (no dependencies)
   ↓
2. elioo-aws-textract (depends on common)
   elioo-aws-comprehend-medical (depends on common)
   elioo-aws-bedrock (depends on common)
   ↓ (parallel build)
3. elioo-aws-starter (depends on all 3 services)
   ↓
4. medscribe-ai (depends on starter)
```

**Time to build all:** ~30-60 seconds (first time), ~5-10 seconds (incremental)

### Gradle Configuration Example

**Root `build.gradle`:**
```gradle
// Parent configuration - shared by all modules
subprojects {
    apply plugin: 'java-library'

    group = 'com.elioo.healthcare'
    version = '1.0.0'

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    repositories {
        mavenCentral()
    }
}
```

**Library module `build.gradle` (e.g., elioo-aws-textract):**
```gradle
plugins {
    id 'java-library'  // NOT spring-boot plugin!
}

dependencies {
    // API = exposed to consumers (transitive)
    api project(':elioo-aws-common')
    api 'software.amazon.awssdk:textract'
    api 'io.projectreactor:reactor-core'

    // Implementation = internal only (not transitive)
    implementation 'com.fasterxml.jackson.core:jackson-databind'

    // Provided = consumer must provide
    compileOnly 'org.springframework.boot:spring-boot-autoconfigure'
}
```

**Application module `build.gradle` (medscribe-ai):**
```gradle
plugins {
    id 'org.springframework.boot' version '3.4.2'  // Creates executable JAR
    id 'io.spring.dependency-management' version '1.1.4'
    id 'java'
}

dependencies {
    // Single dependency pulls in all AWS libraries!
    implementation project(':elioo-aws-starter')

    // Or individual libraries if you want specific ones:
    // implementation project(':elioo-aws-textract')
    // implementation project(':elioo-aws-bedrock')
}
```

**Root `settings.gradle`:**
```gradle
rootProject.name = 'medscribe-platform'

include 'elioo-aws-common'
include 'elioo-aws-textract'
include 'elioo-aws-comprehend-medical'
include 'elioo-aws-bedrock'
include 'elioo-aws-starter'
include 'medscribe-ai'
```

---

## How AWS Services Are Used - Detailed Flow

### Current Architecture (Before Extraction)

**Method Call Flow:**
```
MedicalReportHandler
    ↓ (calls port interface)
OcrPort.extractMedicalData()
    ↓ (implemented by)
TextractAdapter.extractMedicalData()
    ↓ (uses AWS SDK directly)
TextractAsyncClient.analyzeDocument()
    ↓ (AWS HTTP API call)
AWS Textract Service (cloud)
```

### New Architecture (After Extraction)

**Method Call Flow with Library:**
```
MedicalReportHandler (APPLICATION)
    ↓ (calls port interface)
OcrPort.extractMedicalData()
    ↓ (implemented by)
MedicalTextractAdapter (APPLICATION - domain-specific)
    ↓ (calls library service - method call, NOT API call)
TextractService.analyzeDocument() (LIBRARY - generic)
    ↓ (uses AWS SDK)
TextractAsyncClient.analyzeDocument() (AWS SDK)
    ↓ (AWS HTTP API call)
AWS Textract Service (cloud)
```

### Key Point: **Library Calls Are Local Java Method Calls**

**NOT API calls!** Libraries are in the same JVM process.

```java
// LIBRARY CODE (elioo-aws-textract module)
package com.elioo.healthcare.aws.textract;

@Service  // Spring bean
public class TextractService {
    private final TextractAsyncClient awsClient;

    // Generic OCR - no domain knowledge
    public Mono<OcrResponse> analyzeDocument(OcrRequest request) {
        // Build AWS request
        AnalyzeDocumentRequest awsRequest = AnalyzeDocumentRequest.builder()
            .document(Document.builder()
                .bytes(SdkBytes.fromString(request.imageBase64(), StandardCharsets.UTF_8))
                .build())
            .featureTypes(request.featureTypes())
            .build();

        // Call AWS Textract (HTTP API call to AWS cloud)
        return Mono.fromFuture(awsClient.analyzeDocument(awsRequest))
            .map(response -> {
                // Generic mapping: AWS blocks → Generic OcrResponse
                List<ExtractedBlock> blocks = response.blocks().stream()
                    .map(block -> new ExtractedBlock(
                        block.id(),
                        block.blockTypeAsString(),
                        block.text(),
                        block.confidence(),
                        // ... more fields
                    ))
                    .toList();

                return new OcrResponse(blocks, response.jobStatus());
            });
    }
}

// Generic models (no medical domain)
public record OcrRequest(String imageBase64, List<String> featureTypes) {}
public record OcrResponse(List<ExtractedBlock> blocks, String status) {}
public record ExtractedBlock(String id, String blockType, String text, Float confidence) {}
```

```java
// APPLICATION CODE (medscribe-ai module)
package com.elioo.healthcare.medicalreport.adapter.out.aws;

@Component  // Spring bean
public class MedicalTextractAdapter implements OcrPort {

    private final TextractService textractService;  // Injected from library
    private final TestResultParser testResultParser;  // Medical-specific

    @Autowired
    public MedicalTextractAdapter(TextractService textractService) {
        this.textractService = textractService;  // Spring auto-wires library bean
    }

    @Override
    public Flux<TestResult> extractMedicalData(String imageBase64, String reportType) {
        // 1. Call library service (LOCAL METHOD CALL - same JVM)
        OcrRequest request = new OcrRequest(
            imageBase64,
            List.of("TABLES", "FORMS", "LAYOUT")
        );

        return textractService.analyzeDocument(request)  // Method call to library
            .flatMapMany(ocrResponse -> {
                // 2. Medical-specific parsing (domain logic)
                List<ExtractedBlock> blocks = ocrResponse.blocks();

                // Parse generic blocks into TestResult domain objects
                return testResultParser.parseTestResults(blocks, reportType);
            });
    }
}

// Medical-specific parser (stays in application)
@Component
public class TestResultParser {
    public Flux<TestResult> parseTestResults(List<ExtractedBlock> blocks, String reportType) {
        // Complex medical logic:
        // - Find table blocks with test results
        // - Extract test names, values, units, reference ranges
        // - Determine status: NORMAL, ABNORMAL, CRITICAL
        // - Apply medical business rules

        List<TestResult> results = new ArrayList<>();

        // Medical parsing logic here...
        for (ExtractedBlock block : blocks) {
            if (block.blockType().equals("TABLE")) {
                // Extract medical test results from table
                TestResult result = parseTableAsTestResult(block);
                results.add(result);
            }
        }

        return Flux.fromIterable(results);
    }
}
```

### Communication Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                     medscribe-ai.jar                        │
│  ┌────────────────────────────────────────────────────────┐ │
│  │           Application Code (Domain Logic)              │ │
│  │                                                          │ │
│  │  MedicalTextractAdapter (implements OcrPort)           │ │
│  │         ↓ (method call)                                │ │
│  │  TestResultParser (medical parsing)                    │ │
│  └────────────────────────────────────────────────────────┘ │
│                        ↓ (method call)                       │
│  ┌────────────────────────────────────────────────────────┐ │
│  │     Embedded Library JARs (Technical Infrastructure)   │ │
│  │                                                          │ │
│  │  elioo-aws-textract.jar                           │ │
│  │    TextractService (generic OCR service)               │ │
│  │         ↓ (uses AWS SDK)                               │ │
│  │    TextractAsyncClient                                 │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                        ↓ (HTTPS API call)
┌─────────────────────────────────────────────────────────────┐
│               AWS Cloud (External Service)                  │
│                                                              │
│  AWS Textract Service (analyzes documents)                 │
└─────────────────────────────────────────────────────────────┘
```

### Dependency Injection (Spring Auto-Wiring)

**How library beans are available to application:**

1. **Library has auto-configuration:**
```java
// In elioo-aws-textract module
@AutoConfiguration
@ConditionalOnClass(TextractAsyncClient.class)
@EnableConfigurationProperties(TextractProperties.class)
public class TextractAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TextractAsyncClient textractClient(AwsCredentialsProvider credentials) {
        return TextractAsyncClient.builder()
            .credentialsProvider(credentials)
            .region(Region.US_EAST_1)
            .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public TextractService textractService(
            TextractAsyncClient client,
            TextractProperties properties) {
        return new TextractService(client, properties);
    }
}
```

2. **Spring Boot auto-discovers configuration:**
   - Reads `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
   - Automatically creates `TextractService` bean
   - Available for injection in application

3. **Application injects library bean:**
```java
@Component
public class MedicalTextractAdapter implements OcrPort {

    private final TextractService textractService;  // Spring finds this bean!

    @Autowired  // Constructor injection
    public MedicalTextractAdapter(TextractService textractService) {
        this.textractService = textractService;
    }
}
```

### Performance Characteristics

**Method Calls (Library → Application):**
- ⚡ **Nanoseconds** - in-process JVM method invocation
- No network overhead
- No serialization/deserialization
- Same memory space

**AWS API Calls (Application → AWS Cloud):**
- 🌐 **100-500 milliseconds** - network round-trip to AWS
- HTTPS connection
- JSON serialization/deserialization
- Authentication overhead

**Key Insight:** Library extraction adds **zero latency** - it's just code reorganization!

---

## Development Workflow

### Local Development

**Before extraction (current):**
```bash
# Single module
./gradlew build
./gradlew bootRun
```

**After extraction (multi-module):**
```bash
# Build everything
./gradlew build

# Run application (auto-builds dependencies)
./gradlew :medscribe-ai:bootRun

# Test specific library
./gradlew :elioo-aws-textract:test

# Clean rebuild
./gradlew clean build
```

### IDE Support

**IntelliJ IDEA / Eclipse:**
- Import as Gradle project (same as before)
- IDE automatically recognizes multi-module structure
- Can navigate between modules seamlessly
- Refactoring works across modules

### Debugging

**Debug flow across modules:**
1. Set breakpoint in `MedicalTextractAdapter` (application)
2. Step into `textractService.analyzeDocument()` (library)
3. Step into `awsClient.analyzeDocument()` (AWS SDK)
4. **All in same debug session** - no special setup needed

---

## Deployment & Runtime

### Single Deployment Unit

**Important: Application JAR contains everything!**

```
medscribe-ai-1.0.0.jar (executable Spring Boot JAR)
├── BOOT-INF/
│   ├── classes/           # Your application code
│   └── lib/               # All dependencies (including library JARs)
│       ├── elioo-aws-common-1.0.0.jar
│       ├── elioo-aws-textract-1.0.0.jar
│       ├── elioo-aws-comprehend-medical-1.0.0.jar
│       ├── elioo-aws-bedrock-1.0.0.jar
│       ├── spring-boot-*.jar
│       ├── aws-sdk-*.jar
│       └── ... (all other dependencies)
└── org.springframework.boot.loader/
```

**Deploy exactly as before:**
```bash
# Same deployment process - nothing changes!
java -jar medscribe-ai-1.0.0.jar

# Or with Docker
docker run -p 8080:8080 medscribe-ai:latest
```

### Library JARs Are NOT Deployed Separately

**Libraries only matter for:**
1. ✅ Development (code organization)
2. ✅ Testing (independent testing)
3. ✅ Reuse (other microservices can depend on them)

**Libraries DON'T matter for:**
- ❌ Deployment (bundled into application JAR)
- ❌ Runtime classpath management (Spring Boot handles it)
- ❌ Configuration (properties apply to final application)

### Other Microservices Using Libraries

**Future microservice (e.g., billing-service):**

```gradle
// billing-service/build.gradle
dependencies {
    // Option 1: From JitPack (after publishing)
    implementation 'com.elioo.healthcare:elioo-aws-textract:1.0.0'

    // Option 2: From multi-module project
    implementation project(':elioo-aws-textract')
}
```

**Result:** Billing service gets same Textract functionality, zero code duplication!

---

## Ready for Implementation

This plan is finalized with your decisions:
- ✅ Healthcare-focused naming
- ✅ JitPack publishing (GitLab/GitHub)
- ✅ Aggressive 2-3 week timeline
- ✅ Properties-based configuration
- ✅ Big bang migration

**The plan is comprehensive, risk-mitigated, and ready to execute.**

---

**Last Updated:** November 30, 2024
**MedScribe AI Team**
