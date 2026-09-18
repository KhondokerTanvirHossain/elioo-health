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

### 2. Start a local database

```bash
docker compose up -d        # Postgres 17 on localhost:5433 (db/user/pass: medscribe)
```

Or point at Supabase instead: copy `medscribe.env.example` to `.env.local`, fill in the
`PG*` values, then `set -a; source .env.local; set +a` before running.

### 3. Run

```bash
./gradlew :medscribe-ai:bootRun      # http://localhost:8086 (UI, /actuator/health)
```

Clinical insights and chat use the provider named by `LLM_PROVIDER` (`groq` default, `anthropic`,
`openai`, or `bedrock`) with the matching `*_API_KEY`. Switch providers by changing the variable and
restarting; no rebuild needed. See `medscribe.env.example` for every variable.

Flyway applies `medscribe-ai/src/main/resources/db/migration/V*.sql` automatically.
Without GCP credentials the app starts in degraded mode (OCR and translation fail, everything
else works). AWS calls need valid keys in `~/.aws` or `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`.

### 4. Build and test

```bash
./gradlew build                                   # all modules, unit tests
RUN_AWS_INTEGRATION_TESTS=true ./gradlew test     # also the real-AWS integration tests
```

The application will be available at `http://localhost:8086`

## API Documentation

Once the application is running, you can:

- **Test APIs**: Use the [rest.http](rest.http) file in your IDE (IntelliJ IDEA supports this natively)
- **Health Check**: `http://localhost:8086/actuator/health`
- **Complete Documentation**: See [API_DOCUMENTATION.md](API_DOCUMENTATION.md)

### Key Endpoints

Since DR-11 the MedScribe PoC lives under `/medscribeai/` (its UI at `/medscribeai/`, every API route below it);
Medioo (codename Baymax) serves `/`. The PoC is frozen; the routes are listed in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
exercised in [docs/requests.http](docs/requests.http).

```
POST /medscribeai/api/v1/medical-report/process                         image + patient context → 202 + reportId
GET  /medscribeai/api/v1/medical-report/query/status/{reportId}         poll
GET  /medscribeai/api/v1/medical-report/query/results/{reportId}/all    results per stage (ocr, classification, icd10, …, all)
POST /medscribeai/api/v1/medical-report/chat/{reportId}/send            ask about the report
```

## Medical Report Processing Workflow

### Upload → process → results

```bash
B64=$(base64 -i report.png | tr -d '\n')
curl -s -X POST http://localhost:8086/medscribeai/api/v1/medical-report/process \
  -H "Content-Type: application/json" \
  -d "{\"imageBase64\":\"$B64\",\"patientContext\":{\"patientId\":\"DEMO-1\",\"age\":45,\"gender\":\"MALE\"}}"
# → {"reportId":"RPT-…","status":"PENDING",…}
curl -s http://localhost:8086/medscribeai/api/v1/medical-report/query/status/RPT-…
curl -s http://localhost:8086/medscribeai/api/v1/medical-report/query/results/RPT-…/all
```

---

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

## Deployment

Every push runs the **CI/CD** workflow (`.github/workflows/ci-cd.yml`):

1. `build` — Gradle build and tests on every branch and PR.
2. `deploy` (only on `main`) — builds the Docker image, pushes it to
   `ghcr.io/khondokertanvirhossain/elioo-health/medscribe-ai:<sha>`, then SSHes to the EC2
   host and runs `deploy.sh`, which replaces the `medscribe-ai` container and waits for
   `/actuator/health`.

Server prerequisites (one-time): Docker, `/home/ec2-user/medscribe.env` (from
`medscribe.env.example`, mode 600), and GitHub secrets `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`.

Manual rollback on the server: `IMAGE_TAG=<previous sha> bash ~/deploy.sh`.

Local image build, if you need it: `docker build -t medscribe-ai:local .`

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
