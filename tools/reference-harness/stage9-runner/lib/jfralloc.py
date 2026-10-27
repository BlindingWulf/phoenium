"""JFR allocation profile for one run's measurement window (jdk.ObjectAllocationSample, on in JFR default settings).

    python tools/stage9/lib/jfralloc.py <run_dir> [--top 20] [--depth 24]

Weights are JFR's sampled allocation estimates (bytes allocated by the thread since its previous sample), so shares
are of the estimated allocation volume in the window, not of live memory. Groups by object class, by allocating
thread group, and by the first frame outside the JDK/fastutil/netty plumbing ("site"). Writes jvm/allocations.json.
Run it only on a quiet host (it is a single-threaded parse of the whole recording)."""
from __future__ import annotations

import argparse
import collections
import json
import pathlib
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from lib.analyze import JFR, _iso_ms  # noqa: E402
from lib.jfrtop import frame_name, group  # noqa: E402

PLUMBING = ("java.", "jdk.", "sun.", "it.unimi.", "io.netty.", "com.google.common.", "org.spongepowered.")


def site(frames: list[dict]) -> str:
    for frame in frames:
        name = frame_name(frame).replace("/", ".")
        if not name.startswith(PLUMBING):
            return name
    return frame_name(frames[0]).replace("/", ".") if frames else "?"


def allocations(run_dir: pathlib.Path, depth: int = 24, top: int = 20, window_phases: tuple[str, str] = ("measureStart", "measureEnd")) -> dict:
    phases = json.loads((run_dir / "phases.json").read_text())
    window = (phases[window_phases[0]], phases[window_phases[1]])
    out = subprocess.run([JFR, "print", "--json", "--stack-depth", str(depth), "--events", "jdk.ObjectAllocationSample",
                          str(run_dir / "jvm" / "server.jfr")], capture_output=True, text=True, encoding="utf-8",
                         errors="replace", timeout=1800)
    events = json.loads(out.stdout)["recording"]["events"]
    by_class, by_group, by_site = collections.Counter(), collections.Counter(), collections.Counter()
    # samples whose weight is >= 1 MiB (half a 2 MiB G1 region) point at humongous-allocation candidates; a sample's
    # weight can also accumulate many small allocations, so this is a lead, not proof
    large = collections.Counter()
    total = 0
    for event in events:
        values = event["values"]
        if not (window[0] <= _iso_ms(values["startTime"]) < window[1]):
            continue
        weight = int(values.get("weight") or 0)
        total += weight
        by_class[((values.get("objectClass") or {}).get("name") or "?").replace("/", ".")] += weight
        by_group[group((values.get("eventThread") or {}).get("javaName") or "?")] += weight
        where = site((values.get("stackTrace") or {}).get("frames") or [])
        by_site[where] += weight
        if weight >= 1 << 20:
            large[f"{((values.get('objectClass') or {}).get('name') or '?').replace('/', '.')} @ {where}"] += 1
    seconds = (window[1] - window[0]) / 1000
    share = lambda c: [(k, round(v / total, 4), round(v / seconds / 1048576, 1)) for k, v in c.most_common(top)]
    result = {"windowSeconds": seconds, "estimatedMBps": round(total / seconds / 1048576, 1),
              "byClass": share(by_class), "byThreadGroup": share(by_group), "bySite": share(by_site),
              "largeSamples": large.most_common(top),
              "columns": ["name", "share", "MBps"]}
    suffix = "" if window_phases == ("measureStart", "measureEnd") else f"-{window_phases[0]}-{window_phases[1]}"
    (run_dir / "jvm" / f"allocations{suffix}.json").write_text(json.dumps(result, indent=1))
    return result


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("run_dir")
    ap.add_argument("--top", type=int, default=20)
    ap.add_argument("--window", default="measureStart:measureEnd",
                    help="phase pair from phases.json, e.g. joined:end for a run that crashed before measuring")
    ap.add_argument("--depth", type=int, default=24)
    a = ap.parse_args()
    res = allocations(pathlib.Path(a.run_dir), a.depth, a.top, tuple(a.window.split(":")))
    print(f"estimated allocation rate {res['estimatedMBps']} MB/s over {res['windowSeconds']:.0f} s")
    for key in ("byThreadGroup", "byClass", "bySite"):
        print(f"\n== {key}")
        for name, s, mbps in res[key]:
            print(f"{s*100:6.2f}%  {mbps:7.1f} MB/s  {name}")
    print("\n== samples with weight >= 1 MiB (humongous leads)")
    for name, n in res["largeSamples"]:
        print(f"{n:6d}  {name}")


if __name__ == "__main__":
    main()
