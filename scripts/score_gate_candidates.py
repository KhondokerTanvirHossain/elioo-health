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


def load_answer_key(path, context_path):
    """
    The human column out of the review table, mapped back onto every document that shares the image.

    The review is deduplicated: 27 retaken documents are only 8 distinct images, one of them re-uploaded
    twelve times by test runs. Asking for the same page twelve times would have been both wasteful and a
    way to weight one page twelve-fold in the score. CONTEXT.md carries the image -> documents mapping.
    """
    answers = {}
    for line in open(path, encoding="utf-8"):
        if not line.startswith("|") or "images/" not in line:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        m = re.search(r"\[(page\d+)]", cells[1])
        if not m:
            continue
        answer = cells[4].strip().lower()
        if answer in ("yes", "no", "partly"):
            answers[m.group(1)] = (answer, cells[5].strip())

    key = {}
    page = None
    for line in open(context_path, encoding="utf-8"):
        m = re.match(r"- \*\*(page\d+)\*\*: (.+)", line.strip())
        if m:
            page = m.group(1)
            if page in answers:
                for doc in re.findall(r"`([0-9a-f]{8})`", m.group(2)):
                    key[doc] = answers[page]
    return key, answers


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


def score(name, decisions, key, partly_needs_retake):
    """
    decisions: document -> True when the candidate RETAKES it.

    `partly` is scored BOTH ways rather than decided up front. Whether half a readable report should be sent
    back is a product judgement, not an arithmetic one, and the difference between the two scorings is
    itself the finding: if a candidate only wins under one reading of `partly`, it has not won.
    """
    missed_bad, false_retake, correct = [], [], 0
    for doc, (answer, _note) in key.items():
        if doc not in decisions:
            continue
        retaken = decisions[doc]
        should_retake = answer == "no" or (answer == "partly" and partly_needs_retake)
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
    context_path = sys.argv[1].replace("REVIEW.md", "CONTEXT.md")
    key, answers = load_answer_key(sys.argv[1], context_path)
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

    print("# Retake gate candidates, scored against a human answer key\n")
    print(f"**{len(answers)} distinct pages reviewed**, covering {len(key)} retaken documents "
          f"(the same image was re-uploaded by test runs).\n")
    counts = {a: sum(1 for v, _ in answers.values() if v == a) for a in ("yes", "no", "partly")}
    print(f"- readable: **{counts['yes']}**   unreadable: **{counts['no']}**   partly: **{counts['partly']}**\n")
    print("Every document scored here was RETAKEN by the current gate, so a page answered `yes` is a false "
          "retake that already happened.\n")

    for partly_retake in (True, False):
        label = "`partly` = retake" if partly_retake else "`partly` = accept"
        results = [score("a. CURRENT (confidence only)", current, key, partly_retake),
                   score("b. OVERRIDE (confidence AND verification)", override, key, partly_retake)]
        if agree:
            results.append(score("c. AGREEMENT (two extractions)", agree, key, partly_retake))

        print(f"## Scored with {label}\n")
        print("| candidate | documents | correct | missed bad pages | false retakes |")
        print("|---|---|---|---|---|")
        for r in results:
            print(f"| {r['name']} | {r['n']} | {r['correct']} | **{len(r['missed_bad'])}** | "
                  f"**{len(r['false_retake'])}** |")
        print()

    print("## What each candidate gets wrong (`partly` = retake)\n")
    for r in [score("a. CURRENT", current, key, True),
              score("b. OVERRIDE", override, key, True)] + (
              [score("c. AGREEMENT", agree, key, True)] if agree else []):
        print(f"### {r['name']}\n")
        if r["missed_bad"]:
            print(f"- **missed bad pages** ({len(r['missed_bad'])}): "
                  f"{', '.join('`' + d + '`' for d in sorted(set(r['missed_bad'])))}")
        if r["false_retake"]:
            print(f"- **false retakes** ({len(r['false_retake'])}): "
                  f"{', '.join('`' + d + '`' for d in sorted(set(r['false_retake'])))}")
        if not r["missed_bad"] and not r["false_retake"]:
            print("- nothing wrong on this set")
        print()

    print("## Rules as scored\n")
    print(f"- CURRENT: retake when confidence < {MIN_CONFIDENCE}")
    print(f"- OVERRIDE: retake when confidence < {MIN_CONFIDENCE} AND NOT "
          f"(rate >= {MIN_VERIFICATION_RATE} AND confirmed >= {MIN_VERIFIED_ITEMS})")
    print("- AGREEMENT: retake when two extractions of the same image read different values")
    print("\n**If a candidate wins under one reading of `partly` and loses under the other, it has not won.**")


if __name__ == "__main__":
    main()
