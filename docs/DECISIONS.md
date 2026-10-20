# Baymax decision records

Chronological. Later records supersede earlier ones where noted. Format: `DR-n | date | decision | why | supersedes`.
Add new records at the end; never rewrite an old one — add a superseding record instead.

## DR-1 | 2026-09-12 | No external health-record system in MVP

**Decision:** No external health-record system (Medplum or other) in the MVP. Records live in the app's
Postgres, with FHIR-aligned naming, behind one `HealthRecordPort`. Revisit post-pilot or at the first
provider integration.

**Why:** one runtime for a solo-run pilot; FHIR modelling before the extraction schema stabilises is wasted work.

**Supersedes:** none.

## DR-2 | 2026-09-12 | Baymax is a new module beside MedScribe

**Decision:** Baymax is built as a new module (`baymax`) beside `medscribe-ai` in the same repo. The MedScribe
pipeline, endpoints, UI and DB tables are left as-is. Baymax reuses `elioo-llm`, `elioo-gcp-vision`, core and
CI/CD. Comprehend Medical and GCP Translate are not used by Baymax; they are not removed from MedScribe.

**Why:** keep the PoC demoable, no regression risk, reuse what works.

**Supersedes:** BAYMAX.md "remove Comprehend/Translate from `/process`" — reworded to "not used in the Baymax
pipeline". PRODUCT.md §4.3 ("Comprehend Medical removed entirely", "Translate removed") is read the same way:
absent from Baymax, untouched in MedScribe.

## DR-3 | 2026-09-12 | Extraction providers

**Decision:** Extraction default provider is Groq, through the existing `LLM_PROVIDER` wiring. The strong model
is reached via the `anthropic` provider. Groq zero-retention terms are verified before pilot week 0.

**Why:** unblocks the build; pilot data is Tanvir's own families.

**Supersedes:** none.

## DR-4 | 2026-09-12 | Production object storage: self-hosted MinIO — SUPERSEDED same day by DR-5

**Decision:** Production object storage: self-hosted MinIO on the pilot EC2 box, nightly off-box mirror,
restore tested before week 0.

**Why:** avoid storage lock-in.

**Supersedes:** none. **Superseded by:** DR-5.

## DR-5 | 2026-09-12 | Production object storage: AWS S3 via the instance role

**Decision:** Production object storage: AWS S3 ap-south-1, private bucket (`elioo-baymax-prod`), SSE-S3,
EC2 instance role, no static keys. MinIO is local-dev only.

**Why:** durability and presigned URLs proven; zero ops during pilot; the S3-compatible port keeps MinIO as a
later cost swap.

**Supersedes:** DR-4.

## DR-6 | 2026-09-14 | Groq ZDR not enabled for the eval run

**Decision:** The Groq zero-data-retention toggle is not enabled before the evaluation run. The corpus goes to
Groq under default terms: no training on the data, reliability and abuse logging retained up to 30 days, in
US buckets.

**Why:** Tanvir's call — evaluation speed over retention posture, for a one-off run on 10 documents.

