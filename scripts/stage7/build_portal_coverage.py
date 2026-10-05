#!/usr/bin/env python3
"""Bind Stage 7 portal census rows to installed, final-byte observations.

This importer deliberately leaves a row unresolved when its native expectation,
test implementation, installed bytes, or semantic witnesses cannot be checked.
The older 843-ID inventory is read but never rewritten.
"""

from __future__ import annotations

import argparse
import collections
import copy
import dataclasses
import functools
import hashlib
import json
import math
import pathlib
import re
import sys
from typing import Any


OUTCOMES = {
    "TRANSFER",
    "REFUSAL",
    "NO_NATIVE_ENTRY_PATH",
    "ROUTE_NOT_APPLICABLE",
    "SAME_DIMENSION_TRANSFER",
}
DISPOSITIONS = {
    "TRANSFER": "TRANSFER_VERIFIED",
    "REFUSAL": "NATIVE_REFUSAL_VERIFIED",
    "NO_NATIVE_ENTRY_PATH": "NO_NATIVE_ENTRY_PATH_VERIFIED",
    "ROUTE_NOT_APPLICABLE": "ROUTE_NOT_APPLICABLE_VERIFIED",
    "SAME_DIMENSION_TRANSFER": "SAME_DIMENSION_TRANSFER_VERIFIED",
}
ROUTES = {"NETHER_ENTRY", "NETHER_RETURN", "END_ENTRY", "END_EXIT", "END_GATEWAY"}
LEASH_PROOF_SHA = "1412E119E1479551C76B574E01D6C90ECDCF750D3E5A7312D9BAA9EB1D3FF43C"
LEASH_PROOF_KIND = "stable_fence_occupancy_excludes_portal_contact"
LEASH_IDS = {"V2-LEASH_KNOT-" + route for route in ROUTES}
REQUIRED_TRANSFER_ASSERTIONS = {
    "source_retired", "destination_unique", "destination_owner", "tracking_unique"
}
REQUIRED_REFUSAL_ASSERTIONS = {
    "source_unchanged_or_safely_retired", "no_destination_entity", "no_stuck_session"
}
REQUIRED_SAME_DIMENSION_ASSERTIONS = {
    "same_native_object", "source_location_vacated", "destination_unique",
    "destination_owner", "tracking_unique", "same_dimension",
    "gateway_cooldown", "gateway_ticket"
}
OWNER_ASSERTIONS = {"source_owner", "destination_owner", "tracking_owner", "observer_phases"}
PROJECTILE_REQUIREMENTS = {
    "minecraft:fireball": {"post_arrival_tick", "impact_explosion"},
    "minecraft:small_fireball": {"post_arrival_tick", "impact_fire_effect"},
    "minecraft:dragon_fireball": {"post_arrival_tick", "dragon_breath_effect"},
    "minecraft:wither_skull": {"post_arrival_tick", "wither_impact_effect"},
    "minecraft:shulker_bullet": {"owner_target_preserved", "impact_effect"},
    "minecraft:arrow": {"projectile_owner_preserved", "impact_effect"},
    "minecraft:spectral_arrow": {"projectile_owner_preserved", "impact_effect"},
    "minecraft:trident": {"projectile_owner_preserved", "loyalty_effect"},
    "minecraft:ender_pearl": {"projectile_identity", "later_player_teleport"},
    "minecraft:egg": {"impact_effect"},
    "minecraft:snowball": {"impact_effect"},
    "minecraft:splash_potion": {"splash_effect"},
    "minecraft:lingering_potion": {"lingering_cloud_effect"},
    "minecraft:experience_bottle": {"xp_release"},
    "minecraft:firework_rocket": {"firework_effect"},
    "minecraft:wind_charge": {"wind_impact_effect"},
    "minecraft:breeze_wind_charge": {"wind_impact_effect"},
    "minecraft:llama_spit": {"impact_effect"},
}
SHA256_RE = re.compile(r"^[0-9A-F]{64}$")
MOB_CARD = re.compile(r"M0-([a-z_]+)-size(\d+)-minecraft:(overworld|the_nether)")
SILVERFISH_CARD = re.compile(r"M2-silverfish-(NETHER_ENTRY|NETHER_RETURN)")
SILVERFISH_IDS = {"V2-SILVERFISH-NETHER_ENTRY", "V2-SILVERFISH-NETHER_RETURN"}
ARMOR_CARD = re.compile(r"A0-(armor-stand|mannequin)-minecraft:(overworld|the_nether)-(empty|equipped)")
FALLING_CARD = re.compile(r"F1-(sand|gravel|anvil)-native-fall-end-entry")
ITEM_CARD = re.compile(r"I2-minecraft:(overworld|the_nether)")
PEARL_CARD = re.compile(r"I5-minecraft:(overworld|the_nether)")
RESPAWN_CARD = re.compile(r"R[1-5]-[a-z0-9-]+")
HURTING_PROJECTILE_CARD = re.compile(
    r"P1-(fireball|small_fireball|dragon_fireball|wither_skull|wind_charge|breeze_wind_charge)-(NETHER_ENTRY|NETHER_RETURN)")
ARROW_TRIDENT_CARD = re.compile(r"P2-(arrow|spectral_arrow|trident)-(NETHER_ENTRY|NETHER_RETURN)")
THROWABLE_PROJECTILE_CARD = re.compile(
    r"P3-(egg|snowball|splash_potion|lingering_potion|experience_bottle)-(NETHER_ENTRY|NETHER_RETURN)")
EMPTY_BOAT_TYPES = (
    "acacia_boat", "birch_boat", "cherry_boat", "dark_oak_boat",
    "jungle_boat", "mangrove_boat", "oak_boat", "pale_oak_boat",
    "spruce_boat", "bamboo_raft",
)
EMPTY_VEHICLE_CARD = re.compile(
    r"V0-(" + "|".join((*EMPTY_BOAT_TYPES, "minecart"))
    + r")-(NETHER_ENTRY|NETHER_RETURN)"
)
CHEST_BOAT_TYPES = tuple(
    kind.replace("_boat", "_chest_boat").replace("_raft", "_chest_raft")
    for kind in EMPTY_BOAT_TYPES
)
CHEST_VEHICLE_CARD = re.compile(
    r"V1-(" + "|".join(CHEST_BOAT_TYPES)
    + r")-(items|loot)-(NETHER_ENTRY|NETHER_RETURN)"
)
CHEST_MINECART_CARD = re.compile(
    r"V2-chest_minecart-(items|loot)-(NETHER_ENTRY|NETHER_RETURN)"
)
GATEWAY_PIG_CARD = "G1-pig-END_GATEWAY"
NO_NATIVE_ENTRY_CARD = re.compile(
    r"N0-(block_display|ender_dragon|glow_item_frame|interaction|item_display|item_frame|leash_knot|marker|painting|text_display|fishing_bobber|wither)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)")
ROUTE_SOURCE_WORLD = {
    "NETHER_ENTRY": "minecraft:overworld", "NETHER_RETURN": "minecraft:the_nether",
    "END_ENTRY": "minecraft:overworld", "END_EXIT": "minecraft:the_end",
    "END_GATEWAY": "minecraft:the_end",
}
ROUTE_DESTINATION_WORLD = {
    "NETHER_ENTRY": "minecraft:the_nether", "NETHER_RETURN": "minecraft:overworld",
    "END_ENTRY": "minecraft:the_end", "END_EXIT": "minecraft:overworld",
    "END_GATEWAY": "minecraft:the_end",
}
ROUTE_PORTAL_BLOCK = {
    "NETHER_ENTRY": "minecraft:nether_portal", "NETHER_RETURN": "minecraft:nether_portal",
    "END_ENTRY": "minecraft:end_portal", "END_EXIT": "minecraft:end_portal",
    "END_GATEWAY": "minecraft:end_gateway",
}
NO_ENTRY_SOURCE_GUARDS = {"minecraft:wither", "minecraft:fishing_bobber"}

# Legacy card names have meaning only while these test methods retain their
# reviewed assertion bodies. Hashes live in a separate reviewed contract file.
LEGACY_METHODS = {
    "passenger_graph": ("pigChickenGraphCard", "taggedMob", "snapshot", "health", "pass"),
    "no_native_entry": ("noNativeEntryMatrix", "noNativeEntryCase", "taggedMob", "probe",
                        "position", "snapshot", "health", "pass", "cmd", "require"),
    "fireball": ("fireballNetherReturnCard", "taggedProjectile", "projectileEvent", "snapshot", "health", "pass"),
    "hurting_projectile": ("hurtingProjectileMatrix", "hurtingProjectileCase", "taggedProjectile",
                           "projectileEvent", "snapshot", "health", "pass"),
    "arrow_trident": ("arrowTridentMatrix", "arrowTridentCase", "taggedProjectile",
                      "projectileEvent", "snapshot", "health", "pass"),
    "throwable_projectile": ("throwableProjectileMatrix", "throwableProjectileCase", "taggedProjectile",
                             "projectileEvent", "snapshot", "health", "pass"),
    "vehicle_empty": ("emptyVehicleMatrix", "emptyVehicleCase", "taggedMob", "snapshot",
                      "position", "cmd", "require", "health", "pass"),
    "vehicle_chest": ("chestVehicleMatrix", "chestVehicleCase", "requireChestStorage",
                      "looseVehicleItems", "taggedMob", "probe", "snapshot", "position", "cmd",
                      "require", "health", "pass"),
    "minecart_chest": ("chestMinecartMatrix", "chestMinecartCase",
                        "requireMinecartChestStorage", "looseVehicleItems", "taggedMob",
                        "probe", "snapshot", "position", "cmd", "require", "health", "pass"),
    "gateway_pig": ("pigEndGatewayCard", "taggedMob", "snapshot", "position",
                    "cmd", "require", "health", "pass"),
    "mob": ("mobPortalCase", "taggedMob", "snapshot", "health", "pass"),
    "armor": ("decorativeLivingCase", "taggedMob", "snapshot", "health", "pass"),
    "falling": ("fallingEndCards", "falling", "fallingArrival", "fallingLandingEvent",
                "snapshot", "cmd", "require", "pass"),
    "item": ("itemCard", "items", "snapshot", "health", "pass"),
    "cold_item": ("itemCard", "coldDestination", "items", "snapshot", "health", "pass"),
    "player": ("playerPortals", "awaitWorld", "snapshot", "health", "pass"),
    "pearl": ("pearlCard", "awaitWorld", "pearls", "snapshot", "health", "pass"),
    "respawn": ("bedAnchorFirstCards", "bedAnchorRestartCards", "deathAndClientRespawn", "pass"),
    "local_pearl": ("localPearl", "pearls", "snapshot", "health", "pass"),
    "native_respawn": ("respawns", "snapshot", "health", "pass"),
    "restart": ("restartCard", "snapshot", "pass"),
}
LEGACY_TEST_FILE = pathlib.Path("src/clientGameTest/java/dev/phoenium/clienttest/Stage7RepairClientTest.java")


