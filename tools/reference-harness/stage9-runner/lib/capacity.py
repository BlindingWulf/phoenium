"""Capacity summary from COMPLETE runs: per (stack, scenario, heap, fixture, jvm args) the largest tier at which every
tested tier up to and including it passed BOTH verdicts (CORRECTNESS and PERFORMANCE).

    python tools/stage9/lib/capacity.py "q*" [--out STAGE9_CAPACITY_SUMMARY.json] [--exclude q1-B-vmp-region-t50 ...]

When one key/tier has several runs (repeats, harness reruns), the most recent one decides and the others are listed
under `superseded`. A run that HARNESS_HEALTH flags as SUSPECT never counts as a pass. `--exclude` names runs whose
evidence was ruled invalid elsewhere (the reason must be recorded in the final report)."""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import json
import pathlib
import platform
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from lib import analyze  # noqa: E402
from lib.compare import RUNS, row  # noqa: E402


def key_of(meta: dict, summary: dict) -> tuple:
    args = meta.get("args") or {}
    return (summary.get("stack"), summary.get("scenario"), args.get("heap") or "4G",
            (meta.get("fixture") or {}).get("name", "") or "", " ".join(args.get("jvm_arg") or []))


def build(pattern: str, exclude: set[str]) -> dict:
    groups: dict[tuple, dict[int, list]] = collections.defaultdict(lambda: collections.defaultdict(list))
    for run_dir in sorted(RUNS.glob(pattern)):
        if not run_dir.is_dir() or run_dir.name in exclude:
            continue
        r = row(run_dir)
        if r is None or not r["tier"]:
            continue
        meta = json.loads((run_dir / "metadata.json").read_text())
        summary = json.loads((run_dir / "summary.json").read_text())
        r["createdAt"] = meta.get("createdAt")
        r["harnessCommit"] = meta.get("harnessCommit")
        groups[key_of(meta, summary)][r["tier"]].append(r)
    arms = []
    for (stack, scenario, heap, fixture, jvm), tiers in sorted(groups.items(), key=lambda kv: tuple(map(str, kv[0]))):
        tested, capacity, first_fail = [], 0, None
        for tier in sorted(tiers):
            runs = sorted(tiers[tier], key=lambda r: r["createdAt"] or "")
            decisive = runs[-1]
            ok = decisive["correctness"] == "PASS" and decisive["performance"] == "PASS" and decisive["harness"] == "OK"
            tested.append({"tier": tier, "pass": ok, "run": decisive["runId"], "correctness": decisive["correctness"],
                           "performance": decisive["performance"], "harness": decisive["harness"],
                           "tps": decisive["tpsMedian10s"], "msptP50": decisive["msptP50"], "msptP95": decisive["msptP95"],
                           "gcP99Ms": decisive["gcP99Ms"], "liveSetLastMB": decisive["liveSetLastMB"],
                           "serverCores": decisive["serverCores"],
                           "reasons": [x for x in (decisive["correctnessReasons"], decisive["performanceReasons"]) if x],
                           "superseded": [r["runId"] for r in runs[:-1]]})
            if ok and first_fail is None:
                capacity = tier
            elif not ok and first_fail is None:
                first_fail = tier
        arms.append({"stack": stack, "scenario": scenario, "heap": heap, "fixture": fixture, "jvmArgs": jvm,
                     "capacityTier": capacity, "firstFailingTier": first_fail,
                     "note": None if first_fail else ("all tested tiers pass" if tested else None), "tiers": tested})
    return {"schema": "stage9-capacity-v1", "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
            "pattern": pattern, "excluded": sorted(exclude),
            "definition": "capacityTier = largest tested tier such that it and every lower tested tier passed both verdicts "
                          "(decisive run = most recent per tier); tiers are 1/4/8/16/32/50 synthetic players",
            "desktopHost": {"machine": platform.machine(), "os": platform.platform(),
                            "cpu": "AMD Ryzen 7 9800X3D (8C/16T)", "jdk": "Temurin 25.0.4.1"},
            "gates": {"desktop": analyze.DESKTOP_GATES, "joins": analyze.JOIN_GATES,
                      "actions": 0.99, "unintendedDisconnectsMax": "2% of tier", "analyzerVersion": analyze.ANALYZER_VERSION},
            "arms": arms}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("pattern")
    ap.add_argument("--out")
    ap.add_argument("--exclude", nargs="*", default=[])
    a = ap.parse_args()
    result = build(a.pattern, set(a.exclude))
    if a.out:
        pathlib.Path(a.out).write_text(json.dumps(result, indent=1))
    for arm in result["arms"]:
        tiers = " ".join(f"{t['tier']}{'+' if t['pass'] else 'x'}" for t in arm["tiers"])
        print(f"{arm['stack']:24s} {arm['scenario']:15s} heap={arm['heap']:6s} {arm['fixture'][:14]:14s} "
              f"capacity={arm['capacityTier']:>3} firstFail={arm['firstFailingTier']}  [{tiers}]")


if __name__ == "__main__":
    main()
