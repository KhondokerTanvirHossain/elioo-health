# MedScribe AI

> Intelligent medical document processing and health record management powered by AWS AI services

MedScribe AI is a Spring Boot reactive application that automates medical document processing using OCR, medical terminology extraction, coding, timeline visualization, and intelligent summarization. Built with Clean Architecture principles for scalability and maintainability.

## Features

- **OCR Processing** - Extract text from medical documents (Bangla + English support)
- **Medical Coding** - Automatic mapping to ICD-10, RxNorm, LOINC, SNOMED CT, CPT codes
- **Timeline Visualization** - Chronological health events with abnormal value highlighting
- **Voice Transcription** - Convert doctor-patient consultations to structured data
- **Intelligent Summarization** - Generate patient summaries, SOAP notes, and referral summaries

## Tech Stack

- **Backend**: Spring Boot 3.4.2 (Reactive), WebFlux, Java 21
- **Architecture**: Hexagonal Architecture (Ports & Adapters / Clean Architecture)
- **AWS Services**: Textract (planned), Comprehend Medical (planned), Bedrock Claude 3 (planned), S3
- **Database**: PostgreSQL with R2DBC (reactive)
- **Build Tool**: Gradle 8.12.1
- **Object Mapping**: ModelMapper
- **Logging**: Logback with Logstash encoder, Slack appender
- **Tracing**: Zipkin/Brave for distributed tracing
- **Testing**: JUnit 5, Testcontainers, Reactor Test

## Prerequisites

- Java 21 or higher
- Gradle 8.x (or use included Gradle wrapper `./gradlew`)
- PostgreSQL 14+
- Docker & Docker Compose (optional, for containerized deployment)
- AWS Account with configured services (for production):
  - Amazon Textract (planned integration)
  - Amazon Comprehend Medical (planned integration)
  - Amazon Bedrock (planned integration)
  - Amazon S3

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/KhondokerTanvirHossain/elioo-health.git
cd elioo-health
```

### 2. Setup PostgreSQL Database

```bash
# Create database and schema
createdb pfh
psql -d pfh -c "CREATE SCHEMA IF NOT EXISTS pfh;"

# Create user (if needed)
psql -d pfh -c "CREATE USER pfh WITH PASSWORD <YOUR_DB_PASSWORD>;"
psql -d pfh -c "GRANT ALL PRIVILEGES ON DATABASE pfh TO pfh;"
psql -d pfh -c "GRANT ALL PRIVILEGES ON SCHEMA pfh TO pfh;"
```

### 3. Configure Application

Edit `src/main/resources/application-local.properties`:

```properties
# Database Connection (R2DBC)
spring.r2dbc.url=r2dbc:postgresql://localhost:5432/pfh?schema=pfh
spring.r2dbc.username=pfh
spring.r2dbc.password=<YOUR_DB_PASSWORD>

# Server Port
server.port=8086

# Feature Flagging (optional)
feature-flagging.base-url=http://localhost:8087
```

Configure active profile in `src/main/resources/application.properties`:
```properties
spring.profiles.active=local2
```

### 4. Build the Application

```bash
# Build with tests
./gradlew build

# Build without tests
./gradlew build -x test
```

### 5. Run the Application

```bash
# Run using Gradle
./gradlew bootRun

# Or run the JAR directly
java -jar build/libs/medscribe-ai-0.0.1-SNAPSHOT.jar
```

The application will be available at `http://localhost:8086`

## API Documentation

Once the application is running, you can:

- **Test APIs**: Use the [rest.http](rest.http) file in your IDE (IntelliJ IDEA supports this natively)
- **Health Check**: `http://localhost:8086/actuator/health` or `http://localhost:8086/api/v1/notification/pfh/actuator/health`
- **Complete Documentation**: See [API_DOCUMENTATION.md](API_DOCUMENTATION.md)

### Key Endpoints

**Medical Report Processing (3-Step Workflow)**

Step 1: Extract text from medical test report images
```
POST /api/v1/medical-report/ocr
```

Step 2: Classify medical entities using AWS Comprehend Medical
```
POST /api/v1/medical-report/classify
```

Step 3: Generate AI-powered insights and suggestions
```
POST /api/v1/medical-report/suggestions
```

**Hello World (Testing)**
```
GET /api/hello
```

**Other Endpoints (Planned)**

OCR Processing:
```
POST /api/v1/documents/scan
POST /api/v1/documents/{id}/ocr
GET /api/v1/documents/{id}/text
```

Medical Coding:
```
POST /api/v1/medical/extract-terms
POST /api/v1/medical/map-codes
PUT /api/v1/medical/terms/{id}/review
```

Timeline:
```
GET /api/v1/timeline/{patientId}
GET /api/v1/timeline/{patientId}/filter
```

Summarization:
```
POST /api/v1/summarization/generate
GET /api/v1/summarization/{summaryId}
```

---

## Medical Report Processing Workflow

### Complete 3-Step Example

#### Step 1: OCR Processing
Extract structured data from a blood test report image:

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/ocr \
  -H "Content-Type: application/json" \
  -d '{
    "imageBase64": "base64_encoded_image...",
    "patientId": "P12345",
    "reportType": "BLOOD_TEST"
  }'
