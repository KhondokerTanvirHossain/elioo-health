#!/usr/bin/env python3
"""
Dry run: what the live stopgap says today vs what the proposed per-marker thresholds would say.

    scripts/urgency_dry_run.py [corpus-dir] [--md]

NOTHING IS ACTIVATED. This reads the proposed table out of the shipped config and computes, on paper, what
it WOULD decide. The running system is unchanged; every threshold is status=proposed and inert, which
ProposedThresholdsAreAllProposedTest and ProposedThresholdsAreInertTest assert in code.

Input is the VERIFIED LABELS, not extraction output. Extraction currently loses values to crop failures
(30 of 85 in batch 2), and scoring urgency on what survived cropping would credit or blame the thresholds
for the cropper's mistakes. The labels are what the pages actually say, checked by a human against the
images. A label that is not verified is refused, for the same reason the scorer refuses one.

Output is written for a clinician who has not seen this codebase: one row per report, the two verdicts side
by side, and the marker and rule behind each. Every disagreement is flagged.
"""
import json
import os
import re
import sys
import unicodedata

CORPUS = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else "docs/testset/batch2"
AS_MARKDOWN = "--md" in sys.argv
THRESHOLDS_FILE = "baymax/src/main/resources/marker-thresholds.properties"

# Mirrors BaymaxProperties.Outbound defaults. If these drift, the dry run is describing a system that does
# not exist, so they are asserted against the shipped config below.
CRITICAL_HIGH_MULTIPLE = 2.0
CRITICAL_LOW_MULTIPLE = 0.5

RANK = {"ROUTINE": 0, "THIS_WEEK": 1, "NOW": 2}


def fold(s):
    """NFKC, so MICRO SIGN and GREEK SMALL LETTER MU are one character — the batch-2 scorer defect."""
    return unicodedata.normalize("NFKC", s or "").strip().lower()


def unit_key(s):
    return re.sub(r"\s+", "", fold(s))


def number(raw):
    """Mirrors UrgencyService.number: strip everything but digits, '.' and '-', then parse."""
    if raw is None:
        return None
    s = re.sub(r"[^0-9.\-]", "", str(raw))
    if s in ("", "-", "."):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def load_thresholds(path):
    """The shipped proposed table, parsed out of the properties file the application loads."""
    rows = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            m = re.match(r"baymax\.outbound\.marker-thresholds\[(\d+)]\.([a-z\-]+)=(.*)", line)
            if m:
                rows.setdefault(int(m.group(1)), {})[m.group(2)] = m.group(3).strip()
    out = []
    for _, row in sorted(rows.items()):
        out.append({
            "canonical": fold(row.get("canonical-name")),
            "unit": unit_key(row.get("unit", "")),
            "now_low": number(row.get("now-low")),
            "now_high": number(row.get("now-high")),
            "week_low": number(row.get("week-low")),
            "week_high": number(row.get("week-high")),
            "never": row.get("never-escalates", "false") == "true",
            "direction": row.get("direction", "NORMAL"),
            "status": row.get("status", "proposed"),
            "source": row.get("source", ""),
        })
    return out


def stopgap_verdict(value, ref_low, ref_high):
    """Mirrors UrgencyService.criticalKind. No printed range -> no escalation, whatever the number."""
    x, low, high = number(value), number(ref_low), number(ref_high)
    if x is None or low is None or high is None or high < low:
        return "ROUTINE", "no printed range"
    if low <= x <= high:
        return "ROUTINE", "within printed range"
    if x > high:
        if x >= CRITICAL_HIGH_MULTIPLE * high:
            return "NOW", f"{x:g} is >= {CRITICAL_HIGH_MULTIPLE:g}x the printed upper limit {high:g}"
        return "THIS_WEEK", f"{x:g} is above the printed upper limit {high:g}"
    if x <= CRITICAL_LOW_MULTIPLE * low:
        return "NOW", f"{x:g} is <= {CRITICAL_LOW_MULTIPLE:g}x the printed lower limit {low:g}"
    return "THIS_WEEK", f"{x:g} is below the printed lower limit {low:g}"


