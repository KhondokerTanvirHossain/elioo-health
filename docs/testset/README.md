# Evaluation corpus

**These are real patient documents. This whole directory is git-ignored except this file, and must never be
committed.** If you ever see a document or a label in `git status` as staged, stop and remove it from the
index before committing anything.

The evaluation script reads the documents here, runs them through the extraction pipeline, and scores the
result against a hand-labelled expectation. It prints **counts, accuracy, cost, latency and model only** —
never a name, a value or any free text. Per-document detail goes to `report-<run>.json` here, git-ignored
along with everything else.

## Layout

```
docs/testset/
  1.png, 2.png, ...          the documents
  expected/1.json            the label for 1.png — same base name, .json extension
  report-<run>.json          written by the eval script (git-ignored)
```

A document with no label is skipped and reported as unlabelled, so you can label a few at a time.

## Labelling rules

**Every label is verified against the image by a human.** A model may help you draft one, but nothing is
accepted unchecked: the labels are the ground truth the pipeline is measured against, so a wrong label is
worse than a missing one. If you cannot read something confidently, leave it out rather than guess — the
pipeline is not supposed to claim it either.

**Verbatim means verbatim.** `dose_text`, `frequency_text`, `timing_text`, `duration_text` are copied
exactly as they appear:

- No expansion: `1+0+1` stays `1+0+1`, never "once in the morning and once at night".
- No generic or molecule names: `Tab. Napa` stays `Tab. Napa`, never "Paracetamol".
- No normalisation: `5 mg+20 mg` keeps its spacing and its plus sign.
- No translation: Bangla stays Bangla. `রাত` is `রাত`, not "night".

**Numerals are transcribed in whatever script the page uses.** A prescription written `১+০+১` is labelled
`১+০+১`, not `1+0+1`; one written `1+0+1` stays `1+0+1`. This follows from verbatim, and it is also what
the pipeline stores, because the stored text must match the crop beside it (PO decision, 2026-09-17). The
scorer folds every digit script to ASCII on both sides before comparing, so a label in the wrong script
does not change the score — but it is still a wrong label, and the first ten labels were all written in
ASCII where the pages use Bangla. Anything downstream that needs `1+0+1` as a number (nudge scheduling,
BMX-6) gets a derived, normalised field; the verbatim record is never normalised in place.

> page `১ + ০ + ১` → `"frequency_text": "১ + ০ + ১"` · page `৩ মাস` → `"duration_text": "৩ মাস"`

**A value's `name` is what the page calls it; the marker id carries the meaning.** A page that says
`BP 130/80` is stored as two values both named `BP`, with `canonical_name` `bp_systolic` and
`bp_diastolic` assigned by the pipeline from `baymax.markers`. Labels may name the marker directly
(`BP Systolic`, `BP Diastolic`, or any alias in that table): the scorer matches a label name against the
model's `canonical_name` through the same alias table, so both spellings score. Do not invent a name the
page does not use for a value that is not a canonical marker — there is no alias to match it through.

**A medicine's `name` stops before the strength.** `Tab. Thyrox` with `dose_text` `25 mcg`, not
`Tab. Thyrox 25 mcg`. The model sometimes folds the strength into the name; the scorer accepts that when
the trailing tokens equal the dose, so it costs nothing, but the label should keep them apart. Dropping
the form (`Amilin` for `Tab. Amilin`) is not accepted — that is a transcription error, not a formatting one.

**Transcription, not interpretation.** Label what the page says, not what it implies.

- If there is no diagnosis line, `diagnosis` is `[]`. A prescription for insulin is not a diagnosis of
  diabetes.
- A truncated line stays truncated: `"Amenorrhoea due to"` is labelled exactly that way.
- Do not connect items. A diagnosis, a medicine and a test on one page are three separate transcriptions.

**Follow-up dates are resolved.** The instruction stays verbatim, and `due_date` is the absolute date it
means, worked out against `document_date`:

> `document_date` 2026-08-06, instruction `"৩ মাস পর আসবেন"` → `due_date` `"2026-11-06"`

`due_date` is empty only when the page states no timing at all. The nudge engine reads this field, so a
missing date here becomes a reminder that never fires.

