"""JFR execution-sample hotspots for one run's measurement window.

    python tools/stage9/lib/jfrtop.py <run_dir> [--thread "Server thread"] [--top 25] [--depth 40]

Prints self-time (top frame) and inclusive-time (any frame within --depth) shares, per thread group, and writes
jvm/hotspots.json. Shares are of execution samples in the window (JFR default settings sample Java threads every 20 ms)."""
from __future__ import annotations

import argparse
import collections
import json
import pathlib
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from lib.analyze import JFR, _iso_ms  # noqa: E402


def frame_name(frame: dict) -> str:
    method = frame.get("method") or {}
    owner = (method.get("type") or {}).get("name", "?")
    return f"{owner}.{method.get('name', '?')}"


def group(thread: str) -> str:
    for prefix in ("Server thread", "Worker-Main", "Netty", "C2ME", "Chunk", "IO-Worker", "Phoenium", "phoenium"):
        if thread.startswith(prefix):
            return prefix
    return thread.split("-")[0].split("#")[0].strip() or thread


def hotspots(run_dir: pathlib.Path, depth: int = 40, top: int = 25, window_phases: tuple[str, str] = ("measureStart", "measureEnd")) -> dict:
    phases = json.loads((run_dir / "phases.json").read_text())
    window = (phases[window_phases[0]], phases[window_phases[1]])
    out = subprocess.run([JFR, "print", "--json", "--stack-depth", str(depth), "--events", "jdk.ExecutionSample",
                          str(run_dir / "jvm" / "server.jfr")], capture_output=True, text=True, encoding="utf-8",
                         errors="replace", timeout=1800)
    events = json.loads(out.stdout)["recording"]["events"]
    per_group: dict[str, dict] = collections.defaultdict(lambda: {"samples": 0, "self": collections.Counter(),
                                                                   "incl": collections.Counter()})
    for event in events:
        values = event["values"]
        if not (window[0] <= _iso_ms(values["startTime"]) < window[1]):
            continue
        thread = (values.get("sampledThread") or {}).get("javaName") or "?"
        frames = (values.get("stackTrace") or {}).get("frames") or []
        if not frames:
            continue
        g = per_group[group(thread)]
        g["samples"] += 1
        g["self"][frame_name(frames[0])] += 1
        for name in {frame_name(f) for f in frames}:
            g["incl"][name] += 1
    result = {}
    for name, g in sorted(per_group.items(), key=lambda kv: -kv[1]["samples"]):
        n = g["samples"]
        result[name] = {"samples": n,
                        "self": [(k, round(v / n, 4)) for k, v in g["self"].most_common(top)],
                        "inclusive": [(k, round(v / n, 4)) for k, v in g["incl"].most_common(top)]}
    suffix = "" if window_phases == ("measureStart", "measureEnd") else f"-{window_phases[0]}-{window_phases[1]}"
    (run_dir / "jvm" / f"hotspots{suffix}.json").write_text(json.dumps(result, indent=1))
    return result


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("run_dir")
    ap.add_argument("--thread", default="Server thread")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--window", default="measureStart:measureEnd",
                    help="phase pair from phases.json, e.g. joined:end for a run that crashed before measuring")
    ap.add_argument("--depth", type=int, default=40)
    a = ap.parse_args()
    res = hotspots(pathlib.Path(a.run_dir), a.depth, a.top, tuple(a.window.split(":")))
    for name, g in list(res.items())[:6]:
        print(f"== {name}: {g['samples']} samples")
    g = res.get(a.thread)
    if g:
        print(f"\n== {a.thread} SELF")
        for k, v in g["self"]:
            print(f"{v*100:6.2f}%  {k}")
        print(f"\n== {a.thread} INCLUSIVE")
        for k, v in g["inclusive"]:
            print(f"{v*100:6.2f}%  {k}")


if __name__ == "__main__":
    main()
