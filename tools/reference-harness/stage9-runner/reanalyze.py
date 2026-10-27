"""Re-judge COMPLETE runs with the current analyzer's bot accounting and verdicts (no JFR re-parse, no new measurements).

    python tools/stage9/reanalyze.py "q1-*" [--dry-run]

Tick, GC, process and hotspot metrics are taken unchanged from the run's own summary.json; only bot metrics (bots.jsonl)
and the verdicts are recomputed. The previous files are kept, renamed with os.rename to
summary.analyzer-v<N>.json / .csv (never deleted), and the new summary records analyzerVersion and reanalyzedAt.
A run whose summary already carries the current analyzer version is skipped."""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import fnmatch
import json
import os
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from lib import analyze  # noqa: E402

RUNS = HERE.parents[1] / ".audit" / "stage9-20261009" / "runs"


def reanalyze(run_dir: pathlib.Path, dry_run: bool) -> dict | None:
    summary = json.loads((run_dir / "summary.json").read_text())
    old_version = summary.get("analyzerVersion") or (summary.get("bots") or {}).get("analyzerVersion") or 1
    if old_version >= analyze.ANALYZER_VERSION:
        return None
    meta = json.loads((run_dir / "metadata.json").read_text())
    tier = summary["tier"]
    bots = analyze.bot_metrics(run_dir, tier)
    stop = meta.get("stop") or {}
    crashed = bool(stop.get("alreadyExited")) or bool(meta.get("exception"))
    phoenium = meta.get("phoeniumStop")
    ownership_bad = isinstance(phoenium, dict) and (phoenium.get("ownershipViolations") or 0) > 0
    bad = list(meta.get("badLogLines") or []) + (["Phoenium ownershipViolations > 0"] if ownership_bad else [])
    limit = (meta.get("args") or {}).get("controller_cores_limit", 3.0)
    verdict = analyze.verdicts(tier, summary["ticks"], summary["gc"], summary["process"], bots, bad, crashed, limit)
    change = {"runId": summary["runId"], "from": old_version, "to": analyze.ANALYZER_VERSION,
              "before": {k: summary["verdict"][k] for k in ("CORRECTNESS", "PERFORMANCE", "HARNESS_HEALTH")},
              "after": {k: verdict[k] for k in ("CORRECTNESS", "PERFORMANCE", "HARNESS_HEALTH")},
              "beforeReasons": summary["verdict"]["correctnessReasons"] + summary["verdict"]["harnessReasons"],
              "afterReasons": verdict["correctnessReasons"] + verdict["harnessReasons"]}
    if dry_run:
        return change
    for name in ("summary.json", "summary.csv"):
        stem, ext = name.split(".")
        os.rename(run_dir / name, run_dir / f"{stem}.analyzer-v{old_version}.{ext}")
    summary.update({"bots": bots, "verdict": verdict, "analyzerVersion": analyze.ANALYZER_VERSION,
                    "reanalyzedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                    "reanalysis": change})
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=1))
    flat = {"runId": summary["runId"], "stack": summary["stack"], "scenario": summary["scenario"], "tier": tier,
            "seed": summary["seed"],
            **{f"ticks.{k}": v for k, v in summary["ticks"].items() if not isinstance(v, (dict, list))},
            **{f"gc.{k}": v for k, v in summary["gc"].items() if not isinstance(v, (dict, list))},
            "correctness": verdict["CORRECTNESS"], "performance": verdict["PERFORMANCE"],
            "harness": verdict["HARNESS_HEALTH"], "analyzerVersion": analyze.ANALYZER_VERSION}
    with open(run_dir / "summary.csv", "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(flat))
        writer.writeheader()
        writer.writerow(flat)
    return change


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("pattern")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    for run_dir in sorted(RUNS.iterdir()):
        if not (run_dir.is_dir() and fnmatch.fnmatch(run_dir.name, a.pattern) and (run_dir / "COMPLETE").exists()):
            continue
        change = reanalyze(run_dir, a.dry_run)
        if change is None:
            print(f"{run_dir.name}: already analyzer v{analyze.ANALYZER_VERSION}")
        else:
            print(json.dumps(change))
    return 0


if __name__ == "__main__":
    sys.exit(main())
