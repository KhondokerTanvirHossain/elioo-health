# CLAUDE.md

Guide for working on this repository with Claude Code. Keep it short and true; when the code and this
file disagree, the code wins and this file should be fixed.

## What this is

**MedScribe AI** (repo `elioo-health`): a Spring Boot 3.4 / Java 21 WebFlux service that turns a photo of a
medical document (Bangla and/or English lab reports and prescriptions) into structured results, ICD-10 /
RxNorm / SNOMED codes, an LLM-written clinical summary, and a chat about the report. Production runs at
https://baymax.eliooo.org. Full architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Status: PoC closed 2026-09-12; MVP ("Baymax", internal codename) in development.

**Read [docs/BAYMAX.md](docs/BAYMAX.md) and [docs/DECISIONS.md](docs/DECISIONS.md) before any Baymax work.**
Product decisions come from the Baymax PO project via BMX tickets; do not change scope, safety rules or pricing
on your own — propose in the build report. The product definition, MVP in/out list and pilot metrics are in
[docs/PRODUCT.md](docs/PRODUCT.md). Per DR-2, Baymax is a new `baymax` module beside `medscribe-ai`; MedScribe
(pipeline, endpoints, UI, tables) stays as-is and demoable. Baymax does not use Comprehend Medical, Translate or
codification, and targets one LLM extraction call per document at ≤ $0.15. Open MedScribe technical work is
listed at the end of ARCHITECTURE.md.

## Build, run, test

```bash
docker compose up -d                          # Postgres 17 on localhost:5433 (medscribe/medscribe)
./gradlew :medscribe-ai:bootRun               # http://localhost:8086  (UI, /actuator/health)
./gradlew build                               # all 10 modules; unit tests + context test (needs the Docker Postgres)
RUN_AWS_INTEGRATION_TESTS=true ./gradlew test # + real AWS Comprehend/Textract tests
RUN_LLM_INTEGRATION_TESTS=true LLM_PROVIDER=groq ./gradlew :elioo-llm:test --tests '*LlmClientIntegrationTest'
```

Secrets never go in the repo. Locally they live in `.env.local` (git-ignored); load with
`set -a; source .env.local; set +a` before `bootRun`. Template: `medscribe.env.example`.
`.env.local` points at the Docker Postgres only. Production/Supabase credentials live in `.env.supabase`
(git-ignored, mode 600) and are sourced explicitly and rarely, e.g. for a read-only check; never source it
by default, and never run `bootRun` against it.
Without a GCP key the app boots with placeholder credentials and OCR/translation fail at call time (by design).
Without a valid key for the chosen `LLM_PROVIDER` the app refuses to start (also by design).

Flyway applies `medscribe-ai/src/main/resources/db/migration/V*.sql` at startup over JDBC; the app itself
uses R2DBC. Schema is `medscribe`. New migrations: next `V<n>__description.sql`, never edit an applied one.

## Layout

- `medscribe-ai/` — the application (hexagonal: `domain`, `application.port.{in,out}`, `application.service`, `adapter.{in,out}`).
- `baymax/` — the Baymax MVP module (`com.elioo.baymax`, same hexagonal layout). Loaded into the medscribe-ai deployable only when `baymax.enabled=true`; own schema `baymax` and Flyway history under `src/main/resources/db/baymax/migration` (never `db/migration`, which MedScribe's Flyway scans).
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
- Branches: delete the feature branch (remote and local) as soon as its PR is squash-merged. **Never trust a
  merge report — verify the commit is on `origin/main` before deleting a branch.** `gh pr merge` can print
  what reads as success while the PR sits BEHIND and nothing merged; deleting on that reading loses the work.
  Check `git log origin/main --oneline | grep <sha-or-PR-number>` first, then delete.
- An invariant asserted on a proxy is not asserted. Anything non-inspectable — an image, a PDF, a voice note — is
  checked against its actual content, never its presence. "Every value has a crop" was true for 137 crops of which
  116 showed the neighbouring words (DR-12); the test saw a file that existed. This is the fifth flattering failure
  in this repo and the only one that would have reached a family.
- A substring assertion over text containing random ids is not an assertion — match the field, not the document.
  `doesNotContain("999")` on a CSV full of UUIDs passed for the wrong reason for weeks and then failed for the
  wrong reason on a docs-only PR; `doesNotContain(",999,")` says what was meant. Same class as the crop bug and
  the false-positive guard: green for a reason other than the one you think.
- An ignore rule that matches a source directory name will silently drop source from a commit that builds
  locally. `log/` in `.gitignore` swallowed `adapter/out/log/`; the build was green here and CI could not find
  the bean. Before pushing a PR that adds directories, `git status --ignored --short -- <module>/src | grep '^!!'`
  must print nothing — this check is standard now. Sixth flattering failure.
- Regression guards: a test written to catch a specific bug is replayed against the pre-fix source before it counts as done. A guard that passes on the broken code is worse than none — it was written once here, anchored on the wrong text, extracted an empty method body, and went green over the very bug it existed to catch.
- Staging: never `git add -A` or `git add .` in this repo — stage explicit paths. It has caused three
  incidents: an unrelated runbook swept into a feature branch, and a `.env.local` backup holding live API
  keys staged for commit.
- Any file matching `.env*`, backups included, is git-ignored and never staged. After creating one, verify
  with `git check-ignore <file>` — and check the file itself, not a glob, which `-q` treats differently.
- Model prices come from the provider's API (Groq `/v1/models` `pricing` field, the equivalent elsewhere),
  never a docs or marketing page: the pricing page carried no Qwen rates at all while the API did. Every
  entry in the price table records its source and the date fetched.
- Never point a destructive command (`mv`, `rm`, a redirect that overwrites) at a glob that could match
  production credentials. Copy, with an exact filename.
- Branching (since 2026-09-12): `main` is protected; the "Build and test" check must pass and direct pushes are
  rejected, admins included. Work on a branch (`feat/...`, `fix/...`), open a PR with
  `env -u GITHUB_TOKEN gh pr create`, merge when green. Every merge to `main` deploys to production.

## Environment facts (2026-09)

- Server: EC2 `ec2-13-205-14-249.ap-south-1.compute.amazonaws.com`, user `ec2-user`, shared with an n8n stack; Caddy there does HTTPS for `baymax.eliooo.org`.
- Runtime config on the server: `/home/ec2-user/medscribe.env` (mode 600). Redeploy manually: `IMAGE_TAG=<git sha> bash ~/deploy.sh`. Back it up before editing and list every change in the build report; which `BAYMAX_*` lines must be blank vs set, and the post-deploy self-test, are in [docs/RUNBOOK.md](docs/RUNBOOK.md).
- Database: Supabase project `niramoy-rx` (ap-south-1), role/schema `medscribe`, via the session pooler on port 5432.
- LLM: `groq` by default (`openai/gpt-oss-120b`, free tier 8k tokens/min); switch to `anthropic` (`claude-opus-5`) for demos by editing the server env and redeploying.
- AWS: IAM user with Comprehend Medical permissions only; Textract and Bedrock beans are disabled.
- GitHub CLI on the dev machine: use `env -u GITHUB_TOKEN gh ...` (the shell's token is a limited PAT).
