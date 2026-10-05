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
                          [--no-entry-proofs <json>] [--route-na-proofs <json>]
With --route-na-proofs (output of sleeping_na_proof.py) the player `sleeping` rows become ROUTE_NOT_APPLICABLE: the proof and the
three native observations it hashes are copied next to the census, where the importer re-verifies them.
Writes <out-dir>/PORTAL_BEHAVIOR_CENSUS_V3.json plus <out-dir>/native-controls/<run>/ copies and every artifact the v2
census already referenced, so the importer can resolve every control path relative to the census directory.
"""
from __future__ import annotations

import argparse
import copy
import build_portal_coverage as coverage
import hashlib
import json
import pathlib
import re
import shutil
import sys

CROSS_DIMENSION = {"NETHER_ENTRY", "NETHER_RETURN", "END_ENTRY", "END_EXIT"}
CARD = re.compile(r"E0-([a-z_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)")
FALLING_CARD = re.compile(r"D0-falling-([a-z0-9_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)")
GRAPH_CARD = re.compile(r"G0-([a-z0-9_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)")
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
        graph = GRAPH_CARD.fullmatch(card["card"])
        falling = FALLING_CARD.fullmatch(card["card"])
        effects = coverage.EFFECTS_CARD.fullmatch(card["card"])
        variant = coverage.VARIANT_CARD.fullmatch(card["card"])
        client = coverage.CLIENT_CARD.fullmatch(card["card"])
        if client:
            kind, route = "k1:" + client.group(1), client.group(2)
        elif variant:
            kind, route = f"variant:{variant.group(1)}:{variant.group(2)}", variant.group(3)
        elif falling:
            kind, route = "falling:" + falling.group(1), falling.group(2)
        elif effects:
            kind, route = "effects:" + effects.group(1), effects.group(2)
        else:
            kind, route = (("graph:" + graph.group(1), graph.group(2)) if graph else CARD.fullmatch(card["card"]).groups())
        table[(kind, route)] = card["outcome"]
    return table


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--census", type=pathlib.Path, required=True)
    parser.add_argument("--control", type=pathlib.Path, action="append", required=True)
    parser.add_argument("--out-dir", type=pathlib.Path, required=True)
    parser.add_argument("--no-entry-proofs", type=pathlib.Path)
    parser.add_argument("--route-na-proofs", type=pathlib.Path)
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
    sleeping_proof = None
    if args.route_na_proofs:
        document = json.loads(args.route_na_proofs.read_text(encoding="utf-8"))
        if document["sourceJarSha256"] != source_sha or document["mappedBinarySha256"] != binary_sha:
            raise SystemExit("route-not-applicable proof was generated from different pinned sources")
        sleeping_proof = dict(document["proof"], observations=document["observations"])
        shutil.copy2(args.route_na_proofs, out / args.route_na_proofs.name)
        shutil.copytree(args.route_na_proofs.parent / "sleeping-observations", out / "sleeping-observations", dirs_exist_ok=True)
    classified, conflicts, pending, classified_no_entry = 0, [], [], 0

    def receipt_of(run_name: str, card: str, verdict: str) -> dict:
        result_path = out / "native-controls" / run_name / "headless-result.json"
        return {"sourceJarSha256": source_sha, "mappedBinarySha256": binary_sha, "observedOutcome": verdict,
                "path": f"native-controls/{run_name}/headless-result.json", "sha256": digest(result_path), "card": card,
                "receiptSha256": digest(result_path.with_name("headless-receipt.json")),
                "inputsSha256": digest(result_path.with_name("inputs.json"))}

    for row in census["rows"]:
        if sleeping_proof is not None and row.get("entityType") == "minecraft:player" and row.get("variant") == "sleeping":
            row["expectedNativeOutcome"] = "ROUTE_NOT_APPLICABLE"
            row["sourceProof"] = copy.deepcopy(sleeping_proof)
            row["fixture"] = {"contactImpossible": True, "reason": "A sleeping pose is never in portal contact"}
            row["requiredAssertions"] = []
            classified_no_entry += 1
            continue
        fx = coverage.effects_kind_of_row(row)
        fx_observed = controls.get(("effects:" + fx, row["route"])) if fx else None
        if fx_observed and {outcome for outcome, _ in fx_observed} == {"TRANSFERRED"}:
            verdict = "SAME_DIMENSION_TRANSFER" if row["route"] == "END_GATEWAY" else "TRANSFER"
            receipt = receipt_of(fx_observed[0][1], f"D1-{fx}-{row['route']}", verdict)
            if row.get("variant") in (None, ""):
                row["effectsControlReceipt"] = receipt   # the E0 control below still fixes the native outcome
            elif row.get("expectedNativeOutcome") is None:
                row["expectedNativeOutcome"] = verdict
                row["nativeControlReceipt"] = receipt
                classified += 1
                continue
        elif fx and row.get("variant") not in (None, "") and row.get("expectedNativeOutcome") is None:
            pending.append({"id": row["id"], "controlOutcomes": sorted({outcome for outcome, _ in fx_observed or []})})
            continue
        k1 = coverage.k1_kind_of_row(row)
        if k1 is not None:
            observed = controls.get(("k1:" + k1, row["route"]))
            if not observed:
                continue
            outcomes = {outcome for outcome, _ in observed}
            card_name = f"K1-{k1}-{row['route']}"
            expected = row.get("expectedNativeOutcome")
            transfer_verdict = "SAME_DIMENSION_TRANSFER" if row["route"] == "END_GATEWAY" else "TRANSFER"
            if expected is None:
                if outcomes == {"TRANSFERRED"}:
                    row["expectedNativeOutcome"] = transfer_verdict
                    row["nativeControlReceipt"] = receipt_of(observed[0][1], card_name, transfer_verdict)
                    classified += 1
                else:
                    pending.append({"id": row["id"], "controlOutcomes": sorted(outcomes)})
            elif expected in ("TRANSFER", "SAME_DIMENSION_TRANSFER") and outcomes == {"TRANSFERRED"}:
                if row.get("nativeControlReceipt") is None:
                    row["nativeControlReceipt"] = receipt_of(observed[0][1], card_name, expected)
                    classified += 1
            elif expected == "REFUSAL" and outcomes == {"STAYED"}:
                row["nativeControlReceipt"] = receipt_of(observed[0][1], card_name, "REFUSAL")
                classified += 1
            elif expected != "NO_NATIVE_ENTRY_PATH" or outcomes == {"TRANSFERRED"}:
                conflicts.append({"id": row["id"], "expected": expected, "controlOutcomes": sorted(outcomes)})
            continue
        falling = row.get("entityType") == "minecraft:falling_block" and row.get("graph", "standalone") == "standalone"
        state_card = (row.get("entityType") == "minecraft:creaking" and row.get("graph", "standalone") == "standalone"
                      and row.get("variant") in ("heart_bound", "heart_unbound"))
        if state_card:
            observed = controls.get((f"variant:creaking:{row['variant']}", row["route"]))
            if not observed:
                continue
            outcomes = {outcome for outcome, _ in observed}
            card_name = f"Y0-creaking-{row['variant']}-{row['route']}"
            if row["variant"] == "heart_bound" and row.get("expectedNativeOutcome") == "REFUSAL":
                if outcomes == {"STAYED"}:
                    row["nativeControlReceipt"] = receipt_of(observed[0][1], card_name, "REFUSAL")
                    classified += 1
                else:
                    conflicts.append({"id": row["id"], "expected": "REFUSAL", "controlOutcomes": sorted(outcomes)})
            elif row["variant"] == "heart_unbound" and row.get("expectedNativeOutcome") is None:
                if outcomes == {"TRANSFERRED"}:
                    verdict = "SAME_DIMENSION_TRANSFER" if row["route"] == "END_GATEWAY" else "TRANSFER"
                    row["expectedNativeOutcome"] = verdict
                    row["nativeControlReceipt"] = receipt_of(observed[0][1], card_name, verdict)
                    classified += 1
                else:
                    pending.append({"id": row["id"], "controlOutcomes": sorted(outcomes)})
            continue
        if row.get("variant") not in (None, "") and not falling:
            continue
        if falling:
            key = ("falling:" + coverage.falling_variant_name(row["variant"]), row["route"])
        elif row.get("graph", "standalone") != "standalone":
            shape = coverage.GRAPH_ROW_SHAPE.get(row["graph"])
            if shape is None:
                continue
            key = ("graph:" + shape, row["route"])
        else:
            key = (row["entityType"].removeprefix("minecraft:"), row["route"])
        observed = controls.get(key)
        if not observed:
            continue
        outcomes = {outcome for outcome, _ in observed}
        expected = row.get("expectedNativeOutcome")
        proof = proofs.get(row["entityType"]) if row.get("entityType") else None
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
                    "card": (f"G0-{key[0].removeprefix('graph:')}-{key[1]}" if key[0].startswith("graph:")
                             else f"D0-falling-{key[0].removeprefix('falling:')}-{key[1]}" if key[0].startswith("falling:")
                             else f"E0-{key[0]}-{key[1]}"),
                    "receiptSha256": digest(result_path.with_name("headless-receipt.json")),
                    "inputsSha256": digest(result_path.with_name("inputs.json")),
                }
                classified += 1
            else:
                pending.append({"id": row["id"], "controlOutcomes": sorted(outcomes)})
        elif (falling and expected in ("TRANSFER", "SAME_DIMENSION_TRANSFER") and outcomes == {"TRANSFERRED"}
              and row.get("nativeControlReceipt") is None):
            # A legacy falling row whose native outcome the census already fixed: bind it to the control that observed it.
            run_name = observed[0][1]
            result_path = out / "native-controls" / run_name / "headless-result.json"
            row["nativeControlReceipt"] = {
                "sourceJarSha256": source_sha, "mappedBinarySha256": binary_sha, "observedOutcome": expected,
                "path": f"native-controls/{run_name}/headless-result.json", "sha256": digest(result_path),
                "card": f"D0-falling-{key[0].removeprefix('falling:')}-{key[1]}",
                "receiptSha256": digest(result_path.with_name("headless-receipt.json")),
                "inputsSha256": digest(result_path.with_name("inputs.json")),
            }
            classified += 1
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
