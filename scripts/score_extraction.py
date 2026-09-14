#!/usr/bin/env python3
"""Scores one extracted document against its hand-labelled expectation, and prints the run summary.

Two modes:

    score_extraction.py <report.json> <stem> <expected.json> <latency_ms>   # reads the result on stdin
    score_extraction.py --summary <report.json> <total> <labelled>

Nothing patient-identifying reaches stdout: only counts, accuracy, cost, latency and model names. The
per-document detail (which item matched what) goes into the report file, which lives beside the corpus
and is git-ignored with it.
"""
import json
import re
import sys


def norm(value):
    """Compare on meaning, not formatting: case, spaces and punctuation are noise here.

    Bangla and other non-Latin text is compared as-is: stripping it to [a-z0-9] would erase it entirely.
    An empty string and a missing field are the same thing, which is how the labels are written.
    """
    if value is None:
        return ""
    text = str(value).strip().lower()
    if not text:
        return ""
    if any(ord(c) > 0x7F for c in text):          # non-ASCII: collapse whitespace only
        return re.sub(r"\s+", " ", text)
    return re.sub(r"[^a-z0-9.]+", " ", text).strip()


def number_eq(a, b):
    """8.2 and 8.20 are the same reading; anything non-numeric falls back to string equality."""
    try:
        return abs(float(a) - float(b)) < 1e-9
    except (TypeError, ValueError):
        return norm(a) == norm(b)


def match_values(expected, actual):
    """A value counts as correct when the name matches and both value and unit agree."""
    hits, misses = 0, []
    remaining = list(actual)
    for want in expected:
        found = None
        for got in remaining:
            if norm(got.get("name")) == norm(want.get("name")):
                found = got
                break
        if found is None:
            misses.append({"field": "values", "reason": "not found", "name": want.get("name")})
            continue
        remaining.remove(found)
        if number_eq(want.get("value"), found.get("value")) and norm(want.get("unit")) == norm(found.get("unit")):
            hits += 1
        else:
            misses.append({"field": "values", "reason": "wrong reading", "name": want.get("name")})
    return hits, misses, len(remaining)


def match_medicines(expected, actual):
    hits, misses = 0, []
    remaining = list(actual)
    for want in expected:
        found = next((g for g in remaining if norm(g.get("name")) == norm(want.get("name"))), None)
        if found is None:
            misses.append({"field": "medicines", "reason": "not found", "name": want.get("name")})
            continue
        remaining.remove(found)
        wrong = [f for f in ("dose_text", "frequency_text", "timing_text")
                 if norm(want.get(f)) != norm(found.get(f))]
        if not wrong:
            hits += 1
        else:
            misses.append({"field": "medicines", "reason": "wrong " + "/".join(wrong),
                           "name": want.get("name")})
    return hits, misses, len(remaining)


CONTEXT_SECTIONS = ("chief_complaint", "history", "examination", "diagnosis",
                    "investigations_advised", "advice")


def score_context(expected, actual):
    """Clinical context, scored per section and reported separately from the headline number.

    Matching is loose containment on the transcribed text, because a line the doctor wrote by hand will
    not survive OCR character-perfect. Diagnosis is the section to watch: an invented one is worse than a
    missing one, so extras are counted and shown.
    """
    total_expected, total_correct, detail = 0, 0, {}
    for section in CONTEXT_SECTIONS:
        want = [i.get("text") for i in (expected.get(section) or [])]
        got = [i.get("text") for i in (actual.get(section) or [])]
        remaining = list(got)
        hits = 0
        for w in want:
            wn = norm(w)
            found = next((g for g in remaining if wn and (wn in norm(g) or norm(g) in wn)), None)
            if found is not None:
                remaining.remove(found)
                hits += 1
        total_expected += len(want)
        total_correct += hits
        if want or got:
            detail[section] = {"expected": len(want), "correct": hits, "invented": len(remaining)}

    want_ref = norm((expected.get("referral") or {}).get("text"))
    got_ref = norm((actual.get("referral") or {}).get("text"))
    if want_ref:
        total_expected += 1
        if want_ref in got_ref or got_ref in want_ref:
            total_correct += 1
    elif got_ref:
        detail["referral"] = {"expected": 0, "correct": 0, "invented": 1}
    return total_expected, total_correct, detail


def match_follow_up(expected, actual):
    """Instructions are free text, so a loose containment match, but the due date must be exact."""
    hits, misses = 0, []
    remaining = list(actual)
    for want in expected:
        wanted = norm(want.get("instruction"))
        found = next((g for g in remaining
                      if wanted in norm(g.get("instruction")) or norm(g.get("instruction")) in wanted), None)
        if found is None:
            misses.append({"field": "follow_up", "reason": "not found"})
            continue
        remaining.remove(found)
        if norm(want.get("due_date")) == norm(found.get("due_date")):
            hits += 1
        else:
            misses.append({"field": "follow_up", "reason": "wrong due date"})
    return hits, misses, len(remaining)


