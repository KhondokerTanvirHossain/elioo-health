# Baymax decision records

Chronological. Later records supersede earlier ones where noted. Format: `DR-n | date | decision | why | supersedes`.
Add new records at the end; never rewrite an old one — add a superseding record instead.

## DR-1 | 2026-09-12 | (not recorded in this repo)

Reserved. Tanvir to supply the text; do not infer it.

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
