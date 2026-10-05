#!/usr/bin/env python3
"""Classify native outcomes of v2 census rows from Phoenium-free headless controls.

A control run is a headless_census.py --vanilla run: empty mods directory, the same launcher, libraries, world fixture
and RCON driver as the Phoenium runs. For every standalone, variant-free row whose native outcome is still unknown, a
control that observed TRANSFERRED on a cross-dimension route fixes the expected native outcome to TRANSFER and binds the
row to that control run by hash. A STAYED control is never turned into a verdict here: choosing NO_NATIVE_ENTRY_PATH,
REFUSAL or ROUTE_NOT_APPLICABLE needs a pinned-source proof, so those rows stay unresolved and are listed in the report.

With --no-entry-proofs (output of no_entry_source_proofs.py) a type that has a pinned-source proof AND whose every control
observation STAYED/GONE is classified NO_NATIVE_ENTRY_PATH; a type with such a proof whose control TRANSFERRED is a conflict.

Usage: classify_census.py --census <v2.json> --control <run dir> [--control <run dir> ...] --out-dir <dir>
                          [--no-entry-proofs <json>]
Writes <out-dir>/PORTAL_BEHAVIOR_CENSUS_V3.json plus <out-dir>/native-controls/<run>/ copies and every artifact the v2
census already referenced, so the importer can resolve every control path relative to the census directory.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import shutil
import sys

CROSS_DIMENSION = {"NETHER_ENTRY", "NETHER_RETURN", "END_ENTRY", "END_EXIT"}
CARD = re.compile(r"E0-([a-z_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)")
CONTROL_FILES = ("headless-result.json", "headless-receipt.json", "inputs.json")


def digest(path: pathlib.Path) -> str:
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest().upper()


def load_control(run: pathlib.Path, source_sha: str, binary_sha: str) -> dict[tuple[str, str], str]:
    result, receipt, inputs = (json.loads((run / name).read_text(encoding="utf-8")) for name in CONTROL_FILES)
    problems = []
    if inputs.get("vanilla") is not True or inputs.get("mods") != []:
        problems.append("not a Phoenium-free control (vanilla/mods)")
    if inputs.get("sourceJarSha256") != source_sha or inputs.get("mappedBinarySha256") != binary_sha:
        problems.append("pinned source/binary hashes differ from the census")
    if not (result.get("pass") is True and receipt.get("pass") is True and result.get("normalSave") is True
            and not result.get("logFailure") and not result.get("sourceWorldChanges")):
        problems.append("control run did not pass cleanly")
    if [card.get("card") for card in receipt.get("cards", [])] != inputs.get("cards"):
        problems.append("receipt cards differ from requested cards")
    if problems:
        raise SystemExit(f"{run}: " + "; ".join(problems))
    table = {}
    for card in receipt["cards"]:
        kind, route = CARD.fullmatch(card["card"]).groups()
        table[(kind, route)] = card["outcome"]
    return table


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--census", type=pathlib.Path, required=True)
    parser.add_argument("--control", type=pathlib.Path, action="append", required=True)
    parser.add_argument("--out-dir", type=pathlib.Path, required=True)
    parser.add_argument("--no-entry-proofs", type=pathlib.Path)
    args = parser.parse_args(argv)
    census = json.loads(args.census.read_text(encoding="utf-8"))
    source_sha, binary_sha = census["sourceJarSha256"], census["mappedBinarySha256"]
    out = args.out_dir
    out.mkdir(parents=True, exist_ok=True)

    # Everything the v2 census already points at must resolve next to the v3 file.
    base = args.census.parent
    for row in census["rows"]:
        control = row.get("nativeControlReceipt")
        if control:
            relative = pathlib.Path(control["path"])
            source_dir = base / relative.parent
            if source_dir.is_dir() and not (out / relative.parent).exists():
                shutil.copytree(source_dir, out / relative.parent)
    leash = base / "LEASH_KNOT_STABLE_NO_ENTRY_SOURCE_PROOF.json"
    if leash.is_file():
        shutil.copy2(leash, out / leash.name)

    controls = {}
    for run in args.control:
        table = load_control(run, source_sha, binary_sha)
        target = out / "native-controls" / run.name
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True)
        for name in CONTROL_FILES:
            shutil.copy2(run / name, target / name)
        for key, outcome in table.items():
            controls.setdefault(key, []).append((outcome, run.name))

    proofs = {}
    if args.no_entry_proofs:
        document = json.loads(args.no_entry_proofs.read_text(encoding="utf-8"))
        if document["sourceJarSha256"] != source_sha or document["mappedBinarySha256"] != binary_sha:
            raise SystemExit("no-entry proofs were generated from different pinned sources")
        proofs = document["proofs"]
        shutil.copy2(args.no_entry_proofs, out / args.no_entry_proofs.name)
    classified, conflicts, pending, classified_no_entry = 0, [], [], 0
    for row in census["rows"]:
        if row.get("graph", "standalone") != "standalone" or row.get("variant") not in (None, ""):
            continue
        key = (row["entityType"].removeprefix("minecraft:"), row["route"])
        observed = controls.get(key)
        if not observed:
            continue
        outcomes = {outcome for outcome, _ in observed}
        expected = row.get("expectedNativeOutcome")
        proof = proofs.get(row["entityType"])
        if expected is None and proof is not None:
            if outcomes <= {"STAYED", "GONE"}:
                row["expectedNativeOutcome"] = "NO_NATIVE_ENTRY_PATH"
                row["sourceProof"] = proof
                row["fixture"] = {"contactImpossible": True, "reason": "No native portal entry path"}
                row["requiredAssertions"] = proof.get(
                    "requiredAssertions", ["source_unchanged", "destination_absent", "portal_session_absent", "graph_unchanged"])
                classified_no_entry += 1
            else:
                conflicts.append({"id": row["id"], "expected": "NO_NATIVE_ENTRY_PATH", "controlOutcomes": sorted(outcomes)})
        elif expected is None:
            if outcomes == {"TRANSFERRED"} and (row["route"] in CROSS_DIMENSION or row["route"] == "END_GATEWAY"):
                run_name = observed[0][1]
                result_path = out / "native-controls" / run_name / "headless-result.json"
                verdict = "SAME_DIMENSION_TRANSFER" if row["route"] == "END_GATEWAY" else "TRANSFER"
                row["expectedNativeOutcome"] = verdict
                row["nativeControlReceipt"] = {
                    "sourceJarSha256": source_sha, "mappedBinarySha256": binary_sha, "observedOutcome": verdict,
                    "path": f"native-controls/{run_name}/headless-result.json", "sha256": digest(result_path),
                    "card": f"E0-{key[0]}-{key[1]}",
                    "receiptSha256": digest(result_path.with_name("headless-receipt.json")),
                    "inputsSha256": digest(result_path.with_name("inputs.json")),
                }
                classified += 1
            else:
                pending.append({"id": row["id"], "controlOutcomes": sorted(outcomes)})
        elif expected == "TRANSFER" and outcomes != {"TRANSFERRED"}:
            conflicts.append({"id": row["id"], "expected": expected, "controlOutcomes": sorted(outcomes)})
        elif expected in ("NO_NATIVE_ENTRY_PATH", "REFUSAL") and outcomes == {"TRANSFERRED"}:
            conflicts.append({"id": row["id"], "expected": expected, "controlOutcomes": sorted(outcomes)})
    census["classification"] = {
        "tool": "scripts/stage7/classify_census.py", "controlRuns": sorted(run.name for run in args.control),
        "rowsClassifiedTransfer": classified, "rowsClassifiedNoEntry": classified_no_entry,
        "controlConflicts": conflicts, "stillUnclassified": len(pending),
    }
    census_path = out / "PORTAL_BEHAVIOR_CENSUS_V3.json"
    census_path.write_text(json.dumps(census, indent=2) + "\n", encoding="utf-8")
    (out / "PORTAL_BEHAVIOR_CENSUS_V3.pending.json").write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"classifiedTransfer": classified, "classifiedNoEntry": classified_no_entry,
                      "conflicts": conflicts, "stillUnclassified": len(pending)}, indent=2))
    return 1 if conflicts else 0


if __name__ == "__main__":
    sys.exit(main())