def score_one(report_path, stem, expected_path, latency_ms, result):
    with open(expected_path) as f:
        expected = json.load(f)

    status = result.get("status", "NO_RESPONSE")
    values = result.get("values") or []
    medicines = result.get("medicines") or []
    follow_up = result.get("follow_up") or []

    v_hits, v_miss, v_extra = match_values(expected.get("values", []), values)
    m_hits, m_miss, m_extra = match_medicines(expected.get("medicines", []), medicines)
    f_hits, f_miss, f_extra = match_follow_up(expected.get("follow_up", []), follow_up)

    context_expected, context_correct, context_detail = score_context(
        expected.get("clinical_context") or {}, result.get("clinical_context") or {})

    type_ok = norm(expected.get("document_type")) == norm(result.get("document_type"))
    date_ok = norm(expected.get("document_date")) == norm(result.get("document_date"))

    wanted = (len(expected.get("values", [])) + len(expected.get("medicines", []))
              + len(expected.get("follow_up", [])) + 2)  # +2 for type and date
    correct = v_hits + m_hits + f_hits + int(type_ok) + int(date_ok)

    # every surviving item must carry a crop: that is the BMX-2 rule, checked rather than assumed
    without_crop = [i for i in values + medicines + follow_up if not i.get("crop_key")]

    entry = {
        "document": stem,
        "set": expected.get("set", "unknown"),
        "status": status,
        "model": result.get("model"),
        "confidence": result.get("confidence"),
        "latency_ms": int(latency_ms),
        "expected_items": wanted,
        "correct_items": correct,
        "extra_items": v_extra + m_extra + f_extra,
        "items_without_crop": len(without_crop),
        "document_type_ok": type_ok,
        "document_date_ok": date_ok,
        "context_expected": context_expected,
        "context_correct": context_correct,
        "context_detail": context_detail,
        "misses": v_miss + m_miss + f_miss,
    }

    with open(report_path) as f:
        report = json.load(f)
    report["documents"].append(entry)
    with open(report_path, "w") as f:
        json.dump(report, f, indent=2)


def summarise(report_path, total, labelled):
    with open(report_path) as f:
        report = json.load(f)
    docs = report["documents"]

    def table(name, rows):
        if not rows:
            print(f"  {name:<14} no labelled documents")
            return
        wanted = sum(r["expected_items"] for r in rows)
        correct = sum(r["correct_items"] for r in rows)
        extra = sum(r["extra_items"] for r in rows)
        no_crop = sum(r["items_without_crop"] for r in rows)
        done = [r for r in rows if r["status"] == "DONE"]
        retake = [r for r in rows if r["status"] == "NEEDS_RETAKE"]
        failed = [r for r in rows if r["status"] == "FAILED"]
        latencies = sorted(r["latency_ms"] for r in rows)
        p50 = latencies[len(latencies) // 2] if latencies else 0
        accuracy = (correct / wanted * 100) if wanted else 0.0
        print(f"  {name:<14} docs={len(rows):<3} done={len(done):<3} retake={len(retake):<3} "
              f"failed={len(failed):<3} accuracy={accuracy:5.1f}%  invented={extra:<3} "
              f"no_crop={no_crop:<3} p50={p50 / 1000:.1f}s")

    print(f"\nrun: {report['run']}")
    print(f"corpus: {total} documents, {labelled} labelled\n")
    table("printed", [d for d in docs if d["set"] == "printed"])
    table("handwritten", [d for d in docs if d["set"] == "handwritten"])
    table("all", docs)

    ctx_expected = sum(d.get("context_expected", 0) for d in docs)
    ctx_correct = sum(d.get("context_correct", 0) for d in docs)
    invented_dx = sum(d.get("context_detail", {}).get("diagnosis", {}).get("invented", 0) for d in docs)
    if ctx_expected:
        print(f"\n  clinical_context (informational, not in the headline): "
              f"{ctx_correct}/{ctx_expected} = {ctx_correct / ctx_expected * 100:.1f}%")
    if invented_dx:
        print(f"  !! diagnosis lines invented on {invented_dx} occasion(s) — the page did not say it")

    models = sorted({d["model"] for d in docs if d.get("model")})
    if models:
        print(f"\n  models used: {', '.join(models)}")
    print("\n  targets: printed >=95%, handwritten >=85%, no_crop must be 0")
    print("  cost per document: read from the weekly export (ai_call_log), not estimated here")


if __name__ == "__main__":
    if sys.argv[1] == "--summary":
        summarise(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
    else:
        raw = sys.stdin.read().strip()
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {}
        score_one(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], parsed)
