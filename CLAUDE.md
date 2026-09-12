# CLAUDE.md

Guide for working on this repository with Claude Code. Keep it short and true; when the code and this
file disagree, the code wins and this file should be fixed.

## What this is

**MedScribe AI** (repo `elioo-health`): a Spring Boot 3.4 / Java 21 WebFlux service that turns a photo of a
medical document (Bangla and/or English lab reports and prescriptions) into structured results, ICD-10 /
RxNorm / SNOMED codes, an LLM-written clinical summary, and a chat about the report. Production runs at
https://baymax.eliooo.org. Full architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Status: PoC complete, MVP work starting (2026-09). Open work is listed at the end of ARCHITECTURE.md.

## Build, run, test

```bash
docker compose up -d                          # Postgres 17 on localhost:5433 (medscribe/medscribe)
./gradlew :medscribe-ai:bootRun               # http://localhost:8086  (UI, /actuator/health)
./gradlew build                               # all 9 modules, unit tests (no cloud calls)
RUN_AWS_INTEGRATION_TESTS=true ./gradlew test # + real AWS Comprehend/Textract tests
RUN_LLM_INTEGRATION_TESTS=true LLM_PROVIDER=groq ./gradlew :elioo-llm:test --tests '*LlmClientIntegrationTest'
```

Secrets never go in the repo. Locally they live in `.env.local` (git-ignored); load with
`set -a; source .env.local; set +a` before `bootRun`. Template: `medscribe.env.example`.
Without a GCP key the app boots with placeholder credentials and OCR/translation fail at call time (by design).
Without a valid key for the chosen `LLM_PROVIDER` the app refuses to start (also by design).

Flyway applies `medscribe-ai/src/main/resources/db/migration/V*.sql` at startup over JDBC; the app itself
uses R2DBC. Schema is `medscribe`. New migrations: next `V<n>__description.sql`, never edit an applied one.

## Layout

- `medscribe-ai/` — the application (hexagonal: `domain`, `application.port.{in,out}`, `application.service`, `adapter.{in,out}`).
- `elioo-llm/` — provider-neutral LLM layer (`LlmClient`, prompt templates, Anthropic + OpenAI-compatible clients, `LlmAutoConfiguration`).
- `elioo-aws-*`, `elioo-gcp-*` — thin cloud SDK wrappers with Spring auto-configuration; no business logic.
- `docs/superpowers/{specs,plans}` — design specs and the plans that executed them; `docs/archive` — PoC history, not current.
- `.github/workflows/ci-cd.yml` — build on every push; on `main` also image → GHCR → SSH deploy via `deploy.sh`.

Key files when changing behaviour:
- Pipeline: `medicalreport/application/service/MedicalReportOrchestrationService.java` (large; has a mocked-port unit test to extend).
- LLM prompts: `elioo-llm/.../health/prompt/DefaultPromptTemplateEngine.java`; insight mapping: `medicalreport/adapter/out/llm/LlmInsightAdapter.java`.
- Stored-result read-back: `adapter/out/persistence/ResultDataDeserializer.java` (tolerant: unknown enum values map to `OTHER`/`UNKNOWN`, shape mismatches fall back to generic JSON).
- Routes: `adapter/in/router/*Router.java` (WebFlux functional routing; literal paths must be registered before `{variable}` paths).

## Conventions

- Reactive end to end: `Mono`/`Flux`, never `.block()` outside tests. Constructor injection via Lombok `@RequiredArgsConstructor`.
- Provider selection is by property, not by code: `ocr.provider` (gcp|aws), `llm.provider` (groq|anthropic|openai|bedrock).
- Logs must not contain patient text: prompt/response bodies and OCR text at DEBUG only; sizes, ids, durations at INFO.
- Cloud adapters map SDK types to domain records at the boundary; nothing above `adapter.out` imports an AWS/GCP/Anthropic type.
- Tests: TDD for behaviour changes. Unit tests mock ports; tests that call real clouds are gated by the env vars above.
- Commits: conventional (`feat(scope): ...`, `fix(scope): ...`).
- Branching (since 2026-09-12): `main` is protected; the "Build and test" check must pass and direct pushes are
  rejected, admins included. Work on a branch (`feat/...`, `fix/...`), open a PR with
  `env -u GITHUB_TOKEN gh pr create`, merge when green. Every merge to `main` deploys to production.

## Environment facts (2026-09)

- Server: EC2 `ec2-13-205-14-249.ap-south-1.compute.amazonaws.com`, user `ec2-user`, shared with an n8n stack; Caddy there does HTTPS for `baymax.eliooo.org`.
- Runtime config on the server: `/home/ec2-user/medscribe.env` (mode 600). Redeploy manually: `IMAGE_TAG=<git sha> bash ~/deploy.sh`.
- Database: Supabase project `niramoy-rx` (ap-south-1), role/schema `medscribe`, via the session pooler on port 5432.
- LLM: `groq` by default (`openai/gpt-oss-120b`, free tier 8k tokens/min); switch to `anthropic` (`claude-opus-5`) for demos by editing the server env and redeploying.
- AWS: IAM user with Comprehend Medical permissions only; Textract and Bedrock beans are disabled.
- GitHub CLI on the dev machine: use `env -u GITHUB_TOKEN gh ...` (the shell's token is a limited PAT).
