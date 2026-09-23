#!/usr/bin/env python3
"""Expand pinned no-native-entry census rows into an honest installed fixture plan.

This file plans cards. It never awards coverage or changes the census. A leash
knot cannot share its required fence block with a portal block, so those five
rows remain blocked for installed contact and use the separate pinned
source-only proof checked by the strict importer.
"""

from __future__ import annotations

import argparse
import json
import pathlib


ROUTES = {
    "NETHER_ENTRY": {"sourceWorld": "minecraft:overworld", "destinationWorld": "minecraft:the_nether",
                     "portalBlock": "minecraft:nether_portal", "scene": "Nether frame"},
    "NETHER_RETURN": {"sourceWorld": "minecraft:the_nether", "destinationWorld": "minecraft:overworld",
                      "portalBlock": "minecraft:nether_portal", "scene": "Nether frame"},
    "END_ENTRY": {"sourceWorld": "minecraft:overworld", "destinationWorld": "minecraft:the_end",
                  "portalBlock": "minecraft:end_portal", "scene": "End portal slab"},
    "END_EXIT": {"sourceWorld": "minecraft:the_end", "destinationWorld": "minecraft:overworld",
                 "portalBlock": "minecraft:end_portal", "scene": "End exit slab"},
    "END_GATEWAY": {"sourceWorld": "minecraft:the_end", "destinationWorld": "minecraft:the_end",
                    "portalBlock": "minecraft:end_gateway", "scene": "End gateway with exact far exit"},
}
SOURCE_FALSE = {"minecraft:ender_dragon", "minecraft:wither", "minecraft:fishing_bobber"}
STATIC_VOLUME = {"minecraft:block_display", "minecraft:item_display", "minecraft:text_display",
                 "minecraft:interaction"}
FRAMES = {"minecraft:item_frame", "minecraft:glow_item_frame"}
PAINTINGS = {"minecraft:painting"}
POINT_ONLY = {"minecraft:marker"}
FENCE_LOCKED = {"minecraft:leash_knot"}
TYPES = SOURCE_FALSE | STATIC_VOLUME | FRAMES | PAINTINGS | POINT_ONLY | FENCE_LOCKED
ASSERTIONS = ["source_unchanged", "destination_absent", "portal_session_absent", "graph_unchanged"]


def placement(entity_type: str) -> dict[str, str | bool]:
    if entity_type in SOURCE_FALSE:
        return {"kind": "native_block_contact", "contactCanBeAttempted": True,
                "note": "Native applyEffectsFromBlocks visits the portal; canUsePortal(false) rejects it."}
    if entity_type in STATIC_VOLUME:
        return {"kind": "static_portal_volume", "contactCanBeAttempted": False,
                "note": "Give the stationary entity a nonzero hitbox and place it in the portal block; its tick has no block-effects or handlePortal call."}
    if entity_type in FRAMES:
        return {"kind": "fixed_frame_in_portal_volume", "contactCanBeAttempted": False,
                "note": "Fixed frame can occupy portal volume without wall survival; verify it stays native and unchanged."}
    if entity_type in PAINTINGS:
        return {"kind": "wall_supported_portal_volume", "contactCanBeAttempted": False,
                "note": "Use SOUTH-facing 1x1 kebab with stone at portal z-1 and anchor in portal volume; prove survival after the native 100-tick attachment check."}
    if entity_type in POINT_ONLY:
        return {"kind": "portal_point_only", "contactCanBeAttempted": False,
                "note": "Marker has zero hitbox and ignores block triggers; prove its point is within the portal block, not physical intersection."}
    if entity_type in FENCE_LOCKED:
        return {"kind": "blocked_fence_occupancy", "contactCanBeAttempted": False,
                "note": "Leash knot requires a fence at its block position; a portal block cannot occupy that same block. Do not award a contact card."}
    raise ValueError(entity_type)


def build_plan(census: dict) -> dict:
    rows = [row for row in census["rows"] if row.get("expectedNativeOutcome") == "NO_NATIVE_ENTRY_PATH"]
    expected = {(entity_type, route) for entity_type in TYPES for route in ROUTES}
    found = {(row["entityType"], row["route"]) for row in rows}
    if len(rows) != 60 or found != expected or len(found) != len(rows):
        raise ValueError("source-proven no-entry matrix is no longer 12 types x 5 exact routes")
    cards = []
    for row in rows:
        entity_type = row["entityType"]
        route = row["route"]
        source_only = entity_type in FENCE_LOCKED
        expected_assertions = [] if source_only else ASSERTIONS
        if row.get("graph") != "standalone" or row.get("requiredAssertions") != expected_assertions:
            raise ValueError("no-entry row changed graph or negative assertions: " + row["id"])
        proof = row.get("sourceProof")
        if not isinstance(proof, dict) or proof.get("nativeOutcome") != "NO_NATIVE_ENTRY_PATH":
            raise ValueError("no-entry source proof absent: " + row["id"])
        scene = placement(entity_type)
        card = {
            "card": "N0-" + entity_type.removeprefix("minecraft:") + "-" + route,
            "caseId": row["id"], "entityType": entity_type, "route": route,
            "nativeOutcome": "NO_NATIVE_ENTRY_PATH", "sourceProofKind": proof.get("kind"),
            "sourceJarSha256": census["sourceJarSha256"],
            "mappedBinarySha256": census["mappedBinarySha256"],
            "requiredAssertions": expected_assertions.copy(), "requiredWorkers": [] if source_only else [1, 4],
            "routeScene": ROUTES[route], "placement": scene,
            "fixtureState": "BLOCKED" if source_only else "PLANNED",
            "creditableWithoutInstalledWitness": source_only and proof.get("kind") ==
                "stable_fence_occupancy_excludes_portal_contact",
            "installedWitness": None,
        }
        if entity_type == "minecraft:fishing_bobber":
            card["additionalEligibility"] = ("connected Java client casts fishing rod through native use-item packet; "
                                             "same-world player owner, rod equipped, hook within 32 blocks, "
                                             "native hook survives")
        if entity_type in {"minecraft:wither", "minecraft:ender_dragon"}:
            card["additionalEligibility"] = "isolated boss arena, portal block intact, source boss alive and stable"
        if route == "END_GATEWAY":
            card["additionalEligibility"] = (card.get("additionalEligibility", "") + "; "
                                             "gateway block entity exists, cooldown zero, exact remote End exit configured").strip("; ")
        cards.append(card)
    return {
        "schemaVersion": 1,
        "purpose": "planned final-byte negative cards; no coverage credited by this file",
        "sourceJarSha256": census["sourceJarSha256"],
        "mappedBinarySha256": census["mappedBinarySha256"],
        "caseCount": len(cards),
        "plannedCount": sum(card["fixtureState"] == "PLANNED" for card in cards),
        "blockedCount": sum(card["fixtureState"] == "BLOCKED" for card in cards),
        "cards": cards,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--census", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    plan = build_plan(json.loads(args.census.read_text(encoding="utf-8")))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(plan, indent=2) + "\n", encoding="utf-8")
    print("planned", plan["plannedCount"], "blocked", plan["blockedCount"], "of", plan["caseCount"])


if __name__ == "__main__":
    main()
