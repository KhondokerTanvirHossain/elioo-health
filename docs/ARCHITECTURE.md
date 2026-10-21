# MedScribe AI — Architecture (current)

Last verified: 2026-09-12. Historical design notes live in `docs/archive/`; treat those as history, not truth.

## What it does

A photo of a medical document (lab report or prescription, Bangla and/or English) goes in; structured
results, standard medical codes, an AI clinical summary, and a chat about the report come out.
Everything is asynchronous: the client gets a report id immediately and polls.

```
POST /medscribeai/api/v1/medical-report/process  (image, patient context)      -> 202 + reportId
   background pipeline, one DB row per stage:
   IMAGE_VALIDATION -> OCR_PROCESSING (GCP Vision) -> TRANSLATION (GCP Translate, Bangla->English)
   -> ENTITY_DETECTION -> ICD10 -> RXNORM -> SNOMEDCT (AWS Comprehend Medical)
   -> CLINICAL_INSIGHTS (LLM: summary, risk, recommendations, education)
GET  /medscribeai/api/v1/medical-report/query/status/{id}    poll
GET  /medscribeai/api/v1/medical-report/query/results/{id}/{ocr|classification|icd10|rxnorm|snomedct|insights|risk-assessment|recommendations|educational-content|all}
POST /medscribeai/api/v1/medical-report/chat/{id}/send       ask the LLM about the report
GET  /medscribeai/api/v1/medical-report/{id}/translate/{resultType}?lang=bn   show a result in Bangla
```

The single-file UI at `medscribe-ai/src/main/resources/static/index.html` drives all of this.

## Modules (Gradle, `settings.gradle`)

| Module | Role | Depends on |
|---|---|---|
| `medscribe-ai` | The Spring Boot WebFlux application: routers, handlers, orchestration, persistence, UI | all libraries below |
| `baymax` | Medioo MVP (public name per DR-15; module codename Baymax, DR-2): `com.elioo.baymax`, same hexagonal layout, routes under `/api/v1/baymax/**`, own Flyway instance on schema `baymax` (`db/baymax/migration`). Per-call AI cost log `baymax.ai_call_log` written by `MeteredLlmClient`/`MeteredVisionOcr`; weekly CSV at `/api/v1/baymax/admin/metrics/weekly` (`X-Baymax-Admin-Token`). Object storage: `StoragePort`/`S3StorageAdapter` (S3-compatible, `baymax.storage.*`), ledger `baymax.stored_object`, MinIO locally. Extraction (BMX-2): `POST/GET /api/v1/baymax/documents`, tables `document`/`observation`/`medication_event`/`follow_up` (V5), one Vision call per page plus one structured LLM call, confidence gate and strong-model escalation, every item proved by a stored crop. Loaded into the app via auto-configuration only when `baymax.enabled=true` (default off; `BAYMAX_ENABLED`) | elioo-llm, elioo-gcp-vision |
| `elioo-llm` | Provider-neutral LLM layer: `LlmClient` interface, clinical prompt templates (`HealthInsightService`), Anthropic and OpenAI-compatible (Groq/OpenAI) clients, selection by `llm.provider` | Anthropic Java SDK, Spring WebClient |
| `elioo-aws-common` | AWS credentials/region auto-configuration | AWS SDK v2 |
| `elioo-aws-comprehend-medical` | Entity detection, ICD-10, RxNorm, SNOMED CT | aws-common |
| `elioo-aws-textract` | OCR via Textract (dormant; `ocr.provider=gcp` is active) | aws-common |
| `elioo-aws-bedrock` | Bedrock as an optional `LlmClient` (`llm.provider=bedrock`, off by default) | aws-common, elioo-llm |
| `elioo-gcp-common` | GCP credentials (file, inline JSON, or ADC; placeholder if none so the app still boots) | Google auth |
| `elioo-gcp-vision` | OCR via Cloud Vision (Bangla + English hints) | gcp-common |
| `elioo-gcp-translate` | Translation v3 (Bangla to English for AWS; English to Bangla for display) | gcp-common |
| `elioo-aws-spring-boot-starter`, `elioo-gcp-spring-boot-starter` | Aggregators, no code | |

## Hexagonal layout inside `medscribe-ai`

`com.elioo.healthcare.medicalreport`

- `domain` — enums (`ProcessingStage`, `ProcessingStatus`, `Severity`, ...), `TestResult`, `ProcessingContext`, request/response models.
- `application.port.in` — use cases: orchestration, query, chat, content translation.
- `application.port.out` — `OcrPort`, `TranslationPort`, `MedicalClassificationPort`, `ClinicalInsightPort`, `ContentTranslationPort`, `MedicalReportPersistencePort`.
- `application.service` — `MedicalReportOrchestrationService` (the pipeline), `MedicalReportQueryService`, `MedicalReportChatService`, `ContentTranslationService`.
- `adapter.in` — WebFlux functional routers and handlers.
- `adapter.out.gcp` — `VisionAdapter` (OcrPort when `ocr.provider=gcp`), `TranslationAdapter`, `ContentTranslationAdapter`.
- `adapter.out.aws` — `TextractAdapter` (OcrPort when `ocr.provider=aws`), `ComprehendMedicalAdapter`.
- `adapter.out.llm` — `LlmInsightAdapter` (ClinicalInsightPort on top of `elioo-llm`).
- `adapter.out.persistence` — R2DBC entities/repositories, `MedicalReportPersistenceAdapter`, `ResultDataDeserializer` (tolerant read-back of stored JSON).

