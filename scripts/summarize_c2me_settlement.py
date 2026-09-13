"""Extract compact, auditable receipts without treating console PASS as lifetime acceptance."""
from pathlib import Path
import json
import re
import sys

def summarize(root):
    result = {"root": str(root), "provenance": json.loads((root / "patched-provider-provenance.json").read_text())}
    result["installed"] = json.loads((root / "installed.json").read_text())
    phases = result["phases"] = {}
    for phase in ("first", "restart"):
        report_path = root / f"{phase}-report.json"
        if not report_path.exists(): continue
        report = json.loads(report_path.read_text())
        log = (root / f"{phase}.log").read_text(errors="replace")
        def lines(key): return [line for line in log.splitlines() if key in line]
        def counts(key):
            matching = lines(key)
            return dict(re.findall(r"(\w+)=(-?\d+)", matching[-1])) if matching else None
        phases[phase] = {
            "status": report.get("status"), "failure": report.get("failure"),
            "exitCode": report.get("exitCode"), "elapsedSeconds": report.get("elapsedSeconds"),
            "health": counts("PHOENIUM_STAGE2 stopped"),
            "binding": counts("PHOENIUM_CHUNK_BINDING STOPPED"),
            "terminals": dict((outcome, len(re.findall(r"PHOENIUM_C2ME_DISK_TERMINAL .*outcome=" + outcome + r"\b", log))) for outcome in ("APPLIED", "CANCELLED", "SUPERSEDED", "SHUTDOWN", "FAILED")),
            "nativeRequesterErrors": lines("C2ME_NATIVE_REQUESTER"),
            "nativeLater": lines("C2ME_NATIVE_LATER"),
            "injections": lines("C2ME_TEST_INJECTED_FAILURE"),
            "cancel": lines("C2ME_TEST_CANCEL"),
            "halt": lines("C2ME_TEST_HALT_REQUEST"),
            "unexpected": lines("Encountered an unexpected exception") + lines("Exception stopping the server"),
        }
    observation_path = root / "observations.json"
    if observation_path.exists():
        obs = json.loads(observation_path.read_text())
        result["observations"] = {k: {"calls": v["calls"], "owned": v["owned"]} for k, v in obs.items()}
    (root / "settlement-summary.json").write_text(json.dumps(result, indent=2))
    compact = {"root": root.name, "phases": {k: {a: b for a,b in v.items() if a in ("status", "failure", "exitCode", "elapsedSeconds", "health", "binding", "terminals", "unexpected")} for k,v in phases.items()}, "nativeCounts": {k:v for k,v in result.get("observations", {}).items() if k.startswith("native.")}}
    print(json.dumps(compact))

if __name__ == "__main__":
    for arg in sys.argv[1:]: summarize(Path(arg))
