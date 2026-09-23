#!/usr/bin/env python3
"""Prove why a surviving native leash knot has no stable portal-contact fixture.

This creates a source artifact only. It does not alter the census or award credit.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import zipfile

SOURCE_SHA = "055D5A99D49917A03F68007A1AC2F1D0CBD84E28A16D953C6FFC943CE3E15E1E"
BINARY_SHA = "BA0569DEEC34D82FB3B31BC71F2400EE735E5711D4C4F54CE5ED9A789EA52892"
KNOT = "net/minecraft/world/entity/decoration/LeashFenceKnotEntity"
ATTACHED = "net/minecraft/world/entity/decoration/BlockAttachedEntity"
EXPECTED_METHODS = {
    (KNOT, "survives"): "6AF2BD900A5EA1BFF74D1335AB3BB8E91EE2686406CAF3CC38420162F6E6F4A5",
    (KNOT, "recalculateBoundingBox"): "D91052E6F7A90E4EB8BB44F47D4A9C920FAEA58CB512FFDCB3D3F6E2CDF872D4",
    (ATTACHED, "tick"): "C8900E7C6071C28D360C5B14599D3D01824FBD634A93AB65076844E83C168FAF",
}
EXPECTED_REGISTRATION = "853FCA397ED8EBE368025B30C7013A081D5ADC3F94630D89E4D19887460B8865"
PORTAL_BLOCKS = {"minecraft:nether_portal", "minecraft:end_portal", "minecraft:end_gateway"}
ROUTES = ("NETHER_ENTRY", "NETHER_RETURN", "END_ENTRY", "END_EXIT", "END_GATEWAY")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def file_sha(path: pathlib.Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest().upper()


def method(source: str, name: str) -> tuple[int, str]:
    pattern = re.compile(r"\b(?:public|protected|private)\s+(?:[\w<>.?@\[\]]+\s+)*"
                         + re.escape(name) + r"\s*\(")
    matches = list(pattern.finditer(source))
    if len(matches) != 1:
        raise ValueError(f"expected one declaration of {name}, found {len(matches)}")
    start = matches[0].start()
    opening = source.index("{", matches[0].end())
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source.count("\n", 0, start) + 1, source[start:index + 1]
    raise ValueError(f"unterminated method {name}")


def tag_values(binary: zipfile.ZipFile, tag: str, seen: set[str] | None = None) -> tuple[set[str], dict[str, str]]:
    seen = set() if seen is None else seen
    if tag in seen:
        raise ValueError(f"recursive block tag {tag}")
    seen.add(tag)
    namespace, path = tag.split(":", 1)
    member = f"data/{namespace}/tags/block/{path}.json"
    raw = binary.read(member)
    document = json.loads(raw)
    if document.get("replace") is True or not isinstance(document.get("values"), list):
        raise ValueError(f"unexpected block tag shape {tag}")
    values: set[str] = set()
    hashes = {member: sha(raw)}
    for value in document["values"]:
        if not isinstance(value, str):
            raise ValueError(f"non-string block tag entry {tag}")
        if value.startswith("#"):
            child, child_hashes = tag_values(binary, value[1:], seen)
            values |= child
            hashes.update(child_hashes)
        else:
            values.add(value)
    seen.remove(tag)
    return values, hashes


def build_proof(source_path: pathlib.Path, binary_path: pathlib.Path) -> dict:
    if file_sha(source_path) != SOURCE_SHA or file_sha(binary_path) != BINARY_SHA:
        raise ValueError("mapped native source or binary differs from pinned 26.2 oracle")
    references = []
    with zipfile.ZipFile(source_path) as sources, zipfile.ZipFile(binary_path) as binary:
        method_bodies: dict[tuple[str, str], str] = {}
        for (owner, name), expected in EXPECTED_METHODS.items():
            line, body = method(sources.read(owner + ".java").decode(), name)
            if sha(body.encode()) != expected:
                raise ValueError(f"pinned method changed: {owner}.{name}")
            method_bodies[owner, name] = body
            references.append({"file": owner + ".java", "method": name, "line": line,
                               "sourceMethodSha256": expected,
                               "classBytecodeSha256": sha(binary.read(owner + ".class"))})
        survives = method_bodies[KNOT, "survives"]
        bbox = method_bodies[KNOT, "recalculateBoundingBox"]
        attached_tick = method_bodies[ATTACHED, "tick"]
        knot_source = sources.read(KNOT + ".java").decode()
        if "return this.level().getBlockState(this.pos).is(BlockTags.FENCES);" not in survives:
            raise ValueError("leash knot fence survival predicate changed")
        if not all(fragment in bbox for fragment in (
            "this.pos.getX() + 0.5", "this.pos.getY() + 0.375", "this.pos.getZ() + 0.5",
            "double halfWidth = this.getType().getWidth() / 2.0;",
            "double height = this.getType().getHeight();")):
            raise ValueError("leash knot bounding-box placement changed")
        if any(fragment in attached_tick for fragment in (
            "super.tick(", "baseTick(", "handlePortal(", "applyEffectsFromBlocks(")):
            raise ValueError("block-attached tick acquired a native portal caller")
        if any(re.search(pattern, knot_source) for pattern in (
            r"\bvoid\s+tick\s*\(", r"\bvoid\s+baseTick\s*\(", r"\.handlePortal\s*\(")):
            raise ValueError("leash knot acquired a native portal caller")
        registry = sources.read("net/minecraft/world/entity/EntityTypes.java").decode()
        start = registry.index("public static final EntityType<LeashFenceKnotEntity> LEASH_KNOT")
        declaration = registry[start:registry.index(";", start) + 1]
        if sha(declaration.encode()) != EXPECTED_REGISTRATION or ".sized(0.375F, 0.5F)" not in declaration:
            raise ValueError("leash knot registered dimensions changed")
        fence_values, tag_hashes = tag_values(binary, "minecraft:fences")
        if not fence_values or fence_values & PORTAL_BLOCKS:
            raise ValueError("portal block entered the native fences tag")
    half_width = 0.375 / 2
    extent = {"x": [0.5 - half_width, 0.5 + half_width],
              "y": [0.375, 0.375 + 0.5],
              "z": [0.5 - half_width, 0.5 + half_width]}
    if any(low <= 0 or high >= 1 for low, high in extent.values()):
        raise ValueError("surviving knot box is no longer contained in its fence block")
    return {
        "schemaVersion": 1,
        "kind": "stable_fence_occupancy_excludes_portal_contact",
        "sourceJarSha256": SOURCE_SHA,
        "mappedBinarySha256": BINARY_SHA,
        "entityType": "minecraft:leash_knot",
        "caseIds": ["V2-LEASH_KNOT-" + route for route in ROUTES],
        "sourceReferences": references,
        "registration": {"file": "net/minecraft/world/entity/EntityTypes.java", "line": 570,
                         "sourceDeclarationSha256": EXPECTED_REGISTRATION,
                         "width": 0.375, "height": 0.5},
        "fenceTag": {"resolvedValues": sorted(fence_values), "memberSha256": tag_hashes,
                     "portalBlocksExcluded": sorted(PORTAL_BLOCKS)},
        "boundingBoxOffsetsWithinFenceBlock": extent,
        "conclusion": ("A stable native leash knot requires a fence in its own block; its entire hitbox "
                       "lies inside that block. None of the three portal blocks belongs to the resolved "
                       "fences tag. Its inherited tick also skips block effects and handlePortal, so "
                       "there is no native portal entry path for these five route obligations."),
        "coverageCreditedByThisArtifact": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-jar", type=pathlib.Path, required=True)
    parser.add_argument("--mapped-jar", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    proof = build_proof(args.source_jar, args.mapped_jar)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
    print("verified", len(proof["caseIds"]), "exact leash-knot source-only route obligations")


if __name__ == "__main__":
    main()