```

**Response:**
```json
{
  "reportId": "RPT-2024-001",
  "patientId": "P12345",
  "extractedData": [
    {
      "testName": "Serum Creatinine",
      "testValue": "135.0",
      "unit": "µmol/L",
      "referenceRange": "Male: 59-104, Female: 45-84",
      "status": "ABNORMAL"
    },
    {
      "testName": "Blood Ammonia",
      "testValue": "244.0",
      "unit": "µg/dL",
      "referenceRange": "Adult: 19-54 µg/dL",
      "status": "CRITICAL"
    }
  ],
  "confidence": 0.96,
  "processedAt": "2024-01-15T10:30:00Z"
}
```

#### Step 2: Medical Entity Classification
Classify and extract medical entities:

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/classify \
  -H "Content-Type: application/json" \
  -d '{
    "reportId": "RPT-2024-001",
    "extractedData": [...]
  }'
```

**Response:**
```json
{
  "reportId": "RPT-2024-001",
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

#### Step 3: AI-Powered Suggestions
Generate insights and recommendations:

```bash
curl -X POST http://localhost:8086/api/v1/medical-report/suggestions \
  -H "Content-Type: application/json" \
  -d '{
    "reportId": "RPT-2024-001",
    "patientContext": {
      "age": 45,
      "gender": "MALE",
      "medicalHistory": ["Diabetes Type 2"]
    }
  }'
```

**Response:**
```json
{
  "reportId": "RPT-2024-001",
  "summary": "Your recent blood tests show concerning results...",
  "keyFindings": [
    {
      "finding": "Elevated Blood Ammonia (244.0 µg/dL)",
      "severity": "HIGH",
      "interpretation": "Indicates liver may not be processing waste products effectively..."
    }
  ],
  "aiSuggestions": [
    {
      "category": "IMMEDIATE_ACTION",
      "priority": "HIGH",
      "recommendation": "See a liver specialist within 24-48 hours",
      "rationale": "Combination of elevated ammonia and liver enzymes requires immediate evaluation"
    }
  ],
  "riskLevel": "HIGH",
  "requiresImmediateAttention": true
}
```

For complete API documentation, see [API_DOCUMENTATION.md](API_DOCUMENTATION.md)

## Development

### Build Commands

```bash
# Clean build
./gradlew clean build

# Build without tests
./gradlew build -x test

# Run tests
./gradlew test

# Run specific test
./gradlew test --tests "com.elioo.healthcare.MedscribeAiApplicationTests"

# Run application
./gradlew bootRun

# Check dependencies
./gradlew dependencies
```

### Docker Development

```bash
# Build Docker image
docker build -t medscribe-ai:v1 .

# Run Docker container
docker run -dit --rm -p 8086:8086 medscribe-ai:v1

# Or use docker-compose (if configured)
docker-compose up -d
```

### Code Style and Architecture

This project follows:
- **Hexagonal Architecture** (Ports & Adapters)
- **Reactive Programming** with Spring WebFlux and R2DBC
- **Constructor-based Dependency Injection** (using Lombok's `@RequiredArgsConstructor`)
- **Standard Java naming conventions**

## Project Structure

This project follows **Hexagonal Architecture** with clear separation of concerns:

```
com.elioo.healthcare/
├── core/                          # Shared infrastructure
│   ├── base/                      # Generic CRUD functionality (BaseService, BaseHandler, BaseRepository)
│   ├── config/                    # Configuration classes (Firebase, ModelMapper)
│   ├── filters/                   # Web filters (MDC, tracing)
│   ├── util/                      # Utilities and exception handling
│   └── routes/                    # Route name constants
│
├── medicalreport/                 # Medical report processing feature
│   ├── domain/                    # Business entities (TestResult, enums)
│   ├── dto/                       # Request/Response DTOs
│   ├── application/
│   │   ├── port/in/              # Inbound ports (use case interfaces)
│   │   ├── port/out/             # Outbound ports (persistence interfaces)
│   │   └── service/              # Use case implementations
│   └── adapter/
│       ├── in/web/               # Web layer (handlers, routers)
│       └── out/persistence/      # Persistence layer (entities, repositories, adapters)
│
└── hello/                         # Hello World example
    └── adapter/in/web/           # Handler and router
```

**Key Layers:**
- **Domain**: Pure business logic (framework-independent)
- **Application**: Use cases and business services
  - **In Ports**: Driving interfaces (use cases)
  - **Out Ports**: Driven interfaces (persistence, gateways)
- **Adapters**: Infrastructure implementations
  - **In Adapters**: Web handlers, routers
  - **Out Adapters**: Persistence, external services

For detailed architecture documentation, see [CLAUDE.md](CLAUDE.md)

## Team

- **FR-002 (Medical Coding)**: Tanvir
- **FR-003 (Timeline)**: TBD

## Performance Targets

| Feature | Target | Max |
|---------|--------|-----|
| OCR Processing | < 8s | 10s |
| Medical Coding | < 3s | 5s |
| Timeline Loading | < 2s | 3s |
| Summarization | < 4s | 5s |

## Compliance

- HIPAA-compliant data handling
- Data encryption at rest and in transit
- Audit logging for all data access
- PHI data masking in logs

## Contributing

This is a private healthcare project. For access or contributions:

1. Contact the Health Care Team
2. Follow the branching strategy:
   - `main`: Production-ready code
   - `develop`: Integration branch
   - `feature/*`: Feature branches
3. Commit convention: `<type>(<scope>): <subject>`
   - Example: `feat(ocr): implement textract integration`

## Support

- **GitLab**: https://github.com/KhondokerTanvirHossain/elioo-health
- **Team**: Health Care Team

## License

Proprietary - All rights reserved

---

*For detailed technical documentation, architecture details, and implementation guidelines, see [.claude.md](.claude.md)*
