#!/usr/bin/env python3
"""Same-runner A/B regression gate for the curated JMH subset.

Compares two JMH JSON result files produced on the SAME machine in the SAME job:
the base commit and the candidate commit. A benchmark regresses only when the
candidate is more than THRESHOLD times slower AND the gap is larger than the two
runs' combined error bars, so a 1 ns score's noise cannot fail the build.

Comparing against scores stored from earlier runs (the previous design) failed on
runner-hardware swings alone: on 2026-09-24 every gated benchmark read ~2x slower
on one runner while a local A/B of the same two commits showed no change.

Usage: ab-gate.py BASE.json CANDIDATE.json [--threshold 2.0]
Exit 0 = no regression, 1 = regression, 2 = unusable input (fail closed).
"""
import argparse
import json
import sys


def load(path):
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError) as exc:
        print(f"::error::cannot read JMH results {path}: {exc}")
        sys.exit(2)
    scores = {}
    for bench in data:
        params = bench.get("params") or {}
        key = bench["benchmark"] + (" " + json.dumps(params, sort_keys=True) if params else "")
        metric = bench["primaryMetric"]
        error = metric.get("scoreError")
        if not isinstance(error, (int, float)) or error != error:  # NaN when too few iterations
            error = 0.0
        scores[key] = (float(metric["score"]), float(error), metric.get("scoreUnit", ""))
    return scores


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("base")
    parser.add_argument("candidate")
    parser.add_argument("--threshold", type=float, default=2.0)
    args = parser.parse_args()

    base = load(args.base)
    cand = load(args.candidate)
    shared = sorted(set(base) & set(cand))
    if not shared:
        print("::error::no benchmark appears in both result files; the gate cannot compare anything")
        return 2

    regressions = []
    print(f"{'benchmark':<90} {'base':>12} {'candidate':>12} {'ratio':>6}")
    for key in shared:
        b, b_err, unit = base[key]
        c, c_err, _ = cand[key]
        ratio = c / b if b > 0 else float("inf")
        # Scores are average time (lower is better): slower means a larger score.
        regressed = ratio > args.threshold and (c - b) > (b_err + c_err)
        flag = "  REGRESSION" if regressed else ""
        print(f"{key[-90:]:<90} {b:>9.3f}±{b_err:<4.2f} {c:>9.3f}±{c_err:<4.2f} {ratio:>6.2f}{flag}")
        if regressed:
            regressions.append((key, ratio, unit))

    for name in sorted(set(cand) - set(base)):
        print(f"new (no base score, not gated): {name}")

    if regressions:
        for key, ratio, unit in regressions:
            print(f"::error::{key} is {ratio:.2f}x slower than the base commit on the same runner ({unit})")
        return 1
    print(f"OK: no benchmark regressed more than {args.threshold}x beyond its error bars")
    return 0


if __name__ == "__main__":
    sys.exit(main())