**Supersedes:** relaxes BAYMAX.md hard constraint 6 ("LLM providers used only under zero-retention /
no-training terms") **for the evaluation only**. The constraint applies again in full before pilot week 0,
when real families' documents start arriving. Enabling Groq ZDR is on the pending list.

## DR-8 | 2026-09-15 | Strong extraction tier = claude-sonnet-5, not opus-5

**Decision:** Strong extraction tier = claude-sonnet-5, not opus-5.

**Why:** opus measured $0.073–0.113/doc against a $0.15 per-document ceiling.

**Supersedes:** none.

*(DR-7 is unused: the PO's numbering jumped. Left as a gap rather than renumbered, so a ticket that cites
DR-8 keeps meaning this record.)*

*Engineering note:* `baymax.extract.strong-model` defaults to `claude-sonnet-5` and stays overridable per
environment via `BAYMAX_EXTRACT_STRONG_MODEL`, so a run that wants opus can still have it without a deploy.
Sonnet 5 lists at $2/$10 per 1M tokens against opus-5's $5/$25 — a 2.5x cut on both sides before any
measurement of quality, which BMX-2's re-run provides.

## DR-9 | 2026-09-15 | Extraction tiers: cheap = claude-haiku-4-5, strong = claude-sonnet-5; Groq dropped

**Decision:** Extraction tiers: cheap default = claude-haiku-4-5, strong = claude-sonnet-5. Groq dropped —
free-tier cap unshippable, paid tier declined.

**Why:** the cheap tier must actually run; both Anthropic tiers sit under the $0.15/doc ceiling.

**Supersedes:** DR-3, DR-6, DR-8.

*Engineering note:* Groq's free tier caps **output** at 1,000 tokens/minute. One document declares ~1,810
expected output tokens, so Groq returns HTTP 429 before the model runs — measured directly: `max_tokens:
900` returns 200, `max_tokens: 1810` returns 429. This is a production ceiling of roughly one document per
two minutes across all families, not an eval artefact, and no pacing or call-splitting fixes a per-minute
budget. Lowering `max_tokens` under the cap would truncate the JSON mid-object and fail schema validation
instead — a visible failure traded for a silent one.

The Groq client stays in `elioo-llm` and in the config; it is unused by Baymax, not deleted, and returns as
the cheap tier if the account is ever upgraded. MedScribe is untouched and still runs Groq by default.

DR-6 is superseded as a consequence rather than on its merits: it relaxed the zero-retention constraint for
the Groq evaluation run, and there is no longer a Groq run. Hard constraint 6 applies in full again.

## DR-10 | 2026-09-17 | Extraction runs single-tier on claude-sonnet-5 for printed documents

**Decision:** Extraction runs single-tier on claude-sonnet-5 for printed documents. Haiku stays configured
and unused; confidence-based escalation stays in code, default off. Re-measure both on the handwritten batch
before freezing.

**Why:** measured — any threshold recovering the 26-point gap costs as much as Sonnet alone; 86.2% at
$0.0253/doc, 17% of ceiling.

**Supersedes:** DR-9 (tier names stand, default changes).

*Engineering note:* on the ten printed documents Haiku scored 60.0% at $0.0118/doc with two retakes; Sonnet
86.2% at $0.0253 with none. Haiku's reported confidence was 0.85–0.95 on every completed document while its
gap to Sonnet on the same document ranged from 0 to +0.60, so confidence cannot route: escalating at < 0.86
reached 82.5% for $0.024, and every higher threshold cost more than Sonnet alone for no further gain.
Configuration: `baymax.extract.vision-model` defaults to `claude-sonnet-5`, `strong-model-threshold` to `0.0`
(escalation off; a `critical` flag still escalates, to the same model). To re-enable the two-tier path set
`BAYMAX_EXTRACT_VISION_MODEL=claude-haiku-4-5` and a threshold; the Haiku client and its price entries remain.

## DR-11 | 2026-09-18 | URL layout flips: Baymax serves `/`, MedScribe PoC moves to `/medscribeai/`

**Decision:** URL layout flips: Baymax serves `/` (landing page + app); MedScribe PoC moves to `/medscribeai/`.
Deferred at the time; **done in BMX-6b (2026-09-18)** — see the engineering note and docs/BAYMAX.md.

**Why:** Baymax is the product; the PoC is a demo artefact.

**Supersedes:** none.

*Engineering note:* nothing moves now — BMX-5b and BMX-6 come first. When it does: the MedScribe static UI
(`medscribe-ai/src/main/resources/static/index.html`) and its `/api/...` routes gain the `/medscribeai` prefix, the
Baymax web UI moves from `/baymax/**` to `/**` with `/api/v1/baymax/**` unchanged, and Caddy on the box needs no
change (it proxies the whole host). Session cookie `Path=/` already covers the new layout; the OTP login cookie is
scoped to the UI base path and must follow it.

## DR-12 | 2026-09-18 | Crop invariant is content-based, not existence-based

**Decision:** Crop invariant is content-based, not existence-based: a stored crop must contain its item's text,
asserted on every path. Overlap-tolerance resolution is removed.

**Why:** 116 of 137 stored crops showed neighbouring text; constraint 3 was satisfied by a rectangle that existed
rather than one containing the value, and no test could see it because a crop is an image.

**Supersedes:** none.

*Engineering note:* `SpanLocator.contains` gates every crop in `CropCutter` (BMX-5b, PR #17): the model's offsets
are used only when the words there carry the item's text; otherwise the text is located on the page and the whole
OCR line is cropped; otherwise nothing is stored. The re-crop admin route (`POST /api/v1/baymax/admin/documents/
{id}/recrop`, RUNBOOK) replays stored `extraction_json` through the current cutter — Vision only — so a corpus
cropped before this decision never needs re-extraction. Standing rule in CLAUDE.md: an invariant asserted on a proxy
is not asserted.

## DR-13 | 2026-09-18 | Review-gate reviewer during pilot = Tanvir, via WhatsApp approve/reject

**Decision:** Review-gate reviewer during pilot = Tanvir, via WhatsApp approve/reject. Gate on for "this
week"/"now" in weeks 0–2, default off otherwise. No licensed-doctor approver and no provider portal in v1.

**Why:** a doctor approving patient-facing messages converts an information service into something resembling
remote practice (§6.1) and risks their licence, not ours. Reviewer identity is config — a doctor can be attached
later without a refactor.

**Supersedes:** none.

*Engineering note:* `baymax.outbound.gate-mode` = `off` (default) | `all` | `urgency`, with
`baymax.outbound.gate-urgency-levels` = `now,this_week`. For weeks 0–2 set `BAYMAX_GATE_MODE=urgency` on the box.
The reviewer is whoever presents the admin token; the optional `X-Baymax-Reviewer` header names them (default
`tanvir`) and is stored on the row. Notification of a pending message is `ReviewerNotificationPort` — the log until
BMX-10 puts it on WhatsApp. Nothing PENDING is ever delivered without an explicit approve (asserted in tests).

*Amended 2026-10-17 (PO, D5):* the stored, code-computed urgency shows on every surface whatever the gate state. On
WhatsApp a PENDING NOW/THIS_WEEK document gets its fixed urgency line at once. A PENDING **body** is still never
delivered: "Nothing PENDING is ever delivered" now reads "No PENDING body is ever delivered". Spec:
`docs/superpowers/specs/2026-10-17-medioo-companion-experience-design.md` §2 F3.

## DR-14 | 2026-09-18 | Visual direction for the Baymax web app

**Decision:** Visual direction for the Baymax web app: white / soft red / warm neutral palette, rounded and calm
throughout, nothing clinical. Bangla default with an English toggle. Name "Baymax" stands through the open-source
MVP; public brand remains open before commercial launch (§8). Tagline and mascot asset are supplied by Tanvir.

**Why:** the product should feel like a presence, not a form.

**Supersedes:** none.

*Engineering note:* BMX-6b applies this as one token stylesheet (`baymax/src/main/resources/baymax/ui/baymax.css`:
colour, radius, spacing, type scale; since 2026-10-19 written as the parts under `baymax/ui/css/` and served as one
file in `AssetHandler.CSS_PARTS` order) consumed by the landing page and every app page; no colour or radius literal
outside it (grep-asserted in a test). The mascot is referenced from one place (`Html.MASCOT`) with a placeholder until
Tanvir's asset lands; the reference images Tanvir sent are Disney's Baymax character and are used as palette/mood
reference only — never committed or served, because the repo is open-source and the character is Disney IP (§8).

## DR-15 | 2026-09-19 | Public product name is Medioo, under Elioo Health

*(The relayed decision text read "Medio"; the PO corrected the name to **Medioo** and the domain to `medioo` the same day, before merge.)*

**Decision:** Public product name is Medioo, under Elioo Health (company: Elioo, https://www.eliooo.org/). Supersedes
"Baymax" as the public brand; Baymax remains an internal codename only. Tagline slot still Tanvir's. Mascot: an
original asset Tanvir supplies — no Disney character.

**Why:** §8 required a public brand before launch; Medioo is ownable and sits cleanly under the existing Elioo Health /
elioo-health naming.

**Supersedes:** closes the §8 "public brand name" open item.

*Engineering note (BMX-6c):* the rename is copy and docs only. Renamed: every user-facing string in both language
bundles, page titles, the landing wordmark and footer ("Medioo — a product of Elioo Health", linking to the company
site), docs/BAYMAX.md → docs/MEDIOO.md (stub left at the old path). **Not renamed, deliberately:** the Gradle module
`baymax`, Java packages `com.elioo.baymax`, schema `baymax`, config prefix `baymax.*`, env vars `BAYMAX_*`, cookie
names `baymax_*`, the asset path `/assets/baymax.css`, branch and ticket prefixes (BMX-n). Churning code for a brand
buys nothing and risks a bad deploy. `MediooBrandTest` asserts no bundle string and no rendered page carries the codename.
Pre-rename check: medioo.eliooo.org already resolves to the EC2 box (no Caddy site block yet); a quick search found no Bangladeshi health product
named Medioo (MedEasy and MedicBD are the nearest names) — not a trademark search.

*Amendment to DR-14 (PO, 2026-09-19, with the Mio mascot and medioo wordmark):* palette is **teal primary** (from the
asset), white and warm neutral surfaces; **red is reserved for the wordmark dot and NOW-urgency states only** — red
appearing nowhere else is what makes "এখনই যান" read as urgent. Destructive buttons are dark, not red. The supplied
poster is a marketing asset: its gradient background and three-icon footer strip are not used on the landing page.
Slots: `Html.WORDMARK` (`/assets/medioo.svg`, header of every page) and `Html.MASCOT` (`/assets/mio.png`, hero);
each renders only once its file is in `baymax/src/main/resources/baymax/ui/`, so dropping the files in is the whole change.

*Amendment to DR-14, palette values (2026-09-19, with the assets in hand):* ink `#0a2e4d` (the wordmark's "medi"), teal
primary `#06788a`, tint `#ddf1f4`, paper `#f8f6f1`, green `#2ee0a5` (the wordmark's "oo"), amber `#92400e` for THIS_WEEK,
red `#b91c1c` reserved for NOW. The PO approved the storytelling landing design the same day (BMX-6d); its four unkept
promises and the open medicines-in-explanation question are recorded in docs/MEDIOO.md.

## DR-16 | 2026-09-19 | Medicines may appear in outbound explanations as verbatim transcription with crops, never as model-generated text

**Decision:** Medicines may appear in outbound explanations as verbatim transcription with crops, never as
model-generated text; medicines remain out of the model prompt.

**Why:** the dosing line is the most useful thing on a prescription and transcribing it is grounded; composing about
medicines is where the risk is.

**Supersedes:** refines BMX-6's blanket exclusion.

*Engineering note:* `ExplanationService` loads the document's medicines and, **after** the model has phrased the
skeleton and the safety check has passed on that text, appends a fixed header (`medicines.header`) and one line per
medicine — `name`, `dose_text`, `frequency_text`, `timing_text` exactly as stored, joined by " · ", empty fields skipped.
The model never sees them (asserted on the captured prompt). The forbidden-phrase and number checks run on the
model's text only; the verbatim block is exempt. `MedicineTranscription.verify` then asserts that every stored
medicine string appears in the final body character for character, or the message fails closed. Crops are not
copied onto the message: each verbatim line's crop is the medicine's stored `crop_key`, rendered beside it on the
document page and attached at delivery (BMX-10). Still forbidden, unchanged: saying what a medicine is for, inferring a
schedule, restating dosing, any start/stop/change advice. Deviation to note: the 600-character cap applies to the
model's text; the verbatim block is added after it, so a long prescription can push the whole body past 600.

## DR-17 | 2026-09-19 | Trend markers and directions

**Decision:** Trend markers and directions: hba1c, fasting_glucose, creatinine, bp_systolic, bp_diastolic, ldl — rising is
wrong; tsh — either direction. Three consecutive readings.

**Why:** the six most common chronic markers on Bangladeshi reports plus thyroid, which is very common locally.

**Supersedes:** none.

*Engineering note:* `baymax.nudge.trend-rising` / `trend-either-way` / `trend-readings` (config, defaults as ruled). The rule
walks the current monotonic run at the end of a patient's series for a canonical marker (`observation.canonical_name`,
`MarkerMatcher`); the dedupe key is the run's first reading, so a fourth reading in the same run never re-fires and a
reversal starts a new run that can. Values are shown verbatim with their units.

## DR-18 | 2026-09-19 | Nudge policy

**Decision:** Nudge policy: max 2 per patient per 7 days and 1 per day; 09:00–20:00 Asia/Dhaka; highest urgency wins and the
rest drop; nudges are never NOW; all nudges gated during the pilot; every nudge carries an opt-out.

**Why:** over-messaging is how a family mutes the product, and §7.2 metric 5 measures replies, not volume. An unprompted NOW
about old data would terrify rather than help.

**Supersedes:** none.

*Engineering note:* `NudgePolicy` (pure) + `NudgeEvaluationService` (BMX-8). "Never NOW" is a type: `NudgeUrgency` has two
values and the `nudge.urgency` CHECK has two; the composer refuses a NOW row besides. Outside the window a nudge is HELD
with `hold_until` = next 09:00 Dhaka and released by the hourly job (caps re-checked then). `baymax.outbound.gate-nudges`
(default true) parks every nudge PENDING regardless of `gate-mode`. The opt-out link carries an HMAC of the patient id under
`BAYMAX_AUTH_HMAC_SECRET`; opt-out is stored on `patient_profile` / `family_account` (`nudges_opted_out_at`) and checked
before any rule runs, so an opted-out patient — silence rule included — is never evaluated.

## DR-19 | 2026-09-19 | Nudge tie-break order, and deferral instead of dropping for date-bound nudges

**Decision:** On equal urgency, nudge rules are prioritised follow_up_due > course_ending > medicine_changed >
trend > silence; date-bound nudges dropped by a cap are deferred to the next day rather than discarded.

**Why:** follow_up_due fires once per follow-up, so a cap drop meant the family was never told about the appointment.

**Supersedes:** refines DR-18.

*Engineering note:* the order is `NudgeRule`'s declaration order and `NudgePolicy.select` sorts by it after urgency.
`NudgeRule.isDateBound()` is true for follow_up_due and course_ending; a cap gives those rows status `deferred`
(V11) with the cap as the reason, carrying the trigger's `deadline`. The next evaluation calls
`NudgePort.consumeDeferred`, which marks the old row `dropped` with `superseded_by_retry` and lets the trigger be
attempted again; `NudgeCandidate.expired(today)` stops the retry once the date has passed. The export counts
`deferred` in its own column, so a weekly log never reads a postponement as a refusal.

*Copy rulings the same day, standing for every outbound message:* every message names the patient as the family
entered it (`{patient}`), never "আপনার" — the reader is the eldest child and the patient is their parent; dates
render as Bangla day-and-month via `BanglaDate` ("১ জুন"), never ISO; the trend template states the direction in
words ("একটু একটু করে বাড়ছে" / "কমছে").

## DR-20 | 2026-09-19 | A date-bound nudge is never dropped, however it was lost

**Decision:** A date-bound nudge (follow_up_due, course_ending) is never dropped. It sends, defers to the next day,
or expires when its date passes — whether it was refused by a cap or lost a tie in the same evaluation. Only
non-date-bound rules (trend, medicine_changed, silence) may be discarded, since those can be said on any day.

**Why:** a family not told about their mother's appointment is the same failure however it was lost. DR-19 split
the rule on mechanism (a cap) rather than on consequence (the family never hears), so a date-bound nudge beaten in
a tie was still discarded.

**Supersedes:** widens DR-19.

*Engineering note:* `NudgeEvaluationService.deferOrDrop(c)` is the single definition of "may this be discarded?",
called from both the tie path (`decide`) and the cap path (`place`) — previously only the cap path asked. A loser
keeps its `superseded_by_<rule>` reason; only its status changes, so the export still shows what outranked it.
Guard: `aDateBoundNudgeBeatenInATieIsDeferredAndSendsTheNextDay` — replayed red against the pre-fix source
(`expected: DEFERRED but was: DROPPED`) before it counted.

## DR-22 | 2026-09-19 | Nudge uniqueness is a partial unique index over live rows

**Decision:** Nudge uniqueness is a partial unique index over live rows `(patient_id, rule, trigger_key)`
excluding `dropped`/`failed`, not a blanket constraint. A consumed deferral is retired and retained; the retry
inserts cleanly. One ACTIVE nudge per trigger, history preserved.

**Why:** DR-19's audit trail requires the retired row to survive, and V10's blanket constraint made the retry
collide with the row it had just retired — defeating DR-19 and DR-20 one day later. Found on production: the
evaluation 500'd with the deferral already consumed, so the nudge was retired, no replacement row was written,
no message was composed, and the family was never told about the appointment.

**Supersedes:** none.

*Engineering note:* V12 drops `nudge_patient_id_rule_trigger_key_key` and creates `nudge_live_trigger_idx`
`WHERE status NOT IN ('dropped','failed')`. `NudgePort.exists` still counts **every** row, retired included —
the index frees the key, it does not decide whether a trigger may fire again; `consumeDeferred` remains the one
path that deliberately bypasses dedupe. Guard: `PostgresNudgeAdapterTest` on real Postgres (Testcontainers),
replayed red against the pre-V12 schema with the production error verbatim
(`DuplicateKeyException: duplicate key value violates unique constraint`).

*Per-patient isolation, same ruling:* one patient's failure must never cost every other family their nudges for
that run. `evaluateAll` isolates each patient with `onErrorResume`, logs the failing patient and family id,
counts it (`lastRunFailedPatients`) and continues. Before this a single duplicate-key error aborted the whole
evaluation, and nobody would have noticed until a pilot family asked why they had heard nothing — worse than the
duplicate key itself. Guard: `onePatientsFailureDoesNotStopTheRestOfTheRun`, replayed red.

## DR-23 | 2026-09-19 | Query strings are logged in clear; the fix is deferred, with a trigger

**Decision:** Query strings, including the WhatsApp verify token, are logged in clear by `IWebFilter`. Fix
deferred by Tanvir's call. **Must be fixed before any feature puts a credential or an unauthenticated access
link in a query string** — media download, OTP links, opt-out links.

**Why:** negligible exposure today on a private test channel; a filter-level fix that grows more expensive with
each feature added.

**Supersedes:** none.

*Engineering note, 2026-09-20.* `IWebFilter` (MedScribe, pre-Baymax) logs the full request URI at INFO on both
receive and respond, query string included. Two things follow, and the second sharpens the trigger:

1. **The WhatsApp verify token is already in the production log**, written on every handshake. Meta re-verifies
   periodically, so it accumulates. Rotating `BAYMAX_WA_VERIFY_TOKEN` costs one dashboard edit and one restart —
   cheap now, and worth doing whenever the filter is fixed.
2. **The trigger condition is already met in code, though not yet in data.** BMX-8's opt-out link is
   `/app/nudges/opt-out?p=<patientId>&t=<HMAC>` — an unauthenticated access link with its token in the query
   string, live since #33. It has not been logged because no family has clicked one: the filter logs *inbound*
   URIs, and those links exist only inside gated messages that have never been delivered. **The first click
   writes a working opt-out token for a real patient into the log.** The gate is what is holding this, not the
   design.

Until the filter is fixed, treat any credential in a query string as logged. The fix is to redact
`t`, `token`, `verify_token`, `hub.verify_token`, `key` and `secret`-shaped parameters in `IWebFilter` before
the URI reaches the log line — one place, both directions.

**Amendment, 2026-09-21 (Tanvir).** The condition in the original ruling is already met, so the deferral becomes
a **deadline**: the query-string logging fix **and** the `BAYMAX_WA_VERIFY_TOKEN` rotation must land **before any
nudge is delivered to a real family** — that is, before pilot week 0 and before `baymax.outbound.gate-nudges`
stops parking messages PENDING. Listed in *Pending from Tanvir* as blocking week 0.

The reasoning: BMX-8's opt-out link has carried an unauthenticated HMAC in a query string since #33. The only
thing keeping it out of the production log is that the gate has never released a nudge, so no family has ever
clicked one. **That is accidental protection, not a control** — it disappears the moment the gate opens, which is
exactly the moment real families start receiving messages. Rotation is paired with the fix rather than done
first, because rotating into a log that still records the value buys nothing.


**Done, 2026-09-25 — the logging half.** `IWebFilter.redactSecrets` replaces the value of any
credential-bearing query parameter before the URI reaches a log line, on **both** directions and in all three
places the filter leaked: the request `Uri`, the request `Query Params` map, and the response `Uri`. DR-23
named only the URI; the query-params map was a third channel found while fixing it.

Matched on the whole parameter name, case-insensitively, never as a substring — `tokenizer=bert` and
`keyword=fever` survive. **Over-redaction is its own failure:** a log that hides `page=2` makes a real
incident harder to diagnose and the next person turns the filter off, so the non-secret parameters are
asserted to survive verbatim.

Verified on a running app, not only in unit tests: an opt-out link and a WhatsApp handshake were requested and
the log contains zero occurrences of either secret, with `p`, `hub.mode` and `hub.challenge` intact.

**Still outstanding: the `BAYMAX_WA_VERIFY_TOKEN` rotation.** The token already in the production log stays
valid until it is rotated, so the leak is closed but not undone. One dashboard edit and one restart, on the
server — it needs Tanvir, and it is still a week-0 blocker.
## DR-24 | 2026-09-21 | A retake does not consume a free-tier slot

**Decision:** NEEDS_RETAKE documents do not count against the free-tier monthly limit; only DONE consumes a
slot, and FAILED does not count either.

**Why:** a retake delivers nothing, so charging for it bills the family for our failure to read their photo,
contradicts the retake copy ("it's not your fault"), and falls hardest on families with the worst cameras — the
users the product exists for. Pilot metric 1 is activation within 48h, and a family whose photos bounce and
whose next upload is refused never activates.

**Supersedes:** none.

*Engineering note.* Found on production during the BMX-10 acceptance run, in the worst possible order: a photo
came back NEEDS_RETAKE at confidence 0.75 ("it's not your fault, please send another"), and the next upload was
refused with "you have used all your free reports this month". The filter lives in
`PostgresDocumentRecordAdapter.countInMonth`, whose only caller is `FreeTierService`; `countInWindow` stays a
plain count of rows, because reporting wants every document and billing does not. Guard:
`retakesAndFailuresDoNotConsumeAFreeTierSlot` — replayed red against the unfiltered count, where a family with
two readable documents and four unreadable ones is refused.

## DR-25 | 2026-09-21 | Retake images are purged after 7 days

**Decision:** NEEDS_RETAKE page images are purged 7 days after the retake unless the document later reaches
DONE. The document row (status, reason, timestamps, confidence) is kept for metrics; only the image is deleted.
**Retake images are never used for evaluation or any other purpose.**

**Why:** once the family has been asked to resend, the image does nothing for them; the retry path it preserves
is speculative and a bounded window covers it. Using failed photos as eval data would repurpose a family's
medical document without consent.

**Supersedes:** none — pairs with DR-24.

*Engineering note.* Found during the BMX-10 acceptance run: a NEEDS_RETAKE document held 1 object, 107,634
bytes, for a read that delivered nothing. DR-24 had just ruled that a retake must not *cost* the family a slot,
which made keeping their image the obvious next question. Implementation is a scheduled job: delete the object
and its `stored_object` ledger row, then **verify the bucket, not just the ledger** — the family-deletion bug
(#41) was exactly a ledger that agreed with itself while objects survived. The commit-then-bucket ordering from
that fix does not apply here because the document row stays either way. Logs carry counts only, never a family
or patient id.

## DR-26 | 2026-09-21 | The doctor's diagnosis lines go in the message, verbatim

**Decision:** The explanation message includes the document's diagnosis lines verbatim under "ডাক্তার লিখেছেন",
with their crops. Never explained, paraphrased, or linked to medicines or symptoms. Chief complaints,
examination and history remain on the timeline and in the detail reply.

**Why:** the family's first question is whether anything is wrong, and the doctor has written the answer;
listing medicines while omitting the diagnosis they treat is the silent failure. Transcription is grounded;
interpretation is where the risk lives — same line as DR-16.

**Supersedes:** none.

*Engineering note.* Found on 2026-09-21: prescription 08c02570 recorded "HTN (known)", "HTN + DM (known)" and
"Parkinson Disease", plus near-blackout and hallucinations in the complaints, and the family received a medicine
list and a (false) reassurance. `DiagnosisTranscription` inserts the lines after generation exactly as
`MedicineTranscription` does — never passed to the model, verified character for character against the store,
and the composition fails closed if a line is missing. Only `diagnosis` is included; complaints and examination
are deliberately not.

*DDR line, closed 2026-09-22 (Tanvir, against the paper).* The page genuinely reads "১+০+০ - খাবারের আগে -
৩০ দিন। তারপর ১+০+১ - খাবারের আগে - ২ সপ্তাহ" — a two-phase regimen, extracted correctly, including the
sequence word and both durations. **The defect was rendering, not reading:** `duration_text` was never in
`FIELDS` so the durations were dropped, and the frequency was abbreviated to "১+০+০ ... ১+০+১". Evidence for
extraction quality rather than against it, and worth carrying into the confidence-vs-plausibility question: a
model that reads a two-phase handwritten Bangla regimen correctly is not one struggling with legibility.

*The 600-character cap, same ruling.* The cap ran on the model's text while the medicine block — the longest
part — was appended afterwards, so it never covered it. It is now asserted on the complete body after every
insertion. Where that body overflows, `MessageSplit` sends a second message rather than dropping medicines:
the cap was set for single-bubble readability, WhatsApp allows 4,096, and it must never be the reason a family
loses their dosing instructions. A medicine line is never truncated and never dropped; the split falls on a
line boundary. Measured across the production documents before building: 0 of 4 exceeded 600, but the headroom
is 5 medicines at the observed mean line length, so a 6-medicine prescription overflows.

## DR-31 | 2026-09-22 | A locator failure is not a retake

**Decision:** Amends DR-28. A lab report whose extraction passes the confidence gate but shows zero values
after cropping is **DONE, not NEEDS_RETAKE**. Its extraction is persisted; no value is shown; unverified
values may raise urgency; the message says the values could not be checked against the photo and points to
the original. **NEEDS_RETAKE is reserved for weak reading, never for our locator's failure.**

**Why:** a clear, correctly-read photo would otherwise be retaken forever — the family is told their photo is
unclear, retakes it, and hits the same locator failure — and because a retaken document persists no
extraction, the abnormal value is discarded each time. lab10 is exactly this: a clear page, read correctly at
0.9 confidence, whose single value carried an out-of-range uric acid.

**Supersedes:** DR-28's zero-shown clause.

*Engineering note.* The distinction is whose failure it was. The confidence gate judges the READING and may
legitimately ask for another photo. The crop locator judges whether we can point at the text we read, and its
failure says nothing about the photo — asking the family to fix it is both false and futile. DR-28's other
half stands: values dropped for want of a crop still participate in urgency, may raise it and never lower it,
and are never shown or named in the body.

## DR-32 | 2026-09-24 | A crop may come from the image, but never on the model's word alone

**Decision:** when a value's crop cannot be located in the OCR text, it may be cut from a **region** the
model reports on the page image (`source_region`, normalised 0–1). That crop is stored **only** if an
independent reader — Vision OCR on the crop, or, when OCR reads nothing, a model shown the crop and nothing
else — reads the value back out of it, under the same digit-boundary rule as the locator. The reader is
never told what it is looking for. Anything short of confirmation leaves the value unshown, exactly as a
locator failure does today (DR-31).

**Why:** 30 of 85 cropped values in batch 2 were lost as `TEXT_NOT_ON_PAGE` — every one legible on the page.
Extraction reads the image; the locator could only search text Vision produced, and no string rule over that
text can find text Vision never produced. Relaxing the locator's match on digit boundaries recovered exactly
zero. The region recovers 24 of 29 on a like-for-like rerun, at $0.0045 per document.

**What it does not change:** DR-12 stands unweakened. A crop is still stored only when something other than
the extraction confirms its contents — the change is that the confirming evidence is now a second reading of
the crop rather than a lookup in the OCR text. The model's box is a proposal, never evidence.

*Engineering note.* The failure this guards against is DR-12's original one wearing new clothes: a model that
misreads a table by one row produces a confident box over the wrong number, and storing it would put a
picture of a different value under this value's name — worse than showing nothing. A region covering more
than a third of the page is discarded as a shrug rather than a pointer. lab10 is the proof the check bites:
its crop failed OCR, failed the model, and stayed unshown. Every one of the 24 recoveries was confirmed by
OCR, so the model fallback is currently the refusal path rather than the recovery path; if batch 3 confirms
that, it can be dropped for 3% of the verification cost.

## DR-36 | 2026-10-06 | No real patient document in the repository, enforced by a check and not a README

**Decision:** no real medical document may be committed to this repository, in any form, for any purpose —
**including a real record obtained from a public source.** Every image under `src/test/resources` must be a
synthetic fixture declared in `.test-fixtures-allowlist`, and `scripts/check-test-fixtures.sh` fails the
build otherwise. The real evaluation corpus stays git-ignored, as it already was.

**Why:** two real patient lab reports — named patients, hospital identifiers, referring and reporting
clinicians, full results — sat in `medscribe-ai/src/test/resources/test-images/` from the repository's first
public commit (`5529cb6`, 2026-09-11) until 2026-10-06. They were removed from all history with
`git filter-repo` (runbook: `docs/HISTORY_REWRITE.md`), along with a real personal phone number in four test
files.

**The detail that decided the enforcement mechanism:** a `README.md` in that same directory said, in bold,
*"Never use real patient data in test images… Remove all PHI."* **The rule was written and broken in the same
commit, and nothing noticed for three weeks.** A rule in prose asks every contributor to read it, remember it
and apply it under deadline. A check asks nothing.

**Already-public is not an exemption.** The two images were downloaded from a Google image search, so no
disclosure was caused by this repository and no notification was owed — that is why no GitHub Support
cache-purge request was filed. It changed nothing about the removal. A medical record on the public web is
still a real person's record; shipping one in a test fixture normalises it, and the next contributor reads
the repository rather than the README.

**Supersedes:** none. Extends the standing rule that the corpus is git-ignored, from "do not commit the
corpus" to "no real document anywhere, enforced".

*Engineering note.* The allowlist starts **empty** — the only two entries were the files being removed, so
there is no grandfathered exception to argue about later. Adding a fixture means adding its path in the same
commit and saying in the commit message where it came from, which turns an unreviewable binary blob into a
one-line diff a reviewer can actually see. The check is written for bash 3.2 so it runs on a developer's
machine as well as the runner: a guard that only fires in CI lets someone commit the thing locally and find
out thirty minutes later.

## DR-37 | 2026-10-17 | The companion (chat) is core MVP scope

**Decision:** Medioo answers every message, on the web and on WhatsApp, from one companion brain grounded in the
family's own records (PO, D1). Code is in charge of numbers, urgency, medicines, diagnoses, patient identity and
refusals; the model only words facts code has already checked. Recorded with it: **D4** "what is this medicine for?"
stays refused (DR-16 unchanged); **D8** code-built answers are unlimited and model-worded answers are capped at 6 per
family-month, counted on what was sent; **D13** a voice note gets a fixed reply plus the red-flag line now, Bangla
speech-to-text later; and the standing direction **"fully automated first"**: production runs `gate=off` and the
reviewer workflow and portal come later.

**Why:** Tanvir, testing as a user on 2026-10-16, found that "Hi" got silence, only one exact word brought the detail,
and nothing could be asked. A records service that cannot be spoken to reads as twenty-year-old technology. Push
(nudges) stays the moat; chat is added to it, not substituted for it.

**Amends:** PRODUCT §4 "MVP in" (chat added). Retires the UI rule "no urgency anywhere in the UI" in favour of spec
principle 3 (urgency always visible, only up). Spec: `docs/superpowers/specs/2026-10-17-medioo-companion-experience-design.md`;
design artifacts and plan linked from its header.

## DR-38 | 2026-10-16 | Open source is paused; the repository stays private

**Decision:** the repository is private and the open-source release is paused (PO, Sprint 1 brief). The prepared
history rewrite (`docs/HISTORY_REWRITE.md`) is not run while the repository stays private.

**Why:** the product is moving fast with real families in view; publishing can be revisited once DR-36's enforcement
has been in place long enough to trust.

**Consequence found 2026-10-17:** GitHub does not enforce branch protection or rulesets on a private repository on
this plan (the API answers 403, "Upgrade to GitHub Pro or make this repository public"). `main` is therefore not
technically protected; the "Build and test" check is enforced by process (CLAUDE.md, Branching).

*Amended 2026-10-18 and 2026-10-20 (PO):* the repository is **public**. GitHub Actions on a private repository
needed paid billing, which the project does not have; public repositories run Actions free. With the repository
public, branch protection is available again and was re-enabled on 2026-10-20 ("Build and test" required, admins
included, no force-push or deletion). **The history rewrite is declined** (PO, 2026-10-20): the two images DR-36 names
stay reachable in the history from `5529cb6` to PR #54 and in the tag `medscribe-poc-v1`. They came from a public image
search; no secret was ever committed (history scanned 2026-10-18: only AWS's documented example key and a `"..."`
placeholder). DR-36's check still keeps any new real document out of the tree.

## DR-39 | 2026-10-17 | Sign-in is inbound-first

**Decision:** the web sign-in code is fetched, not pushed (PO, D2, with D36 and D37 as recommended). The family texts
"কোড" to Medioo on WhatsApp and `issueCodeFor(sender)` mints a fresh code for the number WhatsApp has proved, within the
existing rate limit. The web mints nothing and never says a code was sent; the rate-limit message goes only to
WhatsApp; the `wa.me` deep link appears on `/app` only, never on the landing page; no code appears in any log above
DEBUG. Effective from the Phase 1a sign-in PR.

**Why:** only a code's hash is stored, so a code cannot be fetched later; a push outside WhatsApp's 24-hour window
needs an approved template, and an open web form that sends to any number would become a sender to arbitrary numbers.
A message the family sends first opens the window and proves the number in one step.

**Supersedes:** BMX-5's log-only delivery (`LogOtpDelivery`), which stays as a fallback that logs no code.

**Amended 2026-10-18 (PO, Tanvir): sign-in must be free.** Codes reach the family two ways, both inside WhatsApp's free
24-hour customer-service window and neither through a template:

1. **Automatic, from the web.** When the web sign-in names a number that (i) belongs to a Medioo account, (ii) is
   permitted by the allowlist rule (`BaymaxProperties.Wa.permits`), and (iii) wrote to Medioo on WhatsApp within
   `BAYMAX_WA_SERVICE_WINDOW` (default 23 h, a margin inside the 24 h window), Medioo mints a code and sends it to that
   WhatsApp as a free-form service message. The code page says the code was sent **only** when the Graph API accepted
   the send; on any failure, and outside the window, it shows the one-tap button instead.
2. **The button.** "WhatsApp খুলে কোড আনুন" opens `wa.me/<BAYMAX_WA_PUBLIC_NUMBER>?text=কোড`; the family sends "কোড"
   (or code, kod, otp, লগইন) and `issueCodeFor(sender)` replies with a fresh code inside the window that message opened.

Unchanged and binding: never a WhatsApp template (authentication, utility or marketing) for codes, because they are
paid; no code in any log above DEBUG (`LogOtpDelivery`'s INFO code line is removed); never an open sender (a number
with no account is sent nothing, from either path); the hourly cap applies to both paths and counts every code made
for the number in the rolling hour, whatever became of it (used, burned or expired), so it bounds sends and guesses at
5 codes x 5 tries per number per hour (review fix: it first counted only live codes, which let five wrong guesses buy
another send without limit); DR-23, no number or code in a query string. "The web mints nothing and never says a code was sent" above is replaced by rule 1.

The window needs one stored fact: when each account number last wrote. It is V17 `wa_last_inbound`, keyed by the
PhoneHasher HMAC of the number (a CHECK refuses anything else), forward-only, with a `family_id` FK that both the
family delete path and the schema-derived deletion sweep cover. No existing table records an inbound text message, so
it could not be derived without a migration. The stored time is the family's send time (Meta's `timestamp`, clamped to
now), not Medioo's receipt time: a callback Meta retries hours later must not open a window WhatsApp already closed.
A family delete locks the family row first, so a WhatsApp message arriving mid-delete cannot fail it on that FK.

**Known trade-offs (for the PO to accept or change):**

- The code page differs between "sent" and "not sent", so someone typing a stranger's number learns that it has an
  account and wrote to Medioo within the last day, and can cause up to five code messages an hour to that number
  (120 a day; every reply the family sends, "stop" included, keeps the window open). The page is the same for every
  not-sent reason, and the API's answer is identical whatever happened.
- The verify page's typed refusals say more than "wrong". Whoever holds the login cookie for a number (anyone who typed
  it) and submits any six digits learns whether a code was made for that number in the last hour: "expired" or "used up
  by wrong tries" means one was, "wrong"/"no code" means none is live. That is mostly what "sent" already reveals, but it
  also works for the button path's codes, which the web never announced. If the PO wants it closed, the page can show
  the specific reason only when the cookie carries the sent mark, or only after a real attempt on a live code.
- Auto-send reaches every account holder inside the window once `BAYMAX_WA_ALLOWLIST` is blank (blank = no phase-1
  restriction). Until the PO widens it on purpose the allowlist stays set to the pilot numbers; blanking it is a PO step,
  not an env tidy-up.

## DR-40 | 2026-10-21 | Next.js frontend beside Java

**Decision:** the Medioo web UI is rebuilt as a separate Next.js frontend, `medioo-web`, beside the Java app (PO
direction 2026-10-20: the web UI reads as toy-like; rebuild it in React/Next.js behind a visual QA gate). Architecture
by Claude Code, recorded as Part A1–A8 of [the rebuild plan](superpowers/plans/2026-10-21-medioo-nextjs-rebuild.md):

1. **Topology (A1).** `medioo-web` is a Next standalone container (`node:22-alpine`, `--memory=320m`) on `n8n_edge`
   with no published port, built and tagged with the same git sha as the Java image by the same deploy job. Caddy
   routes per path and **Java is the default**: a path reaches `medioo-web:3000` only once it is listed in the
   versioned site block [`docs/infra/Caddyfile.medioo`](infra/Caddyfile.medioo) **and applied on the box** (the file
   alone routes nothing; its header holds the in-place edit, validate and reload procedure for the single-file bind
   mount). There is no Next fallback proxy. Flipping a route back is a one-line Caddy edit plus that procedure.
   - **Java off the public port (W1c).** Java binds `127.0.0.1:8086` only and the security group closes 8086, so
     every request goes through Caddy's HSTS and per-path routing. **Known gap until W1c lands:** Java is published
     on all interfaces (`-p 8086:8086`) and CI smoke-tests `:8086` from the runner, so every `/app` page is reachable
     over plain HTTP on `:8086`, bypassing Caddy. The topology above is not yet true on that point.
   - **Headers on a flipped path.** A path routed to `medioo-web` no longer gets Java's `BaymaxSecurityHeadersFilter`
     / `SecurityHeadersFilter` headers, so Next sets them itself on every routed path: the nonce CSP (its `img-src`
     copied verbatim from `BaymaxSecurityHeadersFilter`), `X-Frame-Options: DENY` and `nosniff`. Caddy owns HSTS,
     `X-Content-Type-Options` and `Referrer-Policy` (its header block is deferred and overwrites upstream values), so
     Next sends the same `Referrer-Policy: strict-origin-when-cross-origin` as Caddy and Java. `scripts/smoke-https.sh`
     (W1c) asserts the actual response headers, CSP included, for every path class against
     `https://medioo.eliooo.org`, never against a local Next.
   - **No secrets in Node.** `medioo-web` runs without `--env-file` and receives only `JAVA_INTERNAL_URL` and
     `GIT_SHA`, so Node never holds Java's secrets. The CLAUDE.md log rule (no patient text, phone numbers, OTP codes
     or wamids above DEBUG) applies to the Node runtime and to browser `console` output as much as to Java.
2. **Deploy order and rollback (A2).** `deploy.sh` swaps Java first and waits for its health, then Node. A sha with no
   `medioo-web` image skips the web step and the deploy stays green. One `IMAGE_TAG` rolls back both containers
   **only down to the most recent route flip**. Caddy routing is state on the box, not tied to `IMAGE_TAG`, and
   "additive only" protects forward compatibility, not a rollback past the sha that added an endpoint a flipped route
   calls. Two cases break: rolling back to a sha before W1b skips the web step, so a newer `medioo-web` keeps running
   against older Java; and rolling Java back past the `/app/api` endpoint a flipped route calls breaks that route for
   every family. **Rollback floor:** rolling back to a sha older than the most recent route flip first reverts that
   route in the box's Caddyfile (backup, in-place write, validate, reload, as in the `Caddyfile.medioo` header), then
   sets `IMAGE_TAG`; `scripts/smoke-https.sh` runs after every rollback. Enforcement lands with the first flip (W1c):
   the flip records a routes-floor sha, and `deploy.sh` refuses an `IMAGE_TAG` that is not a descendant of it unless
   `FORCE=1`. The RUNBOOK rollback one-liner carries the same floor. Within the floor, API changes are additive only,
   so old Java with a new UI and new Java with an old UI never break.
   - **Amended 2026-10-21 (W1b, PR #100), for the PO to confirm.** "No `medioo-web` image" now means GHCR's
     missing-tag answer (`manifest unknown` / `not found`) only, and only that skips the web step green. Any other
     pull error (`denied`, `unauthorized`, rate limit, network, no space) fails the deploy after Java is healthy,
     because a green run would claim a rollback the web never took; a tag already on the box is used when the pull
     fails. CI deploys set `WEB_IMAGE_REQUIRED=1`: the job has just pushed `medioo-web:<sha>`, so there even a missing
     tag is a fault and the deploy goes red after Java is healthy; a manual run keeps the skip-and-stay-green rule for
     a missing tag. A failed web swap (no start, or no `/app/healthz` for the sha within 60 s) puts the previous
     `medioo-web` back and still exits 1; a missing `n8n_edge` fails the web step before the running one is touched.
     The health check requires `sha` (run-time `GIT_SHA`) and `build` (the image's baked `BUILD_SHA`) to equal the
     deployed tag. Consequence for manual rollbacks: with a private `medioo-web` package, a logged-out run whose web
     tag is not on the box goes red rather than green; log in first, or make the package public.
3. **Auth across the boundary (A3).** The browser calls `/app/api/*`, which Caddy sends to Java; the `Path=/app`
   session cookie travels on its own and is never widened. Server components call `http://medscribe-ai:8086/app/api/*`
   forwarding the incoming `Cookie` header and nothing else, and **only with `GET` or `HEAD`**; Next never mints or
   reads a session. Next has no server actions and no mutating route handlers: every mutation goes browser → Caddy →
   Java. A server-side fetch from Node sends no `Origin`, so a mutating call made from Next would carry the user's
   session past an Origin check by construction. Enforced twice: a `medioo-web` CI check (W1a) fails on any
   `'use server'` and on any fetch to `JAVA_INTERNAL_URL` with a method other than `GET`/`HEAD`; and Java (B1) requires
   every mutating `/app/api` request to carry an `Origin` equal to `baymax.web.public-origin`, rejecting a missing
   `Origin` with 403 `FORBIDDEN_ORIGIN` (browsers always send `Origin` on a fetch `POST`/`PUT`/`PATCH`/`DELETE`).
   Cookies are set only by browser calls that Java answers with `Set-Cookie`. Whether a family sees a Next twin before its route flips is
   Java's decision (`BAYMAX_WEB_PREVIEW_FAMILIES`, exposed as `me.preview`), never a cookie anyone can set.
4. **No-JS scope (A4).** The timeline, a paper, a NOW result and the opt-out confirmation are server-rendered and
   readable with JavaScript off. Every mutation (sign-in, upload, delete, settings, দেখেছি) needs JavaScript. This
   changes spec principle 14 and is **not in force until the PO accepts it as DR-42** (plan Part B, decision 1); until
   then sign-in stays on the Java pages and Caddy keeps `/app/`, `/app/login` and `/app/verify` on Java.
5. **Display model (A5).** Java owns `DisplayKind`: `ValueRows.kindOf` moves into the application layer, and with it
   the order of NOW reasons, the counts line, the gate state, wording only when `RELEASED`, `viewer.bangladesh`
   (`tel:999` only for +880 viewers), the retake ordinal, the owner flag and the NameGrammar forms. React renders these
   fields and derives none of them. The JSON never carries `confidence`, `model` or developer `status_reason` prose.
6. **Test migration (A6).** No Java UI test is deleted until its rule exists as a Java contract test on `/app/api` and
   a Playwright assertion on the routed page. The ledger is generated (`scripts/ui-test-ledger.sh`), and CI fails if
   any listed test lacks a resolving `// successor:` or if the list is empty. Java pages are deleted per route as each
   flips, never in one PR.
7. **Visual gate is a review (A7).** Every W PR publishes a private screenshot board (phone 390×844, desktop 1280×800,
   light and dark) beside the matching prototype scene for the PO. Correctness comes from content checks: axe, the CSP
   violation hook, overflow checks with the longest real-length strings, every value and medicine `<img>` resolving to
   its own row's crop with `naturalWidth > 0`, and the network budget. Before a route flips, Tanvir tries it on his
   phone with preview on.
8. **Guards replayed on both sides (A8).** Java contract guards are replayed against a deliberately broken build and
   React safety tests against a mutated component (`scripts/replay-web-guard.sh`); the failure message must name the
   defect. MSW fixtures are byte-compared with the files the Java contract tests write in the same CI run.

**Why:** the rebuild must change how Medioo looks without moving a single safety rule out of the code that already
enforces it, and without a day on which every screen is new at once. Per-path routing with Java as the default turns
the rebuild into a sequence of one-route flips, each reversible by one line. Keeping urgency, display kind, wording and
NameGrammar in Java means there is one implementation of each rule; a TypeScript copy would be a second one that drifts.
The generated ledger exists because a hand-written list of moved tests is exactly the check this repo has learned
cannot be trusted (CLAUDE.md: derive the assertion from the system, never from what the author remembered).

**Alternatives:**
- *Restyle the Java server-rendered pages.* Rejected by the PO on 2026-10-20 in favour of React/Next.js.
- *Next in front of everything, proxying to Java as a fallback.* Rejected: it would buffer 60 MB uploads in Node and
  add a second hop to every Java request. Uploads, images and every POST go to Java directly through Caddy.
- *A big-bang cut-over.* Rejected: no route can be flipped back on its own, and every Java UI test would have to be
  replaced before anything ships.
- *Next owns the session (BFF), or the cookie is widened past `/app`.* Rejected: two places would hold auth, and a
  wider cookie would be visible to MedScribe's same-origin PoC script.
- *Safety rules re-implemented in React.* Rejected: two implementations of urgency and wording would drift.

**Supersedes:** none. The Java UI under `web/adapter/in/ui` stays in force route by route until each route flips.
DR-42 (no-JS scope and budget), DR-43 (voice privacy) and DR-44 (new scope) are reserved for the PO decisions in plan
Part B.

## DR-41 | 2026-10-21 | Messages grammar, Mio, wordmark, legibility floor, the দেখেছি acknowledgement: the 2026-10-21 amendment

**Decision:** the PO-approved design for the rebuild is
[`docs/design/2026-10-21-approved/`](design/2026-10-21-approved/README.md) (phone and desktop prototypes and the
direction contract), with the PO's 2026-10-21 amendments to that contract. When a screen and that folder disagree **on
look and layout**, the folder wins until the PO approves a change, recorded as an amendment in its
`direction-contract.md`. The folder never wins on anything else: copy comes only from the `ui-*` and `messages_*`
bundles; urgency, display kind, wording, `tel:999` gating and every other safety rule come from Java (DR-40 A5); and
anything in plan Part B stays with the PO. Known places where the prototypes contradict those rules and must not
ship as drawn:
- the NOW banner "এখনই <name>কে ডাক্তারের কাছে নিয়ে যান", with the patient's name inside the safety sentence
  (`desktop.html`); the shipped `urgency.now` has no name and is read verbatim, and the change is open as Part B
  decision 3, whose recommendation keeps the shipped line;
- `tel:999` shown to every viewer; DR-40 A5 allows it only for +880 viewers (`viewer.bangladesh`);
- the scene bar, the canned chat answers, the timed "পড়া হয়েছে" receipts and the uncatalogued toasts (such as the
  দেখেছি toast), which the folder's README already marks as prototype devices.

Items 1–4 are the contract's 2026-10-21 amendment; item 5 is the PO's 2026-10-21 approval recorded in the rebuild
plan's Global Constraints, now also a dated line in `direction-contract.md`:

1. **Messages grammar.** The conversation follows Apple Messages exactly: grey received bubbles, teal (`#06788a`) sent
   bubbles, on a plain background. This replaces the contract's mist ground, white Medioo bubbles and soft-green family
   bubbles.
2. **Mio as drawn by the PO.** The mascot is the PO's Mio, used exactly as drawn, stethoscope included (PO,
   2026-10-20), from `mio.webp` and `mio-head.webp`. No redraw, no simplified or generated variant. The rebuild plan
   adds three motion rules: Mio moves only as a whole figure, never animates on a NOW result, and any looping motion
   plays at most twice.
3. **The vector wordmark.** The medioo wordmark is the vector master traced from the PO's `medioo.webp`, letters and
   colours unchanged: `medioo-light.svg` and `medioo-dark.svg`, chosen by theme, and `medioo-mono-ink.svg` /
   `medioo-mono-white.svg` for one-colour uses (WhatsApp profile picture, stamps, print).
4. **The legibility floor** (for a parent reading Bangla alone, as well as the adult child): every status is colour +
   icon or shape + word; no text under 12 px; secondary text at least 4.5:1 in both themes; axe reports zero violations
   on every routed page in both themes. The plan adds touch targets of at least 44 px, primary actions at least 48 px.
5. **The দেখেছি acknowledgement (PO-approved 2026-10-21).** The NOW banner is global, and "দেখেছি" **hides the banner
   only**. Stored urgency, the paper's NOW block and WhatsApp delivery are unchanged; a NOW paper the viewer has not
   acknowledged shows the banner again; the patient keeps the red mark. Two engineering rules make that hold
   (recorded here, not by the PO):
   - **Keyed to what was seen.** An acknowledgement stores the exact set of visible NOW documents at the moment of the
     tap, as `(document_id, urgency_assessed_at, release state)`. The banner shows again whenever a visible NOW
     document is not in that set. A *new* upload is only one way a paper becomes NOW: V18 lets urgency rise on an
     existing document (`routine`/`this_week` → `now`, or a NULL first assessment), the A11 sweep gives NOW to an
     older upload whose `explain()` threw, up to an hour later, and the review gate releases a held NOW paper later.
     In each of those the paper is not new, and it is the one most at risk of being missed.
   - **Per account holder, never per family.** The acknowledgement belongs to the member who tapped (member id) and is
     stored on the server, so it holds across that person's devices. A per-family acknowledgement would let one
     account holder's tap hide the global NOW banner from every other member of the family, including members who
     never saw it. Plan Part B decision 8 is still the PO's; its recommendation is now per account holder. If the PO
     picks per family anyway, that trade-off is recorded explicitly in this DR or a new one before B3 merges.
   - **Tests first, both directions (B3, W5):** an acknowledgement hides the banner for exactly the documents acked; a
     `this_week` → `now` raise, a sweep assessment or a later gate release on an existing paper after the ack shows
     the banner again; a new NOW upload shows it again; after member A acks, member B in the same family still gets
     the banner; and stored urgency, the NOW block and WhatsApp delivery are unchanged by any ack.

**Why:** the PO judged the earlier web UI toy-like and chose a grammar every family already reads fluently; WhatsApp
and Messages are the quality bar the contract names. Mio and the wordmark are the PO's own work and the brand, so the
rebuild uses them as made; the vector master is what MEDIOO.md's asset TODO asked for in place of the 246×88 raster
extracted from a downloaded page, and the TODO is cleared once W2c serves the SVG (Mio and its head stay rasters). The legibility floor states the elderly reader's needs as checks a test can fail on
rather than as taste. দেখেছি answers a real nuisance, a global red banner that cannot be put away once it has been
read, without touching hard constraint 2: nothing a family does lowers stored urgency (V18 forbids it in the schema),
so the acknowledgement is a display preference and not a change to the record.

**Alternatives:**
- *Keep the contract's original own-world (mist ground, white and soft-green bubbles).* Replaced by the PO's amendment.
- *A redrawn, simplified or animated-in-parts Mio.* Rejected: the PO's figure is used as drawn.
- *Keep the raster wordmark.* Rejected: it blurs on high-density screens and has no dark-ground or one-colour form.
- *দেখেছি that lowers the document's urgency or clears the patient's red mark.* Rejected: urgency is only ever raised
  (hard constraint 2, V18), and a family that has read the banner still needs to see which parent the NOW paper is for.
- *No acknowledgement at all.* Rejected by the PO on 2026-10-21.

**Supersedes:** amends DR-14 and its DR-15 amendments for the rebuilt web UI: the conversation surface is the
Messages grammar above rather than warm paper surfaces, and the wordmark asset is the vector master rather than
`/assets/medioo.webp`. Teal primary, red reserved for NOW, amber for THIS_WEEK and dark (not red) destructive actions
are unchanged. Records the direction contract's 2026-10-21 amendments in
`docs/design/2026-10-21-approved/direction-contract.md`: the first replaces its OWN-WORLD line; the second records
দেখেছি and the in-folder wordmark files.
