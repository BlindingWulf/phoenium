"""Verify positive lifetime receipts and final residue for the finite locked stack matrix."""
from pathlib import Path
import contextlib
import io
import json
import re
import sys
from summarize_c2me_settlement import summarize

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "run/stage8/entry-20260913/astra-c2me-provider-settlement"
MODES = ("coinstall", "failure", "cancel", "replacement", "stop", "cancel-stop")

def verify(prefix):
    rows = []
    for worker in (1, 4):
        for mode in MODES:
            directory = EVIDENCE / f"{prefix}-{mode}-w{worker}"
            with contextlib.redirect_stdout(io.StringIO()): summarize(directory)
            data = json.loads((directory / "settlement-summary.json").read_text())
            for phase, receipt in data["phases"].items():
                assert receipt["status"] == "PASS_CONSOLE_SAMPLE" and receipt["exitCode"] == 0, directory
                assert not receipt["unexpected"], directory
                for key in ("duplicate", "fallback", "ownershipViolations", "unleased", "leases", "barriers"):
                    assert receipt["health"][key] == "0", (directory, phase, key)
                for key in ("duplicateApplications", "barriers", "retainedGraphs", "returnedIndex", "poiIndex",
                            "c2meRetained", "c2meDiskActions", "c2meSendInputsRetained", "c2meSendPins",
                            "privateC2meReads", "lightBatches", "lightOccurrences", "lightReceipts", "poiEntries",
                            "metadataEntries", "lifecycleEntries", "workEntries", "custodyEntries"):
                    assert receipt["binding"][key] == "0", (directory, phase, key)
                assert receipt["binding"]["c2meSendPrepared"] == receipt["binding"]["c2meSendInputsClosed"]
                log = (directory / f"{phase}.log").read_text(errors="replace")
                starts = re.findall(r"C2ME_NATIVE_ACTION_START .*context=(\d+) token=(\d+)", log)
                terminals = re.findall(r"C2ME_NATIVE_ACTION_TERMINAL .*context=(\d+) token=(\d+)", log)
                assert len(starts) == len(set(starts)), (directory, phase, "duplicate native action start")
                assert len(terminals) == len(set(terminals)), (directory, phase, "duplicate native action terminal")
                assert set(starts) == set(terminals), (directory, phase, "unsettled native action")
                cancelled = re.findall(r"C2ME_TEST_CANCEL_TOKEN context=(\d+) token=(\d+)", log)
                successes = set(re.findall(r"C2ME_NATIVE_ACTION_TERMINAL .*context=(\d+) token=(\d+).*cause=null", log))
                assert not successes.intersection(cancelled), (directory, phase, "cancelled action published success")
            receipt = data["phases"]["restart"]
            binding = receipt["binding"]
            log = (directory / "restart.log").read_text(errors="replace")
            native = {k: v["calls"] for k,v in data["observations"].items() if k.startswith("native.")}
            for status in ("DISK", "FULL"):
                assert native.get(f"native.requested.{status}", 0) == sum(native.get(f"native.terminal.{status}.{end}", 0) for end in ("SUCCESS", "ERROR")), (directory, status)
            assert int(binding["c2meReadRegistrations"]) > 0, directory
            if mode in ("failure", "replacement"):
                assert len(receipt["injections"]) == 1, directory
                assert binding["c2meDiskFailed"] == "1" and binding["c2meReadProviderFailures"] == "1", directory
                original = [line for line in receipt["nativeRequesterErrors"] if "Exception occurred while executing main thread tasks" in line]
                assert any("label=DISK" in line for line in original), directory
                assert any("label=FULL" in line for line in original), directory
                assert all("holderLock=false" in line for line in original), directory
                assert "done=true exceptional=true sameCause=true" in log, directory
                assert "Suppressed: java.lang.RuntimeException: C2ME test injected failure at drainQueue.task" in log, directory
            else:
                assert binding["c2meDiskFailed"] == "0", directory
            if mode in ("cancel", "cancel-stop"):
                assert int(binding["c2meDiskCancelled"]) >= 1, directory
                assert "C2ME_TEST_CANCEL_TOKEN" in log and "providerError=dev.phoenium.chunk.C2meDiskReadOperation$Control" in log, directory
            if mode in ("stop", "cancel-stop"):
                assert receipt["halt"] and "C2ME_TEST_DELAY" in log, directory
            if mode == "replacement":
                assert re.search(r"C2ME_NATIVE_REPLACEMENT_SUCCESS .*oldRetired=true oldCausePreserved=true", log), directory
                assert "REPLACEMENT_LOADED" in log, directory
            if mode == "coinstall":
                assert "LAMP_PERSISTED" in log and "LAMP_OFF" in log, directory
                assert log.count("[Server] CHUNK_RELOADED") == 81, directory
                assert int(binding["c2meSendPrepared"]) > 0, directory
                first = (directory / "first.log").read_text(errors="replace")
                assert re.search(r"PHOENIUM_C2ME_SEND_PREFLIGHT .*footprint=[2-9]", first), directory
            rows.append({"run": directory.name, "worker": worker, "mode": mode,
                         "candidateSha256": data["provenance"]["candidateSha256"],
                         "patchedC2meSha256": data["provenance"]["patchedC2meSha256"],
                         "seconds": round(receipt["elapsedSeconds"], 3), "native": native,
                         "binding": binding, "health": receipt["health"]})
    result = {"status": "PASS_LIFETIME_RECEIPTS", "runs": rows}
    (EVIDENCE / f"{prefix}-verified-matrix.json").write_text(json.dumps(result, indent=2))
    print(json.dumps({"status": result["status"], "runs": len(rows), "candidateSha256": sorted({r['candidateSha256'] for r in rows})}))

if __name__ == "__main__": verify(sys.argv[1])
