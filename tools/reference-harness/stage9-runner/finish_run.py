"""Finish a run whose measurement and teardown completed but whose summary step failed in the harness.

    python tools/stage9/finish_run.py <run-id> --reason "..."

Only for a run directory that has metadata.json written by the teardown (phases with "end", a "stop" record), no
summary.json, and neither COMPLETE nor ABORTED. Computes the summary exactly as run_stage9.run() does after teardown
(ticks, GC, process, hotspots, allocation views, bots, verdict, Phoenium counters), records who finished it and why
(summary "finishedBy"), prunes the copied static inputs, then replaces RUNNING with COMPLETE. Run it inside a queue
hold: it parses the run's JFR recording."""
from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
import types

import run_stage9 as rs
from run_stage9 import analyze, jfrtop, stack


def finish(run_id: str, reason: str) -> int:
    run_dir = rs.AUDIT / "runs" / run_id
    meta_path = run_dir / "metadata.json"
    if not meta_path.is_file():
        raise SystemExit(f"no metadata.json in {run_dir}")
    for marker in ("summary.json", "COMPLETE", "ABORTED"):
        if (run_dir / marker).exists():
            raise SystemExit(f"{run_id} already has {marker}: nothing to finish")
    meta = json.loads(meta_path.read_text())
    phases = json.loads((run_dir / "phases.json").read_text())
    if "end" not in phases or "stop" not in meta:
        raise SystemExit(f"{run_id} did not finish its teardown (phases.end / stop missing): rerun it instead")
    args = types.SimpleNamespace(**meta["args"])
    crashed = bool((meta.get("stop") or {}).get("alreadyExited"))

    window = (phases.get("measureStart", phases["end"]), phases.get("measureEnd", phases["end"]))
    summary: dict = {"runId": run_id, "stack": args.stack, "scenario": args.scenario, "tier": args.tier, "seed": args.seed}
    try:
        summary["ticks"] = analyze.tick_metrics(run_dir, window)
    except Exception as error:  # noqa: BLE001
        summary["ticks"] = {"ticks": 0, "error": str(error)}
    try:
        summary["gc"] = analyze.gc_metrics(run_dir, window)
    except Exception as error:  # noqa: BLE001
        summary["gc"] = {"available": False, "reason": str(error)}
    summary["process"] = analyze.process_metrics(run_dir, window)
    try:
        if (run_dir / "jvm" / "server.jfr").exists() and "measureStart" in phases:
            server_thread = jfrtop.hotspots(run_dir).get("Server thread", {})
            summary["hotspots"] = {"serverThreadSamples": server_thread.get("samples", 0),
                                   "inclusiveTop": server_thread.get("inclusive", [])[:12],
                                   "selfTop": server_thread.get("self", [])[:8]}
    except Exception as error:  # noqa: BLE001
        summary["hotspots"] = {"error": str(error)}
    try:
        if (run_dir / "jvm" / "server.jfr").exists() and not (run_dir / "jvm" / "allocation-views.txt").exists():
            views = [subprocess.run([analyze.JFR, "view", "--width", "200", view, str(run_dir / "jvm" / "server.jfr")],
                                    capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=300).stdout
                     for view in ("allocation-by-class", "allocation-by-thread", "allocation-by-site")]
            (run_dir / "jvm" / "allocation-views.txt").write_text("\n".join(views))
    except Exception as error:  # noqa: BLE001
        meta["allocationViewsError"] = str(error)[:300]
    summary["bots"] = analyze.bot_metrics(run_dir, args.tier)
    try:  # S9-E8: the measure window's engine counters (two phoenium marks); None before D26
        workers = stack.STACKS[args.stack].get("sysprops", {}).get("phoenium.engine.workers")
        summary["engineWindow"] = analyze.engine_window(run_dir, int(workers) if workers else None)
    except Exception as error:  # noqa: BLE001
        summary["engineWindow"] = {"error": str(error)[:300]}
    stop = meta.get("phoeniumStop")
    if isinstance(stop, str):  # recorded raw by the failing harness: parse it as run_stage9 now does
        parsed = analyze.engine_stop(stop)
        if parsed is not None:
            meta["phoeniumStop"] = stop = parsed
    ownership_bad = isinstance(stop, dict) and (stop.get("ownershipViolations") or 0) > 0
    engine_stop = stop if isinstance(stop, dict) and "traps" in stop else None
    traps_bad = [f"Phoenium engine traps {engine_stop['traps']} > 0 (load/write/storage)"] if engine_stop and engine_stop["traps"] else []
    traps_bad += [f"Phoenium engine self-check faults {engine_stop['indexFaults']} > 0 (tracker index, spawn memo, protection memberships)"] \
        if engine_stop and engine_stop.get("indexFaults") else []
    # metadata keeps the first 50 bad lines; the verdict only counts them
    bad = list(meta.get("badLogLines") or []) + (["Phoenium ownershipViolations > 0"] if ownership_bad else []) + traps_bad
    summary["verdict"] = analyze.verdicts(args.tier, summary["ticks"], summary["gc"], summary["process"], summary["bots"],
                                          bad, crashed or bool(meta.get("exception")), args.controller_cores_limit)
    summary["stop"] = meta.get("stop")
    if stack.STACKS[args.stack]["phoenium"]:
        summary["phoenium"] = {"start": meta.get("phoeniumStart"), "stop": meta.get("phoeniumStop")}
    summary["finishedBy"] = {"tool": "tools/stage9/finish_run.py", "at": rs.utc(), "reason": reason}
    rs.write_json(run_dir / "summary.json", summary)
    flat = {"runId": run_id, "stack": args.stack, "scenario": args.scenario, "tier": args.tier, "seed": args.seed,
            **{f"ticks.{k}": v for k, v in summary["ticks"].items() if not isinstance(v, (dict, list))},
            **{f"gc.{k}": v for k, v in summary["gc"].items() if not isinstance(v, (dict, list))},
            **({f"phoenium.{k}": engine_stop.get(k) for k in (*analyze.ENGINE_TRAPS, "traps", *analyze.ENGINE_SERVICES,
                                                                *analyze.ENGINE_TRACKER, *analyze.ENGINE_MEMO, "indexFaults")} if engine_stop else {}),
            **analyze.efficiency(args.tier, summary["process"], summary["gc"]),
            **analyze.census_columns(meta),
            **({f"win.{k}": v for k, v in summary["engineWindow"].items() if not isinstance(v, (dict, list))}
               if isinstance(summary.get("engineWindow"), dict) else {}),
            "correctness": summary["verdict"]["CORRECTNESS"], "performance": summary["verdict"]["PERFORMANCE"],
            "harness": summary["verdict"]["HARNESS_HEALTH"], "finishedBy": "finish_run.py"}
    with open(run_dir / "summary.csv", "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(flat))
        writer.writeheader()
        writer.writerow(flat)
    meta["verdict"] = summary["verdict"]
    meta["finishedBy"] = summary["finishedBy"]
    if not args.keep_server and not meta.get("orphans"):
        meta["pruned"] = rs.prune_static(run_dir / "server", meta["install"])
    rs.write_json(meta_path, meta)
    (run_dir / "RUNNING").unlink(missing_ok=True)
    (run_dir / "COMPLETE").write_text(rs.utc())
    print(json.dumps({"runId": run_id, "verdict": summary["verdict"], "ticks": summary["ticks"],
                      "phoenium": summary.get("phoenium")}, indent=1))
    return 0


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("run_id")
    p.add_argument("--reason", required=True)
    a = p.parse_args()
    return finish(a.run_id, a.reason)


if __name__ == "__main__":
    sys.exit(main())