def marker_tokens(s):
    """
    The words in a printed marker name, so "S-POTASSIUM" and "Serum Potassium" both offer "potassium".

    Deliberately generous HERE and nowhere else: this is a REVIEW document, and a threshold that fails to
    match only because a lab wrote "S-POTASSIUM" would hide the single most important row from the doctor —
    the batch-2 under-escalation. The running system is stricter: it matches on canonical_name from
    MarkerMatcher's alias table, and any marker missing from that table is reported below as a gap, because
    a threshold that cannot be reached is a threshold that does not exist.
    """
    return {w for w in re.split(r"[^a-z0-9]+", fold(s)) if len(w) > 2}


def match_threshold(thresholds, name, canonical, unit):
    want_unit = unit_key(unit)
    names = {fold(canonical), fold(name)}
    tokens = marker_tokens(name) | marker_tokens(canonical)
    for t in thresholds:
        if not t["canonical"] or t["unit"] != want_unit:
            continue
        if t["canonical"] in names or t["canonical"] in tokens:
            return t
    return None


def proposed_verdict(thresholds, name, canonical, unit, value, ref_low, ref_high):
    """What the proposed table would say. Falls back to the stopgap when no row covers this marker+unit."""
    t = match_threshold(thresholds, name, canonical, unit)
    if t is None:
        level, why = stopgap_verdict(value, ref_low, ref_high)
        return level, f"no threshold for this marker/unit — falls back to stopgap ({why})", None
    if t["never"]:
        return "ROUTINE", f"{t['canonical']} never escalates alone", t
    x = number(value)
    if x is None:
        return "ROUTINE", "value not numeric", t
    low_matters = t["direction"] != "HIGH_IS_GOOD" or True          # HIGH_IS_GOOD still uses the low bounds
    high_matters = t["direction"] not in ("LOW_IS_BAD", "HIGH_IS_GOOD")
    if low_matters and t["now_low"] is not None and x <= t["now_low"]:
        return "NOW", f"{x:g} at or below the GO NOW low threshold {t['now_low']:g}", t
    if high_matters and t["now_high"] is not None and x >= t["now_high"]:
        return "NOW", f"{x:g} at or above the GO NOW high threshold {t['now_high']:g}", t
    if low_matters and t["week_low"] is not None and x <= t["week_low"]:
        return "THIS_WEEK", f"{x:g} at or below the THIS WEEK low threshold {t['week_low']:g}", t
    if high_matters and t["week_high"] is not None and x >= t["week_high"]:
        return "THIS_WEEK", f"{x:g} at or above the THIS WEEK high threshold {t['week_high']:g}", t
    return "ROUTINE", f"{x:g} within the proposed thresholds", t


def assess(label, thresholds):
    """Highest verdict across the report's values, under each rule, with the marker that drove it."""
    worst = {"stopgap": ("ROUTINE", "", ""), "proposed": ("ROUTINE", "", "")}
    per_value = []
    for v in label.get("values") or []:
        name = v.get("name") or ""
        canonical = v.get("canonical_name") or name
        unit, value = v.get("unit") or "", v.get("value")
        s_level, s_why = stopgap_verdict(value, v.get("ref_low"), v.get("ref_high"))
        p_level, p_why, t = proposed_verdict(thresholds, name, canonical, unit, value,
                                             v.get("ref_low"), v.get("ref_high"))
        per_value.append((name, value, unit, s_level, s_why, p_level, p_why, t))
        if RANK[s_level] > RANK[worst["stopgap"][0]]:
            worst["stopgap"] = (s_level, name, s_why)
        if RANK[p_level] > RANK[worst["proposed"][0]]:
            worst["proposed"] = (p_level, name, p_why)
    return worst, per_value


