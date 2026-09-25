#!/usr/bin/env python3
"""
Run-to-run variance: how much does extraction change when nothing else does?

    scripts/variance_report.py <runs.txt>

Input is "stem|document_id|status|run" per line, several runs of the same corpus on one build. Reads the
stored extraction and the cost log; makes no model calls.

This exists because the 97/99 reading baseline is ONE run per document and may describe a good day. lab2 read
29 values in one run and 14 in the next — same image, same build, minutes apart. Until the spread is known,
every accuracy number in BASELINE.md carries an unmeasured error bar.

Prints marker names and counts, never a reading.
"""
import os
import re
import statistics
import subprocess
import sys
from collections import defaultdict

PG = ["psql", "-h", os.environ.get("PGHOST", "localhost"),
      "-p", os.environ.get("PGPORT", "5433"),
      "-U", os.environ.get("PGUSER", "medscribe"),
      "-d", os.environ.get("PGDATABASE", "medscribe"),
      "-P", "pager=off", "-At", "-F", "|", "-c"]

GATE = 0.80  # baymax.extract.min-confidence-overall


def query(sql):
    env = dict(os.environ, PGPASSWORD=os.environ.get("PGPASSWORD", "medscribe"))
    out = subprocess.run(PG + [sql], capture_output=True, text=True, env=env)
    if out.returncode != 0:
        sys.exit(f"query failed: {out.stderr.strip()}")
    return [line.split("|") for line in out.stdout.strip().splitlines() if line]


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)

    runs = defaultdict(list)
    for line in open(sys.argv[1], encoding="utf-8"):
        parts = line.strip().split("|")
        if len(parts) == 4:
            runs[parts[0]].append((parts[1], parts[2], int(parts[3])))

    print("# Run-to-run variance — three runs of each document, one build\n")
    print("Same images, same build, same gate. Only the model's reading differs.\n")
    print("| Doc | Values read | Spread | Confidence | Status |")
    print("|---|---|---|---|---|")

    crossings, all_spreads = [], []
    unstable_values = {}

    for stem in sorted(runs, key=lambda s: (len(s), s)):
        counts, confidences, statuses, value_sets = [], [], [], []
        for document_id, status, _ in sorted(runs[stem], key=lambda r: r[2]):
            rows = query(f"""
                select coalesce(jsonb_array_length(d.extraction_json->'values'), 0),
                       coalesce(d.extraction_json->'confidence'->>'overall', ''),
                       coalesce((select string_agg(v->>'name', '~' order by v->>'name')
                                 from jsonb_array_elements(d.extraction_json->'values') v), '')
                from baymax.document d where d.id = '{document_id}'""")
            if not rows:
                continue
            count, confidence, names = rows[0]
            counts.append(int(count))
            confidences.append(float(confidence) if confidence else None)
            statuses.append(status)
            value_sets.append(set(n for n in names.split("~") if n))

        if not counts:
            continue
        spread = max(counts) - min(counts)
        all_spreads.append(spread)
        present = [f"{c}" for c in counts]
        shown_conf = "/".join(f"{c:.2f}" if c is not None else "—" for c in confidences)
        flag = " **⚠**" if spread else ""
        print(f"| {stem} | {'/'.join(present)} | {spread}{flag} | {shown_conf} | "
              f"{'/'.join(s[:4] for s in statuses)} |")

        # A confidence that crosses the 0.80 gate between runs changes the DECISION, not just the numbers.
        real = [c for c in confidences if c is not None]
        if real and min(real) < GATE <= max(real):
            crossings.append((stem, min(real), max(real)))

        if len(value_sets) > 1:
            everywhere = set.intersection(*value_sets)
            anywhere = set.union(*value_sets)
            sometimes = anywhere - everywhere
            if sometimes:
                unstable_values[stem] = (len(everywhere), sorted(sometimes))

    print(f"\n- documents whose value count moved between runs: "
          f"**{sum(1 for s in all_spreads if s)} of {len(all_spreads)}**")
    print(f"- largest spread on one document: **{max(all_spreads)} values**")
    print(f"- mean spread: **{statistics.mean(all_spreads):.1f} values**")

    print("\n## Confidence crossing the 0.80 gate between runs\n")
    if not crossings:
        print("_None: no document changed gate decision because of confidence alone._")
    else:
        print("These documents would be RETAKEN on one run and accepted on another, same image:\n")
        print("| Doc | lowest | highest |")
        print("|---|---|---|")
        for stem, low, high in crossings:
            print(f"| {stem} | {low:.2f} | {high:.2f} |")

    print("\n## Values present in some runs and not others\n")
    if not unstable_values:
        print("_None._")
    else:
        print("The count is not the whole story: these are the specific markers that came and went.\n")
        for stem, (stable, sometimes) in unstable_values.items():
            print(f"- **{stem}** — {stable} values in every run, {len(sometimes)} intermittent: "
                  f"{', '.join(sometimes[:12])}{' …' if len(sometimes) > 12 else ''}")


if __name__ == "__main__":
    main()
