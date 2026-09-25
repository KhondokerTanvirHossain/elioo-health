#!/usr/bin/env python3
"""
Turn one extraction result into a DRAFT label for a human to verify.

    draft_label.py <out.json> <stem> <latency_ms> [--force]   # reads the document result on stdin

The output matches the hand-written labels in expected/, plus a "_draft" block carrying everything a
verifier needs and nothing that would be scored: what the model said about itself (confidence per section),
how long it took, and the flags it set. The scorer ignores keys beginning with "_".

The file says `"verified": false`. It is the only thing standing between a drafted label and a meaningless
accuracy number — a model scored against its own unchecked output reports its agreement with itself. The
verifier reads every field against the image, corrects what is wrong, and sets it to true.

A VERIFIED LABEL IS NEVER OVERWRITTEN without --force. This script was run against a directory holding ten
labels verified field by field against the images and replaced every one with a fresh draft; the corpus is
git-ignored because it holds patient data, so there was no commit and no backup to recover from. The
verification is the foundation every number in BASELINE.md rests on, and the tool that produces those
numbers destroyed it silently. Guard tested both directions in scripts/test_draft_label_guard.py.
"""
import json
import os
import shutil
import sys
import time


def value(v):
    """A value as the label format holds it: what was read, and the printed range it was read against."""
    out = {
        "name": v.get("name"),
        "value": v.get("value"),
        "unit": v.get("unit"),
    }
    # ref_low / ref_high / flag are scored, so they belong in the label proper, not the draft block.
    # A lab report prints its ranges; if the model read none, that is itself a thing to verify.
    for key in ("ref_low", "ref_high", "flag"):
        if v.get(key) is not None:
            out[key] = v.get(key)
    return out


def medicine(m):
    out = {"name": m.get("name")}
    for key in ("dose_text", "frequency_text", "timing_text", "duration_text", "instruction_text"):
        if m.get(key):
            out[key] = m.get(key)
    return out


def context(c):
    if not isinstance(c, dict):
        return {}
    out = {}
    for section in ("chief_complaint", "history", "examination", "diagnosis",
                    "investigations_advised", "advice"):
        entries = c.get(section) or []
        out[section] = [{"text": e.get("text")} for e in entries if isinstance(e, dict) and e.get("text")]
    if c.get("referral"):
        out["referral"] = c["referral"]
    return out


def refuse_to_destroy_verification(out_path, force):
    """
    Nothing is written over a label a human has verified. An existing file is overwritten only when it says
    `"verified": false`; anything else — verified, or unreadable and therefore unknown — stops the run.

    Unreadable counts as verified on purpose. A file that cannot be parsed is not known to be a draft, and
    guessing wrong in that direction is the failure that already happened.
    """
    if not os.path.exists(out_path):
        return

    try:
        with open(out_path, encoding="utf-8") as f:
            existing = json.load(f)
        verified = existing.get("verified") is True
        why = "it is verified" if verified else None
    except (json.JSONDecodeError, OSError) as e:
        verified, why = True, f"it could not be read ({e.__class__.__name__}) and may hold verified work"

    if verified and not force:
        sys.exit(
            f"REFUSING to overwrite {out_path}: {why}.\n"
            f"  A verified label is a human's reading of the image, and the corpus is git-ignored — "
            f"overwriting it destroys work that cannot be recovered from git.\n"
            f"  Move it aside, or pass --force if you genuinely mean to replace it."
        )

    # Even a forced write leaves the original beside it, so the escape hatch is not a second way to lose work.
    if verified:
        backup = f"{out_path}.{time.strftime('%Y%m%dT%H%M%S')}.bak"
        shutil.copy2(out_path, backup)
        print(f"backed up {os.path.basename(out_path)} -> {os.path.basename(backup)}", file=sys.stderr)


def main():
    args = [a for a in sys.argv[1:] if a != "--force"]
    force = "--force" in sys.argv[1:]
    out_path, stem, latency_ms = args[0], args[1], args[2]

    refuse_to_destroy_verification(out_path, force)

    result = json.load(sys.stdin)
    # the document API returns the extraction fields at the TOP level, not nested under "extraction"
    extraction = result
    confidence = result.get("confidence") or {}

    label = {
        # THE VERIFICATION GATE. Nothing is scored until a human has read every field against the image
        # and set this to true. See scripts/score_extraction.py, which refuses a draft.
        "verified": False,
        "verified_by": None,
        "verified_at": None,

        "set": "printed",
        "document_type": extraction.get("document_type"),
        "document_date": extraction.get("document_date"),
        "values": [value(v) for v in (extraction.get("values") or [])],
        "medicines": [medicine(m) for m in (extraction.get("medicines") or [])],
        "follow_up": [{"instruction": f.get("instruction"), "due_date": f.get("due_date")}
                      for f in (extraction.get("follow_up") or [])],
        "clinical_context": context(extraction.get("clinical_context")),

        # Everything below is the model talking about itself. Never scored; here so the verifier can see
        # where the model was unsure and look harder at those fields.
        "_draft": {
            "source": stem,
            "status": result.get("status"),
            "status_reason": result.get("status_reason"),
            "model": result.get("model"),
            "latency_ms": int(latency_ms),
            "confidence": confidence,
            "note": ("Model-drafted, NOT verified. Check every value, unit, ref_low, ref_high and flag "
                     "against the image. Correct what is wrong, then set verified=true and verified_by."),
        },
    }

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(label, f, ensure_ascii=False, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
