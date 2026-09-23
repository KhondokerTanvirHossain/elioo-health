#!/usr/bin/env python3
"""Scores one extracted document against its hand-labelled expectation, and prints the run summary.

Two modes:

    score_extraction.py <report.json> <stem> <expected.json> <latency_ms>   # reads the result on stdin
    score_extraction.py --summary <report.json> <total> <labelled>
    score_extraction.py --rescore <report.json> <expected-dir>     # re-score stored results, no model calls

Nothing patient-identifying reaches stdout: only counts, accuracy, cost, latency and model names. The
per-document detail (which item matched what) goes into the report file, which lives beside the corpus
and is git-ignored with it.
"""
import json
import re
import sys
import unicodedata


def fold_digits(text):
    """Every decimal digit, whatever script it is written in, becomes its ASCII equivalent.

    The stored record is verbatim (PO decision, 2026-09-17): a prescription that says ১+০+১ is stored as
    ১+০+১ so the text matches the crop beside it. The label may say 1+0+1. Both mean the same instruction,
    so the comparison folds numerals on both sides and nowhere else does. Uses the Unicode decimal value,
    so Bangla, Devanagari, Arabic-Indic and anything else with Nd digits all fold the same way.
    """
    return "".join(
        str(unicodedata.decimal(c)) if unicodedata.category(c) == "Nd" else c
        for c in text
    )


def norm(value):
    """Compare on meaning, not formatting: case, spaces, punctuation and digit script are noise here.

    Bangla and other non-Latin text is compared as-is: stripping it to [a-z0-9] would erase it entirely.
    An empty string and a missing field are the same thing, which is how the labels are written.

    NFKC first, because visually identical characters are not automatically equal code points: a unit written
    with MICRO SIGN (U+00B5) and one written with GREEK SMALL LETTER MU (U+03BC) look the same on the page and
    mean the same thing. Without this, 8 of the 9 "unit misses" in the batch-2 baseline were the scorer
    failing to fold micro, not the model misreading a unit.
    """
    if value is None:
        return ""
    text = fold_digits(unicodedata.normalize("NFKC", str(value)).strip().lower())
    if not text:
        return ""
    if any(ord(c) > 0x7F for c in text):
        # non-ASCII: punctuation and its spacing are noise (সকাল-রাত is সকাল - রাত), letters are not
        return re.sub(r"\s+", " ", re.sub(r"[\s,\-–—/()+:;.]+", " ", text)).strip()
    return re.sub(r"[^a-z0-9.]+", " ", text).strip()


def number_eq(a, b):
    """8.2 and 8.20 are the same reading; anything non-numeric falls back to string equality."""
    try:
        return abs(float(fold_digits(str(a))) - float(fold_digits(str(b)))) < 1e-9
    except (TypeError, ValueError):
        return norm(a) == norm(b)


MARKER_ALIASES = None


def marker_aliases():
    """canonical marker -> its aliases, read from baymax.markers in application.properties (config, not code)."""
    global MARKER_ALIASES
    if MARKER_ALIASES is None:
        import os
        path = os.path.join(os.path.dirname(__file__), "..", "medscribe-ai", "src", "main", "resources",
                            "application.properties")
        canon, aliases = {}, {}
        for line in open(path, encoding="utf-8"):
            m = re.match(r"baymax\.markers\[(\d+)\]\.(canonical|aliases)=(.*)", line.strip())
            if m:
                (canon if m.group(2) == "canonical" else aliases)[m.group(1)] = m.group(3)
        MARKER_ALIASES = {canon[i]: {norm(a) for a in aliases.get(i, "").split(",")} | {norm(canon[i])}
                          for i in canon}
    return MARKER_ALIASES


def value_matches(want, got):
    """The stored name is verbatim from the page ("BP", twice for a 130/80) and the meaning lives in
    canonical_name. A label names the marker directly, so it is matched through the same alias table the
    pipeline uses; a plain name match still works for anything that is not a canonical marker."""
    if norm(got.get("name")) == norm(want.get("name")):
        return True
    canonical = got.get("canonical_name")
    return bool(canonical) and norm(want.get("name")) in marker_aliases().get(canonical, set())


# --- per-field value scoring (batch 2) -------------------------------------------------------------

#: Every field a label carries for a value. Scored as its own section, because they test different
#: things: `value`/`unit` test reading, `ref_*`/`ref_text`/`band` test whether the printed reference was
#: transcribed rather than invented (DR-29), `flag` tests whether a printed mark was read, and `status`
#: tests our own abnormal judgement — the one urgency depends on.
VALUE_FIELDS = ["value", "unit", "ref_low", "ref_high", "ref_text", "band", "flag", "status"]


