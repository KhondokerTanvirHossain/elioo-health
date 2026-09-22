#!/usr/bin/env python3
"""
Turn one extraction result into a DRAFT label for a human to verify.

    draft_label.py <out.json> <stem> <latency_ms>     # reads the document result on stdin

The output matches the hand-written labels in expected/, plus a "_draft" block carrying everything a
verifier needs and nothing that would be scored: what the model said about itself (confidence per section),
how long it took, and the flags it set. The scorer ignores keys beginning with "_".

The file says `"verified": false`. It is the only thing standing between a drafted label and a meaningless
accuracy number — a model scored against its own unchecked output reports its agreement with itself. The
verifier reads every field against the image, corrects what is wrong, and sets it to true.
"""
import json
import sys


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


def main():
    out_path, stem, latency_ms = sys.argv[1], sys.argv[2], sys.argv[3]
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
