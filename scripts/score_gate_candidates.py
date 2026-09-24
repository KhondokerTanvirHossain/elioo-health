#!/usr/bin/env python3
"""
Score three retake-gate candidates against a HUMAN answer key.

    scripts/score_gate_candidates.py <review.md> <rates.txt> [agreement.txt]

The first measurement of the verification rate graded it against the CURRENT GATE, which is not ground
truth: the gate rests on a confidence score that moved 0.85 -> 0.70 on the same image, and six prescriptions
it rejected had every gating item independently confirmed on the page. Grading a candidate against a
yardstick that is itself under suspicion can only ever reproduce the yardstick.

So the answer key is a person: for each retaken document, is the page clearly readable? Everything is scored
against that.

  a. CURRENT     retake when the model's confidence is below the threshold
  b. OVERRIDE    retake only when confidence is low AND verification is low — a page whose gating items are
                 independently confirmed is never retaken on the model's self-doubt alone. A minimum count
                 of confirmed items is required so a one-value page cannot pass on a single confirmation.
  c. AGREEMENT   retake when two extractions of the same image disagree about what they read

Two error types, reported SEPARATELY because they are not interchangeable:

  MISSED BAD PAGE  an unreadable page accepted — a family is shown values read off a page nobody could read
  FALSE RETAKE     a readable page rejected — a family is told to re-photograph a report we could read

The first is a safety failure and the second is a usability failure. A single accuracy number would hide
which one a candidate trades for the other.
"""
import json
import re
import sys

MIN_CONFIDENCE = 0.80          # baymax.extract.min-confidence-overall
MIN_VERIFIED_ITEMS = 3         # a one-value page cannot pass the override on a single confirmation
MIN_VERIFICATION_RATE = 0.80


def load_answer_key(path):
    """The human column out of the review table: document -> yes | no | partly."""
    key = {}
    for line in open(path, encoding="utf-8"):
        if not line.startswith("|") or "images/" not in line:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        m = re.search(r"`([0-9a-f]{8})`", cells[1])
        if not m:
            continue
        answer = cells[-1].strip().lower()
        if answer in ("yes", "no", "partly"):
            key[m.group(1)] = answer
    return key


def load_rates(path):
    rates = {}
    for line in open(path, encoding="utf-8"):
        _, _, payload = line.strip().partition("|")
        if payload.startswith("{"):
            r = json.loads(payload)
            rates[r["document_id"][:8]] = r
    return rates


def load_agreement(path):
    """document -> whether two extractions of the same image read the same values."""
    if not path:
        return {}
    out = {}
    for line in open(path, encoding="utf-8"):
        parts = line.strip().split("|")
        if len(parts) >= 2:
            out[parts[0][:8]] = parts[1].strip().lower() == "agree"
    return out


def score(name, decisions, key):
    """decisions: document -> True when the candidate RETAKES it."""
    missed_bad, false_retake, correct = [], [], 0
    for doc, answer in key.items():
        if doc not in decisions:
            continue
        retaken = decisions[doc]
        # "partly" counts as a page that SHOULD be retaken: half a report is not a report.
        should_retake = answer in ("no", "partly")
        if retaken == should_retake:
            correct += 1
        elif should_retake and not retaken:
            missed_bad.append(doc)
        else:
            false_retake.append(doc)
    return {"name": name, "correct": correct, "missed_bad": missed_bad,
            "false_retake": false_retake, "n": sum(1 for d in key if d in decisions)}


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    key = load_answer_key(sys.argv[1])
    rates = load_rates(sys.argv[2])
    agreement = load_agreement(sys.argv[3] if len(sys.argv) > 3 else None)

    if not key:
        sys.exit("No answers found in the review file. Fill the 'readable?' column with yes/no/partly first.")

    current, override, agree = {}, {}, {}
    for doc, r in rates.items():
        if doc not in key:
            continue
        confidence = r.get("model_confidence")
        low_confidence = confidence is None or confidence < MIN_CONFIDENCE
        current[doc] = low_confidence

        rate = r.get("rate")
        well_verified = (rate is not None and rate >= MIN_VERIFICATION_RATE
                         and r.get("confirmed", 0) >= MIN_VERIFIED_ITEMS)
        override[doc] = low_confidence and not well_verified

        if doc in agreement:
            agree[doc] = not agreement[doc]

    results = [score("a. CURRENT (confidence only)", current, key),
               score("b. OVERRIDE (confidence AND verification)", override, key)]
    if agree:
        results.append(score("c. AGREEMENT (two extractions)", agree, key))

    readable = sum(1 for a in key.values() if a == "yes")
    print("# Retake gate candidates, scored against a human answer key\n")
    print(f"Answer key: **{len(key)} documents** reviewed — {readable} readable, "
          f"{sum(1 for a in key.values() if a == 'no')} unreadable, "
          f"{sum(1 for a in key.values() if a == 'partly')} partly.\n")
    print("Every document here was RETAKEN by the current gate, so a readable one is a false retake that "
          "already happened.\n")
    print("| candidate | n | correct | missed bad pages | false retakes |")
    print("|---|---|---|---|---|")
    for r in results:
        print(f"| {r['name']} | {r['n']} | {r['correct']} | **{len(r['missed_bad'])}** | "
              f"**{len(r['false_retake'])}** |")

    print("\n## What each candidate gets wrong\n")
    for r in results:
        print(f"### {r['name']}\n")
        if r["missed_bad"]:
            print(f"- **missed bad pages** ({len(r['missed_bad'])}): "
                  f"{', '.join('`' + d + '`' for d in r['missed_bad'])}")
        if r["false_retake"]:
            print(f"- **false retakes** ({len(r['false_retake'])}): "
                  f"{', '.join('`' + d + '`' for d in r['false_retake'])}")
        if not r["missed_bad"] and not r["false_retake"]:
            print("- nothing wrong on this set")
        print()

    print("## Rules as scored\n")
    print(f"- CURRENT: retake when confidence < {MIN_CONFIDENCE}")
    print(f"- OVERRIDE: retake when confidence < {MIN_CONFIDENCE} AND NOT "
          f"(rate >= {MIN_VERIFICATION_RATE} AND confirmed >= {MIN_VERIFIED_ITEMS})")
    print("- AGREEMENT: retake when two extractions of the same image read different values")
    print("\n\"partly\" is scored as a page that SHOULD be retaken: half a report is not a report.")


if __name__ == "__main__":
    main()
