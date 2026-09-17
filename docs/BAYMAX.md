# BAYMAX — engineering brief for Claude Code

Read this and [DECISIONS.md](DECISIONS.md) before any Baymax work. Product decisions come from the Baymax PO
project via BMX tickets; do not change scope, safety rules or pricing on your own — propose in the build report.

## What this is
MedScribe is becoming Baymax: a WhatsApp-first health-memory assistant for Bangladeshi families. A family owner (one WhatsApp number) sends photos of reports/prescriptions for one or more patient profiles (parents). The system extracts, explains in Bangla (text + voice), assigns urgency, files to a per-patient timeline, and proactively nudges the family. Full product doc: [PRODUCT.md](PRODUCT.md).

## Division of labour
- PO project (claude.ai) → writes BMX tickets with acceptance criteria; owns copy, safety rules, scope, pricing.
- Claude Code (this repo) → owns architecture, code, tests, deployment; returns a build report per ticket.
- Tanvir → final say on conflicts and the "needs Tanvir" list.

## Hard constraints (do not violate; propose instead)
1. Outbound messages never advise starting/stopping/changing a medicine or dose.
2. Urgency is never downgraded by any post-processing; when uncertain, escalate.
3. No numeric value is sent without its source crop; on low confidence, send a retake prompt, not an answer.
4. Every outbound message passes the review-gate stage (config-driven: global / per urgency / per patient; default off).
5. Real per-document cost, model, confidence and review outcome are logged. Remove the hardcoded $0.072.
6. LLM providers used only under zero-retention / no-training terms. *In force again in full: DR-6 relaxed this for a Groq evaluation run, and DR-9 dropped Groq, so the relaxation has no subject. Anthropic's API terms do not train on API inputs; confirm the retention posture before pilot week 0.*
7. Nothing in code, copy or metadata claims data is stored in Bangladesh.