`com.elioo.healthcare.llm` (inside the app) exposes the raw LLM endpoints under `/medscribeai/api/llm/*`;
`com.elioo.healthcare.aws.*` and `gcp.*` expose raw passthrough endpoints for each cloud service (debugging aids).

## Data

PostgreSQL (Supabase in production, Docker locally), schema `medscribe`, migrated by Flyway on startup
(`medscribe-ai/src/main/resources/db/migration/V*.sql`):

- `medical_report_process` — one row per report (status, counters, request JSON).
- `medical_report_process_stage` — one row per persisted stage (`ProcessingStage.persistedStages()`, 8 per report).
- `medical_report_result` — one row per result type, JSONB payload plus cached translation columns.
- `medical_report_error` — every recorded error with stage and retryability.

Chat history is in memory only (lost on restart); see open work.

## Configuration

Everything comes from the environment; see `medscribe.env.example`. The important switches:

| Variable | Effect |
|---|---|
| `LLM_PROVIDER` | `groq` (default, dev/test) / `anthropic` (demos) / `openai` / `bedrock` |
| `GROQ_API_KEY`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` | keys for the chosen provider; startup fails fast if the chosen one is missing |
| `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, `PGSCHEMA` | database (R2DBC at runtime, JDBC for Flyway) |
| `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | Comprehend Medical (IAM user with Comprehend-only policy) |
| `GCP_PROJECT_ID`, `GCP_CREDENTIALS_PATH` | Vision + Translate; mounted into the container by `deploy.sh` |
| `SPRING_PROFILES_ACTIVE` | `local2,aws,gcp` on a laptop; `prod,aws,gcp` on the server |

## Deployment

Stack: Spring Boot 3.4.2 with Spring Framework overridden to 6.2.19 (spring-framework#34178, a multipart hang on
Boot's own 6.2.2), Java 21; see the comment in the root `build.gradle`. The rest of the BOM is Boot 3.4.2's.

Push to `main` -> `.github/workflows/ci-cd.yml` -> Gradle build and tests -> two Docker images to GHCR, both
tagged with the same sha (`medscribe-ai`, and `medioo-web` from W1b) -> SSH to the EC2 host -> `deploy.sh` swaps
the `medscribe-ai` container and waits for `/actuator/health`, then swaps `medioo-web` and waits for `/app/healthz`
to answer for that sha (a failed web swap puts the previous one back). A sha with no web image (any before W1b)
deploys Java only. Details: [RUNBOOK.md](RUNBOOK.md).
Caddy (from the co-located n8n stack) terminates HTTPS for `medioo.eliooo.org` (301 from `baymax.eliooo.org`) and proxies to the container
over the `n8n_edge` Docker network.

## Known limits and open work

- Groq free tier: 8,000 tokens/minute; a report uses about 6,000, so chat immediately after can wait on a retry.
- RxNorm knows US drug names only; Bangladeshi brands need a local brand-to-generic map (planned).
- The structured "test rows" extractor targets lab reports; prescriptions yield raw text only (LLM prescription parser planned).
- Doctor letterhead text leaks into detected patient conditions (letterhead stripping planned).
- No authentication; no stalled-job recovery or cancellation; chat history not persisted; Textract path is behind Vision on Bangla.
- Mixed BOM: Framework 6.2.19 runs on Boot 3.4.2's Reactor (reactor-core 3.7.2, reactor-netty 1.2.2), Netty
  4.1.117.Final and Micrometer 1.14.3, though 6.2.19 was built against reactor-core 3.7.19. Linkage-checked, not
  tested upstream. Removed by the next Boot upgrade, which also deletes the `spring-framework.version` override once
  Boot's own BOM brings 6.2.19 or later (`ShippedSpringFrameworkVersionTest` fails if Boot moves while it is set).
- Boot 3.4.x is past OSS support, and its BOM keeps Netty 4.1.117.Final and reactor-netty 1.2.2 in production
  behind Caddy, which predate published fixes (Netty's HTTP/1.1 chunk-extension request smuggling, CVE-2025-58056,
  fixed in 4.1.125; reactor-netty's redirect credential leak, CVE-2025-22227). A known, tracked choice (#115): move to a
  supported Boot line, or as an interim override `netty.version` and `reactor-bom.version` with their own replay.
