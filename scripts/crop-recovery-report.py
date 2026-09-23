#!/usr/bin/env python3
"""
Value-by-value crop recovery between two runs of the same corpus.

    scripts/crop-recovery-report.py <before.txt> <after.txt>

Each input is one "stem|document_id|status" line per document, as produced by the measurement runner. The
two runs MUST come from the same build with the same gate, differing only in
baymax.extract.recover-crops-from-image — otherwise a document that changed gate status shows up as recovery
or loss that has nothing to do with cropping. The first attempt at this measurement compared two runs whose
gates differed and credited a document with recovering three values when it had simply failed the confidence
gate and never been cropped at all.

Documents are matched by stem, never by position: the shell glob sorts lab1, lab10, lab2, ..., and reading
that order as lab1, lab2, ... put every name from lab10 onwards one row out in an earlier report.

Prints names of values, which for this corpus are marker names, not patient data. Counts and names only —
never a reading.
"""
import os
import subprocess
import sys

PG = ["psql", "-h", os.environ.get("PGHOST", "localhost"),
      "-p", os.environ.get("PGPORT", "5433"),
      "-U", os.environ.get("PGUSER", "medscribe"),
      "-d", os.environ.get("PGDATABASE", "medscribe"),
      "-P", "pager=off", "-At", "-F", "|", "-c"]


def query(sql):
    env = dict(os.environ, PGPASSWORD=os.environ.get("PGPASSWORD", "medscribe"))
    out = subprocess.run(PG + [sql], capture_output=True, text=True, env=env)
    if out.returncode != 0:
        sys.exit(f"query failed: {out.stderr.strip()}")
    return [line.split("|") for line in out.stdout.strip().splitlines() if line]


def read_run(path):
    """stem -> (document_id, status), from the runner's output."""
    runs = {}
    with open(path) as f:
        for line in f:
            parts = line.strip().split("|")
            if len(parts) == 3:
                runs[parts[0]] = (parts[1], parts[2])
    return runs


def extracted_names(document_id):
    rows = query(f"""
        select coalesce(v->>'name','(unnamed)')
        from baymax.document d,
             lateral jsonb_array_elements(d.extraction_json->'values') v
        where d.id = '{document_id}'""")
    return [r[0] for r in rows]


def shown_names(document_id):
    rows = query(f"select name from baymax.observation where document_id = '{document_id}'")
    return [r[0] for r in rows]


def dropped(document_id):
    """Names read but never shown. A multiset difference: the same marker can legitimately appear twice."""
    shown = shown_names(document_id)
    out = []
    for name in extracted_names(document_id):
        if name in shown:
            shown.remove(name)
        else:
            out.append(name)
    return out


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    before, after = read_run(sys.argv[1]), read_run(sys.argv[2])

    print(f"{'doc':<7} {'BEFORE':<14} {'AFTER':<14} {'ext':>4} {'shown':>6} {'->':>3} {'shown':>6} {'recovered':>10}")
    print("-" * 72)

    total_before = total_after = comparable = 0
    recovered_names, still_lost = [], []

    for stem in sorted(before, key=lambda s: (len(s), s)):
        if stem not in after:
            print(f"{stem:<7} present in before only — skipped")
            continue
        b_id, b_status = before[stem]
        a_id, a_status = after[stem]

        b_drop, a_drop = dropped(b_id), dropped(a_id)
        b_ext, a_ext = len(extracted_names(b_id)), len(extracted_names(a_id))
        b_shown, a_shown = len(shown_names(b_id)), len(shown_names(a_id))

        # Only documents that reached the cropper in BOTH arms can be compared. A document retaken in
        # either arm never cropped anything, so its zero drops mean "not attempted", not "nothing lost".
        both_cropped = b_status == "DONE" and a_status == "DONE"
        note = ""
        if not both_cropped:
            note = "  (not comparable: not DONE in both arms)"
        else:
            comparable += 1
            total_before += len(b_drop)
            total_after += len(a_drop)
            remaining = list(a_drop)
            for name in b_drop:
                if name in remaining:
                    remaining.remove(name)
                    still_lost.append((stem, name))
                else:
                    recovered_names.append((stem, name))

        delta = len(b_drop) - len(a_drop) if both_cropped else 0
        print(f"{stem:<7} {b_status:<14} {a_status:<14} {a_ext:>4} {b_shown:>6} {'->':>3} {a_shown:>6} "
              f"{delta:>+10}{note}")

    print()
    print(f"comparable documents: {comparable}")
    print(f"dropped BEFORE: {total_before}   dropped AFTER: {total_after}   "
          f"net recovered: {total_before - total_after:+d}")

    print(f"\nRECOVERED ({len(recovered_names)}):")
    for stem, name in recovered_names or [("", "(none)")]:
        print(f"  {stem:<7} {name}")

    print(f"\nSTILL LOST ({len(still_lost)}):")
    for stem, name in still_lost or [("", "(none)")]:
        print(f"  {stem:<7} {name}")


if __name__ == "__main__":
    main()