## Architecture decisions already made
- Baymax is a new module (`baymax`) beside `medscribe-ai` in the same repo; the MedScribe pipeline, endpoints, UI and DB tables are left as-is. Baymax reuses `elioo-llm`, `elioo-gcp-vision`, core and CI/CD (DR-2).
- AWS Comprehend Medical (DetectEntities, SNOMED, ICD-10, RxNorm) and GCP Translate are not used in the Baymax pipeline. They are not removed from MedScribe (DR-2). Codification is out of MVP.
- Single structured LLM extraction call per document. Output schema (minimum): document_type, patient_hint, document_date, facility, values[] {name, value, unit, ref_low, ref_high, flag}, medicines[] {name, dose_text, frequency_text, duration_text}, follow_up[] {instruction, due_date}, free_text_summary, confidence {overall, per_section}.
- Extraction model (DR-10, supersedes DR-9 default; DR-9 dropped Groq): **single-tier `claude-sonnet-5` for printed documents**. Measured on the ten-document printed corpus: haiku 60.0% at $0.0118/doc with two retakes, sonnet 86.2% at $0.0253 with none; haiku's confidence sat at 0.85–0.95 on every document while its gap to sonnet ranged 0 to +0.60, so no escalation threshold recovered the gap for less than sonnet alone. `baymax.extract.vision-model` defaults to sonnet and `strong-model-threshold` to 0.0 (confidence escalation off; a `critical` flag still escalates). Haiku stays configured and unused: each tier is its own Anthropic client built from its own property (`ExtractionModelConfiguration`, wrapped in `BaymaxModelClient` so neither can displace MedScribe's `LlmClient`), so `BAYMAX_EXTRACT_VISION_MODEL=claude-haiku-4-5` plus a threshold brings the two-tier path back. Re-measure both on the handwritten batch before freezing. Groq stays in `elioo-llm` and config, unused by Baymax: its free tier caps output at 1,000 tokens/minute against the ~1,810 one document declares.
- Strong tier is `claude-sonnet-5` (DR-9; opus was ruled out on cost at $0.073–0.113/doc against a $0.15 ceiling). Extraction returns `confidence.overall`, which the metering wrapper's extractor reads into `ai_call_log.confidence`.
- Vision OCR called once per image (MedScribe currently calls it twice — Baymax must not repeat that).
- Extraction (BMX-2) lives in `com.elioo.baymax.extraction`: intake renders PDFs and non-JPEG images to JPEG pages (`PageRenderer`), Vision runs once per page keeping word geometry (`PageOcr`), one structured call goes out through `MeteredLlmClient` with the OCR text and, on a vision model, the page images. The reply is schema-validated (`extraction-schema.json`) with a single repair retry; a second failure fails the document. Below `baymax.extract.min-confidence-overall` or `min-confidence-section` the document is NEEDS_RETAKE and nothing but the document row is written. Low confidence or a `critical` flag escalates to the strong model and the more confident answer wins; `document.model_final` records which. Every item's `source_span` is mapped through Vision geometry to a bounding box and cut into `crop-{item_id}.jpg` (`CropCutter`); an item with no resolvable crop is dropped, never surfaced as a value, and `crop_key` is NOT NULL in the schema so the rule holds in the data. **Dropped items are counted, not silent**: per-section counts (`unverified_values` / `_medicines` / `_follow_up`, V6) go on the document, appear as `unverified` in the status response when non-zero, and as `unverified_items` in the weekly export. A value we could not locate is still a value the family sent, so it must show as "we could not read part of this" rather than as a shorter answer.
- The cheap tier is `claude-haiku-4-5` (`baymax.extract.vision-model`, DR-9), built as its own client in `ExtractionModelConfiguration`; a `claude-*` id builds an Anthropic client and anything else an OpenAI-compatible one, so a Groq id still works if the account is ever upgraded. Haiku 4.5 rejects both `thinking: adaptive` and `output_config.effort` with a 400, so `AnthropicLlmClient` sends neither to it — extraction is transcription and does not need them. `LlmRequest` gained an optional `images[]` and `LlmClient.supportsImages()`, both additive; MedScribe is untouched and still runs Groq by default.
- Images to object storage (Supabase Storage or S3), not base64 in Postgres. Done in BMX-3: `StoragePort` + `S3StorageAdapter` (`com.elioo.baymax.storage`, S3 API, private bucket, SSE header configurable), keys `{family_id}/{patient_id}/{document_id}/page-{n}.jpg` and `.../crop-{item_id}.jpg` (UUIDs only, never a phone number), ledger table `baymax.stored_object` (keys + sizes, no bytea), signed GET URLs with a 15-minute TTL, delete-by-prefix at document/patient/family level for the 24h delete path. JPEG only: render PDFs and other formats to JPEG before `storePage`/`storeCrop`. Production is AWS S3 `elioo-baymax-prod` in ap-south-1 through the EC2 instance role (`baymax.storage.credentials=instance-role`, DR-5); MinIO is local-dev only (docker-compose, bucket `baymax`). IAM policy: `docs/aws/baymax-s3-policy.json`. Self-test from inside the container: `GET /api/v1/baymax/admin/storage/selftest` (admin token).
- Family accounts (BMX-4): `family_account` / `patient_profile` / `share_member` in schema `baymax`, reached only through `HealthRecordPort` (`com.elioo.baymax.healthrecord`, DR-1; the Postgres adapter is the only implementation, and an ArchUnit test forbids services touching repositories). Consent timestamps are set server-side and immutable (no update method, plus a DB trigger). Phone numbers exist only in those tables: keys, logs and `ai_call_log` carry UUIDs. Free tier: `baymax.free.max-patients` (1) and `baymax.free.max-docs-per-month` (3, UTC calendar month) → HTTP 402 `{reason: free_tier_patients | free_tier_documents}` via `FreeTierUseCase`; BMX-2 calls `checkCanUploadDocument` at intake. Delete-on-request is hard and inside the request: images (objects, then ledger), then rows; `ai_call_log` rows keep their numbers but lose the `document_id`. Endpoints under `/api/v1/baymax/families|patients`, behind the admin token until OTP (BMX-5).
- Records live in the app's Postgres with FHIR-aligned naming behind one `HealthRecordPort`; no Medplum or other external record system in MVP (DR-1).
- Baymax owns Postgres schema `baymax` with its own Flyway history. Migrations live under `baymax/src/main/resources/db/baymax/migration`, never under `db/migration`: Flyway scans locations recursively and MedScribe's instance would pick them up and collide on version numbers (BMX-0).
- The Baymax Flyway instance is built and run inside `BaymaxSchemaMigrator` and is never exposed as a `Flyway` bean. Spring Boot's Flyway auto-configuration is `@ConditionalOnMissingBean(Flyway)`, so a second bean would silently disable the MedScribe migrations (BMX-0).
- Every LLM call from Baymax goes through `MeteredLlmClient` and every OCR call through `MeteredVisionOcr` (`com.elioo.baymax.aicall`); each successful call writes one `baymax.ai_call_log` row (purpose, provider, model, tokens, cost from the `baymax.llm.prices` / `baymax.vision.cost-per-image` config, latency, confidence). Unknown model → `cost_usd` NULL + WARN, never a guess. No prompt, reply or OCR text in the log. Weekly CSV: `GET /api/v1/baymax/admin/metrics/weekly` behind the `X-Baymax-Admin-Token` header (BMX-1). BMX-2 (extraction) calls models only through these wrappers and adds `ai_call_log.status` (ok|failed) in V5 (V3 = stored_object, V4 = family tables): failed calls write a row with zero tokens, NULL cost and the actual latency.
- Every Baymax entity uses a schema-qualified table name (`@Table("baymax.document")` etc.). The shared R2DBC connection keeps `medscribe` as its search path (BMX-0 review).
- Bangla TTS for voice notes (provider TBD by Tanvir).
- Build order: web first (family accounts, patient profiles, timeline, review-gate UI, cost logging), then WhatsApp channel.

