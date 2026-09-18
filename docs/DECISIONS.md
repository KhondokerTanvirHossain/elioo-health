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
