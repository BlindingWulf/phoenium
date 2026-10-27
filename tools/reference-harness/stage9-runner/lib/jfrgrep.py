"""Streaming caller/callee chains for JFR execution samples whose stack contains a frame substring.

    python tools/stage9/lib/jfrgrep.py <run_dir> <substring>[,<substring>...] [--thread "Server thread"]
                                       [--depth 64] [--chain 6] [--top 15]

Reads `jfr print` text output line by line (no JSON, constant memory), so it is a quick question-answering tool next
to jfrattr. For each substring it reports the share of the thread's samples that contain it, then the most common
chains of the --chain frames above the root-most matching frame (callers) and below it (callees). Whole recording;
no phase window."""
from __future__ import annotations

import argparse
import collections
import pathlib
import re
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from lib.analyze import JFR  # noqa: E402

THREAD = re.compile(r'sampledThread = "([^"]*)"')
FRAME = re.compile(r"^\s+(\S+?)\(")


def thread_group(name: str) -> str:
    return re.sub(r"[-#\s]*\d+$", "", name)


def scan(run_dir: pathlib.Path, needles: list[str], thread: str, depth: int, chain: int):
    proc = subprocess.Popen([JFR, "print", "--stack-depth", str(depth), "--events", "jdk.ExecutionSample",
                             str(run_dir / "jvm" / "server.jfr")], stdout=subprocess.PIPE, text=True,
                            encoding="utf-8", errors="replace")
    total = 0
    hits = {n: 0 for n in needles}
    up = {n: collections.Counter() for n in needles}
    down = {n: collections.Counter() for n in needles}
    current_thread, frames, in_stack = None, [], False
    for line in proc.stdout:
        if line.startswith("jdk.ExecutionSample"):
            current_thread, frames, in_stack = None, [], False
        elif "sampledThread" in line:
            m = THREAD.search(line)
            current_thread = thread_group(m.group(1)) if m else None
        elif "stackTrace = [" in line:
            in_stack = True
        elif in_stack and line.strip() == "]":
            in_stack = False
            if current_thread != thread or not frames:
                continue
            total += 1
            for n in needles:
                idx = [i for i, f in enumerate(frames) if n in f]
                if not idx:
                    continue
                i = idx[-1]
                hits[n] += 1
                up[n][" <- ".join(frames[i + 1:i + 1 + chain])] += 1
                down[n][" -> ".join(reversed(frames[max(0, i - chain):i]))] += 1
        elif in_stack:
            m = FRAME.match(line)
            if m:
                frames.append(m.group(1))
    proc.wait()
    return total, hits, up, down


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("run_dir")
    ap.add_argument("needles")
    ap.add_argument("--thread", default="Server thread")
    ap.add_argument("--depth", type=int, default=64)
    ap.add_argument("--chain", type=int, default=6)
    ap.add_argument("--top", type=int, default=15)
    a = ap.parse_args()
    needles = [n for n in a.needles.split(",") if n]
    total, hits, up, down = scan(pathlib.Path(a.run_dir), needles, a.thread, a.depth, a.chain)
    print(f"{a.thread}: {total} samples (depth {a.depth}; deeper frames are cut)")
    for n in needles:
        print(f"\n== {n}: {hits[n]} samples ({100 * hits[n] / max(total, 1):.2f}%)")
        print("-- callers (nearest first)")
        for k, v in up[n].most_common(a.top):
            print(f"{100 * v / max(total, 1):6.2f}%  {k}")
        print("-- callees (toward the leaf)")
        for k, v in down[n].most_common(a.top):
            print(f"{100 * v / max(total, 1):6.2f}%  {k}")


if __name__ == "__main__":
    main()