## Data model (target, adjust as needed)
- family_account (whatsapp_number PK, owner_name, plan, consent_at)
- patient_profile (id, family_id, name, age, sex, chronic_flags[], proxy_consent_at)
- share_member (patient_id, whatsapp_number, added_at, notified_at)
- document (id, patient_id, storage_key, type, doc_date, extraction_json, confidence, cost_usd, model, created_at)
- observation (id, patient_id, document_id, name, value, unit, ref_low, ref_high, flag, observed_at) — for trend detection
- medication_event (id, patient_id, document_id, name, action: started/continued/stopped/changed, at)
- follow_up (id, patient_id, document_id, instruction, due_date, status)
- outbound_message (id, family_id, patient_id, kind, urgency, body, voice_key, gate_status, sent_at, reply_at, feedback)
- nudge_rule (kind, params_json, enabled)
- config (review_gate, thresholds, free_tier_caps)

## Proactive engine (v1 rules)
follow_up_due (T-2 days), medicine_changed (on new prescription diff), trend (3 consecutive readings wrong direction on configured markers), silence (chronic patient, no document ~60 days), pre_visit_summary (auto, T-1 day of follow_up).

*Corpus finding (batch 1, ten printed prescriptions, 2026-09-17):* only 1 of 10 prescriptions states a follow-up
timing, so `follow_up_due` and `pre_visit_summary` fire on ~10% of prescriptions on this corpus. **Proposed, not
built — belongs to BMX-8:** a rule named `course_ending` that derives an end date from `medicines[].duration_text`
+ `document_date` and nudges at T-1; duration text is present on most prescriptions where follow-up timing is not.

## What the evaluation corpus does and does not measure
Batch 1 (docs/testset, ten documents) is **prescriptions only**. The `values[]` score is therefore NOT a lab-report
measurement: the values on these pages are blood-pressure readings written on prescriptions. Reference ranges,
flags and trend detection are **unmeasured on real documents** until a lab-report batch exists. `follow_up` is
excluded from the headline accuracy until a corpus with follow-ups exists (1 of 10 here). Corpus expansion is
deferred — batch 2 (~10 printed lab reports) and batch 3 (~10 handwritten prescriptions) both gate pilot week 0,
not the build (PO, 2026-09-17).

## Metrics to instrument from day one
Per document: cost, model, confidence, review outcome, correction count. Cost is logged at list price (e.g. Vision at $0.0015/image even inside the free tier): the export measures marginal cost at scale, not this month's bill. Per family: documents/month, nudges sent/answered, days since last document, tier. Weekly export for the pilot log.

## Build report format
```
BMX-<n> report
Done / Deviations / Open questions / Cost & metrics / Next
```

## Pending from Tanvir (do not guess)
Public brand name · **Batch 2: ~10 printed lab reports — blocking pilot week 0** · **Batch 3: ~10 handwritten prescriptions — blocking pilot week 0** · Label verification pass on batch 1 (43 numeral fields, docs 2/6/8, doc 8 forms, follow-up count) · **Confirm Anthropic retention posture before pilot week 0** (DR-9 dropped Groq, so the Groq ZDR item is moot) · Meta WhatsApp BD pricing & healthcare template policy · the 5–6 chronic markers for trend detection · TTS provider · BD data-protection law status.
