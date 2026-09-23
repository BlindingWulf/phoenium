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

    def reference(self, assertions: set[str] | None = None, scope: str = "row") -> dict[str, Any]:
        return {
            "card": self.name,
            "workers": self.workers,
            "phase": self.phase,
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
                        uuid_override: str | None = None) -> set[str]:
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
    matching = [event for event in _trace_events(trace_path)
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


def build_report(census: dict[str, Any], frozen: dict[str, Any], candidate_sha: str,
                 source_sha: str, binary_sha: str, cards: list[Card], rejected: list[dict[str, str]],
                 worktree: pathlib.Path, contracts: dict[str, str]) -> dict[str, Any]:
    claims: list[dict[str, Any]] = []
    metadata_errors: list[dict[str, str]] = []
    ancillary: list[dict[str, Any]] = []
    for card in cards:
        legacy = legacy_claims(card, worktree, contracts)
        explicit, errors = explicit_claims(card)
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
        observed_owner = (OWNER_ASSERTIONS.copy() if claim.get("sameDimensionGateway")
                          and OWNER_ASSERTIONS <= gateway_pig_assertions(claim["card"]) else
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
        cards, rejected = scan_installed(args.installed_root, candidate_sha, args.worktree)
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
