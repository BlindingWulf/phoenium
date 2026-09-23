#!/usr/bin/env python3
"""Index installed Stage 7 evidence for one exact product hash, without crediting it."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def sha(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest().upper()


def read(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def reference(path: Path) -> dict | None:
    return {"path": str(path.resolve()), "sha256": sha(path)} if path.is_file() else None


def installed_phase(run: Path, phase: str, candidate_sha: str) -> dict | None:
    inputs_path = run / "inputs.json"
    result_path = run / f"{phase}-result.json"
    if not inputs_path.is_file() or not result_path.is_file():
        return None
    inputs, result = read(inputs_path), read(result_path)
    if inputs.get("candidateSha256") != candidate_sha:
        return None
    receipt_path = run / f"{phase}-client-receipt.json"
    receipt = read(receipt_path) if receipt_path.is_file() else {}
    trace_value = result.get("txTracePath")
    trace = Path(trace_value) if isinstance(trace_value, str) else run / "absent-trace"
    if trace.resolve().parent != run.resolve():
        trace = run / "absent-trace"
    recorded_cards = receipt.get("cards")
    if not isinstance(recorded_cards, list):
        recorded_cards = []
    return {
        "run": run.name,
        "phase": phase,
        "group": inputs.get("group"),
        "workers": inputs.get("workers"),
        "seed": inputs.get("seed"),
        "selectedCards": inputs.get("cards"),
        "completedCardIds": [card.get("card") for card in recorded_cards
                             if isinstance(card, dict) and card.get("result") == "PASS"],
        "pass": result.get("pass") is True,
        "normalSave": result.get("normalSave") is True,
        "normalStop": result.get("normalStopRequested") is True
                      and result.get("serverExitCode") == 0,
        "clientPass": result.get("clientPass") is True,
        "transactionPass": result.get("txPass") is True,
        "transactionStatus": result.get("txStatus"),
        "sourceCommit": inputs.get("head"),
        "sourceDirty": bool(inputs.get("sourceStatus")),
        "sourceStatusSha256": hashlib.sha256(
            str(inputs.get("sourceStatus", "")).encode("utf-8")).hexdigest().upper(),
        "probeSha256": inputs.get("probeSha256"),
        "nativeManifest": reference(Path(inputs["pinnedManifest"]))
        if isinstance(inputs.get("pinnedManifest"), str) else None,
        "providerHashes": result.get("providerHashes"),
        "configurationHashes": inputs.get("configHashes"),
        "testSourceHashes": inputs.get("testSourceHashes"),
        "sourceWorldChanges": result.get("sourceWorldChanges"),
        "testSourceChanged": result.get("testSourceChanged"),
        "firstFailure": result.get("failure"),
        "logFailure": result.get("logFailure"),
        "serverExitCode": result.get("serverExitCode"),
        "clientExitCode": result.get("clientExitCode"),
        "inputs": reference(inputs_path),
        "result": reference(result_path),
        "clientReceipt": reference(receipt_path),
        "transactionTrace": reference(trace),
    }


def build(candidate: Path, installed_root: Path, storm_root: Path) -> dict:
    candidate_sha = sha(candidate)
    installed = []
    for run in sorted(path for path in installed_root.iterdir() if path.is_dir()):
        for phase in ("first", "restart"):
            evidence = installed_phase(run, phase, candidate_sha)
            if evidence is not None:
                installed.append(evidence)
    storms = []
    if storm_root.is_dir():
        for result_path in sorted(storm_root.glob("*/result.json")):
            result = read(result_path)
            if result.get("candidateSha256") != candidate_sha:
                continue
            inputs_path = result_path.parent / "inputs.json"
            inputs = read(inputs_path) if inputs_path.is_file() else {}
            storms.append({
                "run": result_path.parent.name,
                "pass": result.get("pass") is True,
                "firstFailure": result.get("firstFailure"),
                "workers": inputs.get("workers"),
                "seed": inputs.get("seed"),
                "plannedAttempts": inputs.get("attempts"),
                "actualContacts": result.get("actualContacts"),
                "actualTransfers": result.get("actualTransfers"),
                "actualRefusals": result.get("actualRefusals"),
                "observedFamilyRouteCounts": result.get("observedFamilyRouteCounts"),
                "ownerViolations": result.get("ownerViolations"),
                "firstSaveStop": {"saved": result.get("firstSaved"),
                                  "stopRequested": result.get("firstNormalStopRequested"),
                                  "exitCode": result.get("firstExitCode")},
                "sameWorldRestart": {"saved": result.get("restartSaved"),
                                     "stopRequested": result.get("restartNormalStopRequested"),
                                     "exitCode": result.get("restartExitCode")},
                "sourceCommit": inputs.get("head"),
                "sourceDirty": bool(inputs.get("sourceStatus")),
                "nativeSourceSha256": inputs.get("nativeSourceSha256"),
                "probeSha256": result.get("installedProbeSha256"),
                "sourceChanges": result.get("sourceWorldChanges"),
                "testSourceChanges": result.get("executedTestSourceChanges"),
                "fullStormGate": result.get("stage7FullStormGate"),
                "uncoveredStormScope": result.get("uncoveredStormScope"),
                "lastTransactionStatus": result.get("lastTxStatus"),
                "inputs": reference(inputs_path),
                "result": reference(result_path),
            })
    return {
        "schema": "stage7-final-campaign-evidence-index-v1",
        "candidate": reference(candidate),
        "candidateSha256": candidate_sha,
        "installedPhaseCount": len(installed),
        "installedPassPhaseCount": sum(phase["pass"] for phase in installed),
        "stormRunCount": len(storms),
        "stormPassRunCount": sum(storm["pass"] for storm in storms),
        "installedPhases": installed,
        "stormRuns": storms,
        "scope": "Exact product hash and retained files only; this index awards no census disposition.",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--installed-root", type=Path, required=True)
    parser.add_argument("--storm-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    index = build(args.candidate, args.installed_root, args.storm_root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: index[key] for key in (
        "candidateSha256", "installedPhaseCount", "installedPassPhaseCount",
        "stormRunCount", "stormPassRunCount")}, indent=2))


if __name__ == "__main__":
    main()
