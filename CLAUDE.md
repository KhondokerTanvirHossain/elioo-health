# CLAUDE.md

Guide for working on this repository with Claude Code. Keep it short and true; when the code and this
file disagree, the code wins and this file should be fixed.

## What this is

**MedScribe AI** (repo `elioo-health`): a Spring Boot 3.4 / Java 21 WebFlux service that turns a photo of a
medical document (Bangla and/or English lab reports and prescriptions) into structured results, ICD-10 /
RxNorm / SNOMED codes, an LLM-written clinical summary, and a chat about the report. Production runs at
https://medioo.eliooo.org (the old name baymax.eliooo.org redirects permanently, preserving the path). Full architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Status: PoC closed 2026-09-12; MVP in development — public name **Medioo** (DR-15), "Baymax" is the internal codename kept in module, package, schema, config and env names.

**Read [docs/MEDIOO.md](docs/MEDIOO.md) and [docs/DECISIONS.md](docs/DECISIONS.md) before any Baymax/Medioo work.**
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
- **All ten flattering failures share one shape: a check that could not fail for the reason it existed.** The
  crop test saw a file that existed; the CSV guard matched a random id; the nudge mock was a `HashSet` where
  Postgres raises; the deletion test asserted six tables by hand and skipped `document`, the only one that blocked
  the delete — and never inserted a `document` row at all, so the table it forgot was also empty. **For any
  operation spanning multiple tables or systems, the assertion is derived from the schema or the system itself,
  never from a hand-written list of what the author remembered.** `FamilyDeletionAcceptanceTest.tablesStillHolding`
  asks `information_schema` which tables carry a family or patient id and sweeps every one, so a table added in a
  later migration cannot be silently missed. A derived check needs its own guard against emptiness: if the query
  returns no tables the sweep passes vacuously, which is the same defect one level up.
- **A test that works around a constraint, or whose name encodes surprising behaviour, is evidence of a defect
  rather than of intent.** Distinct from the ten above: those were checks that could not fail, this is a check
  that described the bug and called it correct. `PostgresHealthRecordAdapterTest` deleted `stored_object` and
  `document` by hand before calling the port, commenting "documents reference the family" — it knew the
  constraint and stepped around it. `FamilyAccountServiceTest.deleteFamilyRemovesImagesThenRows...` named the
  dangerous ordering and verified it. Both documented delete-on-request being broken and held it in place.
  **When a test has to step around something to pass, the thing it stepped around is the finding.**
- **A fix scoped to the path under discussion, rather than to the invariant it protects, leaves the same defect
  live on every other path.** When a rule is agreed, find every writer of the field it governs and assert the
  rule once, centrally, where all of them meet it. V13 gave `sent_at` the meaning "the provider accepted it" and
  was wired into `ReviewGateService.approve` — the path being discussed. Both release paths kept stamping it at
  save time and discarding the delivery outcome, so the first real WhatsApp send produced a row saying
  `delivery_status` NULL with no `wamid` for a message that had demonstrably gone out. The rule now lives in a
  CHECK constraint (V14): no code path, present or future, can store a row claiming a delivery it never got.
- The retake gate has a replay harness: `scripts/replay-extraction-gate.py` runs every document's **stored**
  extraction through the current gate — no model calls, no cost — and prints which decisions change. **Rerun it
  whenever the gate changes** (thresholds, the expected-sections table, or the gate code) against every document
  in production. It is how the 2-of-6 false-retake rate was found, and every retake rate measured before
  2026-09-21 carries that artefact.
- **A limit is asserted on the thing actually sent, after every insertion — never on an intermediate.** The
  600-character cap ran in `MessageSafetyCheck` on the model's phrased text, and the medicine block, the
  longest part, was appended afterwards: the cap never covered it at all. Thirteenth instance of a check that
  does not cover what it claims to. Same shape whenever a value is assembled in stages — assert at the end.
- **For any rule with two directions (protect X, still catch Y), write both tests before the implementation.**
  A one-directional fix passes the safe case and hides the dangerous one, and the passing half makes it look
  finished. The section gate had to stop rejecting prescriptions for an empty `values[]` *and* keep rejecting a
  lab report that read nothing; the first implementation's `typeOf()` read an unset column, so every document
  became `"other"` — prescriptions passed and a blurry lab report would have sailed through. Only the
  second test caught it, and only because it existed before the code.
- **A scoring harness manufactures findings as readily as it hides them.** Batch 2's unit column read 65/74
  until eight of the nine "misses" turned out to be the scorer failing to Unicode-fold MICRO SIGN (U+00B5)
  against GREEK SMALL LETTER MU (U+03BC) — visually identical, semantically identical, different code points.
  The real number was 73/74. A harness result is evidence about the harness until a mismatch has been looked
  at by value; never open an investigation into a defect a tool reported without first confirming the tool.
- **A red replay must fail ON the defect, not merely fail.** Read the failure message and confirm it names the
  bug: `expected: DEFERRED but was: DROPPED`, `duplicate key value violates unique constraint`. A guard whose
  replay died on a `NullPointerException` in its own Mockito matcher was red, looked like a successful replay and
  proved nothing — the defect was never reached. Assert the failure mode, not the failure. Ninth instance of the
  family, and the second inside the replay step that exists to catch the other eight.
- Any path with a database constraint is exercised against a real database (Testcontainers), never only a mocked
  port. A mock that cannot fail the way production fails is not a test of that path. `NudgePort` was mocked with a
  `HashSet` ledger where a duplicate `add` is a silent no-op; Postgres raises a unique-constraint violation on the
  same operation, so defer → consume → re-insert was green in the unit test and 500'd on production with the
  deferral already consumed and the family never told about their appointment (DR-22). Eighth flattering failure,
  and the second where the guard itself was green for the wrong reason.
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

- Server: EC2 `ec2-13-205-14-249.ap-south-1.compute.amazonaws.com`, user `ec2-user`, shared with an n8n stack; Caddy there does HTTPS for `medioo.eliooo.org` and redirects `baymax.eliooo.org` to it, path preserved (Caddyfile at `/home/ec2-user/n8n/Caddyfile`, reload with `docker exec n8n-caddy-1 caddy reload --config /etc/caddy/Caddyfile`). **Both hostnames are separate A records pointing at the EC2 IP — not a CNAME — so moving the box means changing both in Hostinger.**
- Runtime config on the server: `/home/ec2-user/medscribe.env` (mode 600). Redeploy manually: `IMAGE_TAG=<git sha> bash ~/deploy.sh`. Back it up before editing and list every change in the build report; which `BAYMAX_*` lines must be blank vs set, and the post-deploy self-test, are in [docs/RUNBOOK.md](docs/RUNBOOK.md).
- Database: Supabase project `niramoy-rx` (ap-south-1), role/schema `medscribe`, via the session pooler on port 5432.
- LLM: `groq` by default (`openai/gpt-oss-120b`, free tier 8k tokens/min); switch to `anthropic` (`claude-opus-5`) for demos by editing the server env and redeploying.
- AWS: IAM user with Comprehend Medical permissions only; Textract and Bedrock beans are disabled.
- GitHub CLI on the dev machine: use `env -u GITHUB_TOKEN gh ...` (the shell's token is a limited PAT).