def field_eq(field, want, got):
    """Numeric fields compare as numbers so "5.0" matches "5"; the rest compare as normalised text."""
    if want is None and got is None:
        return True
    if field in ("value", "ref_low", "ref_high"):
        if number_eq(want, got):
            return True
    return norm(want) == norm(got)


def score_value_fields(expected, actual):
    """Each value field scored separately, plus abnormal detection reported as misses vs false alarms.

    status is the field that matters: a missed low/high is a missed urgency, a false one is a false
    urgent message to a family. They are counted apart because they are not the same mistake — one lets
    a real finding pass silently, the other sends someone to a doctor for nothing.
    """
    per_field = {f: {"expected": 0, "correct": 0} for f in VALUE_FIELDS}
    abnormal = {"missed": [], "false": [], "correct": 0}
    remaining = list(actual)

    for want in expected:
        got = next((g for g in remaining if value_matches(want, g)), None)
        if got is None:
            continue                      # a value not found at all is counted by match_values
        remaining.remove(got)
        for field in VALUE_FIELDS:
            if want.get(field) is None and got.get(field) is None:
                continue
            per_field[field]["expected"] += 1
            if field_eq(field, want.get(field), got.get(field)):
                per_field[field]["correct"] += 1

        want_status = norm(want.get("status"))
        got_status = norm(got.get("status"))
        want_abnormal = want_status in ("low", "high")
        got_abnormal = got_status in ("low", "high")
        if want_abnormal and not got_abnormal:
            abnormal["missed"].append(want.get("name"))
        elif got_abnormal and not want_abnormal:
            abnormal["false"].append(want.get("name"))
        elif want_abnormal and got_abnormal and want_status == got_status:
            abnormal["correct"] += 1

    return {f: v for f, v in per_field.items() if v["expected"]}, abnormal


def invented_values(expected, actual):
    """Values the model produced that the label does not have, BY NAME.

    A report with empty result rows is the test: an invented creatinine is a false trend input, and a
    count alone would not say which marker was fabricated.
    """
    remaining = list(actual)
    for want in expected:
        got = next((g for g in remaining if value_matches(want, g)), None)
        if got is not None:
            remaining.remove(got)
    return [g.get("name") for g in remaining]


def match_values(expected, actual):
    """A value counts as correct when the name matches and both value and unit agree."""
    hits, misses = 0, []
    remaining = list(actual)
    for want in expected:
        found = None
        for got in remaining:
            if value_matches(want, got):
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


def medicine_name_matches(want, got):
    """Exact, or the model folded the strength into the name: the label's name is a leading run of whole
    tokens and what remains is the dose. "Tab. Thyrox 25 mcg" matches "Tab. Thyrox" + dose "25 mcg";
    "Tab. Thyroxide" does not match "Tab. Thyrox"."""
    w, g = norm(want.get("name")), norm(got.get("name"))
    if w == g:
        return True
    if w and g.startswith(w + " "):
        rest = g[len(w):].strip()
        dose = norm(got.get("dose_text")) or norm(want.get("dose_text"))
        return rest == dose or not dose
    return False


def match_medicines(expected, actual):
    hits, misses = 0, []
    remaining = list(actual)
    for want in expected:
        found = next((g for g in remaining if medicine_name_matches(want, g)), None)
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


# follow_up joins the headline only once the corpus can measure it. Batch 1 is ten prescriptions of which
# one states a follow-up, so its 0/1 said nothing about the pipeline (PO, 2026-09-17: a corpus fact, excluded
# until a corpus with follow-ups exists). Below this many labelled follow-ups the section is reported and
# not counted, exactly as clinical_context is.
MIN_FOLLOW_UPS_FOR_HEADLINE = 5

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


def require_verified(expected, expected_path):
    """A label that has not been read by a human is not a label.

    A model drafted these files; scoring against them unchecked would report the model's agreement with
    itself as accuracy — a number that looks like a measurement and is not one. The gate is deliberately
    loud and deliberately unskippable: there is no flag to bypass it, because the only reason to want one
    is to produce exactly the number that must not be produced.
    """
    # A label with NO "verified" key is hand-written and predates drafting — batch 1 is ten such files,
    # and they are the most trustworthy labels in the project. Only a file that explicitly says it is
    # unverified is refused: the key exists to mark model-drafted output, not to invalidate human work.
    if "verified" not in expected:
        return
    if expected.get("verified") is not True:
        sys.exit(
            f"REFUSING TO SCORE: {expected_path} is not verified.\n"
            f'  It says "verified": {json.dumps(expected.get("verified"))}.\n'
            "  This label was drafted by the model. Scoring against it measures the model's agreement\n"
            "  with itself, not its accuracy. Read every field against the image, correct what is wrong,\n"
            '  then set "verified": true and "verified_by".'
        )


