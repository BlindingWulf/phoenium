"""Fail-closed comparison for the Stage 7 FastNoise fixed generation boundary."""

import argparse
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from summarize_stage6_audit import region_records


CORE = {(x, z) for z in range(-4, 4) for x in range(6, 14)}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def canonical_container(container):
    if not container:
        return None
    palette = container.get("palette", [])
    labels = [json.dumps(entry, sort_keys=True, separators=(",", ":")) for entry in palette]
    order = sorted(set(labels))
    remap = [order.index(label) for label in labels]
    words = container.get("data", [])
    count = 4096 if any(isinstance(entry, dict) and "Name" in entry for entry in palette) else 64
    minimum_bits = 4 if count == 4096 else 1
    bits = max(minimum_bits, (len(palette) - 1).bit_length())
    per_word = 64 // bits
    mask = (1 << bits) - 1
    values = []
    for index in range(count):
        source = 0 if len(palette) <= 1 else words[index // per_word]
        value = 0 if len(palette) <= 1 else (source >> ((index % per_word) * bits)) & mask
        values.append(remap[value])
    return {"palette": order, "values": values}


def read_snapshot(snapshot):
    chunks = {}
    structures = []
    for path in sorted(Path(snapshot).rglob("*.mca")):
        if path.parent.name != "region" or "overworld" not in str(path):
            continue
        for record in region_records(path):
            key = (record.get("xPos"), record.get("zPos"))
            if key not in CORE:
                continue
            if record.get("Status") != "minecraft:full":
                raise AssertionError(f"Core chunk {key} is not FULL: {record.get('Status')}")
            sections = []
            for section in sorted(record.get("sections", []), key=lambda row: row["Y"]):
                sections.append({
                    "y": section["Y"],
                    "blocks": canonical_container(section.get("block_states")),
                    "biomes": canonical_container(section.get("biomes")),
                })
            chunk_structures = []
            for kind, start in sorted(record.get("structures", {}).get("starts", {}).items()):
                if start.get("id") != "INVALID":
                    row = {"chunk": list(key), "kind": kind, "nbt": start}
                    structures.append(row)
                    chunk_structures.append(row)
            chunks[key] = {
                "blocks": digest([(row["y"], row["blocks"]) for row in sections]),
                "biomes": digest([(row["y"], row["biomes"]) for row in sections]),
                "heightmaps": digest(record.get("Heightmaps", {})),
                "structures": digest(chunk_structures),
            }
    missing = sorted(CORE - set(chunks))
    if missing:
        raise AssertionError(f"Missing core FULL chunks: {missing}")
    return {
        "chunks": {f"{x},{z}": chunks[(x, z)] for x, z in sorted(chunks)},
        "structures": structures,
    }


def compare(reference, candidate):
    fields = ("blocks", "biomes", "heightmaps", "structures")
    result = {}
    for field in fields:
        different = [key for key in reference["chunks"] if reference["chunks"][key][field] != candidate["chunks"][key][field]]
        result[field] = {"same": len(reference["chunks"]) - len(different), "different": different}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--control-a", type=Path, required=True)
    parser.add_argument("--control-b", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, action="append", default=[])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    runs = {"controlA": read_snapshot(args.control_a), "controlB": read_snapshot(args.control_b)}
    for index, path in enumerate(args.candidate, 1):
        runs[f"candidate{index}"] = read_snapshot(path)

    controls = compare(runs["controlA"], runs["controlB"])
    stable_controls = all(not row["different"] for row in controls.values())
    result = {"status": "PASS_STABLE_CONTROLS" if stable_controls else "BLOCKED_UNSTABLE_CONTROLS", "controls": controls, "candidates": {}}
    if stable_controls:
        for name in sorted(key for key in runs if key.startswith("candidate")):
            result["candidates"][name] = compare(runs["controlA"], runs[name])
        if result["candidates"] and all(not field["different"] for comparison in result["candidates"].values() for field in comparison.values()):
            result["status"] = "PASS_FIXED_BOUNDARY_PARITY"
        elif result["candidates"]:
            result["status"] = "FAIL_PROVIDER_PARITY"

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["status"].startswith("PASS_") else 1)


if __name__ == "__main__":
    main()
