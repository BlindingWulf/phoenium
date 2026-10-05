#!/usr/bin/env python3
"""Generate pinned-source no-entry proofs for entity types that can never receive portal block effects.

Native portal entry needs Block.entityInside to run, and that only happens through Entity.applyEffectsFromBlocks ->
Entity.checkInsideBlocks. This tool proves, from the pinned sources and the pinned mapped binary, that for each listed
type either

  no_physics_blocks_effects        the constructor sets noPhysics = true and Entity.isAffectedByBlocks() (which guards both
                                   applyEffectsFromBlocks and checkInsideBlocks) returns !removed && !noPhysics, with no
                                   override anywhere in the class chain; or
  tick_never_applies_block_effects no class in the chain below Entity mentions applyEffectsFromBlocks, checkInsideBlocks
                                   or isAffectedByBlocks, and inside Entity the only callers are the block-effect methods
                                   themselves (nothing in Entity.tick/baseTick/move reaches them).

It never decides anything by observation; the importer pairs each proof with W1/W4 negative observations. Output format
matches nativeNoEntrySourceProofs in the v2 census (sourceMethodSha256 = sha256 of the declaration-to-closing-brace text
with LF newlines and leading tabs of the first line stripped; classBytecodeSha256 = sha256 of the class file).

Usage: no_entry_source_proofs.py --sources <sources.jar> --binary <mapped.jar> --output <json>
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
import zipfile

ENTITY = "net/minecraft/world/entity/Entity"
GUARD_NAMES = ("applyEffectsFromBlocks", "checkInsideBlocks", "isAffectedByBlocks")
SPECS = {
    "minecraft:area_effect_cloud": ("no_physics_blocks_effects", ["net/minecraft/world/entity/AreaEffectCloud"]),
    "minecraft:ominous_item_spawner": ("no_physics_blocks_effects", ["net/minecraft/world/entity/OminousItemSpawner"]),
    "minecraft:eye_of_ender": ("tick_never_applies_block_effects", ["net/minecraft/world/entity/projectile/EyeOfEnder"]),
    "minecraft:lightning_bolt": ("tick_never_applies_block_effects", ["net/minecraft/world/entity/LightningBolt"]),
    "minecraft:evoker_fangs": ("tick_never_applies_block_effects", ["net/minecraft/world/entity/projectile/EvokerFangs"]),
    "minecraft:llama_spit": ("tick_never_applies_block_effects",
                             ["net/minecraft/world/entity/projectile/LlamaSpit", "net/minecraft/world/entity/projectile/Projectile"]),
}
PREDICATES = {
    "no_physics_blocks_effects": (
        "constructor sets noPhysics = true; Entity.isAffectedByBlocks() returns !isRemoved() && !noPhysics and guards both "
        "applyEffectsFromBlocks and checkInsideBlocks; no class in the chain overrides it, so Block.entityInside is never applied"),
    "tick_never_applies_block_effects": (
        "no class in the chain below Entity applies block effects or overrides isAffectedByBlocks, and inside Entity only the "
        "block-effect methods call each other, so tick/baseTick/move never reach Block.entityInside"),
}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def method_at(lines: list[str], declaration_line: int) -> str:
    depth, started, out = 0, False, []
    for line in lines[declaration_line - 1:]:
        out.append(line)
        depth += line.count("{") - line.count("}")
        started = started or "{" in line
        if started and depth == 0:
            break
    return "\n".join(out).lstrip("\t")


def find_method(lines: list[str], pattern: str) -> int:
    hits = [i + 1 for i, line in enumerate(lines) if re.search(pattern, line)]
    if len(hits) != 1:
        raise SystemExit(f"expected exactly one declaration for {pattern!r}, found {hits}")
    return hits[0]


def enclosing_declaration(lines: list[str], marker: str) -> int:
    hits = [i for i, line in enumerate(lines) if marker in line]
    if len(hits) != 1:
        raise SystemExit(f"expected exactly one {marker!r}, found {len(hits)}")
    for index in range(hits[0], -1, -1):
        if re.match(r"\t(?:public|protected|private)\s+[A-Za-z_][\w<>]*\s*\(", lines[index]):
            return index + 1
    raise SystemExit("no enclosing constructor")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=pathlib.Path, required=True)
    parser.add_argument("--binary", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    sources, binary = zipfile.ZipFile(args.sources), zipfile.ZipFile(args.binary)
    source_sha, binary_sha = sha(args.sources.read_bytes()), sha(args.binary.read_bytes())

    def text(name: str) -> list[str]:
        return sources.read(name + ".java").decode("utf-8").replace("\r\n", "\n").split("\n")

    def klass(name: str) -> str:
        return sha(binary.read(name + ".class"))

    entity = text(ENTITY)
    is_affected = find_method(entity, r"\tprotected boolean isAffectedByBlocks\(\)")
    if "return !this.isRemoved() && !this.noPhysics;" not in method_at(entity, is_affected):
        raise SystemExit("Entity.isAffectedByBlocks no longer returns !removed && !noPhysics")
    apply_list = find_method(entity, r"\tprivate void applyEffectsFromBlocks\(final List<Entity\.Movement>")
    check_inside = find_method(entity, r"\tprivate void checkInsideBlocks\(final List<Entity\.Movement>")
    if "if (this.isAffectedByBlocks())" not in method_at(entity, apply_list) \
            or "if (this.isAffectedByBlocks())" not in method_at(entity, check_inside):
        raise SystemExit("block-effect guards in Entity changed")
    # Inside Entity, find every method that mentions a block-effect name: only the block-effect methods may.
    allowed = {"applyEffectsFromBlocks", "applyEffectsFromBlocksForLastMovements", "checkInsideBlocks", "isAffectedByBlocks"}
    current = None
    for line in entity:
        declared = re.match(r"\t(?:public|protected|private)(?:\s+static)?(?:\s+final)?\s+[\w<>\[\].]+\s+(\w+)\(", line)
        if declared:
            current = declared.group(1)
        if any(f"{name}(" in line for name in GUARD_NAMES) and current not in allowed and not line.lstrip().startswith("//"):
            raise SystemExit(f"Entity.{current} references a block-effect method: {line.strip()}")

    entity_types = sources.read("net/minecraft/world/entity/EntityTypes.java").decode("utf-8")
    proofs = {}
    for entity_type, (kind, chain) in SPECS.items():
        leaf = chain[0]
        simple = leaf.rsplit("/", 1)[-1]
        constant = entity_type.split(":")[1].upper()
        registered = re.search(r"EntityType<" + simple + r">\s+" + constant + r"\s*=\s*register\(\s*EntityTypeIds\." + constant
                               + r",\s*EntityType\.Builder\.(?:<" + simple + r">)?of\(" + simple + r"::new", entity_types)
        if registered is None:
            raise SystemExit(f"{entity_type} is not registered to {simple}")
        scanned = []
        for name in chain:
            lines = text(name)
            for index, line in enumerate(lines, 1):
                if any(guard in line for guard in GUARD_NAMES):
                    raise SystemExit(f"{name}:{index} mentions a block-effect method: {line.strip()}")
            scanned.append({"class": name.replace("/", "."), "file": name + ".java", "classBytecodeSha256": klass(name)})
        leaf_lines = text(leaf)
        if kind == "no_physics_blocks_effects":
            line_number = enclosing_declaration(leaf_lines, "this.noPhysics = true;")
            method = simple
        else:
            line_number = find_method(leaf_lines, r"\tpublic void tick\(\) \{")
            method = "tick"
        proofs[entity_type] = {
            "kind": kind, "sourceJarSha256": source_sha, "mappedBinarySha256": binary_sha,
            "nativeOutcome": "NO_NATIVE_ENTRY_PATH",
            "reference": {"file": leaf + ".java", "method": method, "line": line_number,
                          "sourceMethodSha256": sha(method_at(leaf_lines, line_number).encode("utf-8")),
                          "classBytecodeSha256": klass(leaf)},
            "supportingReferences": [
                {"file": ENTITY + ".java", "method": name, "line": line,
                 "sourceMethodSha256": sha(method_at(entity, line).encode("utf-8")), "classBytecodeSha256": klass(ENTITY)}
                for name, line in (("isAffectedByBlocks", is_affected), ("applyEffectsFromBlocks", apply_list),
                                   ("checkInsideBlocks", check_inside))],
            "classChain": scanned + [{"class": ENTITY.replace("/", "."), "file": ENTITY + ".java", "classBytecodeSha256": klass(ENTITY)}],
            "eligibilityPredicate": PREDICATES[kind], "applicabilityDemonstrated": True,
            "artifact": args.output.name,
        }
    args.output.write_text(json.dumps({"schemaVersion": 1, "sourceJarSha256": source_sha, "mappedBinarySha256": binary_sha,
                                       "proofs": proofs}, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(proofs)} proofs to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
