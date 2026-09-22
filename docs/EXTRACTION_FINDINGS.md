# Extraction and urgency: what batch 2 exposed

Findings from ten printed lab reports (batch 2, corpus git-ignored). **Code behaviour only** — no patient
values here; the report-by-report detail with real numbers lives beside the corpus in
`docs/testset/batch2/FINDINGS.md`, which is ignored with the images.

Nothing here has been changed. Several items must not be changed until the doctor's per-marker threshold
list exists (week-0 blocker).

**Corpus caveat: only half of batch 2 is real Bangladeshi reports.** Two are vendor demo templates and three
are from other countries. Any per-country number rests on a handful of documents and must not be
over-read. Batch 3 adds more.

## 1. Qualitative references are invented as numeric ranges

`extraction-schema.json` gives a value only `ref_low`, `ref_high` and `flag`. There is **no `ref_text`, no
`band`, no `status`**. A reference printed as a word — "Nil", "Negative", "Clear" — has nowhere to go, so the
model supplies a numeric range that is not on the page, and that invented range then drives the verdict.

**Needed:** `ref_text` in the schema; numeric range fields left null when the printed reference is not
numeric.

## 2. `flag` and `status` are different questions

`flag` is what the page printed (an H, an L, a colour). `status` is our own judgement of the value against
the applicable range. Most labs print no marks at all, so `status` is what urgency depends on. Extraction
currently conflates them: it writes a `flag` it computed itself, including on results it could not
legitimately compare.

They are separate fields in the label and must be scored as separate sections, with **missed abnormals
reported separately from false abnormals** — a missed one is a missed urgency, a false one is a false alarm.

## 3. Range-valued results parse to null

`UrgencyService.number()` strips everything but digits, `.` and `-`. A result printed as a range yields
nothing, so urgency ignores it — correct. But extraction had already assigned it a flag by its own
comparison, which the label has to correct.

A value with its flag in the same cell (`"80.30 L"`) parses to the number by luck: the strip approach
discards the letter. It is **not robust** — a European `"1,5"` would read as 15 — but batch 2 contains no
counter-example, so this is recorded, not rushed.

## 4. Trends are computed across units

`PostgresNudgeDataAdapter.observationsOf` selects `WHERE patient_id = :p AND canonical_name = :c` with **no
unit predicate**. The same marker reported in different units in different countries is one series to this
query, and a unit change alone could fire a rising-marker nudge. DR-17's distinct-dates-over-14-days rule
makes it less likely, not impossible.

**Fix, with tests in both directions:** group by `(canonical_name, unit)`; never compute a trend across a
unit change.

## 5. The 2x stopgap is wrong in BOTH directions

Previously documented as over-escalating. Batch 2 shows it also **under-escalates**: a marker only modestly
above its range can be a same-day clinical result, and the 2.0 critical multiple puts it at THIS_WEEK. No
single multiplier can serve both a non-specific inflammatory marker and an electrolyte.

**The docs must stop describing the stopgap as only over-escalating.** Under-escalation violates hard
constraint 2, and batch 2 has a concrete instance.

Also for the doctor's list: **which markers may escalate urgency at all**, not merely their thresholds. A
single lipid panel should not escalate beyond ROUTINE — lipids are chronic risk markers whose signal belongs
to the trend rule.

## 6. Selection among several printed ranges is undefined

Reports print sex-specific and age-specific ranges for the same marker. Nothing in the code chooses: the
range fields hold whatever the model put there, and the wrong choice can make a genuinely abnormal value
read as normal.

**Selection must be deterministic** from the patient's sex/age — taken from the report, else the profile —
and never the first line printed. Where the attribute is unknown, evaluate every applicable range: if they
agree, use the verdict; if they disagree, `status` is undetermined. An undetermined value withdraws the
"within normal range" claim, exactly as a dropped value does under DR-28.

## 7. Dates: right on every checkable case, but ambiguity is resolved silently

The schema asks only for "ISO date as written on the document" — there is **no day-first instruction**, so
the convention is the model's alone. On this corpus it was correct every time it could be checked: day-first
where the page proved it, null where the year was unreadable, Gregorian where two calendars appeared.

The finding is the case it **could not** resolve: a date whose day and month are both plausible was resolved
anyway, with nothing on the page to justify the choice. A wrongly-placed report corrupts timeline order and
every trend that joins it. `document_date` needs an uncertain state rather than a coin-flip, and the count of
affected documents across batches 1 and 2 should be established before that is designed.

Day-first should be stated in the schema rather than left to judgement, and Bangla-calendar dates added to
the date-parsing tests.

## 8. Conventions the labels now carry

- `Upto 37` → no lower bound invented; wording kept in `ref_text`.
- Tiered references → `band` holds the printed tier, wording in `ref_text`.
- Direction-reversed markers (a high value that is protective) — if read as an upper limit, a healthy result
  escalates. Not yet checked in code.
- Relative references ("higher than X goal") → `status` null.
- No printed reference → `status` null, and the normal-range claim is withdrawn.
- A printed interpretation band is not a per-value range.
- **Units are transcribed verbatim, never converted** — a page printing an unusual unit would make any
  conversion wrong by orders of magnitude.
- Missing year → `document_date` null. Never inferred from today.
- Bold is styling, not an abnormality marker.
- Value-level comments ("suggest repeat if clinically indicated") have no field and are not surfaced.

## 9. Open questions

- Does extraction capture colour-only marks at all?
- Row-shift on reports with empty result cells: a value assigned to the row above or below. Invented values
  must be scored separately and reported by name — an invented creatinine is a false trend input.
