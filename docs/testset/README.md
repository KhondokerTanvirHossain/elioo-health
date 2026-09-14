# Evaluation corpus

**These are real patient documents. This whole directory is git-ignored and must never be committed.**
If you ever see a file from here in `git status` as staged or tracked, stop and remove it from the index
before committing anything.

The evaluation script reads the documents here, runs them through the extraction pipeline, and scores the
result against a hand-labelled expectation. It prints **counts, cost, latency and model only** — never a
name, a value or any free text. Per-document detail goes to `report.json` in this directory, which is
git-ignored along with everything else.

## Layout

```
docs/testset/
  10.jpg, 26.pdf, Type-p2.jpg, ...      the documents
  expected/10.json                      the hand-labelled expectation for 10.jpg
  expected/26.json                      ... same base name, .json extension
  report.json                           written by the eval script (git-ignored)
```

A document with no `expected/<name>.json` is skipped and reported as unlabelled, so you can label them a
few at a time.

## What to label

Only the fields below are scored. Everything else the pipeline extracts is ignored by the evaluation, so
do not spend time on it.

| Field | Scored as |
|---|---|
| `document_type` | exact match |
| `document_date` | exact match on the ISO date |
| `values[]` | matched on `name` (case- and space-insensitive); `value` and `unit` must both match |
| `medicines[]` | matched on `name`; `dose_text` and `frequency_text` must match |
| `follow_up[]` | matched on `instruction` (loose); `due_date` must match |

Accuracy is `correct items / expected items` per document, then averaged over the printed and handwritten
sets separately. A value the pipeline invents that you did not label counts against precision and is
reported separately, so label everything legible on the page.

`printed` or `handwritten` goes in the `set` field; that is what splits the two accuracy tables.

## Example: lab report

```json
{
  "set": "printed",
  "document_type": "lab_report",
  "document_date": "2026-03-14",
  "values": [
    { "name": "HbA1c", "value": "8.2", "unit": "%" },
    { "name": "Fasting Blood Sugar", "value": "9.1", "unit": "mmol/L" },
    { "name": "S. Creatinine", "value": "1.3", "unit": "mg/dL" }
  ],
  "medicines": [],
  "follow_up": []
}
```

## Example: prescription

```json
{
  "set": "handwritten",
  "document_type": "prescription",
  "document_date": "2026-02-02",
  "values": [],
  "medicines": [
    { "name": "Napryn", "dose_text": "500 mg", "frequency_text": "1+0+1" },
    { "name": "Beklo", "dose_text": "10 mg", "frequency_text": "0+0+1" }
  ],
  "follow_up": [
    { "instruction": "Recheck creatinine", "due_date": "2026-03-02" }
  ]
}
```

## Example: discharge summary

```json
{
  "set": "printed",
  "document_type": "discharge_summary",
  "document_date": "2026-01-20",
  "values": [
    { "name": "Haemoglobin", "value": "10.4", "unit": "g/dL" }
  ],
  "medicines": [
    { "name": "Furotil", "dose_text": "40 mg", "frequency_text": "1+0+0" }
  ],
  "follow_up": [
    { "instruction": "Follow up in cardiology OPD", "due_date": "2026-02-03" }
  ]
}
```

## Example: imaging report

Imaging is scored on the written report only; the pipeline never interprets an image beyond what the
radiologist wrote.

```json
{
  "set": "printed",
  "document_type": "imaging_report",
  "document_date": "2026-04-05",
  "values": [],
  "medicines": [],
  "follow_up": [
    { "instruction": "Repeat ultrasound in 6 months", "due_date": "2026-10-05" }
  ]
}
```

## Notes on labelling

- Copy the value exactly as printed, including the decimal point: `8.2`, not `8.20`.
- Units as written on the page: `mmol/L`, `mg/dL`, `%`.
- A value you cannot read confidently is one the pipeline should not claim either. Leave it out and it
  will not count against accuracy, but if the pipeline reports it, that shows up as a precision miss.
- Dates: ISO `YYYY-MM-DD`. If the page shows only a month, leave `document_date` null.
- `frequency_text` in the Bangladeshi convention (`1+0+1`) is fine; label what the page says.
