"""Validate the shared client fixture's immutable MCA and restored native deadline.

The client test preserves the stopped world and the restart log.  This validator deliberately
reuses the strict native MCA decoder from ``run_stage4_acceptance.py`` and compares the exact
saved relative delay (``t``) with the actual ``LevelChunkTicks.unpack`` receipt.  It does not
accept a late powered-state observation or a record from another chunk/world.
"""
import argparse
import json
import re
from pathlib import Path

from run_stage4_acceptance import expected_pending_tick, inspect_mca, pending_tick_readback


UNPACK = re.compile(
    r"STAGE4_TRACE_RELOAD_UNPACK\s+currentTick=(-?\d+)\s+trigger=(-?\d+)\s+"
    r"delay=(-?\d+)\s+matches=(\d+)"
)


def first_line(path, token):
    if not path.exists():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        if token in line:
            return line
    return None


def validate(evidence_path):
    evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
    trace = Path(evidence["trace"])
    snapshot = Path(evidence["mcaSnapshot"])
    restart_log = Path(evidence["restartLog"])
    expected = expected_pending_tick(trace)
    records = inspect_mca(snapshot, [expected["position"]] if expected else [])
    pending = pending_tick_readback(records, expected)
    unpack_line = evidence.get("restartUnpackReceipt") or first_line(
        restart_log, "STAGE4_TRACE_RELOAD_UNPACK")
    unpack_match = UNPACK.search(unpack_line or "")
    unpack = None
    if unpack_match:
        unpack = {"currentTick": int(unpack_match.group(1)),
                  "trigger": int(unpack_match.group(2)),
                  "delay": int(unpack_match.group(3)),
                  "matches": int(unpack_match.group(4))}
    saved_delay = None
    if pending.get("matches"):
        saved_delay = pending["matches"][0].get("t")
    unpack_delay_match = unpack is not None and unpack["matches"] == 1 \
        and saved_delay is not None and unpack["delay"] == saved_delay \
        and unpack["trigger"] == unpack["currentTick"] + saved_delay
    phase_receipts = {}
    for phase in ("before", "deadline", "after"):
        line = evidence.get("restart" + phase.title() + "Receipt") or first_line(
            restart_log, "STAGE4_TRACE_RELOAD_PHASE phase=" + phase)
        phase_receipts[phase] = {"line": line, "pass": bool(line and "pass=true" in line)}
    result = {
        "kind": "stage4-client-restart-validation",
        "evidence": str(evidence_path),
        "trace": str(trace),
        "mcaSnapshot": str(snapshot),
        "restartLog": str(restart_log),
        "pendingTick": pending,
        "unpack": unpack,
        "savedDelay": saved_delay,
        "unpackDelayMatch": unpack_delay_match,
        "phases": phase_receipts,
    }
    result["result"] = "PASS" if pending.get("status") == "PRESENT" \
        and pending.get("unique") is True and pending.get("exactIdentity") is True \
        and pending.get("deadlineMatch") is True and pending.get("poweredState") is True \
        and unpack_delay_match and all(item["pass"] for item in phase_receipts.values()) else "PARTIAL"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = validate(args.evidence)
    output = args.output or args.evidence.with_name("client-restart-validation.json")
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    return 0 if result["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
