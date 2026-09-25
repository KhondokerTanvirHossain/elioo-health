#!/usr/bin/env python3
"""
Does image-side verification separate readable pages from unreadable ones better than the model's own
confidence does?

    scripts/verification_rate_report.py <rates.txt>

Input is one "YYYY-MM-DD|{json}" line per document from the read-only verification-rate probe.

THE QUESTION. The retake gate rests on the model's self-reported confidence, which moved 0.85 -> 0.70 on the
same image between two runs while the values it read stayed identical. Image-side verification offers an
objective alternative: for each gating item, whether something other than the extraction read the value back
off the page. If that separates good pages from unreadable ones, it is a better gate input. If it does not,
saying so is the result.

NOTHING HERE CHANGES THE GATE. This reports; a threshold is proposed only if the separation is real.

Prints counts and rates, never a reading.
"""
import json
import statistics
import sys
from collections import defaultdict


def load(path):
    rows = []
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if "|" not in line:
            continue
        day, _, payload = line.partition("|")
        if not payload.startswith("{"):
            continue
        try:
            row = json.loads(payload)
        except json.JSONDecodeError:
            continue
        row["day"] = day
        rows.append(row)
    return rows


def describe(values):
    if not values:
        return "—"
    if len(values) == 1:
        return f"{values[0]:.2f}"
    return (f"{statistics.mean(values):.2f} "
            f"(min {min(values):.2f}, max {max(values):.2f}, n={len(values)})")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    rows = [r for r in load(sys.argv[1]) if r.get("outcome") == "measured"]
    judgeable = [r for r in rows if r.get("rate") is not None]

    print("# Verification rate vs model confidence\n")
    print("For every document with stored extraction: what fraction of its GATING items could be confirmed "
          "on the page by something other than the extraction, beside the model's own confidence score.\n")
    print("**Nothing is switched.** The gate still uses model confidence alone.\n")
    print(f"- documents measured: **{len(rows)}**")
    print(f"- with gating items to judge: **{len(judgeable)}** "
          f"({len(rows) - len(judgeable)} have none and yield no rate)\n")

    # The comparison that matters: does either signal separate the documents the gate ACCEPTED from the ones
    # it REJECTED? Confidence separates them by construction — it IS the gate — so the honest question is
    # whether verification adds information confidence does not already carry.
    print("## By gate decision\n")
    print("| Status | n | verification rate | model confidence |")
    print("|---|---|---|---|")
    by_status = defaultdict(list)
    for r in judgeable:
        by_status[r["status"]].append(r)
    for status in sorted(by_status):
        group = by_status[status]
        rates = [r["rate"] for r in group]
        confidences = [r["model_confidence"] for r in group if r.get("model_confidence") is not None]
        print(f"| {status} | {len(group)} | {describe(rates)} | {describe(confidences)} |")

    print("\n## The documents the gate REJECTED, by verification rate\n")
    print("A rejected document that verifies well is a page we could read and sent back anyway.\n")
    retaken = sorted([r for r in judgeable if r["status"] == "NEEDS_RETAKE"],
                     key=lambda r: r["rate"], reverse=True)
    if not retaken:
        print("_None._")
    else:
        print("| document | type | confirmed/extracted | rate | model confidence |")
        print("|---|---|---|---|---|")
        for r in retaken:
            print(f"| `{r['document_id'][:8]}` | {r['document_type']} | "
                  f"{r['confirmed']}/{r['extracted']} | {r['rate']:.2f} | {r['model_confidence']} |")

    print("\n## The documents the gate ACCEPTED, worst verification first\n")
    print("An accepted document that verifies poorly is a page we showed a family without being able to "
          "point at most of it.\n")
    accepted = sorted([r for r in judgeable if r["status"] == "DONE"], key=lambda r: r["rate"])
    print("| document | type | confirmed/extracted | rate | model confidence |")
    print("|---|---|---|---|---|")
    for r in accepted[:15]:
        print(f"| `{r['document_id'][:8]}` | {r['document_type']} | "
              f"{r['confirmed']}/{r['extracted']} | {r['rate']:.2f} | {r['model_confidence']} |")

    # Separation: how much do the two populations overlap under each signal? A signal that cannot tell them
    # apart is not a gate input, however appealing the idea.
    print("\n## Separation\n")
    done_rates = [r["rate"] for r in judgeable if r["status"] == "DONE"]
    retake_rates = [r["rate"] for r in judgeable if r["status"] == "NEEDS_RETAKE"]
    if done_rates and retake_rates:
        overlap = sum(1 for x in retake_rates if x >= min(done_rates))
        print(f"- accepted documents' verification rate: {describe(done_rates)}")
        print(f"- rejected documents' verification rate: {describe(retake_rates)}")
        print(f"- rejected documents scoring at least as well as the WORST accepted one: "
              f"**{overlap} of {len(retake_rates)}**")
        if overlap == len(retake_rates):
            print("\n**The two populations do not separate on verification rate at all.** Every document the "
                  "gate rejected verifies at least as well as the worst it accepted, so no threshold on this "
                  "signal could reproduce the gate's decisions — let alone improve them.")
    else:
        print("_Not enough documents in both groups to compare._")


if __name__ == "__main__":
    main()
