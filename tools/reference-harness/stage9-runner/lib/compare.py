"""One row per finished run: the numbers the Stage 9 gates use, plus the top server-thread hotspot.

    python tools/stage9/lib/compare.py "q1-*"            # table on stdout
    python tools/stage9/lib/compare.py "q*" --csv out.csv  # also write a CSV (STAGE9_BENCHMARK_MATRIX.csv uses this)

Only run dirs with COMPLETE are read; smoke-* and calibration runs are skipped unless the pattern names them."""
from __future__ import annotations

import argparse
import csv
import json
import pathlib

RUNS = pathlib.Path(__file__).resolve().parents[3] / ".audit" / "stage9-20261009" / "runs"

COLUMNS = ["runId", "stack", "scenario", "tier", "editions", "fixture", "correctness", "performance", "harness",
           "tpsMedian10s", "tpsMin10s", "msptP50", "msptP95", "msptP99", "intervalP50", "stallS", "gcShare", "gcP99Ms",
           "gcMaxMs", "liveSetFirstMB", "liveSetLastMB", "liveSetGrowth", "serverCores", "serverRssMB", "botsCores",
           "proxyCores", "hostCpuP95", "joinedFirst", "joinedAny", "expected", "actionRate", "unintendedDisconnects",
           "portalDefects", "deaths", "topInclusive", "performanceReasons", "correctnessReasons"]


def row(run_dir: pathlib.Path) -> dict | None:
    if not (run_dir / "COMPLETE").exists() or not (run_dir / "summary.json").exists():
        return None
    s = json.loads((run_dir / "summary.json").read_text())
    meta = json.loads((run_dir / "metadata.json").read_text()) if (run_dir / "metadata.json").exists() else {}
    t, g, p, b, v = s.get("ticks", {}), s.get("gc", {}), s.get("process", {}), s.get("bots", {}), s.get("verdict", {})
    hot = (s.get("hotspots") or {}).get("inclusiveTop") or []
    # the first frame below the generic server loop that still holds a large share
    generic = ("runServer", "spin", "processPacketsAndTick", "Thread.run", "Lambda", "tickServer", "tickChildren")
    top = next((f"{name.split('/')[-1]} {share:.0%}" for name, share in hot if not any(x in name for x in generic)), "")
    return {
        "runId": s.get("runId"), "stack": s.get("stack"), "scenario": s.get("scenario"), "tier": s.get("tier"),
        "editions": "/".join(f"{k}={n}" for k, n in (meta.get("editions") or {}).items()),
        "fixture": (meta.get("fixture") or {}).get("name", ""),
        "correctness": v.get("CORRECTNESS"), "performance": v.get("PERFORMANCE"), "harness": v.get("HARNESS_HEALTH"),
        "tpsMedian10s": t.get("tpsMedian10s"), "tpsMin10s": t.get("tpsMin10s"), "msptP50": t.get("msptP50"),
        "msptP95": t.get("msptP95"), "msptP99": t.get("msptP99"), "intervalP50": t.get("intervalP50"), "stallS": t.get("stallS"),
        "gcShare": g.get("pauseShare"), "gcP99Ms": g.get("pauseP99Ms"), "gcMaxMs": g.get("pauseMaxMs"),
        "liveSetFirstMB": g.get("liveSetFirstMB"), "liveSetLastMB": g.get("liveSetLastMB"), "liveSetGrowth": g.get("liveSetGrowth"),
        "serverCores": (p.get("server") or {}).get("coresMean"), "serverRssMB": (p.get("server") or {}).get("rssMBMax"),
        "botsCores": (p.get("bots") or {}).get("coresMean"), "proxyCores": (p.get("proxy") or {}).get("coresMean"),
        "hostCpuP95": p.get("hostCpuPctP95"), "joinedFirst": b.get("joinedFirstAttempt"), "joinedAny": b.get("joinedAny"),
        "expected": b.get("expected"), "actionRate": b.get("actionSuccessRate"), "unintendedDisconnects": b.get("unintendedDisconnects"),
        "portalDefects": b.get("portalDefectCount"), "deaths": b.get("deaths"), "topInclusive": top,
        "performanceReasons": "; ".join(v.get("performanceReasons") or []), "correctnessReasons": "; ".join(v.get("correctnessReasons") or []),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("pattern")
    ap.add_argument("--csv")
    a = ap.parse_args()
    rows = [r for d in sorted(RUNS.glob(a.pattern)) if d.is_dir() for r in [row(d)] if r]
    if a.csv:
        with open(a.csv, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=COLUMNS)
            w.writeheader()
            w.writerows(rows)
    short = ["runId", "correctness", "performance", "tpsMedian10s", "msptP50", "msptP95", "intervalP50", "gcShare",
             "liveSetLastMB", "liveSetGrowth", "serverCores", "joinedFirst", "actionRate", "topInclusive"]
    print(" | ".join(short))
    for r in rows:
        print(" | ".join("" if r[k] is None else (f"{r[k]:.3f}" if isinstance(r[k], float) else str(r[k])) for k in short))


if __name__ == "__main__":
    main()
