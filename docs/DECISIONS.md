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

## DR-14 | 2026-09-18 | Visual direction for the Baymax web app

**Decision:** Visual direction for the Baymax web app: white / soft red / warm neutral palette, rounded and calm
throughout, nothing clinical. Bangla default with an English toggle. Name "Baymax" stands through the open-source
MVP; public brand remains open before commercial launch (§8). Tagline and mascot asset are supplied by Tanvir.

**Why:** the product should feel like a presence, not a form.

**Supersedes:** none.

*Engineering note:* BMX-6b applies this as one token stylesheet (`baymax/src/main/resources/baymax/ui/baymax.css`:
colour, radius, spacing, type scale) consumed by the landing page and every app page; no colour or radius literal
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

## DR-29 | 2026-09-22 | Extraction transcribes references, never computes them

**Decision:** Extraction transcribes references, never computes them. `ref_low`/`ref_high` only when the page
prints numbers; any other reference goes verbatim into `ref_text`; tiered references record the printed
`band`. `flag` records only a mark the page prints. **Abnormal judgement (`status`) is computed in code,
never by the model.**

**Why:** the model invented numeric ranges for word references — lab2's "Nil" stored with `ref_low` 0 and
`ref_high` 2, a range not on the page — and wrote flags from its own comparisons. Fabricated ranges were
driving urgency with nothing to flag them.

**Supersedes:** none.

*Engineering note.* The schema gave a value only `ref_low`, `ref_high` and `flag`, so a reference printed as
a word had nowhere to go and the model filled the numeric fields anyway. Three separate questions were
collapsed into one: what the page printed as a reference (`ref_text`, `band`), what mark the page printed
(`flag`), and whether the value is abnormal (`status`). The label format now keeps them apart and the scorer
scores each as its own section — with **missed abnormals reported separately from false ones**, because a
missed low is a missed urgency and a false one is a family sent to a doctor for nothing.

Batch 2 also showed why the urgency *rule* must be reported beside the level: a sex-range misselection
removed a real low haemoglobin while both label and extraction still read THIS_WEEK overall. Comparing levels
alone would have called that a match.
