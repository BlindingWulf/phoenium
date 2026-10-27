"""Attribute server-thread JFR execution samples to Phoenium code (profile-first loop for D-candidates).

    python tools/stage9/lib/jfrattr.py <run_dir> [--window measureStart:measureEnd] [--thread "Server thread"]
                                       [--depth 128] [--top 30]

For every sample of the thread group (frames are leaf-first):
  nearest   = the first dev.phoenium frame from the leaf: the Phoenium method whose own work, or whose direct
              JDK/library calls, the sample is spent in;
  outermost = the root-most dev.phoenium frame: the Phoenium entry point (subsystem) the work belongs to;
  leafKind  = the leaf frame's library (phoenium / minecraft / c2me / jdk / other);
  inclusive = every distinct dev.phoenium method on the stack (inclusive share of the subtree it roots).
--focus <substring>[,<substring>...] additionally reports, per substring, for samples whose stack has a matching
Phoenium frame, the next Phoenium frame toward the root (callers) and toward the leaf (callees); one parse serves all.
Samples with no Phoenium frame are attributed to their leaf library. Shares are of all samples of the thread in the
window. Writes jvm/attribution[-<window>].json. Run it on a quiet host (single-threaded parse of the recording)."""
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


def is_phoenium(name: str) -> bool:
    return name.startswith(("dev/phoenium/", "dev.phoenium.")) or "$phoenium$" in name or ".phoenium$" in name


def short(name: str) -> str:
    return name.replace("dev/phoenium/", "").replace("dev.phoenium.", "").replace("/", ".")


def kind(name: str) -> str:
    if is_phoenium(name):
        return "phoenium"
    for prefix, label in (("net/minecraft", "minecraft"), ("net.minecraft", "minecraft"), ("com/ishland", "c2me"),
                          ("com.ishland", "c2me"), ("java", "jdk"), ("jdk", "jdk"), ("sun", "jdk"),
                          ("it/unimi", "fastutil"), ("it.unimi", "fastutil")):
        if name.startswith(prefix):
            return label
    return "other"


def attribute(run_dir: pathlib.Path, window_phases: tuple[str, str], thread: str, depth: int,
              focus: str | None = None) -> dict:
    foci = [f for f in (focus or "").split(",") if f]
    phases = json.loads((run_dir / "phases.json").read_text())
    window = (phases[window_phases[0]], phases[window_phases[1]])
    out = subprocess.run([JFR, "print", "--json", "--stack-depth", str(depth), "--events", "jdk.ExecutionSample",
                          str(run_dir / "jvm" / "server.jfr")], capture_output=True, text=True, encoding="utf-8",
                         errors="replace", timeout=3600)
    events = json.loads(out.stdout)["recording"]["events"]
    nearest, outermost, leaf_kind, pairs, inclusive = (collections.Counter() for _ in range(5))
    callers = {f: collections.Counter() for f in foci}
    callees = {f: collections.Counter() for f in foci}
    samples = truncated = 0
    for event in events:
        values = event["values"]
        if not (window[0] <= _iso_ms(values["startTime"]) < window[1]):
            continue
        if group((values.get("sampledThread") or {}).get("javaName") or "?") != thread:
            continue
        trace = values.get("stackTrace") or {}
        frames = [frame_name(f) for f in (trace.get("frames") or [])]
        if not frames:
            continue
        samples += 1
        truncated += bool(trace.get("truncated"))
        phoenium = [f for f in frames if is_phoenium(f)]
        leaf_kind[kind(frames[0])] += 1
        if phoenium:
            nearest[short(phoenium[0])] += 1
            outermost[short(phoenium[-1])] += 1
            pairs[f"{short(phoenium[-1])}  ->  {short(phoenium[0])}"] += 1
            names = [short(f) for f in phoenium]
            for name in set(names):
                inclusive[name] += 1
            for f in foci:
                hits = [i for i, name in enumerate(names) if f in name]
                if hits:
                    i = hits[-1]  # root-most matching frame: callers above it, callees below it
                    callers[f][names[i + 1] if i + 1 < len(names) else "<root>"] += 1
                    callees[f][names[i - 1] if i > 0 else "<self/library>"] += 1
        else:
            label = f"<no phoenium frame: {kind(frames[0])}> {frames[0].replace('/', '.')}"
            nearest[label] += 1
            outermost[f"<no phoenium frame: {kind(frames[0])}>"] += 1
    share = lambda c, n=40: [(k, round(v / samples, 4)) for k, v in c.most_common(n)] if samples else []
    result = {"thread": thread, "window": list(window_phases), "samples": samples, "truncatedStacks": truncated,
              "depth": depth, "leafKind": share(leaf_kind), "nearestPhoenium": share(nearest),
              "outermostPhoenium": share(outermost), "entryToNearest": share(pairs, 60),
              "inclusivePhoenium": share(inclusive, 120)}
    if foci:
        result.update(focus=foci, focusCallers={f: share(callers[f]) for f in foci},
                      focusCallees={f: share(callees[f]) for f in foci})
    suffix = "" if window_phases == ("measureStart", "measureEnd") else f"-{window_phases[0]}-{window_phases[1]}"
    if focus:
        suffix += "-focus-" + "".join(c if c.isalnum() else "_" for c in focus)
    (run_dir / "jvm" / f"attribution{suffix}.json").write_text(json.dumps(result, indent=1))
    return result


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("run_dir")
    ap.add_argument("--window", default="measureStart:measureEnd")
    ap.add_argument("--thread", default="Server thread")
    ap.add_argument("--depth", type=int, default=128)
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--focus")
    a = ap.parse_args()
    r = attribute(pathlib.Path(a.run_dir), tuple(a.window.split(":")), a.thread, a.depth, a.focus)
    print(f"{r['thread']}: {r['samples']} samples, {r['truncatedStacks']} truncated at depth {r['depth']}")
    for key in ["leafKind", "inclusivePhoenium", "nearestPhoenium", "entryToNearest"]:
        print(f"\n== {key}")
        for name, s in r[key][:a.top]:
            print(f"{s*100:6.2f}%  {name}")
    for f in r.get("focus", []):
        for key in ("focusCallers", "focusCallees"):
            print(f"\n== {key} of {f}")
            for name, s in r[key][f][:a.top]:
                print(f"{s*100:6.2f}%  {name}")


if __name__ == "__main__":
    main()
