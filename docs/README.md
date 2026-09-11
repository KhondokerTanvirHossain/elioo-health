# MedScribe AI Documentation Index

> Comprehensive documentation for the MedScribe AI medical document processing system

## Table of Contents

1. [Getting Started](#getting-started)
2. [Architecture & Design](#architecture--design)
3. [API Documentation](#api-documentation)
4. [Domain Model](#domain-model)
5. [Development Guide](#development-guide)

---

## Getting Started

### Quick Links
- **[Main README](../README.md)** - Project overview, setup instructions, and quick start guide
- **[Claude AI Context](../CLAUDE.md)** - Development guide for working with this codebase using Claude Code

### Prerequisites
- Java 21 or higher
- Gradle 8.12.1
- PostgreSQL 14+
- AWS Account (for production deployment)

### First Steps
1. Follow the setup instructions in [README.md](../README.md)
2. Review the architecture in [CLAUDE.md](../CLAUDE.md)
3. Explore the [API endpoints](#api-documentation)

---

## Architecture & Design

### Hexagonal Architecture (Ports & Adapters)

This project follows **Clean Architecture** principles with clear separation between:
- **Domain Layer**: Business entities and logic
- **Application Layer**: Use cases and business services
- **Adapter Layer**: Web handlers, persistence, and external integrations

For complete architectural details, see [CLAUDE.md](../CLAUDE.md)

### Domain-Driven Design

**[Domain Model Documentation](DDD_DOMAIN_MODEL.md)** - Comprehensive DDD analysis including:

#### Bounded Contexts
1. **Document Processing Context** - OCR and text extraction
2. **Clinical Data Context** - Medical entity classification and coding
3. **Patient Timeline Context** - Historical health event visualization
4. **Clinical Intelligence Context** - AI-powered insights and summarization
5. **Patient Context** - Shared patient information (Shared Kernel)

#### Key Concepts
- **Aggregates**: MedicalDocument, ClinicalRecord, PatientTimeline, ClinicalInsight, Patient
- **Value Objects**: TestValue, MedicalCode, ConfidenceScore, ClinicalSignificance
- **Domain Events**: DocumentProcessed, EntitiesClassified, TimelineUpdated, InsightsGenerated
- **Domain Services**: Clinical classification, abnormal value detection, trend analysis

[Read the complete DDD documentation →](DDD_DOMAIN_MODEL.md)

---

## API Documentation

### Overview

MedScribe AI provides three core APIs that work together to process medical documents:

```
Medical Document (Image)
         ↓
    [1. OCR API] ────────→ Structured Text Data
         ↓
    [2. Classification API] ──→ Medical Entities + Codes
         ↓
    [3. Suggestions API] ────→ Clinical Insights + Recommendations
```

### API Endpoints

#### 1. OCR API - Text Extraction
**[Complete OCR Documentation](endpoints/OCR_API.md)**

Extract structured data from medical document images with support for Bangla and English.

- **Endpoint**: `POST /api/v1/medical-report/ocr`
- **Features**:
  - Image quality validation
  - Multi-language support (Bangla + English)
  - Confidence scoring
  - Abnormal value detection
- **Performance**: 2-12 seconds depending on image complexity
- **Use Cases**: Blood tests, urine tests, radiology reports, pathology reports

[View detailed OCR documentation →](endpoints/OCR_API.md)

---

#### 2. Classification API - Medical Entity Recognition
**[Complete Classification Documentation](endpoints/CLASSIFICATION_API.md)**

Classify medical entities and map to standard medical coding systems using AWS Comprehend Medical.

- **Endpoint**: `POST /api/v1/medical-report/classify`
- **Features**:
  - Entity recognition (conditions, medications, tests, anatomy)
  - Medical code mapping (ICD-10, LOINC, SNOMED CT, RxNorm, CPT)
  - Relationship extraction between entities
  - Attribute detection (dosage, frequency, severity)
- **Performance**: 1-3 seconds
- **Supported Code Systems**: 6 major medical coding standards

[View detailed Classification documentation →](endpoints/CLASSIFICATION_API.md)

---

#### 3. Suggestions API - Clinical Intelligence
**[Complete Suggestions Documentation](endpoints/SUGGESTIONS_API.md)**

Generate AI-powered clinical insights, recommendations, and risk assessments using AWS Bedrock.

- **Endpoint**: `POST /api/v1/medical-report/suggestions`
- **Features**:
  - Patient-friendly summaries
  - Risk assessment (cardiovascular, metabolic, renal, hepatic)
  - Evidence-based recommendations
  - Trend analysis from historical data
  - Action plans with prioritization
  - Educational content
- **Performance**: 3-5 seconds
- **Customization**: Audience-specific language, risk focus areas

[View detailed Suggestions documentation →](endpoints/SUGGESTIONS_API.md)

---

### High-Level API Documentation

**[API_DOCUMENTATION.md](../API_DOCUMENTATION.md)** - Quick reference guide covering:
- Complete 3-step workflow examples
- Security and authentication
- Rate limits and quotas
- Error handling
- Data retention policies
- Compliance (HIPAA, data encryption)

---

## Domain Model

### Entity Relationship Overview

```
Patient (Aggregate Root - Shared Kernel)
    └── Demographics
    └── MedicalHistory
    └── Medications
    └── Allergies

MedicalDocument (Document Processing Context)
    ├── DocumentMetadata
    ├── ProcessingStatus
    └── ExtractedData[]
        └── TestResult (Value Object)

ClinicalRecord (Clinical Data Context)
    ├── MedicalEntities[]
    │   ├── MedicalCodes[]
    │   └── EntityAttributes[]
    └── EntityRelationships[]

PatientTimeline (Patient Timeline Context)
    └── MedicalEvents[]
        ├── ObservationEvent
        ├── TreatmentEvent
        └── DiagnosisEvent

ClinicalInsight (Clinical Intelligence Context)
    ├── KeyFindings[]
    ├── RiskAssessment
    ├── Recommendations[]
    └── ActionPlan
```

### Domain Events Flow

```
DocumentUploaded
    ↓
DocumentProcessed (OCR Complete)
    ↓
EntitiesClassified (Classification Complete)
    ↓
TimelineUpdated (Events Added to Timeline)
    ↓
InsightsGenerated (AI Analysis Complete)
    ↓
PatientNotified
```

[Explore complete domain model →](DDD_DOMAIN_MODEL.md)

---

## Development Guide

### Project Structure

```
com.elioo.healthcare/
├── core/                          # Shared infrastructure
│   ├── base/                      # Generic CRUD (BaseService, BaseHandler)
│   ├── config/                    # Configuration (Firebase, ModelMapper)
│   ├── filters/                   # Web filters (MDC, tracing)
│   └── util/                      # Utilities and exception handling
│
├── medicalreport/                 # Medical report processing feature
│   ├── domain/                    # Business entities
│   ├── dto/                       # Request/Response DTOs
│   ├── application/
│   │   ├── port/in/              # Use case interfaces
│   │   ├── port/out/             # Persistence interfaces
│   │   └── service/              # Business logic implementation
│   └── adapter/
│       ├── in/web/               # HTTP handlers and routers
│       └── out/persistence/      # Database repositories
│
└── hello/                         # Hello World example
```

### Tech Stack

- **Backend**: Spring Boot 3.4.2 (Reactive WebFlux), Java 21
- **Database**: PostgreSQL with R2DBC (reactive)
- **AWS Services**: Textract, Comprehend Medical, Bedrock Claude 3, S3
- **Build Tool**: Gradle 8.12.1
- **Testing**: JUnit 5, Testcontainers, Reactor Test

### Key Development Commands

```bash
# Build project
./gradlew build

# Run tests
./gradlew test

# Run application
./gradlew bootRun

# Build Docker image
docker build -t medscribe-ai:v1 .
```

### Adding New Features

To add a new feature module following hexagonal architecture:

1. Create package structure under `com.elioo.healthcare.{feature}`
2. Define domain entities and value objects
3. Create use case interfaces (in ports)
4. Implement business services
5. Create persistence interfaces (out ports)
6. Implement adapters (web handlers, persistence)
7. Register routes and dependencies

See [CLAUDE.md](../CLAUDE.md) for detailed implementation guidelines.

---

## Testing

### Test Strategy

- **Unit Tests**: Business logic in service layer
- **Integration Tests**: API endpoints with Testcontainers
- **Contract Tests**: AWS service integrations

### Test Files

```
src/test/java/com/elioo/healthcare/
├── medicalreport/
│   ├── application/service/
│   └── adapter/in/web/
└── MedscribeAiApplicationTests.java
```

### Running Tests

```bash
# All tests
./gradlew test

# Specific test class
./gradlew test --tests "com.elioo.healthcare.medicalreport.*"

# Integration tests only
./gradlew integrationTest
```

---

## Compliance & Security

### HIPAA Compliance

- ✅ Data encryption at rest and in transit
- ✅ Audit logging for all data access
- ✅ PHI data masking in logs
- ✅ Access control and authentication
- ✅ Data retention policies

### Security Features

- AWS IAM role-based access control
- Encrypted S3 storage for medical documents
- HTTPS/TLS for all API communications
- Input validation and sanitization
- Rate limiting and DDoS protection

---

## Performance Targets

| Feature | Target | Maximum |
|---------|--------|---------|
| OCR Processing | < 8s | 10s |
| Medical Classification | < 3s | 5s |
| AI Suggestions | < 4s | 5s |
| Timeline Loading | < 2s | 3s |

---

## Additional Resources

### External Documentation

- [Spring WebFlux Documentation](https://docs.spring.io/spring-framework/reference/web/webflux.html)
- [AWS Textract Developer Guide](https://docs.aws.amazon.com/textract/)
- [AWS Comprehend Medical Guide](https://docs.aws.amazon.com/comprehend-medical/)
- [AWS Bedrock Documentation](https://docs.aws.amazon.com/bedrock/)

### Medical Coding Systems

- [ICD-10 Code Sets](https://www.cdc.gov/nchs/icd/icd-10-cm.htm)
- [LOINC Database](https://loinc.org/)
- [SNOMED CT Browser](https://browser.ihtsdotools.org/)
- [RxNorm Documentation](https://www.nlm.nih.gov/research/umls/rxnorm/)

### Support

- **GitLab**: https://github.com/KhondokerTanvirHossain/elioo-health
- **Team**: Health Care Team
---

## Document Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 1.0.0 | 2024-01-15 | Initial documentation structure | MedScribe AI Team |
| 1.1.0 | 2024-01-15 | Added DDD domain model and detailed API docs | Claude Code |

---

## Contributing

For information on contributing to this project:

1. Review the [architecture documentation](../CLAUDE.md)
2. Follow the [domain model](DDD_DOMAIN_MODEL.md) for new features
3. Ensure API changes are documented in endpoint files
4. Write tests following existing patterns
5. Submit merge requests following GitLab workflow

**Branching Strategy**:
- `main`: Production-ready code
- `develop`: Integration branch
- `feature/*`: Feature branches

**Commit Convention**: `<type>(<scope>): <subject>`
- Example: `feat(ocr): add multi-language support`
- Types: feat, fix, docs, refactor, test, chore

---

*Last Updated: January 2025*
