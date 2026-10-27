"""Generate benchq JSONL lines for a full scenario x tier matrix (Stage 9 timing rule: tiers 32/50 use 180 s warmup +
420 s measure, smaller tiers 120 + 300).

    python tools/stage9/lib/matrix.py --prefix q5 --stack B-moon-lith-ferr [--tag g1t] [--jvm-args-file flags.json]
        [--scenarios connect region explore combat mounted-portal mixed groups] [--tiers 1 4 8 16 32 50]
        [--heap 1536M] [--pregen] [--skip q3-...]

`--pregen` adds explore t32/t50 on the pinned pregenerated fixture. Prints JSONL to stdout; review it, then insert it
into a queue file (benchq re-reads the file before every pick)."""
from __future__ import annotations

import argparse
import json
import pathlib

FIXTURE = r"C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage9-20261009\fixtures\pregen-r2560-v1"


def timing(tier: int) -> tuple[int, int]:
    return (180, 420) if tier >= 32 else (120, 300)


def entries(prefix: str, stack: str, tag: str, scenarios: list[str], tiers: list[int], jvm_args: list[str],
            heap: str | None, pregen: bool, skip: set[str]) -> list[dict]:
    out = []
    label = f"{stack}{'-' + tag if tag else ''}"

    def add(scenario: str, tier: int, extra: dict, suffix: str = "") -> None:
        warmup, measure = timing(tier)
        run_id = f"{prefix}-{label}-{scenario}{suffix}-t{tier}"
        if run_id in skip:
            return
        e = {"id": run_id, "stack": stack, "scenario": scenario, "tier": tier, "warmup": warmup, "measure": measure}
        if heap:
            e["heap"] = heap
        if jvm_args:
            e["jvm_args"] = jvm_args
        e.update(extra)
        out.append(e)

    for scenario in scenarios:
        for tier in tiers:
            add(scenario, tier, {})
    if pregen:
        for tier in (32, 50):
            add("explore", tier, {"fixture": FIXTURE}, "-pregen")
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--prefix", required=True)
    ap.add_argument("--stack", required=True)
    ap.add_argument("--tag", default="")
    ap.add_argument("--scenarios", nargs="+",
                    default=["connect", "region", "explore", "combat", "mounted-portal", "mixed", "groups"])
    ap.add_argument("--tiers", nargs="+", type=int, default=[1, 4, 8, 16, 32, 50])
    ap.add_argument("--jvm-args-file")
    ap.add_argument("--heap")
    ap.add_argument("--pregen", action="store_true")
    ap.add_argument("--skip", nargs="*", default=[])
    a = ap.parse_args()
    jvm = json.loads(pathlib.Path(a.jvm_args_file).read_text()) if a.jvm_args_file else []
    for e in entries(a.prefix, a.stack, a.tag, a.scenarios, a.tiers, jvm, a.heap, a.pregen, set(a.skip)):
        print(json.dumps(e))


if __name__ == "__main__":
    main()