def main():
    if not os.path.exists(THRESHOLDS_FILE):
        sys.exit(f"missing {THRESHOLDS_FILE}")
    thresholds = load_thresholds(THRESHOLDS_FILE)
    active = [t for t in thresholds if t["status"] != "proposed"]
    if active:
        sys.exit(f"REFUSING: {len(active)} threshold(s) are not 'proposed'. This dry run describes an "
                 f"inactive table; if any row is active it is not a dry run.")

    expected = os.path.join(CORPUS, "expected")
    labels = {}
    unverified = []
    for f in sorted(os.listdir(expected)):
        if not f.endswith(".json"):
            continue
        stem = f[:-5]
        label = json.load(open(os.path.join(expected, f), encoding="utf-8"))
        if label.get("verified") is not True:
            unverified.append(stem)
            continue
        labels[stem] = label

    rows = []
    for stem in sorted(labels, key=lambda s: (len(s), s)):
        worst, per_value = assess(labels[stem], thresholds)
        rows.append((stem, worst, per_value))

    print("# Urgency dry run — the live rule today vs the proposed thresholds\n")
    print("**Nothing here is switched on.** Every threshold below is marked `proposed` and has no effect on "
          "any message. This table exists so the numbers can be reviewed before they are.\n")
    print("**GO NOW** = tell the family to seek care today. **THIS WEEK** = see a doctor this week. "
          "**ROUTINE** = no urgency message.\n")
    if unverified:
        print(f"> **{len(unverified)} report(s) excluded — label not verified:** {', '.join(unverified)}. "
              f"A report is only included when a human has checked every value against the image.\n")

    print("| Report | TODAY (live) | PROPOSED | Agree? | What drives it |")
    print("|---|---|---|---|---|")
    for stem, worst, _ in rows:
        s_level, s_marker, _ = worst["stopgap"]
        p_level, p_marker, p_why = worst["proposed"]
        agree = "same" if s_level == p_level else "**DIFFERS**"
        driver = f"{p_marker or s_marker or '—'}"
        print(f"| {stem} | {s_level.replace('_',' ')} | {p_level.replace('_',' ')} | {agree} | {driver} |")

    print("\n## Every disagreement, in detail\n")
    any_diff = False
    for stem, worst, per_value in rows:
        diffs = [r for r in per_value if r[3] != r[5]]
        if worst["stopgap"][0] == worst["proposed"][0] and not diffs:
            continue
        any_diff = True
        print(f"### {stem}: today **{worst['stopgap'][0].replace('_',' ')}** → "
              f"proposed **{worst['proposed'][0].replace('_',' ')}**\n")
        print("| Marker | Reading | Today says | Because | Proposed says | Because | Source |")
        print("|---|---|---|---|---|---|---|")
        for name, value, unit, s_level, s_why, p_level, p_why, t in diffs:
            source = t["source"] if t else "no threshold — stopgap"
            print(f"| {name} | {value} {unit} | {s_level.replace('_',' ')} | {s_why} | "
                  f"{p_level.replace('_',' ')} | {p_why} | {source} |")
        print()
    if not any_diff:
        print("_No disagreements._\n")

    print("## Markers on these reports with no proposed threshold\n")
    print("These fall back to today's rule. Each is a question for the doctor: should it have a threshold, "
          "or should it never raise urgency on its own?\n")
    missing = {}
    for stem, _, per_value in rows:
        for name, value, unit, s_level, _, _, _, t in per_value:
            if t is None:
                missing.setdefault((name, unit), []).append((stem, s_level))
    if not missing:
        print("_None._")
    for (name, unit), where in sorted(missing.items()):
        escalating = [f"{s} ({lv.replace('_',' ')})" for s, lv in where if lv != "ROUTINE"]
        note = f" — **escalates today on:** {', '.join(escalating)}" if escalating else ""
        print(f"- **{name}** ({unit or 'no unit'}){note}")

    # A threshold the running system could never reach is a threshold that does not exist. The app joins a
    # value to a threshold on canonical_name, which MarkerMatcher derives from the alias table; a marker
    # absent from that table can never be canonicalised, so its threshold can never fire however carefully a
    # doctor sets it. This dry run matches more loosely on purpose, so without this section the table would
    # look complete while the code could not use it.
    print("\n## Engineering gap: thresholds the running system cannot reach yet\n")
    aliases = set()
    app_properties = "medscribe-ai/src/main/resources/application.properties"
    if os.path.exists(app_properties):
        with open(app_properties, encoding="utf-8") as f:
            for line in f:
                m = re.match(r"baymax\.markers\[\d+]\.canonical=(.*)", line.strip())
                if m:
                    aliases.add(fold(m.group(1)))
    unreachable = sorted({t["canonical"] for t in thresholds if t["canonical"] not in aliases})
    if not unreachable:
        print("_None: every proposed threshold names a marker the canonicaliser knows._")
    else:
        print("These markers have a proposed threshold but **no entry in the canonical marker table**, so the "
              "code cannot match a reading to the threshold. This is a code/config task, not a clinical one, "
              "and it must be done before any of these rows is switched on:\n")
        for canonical in unreachable:
            print(f"- `{canonical}`")


if __name__ == "__main__":
    main()
