#!/usr/bin/env python3
"""
The guard that stands between a drafting run and a human's verification work.

    python3 scripts/test_draft_label_guard.py

Why this exists: `draft-labels.sh` was run against a directory holding ten labels Tanvir had verified field
by field against the images, and it overwrote all ten with fresh drafts saying `"verified": false`. The
corpus is git-ignored because it holds real patient data, so there was no commit to recover from and no
backup. The verification — the single thing that makes every accuracy number in BASELINE.md mean anything —
was destroyed by the tool whose output those numbers describe.

Same shape as the flattering-failure family in CLAUDE.md, one level up: not a check that could not fail, but
a writer with no check at all, pointed at the measurement's own foundation.

Both directions, because a one-directional guard hides the dangerous half:
  - a verified label is NEVER overwritten, and the run fails loudly rather than skipping quietly
  - an unverified draft IS overwritten, or the drafting tool does nothing on a second run
  - --force overwrites a verified label, because the escape hatch must exist and must be explicit
  - every write is preceded by a backup of the file being replaced
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
DRAFT_LABEL = os.path.join(HERE, "draft_label.py")

RESULT = json.dumps({
    "status": "DONE",
    "document_type": "lab_report",
    "document_date": "2026-09-01",
    "values": [{"name": "Uric Acid", "value": "2.1", "unit": "mg/dL"}],
    "confidence": {"overall": 0.9},
    "model": "anthropic/claude-sonnet-5",
})

VERIFIED_LABEL = {
    "verified": True,
    "verified_by": "tanvir",
    "verified_at": "2026-09-23",
    "set": "printed",
    "document_type": "lab_report",
    "values": [{"name": "Uric Acid", "value": "2.1", "unit": "mg/dL", "ref_low": "3.4", "ref_high": "7.0"}],
}

FAILURES = []


def check(name, condition, detail=""):
    if condition:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}{(' — ' + detail) if detail else ''}")
        FAILURES.append(name)


def run_draft(out_path, *extra):
    """Run the drafting writer exactly as draft-labels.sh does, result on stdin."""
    return subprocess.run(
        [sys.executable, DRAFT_LABEL, out_path, "lab10", "6852", *extra],
        input=RESULT, capture_output=True, text=True,
    )


def read(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def test_a_verified_label_is_never_overwritten(tmp):
    """The direction that matters. This is the case that destroyed batch 2."""
    out = os.path.join(tmp, "lab10.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(VERIFIED_LABEL, f)

    proc = run_draft(out)

    check("a verified label survives a drafting run", read(out) == VERIFIED_LABEL,
          "the file on disk changed")
    check("the run FAILS rather than skipping quietly", proc.returncode != 0,
          f"exit code was {proc.returncode}")
    check("the failure names verification and the file",
          "verified" in (proc.stderr + proc.stdout).lower() and "lab10" in (proc.stderr + proc.stdout))


def test_an_unverified_draft_is_overwritten(tmp):
    """The other direction: the tool must still do its job, or drafting a corpus twice is impossible."""
    out = os.path.join(tmp, "lab10.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"verified": False, "values": [], "_draft": {"source": "stale"}}, f)

    proc = run_draft(out)
    written = read(out)

    check("an unverified draft is replaced", proc.returncode == 0, proc.stderr.strip())
    check("the replacement is the new draft",
          written.get("values") and written["values"][0]["name"] == "Uric Acid")
    check("the replacement is still marked unverified", written.get("verified") is False)


def test_a_new_label_is_written(tmp):
    """A first run on an unlabelled corpus must not be blocked by the guard."""
    out = os.path.join(tmp, "lab10.json")
    proc = run_draft(out)
    check("a label that does not exist yet is written", proc.returncode == 0 and os.path.exists(out),
          proc.stderr.strip())


def test_force_overwrites_a_verified_label(tmp):
    """The escape hatch exists, and costs an explicit flag."""
    out = os.path.join(tmp, "lab10.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(VERIFIED_LABEL, f)

    proc = run_draft(out, "--force")
    written = read(out)

    check("--force overwrites a verified label", proc.returncode == 0, proc.stderr.strip())
    check("the forced write is a fresh draft", written.get("verified") is False)


def test_the_replaced_file_is_backed_up(tmp):
    """A backup of what was replaced, so even a forced run is recoverable."""
    out = os.path.join(tmp, "lab10.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(VERIFIED_LABEL, f)

    run_draft(out, "--force")

    backups = [f for f in os.listdir(tmp) if f.startswith("lab10.json.") and f != "lab10.json"]
    check("the replaced file is backed up beside it", len(backups) == 1, f"found {backups}")
    if backups:
        check("the backup holds the ORIGINAL verified content",
              read(os.path.join(tmp, backups[0])) == VERIFIED_LABEL)


def test_an_unreadable_label_is_treated_as_verified(tmp):
    """
    A file that exists but cannot be parsed is not known to be a draft. The guard refuses, because the
    failure mode of guessing wrong is the one that already happened.
    """
    out = os.path.join(tmp, "lab10.json")
    with open(out, "w", encoding="utf-8") as f:
        f.write("{ this is not json")

    proc = run_draft(out)
    check("an unparseable existing label is not overwritten", proc.returncode != 0,
          "the guard overwrote a file it could not read")


def main():
    tests = [
        test_a_verified_label_is_never_overwritten,
        test_an_unverified_draft_is_overwritten,
        test_a_new_label_is_written,
        test_force_overwrites_a_verified_label,
        test_the_replaced_file_is_backed_up,
        test_an_unreadable_label_is_treated_as_verified,
    ]
    for test in tests:
        print(f"\n{test.__name__}")
        tmp = tempfile.mkdtemp(prefix="draft-guard-")
        try:
            test(tmp)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    print()
    if FAILURES:
        print(f"{len(FAILURES)} FAILED: {', '.join(FAILURES)}")
        sys.exit(1)
    print("all green")


if __name__ == "__main__":
    main()
