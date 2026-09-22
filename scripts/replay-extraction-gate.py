#!/usr/bin/env python3
"""
Replay every document's STORED extraction through the current retake gate.

No model calls, no cost, no network beyond one read-only query: the extraction JSON is already in the database,
so the gate's decision can be recomputed for every document ever processed. Rerun this whenever the gate
changes — thresholds, the expected-sections table, or the gate code itself.

It exists because the gate was wrong and nobody could see it. A prescription read at 0.85 with every medicine
correct was rejected as an unclear photo, because values[] — a section a prescription never carries — reported
0.0 and the min-section gate counted it. Replaying the six documents then in production showed 2 of 6 wrongly
retaken, all prescriptions: the artefact in every retake rate measured up to 2026-09-21 (DR pending).

Usage:
    set -a; source .env.supabase; set +a
    python3 scripts/replay-extraction-gate.py            # compare current gate against what was stored
    python3 scripts/replay-extraction-gate.py --json     # machine-readable, for a build report

Keep the constants below in step with BaymaxProperties.Extract. They are duplicated deliberately: this script
must be able to model a gate that is NOT the one currently deployed, which is the whole point of a replay.
"""
import argparse
import json
import os
import subprocess
import sys

MIN_CONFIDENCE_OVERALL = 0.80
MIN_CONFIDENCE_SECTION = 0.70

# BaymaxProperties.Extract.expectedSections — which sections a type must actually carry
EXPECTED_SECTIONS = {
    "lab_report": ["values"],
    "prescription": ["medicines"],
    "discharge_summary": [],
    "imaging_report": [],
    "other": [],
}

QUERY = """
SELECT left(id::text, 8), coalesce(document_type, 'other'), status,
       coalesce(extraction_json->'confidence'->>'overall', '-'),
       coalesce(extraction_json->'confidence'->>'values', '-'),
       coalesce(extraction_json->'confidence'->>'medicines', '-'),
       coalesce(extraction_json->'confidence'->>'follow_up', '-'),
       coalesce(extraction_json->'confidence'->>'clinical_context', '-'),
       coalesce(jsonb_array_length(extraction_json->'values'), 0),
       coalesce(jsonb_array_length(extraction_json->'medicines'), 0),
       coalesce(jsonb_array_length(extraction_json->'follow_up'), 0)
FROM baymax.document WHERE extraction_json IS NOT NULL ORDER BY created_at
"""


def fetch_rows():
    for var in ("PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD"):
        if not os.environ.get(var):
            sys.exit(f"{var} is not set — run: set -a; source .env.supabase; set +a")
    url = (f"postgresql://{os.environ['PGUSER']}:{os.environ['PGPASSWORD']}"
           f"@{os.environ['PGHOST']}:{os.environ['PGPORT']}/{os.environ['PGDATABASE']}")
    out = subprocess.run(["psql", url, "-At", "-F", "|", "-c", QUERY],
                         capture_output=True, text=True, check=True).stdout.strip()
    return [line.split("|") for line in out.split("\n") if line]


def number(text):
    return None if text == "-" else float(text)


def old_gate(overall, sections):
    """The gate before 2026-09-21: min across every reported section, empty or not."""
    if overall < MIN_CONFIDENCE_OVERALL:
        return "NEEDS_RETAKE", "overall"
    lowest = min((c for c, _ in sections.values() if c is not None), default=1.0)
    return ("NEEDS_RETAKE", "section") if lowest < MIN_CONFIDENCE_SECTION else ("DONE", "-")


def current_gate(overall, sections, document_type):
    """The gate now: an empty section counts only where the document type expects one."""
    if overall < MIN_CONFIDENCE_OVERALL:
        return "NEEDS_RETAKE", "overall"
    for section in EXPECTED_SECTIONS.get(document_type, []):
        if sections.get(section, (None, 0))[1] == 0:
            return "NEEDS_RETAKE", f"missing {section}"
    lowest = min((c for c, n in sections.values() if c is not None and n > 0), default=1.0)
    return ("NEEDS_RETAKE", "section") if lowest < MIN_CONFIDENCE_SECTION else ("DONE", "-")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    args = parser.parse_args()

    results, disagreements = [], 0
    for row in fetch_rows():
        doc, dtype, stored, o, v, m, f, cc, n_v, n_m, n_f = row
        sections = {
            "values": (number(v), int(n_v)),
            "medicines": (number(m), int(n_m)),
            "follow_up": (number(f), int(n_f)),
            "clinical_context": (number(cc), 1),
        }
        before, _ = old_gate(float(o), sections)
        now, why = current_gate(float(o), sections, dtype)
        if before != now:
            disagreements += 1
        results.append({"doc": doc, "type": dtype, "stored": stored,
                        "old_gate": before, "current_gate": now, "reason": why,
                        "changed": before != now})

    if args.json:
        print(json.dumps({"documents": len(results), "changed": disagreements,
                          "results": results}, indent=2))
        return

    print(f"{'doc':10}{'type':16}{'stored':14}{'old':14}{'current':14}reason")
    print("-" * 82)
    for r in results:
        flag = "  <-- CHANGED" if r["changed"] else ""
        print(f"{r['doc']:10}{r['type']:16}{r['stored']:14}{r['old_gate']:14}"
              f"{r['current_gate']:14}{r['reason']}{flag}")
    print("-" * 82)
    print(f"{len(results)} documents, {disagreements} decided differently by the current gate")
    if disagreements:
        print("Each CHANGED row is a family who was asked to resend a photo we had already read.")


if __name__ == "__main__":
    main()