**Blood pressure is two entries, never one.** A page that reads `130/80 mmHg` is labelled as `BP Systolic`
= `130` and `BP Diastolic` = `80`, two separate rows in `values[]`. This is not a formatting preference: the
canonical markers in `baymax.markers` are `bp_systolic` and `bp_diastolic` as distinct series, because trend
detection has to see systolic move on its own. A compound `"130/80"` label scores a correct extraction as two
misses and an invented item — which is exactly what happened across all six BP documents in the first eval,
and it cost the run its credibility before anyone looked at the model.

The same test applies to any other reading a page writes as one token but the system stores as two. If the
marker list splits it, the label splits it.

**No `generic_name` field in v1.** If the corpus shows that brand-to-generic mapping matters often enough,
that is a finding to report, not a field to add quietly.

Empty string and absent mean the same thing. Write `""` or omit the key, whichever is easier.

## What this corpus is

**Batch 1 is prescriptions only** — ten printed prescriptions, all from the same clinic style. Read every number
it produces with that in mind:

- The `values[]` score is **not a lab-report measurement**. The values here are blood-pressure readings written
  on prescriptions. Reference ranges, flags and trend detection are unmeasured on real documents until batch 2.
- Only 1 of 10 documents states a follow-up timing, so **`follow_up` is excluded from the headline** until a
  corpus with follow-ups exists (the scorer counts it once at least 5 are labelled). Its 0/1 here was a corpus
  fact, not a pipeline score — and the one item was in fact transcribed by every model and lost to crop
  resolution, which the `dropped` column now shows.
- Batch 2 (~10 printed lab reports) and batch 3 (~10 handwritten prescriptions) are deferred; both gate pilot
  week 0, not the build.

## What is scored

The headline accuracy is `values`, `medicines` and — once the corpus labels at least 5 of them — `follow_up`. `clinical_context` is scored and reported
**informationally** — it matters for the Bangla explanation later, but it is free text and a looser match,
so it does not move the headline number.

| Field | Matched on |
|---|---|
| `document_type` | exact |
| `document_date` | exact, ISO |
| `values[]` | `name`, or the model's `canonical_name` via the `baymax.markers` aliases; then `value` and `unit` must both agree |
| `medicines[]` | `name` (a strength folded into the name is tolerated, a dropped form is not), then `dose_text`, `frequency_text` and `timing_text` must all agree |

Before every comparison both sides are folded: digits of any script to ASCII, case, and — in non-Latin
text — punctuation and its spacing (`সকাল-রাত` is `সকাল - রাত`). A dropped clause is still a miss:
`সকাল-রাত` does not match `সকাল-রাত, খাবারের পরে`.
| `follow_up[]` | `instruction` loosely, then `due_date` exactly |
| `clinical_context` | per section, loose containment; invented `diagnosis` lines are called out separately |

An item the pipeline reports that you did not label counts against precision and is reported separately, so
label everything legible on the page.

`set` is `printed` or `handwritten`; that is what splits the two accuracy tables.

## The shape

```json
{
  "set": "printed",
  "document_type": "prescription",
  "document_date": "2026-09-14",
  "values": [
    { "name": "BP Systolic", "value": "130", "unit": "mmHg" },
    { "name": "BP Diastolic", "value": "80", "unit": "mmHg" }
  ],
  "clinical_context": {
    "chief_complaint": [
      { "text": "Lt hemisensory disturbance", "duration": "" }
    ],
    "history": [
      { "text": "Hypertension" },
      { "text": "Diabetes Mellitus" },
      { "text": "H/O Stroke (ICH)" }
    ],
    "examination": [
      { "text": "No focal neurological signs" }
    ],
    "diagnosis": [],
    "investigations_advised": [],
    "advice": [
      { "text": "নিয়মিত ঔষধ খাবেন" },
      { "text": "চিনি ও মিষ্টি খাবার খাবেন না" }
    ],
    "referral": { "text": "" }
  },
  "medicines": [
    {
      "name": "Tab. Bizoran",
      "dose_text": "5 mg+20 mg",
      "route": "",
      "frequency_text": "0+0+1",
      "timing_text": "রাত",
      "duration_text": "চলবে"
    }
  ],
  "follow_up": [
    { "instruction": "৩ মাস পর আসবেন", "due_date": "2026-12-14" }
  ]
}
```

