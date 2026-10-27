"""X1 aggregate: per (arm, scenario, tier[, heap, fixture]) every COMPLETE run counts (repeats are averaged, never replaced),
with n, the verdict tally and mean / min / max of the gate metrics, plus Phoenium vs the best competitor per cell.

    python tools/stage9/lib/x1summary.py [--glob "x1*"] [--json out.json] [--md out.md]

Phoenium arms are stacks starting with "E" (engine) and "E16-default" (default mode); competitors are the other stacks.
The best competitor of a cell is the one with the lowest mean MSPT p50 among the runs whose PERFORMANCE passed (else
among all). Workload v2 only (scenario names ending in -v2); v1 runs are never mixed in."""
from __future__ import annotations

import argparse
import json
import pathlib
import statistics

RUNS = pathlib.Path(__file__).resolve().parents[3] / ".audit" / "stage9-20261009" / "runs"
METRICS = (("msptP50", "ticks"), ("msptP95", "ticks"), ("msptP99", "ticks"), ("msptP999", "ticks"), ("msptMax", "ticks"),
           ("ticksOver50ms", "ticks"), ("ticksOver100ms", "ticks"), ("tpsMedian10s", "ticks"), ("frameP50", "ticks"),
           ("frameP99", "ticks"), ("coresMean", "server"), ("rssMBMax", "server"), ("liveSetLastMB", "gc"),
           ("pauseMaxMs", "gc"), ("allocRateMBs", "gc"), ("actionSuccessRate", "bots"), ("unintendedDisconnects", "bots"))


def load(glob: str) -> list[dict]:
    rows = []
    for d in sorted(RUNS.glob(glob)):
        f = d / "summary.json"
        if not d.is_dir() or not (d / "COMPLETE").exists() or not f.exists():
            continue
        s = json.loads(f.read_text(encoding="utf-8"))
        if not str(s.get("scenario", "")).endswith("-v2"):
            continue
        meta = json.loads((d / "metadata.json").read_text(encoding="utf-8")) if (d / "metadata.json").exists() else {}
        src = {"ticks": s.get("ticks") or {}, "server": ((s.get("process") or {}).get("server") or {}), "gc": s.get("gc") or {},
               "bots": s.get("bots") or {}}
        row = {"run": d.name, "stack": s["stack"], "scenario": s["scenario"], "tier": s["tier"],
               "heap": (meta.get("args") or {}).get("heap", "4G"), "fixture": (meta.get("fixture") or {}).get("name", ""),
               "C": s["verdict"]["CORRECTNESS"], "P": s["verdict"]["PERFORMANCE"], "H": s["verdict"]["HARNESS_HEALTH"]}
        for key, part in METRICS:
            row[key] = src[part].get(key)
        tier = s["tier"] or 0
        row["coresPerPlayer"] = round(row["coresMean"] / tier, 4) if row.get("coresMean") and tier else None
        row["liveMBPerPlayer"] = round(row["liveSetLastMB"] / tier, 1) if row.get("liveSetLastMB") and tier else None
        rows.append(row)
    return rows


def agg(rows: list[dict]) -> list[dict]:
    cells: dict[tuple, list[dict]] = {}
    for r in rows:
        cells.setdefault((r["stack"], r["scenario"], r["tier"], r["heap"], r["fixture"]), []).append(r)
    out = []
    for (stack, scen, tier, heap, fix), rs in sorted(cells.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][3], kv[0][4], kv[0][0])):
        a = {"stack": stack, "scenario": scen, "tier": tier, "heap": heap, "fixture": fix, "n": len(rs), "runs": [r["run"] for r in rs],
             "verdicts": ["".join(r[k][0] for k in "CPH") for r in rs], "phoenium": stack.startswith("E")}
        for key in [m for m, _ in METRICS] + ["coresPerPlayer", "liveMBPerPlayer"]:
            vals = [r[key] for r in rs if isinstance(r.get(key), (int, float))]
            a[key] = round(statistics.mean(vals), 3) if vals else None
            if len(vals) > 1 and key in ("msptP50", "msptP99", "msptMax", "coresMean", "liveSetLastMB"):
                a[key + "Range"] = [min(vals), max(vals)]
        out.append(a)
    for a in out:
        if not a["phoenium"]:
            continue
        rivals = [b for b in out if not b["phoenium"] and (b["scenario"], b["tier"], b["heap"], b["fixture"]) ==
                  (a["scenario"], a["tier"], a["heap"], a["fixture"]) and b.get("msptP50")]
        passing = [b for b in rivals if all(v[1] == "P" for v in b["verdicts"])] or rivals
        if passing and a.get("msptP50"):
            best = min(passing, key=lambda b: b["msptP50"])
            a["bestCompetitor"] = best["stack"]
            a["p50VsBest"] = round(a["msptP50"] / best["msptP50"], 3)
            if a.get("msptP99") and best.get("msptP99"):
                a["p99VsBest"] = round(a["msptP99"] / best["msptP99"], 3)
            if a.get("coresMean") and best.get("coresMean"):
                a["coresVsBest"] = round(a["coresMean"] / best["coresMean"], 3)
            if a.get("liveSetLastMB") and best.get("liveSetLastMB"):
                a["liveVsBest"] = round(a["liveSetLastMB"] / best["liveSetLastMB"], 3)
    return out


def md(cells: list[dict]) -> str:
    lines = ["| scenario | tier | heap | arm | n | verdicts | p50 | p99 | max | >50ms | TPS | cores | live MB | vs best (p50 / p99 / cores / live) |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for c in cells:
        vs = (f"{c['bestCompetitor']}: {c['p50VsBest']} / {c.get('p99VsBest')} / {c.get('coresVsBest')} / {c.get('liveVsBest')}"
              if c.get("bestCompetitor") else "")
        lines.append(f"| {c['scenario']}{' ' + c['fixture'] if c['fixture'] else ''} | {c['tier']} | {c['heap']} | {c['stack']} | {c['n']} | "
                     f"{' '.join(c['verdicts'])} | {c['msptP50']} | {c['msptP99']} | {c['msptMax']} | {c['ticksOver50ms']} | "
                     f"{c['tpsMedian10s']} | {c['coresMean']} | {c['liveSetLastMB']} | {vs} |")
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--glob", default="x1*")
    ap.add_argument("--json")
    ap.add_argument("--md")
    a = ap.parse_args()
    cells = agg(load(a.glob))
    if a.json:
        pathlib.Path(a.json).write_text(json.dumps(cells, indent=1), encoding="utf-8")
    text = md(cells)
    if a.md:
        pathlib.Path(a.md).write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
