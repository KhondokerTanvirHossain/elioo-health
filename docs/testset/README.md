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

**No `generic_name` field in v1.** If the corpus shows that brand-to-generic mapping matters often enough,
that is a finding to report, not a field to add quietly.

Empty string and absent mean the same thing. Write `""` or omit the key, whichever is easier.

## What is scored

The headline accuracy is `values`, `medicines` and `follow_up`. `clinical_context` is scored and reported
**informationally** — it matters for the Bangla explanation later, but it is free text and a looser match,
so it does not move the headline number.

| Field | Matched on |
|---|---|
| `document_type` | exact |
| `document_date` | exact, ISO |
| `values[]` | `name`, then `value` and `unit` must both agree |
| `medicines[]` | `name`, then `dose_text`, `frequency_text` and `timing_text` must all agree |
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
    { "name": "Blood Pressure", "value": "130/80", "unit": "mmHg" }
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