def score_one(report_path, stem, expected_path, latency_ms, result, document_id=None):
    with open(expected_path) as f:
        expected = json.load(f)
    require_verified(expected, expected_path)
    entry = score_entry(stem, expected, latency_ms, result, document_id)
    with open(report_path) as f:
        report = json.load(f)
    report["documents"].append(entry)
    with open(report_path, "w") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)


#: The 2.0 / 0.5 STOPGAP multiples, mirroring BaymaxProperties.Outbound. A stopgap by PO ruling
#: 2026-09-18: the correct source is per-marker panic thresholds from a clinician, pending and blocking
#: week 0. Batch 2 showed it wrong in BOTH directions — over-escalating on several reports and
#: under-escalating on an electrolyte modestly above range.
CRITICAL_HIGH_MULTIPLE = 2.0
CRITICAL_LOW_MULTIPLE = 0.5


def urgency_of(expected_values, actual_values):
    """The urgency today's code would assign, and which value produced it.

    Mirrors UrgencyService.criticalKind exactly, including the part that is easy to miss: BOTH ref_low
    and ref_high must be numeric, so a one-sided printed reference ("Upto 37") escalates nothing. Run
    against the LABEL's values, because the question is what the page warrants — and against the model's,
    because the difference between the two is the cost of a misread.

    The page's own critical wording is not checked here; that needs the document text, which the label
    does not carry. Reported urgency is therefore a floor, never an over-estimate.
    """
    def verdict(values):
        level, rules = "ROUTINE", []
        for v in values:
            value = to_number(v.get("value"))
            low = to_number(v.get("ref_low"))
            high = to_number(v.get("ref_high"))
            if value is None or low is None or high is None or high < low:
                continue
            if low <= value <= high:
                continue
            name = v.get("canonical_name") or v.get("name")
            if value > high:
                critical = value >= CRITICAL_HIGH_MULTIPLE * high
            else:
                critical = value <= CRITICAL_LOW_MULTIPLE * low
            if critical:
                level = "NOW"
                rules.append(f"value_critical:{name}")
            else:
                if level != "NOW":
                    level = "THIS_WEEK"
                rules.append(f"value_outside_range:{name}")
        return level, rules

    label_level, label_rules = verdict(expected_values)
    model_level, model_rules = verdict(actual_values)
    return (
        {"from_label": label_level, "from_extraction": model_level,
         "agree": label_level == model_level},
        {"from_label": label_rules, "from_extraction": model_rules},
    )


def to_number(raw):
    """UrgencyService.number(): strip to digits, dot and minus, then parse. Deliberately the same, so the
    report shows what the code does rather than what it ought to."""
    if raw is None:
        return None
    s = re.sub(r"[^0-9.\-]", "", str(raw))
    if s in ("", "-", "."):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def score_entry(stem, expected, latency_ms, result, document_id=None):

    status = result.get("status", "NO_RESPONSE")
    values = result.get("values") or []
    medicines = result.get("medicines") or []
    follow_up = result.get("follow_up") or []

    v_hits, v_miss, v_extra = match_values(expected.get("values", []), values)
    m_hits, m_miss, m_extra = match_medicines(expected.get("medicines", []), medicines)
    f_hits, f_miss, f_extra = match_follow_up(expected.get("follow_up", []), follow_up)

    context_expected, context_correct, context_detail = score_context(
        expected.get("clinical_context") or {}, result.get("clinical_context") or {})

    value_fields, abnormal = score_value_fields(expected.get("values", []), values)
    invented = invented_values(expected.get("values", []), values)
    urgency, urgency_rules = urgency_of(expected.get("values", []), values)

    type_ok = norm(expected.get("document_type")) == norm(result.get("document_type"))
    date_ok = norm(expected.get("document_date")) == norm(result.get("document_date"))

    wanted = (len(expected.get("values", [])) + len(expected.get("medicines", []))
              + len(expected.get("follow_up", [])) + 2)  # +2 for type and date
    correct = v_hits + m_hits + f_hits + int(type_ok) + int(date_ok)

    # every surviving item must carry a crop: that is the BMX-2 rule, checked rather than assumed
    without_crop = [i for i in values + medicines + follow_up if not i.get("crop_key")]
    # items the model returned that the server dropped because no crop could be cut for them. They are
    # counted per section in the response and were invisible to this scorer until a follow-up that both
    # models had transcribed correctly scored 0/1 for three runs running.
    dropped = result.get("unverified") or {}

    entry = {
        "document": stem,
        "document_id": document_id,
        # per section, so one broken section cannot silently sink the headline: that is exactly how a
        # clinical_context of 0/40 (an unexposed API field, not a model failure) hid behind a 26.5% number
        "sections": {
            "values":    {"expected": len(expected.get("values", [])),    "correct": v_hits, "invented": v_extra, "dropped": dropped.get("values", 0)},
            "medicines": {"expected": len(expected.get("medicines", [])), "correct": m_hits, "invented": m_extra, "dropped": dropped.get("medicines", 0)},
            "follow_up": {"expected": len(expected.get("follow_up", [])), "correct": f_hits, "invented": f_extra, "dropped": dropped.get("follow_up", 0)},
            "clinical_context": {"expected": context_expected, "correct": context_correct, "invented": 0, "dropped": 0},
        },
        "set": expected.get("set", "unknown"),
        # a vendor demo template is not a real patient document; scored, but reported apart
        "demo": bool(expected.get("demo")),
        "country": expected.get("country"),
        # per value FIELD, so reading (value/unit), transcription (ref_*/ref_text/band), printed marks
        # (flag) and our own judgement (status) are never averaged into one another
        "value_fields": value_fields,
        # the one urgency depends on: a missed abnormal is a missed urgency, a false one a false alarm
        "abnormal_detection": abnormal,
        # BY NAME, not a count: an invented creatinine is a false trend input
        "invented_values": invented,
        "urgency": urgency,
        "urgency_rules": urgency_rules,
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
        # the raw response, so the run can be rescored under a later scorer without another model call;
        # this file lives beside the corpus and is git-ignored with it, so patient text is allowed here
        "result": result,
    }
    return entry