def digest(path: pathlib.Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest().upper()


def read_json(path: pathlib.Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def valid_sha(value: Any) -> bool:
    return isinstance(value, str) and SHA256_RE.fullmatch(value.upper()) is not None


def _method_body_from_match(source: str, match: re.Match[str]) -> str | None:
    """Extract a Java method while ignoring braces in comments and literals."""
    opening = source.find("{", match.end())
    if opening < 0:
        return None
    depth = 0
    state = "code"
    index = opening
    while index < len(source):
        ch = source[index]
        nxt = source[index + 1] if index + 1 < len(source) else ""
        if state == "code":
            if ch == '"':
                state = "string"
            elif ch == "'":
                state = "char"
            elif ch == "/" and nxt == "/":
                state = "line"
                index += 1
            elif ch == "/" and nxt == "*":
                state = "block"
                index += 1
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return source[match.start():index + 1].replace("\r\n", "\n")
        elif state in ("string", "char"):
            if ch == "\\":
                index += 1
            elif ch == ('"' if state == "string" else "'"):
                state = "code"
        elif state == "line" and ch == "\n":
            state = "code"
        elif state == "block" and ch == "*" and nxt == "/":
            state = "code"
            index += 1
        index += 1
    return None


def method_digest(source: str, name: str) -> str | None:
    matches = re.finditer(r"\b(?:void|JsonObject|JsonArray|String|int|boolean)\s+"
                          + re.escape(name) + r"\s*\(", source)
    bodies = [_method_body_from_match(source, match) for match in matches]
    return (hashlib.sha256("\n".join(bodies).encode("utf-8")).hexdigest().upper()
            if bodies and all(bodies) else None)


def legacy_contract_ok(worktree: pathlib.Path, contracts: dict[str, str], family: str) -> bool:
    source_file = worktree / LEGACY_TEST_FILE
    if not source_file.is_file():
        return False
    source = source_file.read_text(encoding="utf-8")
    return all(valid_sha(contracts.get(name)) and method_digest(source, name) == contracts[name].upper()
               for name in LEGACY_METHODS[family])


@dataclasses.dataclass(frozen=True)
class Card:
    name: str
    workers: int
    phase: str
    receipt_path: pathlib.Path
    result_path: pathlib.Path
    inputs_path: pathlib.Path
    data: dict[str, Any]
    result_data: dict[str, Any] = dataclasses.field(default_factory=dict)
    receipt_data: dict[str, Any] = dataclasses.field(default_factory=dict)
    inputs_data: dict[str, Any] = dataclasses.field(default_factory=dict)
    evidence: str = "real-client"

    def reference(self, assertions: set[str] | None = None, scope: str = "row") -> dict[str, Any]:
        return {
            "card": self.name,
            "workers": self.workers,
            "phase": self.phase,
            "evidence": self.evidence,
            "scope": scope,
            "assertions": sorted(assertions or set()),
            "resultPath": str(self.result_path),
            "receiptPath": str(self.receipt_path),
            "inputsPath": str(self.inputs_path),
        }


def _test_sources_match(worktree: pathlib.Path, hashes: Any) -> bool:
    if not isinstance(hashes, dict) or not hashes:
        return False
    for relative, expected in hashes.items():
        if not valid_sha(expected):
            return False
        pieces = re.split(r"[/\\]+", relative)
        if ".." in pieces or any(not part for part in pieces):
            return False
        source = worktree.joinpath(*pieces)
        if not source.is_file() or digest(source) != expected.upper():
            return False
    return True


def scan_installed(
    roots: list[pathlib.Path], candidate_sha: str, worktree: pathlib.Path
) -> tuple[list[Card], list[dict[str, str]]]:
    cards: list[Card] = []
    rejected: list[dict[str, str]] = []
    installed_hash_cache: dict[pathlib.Path, str] = {}
    seen: set[pathlib.Path] = set()
    for root in roots:
        for result_path in sorted(root.glob("*/**/*-result.json")):
            if result_path in seen:
                continue
            seen.add(result_path)
            phase = result_path.name.removesuffix("-result.json")
            if phase == "headless":
                continue
            receipt_path = result_path.with_name(f"{phase}-client-receipt.json")
            inputs_path = result_path.with_name("inputs.json")
            reason = None
            try:
                if not receipt_path.is_file() or not inputs_path.is_file():
                    reason = "missing client receipt or inputs"
                else:
                    result, receipt, inputs = map(read_json, (result_path, receipt_path, inputs_path))
                    if not isinstance(result, dict) or not isinstance(receipt, dict) or not isinstance(inputs, dict):
                        reason = "malformed result, receipt, or inputs"
                    elif any(doc.get("candidateSha256", "").upper() != candidate_sha for doc in (result, receipt, inputs)):
                        reason = "candidate hash mismatch"
                    elif result.get("installedCandidateSha256", "").upper() != candidate_sha:
                        reason = "installed candidate hash mismatch"
                    elif result.get("phase") != phase:
                        reason = "result phase mismatch"
                    elif not all((result.get("pass") is True, result.get("clientPass") is True,
                                  receipt.get("pass") is True, result.get("logFailure") is False,
                                  result.get("normalSave") is True,
                                  result.get("normalStopRequested") is True,
                                  result.get("serverExitCode") == 0,
                                  result.get("clientExitCode") == 0)):
                        reason = "phase did not pass and save/stop cleanly"
                    elif any(result.get(key) for key in ("sourceWorldChanges", "testSourceChanged")):
                        reason = "source world or test source changed during run"
                    elif receipt.get("group") != inputs.get("group"):
                        reason = "receipt group mismatch"
                    elif not isinstance(inputs.get("cards", []), list) or any(
                            not isinstance(name, str) or not name for name in inputs.get("cards", [])):
                        reason = "malformed selected card list"
                    elif inputs.get("cards") and (
                            len(inputs["cards"]) != 1
                            or receipt.get("selectedCards") != inputs["cards"][0]
                            or not isinstance(receipt.get("cards"), list)
                            or [item.get("card") for item in receipt["cards"] if isinstance(item, dict)]
                               != inputs["cards"]):
                        reason = "selected card receipt differs from requested card"
                    elif not _test_sources_match(worktree, inputs.get("testSourceHashes")):
                        reason = "test source identity differs from installed run"
                    elif inputs.get("workers") not in (1, 4):
                        reason = "unsupported worker count"
                    else:
                        installed_path = pathlib.Path(inputs.get("candidate", ""))
                        if installed_path not in installed_hash_cache:
                            installed_hash_cache[installed_path] = digest(installed_path) if installed_path.is_file() else ""
                        if installed_hash_cache[installed_path] != candidate_sha:
                            reason = "installed candidate copy differs from final candidate"
                        elif not isinstance(receipt.get("cards"), list):
                            reason = "missing card list"
                        else:
                            for card in receipt["cards"]:
                                if isinstance(card, dict) and card.get("result") == "PASS" and isinstance(card.get("card"), str):
                                    cards.append(Card(card["card"], inputs["workers"], phase,
                                                      receipt_path, result_path, inputs_path, card,
                                                      result, receipt, inputs))
            except (OSError, ValueError, TypeError, KeyError) as error:
                reason = f"unreadable evidence: {error}"
            if reason:
                rejected.append({"path": str(result_path), "reason": reason})
    return cards, rejected


def route_from_source(source: str) -> str:
    return "NETHER_ENTRY" if source == "overworld" else "NETHER_RETURN"


def trace_entity_type(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    if value.startswith("entity."):
        parts = value.split(".", 2)
        if len(parts) == 3:
            return parts[1] + ":" + parts[2]
    return value


@functools.lru_cache(maxsize=256)
def _trace_events(path: pathlib.Path) -> tuple[dict[str, Any], ...]:
    events = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        marker = "STAGE7_PORTAL_TX {"
        at = line.find(marker)
        if at < 0:
            continue
        try:
            event = json.loads(line[at + len("STAGE7_PORTAL_TX "):])
        except json.JSONDecodeError:
            continue
        if isinstance(event, dict):
            events.append(event)
    return tuple(events)


def silverfish_snapshot_witness(card: Card, route: str) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]] | None:
    """Recover one exact M2 source, arrival and continuation from raw snapshots."""
    match = SILVERFISH_CARD.fullmatch(card.name)
    if (match is None or match.group(1) != route
            or card.inputs_data.get("group") != "mob-prefix"
            or card.receipt_data.get("selectedCards") not in ("", card.name)
            or not isinstance(card.receipt_data.get("observations"), list)):
        return None
    details = card.data.get("details", "")
    found = re.search(r"\bUUID=([0-9a-fA-F-]{36})\b", details) if isinstance(details, str) else None
    if found is None:
        return None
    uuid = found.group(1).lower()
    if not re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", uuid):
        return None
    observations = card.receipt_data["observations"]
    prefix = card.name + "-"

    def members(snapshot: dict[str, Any]) -> list[dict[str, Any]]:
        entities = snapshot.get("entities")
        return ([entity for entity in entities if isinstance(entity, dict)
                 and isinstance(entity.get("uuid"), str)
                 and entity["uuid"].lower() == uuid]
                if isinstance(entities, list) else [])

    source_states = [state for state in observations if isinstance(state, dict)
                     and state.get("label") == prefix + "source"]
    if len(source_states) != 1 or len(members(source_states[0])) != 1:
        return None
    source = members(source_states[0])[0]
    destination = ROUTE_DESTINATION_WORLD[route]
    arrivals = [state for state in observations if isinstance(state, dict)
                and state.get("label") == prefix + "wait"
                and len(members(state)) == 1
                and members(state)[0].get("world") == destination]
    if len(arrivals) != 1:
        return None
    arrival = members(arrivals[0])[0]
    later_states = [state for state in observations if isinstance(state, dict)
                    and state.get("label") == prefix + "later"]
    if not later_states or any(len(members(state)) != 1 for state in later_states):
        return None
    later = members(later_states[-1])[0]
    expected_tag = "stage7_portal_mob_" + ("0" if route == "NETHER_ENTRY" else "1")
    if (any(part.get("type") != "minecraft:silverfish" or part.get("removed") is not False
            or not isinstance(part.get("health"), (int, float)) or part["health"] <= 0
            or part.get("vehicleUuid") != "UNAVAILABLE" or part.get("passengerUuids") != []
            or not isinstance(part.get("object"), str) or not part["object"]
            or not isinstance(part.get("nativeId"), int) or part["nativeId"] < 0
            for part in (source, arrival, later))
            or source.get("world") != ROUTE_SOURCE_WORLD[route]
            or source.get("portalCooldown") != 0
            or source.get("x") != 0.5 or source.get("y") != 65.0 or source.get("z") != 2.5
            or destination != arrival.get("world") or destination != later.get("world")
            or source["object"] == arrival["object"]
            or source["nativeId"] == arrival["nativeId"]
            or arrival["object"] != later["object"] or arrival["nativeId"] != later["nativeId"]
            or not isinstance(arrival.get("ticks"), int)
            or not isinstance(later.get("ticks"), int)
            or later["ticks"] <= arrival["ticks"] + 20
            or not isinstance(source.get("tags"), str)
            or expected_tag not in source["tags"] or "stage7_mob" not in source["tags"]
            or source["tags"] != arrival.get("tags") or source["tags"] != later.get("tags")
            or any(member.get("object") == source["object"]
                   or member.get("nativeId") == source["nativeId"]
                   for state in (arrivals[0], later_states[-1])
                   for member in state.get("entities", []) if isinstance(member, dict))):
        return None
    if any((part.get("world"), part.get("object"), part.get("nativeId")) !=
           (destination, arrival["object"], arrival["nativeId"])
           for state in later_states for part in members(state)):
        return None
    return source, arrival, later


def observer_assertions(card: Card, entity_type: str, route: str,
                        uuid_override: str | None = None, window: Any = None) -> set[str]:
    """Credit owner context only from a matching final-byte transaction trace."""
    silverfish = SILVERFISH_CARD.fullmatch(card.name)
    silverfish_witness = None
    if silverfish:
        if entity_type != "minecraft:silverfish" or silverfish.group(1) != route:
            return set()
        silverfish_witness = silverfish_snapshot_witness(card, route)
        if silverfish_witness is None:
            return set()
    result = card.result_data
    status = result.get("txStatus")
    if (result.get("txPass") is not True or not isinstance(status, dict)
            or status.get("pending") != 0 or status.get("violations") != 0):
        return set()
    candidate_sha = result.get("candidateSha256")
    probe_sha = status.get("testSourceSha256")
    if (status.get("candidateSha256") != candidate_sha or not valid_sha(probe_sha)
            or probe_sha != card.inputs_data.get("probeSha256")):
        return set()
    trace_value = result.get("txTracePath")
    if not isinstance(trace_value, str):
        return set()
    trace_path = pathlib.Path(trace_value)
    if (not trace_path.is_file() or trace_path.resolve().parent != card.result_path.resolve().parent
            or digest(trace_path) != result.get("txTraceSha256")):
        return set()
    details = card.data.get("details", "")
    found_uuid = (re.search(r"([0-9a-fA-F-]{36})", uuid_override)
                  if isinstance(uuid_override, str) else
                  re.search(r"\bUUID=([0-9a-fA-F-]{36})\b", details))
    if not found_uuid:
        source = card.receipt_data.get(card.name + "-source")
        if isinstance(source, dict) and isinstance(source.get("uuid"), str):
            found_uuid = re.search(r"([0-9a-fA-F-]{36})", source["uuid"])
    if not found_uuid:
        return set()
    uuid = found_uuid.group(1).lower()
    scoped = _trace_events(trace_path)
    if window is not None:
        if (not isinstance(window, list) or len(window) != 2 or not all(isinstance(bound, int) for bound in window)
                or not 0 <= window[0] <= window[1] <= len(scoped)):
            return set()
        scoped = scoped[window[0]:window[1]]
    matching = [event for event in scoped
                if event.get("uuid", "").lower() == uuid
                and trace_entity_type(event.get("type")) == entity_type and event.get("route") == route
                and event.get("candidateSha256") == candidate_sha
                and event.get("testSourceSha256") == probe_sha
                and isinstance(event.get("generation"), int) and event["generation"] > 0]
    by_generation: dict[int, list[dict[str, Any]]] = collections.defaultdict(list)
    for event in matching:
        by_generation[event["generation"]].append(event)
    phases = ("SOURCE_RETIRING", "ADD_DURING_TELEPORT_HEAD",
              "CHUNK_MAP_TRACKING_HEAD", "CHUNK_MAP_TRACKING_RETURN",
              "ADD_DURING_TELEPORT_RETURN")
    for events in by_generation.values():
        selected = {}
        for index, event in enumerate(events):
            selected.setdefault(event.get("phase"), (index, event))
        if not all(phase in selected for phase in phases):
            continue
        if [selected[phase][0] for phase in phases] != sorted(selected[phase][0] for phase in phases):
            continue
        if silverfish_witness is not None:
            retired = selected.get("SOURCE_RETIRED")
            settled = selected.get("TRANSFER_SETTLED_AFTER_SUFFIX")
            if (retired is None or settled is None
                    or not (selected["SOURCE_RETIRING"][0] < retired[0]
                            < selected["ADD_DURING_TELEPORT_HEAD"][0])
                    or settled[0] <= selected["ADD_DURING_TELEPORT_RETURN"][0]):
                continue
        source = selected["SOURCE_RETIRING"][1]
        admission = selected["ADD_DURING_TELEPORT_HEAD"][1]
        dest = admission.get("destinationWorld")
        if (source.get("sourceWorld") != ROUTE_SOURCE_WORLD[route]
                or dest != ROUTE_DESTINATION_WORLD[route]
                or source.get("ownerWorld") != ROUTE_SOURCE_WORLD[route]
                or any(selected[phase][1].get("ownerWorld") != dest
                       or selected[phase][1].get("destinationWorld") != dest
                       for phase in phases[1:])
                or any(event.get("violation") for event in events)):
            continue
        source_snapshot = (silverfish_witness[0] if silverfish_witness is not None
                           else card.receipt_data.get(card.name + "-source"))
        arrival_snapshot = (silverfish_witness[1] if silverfish_witness is not None
                            else card.receipt_data.get(card.name + "-arrival"))
        if isinstance(source_snapshot, dict) and isinstance(arrival_snapshot, dict):
            if (not isinstance(source_snapshot.get("uuid"), str)
                    or not isinstance(arrival_snapshot.get("uuid"), str)
                    or source_snapshot["uuid"].lower() != uuid
                    or arrival_snapshot["uuid"].lower() != uuid
                    or source.get("nativeId") != source_snapshot.get("nativeId")
                    or source.get("object") != source_snapshot.get("object")
                    or admission.get("nativeId") != arrival_snapshot.get("nativeId")
                    or admission.get("object") != arrival_snapshot.get("object")):
                continue
        if silverfish_witness is not None:
            old = (source_snapshot["nativeId"], source_snapshot["object"])
            if (retired[1].get("ownerWorld") != ROUTE_SOURCE_WORLD[route]
                    or (retired[1].get("nativeId"), retired[1].get("object")) != old
                    or settled[1].get("ownerWorld") != "NONE"
                    or (settled[1].get("nativeId"), settled[1].get("object")) != old):
                continue
        return OWNER_ASSERTIONS.copy()
    return set()


def graph_observer_assertions(card: Card, route: str) -> set[str]:
    """Require owner traces for both nodes of the exact pig/chicken graph."""
    members = (("sourcePig", "minecraft:pig"), ("sourceChicken", "minecraft:chicken"))
    for suffix, entity_type in members:
        source = card.receipt_data.get(card.name + "-" + suffix)
        if not isinstance(source, dict) or not isinstance(source.get("uuid"), str):
            return set()
        if not OWNER_ASSERTIONS <= observer_assertions(card, entity_type, route, source["uuid"]):
            return set()
    return OWNER_ASSERTIONS.copy()


def negative_assertions(card: Card, entity_type: str, route: str) -> set[str]:
    """Derive a negative witness from bound snapshots and the full tx trace."""
    source = card.receipt_data.get(card.name + "-source")
    settled_state = card.receipt_data.get(card.name + "-settledState")
    applicability = card.receipt_data.get(card.name + "-applicability")
    if not isinstance(source, dict) or not isinstance(settled_state, dict) or not isinstance(applicability, dict):
        return set()
    uuid = source.get("uuid")
    if not isinstance(uuid, str) or re.fullmatch(r"[0-9a-fA-F-]{36}", uuid) is None:
        return set()
    source_world = ROUTE_SOURCE_WORLD[route]
    expected_kind = ("unconditional_canUsePortal_false" if entity_type in NO_ENTRY_SOURCE_GUARDS
                     else "tick_override_skips_portal_processing")
    if (applicability.get("sourceWorld") != source_world
            or applicability.get("destinationWorld") != ROUTE_DESTINATION_WORLD[route]
            or applicability.get("portalBlock") != ROUTE_PORTAL_BLOCK[route]
            or applicability.get("sourceProofKind") != expected_kind
            or not all(isinstance(applicability.get(key), int) for key in ("portalX", "portalY", "portalZ"))
            or not all(valid_sha(applicability.get(key)) for key in ("sourceJarSha256", "mappedBinarySha256"))):
        return set()
    if (source.get("type") != entity_type or source.get("world") != source_world
            or source.get("removed") is not False):
        return set()
    if any(not isinstance(source.get(axis), (int, float))
           or int(source[axis] // 1) != applicability["portal" + axis.upper()]
           for axis in ("x", "y", "z")):
        return set()
    entities = settled_state.get("entities")
    if not isinstance(entities, list):
        return set()
    matches = [item for item in entities if isinstance(item, dict)
               and isinstance(item.get("uuid"), str) and item["uuid"].lower() == uuid.lower()]
    if len(matches) != 1:
        return set()
    settled = matches[0]
    assertions = set()
    if (settled.get("type") == entity_type and settled.get("world") == source_world
            and settled.get("object") == source.get("object")
            and settled.get("nativeId") == source.get("nativeId")
            and settled.get("removed") is False):
        assertions.update(("source_unchanged", "destination_absent"))
    if (settled.get("vehicleUuid") == source.get("vehicleUuid")
            and settled.get("passengerUuids") == source.get("passengerUuids")
            and "vehicleUuid" in source and "passengerUuids" in source):
        assertions.add("graph_unchanged")
    if source.get("portalProcessPresent") is not False or settled.get("portalProcessPresent") is not False:
        return assertions
    contacts = settled_state.get("noEntryContactEvents")
    if not isinstance(contacts, list):
        return assertions
    matching_contacts = [event for event in contacts if isinstance(event, dict)
                         and str(event.get("uuid", "")).lower() == uuid.lower()]
    expected_contact = (entity_type in NO_ENTRY_SOURCE_GUARDS)
    if expected_contact != any(
            event.get("type") == entity_type and event.get("world") == source_world
            and event.get("block") == ROUTE_PORTAL_BLOCK[route]
            and (event.get("x"), event.get("y"), event.get("z")) ==
            (applicability["portalX"], applicability["portalY"], applicability["portalZ"])
            for event in matching_contacts):
        return assertions
    if not expected_contact and matching_contacts:
        return assertions
    result = card.result_data
    status = result.get("txStatus")
    if (result.get("txPass") is not True or not isinstance(status, dict)
            or status.get("pending") != 0 or status.get("violations") != 0
            or status.get("candidateSha256") != result.get("candidateSha256")
            or status.get("testSourceSha256") != card.inputs_data.get("probeSha256")):
        return assertions
    trace_value = result.get("txTracePath")
    if not isinstance(trace_value, str):
        return assertions
    trace_path = pathlib.Path(trace_value)
    if (not trace_path.is_file() or trace_path.resolve().parent != card.result_path.resolve().parent
            or digest(trace_path) != result.get("txTraceSha256")):
        return assertions
    if any(str(event.get("uuid", "")).lower() == uuid.lower() for event in _trace_events(trace_path)):
        return assertions
    assertions.add("portal_session_absent")
    return assertions


def empty_vehicle_assertions(card: Card, entity_type: str, route: str) -> set[str]:
    """Read the exact V0 source, destination and later-tick snapshots."""
    source = card.receipt_data.get(card.name + "-source")
    arrival = card.receipt_data.get(card.name + "-arrival")
    observations = card.receipt_data.get("observations")
    if not isinstance(source, dict) or not isinstance(arrival, dict) or not isinstance(observations, list):
        return set()
    uuid = source.get("uuid")
    if (not isinstance(uuid, str) or re.fullmatch(r"[0-9a-fA-F-]{36}", uuid) is None
            or source.get("type") != entity_type or arrival.get("type") != entity_type
            or source.get("world") != ROUTE_SOURCE_WORLD[route]
            or arrival.get("world") != ROUTE_DESTINATION_WORLD[route]
            or arrival.get("uuid") != uuid
            or source.get("object") == arrival.get("object")
            or source.get("nativeId") == arrival.get("nativeId")
            or source.get("removed") is not False or arrival.get("removed") is not False):
        return set()
    empty_graph = (source.get("vehicleUuid") == "UNAVAILABLE"
                   and arrival.get("vehicleUuid") == "UNAVAILABLE"
                   and source.get("passengerUuids") == []
                   and arrival.get("passengerUuids") == []
                   and source.get("tags") == arrival.get("tags"))
    snapshots = {item.get("label"): item for item in observations
                 if isinstance(item, dict) and isinstance(item.get("label"), str)}
    scoped = snapshots.get(card.name + "-scoped", {})
    later = snapshots.get(card.name + "-later", {})
    scoped_matches = [item for item in scoped.get("entities", []) if isinstance(item, dict)
                      and item.get("uuid") == uuid]
    if (len(scoped_matches) != 1 or scoped_matches[0].get("world") != ROUTE_DESTINATION_WORLD[route]
            or not isinstance(arrival.get("ticks"), int)):
        return set()
    later_matches = [item for item in later.get("entities", []) if isinstance(item, dict)
                     and item.get("uuid") == uuid]
    assertions = {"source_retired", "destination_unique", "tracking_unique",
                  "uuid_preserved", "replacement_identity", "client_tracking"}
    if empty_graph:
        assertions.add("vehicle_state_preserved")
    if (len(later_matches) == 1 and later_matches[0].get("world") == ROUTE_DESTINATION_WORLD[route]
            and isinstance(later_matches[0].get("ticks"), int)
            and later_matches[0]["ticks"] > arrival["ticks"] + 20):
        assertions.add("post_arrival_tick")
    return assertions


def chest_vehicle_assertions(card: Card, entity_type: str, route: str,
                             storage: str) -> set[str]:
    """Derive a V1 witness from raw storage and identity at all three checkpoints."""
    source = card.receipt_data.get(card.name + "-source")
    arrival = card.receipt_data.get(card.name + "-arrival")
    later = card.receipt_data.get(card.name + "-later")
    loose = card.receipt_data.get(card.name + "-looseItems")
    observations = card.receipt_data.get("observations")
    if (not all(isinstance(part, dict) for part in (source, arrival, later, loose))
            or not isinstance(observations, list)):
        return set()
    uuid = source.get("uuid")
    if (not isinstance(uuid, str) or re.fullmatch(r"[0-9a-fA-F-]{36}", uuid) is None
            or any(part.get("uuid") != uuid or part.get("type") != entity_type
                   or part.get("removed") is not False for part in (source, arrival, later))
            or source.get("world") != ROUTE_SOURCE_WORLD[route]
            or arrival.get("world") != ROUTE_DESTINATION_WORLD[route]
            or later.get("world") != ROUTE_DESTINATION_WORLD[route]
            or source.get("object") in (None, arrival.get("object"))
            or source.get("nativeId") in (None, arrival.get("nativeId"))
            or later.get("object") != arrival.get("object")
            or later.get("nativeId") != arrival.get("nativeId")
            or source.get("tags") != arrival.get("tags")
            or arrival.get("tags") != later.get("tags")):
        return set()
    if any(part.get("vehicleUuid") != "UNAVAILABLE"
           or part.get("passengerUuids") != [] for part in (source, arrival, later)):
        return set()
    if (not isinstance(arrival.get("ticks"), int)
            or not isinstance(later.get("ticks"), int)
            or later["ticks"] <= arrival["ticks"] + 20):
        return set()
    minecart = entity_type == "minecraft:chest_minecart"
    second_item = "minecraft:iron_ingot" if minecart else "minecraft:sugar_cane"
    second_count = 13 if minecart else 3
    second_key = "Iron" if minecart else "SugarCane"
    expected_items = ([{"slot": 0, "item": "minecraft:diamond", "count": 7},
                       {"slot": 26, "item": second_item, "count": second_count}]
                      if storage == "items" else [])
    expected_table = ("UNAVAILABLE" if storage == "items"
                      else "minecraft:chests/simple_dungeon")
    expected_seed = 0 if storage == "items" else 123456789
    if any(part.get("vehicleSlotCount") != 27
           or part.get("vehicleItems") != expected_items
           or part.get("vehicleLootTable") != expected_table
           or part.get("vehicleLootSeed") != expected_seed
           for part in (source, arrival, later)):
        return set()
    if (set(loose) != {"sourceDiamonds", "arrivalDiamonds", "laterDiamonds",
                       "source" + second_key, "arrival" + second_key, "later" + second_key}
            or any(value != 0 for value in loose.values())):
        return set()
    def states(label: str, entity: dict[str, Any]) -> list[dict[str, Any]]:
        return [state for state in observations
                if isinstance(state, dict) and state.get("label") == label
                and isinstance(state.get("entities"), list)
                and sum(isinstance(part, dict) and part.get("uuid") == uuid
                        for part in state["entities"]) == 1
                and entity in state["entities"]]

    source_states = states(card.name + "-source", source)
    arrival_states = states(card.name + "-wait", arrival)
    later_states = states(card.name + "-later", later)
    if len(source_states) != 1 or len(arrival_states) != 1 or len(later_states) != 1:
        return set()

    def loose_count(state: dict[str, Any], item_id: str) -> int | None:
        count = 0
        for part in state["entities"]:
            if not isinstance(part, dict):
                return None
            if part.get("type") == "minecraft:item" and part.get("item") == item_id:
                amount = part.get("count")
                if not isinstance(amount, int) or amount < 0:
                    return None
                count += amount
        return count

    for stage, state in (("source", source_states[0]), ("arrival", arrival_states[0]),
                         ("later", later_states[0])):
        if (loose_count(state, "minecraft:diamond") != loose[stage + "Diamonds"]
                or loose_count(state, second_item) != loose[stage + second_key]):
            return set()

    snapshots = {item.get("label"): item for item in observations
                 if isinstance(item, dict) and isinstance(item.get("label"), str)}
    scoped = snapshots.get(card.name + "-scoped", {})
    later_state = later_states[0]
    scoped_matches = [item for item in scoped.get("entities", []) if isinstance(item, dict)
                      and item.get("uuid") == uuid]
    later_matches = [item for item in later_state.get("entities", []) if isinstance(item, dict)
                     and item.get("uuid") == uuid]
    if (len(scoped_matches) != 1
            or scoped_matches[0].get("world") != ROUTE_DESTINATION_WORLD[route]
            or len(later_matches) != 1 or later_matches[0] != later):
        return set()
    return {"source_retired", "destination_unique", "tracking_unique",
            "uuid_preserved", "replacement_identity", "client_tracking",
            "vehicle_state_preserved", "post_arrival_tick", "inventory_preserved",
            "no_loose_item_duplication"}


def gateway_pig_assertions(card: Card) -> set[str]:
    """Read the exact same-object gateway snapshots and native hook witnesses.

    The G1 method contract binds the RCON vacancy and connected-client checks;
    this parser also requires their results and independently checks the raw
    source, arrival, later, contact, ticket, and tracking records. No
    cross-dimension retirement/admission phase is expected for this route.
    """
    name = GATEWAY_PIG_CARD
    receipt = card.receipt_data
    source, arrival, later = (receipt.get(name + "-" + suffix)
                              for suffix in ("source", "arrival", "later"))
    contact, ticket, tracking = (receipt.get(name + "-" + suffix)
                                 for suffix in ("gatewayContact", "gatewayTicket",
                                                "destinationTracking"))
    if any(not isinstance(part, dict) for part in
           (source, arrival, later, contact, ticket, tracking)):
        return set()
    uuid = source.get("uuid")
    if (not isinstance(uuid, str) or re.fullmatch(r"[0-9a-fA-F-]{36}", uuid) is None
            or source.get("type") != "minecraft:pig"
            or any(part.get("uuid") != uuid or part.get("world") != "minecraft:the_end"
                   or part.get("removed") is not False
                   for part in (source, arrival, later))
            or not isinstance(source.get("object"), str)
            or not (source.get("object") == arrival.get("object") == later.get("object"))
            or not isinstance(source.get("nativeId"), int)
            or not (source.get("nativeId") == arrival.get("nativeId") == later.get("nativeId"))
            or any(part.get("vehicleUuid") != "UNAVAILABLE"
                   or part.get("passengerUuids") != [] for part in (source, arrival, later))
            or (source.get("x"), source.get("y"), source.get("z")) != (12.5, 66.0, 8.5)
            or source.get("chunkX") != 0 or source.get("chunkZ") != 0
            or any(part.get("chunkX") != 10 or part.get("chunkZ") != 10
                   or not isinstance(part.get("x"), (int, float))
                   or not isinstance(part.get("z"), (int, float))
                   or abs(part.get("x", -1000) - 160.5) >= 1
                   or abs(part.get("z", -1000) - 160.5) >= 1
                   for part in (arrival, later))
            or not isinstance(arrival.get("ticks"), int)
            or not isinstance(later.get("ticks"), int)
            or later["ticks"] <= arrival["ticks"] + 20
            or not isinstance(arrival.get("portalCooldown"), int)
            or not isinstance(later.get("portalCooldown"), int)
            or arrival["portalCooldown"] <= 0 or later["portalCooldown"] <= 0
            or receipt.get(name + "-oldVacancy") is not True
            or receipt.get(name + "-clientTracking") is not True):
        return set()
    source_diagnostic = source.get("ownershipDiagnostic")
    diagnostic = arrival.get("ownershipDiagnostic")
    def region(value: Any) -> str | None:
        if not isinstance(value, str):
            return None
        match = re.search(r"(?:region|owner)=(RegionId\[world=WorldId\[value=[^\]]+\], value=[^\]]+\])", value)
        return match.group(1) if match else None
    def incarnation(value: Any) -> str | None:
        if not isinstance(value, str):
            return None
        match = re.search(r"\bincarnation=(\d+)\b", value)
        return match.group(1) if match else None
    source_region, destination_region = region(source_diagnostic), region(diagnostic)
    if (not isinstance(source_diagnostic, str) or uuid not in source_diagnostic
            or "x=0, z=0" not in source_diagnostic or "state=OWNED" not in source_diagnostic
            or not isinstance(diagnostic, str) or uuid not in diagnostic
            or "x=10, z=10" not in diagnostic or "state=OWNED" not in diagnostic
            or source_region is None or destination_region is None
            or incarnation(source_diagnostic) is None
            or incarnation(source_diagnostic) != incarnation(diagnostic)):
        return set()
    if (contact.get("uuid") != uuid or contact.get("world") != "minecraft:the_end"
            or (contact.get("x"), contact.get("y"), contact.get("z")) != (12, 66, 8)
            or contact.get("ownerWorld") != "minecraft:the_end"
            or contact.get("ownerContext") in (None, "NONE")
            or region(contact.get("ownerContext")) != source_region
            or ticket.get("uuid") != uuid or ticket.get("world") != "minecraft:the_end"
            or ticket.get("ticketType") != "minecraft:portal"
            or (ticket.get("chunkX"), ticket.get("chunkZ"), ticket.get("radius")) != (10, 10, 3)
            or ticket.get("ownerWorld") != "minecraft:the_end"
            or ticket.get("ownerContext") in (None, "NONE")
            or region(ticket.get("ownerContext")) != destination_region
            or tracking.get("uuid") != uuid or tracking.get("world") != "minecraft:the_end"
            or tracking.get("phase") != "TRACKING_UPDATE_RETURN"
            or tracking.get("object") != source["object"]
            or tracking.get("nativeId") != source["nativeId"]
            or (tracking.get("chunkX"), tracking.get("chunkZ")) != (10, 10)
            or tracking.get("ownerWorld") != "minecraft:the_end"
            or tracking.get("ownerContext") in (None, "NONE")
            or region(tracking.get("ownerContext")) != destination_region):
        return set()
    observations = receipt.get("observations")
    if not isinstance(observations, list):
        return set()
    def exact_snapshot(prefix: str, expected: dict[str, Any]) -> bool:
        return any(isinstance(snapshot, dict)
                   and isinstance(snapshot.get("label"), str)
                   and snapshot["label"].startswith(name + prefix)
                   and isinstance(snapshot.get("entities"), list)
                   and len([entity for entity in snapshot.get("entities", [])
                            if isinstance(entity, dict) and entity.get("uuid") == uuid]) == 1
                   and expected in snapshot.get("entities", [])
                   for snapshot in observations)
    if not exact_snapshot("-wait-", arrival) or not exact_snapshot("-later-poll", later):
        return set()
    return {"same_native_object", "source_location_vacated", "destination_unique",
            "destination_owner", "tracking_unique", "same_dimension",
            "gateway_cooldown", "gateway_ticket", "post_arrival_tick",
            "source_owner", "tracking_owner", "observer_phases", "client_tracking"}


def falling_source_cell_assertions(card: Card, material: str) -> set[str]:
    """Read the exact carried source cell, not the support block below it."""
    source = card.receipt_data.get(card.name + "-source")
    cell = card.receipt_data.get(card.name + "-source-cell")
    if not isinstance(source, dict) or not isinstance(cell, dict):
        return set()
    uuid = source.get("uuid")
    # The fixture's default anvil block state carries a facing property. Require
    # the exact native value observed for its unqualified setblock command.
    state = "Block{minecraft:" + material + "}" + ("[facing=north]" if material == "anvil" else "")
    if (not isinstance(uuid, str) or re.fullmatch(r"[0-9a-fA-F-]{36}", uuid) is None
            or source.get("type") != "minecraft:falling_block"
            or source.get("world") != "minecraft:overworld"
            or source.get("fallingState") != state
            or source.get("x") != 0.5 or source.get("z") != 0.5
            or not isinstance(source.get("y"), (int, float))
            or not 65 <= source["y"] <= 74
            or cell.get("world") != "minecraft:overworld"
            or cell.get("position") != "0 73 0"
            or cell.get("material") != "minecraft:" + material
            or cell.get("sourceUuid") != uuid
            or cell.get("sourceObject") != source.get("object")
            or cell.get("sourceFallingState") != state
            or cell.get("beforeCommand") != "execute in minecraft:overworld run execute if block 0 73 0 minecraft:" + material
            or cell.get("afterCommand") != "execute in minecraft:overworld run execute if block 0 73 0 minecraft:air"
            or not isinstance(cell.get("beforeResponse"), str)
            or "Test passed" not in cell["beforeResponse"]
            or not isinstance(cell.get("afterResponse"), str)
            or "Test passed" not in cell["afterResponse"]):
        return set()
    return {"source_block_removed"}


def falling_source_retired_assertions(card: Card) -> set[str]:
    """Bind native source removal to the same UUID and replacement admission."""
    source = card.receipt_data.get(card.name + "-source")
    arrival = card.receipt_data.get(card.name + "-arrival")
    observations = card.receipt_data.get("observations")
    if (not isinstance(source, dict) or not isinstance(arrival, dict)
            or not isinstance(observations, list)
            or source.get("type") != "minecraft:falling_block"
            or source.get("world") != "minecraft:overworld"
            or arrival.get("type") != "minecraft:falling_block"
            or arrival.get("world") != "minecraft:the_end"
            or source.get("uuid") != arrival.get("uuid")
            or not isinstance(source.get("uuid"), str)
            or source.get("object") == arrival.get("object")
            or source.get("nativeId") == arrival.get("nativeId")
            or source.get("fallingState") != arrival.get("fallingState")):
        return set()
    snapshots = {}
    for suffix in ("-arrival", "-settled"):
        matches = [row for row in observations if isinstance(row, dict)
                   and row.get("label") == card.name + suffix]
        if len(matches) != 1:
            return set()
        snapshots[suffix] = matches[0]
    for snapshot in snapshots.values():
        live = snapshot.get("entities")
        events = snapshot.get("fallingEvents")
        if not isinstance(live, list) or not isinstance(events, list):
            return set()
        if any(isinstance(entity, dict) and entity.get("uuid") == source["uuid"]
               and (entity.get("world") == source["world"]
                    or entity.get("object") == source.get("object")) for entity in live):
            return set()
        source_removals = [(index, event) for index, event in enumerate(events)
                           if isinstance(event, dict)
                           and event.get("uuid") == source["uuid"]
                           and event.get("object") == source.get("object")
                           and event.get("nativeId") == source.get("nativeId")
                           and event.get("world") == source["world"]
                           and event.get("type") == source["type"]
                           and event.get("phase") == "TRACKING_REMOVE_RETURN"
                           and event.get("removed") is True
                           and event.get("reason") == "CHANGED_DIMENSION"]
        destination_adds = [(index, event) for index, event in enumerate(events)
                            if isinstance(event, dict)
                            and event.get("uuid") == arrival["uuid"]
                            and event.get("object") == arrival.get("object")
                            and event.get("nativeId") == arrival.get("nativeId")
                            and event.get("world") == arrival["world"]
                            and event.get("type") == arrival["type"]
                            and event.get("phase") == "TRACKING_ADD_RETURN"]
        if (len(source_removals) != 1 or len(destination_adds) != 1
                or source_removals[0][0] >= destination_adds[0][0]):
            return set()
    return {"source_retired"}


def legacy_claims(card: Card, worktree: pathlib.Path, contracts: dict[str, str]) -> list[dict[str, Any]]:
    """Return reviewed legacy observations; no native outcome is inferred here."""
    name = card.name
    claims: list[dict[str, Any]] = []

    def add(family: str, entity: str, route: str, variant: str | None,
            assertions: set[str], basic: bool = True, scope: str = "row") -> None:
        if legacy_contract_ok(worktree, contracts, family):
            claims.append(dict(card=card, entityType=entity, route=route, variant=variant,
                               graph="standalone", observedOutcome="TRANSFER",
                               assertions=assertions, basicObservation=basic, scope=scope))

    match = MOB_CARD.fullmatch(name)
    negative = NO_NATIVE_ENTRY_CARD.fullmatch(name)
    if negative and legacy_contract_ok(worktree, contracts, "no_native_entry"):
        entity, route = negative.groups()
        entity_type = "minecraft:" + entity
        witnessed = negative_assertions(card, entity_type, route)
        if {"source_unchanged", "destination_absent", "portal_session_absent", "graph_unchanged"} <= witnessed:
            claims.append(dict(card=card, caseId="V2-" + entity.upper() + "-" + route,
                               entityType=entity_type, route=route, variant=None,
                               graph="standalone", observedOutcome="NO_NATIVE_ENTRY_PATH",
                               applicability=card.receipt_data.get(card.name + "-applicability"),
                               assertions=witnessed, basicObservation=True, scope="row"))
    if name == "G0-pig-chicken-NETHER_ENTRY" and legacy_contract_ok(worktree, contracts, "passenger_graph"):
        claims.append(dict(card=card, caseId="V2-GRAPH_mob-on-mob-NETHER_ENTRY",
                           entityType=None, route="NETHER_ENTRY", variant=None,
                           graph="mob riding mob", observedOutcome="TRANSFER",
                           assertions={"source_retired", "destination_unique", "tracking_unique",
                                        "uuid_preserved", "replacement_identity", "graph_nodes_exact",
                                        "graph_edges_exact", "no_dual_addressability", "post_arrival_tick",
                                        "client_tracking"}, basicObservation=True, scope="row"))
    if name == GATEWAY_PIG_CARD and legacy_contract_ok(worktree, contracts, "gateway_pig"):
        witnessed = gateway_pig_assertions(card)
        if REQUIRED_SAME_DIMENSION_ASSERTIONS | {"post_arrival_tick"} <= witnessed:
            claims.append(dict(card=card, caseId="V2-PIG-END_GATEWAY",
                               entityType="minecraft:pig", route="END_GATEWAY", variant=None,
                               graph="standalone", observedOutcome="SAME_DIMENSION_TRANSFER",
                               assertions=witnessed, basicObservation=True, scope="row",
                               sameDimensionGateway=True))
    if match:
        entity, size, source = match.groups()
        add("mob", "minecraft:" + entity, route_from_source(source),
            None if size == "1" else "size" + size,
            {"source_retired", "destination_unique", "tracking_unique", "post_arrival_tick",
             "uuid_preserved", "replacement_identity"})
    silverfish = SILVERFISH_CARD.fullmatch(name)
    if (silverfish and legacy_contract_ok(worktree, contracts, "mob")
            and silverfish_snapshot_witness(card, silverfish.group(1)) is not None):
        route = silverfish.group(1)
        claims.append(dict(card=card, caseId="V2-SILVERFISH-" + route,
                           entityType="minecraft:silverfish", route=route, variant=None,
                           graph="standalone", observedOutcome="TRANSFER",
                           assertions={"destination_unique", "post_arrival_tick",
                                       "uuid_preserved", "replacement_identity", "client_tracking"},
                           basicObservation=True, scope="row"))
    match = ARMOR_CARD.fullmatch(name)
    if match:
        entity, source, state = match.groups()
        add("armor", "minecraft:" + entity.replace("-", "_"), route_from_source(source),
            None if state == "empty" else state,
            {"source_retired", "destination_unique", "tracking_unique", "equipment_preserved",
             "replacement_identity"})
    match = EMPTY_VEHICLE_CARD.fullmatch(name)
    if match and legacy_contract_ok(worktree, contracts, "vehicle_empty"):
        entity, route = match.groups()
        entity_type = "minecraft:" + entity
        assertions = empty_vehicle_assertions(card, entity_type, route)
        if {"source_retired", "destination_unique", "tracking_unique",
            "vehicle_state_preserved", "post_arrival_tick"} <= assertions:
            claims.append(dict(card=card, caseId="V2-" + entity.upper() + "-" + route,
                               entityType=entity_type, route=route, variant=None,
                               graph="standalone", observedOutcome="TRANSFER",
                               assertions=assertions, basicObservation=True, scope="row"))
    match = CHEST_VEHICLE_CARD.fullmatch(name)
    if match and legacy_contract_ok(worktree, contracts, "vehicle_chest"):
        entity, storage, route = match.groups()
        if storage == "items" or entity in ("oak_chest_boat", "bamboo_chest_raft"):
            entity_type = "minecraft:" + entity
            assertions = chest_vehicle_assertions(card, entity_type, route, storage)
            if {"source_retired", "destination_unique", "tracking_unique",
                "vehicle_state_preserved", "post_arrival_tick"} <= assertions:
                claims.append(dict(card=card, caseId="V2-" + entity.upper() + "-" + route,
                                   entityType=entity_type, route=route, variant=None,
                                   graph="standalone", observedOutcome="TRANSFER",
                                   assertions=assertions, basicObservation=storage == "items",
                                   scope="row" if storage == "items" else "supporting"))
    match = CHEST_MINECART_CARD.fullmatch(name)
    if match and legacy_contract_ok(worktree, contracts, "minecart_chest"):
        storage, route = match.groups()
        entity_type = "minecraft:chest_minecart"
        assertions = chest_vehicle_assertions(card, entity_type, route, storage)
        if {"source_retired", "destination_unique", "tracking_unique",
            "vehicle_state_preserved", "post_arrival_tick"} <= assertions:
            claims.append(dict(card=card, caseId="V2-CHEST_MINECART-" + route,
                               entityType=entity_type, route=route, variant=None,
                               graph="standalone", observedOutcome="TRANSFER",
                               assertions=assertions, basicObservation=storage == "items",
                               scope="row" if storage == "items" else "supporting"))
    match = FALLING_CARD.fullmatch(name)
    if match:
        material = match.group(1)
        add("falling", "minecraft:falling_block", "END_ENTRY", "minecraft:" + material,
            {"support_removed", "destination_unique", "tracking_unique", "uuid_preserved",
             "replacement_identity", "carried_state", "end_platform", "native_landing"}
            | falling_source_cell_assertions(card, material))
    if name == "P0-fireball-nether-return":
        add("fireball", "minecraft:fireball", "NETHER_RETURN", None,
            {"source_retired", "destination_unique", "tracking_unique", "uuid_preserved",
             "replacement_identity", "post_arrival_tick", "client_tracking",
             "native_impact", "impact_explosion"})
    match = HURTING_PROJECTILE_CARD.fullmatch(name)
    if match:
        entity, route = match.groups()
        effects = {
            "fireball": {"post_arrival_tick", "impact_explosion"},
            "small_fireball": {"post_arrival_tick", "impact_fire_effect"},
            "dragon_fireball": {"post_arrival_tick", "dragon_breath_effect"},
            "wither_skull": {"post_arrival_tick", "wither_impact_effect"},
            "wind_charge": {"wind_impact_effect"},
            "breeze_wind_charge": {"wind_impact_effect"},
        }
        add("hurting_projectile", "minecraft:" + entity, route, None,
            {"source_retired", "destination_unique", "tracking_unique", "uuid_preserved",
             "replacement_identity", "client_tracking", "native_impact"} | effects[entity])
    match = THROWABLE_PROJECTILE_CARD.fullmatch(name)
    if match:
        entity, route = match.groups()
        effects = {
            "egg": "impact_effect",
            "snowball": "impact_effect",
            "splash_potion": "splash_effect",
            "lingering_potion": "lingering_cloud_effect",
            "experience_bottle": "xp_release",
        }
        add("throwable_projectile", "minecraft:" + entity, route, None,
            {"source_retired", "destination_unique", "tracking_unique", "uuid_preserved",
             "replacement_identity", "client_tracking", "post_arrival_tick", "native_impact", effects[entity]})
    match = ARROW_TRIDENT_CARD.fullmatch(name)
    if match:
        entity, route = match.groups()
        add("arrow_trident", "minecraft:" + entity, route, None,
            {"source_retired", "destination_unique", "tracking_unique", "uuid_preserved",
             "replacement_identity", "client_tracking", "post_arrival_tick",
             "projectile_owner_preserved", "loyalty_effect" if entity == "trident" else "impact_effect"})
    match = ITEM_CARD.fullmatch(name)
    if match:
        add("item", "minecraft:item", route_from_source(match.group(1)), None,
            {"source_retired", "destination_unique", "tracking_unique", "item_count_preserved", "native_pickup"})
    if name == "I3-2":
        add("item", "minecraft:item", "NETHER_ENTRY", None,
            {"source_retired", "destination_unique", "tracking_unique", "item_count_preserved", "native_pickup"})
    if name == "I3-3":
        add("item", "minecraft:item", "NETHER_RETURN", None,
            {"source_retired", "destination_unique", "tracking_unique", "item_count_preserved", "native_pickup"})
    if name == "I6":
        add("cold_item", "minecraft:item", "NETHER_ENTRY", "cold_destination",
            {"source_retired", "destination_unique", "tracking_unique", "item_count_preserved", "native_pickup", "cold_destination"})
    if name == "I1":
        for route in ("NETHER_ENTRY", "NETHER_RETURN"):
            add("player", "minecraft:player", route, None,
                {"player_world_changed", "client_playable"})
    match = PEARL_CARD.fullmatch(name)
    if match:
        # The old I5 fixture checks the later player-world change and hit, but
        # does not identify the projectile in both worlds. Keep it as support.
        add("pearl", "minecraft:ender_pearl", route_from_source(match.group(1)), None,
            {"pearl_hit", "player_world_changed", "pearl_retired"}, False, "supporting")
    return claims


# ---------------------------------------------------------------------------------------------------------------
# Server-headless evidence (scripts/stage7/headless_census.py, schema stage7-headless-receipt-v1).
#
# These runs have a real final-byte Phoenium server, the pinned probe/observer and an RCON driver, but NO client.
# They are therefore honest evidence only for assertions a server can witness (source/destination snapshots, owner
# phases, tracking admissions, post-arrival ticks). They can never witness client_tracking, client_playable or
# native_pickup, and every row they credit is labelled "server-headless" in its receipts.
# ---------------------------------------------------------------------------------------------------------------
HEADLESS_CARD = re.compile(r"E0-([a-z_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)")
GRAPH_CARD = re.compile(r"G0-([a-z0-9_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)")
DYNAMIC_CARD = re.compile(r"D0-falling-([a-z0-9_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)")
HEADLESS_CENSUS_SCRIPT = pathlib.Path("scripts/stage7/headless_census.py")
HEADLESS_GRAPH_SCRIPT = pathlib.Path("scripts/stage7/headless_graphs.py")
HEADLESS_DYNAMIC_SCRIPT = pathlib.Path("scripts/stage7/headless_dynamic.py")
EFFECTS_CARD = re.compile(r"D1-([a-z_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)")
HEADLESS_EFFECTS_SCRIPT = pathlib.Path("scripts/stage7/headless_effects.py")
HEADLESS_GATEWAY_TRIDENT_SCRIPT = pathlib.Path("scripts/stage7/headless_gateway_trident.py")
VARIANT_CARD = re.compile(
    r"Y0-(creaking)-(heart_bound|heart_unbound)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)")
HEADLESS_VARIANTS_SCRIPT = pathlib.Path("scripts/stage7/headless_variants.py")
# Where headless_variants.py stands each route's creaking_heart block (the heart-bound creaking's home_pos).
VARIANT_HEART_AT = {"NETHER_ENTRY": (6, 65, 6), "NETHER_RETURN": (6, 65, 6), "END_ENTRY": (5, 65, 5),
                    "END_EXIT": (5, 70, 5), "END_GATEWAY": (10, 65, 10)}
HEADLESS_GROUPS = {"headless-census": (HEADLESS_CENSUS_SCRIPT, HEADLESS_CARD),
                   "headless-variants": (HEADLESS_VARIANTS_SCRIPT, VARIANT_CARD),
                   "headless-graphs": (HEADLESS_GRAPH_SCRIPT, GRAPH_CARD),
                   "headless-dynamic": (HEADLESS_DYNAMIC_SCRIPT, DYNAMIC_CARD),
                   "headless-effects": (HEADLESS_EFFECTS_SCRIPT, EFFECTS_CARD),
                   "headless-gateway-trident": (HEADLESS_GATEWAY_TRIDENT_SCRIPT, EFFECTS_CARD)}
# Census directory: native-control receipt paths of the rows resolve against it (set by main / tests).
CONTROL_ROOT: pathlib.Path | None = None
# shape -> (root type, children); each child is again (type, children). Mirrors SHAPES in headless_graphs.py.
GRAPH_SHAPES = {
    "boat_empty": ("minecraft:oak_boat", ()),
    "boat_mob": ("minecraft:oak_boat", (("minecraft:villager", ()),)),
    "boat_two": ("minecraft:oak_boat", (("minecraft:villager", ()), ("minecraft:pig", ()))),
    "minecart_empty": ("minecraft:minecart", ()),
    "minecart_mob": ("minecraft:minecart", (("minecraft:pig", ()),)),
    "mob_on_mob": ("minecraft:pig", (("minecraft:chicken", ()),)),
    "nested_passenger": ("minecraft:pig", (("minecraft:chicken", (("minecraft:chicken", ()),)),)),
    "chest_boat_inventory_mob": ("minecraft:oak_chest_boat", (("minecraft:villager", ()),)),
    "chest_raft_inventory_two": ("minecraft:bamboo_chest_raft", (("minecraft:villager", ()), ("minecraft:pig", ()))),
    "furnace_minecart_rider": ("minecraft:furnace_minecart", (("minecraft:pig", ()),)),
    "hopper_minecart_inventory_rider": ("minecraft:hopper_minecart", (("minecraft:pig", ()),)),
    "command_block_minecart_rider": ("minecraft:command_block_minecart", (("minecraft:pig", ()),)),
    "nested_vehicle_mob": ("minecraft:oak_boat", (("minecraft:pig", (("minecraft:chicken", ()),)),)),
}
# ledger graph name -> headless shape (player-rider graph names are deliberately absent: they need the real client)
GRAPH_ROW_SHAPE = {
    "boat, no passengers": "boat_empty", "boat with mob rider": "boat_mob", "boat with two riders": "boat_two",
    "minecart, no passengers": "minecart_empty", "minecart with mob rider": "minecart_mob",
    "mob riding mob": "mob_on_mob", "nested passenger graph": "nested_passenger",
    "chest boat with inventory and mob": "chest_boat_inventory_mob",
    "chest raft with inventory and two riders": "chest_raft_inventory_two",
    "furnace minecart with rider": "furnace_minecart_rider",
    "hopper minecart with inventory and rider": "hopper_minecart_inventory_rider",
    "command block minecart with rider": "command_block_minecart_rider",
    "nested vehicle and mob graph": "nested_vehicle_mob",
}
GRAPH_SHAPE_ROW = {shape: name for name, shape in GRAPH_ROW_SHAPE.items()}
# Real-client (K1) cards: the connected PhoeniumTest0 session is the entity, or the rider of the graph (client_closeout.py).
CLIENT_CLOSEOUT_SCRIPT = pathlib.Path("scripts/stage7/client_closeout.py")
CLIENT_CARD = re.compile(r"K1-([a-z_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)")
PLAYER_GRAPH_KIND = {
    "boat with player rider": "pboat", "minecart with player rider": "pminecart", "player riding mob": "pmob",
    "bamboo raft with player": "praft", "player riding camel with second passenger": "pcamel",
    "chest boat with player and second passenger": "pchestboat",
    "boat with player rider credits unseen": "cboat", "minecart with player rider credits unseen": "cminecart",
    "mountable mob with player rider credits unseen": "ccamel",
}
PLAYER_GRAPH_KIND_ROW = {kind: name for name, kind in PLAYER_GRAPH_KIND.items()}
CLIENT_STANDALONE_KIND = {
    ("minecraft:player", None): "player", ("minecraft:player", "credits_unseen"): "credits_unseen",
    ("minecraft:player", "portal_cooldown"): "player_cooldown", ("minecraft:ender_pearl", None): "ender_pearl",
    ("minecraft:ender_pearl", "passenger_override"): "ender_pearl_override", ("minecraft:item", None): "item",
    ("minecraft:fishing_bobber", None): "fishing_bobber",
}


HEADLESS_GROUPS["client-closeout"] = (CLIENT_CLOSEOUT_SCRIPT, CLIENT_CARD)


def k1_kind_of_row(row: dict[str, Any]) -> str | None:
    """The real-client card kind that observes this ledger row, or None when the row needs no connected client."""
    graph = row.get("graph") or "standalone"
    if graph != "standalone":
        return PLAYER_GRAPH_KIND.get(graph)
    return CLIENT_STANDALONE_KIND.get((row.get("entityType"), row.get("variant") or None))

HEADLESS_OUTCOMES = {"TRANSFERRED", "STAYED", "GONE", "FIXTURE_INVALID"}
HEADLESS_PHASES = ("SOURCE_RETIRING", "SOURCE_RETIRED", "ADD_DURING_TELEPORT_HEAD", "CHUNK_MAP_TRACKING_HEAD",
                   "CHUNK_MAP_TRACKING_RETURN", "ADD_DURING_TELEPORT_RETURN")
# Types whose rows need family-specific semantics (pickup, landing, fuse, impact, vehicle state, inventory);
# the generic server-headless contract below never credits them.
HEADLESS_SPECIAL_TYPES = {"minecraft:player", "minecraft:item", "minecraft:falling_block", "minecraft:tnt",
                          "minecraft:fishing_bobber", *PROJECTILE_REQUIREMENTS}


def headless_family(entity_type: str) -> str | None:
    """'plain' needs only the generic witnesses; 'vehicle' adds state/inventory; anything else has no headless contract."""
    name = entity_type.rsplit(":", 1)[-1]
    if "boat" in name or "raft" in name or "minecart" in name:
        return "vehicle"
    if entity_type in HEADLESS_SPECIAL_TYPES:
        return None
    return "plain"


def is_chest_vehicle(entity_type: str) -> bool:
    name = entity_type.rsplit(":", 1)[-1]
    return "chest_boat" in name or "chest_raft" in name or name == "chest_minecart"


def headless_vehicle_extras(card: Card, entity_type: str) -> set[str]:
    """vehicle_state_preserved / inventory_preserved / no_loose_item_duplication from the raw checkpoints.

    The source checkpoint is optional (an immediate End-exit transfer can finish before the first RCON snapshot); when it
    exists it must agree. The harness itself fills chest vehicles with the fixed stacks below, so arrival and later are
    compared with that known content rather than with the receipt's own claim.
    """
    witness = card.data.get("witness")
    if not isinstance(witness, dict):
        return set()
    arrival, later = _single(witness.get("arrival")), _single(witness.get("later"))
    source_list = witness.get("source")
    source = _single(source_list) if source_list else None
    if arrival is None or later is None or (source_list and source is None):
        return set()
    stages = [part for part in (source, arrival, later) if part is not None]
    extras: set[str] = set()
    if (all(part.get("vehicleUuid") == "UNAVAILABLE" and part.get("passengerUuids") == [] for part in stages)
            and len({part.get("tags") for part in stages}) == 1):
        extras.add("vehicle_state_preserved")
    if is_chest_vehicle(entity_type):
        minecart = entity_type == "minecraft:chest_minecart"
        second_item, second_count = ("minecraft:iron_ingot", 13) if minecart else ("minecraft:sugar_cane", 3)
        expected = [{"slot": 0, "item": "minecraft:diamond", "count": 7},
                    {"slot": 26, "item": second_item, "count": second_count}]
        if all(part.get("vehicleSlotCount") == 27 and part.get("vehicleItems") == expected
               and part.get("vehicleLootTable") == "UNAVAILABLE" and part.get("vehicleLootSeed") == 0 for part in stages):
            extras.add("inventory_preserved")
        loose = [witness.get(key) for key in ("looseSource", "looseArrival", "looseLater")
                 if witness.get(key) is not None]
        if len(loose) >= 2 and all(value == {} for value in loose):
            extras.add("no_loose_item_duplication")
    return extras


def _snapshot_uuid(card_data: dict[str, Any]) -> str | None:
    witness = card_data.get("witness")
    if not isinstance(witness, dict):
        return None
    for key in ("arrival", "settled", "source"):
        members = witness.get(key)
        if isinstance(members, list) and len(members) == 1 and isinstance(members[0], dict):
            found = members[0].get("uuid")
            if isinstance(found, str) and re.fullmatch(r"[0-9a-fA-F-]{36}", found):
                return found.lower()
    return None


def scan_headless(roots: list[pathlib.Path], candidate_sha: str, worktree: pathlib.Path
                  ) -> tuple[list[Card], list[dict[str, str]]]:
    """Read headless census runs and return one Card per recorded census card."""
    cards: list[Card] = []
    rejected: list[dict[str, str]] = []
    script_shas = {}
    for group, (script_path, _pattern) in HEADLESS_GROUPS.items():
        script = worktree.joinpath(*script_path.parts)
        script_shas[group] = digest(script) if script.is_file() else ""
    for root in roots:
        for result_path in sorted(root.glob("*/headless-result.json")):
            receipt_path = result_path.with_name("headless-receipt.json")
            inputs_path = result_path.with_name("inputs.json")
            reason = None
            try:
                result, receipt, inputs = (read_json(path) for path in (result_path, receipt_path, inputs_path))
                if not all(isinstance(doc, dict) for doc in (result, receipt, inputs)):
                    reason = "malformed result, receipt, or inputs"
                elif inputs.get("vanilla") is True:
                    continue  # a Phoenium-free native control, consumed by classify_census.py
                elif any(str(doc.get("candidateSha256", "")).upper() != candidate_sha for doc in (result, receipt, inputs)):
                    reason = "candidate hash mismatch"
                elif str(result.get("installedCandidateSha256", "")).upper() != candidate_sha:
                    reason = "installed candidate hash mismatch"
                elif result.get("phase") != ("client-closeout" if inputs.get("group") == "client-closeout" else "headless") \
                        or inputs.get("group") not in HEADLESS_GROUPS or receipt.get("group") != inputs.get("group"):
                    reason = "not a headless census run"
                elif (inputs.get("evidence") != receipt.get("evidence")
                      or inputs.get("evidence") != ("real-client" if inputs.get("group") == "client-closeout" else "server-headless")
                      or inputs.get("headless") is not (inputs.get("group") != "client-closeout")):
                    reason = "headless evidence label missing"
                elif not (result.get("pass") is True and receipt.get("pass") is True and result.get("txPass") is True
                          and not result.get("logFailure") and result.get("normalSave") is True
                          and result.get("normalStopRequested") is True and result.get("serverExitCode") == 0
                          and not result.get("forcedOwnServerStop")):
                    reason = "phase did not pass and save/stop cleanly"
                elif any(result.get(key) for key in ("sourceWorldChanges", "testSourceChanged", "configChanges")):
                    reason = "source world, test source or config changed during run"
                elif inputs.get("workers") not in (1, 4):
                    reason = "unsupported worker count"
                elif (not script_shas[inputs["group"]]
                      or str(inputs.get("censusScriptSha256", "")).upper() != script_shas[inputs["group"]]):
                    reason = "headless census script identity differs from installed run"
                elif not _test_sources_match(worktree, inputs.get("testSourceHashes")):
                    reason = "test source identity differs from installed run"
                else:
                    installed_path = pathlib.Path(inputs.get("candidate", ""))
                    entries = receipt.get("cards")
                    if not installed_path.is_file() or digest(installed_path) != candidate_sha:
                        reason = "installed candidate copy differs from final candidate"
                    elif (not isinstance(entries, list) or not all(isinstance(entry, dict) for entry in entries)
                          or [entry.get("card") for entry in entries] != inputs.get("cards")
                          or len({entry.get("card") for entry in entries}) != len(entries)):
                        reason = "receipt cards differ from requested cards"
                    else:
                        pattern = HEADLESS_GROUPS[inputs["group"]][1]
                        for entry in entries:
                            if (entry.get("outcome") in HEADLESS_OUTCOMES | {"PARTIAL"} and isinstance(entry.get("card"), str)
                                    and pattern.fullmatch(entry["card"])):
                                data = dict(entry)
                                found = _snapshot_uuid(entry)
                                if found:
                                    data["details"] = "UUID=" + found
                                cards.append(Card(entry["card"], inputs["workers"], "headless", receipt_path,
                                                  result_path, inputs_path, data, result, receipt, inputs,
                                                  inputs["evidence"]))
            except (OSError, ValueError, TypeError, KeyError) as error:
                reason = f"unreadable evidence: {error}"
            if reason:
                rejected.append({"path": str(result_path), "reason": reason})
    return cards, rejected


def headless_generation(card: Card, uuid: str, entity_type: str, route: str) -> list[dict[str, Any]] | None:
    """Events of the first complete transaction generation for this exact entity, or None."""
    result = card.result_data
    status = result.get("txStatus")
    trace_value = result.get("txTracePath")
    if not isinstance(status, dict) or not isinstance(trace_value, str):
        return None
    trace_path = pathlib.Path(trace_value)
    if (not trace_path.is_file() or trace_path.resolve().parent != card.result_path.resolve().parent
            or digest(trace_path) != result.get("txTraceSha256")):
        return None
    candidate_sha, probe_sha = result.get("candidateSha256"), status.get("testSourceSha256")
    if probe_sha != card.inputs_data.get("probeSha256") or not valid_sha(probe_sha):
        return None
    by_generation: dict[int, list[dict[str, Any]]] = collections.defaultdict(list)
    for event in _trace_events(trace_path):
        if (str(event.get("uuid", "")).lower() == uuid
                and trace_entity_type(event.get("type")) == entity_type and event.get("route") == route
                and event.get("candidateSha256") == candidate_sha and event.get("testSourceSha256") == probe_sha
                and isinstance(event.get("generation"), int) and event["generation"] > 0):
            by_generation[event["generation"]].append(event)
    complete = [by_generation[generation] for generation in sorted(by_generation)
                if all(any(event.get("phase") == phase for event in by_generation[generation])
                       for phase in HEADLESS_PHASES)]
    return complete[0] if complete else None


def headless_transfer_assertions(card: Card, entity_type: str, route: str) -> set[str]:
    """Derive server-witnessable transfer assertions from raw snapshots and the full transaction trace."""
    witness = card.data.get("witness")
    uuid = _snapshot_uuid(card.data)
    if card.data.get("outcome") != "TRANSFERRED" or not isinstance(witness, dict) or uuid is None:
        return set()
    source_world, destination = ROUTE_SOURCE_WORLD[route], ROUTE_DESTINATION_WORLD[route]
    arrival_all, later_all = witness.get("arrival"), witness.get("later")
    if (not isinstance(arrival_all, list) or not isinstance(later_all, list)
            or len(arrival_all) != 1 or len(later_all) != 1
            or not all(isinstance(member, dict) for member in (*arrival_all, *later_all))):
        return set()
    arrival, later = arrival_all[0], later_all[0]
    if (str(arrival.get("uuid", "")).lower() != uuid or str(later.get("uuid", "")).lower() != uuid
            or arrival.get("type") != entity_type or later.get("type") != entity_type
            or arrival.get("world") != destination or later.get("world") != destination
            or arrival.get("removed") is not False or later.get("removed") is not False):
        return set()
    assertions = {"destination_unique"}
    if (arrival.get("object") == later.get("object") and arrival.get("nativeId") == later.get("nativeId")
            and isinstance(arrival.get("ticks"), int) and isinstance(later.get("ticks"), int)
            and later["ticks"] > arrival["ticks"] + 20):
        assertions.add("post_arrival_tick")
    events = headless_generation(card, uuid, entity_type, route)
    if events is None:
        return assertions
    first = {phase: next(event for event in events if event.get("phase") == phase) for phase in HEADLESS_PHASES}
    if any(event.get("violation") for event in events):
        return assertions
    retiring, retired, head = first["SOURCE_RETIRING"], first["SOURCE_RETIRED"], first["ADD_DURING_TELEPORT_HEAD"]
    if (retiring.get("sourceWorld") != source_world or retired.get("ownerWorld") != source_world
            or head.get("destinationWorld") != destination
            or head.get("object") != arrival.get("object") or head.get("nativeId") != arrival.get("nativeId")):
        return assertions
    # The tagged entity is unique in the whole server, so a single arrival member also proves the source vanished.
    assertions.add("source_retired")
    assertions.add("uuid_preserved")
    if retiring.get("object") != head.get("object") and retiring.get("nativeId") != head.get("nativeId"):
        assertions.add("replacement_identity")
    tracking = [event for event in events if event.get("object") == head.get("object")
                and event.get("phase") in ("CHUNK_MAP_TRACKING_HEAD", "CHUNK_MAP_TRACKING_RETURN")]
    if ([event["phase"] for event in tracking].count("CHUNK_MAP_TRACKING_HEAD") == 1
            and [event["phase"] for event in tracking].count("CHUNK_MAP_TRACKING_RETURN") == 1):
        assertions.add("tracking_unique")
    return assertions


# Portal block cell of the headless fixture per route (the entity is summoned inside this exact cell).
HEADLESS_PORTAL_CELL = {"NETHER_ENTRY": (0, 65, 0), "NETHER_RETURN": (0, 65, 0), "END_ENTRY": (8, 65, 8),
                        "END_EXIT": (8, 70, 8), "END_GATEWAY": (12, 66, 8)}
HEADLESS_MIN_DWELL_TICKS = 40
# Self-discarding entities and the least dwell (ticks alive in the portal cell) that still proves they were processed:
# an evoker fang lives ~20 ticks, a lightning bolt a random 3-30 (the census polls every 0.25 s, so the last snapshot can be early).
HEADLESS_SHORT_DWELL_TICKS = {"minecraft:lightning_bolt": 2, "minecraft:evoker_fangs": 8}
HEADLESS_SHORT_LIVED = set(HEADLESS_SHORT_DWELL_TICKS)
HEADLESS_CONTACT_TYPES = {"minecraft:wither", "minecraft:fishing_bobber"}         # canUsePortal(false) rejects it AFTER the portal block visited it


def _single(members: Any) -> dict[str, Any] | None:
    return members[0] if (isinstance(members, list) and len(members) == 1 and isinstance(members[0], dict)) else None


def creaking_variant_state_ok(card: Card, route: str, variant: str) -> bool:
    """The constructed state, read back from the server: a heart-bound creaking's home_pos names a creaking_heart block
    entity whose protector UUID is exactly this creaking (the native Creaking.isHeartBound()/isProtector relationship);
    an unbound one carries no home_pos at all."""
    witness = card.data.get("witness")
    if not isinstance(witness, dict) or witness.get("variant") != variant:
        return False
    source_nbt = witness.get("nbtSource")
    if not isinstance(source_nbt, str):
        return False
    home = re.search(r"home_pos: \[I; (-?\d+), (-?\d+), (-?\d+)\]", source_nbt)
    if variant == "heart_unbound":
        return home is None and "heartBlock" not in witness
    uuid_ints = re.search(r"UUID: \[I; (-?\d+), (-?\d+), (-?\d+), (-?\d+)\]", source_nbt)
    heart = witness.get("heartBlock")
    if home is None or uuid_ints is None or not isinstance(heart, str):
        return False
    heart_pos = VARIANT_HEART_AT[route]
    protector = re.search(r"creaking: \[I; (-?\d+), (-?\d+), (-?\d+), (-?\d+)\]", heart)
    return (tuple(int(value) for value in home.groups()) == heart_pos
            and f"{heart_pos[0]}, {heart_pos[1]}, {heart_pos[2]} has the following block data" in heart
            and 'id: "minecraft:creaking_heart"' in heart
            and protector is not None and protector.groups() == uuid_ints.groups())


def headless_negative_assertions(card: Card, entity_type: str, route: str, variant: str | None = None) -> set[str]:
    """Server-side negative witness: the entity sat in the portal cell, was never visited/processed, and never left."""
    witness = card.data.get("witness")
    uuid = _snapshot_uuid(card.data)
    if card.data.get("outcome") != "STAYED" or not isinstance(witness, dict) or uuid is None:
        return set()
    source, settled = _single(witness.get("source")), _single(witness.get("settled"))
    if source is None or settled is None:
        return set()
    source_world = ROUTE_SOURCE_WORLD[route]
    if any(str(part.get("uuid", "")).lower() != uuid or part.get("type") != entity_type
           or part.get("world") != source_world or part.get("removed") is not False
           or part.get("portalProcessPresent") is not False for part in (source, settled)):
        return set()
    if (source.get("object") != settled.get("object") or source.get("nativeId") != settled.get("nativeId")
            or not isinstance(source.get("ticks"), int) or not isinstance(settled.get("ticks"), int)):
        return set()
    dwell = HEADLESS_SHORT_DWELL_TICKS.get(entity_type, HEADLESS_MIN_DWELL_TICKS)
    if settled["ticks"] < dwell or settled["ticks"] <= source["ticks"]:
        return set()
    cell = HEADLESS_PORTAL_CELL[route]
    for part in (source, settled):
        if any(not isinstance(part.get(axis), (int, float)) for axis in ("x", "y", "z")):
            return set()
    if (tuple(int(source[axis] // 1) for axis in ("x", "y", "z")) != cell
            or tuple(int(settled[axis] // 1) for axis in ("x", "y", "z")) != cell):
        return set()
    # The tagged entity is unique in the whole server, so one settled member also proves the destination stayed empty.
    assertions = {"source_unchanged", "destination_absent"}
    if (settled.get("vehicleUuid") == source.get("vehicleUuid") and settled.get("passengerUuids") == source.get("passengerUuids")
            and "vehicleUuid" in source and "passengerUuids" in source):
        assertions.add("graph_unchanged")
    contacts = witness.get("noEntryContactEvents")
    if not isinstance(contacts, list):
        return assertions
    mine = [event for event in contacts if isinstance(event, dict) and str(event.get("uuid", "")).lower() == uuid]
    # canUsePortal(false) rejects the entity AFTER the portal block visited it: the wither, and a heart-bound creaking.
    expected_contact = entity_type in HEADLESS_CONTACT_TYPES or variant == "heart_bound"
    visited = any(event.get("type") == entity_type and event.get("world") == source_world
                  and event.get("block") == ROUTE_PORTAL_BLOCK[route]
                  and (event.get("x"), event.get("y"), event.get("z")) == cell for event in mine)
    if expected_contact != visited or (not expected_contact and mine):
        return assertions
    result = card.result_data
    status = result.get("txStatus")
    trace_value = result.get("txTracePath")
    if (result.get("txPass") is not True or not isinstance(status, dict) or status.get("pending") != 0
            or status.get("violations") != 0 or status.get("candidateSha256") != result.get("candidateSha256")
            or status.get("testSourceSha256") != card.inputs_data.get("probeSha256")
            or not isinstance(trace_value, str)):
        return assertions
    trace_path = pathlib.Path(trace_value)
    if (not trace_path.is_file() or trace_path.resolve().parent != card.result_path.resolve().parent
            or digest(trace_path) != result.get("txTraceSha256")):
        return assertions
    if any(str(event.get("uuid", "")).lower() == uuid for event in _trace_events(trace_path)):
        return assertions
    assertions.add("portal_session_absent")
    return assertions


def headless_discard_assertions(card: Card, entity_type: str, route: str) -> set[str]:
    """A native self-discard inside the portal: alive in the portal cell, then absent everywhere, with no portal activity."""
    witness = card.data.get("witness")
    uuid = _snapshot_uuid(card.data)
    if card.data.get("outcome") != "GONE" or not isinstance(witness, dict) or uuid is None:
        return set()
    source = _single(witness.get("source"))
    if (source is None or witness.get("afterWindow") != [] or witness.get("settled") or witness.get("arrival")
            or str(source.get("uuid", "")).lower() != uuid or source.get("type") != entity_type
            or source.get("world") != ROUTE_SOURCE_WORLD[route] or source.get("removed") is not False
            or any(not isinstance(source.get(axis), (int, float)) for axis in ("x", "y", "z"))
            or tuple(int(source[axis] // 1) for axis in ("x", "y", "z")) != HEADLESS_PORTAL_CELL[route]):
        return set()
    result = card.result_data
    status = result.get("txStatus")
    trace_value = result.get("txTracePath")
    if (result.get("txPass") is not True or not isinstance(status, dict) or status.get("pending") != 0
            or status.get("violations") != 0 or not isinstance(trace_value, str)):
        return set()
    trace_path = pathlib.Path(trace_value)
    if (not trace_path.is_file() or trace_path.resolve().parent != card.result_path.resolve().parent
            or digest(trace_path) != result.get("txTraceSha256")
            or any(str(event.get("uuid", "")).lower() == uuid for event in _trace_events(trace_path))):
        return set()
    return {"destination_absent", "portal_session_absent", "native_discard"}


def _region(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    match = re.search(r"(?:region|owner)=(RegionId\[world=WorldId\[value=[^\]]+\], value=[^\]]+\])", value)
    return match.group(1) if match else None


def headless_gateway_assertions(card: Card, entity_type: str, falling: bool = False, later_optional: bool = False,
                                ends_on_impact: bool = False, rest_source: bool = False) -> set[str]:
    """Same-dimension End gateway witness from raw snapshots plus the probe's contact/ticket/tracking records.

    `ends_on_impact` is the same relaxation for an effect projectile that ends itself on its target.
    `falling` is for a falling block: it is summoned above the gateway (the contact event proves it entered the gateway cell),
    and it lands and discards itself soon after arriving, so only the source and arrival snapshots can exist.
    """
    witness = card.data.get("witness")
    uuid = _snapshot_uuid(card.data)
    if card.data.get("outcome") != "TRANSFERRED" or not isinstance(witness, dict) or uuid is None:
        return set()
    source, arrival, later = (_single(witness.get(key)) for key in ("source", "arrival", "later"))
    gateway = witness.get("gateway")
    if source is None or arrival is None or (later is None and not (falling or later_optional)) or not isinstance(gateway, dict):
        return set()
    end = "minecraft:the_end"
    parts = (source, arrival) if later is None else (source, arrival, later)
    if any(str(part.get("uuid", "")).lower() != uuid or part.get("type") != entity_type or part.get("world") != end
           or part.get("removed") is not False for part in parts):
        return set()
    if not (isinstance(source.get("object"), str) and all(part.get("object") == source["object"] for part in parts)
            and isinstance(source.get("nativeId"), int) and all(part.get("nativeId") == source["nativeId"] for part in parts)):
        return set()
    # `rest_source`: the real-client harness observes the entity at rest beside the gateway and then carries it into the cell;
    # the contact record (checked below) proves it was in the gateway cell, and "vacated" is measured from that cell.
    if (not falling and not rest_source
            and tuple(int(source[axis] // 1) for axis in ("x", "y", "z")) != HEADLESS_PORTAL_CELL["END_GATEWAY"]):
        return set()
    assertions = {"same_native_object", "same_dimension", "destination_unique"}
    origin = (HEADLESS_PORTAL_CELL["END_GATEWAY"][0] + .5, HEADLESS_PORTAL_CELL["END_GATEWAY"][2] + .5) if rest_source \
        else (source.get("x"), source.get("z"))
    if all(isinstance(arrival.get(axis), (int, float)) for axis in ("x", "z")) and \
            ((arrival["x"] - origin[0]) ** 2 + (arrival["z"] - origin[1]) ** 2) > 8.0 ** 2:
        assertions.add("source_location_vacated")
    if (later is not None and isinstance(arrival.get("ticks"), int) and isinstance(later.get("ticks"), int)
            and later["ticks"] > arrival["ticks"] + 20
            and later.get("chunkX") == arrival.get("chunkX") and later.get("chunkZ") == arrival.get("chunkZ")):
        assertions.add("post_arrival_tick")
    # The native gateway teleport sets a portal cooldown on the teleported entity (a vehicle's is short, so only the value
    # right after arrival is required to be positive).
    if isinstance(arrival.get("portalCooldown"), int) and arrival["portalCooldown"] > 0:
        assertions.add("gateway_cooldown")
    # A retained vehicle may visit the gateway block on more than one tick before the teleport settles: every record must
    # agree, and at least one must exist.
    contacts = [event for event in gateway.get("contact", []) if isinstance(event, dict)
                and str(event.get("uuid", "")).lower() == uuid]
    tickets = [event for event in gateway.get("ticket", []) if isinstance(event, dict)
               and str(event.get("uuid", "")).lower() == uuid]
    every_tracking = [event for event in gateway.get("tracking", []) if isinstance(event, dict)
                      and str(event.get("uuid", "")).lower() == uuid]
    source_region, destination_region = _region(source.get("ownershipDiagnostic")), _region(arrival.get("ownershipDiagnostic"))
    if not contacts or not tickets or not every_tracking or source_region is None or destination_region is None:
        return assertions
    arrival_chunk = (arrival.get("chunkX"), arrival.get("chunkZ"))
    destination_updates = [event for event in every_tracking if event.get("phase") == "TRACKING_UPDATE_RETURN"
                           and (event.get("chunkX"), event.get("chunkZ")) == arrival_chunk]
    if all(contact.get("world") == end
           and (contact.get("x"), contact.get("y"), contact.get("z")) == HEADLESS_PORTAL_CELL["END_GATEWAY"]
           and contact.get("ownerWorld") == end and contact.get("ownerContext") not in (None, "NONE")
           and _region(contact.get("ownerContext")) == source_region for contact in contacts):
        assertions.add("source_owner")
    if all(ticket.get("world") == end and ticket.get("ticketType") == "minecraft:portal"
           and (ticket.get("chunkX"), ticket.get("chunkZ")) == arrival_chunk
           and ticket.get("ownerWorld") == end and ticket.get("ownerContext") not in (None, "NONE")
           and _region(ticket.get("ownerContext")) == destination_region for ticket in tickets):
        assertions.add("gateway_ticket")
        # A projectile's native dimension-changing delay is 2 ticks (Projectile.getDimensionChangingDelay), so by the first
        # read-back of a pearl that has already travelled through the gateway the cooldown set by the teleport has run out:
        # the cooldown is credited from the contact+ticket records and the native delay, not from a positive reading.
        if (ends_on_impact and "gateway_cooldown" not in assertions and arrival.get("portalCooldown") == 0
                and isinstance(arrival.get("ticks"), int) and arrival["ticks"] > 2 and "source_location_vacated" in assertions):
            assertions.add("gateway_cooldown")
    # Same native object: tracking may only ever UPDATE it (no add/remove pair, which would mean a replacement).
    # The spawn itself is tracked once (ADD in the source chunk); an ADD in the destination chunk or any REMOVE would mean
    # a replacement object.
    if (destination_updates and not any(
            event.get("phase") == "TRACKING_REMOVE_RETURN"
            or (event.get("phase") == "TRACKING_ADD_RETURN" and (event.get("chunkX"), event.get("chunkZ")) == arrival_chunk)
            for event in every_tracking)
            and all(event.get("object") == source["object"] and event.get("nativeId") == source["nativeId"]
                    and event.get("world") == end and event.get("ownerWorld") == end
                    and event.get("ownerContext") not in (None, "NONE")
                    and _region(event.get("ownerContext")) == destination_region for event in destination_updates)):
        assertions.update(("tracking_unique", "tracking_owner"))
    elif ((falling or ends_on_impact) and not destination_updates and not any(
            event.get("phase") == "TRACKING_REMOVE_RETURN"
            or (event.get("phase") == "TRACKING_ADD_RETURN" and (event.get("chunkX"), event.get("chunkZ")) == arrival_chunk)
            for event in every_tracking)
            and all(event.get("object") == source["object"] and event.get("nativeId") == source["nativeId"]
                    for event in every_tracking)):
        # A falling block lands (and an effect projectile hits its target) within a tick or two of the teleport, so the destination chunk's tracker
        # may never get an update before it ends. Everything the tracker did say is about the one native object, with no
        # replacement; destination tracking ownership stays unproven (no tracking_owner).
        assertions.add("tracking_unique")
    if {"source_owner", "gateway_ticket", "tracking_owner"} <= assertions:
        assertions.update(("destination_owner", "observer_phases"))
    elif (ends_on_impact and later is not None and {"source_owner", "gateway_ticket", "post_arrival_tick", "tracking_unique"} <= assertions
          and _region(later.get("ownershipDiagnostic")) == destination_region):
        # The tracker never updated the destination chunk (the effect projectile settles at once), but the live entity itself
        # was read back owned by the destination region after it had ticked there for 20+ ticks. tracking_owner stays unproven.
        assertions.add("destination_owner")
    return assertions


def _expected_tree(node: tuple) -> tuple:
    kind, children = node
    return (kind, tuple(sorted(_expected_tree(child) for child in children)))


def _snapshot_tree(members: list[dict[str, Any]]) -> tuple | None:
    """Canonical (type, sorted children) tree from vehicle/passenger edges; None unless a single consistent tree."""
    by_uuid = {str(member.get("uuid", "")).lower(): member for member in members}
    if len(by_uuid) != len(members) or "" in by_uuid:
        return None
    roots = [member for member in members if member.get("vehicleUuid") in (None, "UNAVAILABLE")]
    if len(roots) != 1:
        return None
    seen: set[str] = set()

    def build(member: dict[str, Any]) -> tuple:
        uuid = str(member["uuid"]).lower()
        if uuid in seen:
            raise ValueError("cycle")
        seen.add(uuid)
        kids = []
        for passenger in member.get("passengerUuids", []):
            child = by_uuid.get(str(passenger).lower())
            if child is None or str(child.get("vehicleUuid", "")).lower() != uuid:
                raise ValueError("edge mismatch")
            kids.append(build(child))
        return (member["type"], tuple(sorted(kids)))

    try:
        tree = build(roots[0])
    except (ValueError, KeyError):
        return None
    return tree if len(seen) == len(members) else None


def headless_graph_assertions(card: Card, shape: str, route: str) -> tuple[set[str], bool]:
    """Assertions for one non-player passenger graph, and whether every node's owner phases were validated."""
    witness = card.data.get("witness")
    shapes = {**GRAPH_SHAPES, **PGRAPH_SHAPES}
    if card.data.get("outcome") != "TRANSFERRED" or not isinstance(witness, dict) or shape not in shapes:
        return set(), False
    expected = _expected_tree(shapes[shape])

    def count(tree: tuple) -> int:
        return 1 + sum(count(child) for child in tree[1])

    total = count(expected)
    arrival, later = witness.get("arrival"), witness.get("later")
    if (not isinstance(arrival, list) or not isinstance(later, list) or len(arrival) != total or len(later) != total
            or not all(isinstance(member, dict) for member in (*arrival, *later))
            or witness.get("counts", {}).get("total") != total):
        return set(), False
    destination = ROUTE_DESTINATION_WORLD[route]
    if any(member.get("world") != destination or member.get("removed") is not False for member in (*arrival, *later)):
        return set(), False
    arrival_tree, later_tree = _snapshot_tree(arrival), _snapshot_tree(later)
    assertions: set[str] = set()
    if arrival_tree == expected:
        assertions.add("graph_nodes_exact")
        assertions.add("graph_edges_exact")
    if arrival_tree is None or later_tree != arrival_tree:
        return set(), False
    # One tagged copy of every node in the whole server, all in the destination world: nothing addressable twice.
    assertions |= {"no_dual_addressability", "destination_unique"}
    later_by_uuid = {str(member["uuid"]).lower(): member for member in later}
    ticks_ok = True
    for member in arrival:
        other = later_by_uuid.get(str(member["uuid"]).lower())
        if (other is None or other.get("object") != member.get("object") or other.get("nativeId") != member.get("nativeId")
                or not isinstance(member.get("ticks"), int) or not isinstance(other.get("ticks"), int)
                or other["ticks"] <= member["ticks"] + 20):
            ticks_ok = False
    if ticks_ok:
        assertions.add("post_arrival_tick")
    if route == "END_GATEWAY":
        return _headless_graph_gateway(card, shape, witness, arrival, later, assertions)
    owners = True
    for member in arrival:
        uuid = str(member["uuid"]).lower()
        if member["type"] == "minecraft:player":
            if card.evidence == "real-client" and not client_player_generation_ok(card, member, route):
                # A player riding the vehicle is carried by the vehicle's native transfer (ServerPlayer.teleport of the ejected rider),
                # so the vehicle's generation is the player's transaction.
                if not client_rider_ok(card, member, route, arrival):
                    return assertions, False
                continue
            if not client_player_generation_ok(card, member, route):
                return assertions, False
            if not OWNER_ASSERTIONS <= observer_assertions(card, member["type"], route, uuid,
                                                           window=card.data.get("txRange")):
                owners = False
            continue
        events = headless_generation(card, uuid, member["type"], route)
        if events is None or any(event.get("violation") for event in events):
            return assertions, False
        first = {phase: next(event for event in events if event.get("phase") == phase) for phase in HEADLESS_PHASES}
        retiring, retired, head = first["SOURCE_RETIRING"], first["SOURCE_RETIRED"], first["ADD_DURING_TELEPORT_HEAD"]
        if (retiring.get("sourceWorld") != ROUTE_SOURCE_WORLD[route] or retired.get("ownerWorld") != ROUTE_SOURCE_WORLD[route]
                or head.get("destinationWorld") != destination or head.get("object") != member.get("object")
                or head.get("nativeId") != member.get("nativeId")
                or retiring.get("object") == head.get("object") or retiring.get("nativeId") == head.get("nativeId")):
            return assertions, False
        phases = [event["phase"] for event in events if event.get("object") == head.get("object")
                  and event.get("phase") in ("CHUNK_MAP_TRACKING_HEAD", "CHUNK_MAP_TRACKING_RETURN")]
        if phases.count("CHUNK_MAP_TRACKING_HEAD") != 1 or phases.count("CHUNK_MAP_TRACKING_RETURN") != 1:
            return assertions, False
        if not OWNER_ASSERTIONS <= observer_assertions(card, member["type"], route, uuid):
            owners = False
    assertions |= {"source_retired", "tracking_unique", "uuid_preserved", "replacement_identity"}
    return assertions, owners


def client_rider_ok(card: Card, member: dict[str, Any], route: str, arrival: list[dict[str, Any]]) -> bool:
    """The connected player rode a transferred vehicle: its own native teleport ran inside the vehicle's generation, from the
    source-owned eject to the destination-owned remount, and the SAME player object arrived."""
    root = next((node for node in arrival if node.get("vehicleUuid") in (None, "UNAVAILABLE")), None)
    witness = card.data.get("witness")
    before = witness.get("source") if isinstance(witness, dict) else None
    before = before if isinstance(before, list) else (_members(witness, "source") or [])
    source = next((node for node in before if isinstance(node, dict) and node.get("type") == "minecraft:player"
                   and str(node.get("uuid", "")).lower() == str(member.get("uuid", "")).lower()), None)
    if root is None or source is None or source.get("object") != member.get("object") \
            or source.get("nativeId") != member.get("nativeId") or source.get("world") != ROUTE_SOURCE_WORLD[route]:
        return False
    events = headless_generation(card, str(root.get("uuid", "")).lower(), root["type"], route)
    if events is None or any(event.get("violation") for event in events):
        return False
    phases = [event.get("phase") for event in events]
    riders = sum(1 for node in arrival if node.get("type") == "minecraft:player")
    return (phases.count("PASSENGER_TRANSITION_ACCEPT") >= riders and phases.count("RIDER_TELEPORTED") == riders
            and ("RIDER_REMOUNT_DEFERRED" in phases or "ATTACH_PASSENGER" in phases))


def _validated_trace(card: Card) -> tuple[dict[str, Any], ...] | None:
    """All transaction events of a clean run whose trace file, status and probe identity are bound to this card's result."""
    result = card.result_data
    status = result.get("txStatus")
    trace_value = result.get("txTracePath")
    if (result.get("txPass") is not True or not isinstance(status, dict) or status.get("pending") != 0
            or status.get("violations") != 0 or status.get("candidateSha256") != result.get("candidateSha256")
            or status.get("testSourceSha256") != card.inputs_data.get("probeSha256")
            or not valid_sha(status.get("testSourceSha256")) or not isinstance(trace_value, str)):
        return None
    trace_path = pathlib.Path(trace_value)
    if (not trace_path.is_file() or trace_path.resolve().parent != card.result_path.resolve().parent
            or digest(trace_path) != result.get("txTraceSha256")):
        return None
    return _trace_events(trace_path)


def client_window_events(card: Card) -> tuple[dict[str, Any], ...] | None:
    """The transaction events produced while this one real-client card ran (index range recorded by the harness)."""
    events = _validated_trace(card)
    window = card.data.get("txRange")
    if (events is None or not isinstance(window, list) or len(window) != 2
            or not all(isinstance(bound, int) for bound in window) or not 0 <= window[0] <= window[1] <= len(events)):
        return None
    return events[window[0]:window[1]]


def client_player_generation_ok(card: Card, member: dict[str, Any], route: str) -> bool:
    """The connected player crossed once in this card: SOURCE_RETIRING in the source world, ADD_DURING_TELEPORT in the destination
    world, tracked exactly once, and the SAME native object throughout (the 26.2 player is moved, not replaced)."""
    events = client_window_events(card)
    uuid = str(member.get("uuid", "")).lower()
    if events is None or not uuid:
        return False
    mine = [event for event in events if str(event.get("uuid", "")).lower() == uuid
            and trace_entity_type(event.get("type")) == "minecraft:player" and event.get("route") == route
            and isinstance(event.get("generation"), int) and event["generation"] > 0]
    generations = {event["generation"] for event in mine}
    if len(generations) != 1 or any(event.get("violation") for event in mine):
        return False
    phases = [event.get("phase") for event in mine]
    retiring = next((event for event in mine if event.get("phase") == "SOURCE_RETIRING"), None)
    head = next((event for event in mine if event.get("phase") == "ADD_DURING_TELEPORT_HEAD"), None)
    if (retiring is None or head is None or phases.count("CHUNK_MAP_TRACKING_HEAD") != 1
            or phases.count("CHUNK_MAP_TRACKING_RETURN") != 1 or phases.count("ADD_DURING_TELEPORT_RETURN") != 1):
        return False
    return (retiring.get("sourceWorld") == ROUTE_SOURCE_WORLD[route] and head.get("destinationWorld") == ROUTE_DESTINATION_WORLD[route]
            and retiring.get("object") == head.get("object") == member.get("object")
            and retiring.get("nativeId") == head.get("nativeId") == member.get("nativeId"))


def _headless_graph_gateway(card: Card, shape: str, witness: dict[str, Any], arrival: list, later: list,
                            assertions: set[str]) -> tuple[set[str], bool]:
    """Same-dimension gateway: every node keeps its native object; the root carries the gateway/ticket/owner records."""
    source_members = witness.get("source")
    if not isinstance(source_members, list) or len(source_members) != len(arrival):
        return assertions, False
    source_by_uuid = {str(member.get("uuid", "")).lower(): member for member in source_members if isinstance(member, dict)}
    for member in arrival:
        before = source_by_uuid.get(str(member["uuid"]).lower())
        if (before is None or before.get("object") != member.get("object") or before.get("nativeId") != member.get("nativeId")
                or before.get("world") != "minecraft:the_end"):
            return assertions, False
    assertions = assertions | {"same_native_object", "same_dimension"}
    root_uuid = next(str(member["uuid"]).lower() for member in arrival if member.get("vehicleUuid") in (None, "UNAVAILABLE"))
    pick = lambda members: [member for member in members if str(member.get("uuid", "")).lower() == root_uuid]
    synthetic = dataclasses.replace(card, data=dict(card.data, details="UUID=" + root_uuid, witness={
        "source": pick(source_members), "arrival": pick(arrival), "later": pick(later),
        "gateway": {kind: [event for event in events if str(event.get("uuid", "")).lower() == root_uuid]
                    for kind, events in (witness.get("gateway") or {}).items()},
    }))
    root_type = {**GRAPH_SHAPES, **PGRAPH_SHAPES}[shape][0]
    root = headless_gateway_assertions(synthetic, root_type, rest_source=card.evidence == "real-client")
    # Vehicles keep their riders, so the root-only checks that compare against the whole snapshot are re-derived above.
    assertions |= root & {"source_location_vacated", "gateway_cooldown", "gateway_ticket", "source_owner", "tracking_unique",
                          "tracking_owner", "destination_owner", "observer_phases"}
    return assertions, OWNER_ASSERTIONS <= root


# ---------------------------------------------------------------------------------------------------------------
# Falling blocks (scripts/stage7/headless_dynamic.py). The entity lands and discards itself, so the result is read from
# block-count observables (before/after, per dimension and named region) that must equal a Phoenium-free control's.
# ---------------------------------------------------------------------------------------------------------------
WORLD_LABEL = {"minecraft:overworld": "overworld", "minecraft:the_nether": "nether", "minecraft:the_end": "end"}
FALLING_ROW_VARIANT = {"block": "minecraft:block", "landing_damage": "landing_damage"}
FALLING_CARRIED_BLOCK = {"block": "emerald_block", "landing_damage": "gold_block"}
FALLING_STATE_PROPS = {"anvil": {"facing": "east"}, "chipped_anvil": {"facing": "east"}, "damaged_anvil": {"facing": "east"},
                       "suspicious_sand": {"dusted": "2"}, "suspicious_gravel": {"dusted": "2"}}
GATEWAY_LANDING_AREAS = ("gateway_exit",)
GATEWAY_SOURCE_AREAS = ("fixture",)
FALLING_REGION_LABELS = {"overworld": ("main",), "nether": ("main",), "end": ("platform", "fixture", "gateway_exit")}


def falling_variant_name(row_variant: str) -> str:
    """Card-name spelling of a ledger variant: 'minecraft:block' -> 'block', 'minecraft:sand' -> 'sand', 'landing_damage' stays."""
    for name, value in FALLING_ROW_VARIANT.items():
        if value == row_variant:
            return name
    return row_variant.removeprefix("minecraft:")


def falling_row_variant(variant: str) -> str:
    return FALLING_ROW_VARIANT.get(variant, "minecraft:" + variant)


def _dynamic_deltas(witness: dict[str, Any]) -> dict[str, dict[str, int]] | None:
    """Per dimension and region: blocks after minus blocks before. None unless the structure is complete and every region loaded."""
    after, before = witness.get("observables"), witness.get("baseline")
    if not isinstance(after, dict) or not isinstance(before, dict):
        return None
    deltas: dict[str, dict[str, int]] = {}
    for dimension, labels in FALLING_REGION_LABELS.items():
        try:
            regions_after, regions_before = after[dimension]["regions"], before[dimension]["regions"]
            if set(regions_after) != set(labels) or set(regions_before) != set(labels):
                return None
            entry = {}
            for label in labels:
                if regions_after[label].get("loaded") is not True or regions_before[label].get("loaded") is not True:
                    return None
                entry[label] = int(regions_after[label]["blocks"]) - int(regions_before[label]["blocks"])
            deltas[dimension] = entry
        except (KeyError, TypeError, ValueError):
            return None
    return deltas


def _dynamic_comparable(observables: Any) -> Any:
    """Observables without the world-wide loose-item totals: the overworld copy drops items at random (leaf decay, crops), in the
    native control as well. What a fall leaves behind is still compared through `carriedItems`, `fallingBlocks` and the block counts."""
    if not isinstance(observables, dict):
        return observables
    return {dimension: ({key: value for key, value in entry.items() if key not in ("items", "itemIds")}
                        if isinstance(entry, dict) else entry) for dimension, entry in observables.items()}


def _dynamic_landing(witness: dict[str, Any], route: str) -> dict[str, Any] | None:
    """Where the carried block ended up, or None when the observables are malformed or not a single clean landing."""
    deltas = _dynamic_deltas(witness)
    if deltas is None:
        return None
    destination = WORLD_LABEL[ROUTE_DESTINATION_WORLD[route]]
    source = WORLD_LABEL[ROUTE_SOURCE_WORLD[route]]
    if route == "END_GATEWAY":
        landed = {("end", label): deltas["end"][label] for label in GATEWAY_LANDING_AREAS}
        vacated = {("end", label): deltas["end"][label] for label in GATEWAY_SOURCE_AREAS}
    else:
        landed = {(destination, label): value for label, value in deltas[destination].items()}
        vacated = {(source, label): value for label, value in deltas[source].items()}
    counted = set(landed) | set(vacated)
    elsewhere = sum(value for dimension, entry in deltas.items() for label, value in entry.items()
                    if (dimension, label) not in counted)
    leftover = sum(int(witness["observables"][dimension].get("fallingBlocks", 1)) for dimension in deltas)
    return {"landed": sum(landed.values()), "vacated": sum(vacated.values()), "elsewhere": elsewhere, "leftover": leftover,
            "landedRegions": {label: value for (_, label), value in landed.items() if value}}


def _control_card(row: dict[str, Any], name: str) -> dict[str, Any] | None:
    """The native-control card for this exact card name, only through the hash-bound control receipt of the row."""
    control = next((candidate for candidate in (row.get("nativeControlReceipt"), row.get("effectsControlReceipt"))
                    if isinstance(candidate, dict) and candidate.get("card") == name), None)
    if control is None or CONTROL_ROOT is None:
        return None
    relative = pathlib.Path(str(control.get("path", "")))
    if relative.is_absolute() or ".." in relative.parts or not relative.parts:
        return None
    receipt = (CONTROL_ROOT / relative).resolve().with_name("headless-receipt.json")
    if not receipt.is_file() or digest(receipt) != control.get("receiptSha256"):
        return None
    return next((entry for entry in read_json(receipt).get("cards", []) if entry.get("card") == name), None)


def _dynamic_trace_assertions(card: Card, route: str, uuid: str, entity_type: str = "minecraft:falling_block") -> set[str]:
    """Transfer assertions for a falling block (or another short-lived entity) from the complete transaction trace
    (the entity may be gone before any arrival snapshot)."""
    events = headless_generation(card, uuid, entity_type, route)
    if events is None or any(event.get("violation") for event in events):
        return set()
    first = {phase: next(event for event in events if event.get("phase") == phase) for phase in HEADLESS_PHASES}
    retiring, retired, head = first["SOURCE_RETIRING"], first["SOURCE_RETIRED"], first["ADD_DURING_TELEPORT_HEAD"]
    if (retiring.get("sourceWorld") != ROUTE_SOURCE_WORLD[route] or retired.get("ownerWorld") != ROUTE_SOURCE_WORLD[route]
            or head.get("destinationWorld") != ROUTE_DESTINATION_WORLD[route]):
        return set()
    assertions = {"source_retired", "uuid_preserved"}
    if retiring.get("object") != head.get("object") and retiring.get("nativeId") != head.get("nativeId"):
        assertions.add("replacement_identity")
    if sum(1 for event in events if event.get("phase") == "ADD_DURING_TELEPORT_HEAD") == 1:
        assertions.add("destination_unique")
    tracking = [event["phase"] for event in events if event.get("object") == head.get("object")
                and event.get("phase") in ("CHUNK_MAP_TRACKING_HEAD", "CHUNK_MAP_TRACKING_RETURN")]
    if tracking.count("CHUNK_MAP_TRACKING_HEAD") == 1 and tracking.count("CHUNK_MAP_TRACKING_RETURN") == 1:
        assertions.add("tracking_unique")
    return assertions


def _carried_state_matches(witness: dict[str, Any], variant: str) -> bool:
    block = FALLING_CARRIED_BLOCK.get(variant, variant)
    props = FALLING_STATE_PROPS.get(block, {})
    spec = "minecraft:" + block + (("[" + ",".join(f"{key}={value}" for key, value in props.items()) + "]") if props else "")
    nbt = witness.get("nbtSource")
    if witness.get("carriedSpec") != spec or not isinstance(nbt, str) or f'Name: "minecraft:{block}"' not in nbt:
        return False
    return all(f'{key}: "{value}"' in nbt for key, value in props.items())


def headless_dynamic_claims(card: Card, rows: list[dict[str, Any]] | None) -> list[dict[str, Any]]:
    match = DYNAMIC_CARD.fullmatch(card.name)
    witness = card.data.get("witness")
    if card.evidence != "server-headless" or match is None or not isinstance(witness, dict):
        return []
    variant, route = match.groups()
    if card.data.get("outcome") != "TRANSFERRED":
        return []
    row_variant = falling_row_variant(variant)
    row = next((candidate for candidate in rows or [] if candidate.get("entityType") == "minecraft:falling_block"
                and candidate.get("route") == route and candidate.get("variant") == row_variant
                and candidate.get("graph", "standalone") == "standalone"), None)
    gateway = route == "END_GATEWAY"
    expected = "SAME_DIMENSION_TRANSFER" if gateway else "TRANSFER"
    if row is None or row.get("expectedNativeOutcome") != expected:
        return []
    control = _control_card(row, card.name)
    control_witness = control.get("witness") if isinstance(control, dict) else None
    uuid = _snapshot_uuid(card.data)
    if not isinstance(control_witness, dict) or control.get("outcome") != "TRANSFERRED" or uuid is None:
        return []
    own, native = _dynamic_landing(witness, route), _dynamic_landing(control_witness, route)
    clean = lambda landing: (landing is not None and landing["landed"] == 1 and landing["vacated"] == 0
                             and landing["elsewhere"] == 0 and landing["leftover"] == 0)
    if not (clean(own) and clean(native)):
        return []
    assertions: set[str] = set()
    # native_landing: the same world as the Phoenium-free control (every block count, drop, leftover entity and target health).
    if (witness.get("carriedSpec") == control_witness.get("carriedSpec")
            and all(_dynamic_comparable(witness.get(key)) == _dynamic_comparable(control_witness.get(key))
                    for key in ("baseline", "observables"))):
        if variant != "landing_damage" or _landing_damage_exhibited(control_witness, route):
            assertions.add("native_landing")
    if _carried_state_matches(witness, variant) and "native_landing" in assertions:
        assertions.add("carried_state")
    if own["vacated"] == 0 and own["leftover"] == 0:
        assertions.add("source_block_removed")
    if route == "END_ENTRY" and own["landedRegions"] == {"platform": 1}:
        assertions.add("end_platform")
    if gateway:
        assertions |= headless_gateway_assertions(card, "minecraft:falling_block", falling=True)
        needed = REQUIRED_SAME_DIMENSION_ASSERTIONS
    else:
        assertions |= _dynamic_trace_assertions(card, route, uuid)
        needed = REQUIRED_TRANSFER_ASSERTIONS | {"uuid_preserved", "replacement_identity"}
    # destination_owner (and the other owner assertions) come from the observer trace in build_report.
    needed = (needed - OWNER_ASSERTIONS - {"destination_owner"}) | {"source_block_removed", "carried_state", "native_landing"}
    if route == "END_ENTRY":
        needed |= {"end_platform"}
    if not needed <= assertions:
        return []
    return [dict(card=card, entityType="minecraft:falling_block", route=route, variant=row_variant, graph="standalone",
                 observedOutcome=expected, assertions=assertions, basicObservation=True, scope="row",
                 sameDimensionGateway=gateway, headlessOwner=gateway and OWNER_ASSERTIONS <= assertions)]


def _landing_damage_exhibited(control_witness: dict[str, Any], route: str) -> bool:
    """The native control must show the falling block hurting or killing a destination target; otherwise the row proves nothing about damage."""
    destination = WORLD_LABEL[ROUTE_DESTINATION_WORLD[route]]
    try:
        before = control_witness["baseline"][destination]
        after = control_witness["observables"][destination]
        # The destination fixture's transient portal fire can scorch the one target standing on its cell before the baseline is
        # read (the Phoenium-free control does it too), so the baseline need not be pristine: the block must hurt a further target.
        return (before["targets"] > 0 and before["targetsUndamaged"] > 0
                and after["targetsUndamaged"] < before["targetsUndamaged"])
    except (KeyError, TypeError):
        return False


# kind -> (entity type, ledger variant, the assertions the observation can establish)
EFFECT_KINDS: dict[str, tuple[str, str | None, set[str]]] = {
    "arrow": ("minecraft:arrow", None, {"projectile_owner_preserved", "impact_effect"}),
    "spectral_arrow": ("minecraft:spectral_arrow", None, {"projectile_owner_preserved", "impact_effect"}),
    "trident": ("minecraft:trident", None, {"projectile_owner_preserved", "loyalty_effect"}),
    "fireball": ("minecraft:fireball", None, {"impact_explosion"}),
    "small_fireball": ("minecraft:small_fireball", None, {"impact_fire_effect"}),
    "dragon_fireball": ("minecraft:dragon_fireball", None, {"dragon_breath_effect"}),
    "wither_skull": ("minecraft:wither_skull", None, {"wither_impact_effect"}),
    "wind_charge": ("minecraft:wind_charge", None, {"wind_impact_effect"}),
    "breeze_wind_charge": ("minecraft:breeze_wind_charge", None, {"wind_impact_effect"}),
    "shulker_bullet": ("minecraft:shulker_bullet", None, {"owner_target_preserved", "impact_effect"}),
    "egg": ("minecraft:egg", None, {"impact_effect"}),
    "snowball": ("minecraft:snowball", None, {"impact_effect"}),
    "splash_potion": ("minecraft:splash_potion", None, {"splash_effect"}),
    "lingering_potion": ("minecraft:lingering_potion", None, {"lingering_cloud_effect"}),
    "experience_bottle": ("minecraft:experience_bottle", None, {"xp_release"}),
    "firework_rocket": ("minecraft:firework_rocket", None, {"firework_effect"}),
    "tnt": ("minecraft:tnt", None, {"fuse_preserved", "native_explosion"}),
    "tnt_near_expiry": ("minecraft:tnt", "fuse_near_expiry", {"fuse_preserved", "native_explosion"}),
}
# Kinds with no snapshot after the arrival: they explode or end by themselves within a second.
EFFECT_SHORT_LIVED = {"firework_rocket", "tnt_near_expiry"}
EFFECT_OWNER_KINDS = {"arrow", "spectral_arrow", "trident", "shulker_bullet"}
UUID_ARRAY = r"\[I; (?:-?\d+, ){3}-?\d+\]"


def _effect_targets(witness: dict[str, Any], route: str) -> dict[str, dict[str, Any]] | None:
    try:
        targets = witness["effects"][WORLD_LABEL[ROUTE_DESTINATION_WORLD[route]]]["targets"]
    except (KeyError, TypeError):
        return None
    return targets if isinstance(targets, dict) and targets else None


def effects_kind_of_row(row: dict[str, Any]) -> str | None:
    """The D1 card kind of a standalone ledger row that the effects harness drives, or None."""
    if row.get("graph", "standalone") != "standalone":
        return None
    variant = row.get("variant") or None
    return next((kind for kind, (entity_type, row_variant, _) in EFFECT_KINDS.items()
                 if entity_type == row.get("entityType") and row_variant == variant), None)


EFFECT_RANDOM_MAGNITUDE = {"firework_rocket"}


def _effects_comparable(witness: dict[str, Any], coarse: bool = False, orbs_anywhere: bool = False) -> Any:
    """What a native effect leaves behind, normalised: target health to one decimal, fire/orbs as flags, the random hen of an egg dropped.

    `coarse` is for an effect whose native magnitude is itself random (a firework's burst): health becomes "was damaged"
    positions are floored and the projectile's own position is ignored, so only the exhibited outcome has to match.
    `orbs_anywhere` is for an experience bottle: its orbs drift at random, so one may end in either dimension (an orb can
    natively re-enter the portal it was released beside); only whether orbs were released at all is compared."""
    effects = witness.get("effects")
    if not isinstance(effects, dict) or not effects:
        return None
    result: dict[str, Any] = {}
    for dimension, entry in sorted(effects.items()):
        try:
            targets = {name: (state.get("alive"),
                              None if state.get("health") is None
                              else (float(state["health"]) < 10.0 if coarse else round(float(state["health"]), 1)),
                              tuple(state.get("effects") or ()), (state.get("fire") or 0) > 0, bool(state.get("hurtBy")),
                              tuple(math.floor(value) for value in state.get("pos") or ()) if coarse
                              else tuple(state.get("pos") or ()))
                       for name, state in sorted(entry["targets"].items())}
            owner = entry["owner"]
            projectile = entry["projectile"]
            result[dimension] = {
                "targets": targets,
                "owner": (owner.get("alive"), None if owner.get("health") is None else round(float(owner["health"]), 1)),
                "counts": {key: (value > 0 if key == "experience_orb" else value)
                           for key, value in sorted(entry["counts"].items())
                           if key != "chicken" and not (orbs_anywhere and key == "experience_orb")},
                # a rocket's flight length is random too, so only whether it is still flying is compared
                "projectile": (projectile.get("alive"),) if coarse
                else (projectile.get("alive"), tuple(round(value, 1) for value in projectile.get("pos") or ())),
            }
        except (KeyError, TypeError, AttributeError, ValueError):
            return None
    if orbs_anywhere:
        result["orbs_anywhere"] = any(entry["counts"].get("experience_orb", 0) > 0 for entry in effects.values())
    return result


def _is_damaged(state: dict[str, Any]) -> bool:
    return state.get("alive") is False or (state.get("health") is not None and state["health"] < 10.0)


def _effect_exhibited(kind: str, witness: dict[str, Any], route: str) -> bool:
    """Does the NATIVE control show this projectile's effect on the targets it was aimed at? (Phoenium is then compared to it.)"""
    targets = _effect_targets(witness, route)
    if targets is None:
        return False
    destination = witness["effects"][WORLD_LABEL[ROUTE_DESTINATION_WORLD[route]]]
    states, counts = list(targets.values()), destination.get("counts", {})
    damaged = any(_is_damaged(state) for state in states)
    if kind == "firework_rocket":
        # In the Nether the rocket bursts on the ceiling. The End has none under 467 blocks, so the native control's rocket is
        # still climbing at the end of the window: there the effect is "keeps flying exactly as native does" (credited only
        # when Phoenium matches the control, a documented native-equal limit like the shulker bullet).
        return damaged or (route in ("END_ENTRY", "END_EXIT", "END_GATEWAY") and destination.get("projectile", {}).get("alive") is True)
    if kind in ("arrow", "fireball", "small_fireball", "wither_skull", "wind_charge", "breeze_wind_charge"):
        return damaged
    if kind == "spectral_arrow":
        return any("glowing" in (state.get("effects") or ()) for state in states)
    if kind == "trident":
        projectile = destination.get("projectile", {})
        return damaged and projectile.get("alive") is not True
    if kind == "dragon_fireball":
        return counts.get("area_effect_cloud", 0) >= 1
    if kind in ("egg", "snowball"):
        # a zero-damage throwable leaves no mark on the target; the native impact is the projectile being consumed (it would
        # otherwise fly on forever under NoGravity), and Phoenium must be consumed identically (see _effects_comparable)
        return damaged or any(state.get("hurtBy") for state in states) or destination.get("projectile", {}).get("alive") is False
    if kind == "splash_potion":
        return any("poison" in (state.get("effects") or ()) for state in states)
    if kind == "lingering_potion":
        return counts.get("area_effect_cloud", 0) >= 1 or any("poison" in (state.get("effects") or ()) for state in states)
    if kind == "experience_bottle":
        return any(entry.get("counts", {}).get("experience_orb", 0) > 0 for entry in witness["effects"].values())
    if kind in ("tnt", "tnt_near_expiry"):
        return damaged and destination.get("counts", {}).get("tnt", 1) == 0
    if kind == "shulker_bullet":
        return True   # the native outcome is "no impact": the bullet keeps no target (see _effect_links)
    return False


def _nbt_value(nbt: Any, key: str, pattern: str) -> str | None:
    found = re.search(rf"[{{ ,]{key}: ({pattern})", nbt) if isinstance(nbt, str) else None
    return found.group(1) if found else None


def _effect_links(kind: str, witness: dict[str, Any], control_witness: dict[str, Any]) -> set[str]:
    """Owner (and a shulker bullet's target) across the transfer, exactly as the native control kept or dropped it."""
    result: set[str] = set()
    if kind not in EFFECT_OWNER_KINDS:
        return result
    stages = ("nbtSource", "nbtArrival", "nbtLater")

    def links(source: dict[str, Any]) -> dict[str, dict[str, str | None]]:
        # The fixture derives the owner/target uuids from the card's index inside its run, so two runs name the same link with
        # different uuids: each stage is read relative to that run's own source (SOURCE = the link as it was when the card started).
        raw = {stage: {key: _nbt_value(source.get(stage), key, UUID_ARRAY) for key in ("Owner", "Target")} for stage in stages}
        return {stage: {key: ("SOURCE" if value is not None and value == raw["nbtSource"][key] else value)
                        for key, value in raw[stage].items()} for stage in stages}

    raw_owner = _nbt_value(witness.get("nbtSource"), "Owner", UUID_ARRAY)
    own, native = links(witness), links(control_witness)
    if raw_owner is None or own["nbtSource"] != native["nbtSource"]:
        return result
    if own["nbtArrival"] != native["nbtArrival"] or own["nbtLater"] != native["nbtLater"]:
        return result
    if own["nbtArrival"]["Owner"] == "SOURCE":
        result.add("owner_target_preserved" if kind == "shulker_bullet" else "projectile_owner_preserved")
    return result


def _fuse_preserved(witness: dict[str, Any], control_witness: dict[str, Any]) -> bool:
    """The primed TNT keeps counting down from where it was (not reset), and in the same way as in the native control."""
    def fuse(source: dict[str, Any], stage: str) -> int | None:
        value = _nbt_value(source.get(stage), "fuse", r"\d+")
        return int(value) if value is not None else None
    start, arrival = fuse(witness, "nbtSource"), fuse(witness, "nbtArrival")
    native_start, native_arrival = fuse(control_witness, "nbtSource"), fuse(control_witness, "nbtArrival")
    if None in (start, arrival, native_start, native_arrival) or start != native_start:
        return False
    if not 0 < arrival <= start or start - arrival > 40 or not 0 < native_arrival <= native_start:
        return False
    later, native_later = fuse(witness, "nbtLater"), fuse(control_witness, "nbtLater")
    if (later is None) != (native_later is None):
        return False
    return later is None or later < arrival


def headless_effects_claims(card: Card, rows: list[dict[str, Any]] | None) -> list[dict[str, Any]]:
    match = EFFECTS_CARD.fullmatch(card.name)
    witness = card.data.get("witness")
    if card.evidence != "server-headless" or match is None or not isinstance(witness, dict):
        return []
    kind, route = match.groups()
    spec = EFFECT_KINDS.get(kind)
    if spec is None or card.data.get("outcome") != "TRANSFERRED":
        return []
    entity_type, row_variant, effect_assertions = spec
    gateway = route == "END_GATEWAY"
    expected = "SAME_DIMENSION_TRANSFER" if gateway else "TRANSFER"
    row = next((candidate for candidate in rows or [] if candidate.get("entityType") == entity_type
                and candidate.get("route") == route and (candidate.get("variant") or None) == row_variant
                and candidate.get("graph", "standalone") == "standalone"), None)
    if row is None or row.get("expectedNativeOutcome") != expected:
        return []
    control = _control_card(row, card.name)
    control_witness = control.get("witness") if isinstance(control, dict) else None
    uuid = _snapshot_uuid(card.data)
    if not isinstance(control_witness, dict) or control.get("outcome") != "TRANSFERRED" or uuid is None:
        return []
    coarse = kind in EFFECT_RANDOM_MAGNITUDE
    orbs = kind == "experience_bottle"
    own, native = _effects_comparable(witness, coarse, orbs), _effects_comparable(control_witness, coarse, orbs)
    if own is None or native is None or own != native:
        return []
    own_armed, native_armed = witness.get("armedAt"), control_witness.get("armedAt")
    if coarse and isinstance(own_armed, list) and isinstance(native_armed, list):
        # a rocket is armed while it is already climbing, so the exact armed position is timing noise
        own_armed, native_armed = ([math.floor(value) for value in position] for position in (own_armed, native_armed))
    if own_armed != native_armed:
        return []
    if not _effect_exhibited(kind, control_witness, route):
        return []
    assertions: set[str] = set(effect_assertions) - {"projectile_owner_preserved", "owner_target_preserved", "fuse_preserved"}
    assertions |= _effect_links(kind, witness, control_witness)
    if "fuse_preserved" in effect_assertions and _fuse_preserved(witness, control_witness):
        assertions.add("fuse_preserved")
    if gateway:
        assertions |= headless_gateway_assertions(card, entity_type, later_optional=kind in EFFECT_SHORT_LIVED, ends_on_impact=True)
        needed = REQUIRED_SAME_DIMENSION_ASSERTIONS
    else:
        assertions |= _dynamic_trace_assertions(card, route, uuid, entity_type)
        if kind not in EFFECT_SHORT_LIVED:
            assertions |= headless_transfer_assertions(card, entity_type, route)
        needed = REQUIRED_TRANSFER_ASSERTIONS | {"uuid_preserved", "replacement_identity"}
    needed = (needed - OWNER_ASSERTIONS - {"destination_owner"}) | (required_assertions(row, expected) - REQUIRED_TRANSFER_ASSERTIONS
                                                                       - REQUIRED_SAME_DIMENSION_ASSERTIONS)
    if not needed <= assertions:
        return []
    return [dict(card=card, entityType=entity_type, route=route, variant=row_variant, graph="standalone",
                 observedOutcome=expected, assertions=assertions, basicObservation=True, scope="row",
                 sameDimensionGateway=gateway, headlessOwner=gateway and OWNER_ASSERTIONS <= assertions,
                 ownerAssertions=(OWNER_ASSERTIONS & assertions) if gateway and not OWNER_ASSERTIONS <= assertions else None)]


def headless_claims(card: Card, rows: list[dict[str, Any]] | None = None) -> list[dict[str, Any]]:
    """Server-headless claims for entity types without family-specific semantics (plain entities) and passenger graphs."""
    if card.evidence == "real-client" and CLIENT_CARD.fullmatch(card.name):
        return client_claims(card, rows)
    if DYNAMIC_CARD.fullmatch(card.name):
        return headless_dynamic_claims(card, rows)
    if EFFECTS_CARD.fullmatch(card.name):
        return headless_effects_claims(card, rows)
    graph_match = GRAPH_CARD.fullmatch(card.name)
    if card.evidence == "server-headless" and graph_match is not None:
        shape, route = graph_match.groups()
        if shape not in GRAPH_SHAPE_ROW or card.data.get("outcome") != "TRANSFERRED":
            return []
        assertions, owners = headless_graph_assertions(card, shape, route)
        if route == "END_GATEWAY":
            needed = REQUIRED_SAME_DIMENSION_ASSERTIONS | {"graph_nodes_exact", "graph_edges_exact", "no_dual_addressability"}
            outcome = "SAME_DIMENSION_TRANSFER"
        else:
            needed = {"source_retired", "destination_unique", "tracking_unique", "uuid_preserved", "replacement_identity",
                      "graph_nodes_exact", "graph_edges_exact", "no_dual_addressability"}
            outcome = "TRANSFER"
        if needed - OWNER_ASSERTIONS - {"destination_owner"} <= assertions and owners:
            return [dict(card=card, entityType=None, route=route, variant=None, graph=GRAPH_SHAPE_ROW[shape],
                         observedOutcome=outcome, assertions=assertions, basicObservation=True, scope="row",
                         headlessOwner=True, sameDimensionGateway=route == "END_GATEWAY")]
        return []
    state_variant = None
    match = HEADLESS_CARD.fullmatch(card.name)
    variant_match = VARIANT_CARD.fullmatch(card.name)
    if card.evidence != "server-headless" or (match is None and variant_match is None):
        return []
    if variant_match is not None:
        kind, state_variant, route = variant_match.groups()
        if not creaking_variant_state_ok(card, route, state_variant):
            return []
    else:
        kind, route = match.groups()
    entity_type = "minecraft:" + kind
    outcome = card.data.get("outcome")
    # Negative (no-entry) claims are gated by the row's pinned-source proof; transfers need a plain family contract.
    family = headless_family(entity_type)
    if outcome in ("TRANSFERRED",) and family is None:
        return []
    if route == "END_GATEWAY" and outcome == "TRANSFERRED":
        assertions = headless_gateway_assertions(card, entity_type)
        if family == "vehicle":
            assertions |= headless_vehicle_extras(card, entity_type)
        needed = REQUIRED_SAME_DIMENSION_ASSERTIONS | {"post_arrival_tick"}
        if family == "vehicle":
            needed |= required_assertions({"entityType": entity_type, "route": route}, "SAME_DIMENSION_TRANSFER")
        if needed <= assertions:
            return [dict(card=card, entityType=entity_type, route=route, variant=state_variant, stateVariant=state_variant,
                         graph="standalone",
                         observedOutcome="SAME_DIMENSION_TRANSFER", assertions=assertions, basicObservation=True,
                         scope="row", sameDimensionGateway=True, headlessOwner=OWNER_ASSERTIONS <= assertions)]
        return []
    if outcome == "TRANSFERRED":
        assertions = headless_transfer_assertions(card, entity_type, route)
        needed = {"source_retired", "destination_unique", "tracking_unique", "post_arrival_tick",
                  "uuid_preserved", "replacement_identity"}
        if family == "vehicle":
            assertions |= headless_vehicle_extras(card, entity_type)
            needed |= required_assertions({"entityType": entity_type, "route": route}, "TRANSFER") - OWNER_ASSERTIONS \
                - {"destination_owner"}
        if needed <= assertions:
            return [dict(card=card, entityType=entity_type, route=route, variant=state_variant, stateVariant=state_variant,
                         graph="standalone",
                         observedOutcome="TRANSFER", assertions=assertions, basicObservation=True, scope="row")]
        return []
    if state_variant == "heart_bound" and outcome == "STAYED":
        row = next((candidate for candidate in rows or [] if candidate.get("entityType") == entity_type
                    and candidate.get("route") == route and candidate.get("variant") == state_variant
                    and candidate.get("graph", "standalone") == "standalone"), None)
        proof = row.get("sourceProof") if isinstance(row, dict) else None
        if not isinstance(proof, dict) or row.get("expectedNativeOutcome") != "REFUSAL":
            return []
        negative = headless_negative_assertions(card, entity_type, route, state_variant)
        if not {"source_unchanged", "destination_absent", "portal_session_absent", "graph_unchanged"} <= negative:
            return []
        assertions = set(negative) | {"source_unchanged_or_safely_retired", "no_destination_entity", "no_stuck_session"}
        applicability = {"sourceProofKind": proof.get("kind"), "sourceJarSha256": proof.get("sourceJarSha256"),
                         "mappedBinarySha256": proof.get("mappedBinarySha256")}
        return [dict(card=card, entityType=entity_type, route=route, variant=state_variant, stateVariant=state_variant,
                     graph="standalone", observedOutcome="REFUSAL", applicability=applicability, assertions=assertions,
                     basicObservation=True, scope="row", stateConstructed=True)]
    if state_variant is not None:
        return []
    if outcome in ("STAYED", "GONE"):
        row = next((candidate for candidate in rows or [] if candidate.get("entityType") == entity_type
                    and candidate.get("route") == route and candidate.get("variant") in (None, "")
                    and candidate.get("graph", "standalone") == "standalone"), None)
        proof = row.get("sourceProof") if isinstance(row, dict) else None
        if not isinstance(proof, dict) or row.get("expectedNativeOutcome") != "NO_NATIVE_ENTRY_PATH":
            return []
        assertions = (headless_negative_assertions(card, entity_type, route) if outcome == "STAYED"
                      else headless_discard_assertions(card, entity_type, route))
        needed = ({"source_unchanged", "destination_absent", "portal_session_absent", "graph_unchanged"}
                  if outcome == "STAYED" else {"destination_absent", "portal_session_absent", "native_discard"})
        if needed <= assertions:
            cell = HEADLESS_PORTAL_CELL[route]
            applicability = {
                "sourceWorld": ROUTE_SOURCE_WORLD[route], "destinationWorld": ROUTE_DESTINATION_WORLD[route],
                "portalBlock": ROUTE_PORTAL_BLOCK[route], "sourceProofKind": proof.get("kind"),
                "portalX": cell[0], "portalY": cell[1], "portalZ": cell[2],
                "sourceJarSha256": proof.get("sourceJarSha256"), "mappedBinarySha256": proof.get("mappedBinarySha256")}
            return [dict(card=card, entityType=entity_type, route=route, variant=None, graph="standalone",
                         observedOutcome="NO_NATIVE_ENTRY_PATH", applicability=applicability, assertions=assertions,
                         basicObservation=True, scope="row")]
    return []

# ---------------------------------------------------------------------------------------------------------------
# Real-client (K1) cards (scripts/stage7/client_closeout.py): the connected PhoeniumTest0 session is the entity or the rider.
# Every witness is a triple: server NBT of the player, the probe's raw entities, and the real client's own snapshot.
# ---------------------------------------------------------------------------------------------------------------
PGRAPH_SHAPES = {
    "pboat": ("minecraft:oak_boat", (("minecraft:player", ()),)),
    "pminecart": ("minecraft:minecart", (("minecraft:player", ()),)),
    "pmob": ("minecraft:pig", (("minecraft:player", ()),)),
    "praft": ("minecraft:bamboo_raft", (("minecraft:player", ()),)),
    "pcamel": ("minecraft:camel", (("minecraft:player", ()), ("minecraft:villager", ()))),
    # A chest boat seats one passenger natively (AbstractChestBoat.getMaxPassengers() == 1), but /ride forces a second one aboard
    # (startRiding(force = true)); the Phoenium-free control run builds the same forced graph, so the native outcome is observed.
    "pchestboat": ("minecraft:oak_chest_boat", (("minecraft:player", ()), ("minecraft:villager", ()))),
}
CREDITS_GRAPH_VEHICLE = {"cboat": "minecraft:oak_boat", "cminecart": "minecraft:minecart", "ccamel": "minecraft:camel"}
CLIENT_MOVE_MIN = 0.3          # blocks the real client must have walked once the hand-over is complete
CLIENT_POSITION_TOLERANCE = 2.0


def _obs(witness: Any, key: str) -> dict[str, Any] | None:
    value = witness.get(key) if isinstance(witness, dict) else None
    return value if isinstance(value, dict) else None


def _members(witness: Any, key: str, entity_type: str | None = None) -> list[dict[str, Any]] | None:
    observation = _obs(witness, key)
    probe = observation.get("probe") if observation else None
    if not isinstance(probe, list) or not all(isinstance(item, dict) for item in probe):
        return None
    return [item for item in probe if entity_type is None or item.get("type") == entity_type]


def _client_gateway(witness: Any) -> dict[str, list[dict[str, Any]]]:
    """Probe gateway/no-entry events of a real-client card. The probe keeps tiny rings (4 contacts, 4 tickets, 8 tracking events,
    256 no-entry contacts), so every observation carries the ring as it was then; the union over the card's observations is
    de-duplicated by content, and the no-entry ring is the one read at the end of the card. Callers filter by entity uuid."""
    merged: dict[str, list[dict[str, Any]]] = {"contact": [], "ticket": [], "tracking": [], "noEntry": []}
    seen: set[str] = set()
    if not isinstance(witness, dict):
        return merged
    for key in ("source", "arrival", "later", "final", "settled", "carried"):
        events = (_obs(witness, key) or {}).get("events")
        if not isinstance(events, dict):
            continue
        for kind in ("contact", "ticket", "tracking"):
            for event in events.get(kind) or []:
                marker = kind + json.dumps(event, sort_keys=True)
                if isinstance(event, dict) and marker not in seen:
                    seen.add(marker)
                    merged[kind].append(event)
    ending = witness.get("probeEvents")
    if isinstance(ending, dict):
        merged["noEntry"] = [event for event in ending.get("noEntry") or [] if isinstance(event, dict)]
    return merged


def _xz_distance(a: Any, b: Any) -> float | None:
    try:
        return ((a["x"] - b["x"]) ** 2 + (a["z"] - b["z"]) ** 2) ** .5
    except (KeyError, TypeError):
        return None


def client_session_assertions(witness: dict[str, Any], uuid: str, destination: str, source_player: dict[str, Any]
                              ) -> set[str]:
    """The real client and the server agree about the player after the hand-over, on the same connection, and it can play."""
    assertions: set[str] = set()
    converged = True
    for key in ("later", "final"):
        observation = _obs(witness, key)
        client = observation.get("client") if observation else None
        player = client.get("player") if isinstance(client, dict) else None
        members = _members(witness, key, "minecraft:player")
        probe = members[0] if members and len(members) == 1 else None
        if (not isinstance(player, dict) or probe is None or client.get("connected") is not True
                or client.get("dimension") != destination or client.get("screen") is not None
                or str(player.get("uuid", "")).lower() != uuid or player.get("removed") is not False
                or str(probe.get("uuid", "")).lower() != uuid or probe.get("world") != destination
                or probe.get("connectionObject") != source_player.get("connectionObject")
                or probe.get("listenerPlayerObject") != probe.get("object")
                or _xz_distance(player, probe) is None or _xz_distance(player, probe) > CLIENT_POSITION_TOLERANCE):
            converged = False
    if converged:
        assertions.add("session_converged")
    playable = witness.get("playable")
    if isinstance(playable, dict):
        before, after, server = playable.get("clientBefore"), playable.get("clientAfter"), playable.get("server")
        values = server.get("posValues") if isinstance(server, dict) else None
        if (isinstance(before, dict) and isinstance(after, dict) and isinstance(values, list) and len(values) == 3
                and server.get("dimension") == destination
                and (_xz_distance(before, after) or 0) >= CLIENT_MOVE_MIN
                and ((after.get("x", 1e9) - values[0]) ** 2 + (after.get("z", 1e9) - values[2]) ** 2) ** .5
                <= CLIENT_POSITION_TOLERANCE):
            assertions.add("client_playable")
    return assertions


def _client_synthetic(card: Card, uuid: str, entity_type: str, route: str, witness: dict[str, Any], **keys: Any) -> Card:
    """A headless-shaped view of one tracked entity of a real-client card, so the headless derivations apply unchanged."""
    shaped = {key: _members(witness, key, entity_type) for key in ("source", "arrival", "later") if _obs(witness, key)}
    shaped.update(keys)
    return dataclasses.replace(card, data=dict(card.data, details="UUID=" + uuid, witness=shaped))


def client_credits_assertions(card: Card, route: str) -> set[str]:
    """The End exit of a player who has not seen the credits is native's respawn path, not a portal transfer: the exiting
    ServerPlayer leaves every level (CHANGED_DIMENSION) while the real client shows WinScreen, and on the client's respawn
    request the player list creates a REPLACEMENT ServerPlayer on the same connection in the respawn dimension. There are no
    portal transaction events to read, so each assertion comes from the raw triple: server NBT, probe, real client."""
    witness = card.data.get("witness")
    if card.data.get("outcome") != "TRANSFERRED" or route != "END_EXIT" or not isinstance(witness, dict):
        return set()
    source, arrival, later = (_single(_members(witness, key, "minecraft:player")) for key in ("source", "arrival", "later"))
    shown, server_before, in_credits = _obs(witness, "creditsShown"), _obs(witness, "source"), witness.get("creditsServer")
    if (source is None or arrival is None or later is None or shown is None or server_before is None
            or not isinstance(in_credits, dict) or witness.get("creditsProbe") != []):
        return set()
    uuid = str(source.get("uuid", "")).lower()
    destination = ROUTE_DESTINATION_WORLD[route]
    shown_player = shown.get("player") if isinstance(shown.get("player"), dict) else {}
    if (not re.fullmatch(r"[0-9a-f-]{36}", uuid) or shown.get("screen") != "WinScreen"
            or server_before.get("server", {}).get("seenCredits") != "0b" or in_credits.get("seenCredits") != "1b"
            or str(shown_player.get("uuid", "")).lower() != uuid
            or any(str(part.get("uuid", "")).lower() != uuid or part.get("removed") is not False for part in (arrival, later))
            or source.get("world") != ROUTE_SOURCE_WORLD[route] or arrival.get("world") != destination
            or later.get("world") != destination or arrival.get("object") != later.get("object")
            or source.get("object") == arrival.get("object")
            or arrival.get("connectionObject") != source.get("connectionObject")
            or later.get("connectionObject") != source.get("connectionObject")
            or arrival.get("listenerPlayerObject") != arrival.get("object")):
        return set()
    assertions = client_session_assertions(witness, uuid, destination, source)
    assertions |= {"uuid_preserved", "source_retired", "destination_unique"}
    if (isinstance(arrival.get("ticks"), int) and isinstance(later.get("ticks"), int) and later["ticks"] > arrival["ticks"] + 20):
        assertions |= {"post_arrival_tick", "destination_owner"}
    client = ((_obs(witness, "later") or {}).get("client") or {})
    entities = client.get("entities")
    if (isinstance(entities, list) and not any(isinstance(item, dict) and str(item.get("uuid", "")).lower() == uuid
                                               for item in entities)):
        assertions.add("tracking_unique")
    return assertions


def client_player_gateway_owner_assertions(card: Card, assertions: set[str], uuid: str) -> set[str]:
    """A connected player's own gateway teleport runs on the connection tick, outside any regional owner context, so the probe
    records its gateway contact and portal ticket with ownerContext NONE (a pig's are recorded inside its region). The records
    still have to be the right ones (the gateway cell, the destination chunk, the portal ticket type), and the owner identity is
    then the live object's: a PLAYER object OWNED by one and the same End region in the source, arrival and later snapshots,
    whose tracking updates ran inside that region (tracking_owner). A record carrying another region's context is refused."""
    witness = card.data.get("witness")
    gateway = witness.get("gateway") if isinstance(witness, dict) else None
    snapshots = [_single(witness.get(key)) if isinstance(witness, dict) else None for key in ("source", "arrival", "later")]
    if (not isinstance(gateway, dict) or any(snapshot is None for snapshot in snapshots) or "tracking_owner" not in assertions
            or not {"same_native_object", "same_dimension", "destination_unique"} <= assertions):
        return set()
    end = "minecraft:the_end"
    regions = set()
    for snapshot in snapshots:
        diagnostic = snapshot.get("ownershipDiagnostic")
        if (not isinstance(diagnostic, str) or "kind=PLAYER" not in diagnostic or "state=OWNED" not in diagnostic
                or f"objectId={uuid}" not in diagnostic.lower().replace("objectid=", "objectId=")
                or _region(diagnostic) is None or snapshot.get("world") != end):
            return set()
        regions.add(_region(diagnostic))
    if len(regions) != 1:
        return set()
    region = next(iter(regions))
    arrival_chunk = (snapshots[1].get("chunkX"), snapshots[1].get("chunkZ"))
    contacts = [event for event in gateway.get("contact", []) if isinstance(event, dict) and str(event.get("uuid", "")).lower() == uuid]
    tickets = [event for event in gateway.get("ticket", []) if isinstance(event, dict) and str(event.get("uuid", "")).lower() == uuid]

    def no_foreign_context(event: dict[str, Any]) -> bool:
        context = event.get("ownerContext")
        return context in (None, "NONE") or _region(context) == region

    if (contacts and tickets
            and all(c.get("world") == end and (c.get("x"), c.get("y"), c.get("z")) == HEADLESS_PORTAL_CELL["END_GATEWAY"]
                    and no_foreign_context(c) for c in contacts)
            and all(t.get("world") == end and t.get("ticketType") == "minecraft:portal"
                    and (t.get("chunkX"), t.get("chunkZ")) == arrival_chunk and no_foreign_context(t) for t in tickets)):
        return {"source_owner", "gateway_ticket", "destination_owner", "observer_phases"}
    return set()


def client_player_assertions(card: Card, route: str, credits_unseen: bool = False) -> set[str]:
    witness = card.data.get("witness")
    if card.data.get("outcome") != "TRANSFERRED" or not isinstance(witness, dict):
        return set()
    if credits_unseen:
        return client_credits_assertions(card, route)
    source, arrival, later = (_single(_members(witness, key, "minecraft:player")) for key in ("source", "arrival", "later"))
    if source is None or arrival is None or later is None:
        return set()
    uuid = str(source.get("uuid", "")).lower()
    destination = ROUTE_DESTINATION_WORLD[route]
    if (not re.fullmatch(r"[0-9a-f-]{36}", uuid) or any(str(part.get("uuid", "")).lower() != uuid for part in (arrival, later))
            or source.get("world") != ROUTE_SOURCE_WORLD[route] or arrival.get("world") != destination
            or later.get("world") != destination or any(part.get("removed") is not False for part in (source, arrival, later))
            or not all(part.get("object") == source.get("object") and part.get("nativeId") == source.get("nativeId")
                       for part in (arrival, later))):
        return set()
    assertions = client_session_assertions(witness, uuid, destination, source)
    assertions.add("uuid_preserved")
    if (isinstance(arrival.get("ticks"), int) and isinstance(later.get("ticks"), int) and later["ticks"] > arrival["ticks"] + 20):
        assertions.add("post_arrival_tick")
    if route == "END_GATEWAY":
        synthetic = _client_synthetic(card, uuid, "minecraft:player", route, witness, gateway=_client_gateway(witness))
        assertions |= headless_gateway_assertions(synthetic, "minecraft:player", rest_source=True)
        assertions |= client_player_gateway_owner_assertions(synthetic, assertions, uuid)
        return assertions
    if not client_player_generation_ok(card, arrival, route):
        return assertions
    assertions |= {"source_retired", "destination_unique", "tracking_unique"}
    assertions |= observer_assertions(card, "minecraft:player", route, uuid, window=card.data.get("txRange"))
    return assertions


def client_cooldown_assertions(card: Card, route: str) -> set[str]:
    """A player that arrived through a portal is standing in this portal during its arrival cooldown: it must stay put."""
    witness = card.data.get("witness")
    if card.data.get("outcome") != "STAYED" or not isinstance(witness, dict):
        return set()
    source = ROUTE_SOURCE_WORLD[route]
    series, held = witness.get("series"), _obs(witness, "held")
    first = _members(witness, "source", "minecraft:player")
    members = _members(witness, "held", "minecraft:player")
    probe = _single(members)
    if (not isinstance(series, list) or len(series) < 12 or probe is None or _single(first) is None
            or any(not isinstance(sample, list) or len(sample) != 4 or sample[1] != source for sample in series)):
        return set()
    # The state the proof is conditional on: a positive arrival cooldown, constructed by a real arrival, read back live.
    try:
        constructed = int(str(series[0][2]).rstrip("b").strip()) > 0
    except ValueError:
        constructed = False
    client = held.get("client") if held else None
    if (not constructed or probe.get("world") != source or probe.get("removed") is not False
            or not isinstance(client, dict) or client.get("dimension") != source or client.get("connected") is not True
            or client.get("screen") is not None):
        return set()
    events = client_window_events(card)
    uuid = str(probe.get("uuid", "")).lower()
    if events is None or any(str(event.get("uuid", "")).lower() == uuid and event.get("generation", 0) > 0
                             and event.get("route") == route for event in events):
        return set()
    assertions = {"source_unchanged", "destination_absent", "portal_session_absent", "source_unchanged_or_safely_retired",
                  "no_destination_entity", "no_stuck_session"}
    # The player is a graph of one node before and while it is held: no vehicle, no passenger, same live object.
    start = _single(first)
    if (start.get("uuid") == probe.get("uuid") and start.get("object") == probe.get("object")
            and all(node.get("vehicleUuid") in (None, "UNAVAILABLE") and node.get("passengerUuids") == []
                    for node in (start, probe))):
        assertions.add("graph_unchanged")
    return assertions


def client_graph_assertions(card: Card, shape: str, route: str) -> tuple[set[str], bool]:
    witness = card.data.get("witness")
    if card.data.get("outcome") != "TRANSFERRED" or not isinstance(witness, dict) or shape not in PGRAPH_SHAPES:
        return set(), False
    members = {key: _members(witness, key) for key in ("source", "arrival", "later", "final")}
    if any(value is None for value in members.values()):
        return set(), False
    synthetic = dataclasses.replace(card, data=dict(
        card.data, witness=dict(members, counts=witness.get("counts"), gateway=_client_gateway(witness))))
    assertions, owners = headless_graph_assertions(synthetic, shape, route)
    player = next((member for member in members["arrival"] if member.get("type") == "minecraft:player"), None)
    if player is None:
        return set(), False
    uuid = str(player["uuid"]).lower()
    root = next((member for member in members["arrival"] if member.get("vehicleUuid") in (None, "UNAVAILABLE")), None)
    destination = ROUTE_DESTINATION_WORLD[route]
    source_player = _single(_members(witness, "source", "minecraft:player"))
    if source_player is not None and root is not None:
        assertions |= client_session_assertions(witness, uuid, destination, source_player)
        client_later = _obs(witness, "later")
        client_player = ((client_later or {}).get("client") or {}).get("player") or {}
        # The player is still seated in the same vehicle on the real client after the hand-over.
        if (client_player.get("vehicleUuid") is not None
                and str(client_player.get("vehicleUuid")).lower() == str(root.get("uuid")).lower()):
            assertions.add("client_seated")
    return assertions, owners


def client_item_assertions(card: Card, route: str) -> set[str]:
    witness = card.data.get("witness")
    if card.data.get("outcome") != "TRANSFERRED" or not isinstance(witness, dict):
        return set()
    arrival = _single(_members(witness, "arrival", "minecraft:item"))
    uuid = str((arrival or {}).get("uuid", "")).lower()
    if arrival is None or not uuid:
        return set()
    synthetic = _client_synthetic(card, uuid, "minecraft:item", route, witness, gateway=_client_gateway(witness))
    if route == "END_GATEWAY":
        assertions = headless_gateway_assertions(synthetic, "minecraft:item", rest_source=True)
    else:
        assertions = headless_transfer_assertions(synthetic, "minecraft:item", route)
        assertions |= observer_assertions(card, "minecraft:item", route, uuid, window=card.data.get("txRange"))
    carried = str(arrival.get("item", ""))
    if "diamond" in carried and arrival.get("count") == 5:
        before = (_obs(witness, "picked") or {}).get("client") or {}
        after_inventory = (before.get("inventory") or {}) if isinstance(before, dict) else {}
        loose = _members(witness, "picked", "minecraft:item")
        if (witness.get("diamonds") == 5 and after_inventory.get("minecraft:diamond") == 5 and loose == []
                and ((witness.get("inventoryBefore") or {}).get("minecraft:diamond", 0) == 0)):
            assertions |= {"item_count_preserved", "native_pickup"}
    return assertions


def client_pearl_assertions(card: Card, route: str) -> set[str]:
    witness = card.data.get("witness")
    if card.data.get("outcome") != "TRANSFERRED" or not isinstance(witness, dict):
        return set()
    arrival = _single(_members(witness, "arrival", "minecraft:ender_pearl"))
    uuid = str((arrival or {}).get("uuid", "")).lower()
    if arrival is None or not uuid:
        return set()
    synthetic = _client_synthetic(card, uuid, "minecraft:ender_pearl", route, witness, gateway=_client_gateway(witness))
    if route == "END_GATEWAY":
        assertions = headless_gateway_assertions(synthetic, "minecraft:ender_pearl", rest_source=True, ends_on_impact=True,
                                                 later_optional=True)
    else:
        assertions = headless_transfer_assertions(synthetic, "minecraft:ender_pearl", route)
        assertions |= observer_assertions(card, "minecraft:ender_pearl", route, uuid, window=card.data.get("txRange"))
    source = _single(_members(witness, "source", "minecraft:ender_pearl"))
    if (source is not None and str(source.get("uuid", "")).lower() == uuid and arrival.get("type") == "minecraft:ender_pearl"
            and arrival.get("world") == ROUTE_DESTINATION_WORLD[route]):
        assertions.add("projectile_identity")
    teleport = witness.get("teleport")
    owner = str(witness.get("owner", "")).lower().strip('"')
    if (witness.get("teleported") is True and isinstance(teleport, dict) and teleport.get("dimension") == ROUTE_DESTINATION_WORLD[route]
            and owner):
        client = ((_obs(witness, "afterTeleport") or {}).get("client") or {})
        player = client.get("player") or {}
        if client.get("dimension") == ROUTE_DESTINATION_WORLD[route] and client.get("connected") is True and player:
            assertions.add("later_player_teleport")
    return assertions


def client_bobber_assertions(card: Card, route: str) -> set[str]:
    """A bobber cast by the real client and carried into the portal cell: it is visited by the block and never uses it."""
    witness = card.data.get("witness")
    if card.data.get("outcome") != "STAYED" or not isinstance(witness, dict) or witness.get("cast", {}).get("source") != 1:
        return set()
    carried = _single(_members(witness, "carried", "minecraft:fishing_bobber"))
    settled = _single(_members(witness, "settled", "minecraft:fishing_bobber"))
    series = witness.get("series")
    if carried is None or settled is None or not isinstance(series, list) or len(series) < 6:
        return set()
    uuid = str(carried.get("uuid", "")).lower()
    # The rod is the native cast of the connected player: exactly one bobber in the source world, none in the destination, at
    # every sample of the dwell (a bobber that had used the portal would be counted on the destination side or twice).
    if any(not isinstance(sample, list) or len(sample) != 3 or sample[1] != 1 or sample[2] not in (0, None)
           for sample in series) or witness.get("cast", {}).get("destination") not in (0, None):
        return set()
    # The bobber is the same live object at the portal cell throughout, and (like any entity that cannot use portals) it was
    # still visited by the portal block.
    synthetic = dataclasses.replace(card, data=dict(card.data, details="UUID=" + uuid, outcome="STAYED", witness={
        "source": [dict(carried, vehicleUuid=None, passengerUuids=[])],
        "settled": [dict(settled, vehicleUuid=None, passengerUuids=[])],
        "noEntryContactEvents": _client_gateway(witness)["noEntry"]}))
    return headless_negative_assertions(synthetic, "minecraft:fishing_bobber", route)


def client_claims(card: Card, rows: list[dict[str, Any]] | None = None) -> list[dict[str, Any]]:
    match = CLIENT_CARD.fullmatch(card.name)
    if match is None:
        return []
    kind, route = match.groups()
    outcome = card.data.get("outcome")
    common = dict(card=card, route=route, basicObservation=True, scope="row")
    gateway = route == "END_GATEWAY"
    transfer = "SAME_DIMENSION_TRANSFER" if gateway else "TRANSFER"
    if kind in ("player", "credits_unseen"):
        assertions = client_player_assertions(card, route, credits_unseen=kind == "credits_unseen")
        needed = (REQUIRED_SAME_DIMENSION_ASSERTIONS if gateway else REQUIRED_TRANSFER_ASSERTIONS) | {"session_converged", "client_playable"}
        if needed <= assertions:
            return [dict(common, entityType="minecraft:player", variant=None if kind == "player" else "credits_unseen",
                         stateVariant=None if kind == "player" else "credits_unseen", graph="standalone",
                         observedOutcome=transfer, assertions=assertions, observerValidated=OWNER_ASSERTIONS <= assertions,
                         sameDimensionGateway=gateway)]
        return []
    if kind == "player_cooldown":
        assertions = client_cooldown_assertions(card, route)
        row = next((candidate for candidate in rows or [] if candidate.get("entityType") == "minecraft:player"
                    and candidate.get("route") == route and candidate.get("variant") == "portal_cooldown"), None)
        proof = row.get("sourceProof") if isinstance(row, dict) else None
        needed = {"source_unchanged", "destination_absent", "portal_session_absent", "source_unchanged_or_safely_retired",
                  "no_destination_entity", "no_stuck_session"}
        if not isinstance(proof, dict) or row.get("expectedNativeOutcome") != "REFUSAL" or not needed <= assertions:
            return []
        applicability = {"sourceProofKind": proof.get("kind"), "sourceJarSha256": proof.get("sourceJarSha256"),
                         "mappedBinarySha256": proof.get("mappedBinarySha256")}
        return [dict(common, entityType="minecraft:player", variant="portal_cooldown", stateVariant="portal_cooldown",
                     graph="standalone", observedOutcome="REFUSAL", applicability=applicability, assertions=assertions,
                     stateConstructed=True)]
    if kind in PLAYER_GRAPH_KIND_ROW:
        row_graph = PLAYER_GRAPH_KIND_ROW[kind]
        if kind in CREDITS_GRAPH_VEHICLE:
            # Credits-unseen vehicle graphs: the vehicle carrying a credits-unseen player never leaves the End.
            return client_credits_graph_claims(card, kind, route, row_graph, rows)
        if kind not in PGRAPH_SHAPES:
            return []
        assertions, owners = client_graph_assertions(card, kind, route)
        needed = ((REQUIRED_SAME_DIMENSION_ASSERTIONS if gateway else REQUIRED_TRANSFER_ASSERTIONS)
                  | {"graph_nodes_exact", "graph_edges_exact", "no_dual_addressability", "session_converged", "client_playable"})
        if needed - OWNER_ASSERTIONS - {"destination_owner"} <= assertions and owners:
            return [dict(common, entityType=None, variant=None, graph=row_graph, observedOutcome=transfer,
                         assertions=assertions | OWNER_ASSERTIONS, observerValidated=True, headlessOwner=True,
                         sameDimensionGateway=gateway)]
        return []
    if kind == "item":
        assertions = client_item_assertions(card, route)
        needed = (REQUIRED_SAME_DIMENSION_ASSERTIONS if gateway else REQUIRED_TRANSFER_ASSERTIONS) | {"item_count_preserved", "native_pickup"}
        if needed <= assertions:
            return [dict(common, entityType="minecraft:item", variant=None, graph="standalone", observedOutcome=transfer,
                         assertions=assertions, observerValidated=OWNER_ASSERTIONS <= assertions, sameDimensionGateway=gateway,
                         ownerAssertions=OWNER_ASSERTIONS & assertions or None)]
        return []
    if kind in ("ender_pearl", "ender_pearl_override"):
        assertions = client_pearl_assertions(card, route)
        needed = (REQUIRED_SAME_DIMENSION_ASSERTIONS if gateway else REQUIRED_TRANSFER_ASSERTIONS) | {"projectile_identity", "later_player_teleport"}
        override = kind == "ender_pearl_override"
        if needed <= assertions:
            return [dict(common, entityType="minecraft:ender_pearl", variant="passenger_override" if override else None,
                         stateVariant="passenger_override" if override else None, graph="standalone", observedOutcome=transfer,
                         assertions=assertions, observerValidated=OWNER_ASSERTIONS <= assertions, sameDimensionGateway=gateway,
                         ownerAssertions=OWNER_ASSERTIONS & assertions or None)]
        return []
    if kind == "fishing_bobber":
        assertions = client_bobber_assertions(card, route)
        row = next((candidate for candidate in rows or [] if candidate.get("entityType") == "minecraft:fishing_bobber"
                    and candidate.get("route") == route and candidate.get("variant") in (None, "")), None)
        proof = row.get("sourceProof") if isinstance(row, dict) else None
        if (not isinstance(proof, dict) or row.get("expectedNativeOutcome") != "NO_NATIVE_ENTRY_PATH"
                or not {"source_unchanged", "destination_absent", "portal_session_absent", "graph_unchanged"} <= assertions):
            return []
        cell = HEADLESS_PORTAL_CELL[route]
        applicability = {
            "sourceWorld": ROUTE_SOURCE_WORLD[route], "destinationWorld": ROUTE_DESTINATION_WORLD[route],
            "portalBlock": ROUTE_PORTAL_BLOCK[route], "sourceProofKind": proof.get("kind"),
            "portalX": cell[0], "portalY": cell[1], "portalZ": cell[2],
            "sourceJarSha256": proof.get("sourceJarSha256"), "mappedBinarySha256": proof.get("mappedBinarySha256")}
        return [dict(common, entityType="minecraft:fishing_bobber", variant=None, graph="standalone",
                     observedOutcome="NO_NATIVE_ENTRY_PATH", applicability=applicability, assertions=assertions)]
    return []


def client_credits_graph_claims(card: Card, kind: str, route: str, row_graph: str, rows: list[dict[str, Any]] | None
                                ) -> list[dict[str, Any]]:
    witness = card.data.get("witness")
    if card.data.get("outcome") != "STAYED" or route != "END_EXIT" or not isinstance(witness, dict):
        return []
    vehicle_type = CREDITS_GRAPH_VEHICLE[kind]
    source = _members(witness, "source")
    settled = _members(witness, "settled")
    counts = witness.get("counts") or {}
    if (source is None or settled is None or counts.get("total") != 2 or counts.get("destination") != 0
            or counts.get("source") != 2 or len(source) != 2 or len(settled) != 2):
        return []
    key = lambda members: sorted((str(member.get("uuid", "")).lower(), member.get("type"), member.get("object"),
                                  str(member.get("vehicleUuid")).lower(), tuple(member.get("passengerUuids", [])))
                                 for member in members)
    server_before = (_obs(witness, "source") or {}).get("server", {})
    if (key(source) != key(settled) or sorted(member.get("type") for member in source) != sorted([vehicle_type, "minecraft:player"])
            or any(member.get("world") != "minecraft:the_end" or member.get("removed") is not False for member in settled)
            or server_before.get("seenCredits") != "0b"):
        return []
    client = ((_obs(witness, "settled") or {}).get("client") or {})
    if client.get("dimension") != "minecraft:the_end" or client.get("connected") is not True or client.get("screen") is not None:
        return []
    events = client_window_events(card)
    uuids = {str(member.get("uuid", "")).lower() for member in settled}
    mine = [event for event in events or [] if str(event.get("uuid", "")).lower() in uuids and event.get("generation", 0) > 0]
    # The vehicle visits the exit portal and Phoenium settles it as the native no-transfer: the attempt is on record and never
    # reaches a source retirement, a destination insertion or a restore.
    if (events is None or any(event.get("violation") for event in mine)
            or any(event.get("phase") in ("SOURCE_RETIRING", "SOURCE_RETIRED", "ADD_DURING_TELEPORT_HEAD", "CREATE", "RESTORE",
                                          "DESTINATION_INSERTING", "DESTINATION_ADMITTED") for event in mine)
            or sum(1 for event in mine if event.get("phase") == "SETTLED_NATIVE_NO_TRANSFER") != 1):
        return []
    assertions = {"source_unchanged_or_safely_retired", "no_destination_entity", "no_stuck_session", "graph_unchanged",
                  "source_unchanged", "destination_absent"}
    if all(member.get("portalProcessPresent") is False for member in settled):
        # no portal session outlived the attempt: the settled graph holds no portal process and nothing was retired or inserted
        assertions.add("portal_session_absent")
    return [dict(card=card, route=route, basicObservation=True, scope="row", entityType=vehicle_type,
                 variant="credits_unseen_passenger", stateVariant="credits_unseen_passenger", graph=row_graph,
                 observedOutcome="REFUSAL", assertions=assertions, stateConstructed=True)]


def explicit_claims(card: Card) -> tuple[list[dict[str, Any]], list[str]]:
    metadata = card.data.get("coverage")
    if metadata is None or metadata == []:
        return [], []
    # A receipt controls its own strings. Until an exact reviewed card contract
    # derives assertions from snapshots and trace phases, these strings cannot
    # certify a census row even when the installed product/test hashes match.
    return [], [f"unreviewed explicit coverage metadata on {card.name}"]


def proof_valid(row: dict[str, Any], source_sha: str, binary_sha: str) -> bool:
    proof = row.get("sourceProof")
    reference = proof.get("reference") if isinstance(proof, dict) else None
    reference_valid = (
        isinstance(reference, dict)
        and isinstance(reference.get("file"), str) and bool(reference["file"])
        and isinstance(reference.get("method"), str) and bool(reference["method"])
        and isinstance(reference.get("line"), int) and reference["line"] > 0
        and valid_sha(reference.get("sourceMethodSha256"))
        and valid_sha(reference.get("classBytecodeSha256"))
    ) or isinstance(reference, str) and bool(reference)
    return (isinstance(proof, dict)
            and proof.get("sourceJarSha256", "").upper() == source_sha
            and proof.get("mappedBinarySha256", "").upper() == binary_sha
            and proof.get("nativeOutcome") == row.get("expectedNativeOutcome")
            and proof.get("applicabilityDemonstrated") is True
            and reference_valid
            and isinstance(proof.get("eligibilityPredicate"), str) and bool(proof["eligibilityPredicate"]))


def silverfish_source_chain_ok(row: dict[str, Any], source_sha: str, binary_sha: str) -> bool:
    """Bind the two M2 rows to their exact pinned native caller references."""
    if row.get("id") not in SILVERFISH_IDS or row.get("entityType") != "minecraft:silverfish":
        return False
    if row.get("id") != "V2-SILVERFISH-" + str(row.get("route")):
        return False
    proof = row.get("sourceProof")
    source_reference = row.get("sourceReference")
    methods = source_reference.get("callerMethods") if isinstance(source_reference, dict) else None
    fixture = row.get("fixture")
    if (not isinstance(proof, dict) or not isinstance(methods, dict)
            or not isinstance(fixture, dict) or not isinstance(row.get("eligibility"), dict)):
        return False
    tick = methods.get("tick")
    handle = methods.get("handlePortal")
    reference = proof.get("reference")
    tick_method = tick.get("method") if isinstance(tick, dict) else None
    handle_method = handle.get("method") if isinstance(handle, dict) else None
    if not isinstance(tick_method, dict) or not isinstance(handle_method, dict):
        return False
    return (proof.get("kind") == "conditional_silverfish_prefix_nether_transfer"
            and proof.get("sourceJarSha256") == source_sha
            and proof.get("mappedBinarySha256") == binary_sha
            and proof.get("nativeOutcome") == "TRANSFER"
            and proof.get("registeredTypeId") == "minecraft:silverfish"
            and proof.get("concreteClass") == "net.minecraft.world.entity.monster.Silverfish"
            and proof.get("tickReference") == tick
            and proof.get("baseTickReference") == methods.get("baseTick")
            and proof.get("eligibilityReference") == methods.get("canUsePortal")
            and isinstance(tick, dict) and isinstance(handle, dict)
            and tick.get("classBytecodeSha256") ==
                "523DB646F5430AA9FB04D6A67B278730F7F1033BD79D0B9D3EBE50191490288C"
            and tick_method.get("sourceSha256") ==
                "B08A64A733B29CE38F7AAA98DCF7743DE77F3DFDA754ECBCA4842C9D3B46BC9C"
            and tick_method.get("lexicalSuffixSha256") ==
                "D1E893A89B088DA316832FB74914D9E3870B957CA7BB0E0B478D1D8559A93D85"
            and tick_method.get("lexicalSuffixBytes") == 5
            and isinstance(reference, dict)
            and reference.get("file") == handle.get("file")
            and reference.get("method") == "handlePortal"
            and reference.get("line") == handle_method.get("line")
            and reference.get("sourceMethodSha256") == handle_method.get("sourceSha256")
            and reference.get("classBytecodeSha256") == handle.get("classBytecodeSha256")
            and fixture.get("contactImpossible") is False
            and fixture.get("requiredPredicates") == proof.get("eligibilityPredicate"))


def native_control_valid(row: dict[str, Any], source_sha: str, binary_sha: str) -> bool:
    control = row.get("nativeControlReceipt")
    return (isinstance(control, dict)
            and control.get("sourceJarSha256", "").upper() == source_sha
            and control.get("mappedBinarySha256", "").upper() == binary_sha
            and control.get("observedOutcome") == row.get("expectedNativeOutcome")
            and isinstance(control.get("path"), str) and bool(control["path"]))


# REFUSAL rows whose pinned proof is conditional on a state the card constructs and reads back live (creaking heart bond,
# a player's arrival cooldown); the native control must have refused the same state.
STATE_CONSTRUCTED_REFUSAL_VARIANTS = ("heart_bound", "portal_cooldown", "credits_unseen_passenger")
SLEEPING_NA_KIND = "sleeping_pose_excludes_portal_contact"
PORTAL_BLOCK_STEP = re.compile(
    r"rcon:execute in minecraft:overworld run setblock (-?\d+) (-?\d+) (-?\d+) minecraft:(nether_portal|end_portal)(?:\[.*\])?")


def sleeping_observation_verdict(entries: Any) -> list[dict[str, Any]] | None:
    """Read a real-client exploration (`explore-output.json`) of the sleeping state.

    For each `setblock ... nether_portal|end_portal` step: the read-backs since the previous such step must show that the
    player really slept in exactly that cell (SleepTimer > 0, sleeping_pos = the cell, Pos = the bed height inside it), and
    the read-backs after it (up to the next bed being built) that the player woke at once (SleepTimer 0). Returns one verdict per
    replaced bed cell, or None when the transcript is not that exploration."""
    if not isinstance(entries, list) or not all(isinstance(entry, dict) for entry in entries):
        return None

    def values(first: int, last: int, name: str, pattern: str) -> list[str]:
        found = []
        for item in entries[first:last]:
            step, value = item.get("step"), item.get("value")
            if isinstance(step, str) and step == "rcon:data get entity PhoeniumTest0 " + name and isinstance(value, str):
                data = re.fullmatch(r"PhoeniumTest0 has the following entity data: (.*)", value.strip())
                if data and re.fullmatch(pattern, data.group(1)):
                    found.append(data.group(1))
        return found

    stops = [(index, match) for index, match in
             ((index, PORTAL_BLOCK_STEP.fullmatch(str(entry.get("step")))) for index, entry in enumerate(entries)) if match]
    verdicts: list[dict[str, Any]] = []
    previous = 0
    for position, (index, match) in enumerate(stops):
        following = next((later for later in range(index + 1, len(entries)) if "red_bed" in str(entries[later].get("step"))),
                         len(entries))   # the read-backs after the portal block end where the next bed is built
        cell = [int(part) for part in match.groups()[:3]]
        timer = values(previous, index, "SleepTimer", r"\d+s")
        sleeping_pos = values(previous, index, "sleeping_pos", r"\[I; -?\d+, -?\d+, -?\d+\]")
        at = values(previous, index, "Pos", r"\[-?[\d.]+d, -?[\d.]+d, -?[\d.]+d\]")
        awake = values(index + 1, following, "SleepTimer", r"\d+s")
        dimension = values(index + 1, following, "Dimension", r'"minecraft:[a-z_]+"')
        previous = index + 1   # the latest read-back before the next portal block is the one taken while that bed slept
        if not (timer and sleeping_pos and at and len(awake) >= 2 and dimension):
            return None
        slept_cell = [int(part) for part in re.findall(r"-?\d+", sleeping_pos[-1])]
        x, y, z = (float(part.rstrip("d")) for part in re.findall(r"-?[\d.]+d", at[-1]))
        verdicts.append({
            "block": match.group(4), "cell": cell,
            "asleepBefore": int(timer[-1].rstrip("s")) > 0 and slept_cell == cell,
            "positionInCell": [math.floor(x), math.floor(y), math.floor(z)] == cell and abs(y - (cell[1] + 0.6875)) < 1e-6,
            "wokeAtOnce": all(int(item.rstrip("s")) == 0 for item in awake[-2:]),
            "dimensionAfter": dimension[-1].strip('"'),
        })
    if sorted(verdict["block"] for verdict in verdicts) != ["end_portal", "nether_portal"]:
        return None
    return verdicts


def sleeping_na_proof_ok(row: dict[str, Any], source_sha: str, binary_sha: str) -> bool:
    """The ROUTE_NOT_APPLICABLE proof of a sleeping-player row: pinned source plus the same native observation on the
    Phoenium-free control and on W1 and W4, every verdict constructed and identical."""
    proof = row.get("sourceProof")
    if not isinstance(proof, dict) or proof.get("kind") != SLEEPING_NA_KIND or not proof_valid(row, source_sha, binary_sha):
        return False
    observations = proof.get("observations")
    if (not isinstance(observations, list) or not all(isinstance(item, dict) for item in observations)
            or sorted(str(item.get("mode")) for item in observations) != ["W1", "W4", "vanilla"]):
        return False
    reference = observations[0].get("verdicts")
    for item in observations:
        verdicts = item.get("verdicts")
        if (not isinstance(verdicts, list) or len(verdicts) != 2 or not valid_sha(item.get("sha256")) or verdicts != reference
                or not all(isinstance(v, dict) and v.get("asleepBefore") is True and v.get("positionInCell") is True
                           and v.get("wokeAtOnce") is True for v in verdicts)):
            return False
    return True


def exact_source_only_no_entry(row: dict[str, Any]) -> bool:
    proof = row.get("sourceProof")
    return (row.get("id") in LEASH_IDS
            and row.get("legacyCaseId") == row.get("id", "").removeprefix("V2-")
            and row.get("entityType") == "minecraft:leash_knot"
            and row.get("expectedNativeOutcome") == "NO_NATIVE_ENTRY_PATH"
            and row.get("requiredAssertions") == []
            and isinstance(proof, dict)
            and proof.get("kind") == LEASH_PROOF_KIND
            and proof.get("artifact") == "LEASH_KNOT_STABLE_NO_ENTRY_SOURCE_PROOF.json"
            and proof.get("artifactSha256") == LEASH_PROOF_SHA)


def required_assertions(row: dict[str, Any], expected: str) -> set[str]:
    result = set(row.get("requiredAssertions") or [])
    if expected == "TRANSFER":
        result |= REQUIRED_TRANSFER_ASSERTIONS
    elif expected == "SAME_DIMENSION_TRANSFER":
        result |= REQUIRED_SAME_DIMENSION_ASSERTIONS
    elif expected == "REFUSAL":
        result |= REQUIRED_REFUSAL_ASSERTIONS
    if expected in ("TRANSFER", "SAME_DIMENSION_TRANSFER"):
        entity_type = row.get("entityType")
        graph = row.get("graph") or "standalone"
        if graph != "standalone":
            result |= {"graph_nodes_exact", "graph_edges_exact", "no_dual_addressability"}
            if "player" in graph:
                result |= {"session_converged", "client_playable"}
        if entity_type == "minecraft:item":
            result |= {"item_count_preserved", "native_pickup"}
        elif entity_type == "minecraft:falling_block":
            result |= {"source_block_removed", "carried_state", "native_landing"}
            if row.get("route") == "END_ENTRY":
                result.add("end_platform")
        elif entity_type == "minecraft:player":
            result |= {"session_converged", "client_playable"}
        elif entity_type == "minecraft:tnt":
            result |= {"fuse_preserved", "native_explosion"}
        elif entity_type in PROJECTILE_REQUIREMENTS:
            result |= PROJECTILE_REQUIREMENTS[entity_type]
        elif isinstance(entity_type, str) and (
                "boat" in entity_type.rsplit(":", 1)[-1]
                or "raft" in entity_type.rsplit(":", 1)[-1]
                or "minecart" in entity_type.rsplit(":", 1)[-1]):
            result |= {"vehicle_state_preserved", "post_arrival_tick"}
            if ("chest_boat" in entity_type or "chest_raft" in entity_type
                    or entity_type == "minecraft:chest_minecart"):
                result |= {"inventory_preserved", "no_loose_item_duplication"}
        elif isinstance(entity_type, str) and entity_type not in ("minecraft:marker",):
            result.add("post_arrival_tick")
    return result


def claim_matches(row: dict[str, Any], claim: dict[str, Any]) -> bool:
    if claim.get("caseId") is not None and claim["caseId"] != row["id"]:
        return False
    if row.get("entityType") != claim["entityType"] or row.get("route") != claim["route"]:
        return False
    if row.get("graph", "standalone") != claim["graph"]:
        return False
    applicability = claim.get("applicability")
    if applicability is not None:
        proof = row.get("sourceProof")
        if (not isinstance(applicability, dict) or not isinstance(proof, dict)
                or applicability.get("sourceProofKind") != proof.get("kind")
                or applicability.get("sourceJarSha256") != proof.get("sourceJarSha256")
                or applicability.get("mappedBinarySha256") != proof.get("mappedBinarySha256")):
            return False
    variant = row.get("variant")
    if claim.get("stateVariant") is not None:
        # A state-variant card (heart-bound creaking...) credits ONLY the row of exactly that state, never the plain row.
        return variant == claim["stateVariant"]
    return variant in (None, "") or variant == claim.get("variant")


def apply_claims(row: dict[str, Any], claims: list[dict[str, Any]], candidate_sha: str,
                 source_sha: str, binary_sha: str) -> dict[str, Any]:
    row = copy.deepcopy(row)
    expected = row.get("expectedNativeOutcome")
    receipts: dict[str, list[dict[str, Any]]] = {"W1": [], "W4": []}
    supporting: list[dict[str, Any]] = []
    observed: set[str] = set()
    assertions: set[str] = set()
    basic_by_worker: set[int] = set()
    owner_by_worker: set[int] = set()
    state_by_worker: set[int] = set()
    for claim in claims:
        if not claim_matches(row, claim):
            continue
        card: Card = claim["card"]
        verified_assertions = set(claim["assertions"])
        if not claim.get("observerValidated"):
            verified_assertions -= OWNER_ASSERTIONS
        reference = card.reference(verified_assertions, claim["scope"])
        if claim["scope"] == "supporting":
            supporting.append(reference)
            continue
        receipts[f"W{card.workers}"].append(reference)
        observed.add(claim["observedOutcome"])
        assertions.update(verified_assertions)
        if claim["basicObservation"]:
            basic_by_worker.add(card.workers)
        if claim.get("stateConstructed"):
            state_by_worker.add(card.workers)
        if OWNER_ASSERTIONS <= verified_assertions:
            owner_by_worker.add(card.workers)
    row["receipts"] = receipts
    row["supportingReceipts"] = supporting
    row["finalCandidateSha256"] = candidate_sha if receipts["W1"] or receipts["W4"] else None
    row["observedPhoeniumResult"] = next(iter(observed)) if len(observed) == 1 else None
    row["witnessedAssertions"] = sorted(assertions)
    row["disposition"] = None
    silverfish = row.get("id") in SILVERFISH_IDS
    if (silverfish and basic_by_worker == {1, 4} and owner_by_worker == {1, 4}
            and observed == {"TRANSFER"}
            and REQUIRED_TRANSFER_ASSERTIONS | {"post_arrival_tick", "uuid_preserved",
                                                "replacement_identity", "client_tracking"} <= assertions
            and silverfish_source_chain_ok(row, source_sha, binary_sha)):
        row["sourceProof"]["applicabilityDemonstrated"] = True
        row["eligibility"]["resolvedForFixture"] = True
        row["fixture"]["constructed"] = True
    if (expected == "REFUSAL" and row.get("variant") in STATE_CONSTRUCTED_REFUSAL_VARIANTS and state_by_worker == {1, 4}
            and observed == {"REFUSAL"} and isinstance(row.get("sourceProof"), dict)
            and native_control_valid(row, source_sha, binary_sha)):
        # The state the pinned proof is conditional on (Creaking.isHeartBound()) was constructed and read back on W1 and W4,
        # and the native control observed the same refusal for the same state.
        row["sourceProof"]["applicabilityDemonstrated"] = True
        if isinstance(row.get("fixture"), dict):
            row["fixture"]["constructed"] = True
        if isinstance(row.get("eligibility"), dict):
            row["eligibility"]["resolvedForFixture"] = True
    reasons = []
    if expected not in OUTCOMES:
        reasons.append("native outcome unresolved or invalid")
    elif len(observed) > 1:
        reasons.append("conflicting observed outcomes")
    elif observed and observed != {expected}:
        reasons.append("observed PHOENIUM outcome differs from native outcome")
    else:
        proof = proof_valid(row, source_sha, binary_sha)
        contact_impossible = isinstance(row.get("fixture"), dict) and row["fixture"].get("contactImpossible") is True
        proof_only_assertions = required_assertions(row, expected)
        if (expected == "NO_NATIVE_ENTRY_PATH" and proof and contact_impossible
                and not proof_only_assertions and exact_source_only_no_entry(row)):
            row["observedPhoeniumResult"] = expected
            row["disposition"] = DISPOSITIONS[expected]
            row["finalCandidateSha256"] = candidate_sha
            row["evidenceScope"] = "pinned_source_only; stable native contact impossible"
            row["witnessedAssertions"] = ["stable_fence_occupancy_excludes_portal_contact"]
        elif (expected == "REFUSAL" and proof and contact_impossible
              and not proof_only_assertions and native_control_valid(row, source_sha, binary_sha)):
            row["observedPhoeniumResult"] = expected
            row["disposition"] = DISPOSITIONS[expected]
        elif (expected == "ROUTE_NOT_APPLICABLE" and proof and contact_impossible and not proof_only_assertions
              and sleeping_na_proof_ok(row, source_sha, binary_sha)):
            row["observedPhoeniumResult"] = expected
            row["disposition"] = DISPOSITIONS[expected]
            row["finalCandidateSha256"] = candidate_sha
            row["evidenceScope"] = "pinned_source_and_native_W1_W4_observation; sleeping pose cannot be in portal contact"
            row["witnessedAssertions"] = ["sleeping_pose_excludes_portal_contact"]
        else:
            if basic_by_worker != {1, 4}:
                reasons.append("missing basic final-byte W1/W4 observation")
            if observed != {expected}:
                reasons.append("missing matching observed PHOENIUM outcome")
            missing = required_assertions(row, expected) - assertions
            if missing:
                reasons.append("missing semantic assertions: " + ", ".join(sorted(missing)))
            if expected in ("REFUSAL", "NO_NATIVE_ENTRY_PATH", "ROUTE_NOT_APPLICABLE") and not proof:
                reasons.append("missing applicable pinned native source proof")
            if (expected == "REFUSAL" and row.get("variant") in STATE_CONSTRUCTED_REFUSAL_VARIANTS
                    and not native_control_valid(row, source_sha, binary_sha)):
                reasons.append("missing native control receipt for the constructed state")
            if (expected in ("TRANSFER", "SAME_DIMENSION_TRANSFER") and row.get("nativeControlReceipt") is not None
                    and not native_control_valid(row, source_sha, binary_sha)):
                reasons.append("native control receipt does not match the native outcome")
            if silverfish and not (silverfish_source_chain_ok(row, source_sha, binary_sha) and proof):
                reasons.append("missing exact applicable Silverfish pinned native source proof")
            if contact_impossible and proof_only_assertions and not (receipts["W1"] and receipts["W4"]):
                reasons.append("source proof does not satisfy required final-byte negative assertions")
            if not reasons:
                row["disposition"] = DISPOSITIONS[expected]
                if silverfish:
                    row["evidenceScope"] = "pinned_source_and_exact_final_byte_W1_W4"
    row["unresolvedReasons"] = reasons
    return row


def validate_census(census: dict[str, Any], frozen: dict[str, Any], source_sha: str,
                    binary_sha: str, census_dir: pathlib.Path | None = None) -> None:
    rows = census.get("rows")
    if not isinstance(rows, list) or not rows:
        raise ValueError("v2 census rows missing")
    if census.get("sourceJarSha256", "").upper() != source_sha:
        raise ValueError("pinned native source hash differs from census")
    if census.get("mappedBinarySha256", "").upper() != binary_sha:
        raise ValueError("pinned mapped binary hash differs from census")
    required_ids = frozen["required_case_ids"]
    if len(required_ids) != 843 or len(set(required_ids)) != 843:
        raise ValueError("frozen 843-ID inventory changed")
    mapping = census.get("frozenIdMapping")
    if not isinstance(mapping, dict) or set(mapping) != set(required_ids):
        raise ValueError("frozen-to-v2 mapping incomplete or changed")
    by_id = {row.get("id"): row for row in rows if isinstance(row, dict)}
    if len(by_id) != len(rows):
        raise ValueError("duplicate or malformed v2 row ID")
    if any(row_id not in by_id or by_id[row_id].get("legacyCaseId") != old_id
           for old_id, row_id in mapping.items()):
        raise ValueError("frozen ID mapping does not point to its exact v2 row")
    if any(row.get("route") not in ROUTES for row in rows):
        raise ValueError("unclassified route in v2 census")
    source_only = {row.get("id") for row in rows if exact_source_only_no_entry(row)}
    if source_only and source_only != LEASH_IDS:
        raise ValueError("incomplete exact leash-knot source-only set")
    if any(row.get("expectedNativeOutcome") == "NO_NATIVE_ENTRY_PATH"
           and row.get("requiredAssertions") == [] and row.get("id") not in source_only
           for row in rows):
        raise ValueError("unsupported source-only no-entry row")
    if census_dir is not None:
        root = census_dir.resolve(strict=True)
        if source_only:
            artifact = root / "LEASH_KNOT_STABLE_NO_ENTRY_SOURCE_PROOF.json"
            if digest(artifact) != LEASH_PROOF_SHA:
                raise ValueError("leash-knot source-only proof hash mismatch")
            proof = read_json(artifact)
            if (proof.get("kind") != LEASH_PROOF_KIND
                    or set(proof.get("caseIds", [])) != LEASH_IDS
                    or proof.get("sourceJarSha256") != source_sha
                    or proof.get("mappedBinarySha256") != binary_sha
                    or proof.get("coverageCreditedByThisArtifact") is not False):
                raise ValueError("leash-knot source-only proof content mismatch")
        for row in rows:
            proof = row.get("sourceProof")
            if isinstance(proof, dict) and proof.get("kind") == SLEEPING_NA_KIND:
                for item in proof.get("observations", []):
                    relative = pathlib.Path(str(item.get("path", "")))
                    if relative.is_absolute() or not relative.parts or ".." in relative.parts:
                        raise ValueError(f"unsafe sleeping observation path: {row.get('id')}")
                    observed = (root / relative).resolve(strict=True)
                    if (not observed.is_relative_to(root) or digest(observed) != item.get("sha256")
                            or sleeping_observation_verdict(read_json(observed)) != item.get("verdicts")):
                        raise ValueError(f"sleeping observation changed or does not show the state: {row.get('id')}")
            control = row.get("nativeControlReceipt")
            if control is None:
                continue
            relative = pathlib.Path(control.get("path", ""))
            if relative.is_absolute() or not relative.parts or ".." in relative.parts:
                raise ValueError(f"unsafe native control path: {row.get('id')}")
            receipt = (root / relative).resolve(strict=True)
            if not receipt.is_relative_to(root) or digest(receipt) != control.get("sha256"):
                raise ValueError(f"native control receipt hash mismatch: {row.get('id')}")
            result = read_json(receipt)
            inputs = read_json(receipt.with_name("inputs.json"))
            if (result.get("pass") is not True or inputs.get("mods") != []
                    or inputs.get("sourceJarSha256") != source_sha
                    or inputs.get("mappedBinarySha256") != binary_sha
                    or control.get("sourceJarSha256") != source_sha
                    or control.get("mappedBinarySha256") != binary_sha):
                raise ValueError(f"native control oracle mismatch: {row.get('id')}")
            if control.get("card") is not None:
                # Headless control: the named census card in the sibling receipt must show exactly this outcome.
                receipt_path = receipt.with_name("headless-receipt.json")
                if (digest(receipt_path) != control.get("receiptSha256")
                        or digest(receipt.with_name("inputs.json")) != control.get("inputsSha256")
                        or inputs.get("vanilla") is not True):
                    raise ValueError(f"headless native control files changed: {row.get('id')}")
                cards = {item.get("card"): item.get("outcome") for item in read_json(receipt_path).get("cards", [])}
                if {"TRANSFER": "TRANSFERRED", "SAME_DIMENSION_TRANSFER": "TRANSFERRED", "REFUSAL": "STAYED"}.get(
                        control.get("observedOutcome")) != cards.get(control["card"]):
                    raise ValueError(f"headless native control card outcome mismatch: {row.get('id')}")


def build_report(census: dict[str, Any], frozen: dict[str, Any], candidate_sha: str,
                 source_sha: str, binary_sha: str, cards: list[Card], rejected: list[dict[str, str]],
                 worktree: pathlib.Path, contracts: dict[str, str]) -> dict[str, Any]:
    claims: list[dict[str, Any]] = []
    metadata_errors: list[dict[str, str]] = []
    ancillary: list[dict[str, Any]] = []
    for card in cards:
        # Census-shaped cards: the server-headless runs and the real-client K1 cards of client_closeout.py.
        census_shaped = card.evidence == "server-headless" or (
            card.evidence == "real-client" and card.result_path.name == "headless-result.json" and CLIENT_CARD.fullmatch(card.name))
        legacy = (headless_claims(card, census["rows"]) if census_shaped
                  else legacy_claims(card, worktree, contracts))
        explicit, errors = explicit_claims(card) if not census_shaped else ([], [])
        claims.extend(legacy)
        claims.extend(explicit)
        metadata_errors.extend({"card": card.name, "path": str(card.receipt_path), "reason": error} for error in errors)
        if not legacy and not explicit:
            family = ("respawn" if RESPAWN_CARD.fullmatch(card.name) else
                      "local_pearl" if card.name == "I4" else
                      "native_respawn" if card.name == "I7" else
                      "restart" if card.name == "I8" else None)
            if family and legacy_contract_ok(worktree, contracts, family):
                ancillary.append(card.reference(scope=family))
    for claim in claims:
        observed_owner = (OWNER_ASSERTIONS.copy() if claim.get("headlessOwner") else
                          set(claim["ownerAssertions"]) if claim.get("ownerAssertions") else
                          OWNER_ASSERTIONS.copy() if claim.get("sameDimensionGateway")
                          and OWNER_ASSERTIONS <= gateway_pig_assertions(claim["card"]) else
                          set() if claim.get("observedOutcome") in ("NO_NATIVE_ENTRY_PATH", "REFUSAL") else
                          observer_assertions(claim["card"], claim["entityType"], claim["route"])
                          if isinstance(claim["entityType"], str) else
                          graph_observer_assertions(claim["card"], claim["route"])
                          if claim.get("graph") == "mob riding mob" else set())
        claim["assertions"].update(observed_owner)
        if (SILVERFISH_CARD.fullmatch(claim["card"].name)
                and OWNER_ASSERTIONS <= observed_owner):
            # The raw full-level snapshots prove the old incarnation vanished;
            # the matching trace proves retirement and one tracking admission.
            claim["assertions"].update({"source_retired", "tracking_unique"})
        if (claim["entityType"] == "minecraft:falling_block"
                and claim["route"] == "END_ENTRY" and OWNER_ASSERTIONS <= observed_owner):
            claim["assertions"].update(falling_source_retired_assertions(claim["card"]))
        claim["observerValidated"] = bool(observed_owner)
    rows = [apply_claims(row, claims, candidate_sha, source_sha, binary_sha) for row in census["rows"]]
    disposition_counts = collections.Counter({name: 0 for name in (*DISPOSITIONS.values(), "UNRESOLVED")})
    disposition_counts.update(row["disposition"] or "UNRESOLVED" for row in rows)
    mapping = census["frozenIdMapping"]
    by_id = {row["id"]: row for row in rows}
    frozen_dispositions = {old_id: by_id[mapping[old_id]]["disposition"] for old_id in frozen["required_case_ids"]}
    generated = [row["id"] for row in rows if row.get("legacyCaseId") is None]
    return {
        "schemaVersion": 2,
        "candidateSha256": candidate_sha,
        "sourceJarSha256": source_sha,
        "mappedBinarySha256": binary_sha,
        "frozenCaseCount": len(mapping),
        "generatedCaseCount": len(generated),
        "generatedCaseIds": generated,
        "dispositionCounts": dict(sorted(disposition_counts.items())),
        "unresolvedCount": disposition_counts["UNRESOLVED"],
        "complete": disposition_counts["UNRESOLVED"] == 0 and not metadata_errors,
        "frozenIdMapping": mapping,
        "frozenDispositions": frozen_dispositions,
        "creditedCardCount": len({(claim["card"].name, str(claim["card"].receipt_path)) for claim in claims}
                                 | {(card["card"], card["receiptPath"]) for card in ancillary}),
        "ancillaryCards": ancillary,
        "rejectedRuns": rejected,
        "metadataErrors": metadata_errors,
        "rows": rows,
    }


def markdown_report(report: dict[str, Any]) -> str:
    lines = ["# Stage 7 portal coverage", "",
             f"Candidate SHA-256: `{report['candidateSha256']}`. Pinned source SHA-256: `{report['sourceJarSha256']}`.", "",
             f"Frozen rows: {report['frozenCaseCount']}; generated rows: {report['generatedCaseCount']}; "
             f"unresolved: {report['unresolvedCount']}; complete: **{report['complete']}**.", "",
             "## Dispositions", ""]
    for key, count in report["dispositionCounts"].items():
        lines.append(f"- {key}: {count}")
    lines.extend(["", "## First unresolved rows", "",
                  "| V2 row | Old ID | Route | Native | Observed | W1 | W4 | Reason |",
                  "|---|---|---|---|---|---:|---:|---|"])
    for row in (row for row in report["rows"] if row["disposition"] is None):
        lines.append("| {id} | {old} | {route} | {native} | {observed} | {w1} | {w4} | {reason} |".format(
            id=row["id"], old=row.get("legacyCaseId") or "", route=row["route"],
            native=row.get("expectedNativeOutcome") or "UNKNOWN",
            observed=row.get("observedPhoeniumResult") or "NOT_EXERCISED",
            w1=len(row["receipts"]["W1"]), w4=len(row["receipts"]["W4"]),
            reason="; ".join(row["unresolvedReasons"]).replace("|", "\\|")).rstrip())
    lines.extend(["", "## Evidence intake", "",
                  f"Credited card receipts: {report['creditedCardCount']}.",
                  f"Ancillary passing cards: {len(report['ancillaryCards'])}.",
                  f"Rejected installed runs: {len(report['rejectedRuns'])}.",
                  f"Card metadata errors: {len(report['metadataErrors'])}.", ""])
    for rejected in report["rejectedRuns"][:30]:
        lines.append(f"- Rejected `{rejected['path']}`: {rejected['reason']}")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--census", type=pathlib.Path, required=True)
    parser.add_argument("--frozen", type=pathlib.Path, required=True)
    parser.add_argument("--candidate", type=pathlib.Path, required=True)
    parser.add_argument("--native-source", type=pathlib.Path, required=True)
    parser.add_argument("--mapped-binary", type=pathlib.Path, required=True)
    parser.add_argument("--worktree", type=pathlib.Path, required=True)
    parser.add_argument("--installed-root", type=pathlib.Path, action="append", required=True)
    parser.add_argument("--legacy-contracts", type=pathlib.Path,
                        default=pathlib.Path(__file__).with_name("portal_legacy_card_contracts.json"))
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--gate", action="store_true")
    args = parser.parse_args(argv)
    try:
        candidate_sha = digest(args.candidate)
        source_sha = digest(args.native_source)
        binary_sha = digest(args.mapped_binary)
        census, frozen, contracts = map(read_json, (args.census, args.frozen, args.legacy_contracts))
        validate_census(census, frozen, source_sha, binary_sha, args.census.parent)
        global CONTROL_ROOT
        CONTROL_ROOT = args.census.parent.resolve()
        cards, rejected = scan_installed(args.installed_root, candidate_sha, args.worktree)
        headless_cards, headless_rejected = scan_headless(args.installed_root, candidate_sha, args.worktree)
        cards, rejected = cards + headless_cards, rejected + headless_rejected
        report = build_report(census, frozen, candidate_sha, source_sha, binary_sha,
                              cards, rejected, args.worktree, contracts)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        args.output.with_suffix(".md").write_text(markdown_report(report), encoding="utf-8")
        print(json.dumps({key: report[key] for key in ("frozenCaseCount", "generatedCaseCount",
                                                "dispositionCounts", "unresolvedCount", "complete",
                                                "creditedCardCount")}, indent=2))
        if args.gate and not report["complete"]:
            print("PORTAL COVERAGE GATE FAILED: unresolved rows or invalid card metadata", file=sys.stderr)
            return 1
        return 0
    except (OSError, KeyError, TypeError, ValueError) as error:
        print(f"PORTAL COVERAGE INPUT ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