### A lab report

Lab reports carry values and usually no medicines. Do not pad the empty sections.

```json
{
  "set": "printed",
  "document_type": "lab_report",
  "document_date": "2026-03-14",
  "values": [
    { "name": "HbA1c", "value": "8.2", "unit": "%" },
    { "name": "S. Creatinine", "value": "1.3", "unit": "mg/dL" }
  ],
  "clinical_context": { "diagnosis": [], "advice": [] },
  "medicines": [],
  "follow_up": []
}
```

### A discharge summary

```json
{
  "set": "printed",
  "document_type": "discharge_summary",
  "document_date": "2026-01-20",
  "values": [
    { "name": "Haemoglobin", "value": "10.4", "unit": "g/dL" }
  ],
  "clinical_context": {
    "diagnosis": [{ "text": "Acute coronary syndrome" }],
    "advice": [{ "text": "Salt restriction" }],
    "referral": { "text": "Cardiology OPD" }
  },
  "medicines": [
    {
      "name": "Tab. Furotil",
      "dose_text": "40 mg",
      "route": "oral",
      "frequency_text": "1+0+0",
      "timing_text": "খাওয়ার পর",
      "duration_text": "1 month"
    }
  ],
  "follow_up": [
    { "instruction": "Follow up in 2 weeks", "due_date": "2026-02-03" }
  ]
}
```

### An imaging report

Scored on the radiologist's written report only; the pipeline never interprets the image itself.

```json
{
  "set": "printed",
  "document_type": "imaging_report",
  "document_date": "2026-04-05",
  "values": [],
  "clinical_context": {
    "examination": [{ "text": "Mild hepatomegaly. No focal lesion." }],
    "diagnosis": []
  },
  "medicines": [],
  "follow_up": [
    { "instruction": "Repeat ultrasound in 6 months", "due_date": "2026-10-05" }
  ]
}
```

## Labels: hand-written, or model-drafted then human-verified

Batch 1 (`1.png`–`10.png`, `expected/*.json`) was hand-labelled from scratch. Those files carry no
`verified` key and are scored as-is — they are the most trustworthy labels here.

Batch 2 (`batch2/lab1.png`–`lab10.png`) is too dense to hand-label from scratch, so the labels were
**drafted by the model and must be verified by a human before they mean anything**:

1. `scripts/draft-labels.sh docs/testset/batch2` runs extraction once and writes
   `batch2/expected/lab<n>.json`, each carrying `"verified": false` and a `_draft` block with the model's
   own confidence per section.
2. A human reads every field against the image — value, unit, `ref_low`, `ref_high`, `flag` — corrects what
   is wrong, and sets `"verified": true` with `"verified_by"`.
3. Only then can it be scored.

**`scripts/score_extraction.py` refuses to score a file that says `"verified": false`,** and there is no
flag to bypass it. Scoring a model against its own unchecked output reports the model's agreement with
itself as accuracy: a number that looks like a measurement and is not one. The refusal is the only thing
standing between a drafted label and that number.

A label with no `verified` key at all is treated as hand-written (batch 1). The key marks model-drafted
output; it does not invalidate human work that predates it.

**Do not run `evaluate-extraction.sh` without `TESTSET=`.** It globs `$TESTSET/*.png`, so from the default
`docs/testset` it would run batch 1 and batch 2 together — twice the cost, two corpora in one report. Use
`TESTSET=docs/testset/batch2`.

## Batch 2 findings

Code-level findings from the ten lab reports — schema gaps, the urgency stopgap being wrong in both
directions, trends computed across units, range selection, date ambiguity — are in
[../EXTRACTION_FINDINGS.md](../EXTRACTION_FINDINGS.md), which carries no patient values and is tracked.

The report-by-report detail **with real values** is in `batch2/FINDINGS.md`, which is git-ignored with the
images. It stays out of the repository for the same reason the images do.