def rescore(report_path, expected_dir):
    """Rebuild every entry from its stored result under the current scoring rules. No model calls."""
    with open(report_path) as f:
        report = json.load(f)
    rebuilt = []
    for old in report["documents"]:
        if "result" not in old:
            sys.exit(f"{report_path}: document {old['document']} has no stored result; cannot rescore")
        expected_path = f"{expected_dir}/{old['document']}.json"
        with open(expected_path) as f:
            expected = json.load(f)
        require_verified(expected, expected_path)
        rebuilt.append(score_entry(old["document"], expected, old["latency_ms"], old["result"],
                                   old.get("document_id")))
    report["documents"] = rebuilt
    with open(report_path, "w") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)


def summarise(report_path, total, labelled):
    with open(report_path) as f:
        report = json.load(f)
    docs = report["documents"]

    labelled_follow_ups = sum(d.get("sections", {}).get("follow_up", {}).get("expected", 0) for d in docs)
    count_follow_up = labelled_follow_ups >= MIN_FOLLOW_UPS_FOR_HEADLINE

    def headline(r):
        """(correct, expected) for one document, with follow_up left out while the corpus cannot measure it."""
        fu = r.get("sections", {}).get("follow_up", {})
        if count_follow_up:
            return r["correct_items"], r["expected_items"]
        return r["correct_items"] - fu.get("correct", 0), r["expected_items"] - fu.get("expected", 0)

    def table(name, rows):
        if not rows:
            print(f"  {name:<14} no labelled documents")
            return
        wanted = sum(headline(r)[1] for r in rows)
        correct = sum(headline(r)[0] for r in rows)
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

    if count_follow_up:
        print("\n  per section (headline = values + medicines + follow_up + type/date):")
    else:
        print(f"\n  per section (headline = values + medicines + type/date; follow_up excluded: the corpus labels "
              f"{labelled_follow_ups} follow-up(s), fewer than {MIN_FOLLOW_UPS_FOR_HEADLINE} — a corpus fact, not a score):")
    print(f"    {'section':<18}{'correct':>9}{'expected':>10}{'accuracy':>10}{'invented':>10}{'dropped':>9}")
    for name in ("values", "medicines", "follow_up", "clinical_context"):
        e = sum(d.get("sections", {}).get(name, {}).get("expected", 0) for d in docs)
        c = sum(d.get("sections", {}).get(name, {}).get("correct", 0) for d in docs)
        i = sum(d.get("sections", {}).get(name, {}).get("invented", 0) for d in docs)
        dr = sum(d.get("sections", {}).get(name, {}).get("dropped", 0) for d in docs)
        pct = f"{c / e * 100:5.1f}%" if e else "    n/a"
        excluded = name == "follow_up" and not count_follow_up
        warn = "   (excluded from headline)" if excluded else (
            "   <-- ZERO across a non-empty section: check the pipeline exposes it" if e and c == 0 else "")
        if dr:
            warn += f"   <-- {dr} item(s) the model returned were dropped for having no crop"
        print(f"    {name:<18}{c:>9}{e:>10}{pct:>10}{i:>10}{dr:>9}{warn}")

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
    elif sys.argv[1] == "--rescore":
        rescore(sys.argv[2], sys.argv[3])
    else:
        raw = sys.stdin.read().strip()
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {}
        score_one(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], parsed,
                  sys.argv[5] if len(sys.argv) > 5 else None)
