"""Failure-oriented checks for the final-byte Stage 7 coverage gate."""

from __future__ import annotations

import copy
import dataclasses
import hashlib
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch

import build_portal_coverage as coverage


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def row(expected: str = "TRANSFER") -> dict:
    return {
        "id": "V2-ITEM-NETHER_ENTRY",
        "legacyCaseId": "ITEM-NETHER_ENTRY",
        "entityType": "minecraft:item",
        "variant": None,
        "route": "NETHER_ENTRY",
        "graph": "standalone",
        "expectedNativeOutcome": expected,
        "sourceProof": None,
        "nativeControlReceipt": None,
        "fixture": None,
    }


def card(name: str, workers: int, phase: str = "first", data: dict | None = None) -> coverage.Card:
    return coverage.Card(name, workers, phase, pathlib.Path("receipt.json"),
                         pathlib.Path("result.json"), pathlib.Path("inputs.json"), data or {})


def claim(name: str, workers: int, assertions: set[str], outcome: str = "TRANSFER") -> dict:
    return {
        "card": card(name, workers), "entityType": "minecraft:item", "route": "NETHER_ENTRY",
        "variant": None, "graph": "standalone", "observedOutcome": outcome,
        "assertions": assertions, "basicObservation": True, "scope": "row",
    }


class CoverageTests(unittest.TestCase):
    def test_silverfish_m2_claim_requires_exact_raw_source_arrival_and_later(self) -> None:
        name = "M2-silverfish-NETHER_ENTRY"
        uuid = "12345678-1234-1234-1234-123456789abc"
        source = {
            "uuid": uuid, "type": "minecraft:silverfish", "world": "minecraft:overworld",
            "object": "source", "nativeId": 17, "ticks": 0, "health": 8.0,
            "removed": False, "portalCooldown": 0, "x": 0.5, "y": 65.0, "z": 2.5,
            "vehicleUuid": "UNAVAILABLE", "passengerUuids": [],
            "tags": "[stage7_portal_mob_0, stage7_mob]",
        }
        arrival = dict(source, world="minecraft:the_nether", object="replacement",
                       nativeId=27, ticks=1, portalCooldown=300)
        later = dict(arrival, ticks=27, portalCooldown=274)
        receipt = {
            "selectedCards": "",
            "observations": [
                {"label": name + "-source", "entities": [source]},
                {"label": name + "-wait", "entities": [arrival]},
                {"label": name + "-later", "entities": [later]},
            ],
        }
        witness = dataclasses.replace(card(name, 1, data={"details": "UUID=" + uuid}),
                                      receipt_data=receipt, inputs_data={"group": "mob-prefix"})
        self.assertEqual(coverage.silverfish_snapshot_witness(witness, "NETHER_ENTRY"),
                         (source, arrival, later))
        with patch.object(coverage, "legacy_contract_ok", return_value=True):
            claims = coverage.legacy_claims(witness, pathlib.Path("unused"), {})
        self.assertEqual(len(claims), 1)
        self.assertEqual(claims[0]["caseId"], "V2-SILVERFISH-NETHER_ENTRY")
        self.assertNotIn("source_retired", claims[0]["assertions"])
        for changed in (
            {"observations": [receipt["observations"][0],
                              {"label": name + "-wait", "entities": [arrival, dict(arrival)]},
                              receipt["observations"][2]]},
            {"observations": [receipt["observations"][0],
                              {"label": name + "-wait", "entities": [source, arrival]},
                              receipt["observations"][2]]},
            {"observations": [receipt["observations"][0], receipt["observations"][1],
                              {"label": name + "-later", "entities": [dict(later, ticks=20)]}]},
            {"observations": [receipt["observations"][0],
                              {"label": name + "-wait", "entities": [dict(arrival, uuid="0" * 36)]},
                              receipt["observations"][2]]},
        ):
            forged = dataclasses.replace(witness, receipt_data={**receipt, **changed})
            self.assertIsNone(coverage.silverfish_snapshot_witness(forged, "NETHER_ENTRY"))
        self.assertIsNone(coverage.silverfish_snapshot_witness(
            dataclasses.replace(witness, inputs_data={"group": "legacy"}), "NETHER_ENTRY"))

    def test_silverfish_owner_trace_requires_retirement_and_settled_suffix(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run = pathlib.Path(temp)
            trace = run / "first-portal-transactions.log"
            name = "M2-silverfish-NETHER_ENTRY"
            uuid = "12345678-1234-1234-1234-123456789abc"
            source = {
                "uuid": uuid, "type": "minecraft:silverfish", "world": "minecraft:overworld",
                "object": "old", "nativeId": 17, "ticks": 0, "health": 8.0,
                "removed": False, "portalCooldown": 0, "x": 0.5, "y": 65.0, "z": 2.5,
                "vehicleUuid": "UNAVAILABLE", "passengerUuids": [],
                "tags": "[stage7_portal_mob_0, stage7_mob]",
            }
            arrival = dict(source, world="minecraft:the_nether", object="new",
                           nativeId=27, ticks=1, portalCooldown=300)
            later = dict(arrival, ticks=27)
            receipt = {
                "selectedCards": "",
                "observations": [
                    {"label": name + "-source", "entities": [source]},
                    {"label": name + "-wait", "entities": [arrival]},
                    {"label": name + "-later", "entities": [later]},
                ],
            }
            phases = ("SOURCE_RETIRING", "SOURCE_RETIRED",
                      "ADD_DURING_TELEPORT_HEAD", "CHUNK_MAP_TRACKING_HEAD",
                      "CHUNK_MAP_TRACKING_RETURN", "ADD_DURING_TELEPORT_RETURN",
                      "TRANSFER_SETTLED_AFTER_SUFFIX")
            events = [{
                "phase": phase, "uuid": uuid, "type": "entity.minecraft.silverfish",
                "route": "NETHER_ENTRY", "generation": 7, "sourceWorld": "minecraft:overworld",
                "destinationWorld": ("UNAVAILABLE" if phase == "TRANSFER_SETTLED_AFTER_SUFFIX"
                                     else "minecraft:the_nether"),
                "ownerWorld": ("minecraft:overworld" if phase in ("SOURCE_RETIRING", "SOURCE_RETIRED")
                               else "NONE" if phase == "TRANSFER_SETTLED_AFTER_SUFFIX"
                               else "minecraft:the_nether"),
                "object": "old" if phase in ("SOURCE_RETIRING", "SOURCE_RETIRED",
                                           "TRANSFER_SETTLED_AFTER_SUFFIX") else "new",
                "nativeId": 17 if phase in ("SOURCE_RETIRING", "SOURCE_RETIRED",
                                            "TRANSFER_SETTLED_AFTER_SUFFIX") else 27,
                "candidateSha256": "A" * 64, "testSourceSha256": "B" * 64,
            } for phase in phases]
            result = {
                "txPass": True,
                "txStatus": {"pending": 0, "violations": 0,
                             "candidateSha256": "A" * 64, "testSourceSha256": "B" * 64},
                "candidateSha256": "A" * 64, "txTracePath": str(trace),
            }
            witness = coverage.Card(name, 1, "first", run / "first-client-receipt.json",
                                    run / "first-result.json", run / "inputs.json",
                                    {"details": "UUID=" + uuid}, result, receipt,
                                    {"group": "mob-prefix", "probeSha256": "B" * 64})

            def observed(current: list[dict]) -> set[str]:
                trace.write_text("".join("STAGE7_PORTAL_TX " + json.dumps(event) + "\n"
                                         for event in current), encoding="utf-8")
                result["txTraceSha256"] = coverage.digest(trace)
                coverage._trace_events.cache_clear()
                return coverage.observer_assertions(witness, "minecraft:silverfish", "NETHER_ENTRY")

            self.assertEqual(observed(events), coverage.OWNER_ASSERTIONS)
            self.assertFalse(observed([event for event in events
                                       if event["phase"] != "SOURCE_RETIRED"]))
            self.assertFalse(observed([event for event in events
                                       if event["phase"] != "TRANSFER_SETTLED_AFTER_SUFFIX"]))
            wrong_owner = copy.deepcopy(events)
            wrong_owner[3]["ownerWorld"] = "minecraft:overworld"
            self.assertFalse(observed(wrong_owner))
            wrong_id = copy.deepcopy(events)
            wrong_id[2]["nativeId"] = 28
            self.assertFalse(observed(wrong_id))

    def test_silverfish_coverage_needs_both_owner_witnesses_and_exact_source_proof(self) -> None:
        source_sha, binary_sha, candidate_sha = "A" * 64, "B" * 64, "C" * 64
        tick = {
            "classBytecodeSha256": "523DB646F5430AA9FB04D6A67B278730F7F1033BD79D0B9D3EBE50191490288C",
            "method": {
                "sourceSha256": "B08A64A733B29CE38F7AAA98DCF7743DE77F3DFDA754ECBCA4842C9D3B46BC9C",
                "lexicalSuffixSha256": "D1E893A89B088DA316832FB74914D9E3870B957CA7BB0E0B478D1D8559A93D85",
                "lexicalSuffixBytes": 5,
            },
        }
        handle = {"file": "net/minecraft/world/entity/Entity.java",
                  "classBytecodeSha256": "D" * 64,
                  "method": {"line": 2607, "sourceSha256": "E" * 64}}
        methods = {"tick": tick, "baseTick": {"owner": "net.minecraft.world.entity.Mob"},
                   "canUsePortal": {"owner": "net.minecraft.world.entity.LivingEntity"},
                   "handlePortal": handle}
        reference = {"file": handle["file"], "method": "handlePortal", "line": 2607,
                     "sourceMethodSha256": "E" * 64, "classBytecodeSha256": "D" * 64}
        predicate = "alive awake standalone registered Silverfish in a ready Nether portal"
        target = {
            "id": "V2-SILVERFISH-NETHER_ENTRY", "legacyCaseId": "SILVERFISH-NETHER_ENTRY",
            "entityType": "minecraft:silverfish", "route": "NETHER_ENTRY", "variant": None,
            "graph": "standalone", "expectedNativeOutcome": "TRANSFER",
            "sourceReference": {"callerMethods": methods},
            "sourceProof": {
                "kind": "conditional_silverfish_prefix_nether_transfer",
                "sourceJarSha256": source_sha, "mappedBinarySha256": binary_sha,
                "nativeOutcome": "TRANSFER", "registeredTypeId": "minecraft:silverfish",
                "concreteClass": "net.minecraft.world.entity.monster.Silverfish",
                "tickReference": tick, "baseTickReference": methods["baseTick"],
                "eligibilityReference": methods["canUsePortal"], "reference": reference,
                "eligibilityPredicate": predicate, "applicabilityDemonstrated": False,
            },
            "eligibility": {"resolvedForFixture": False},
            "fixture": {"contactImpossible": False, "requiredPredicates": predicate,
                        "constructed": False},
        }
        assertions = (coverage.REQUIRED_TRANSFER_ASSERTIONS | coverage.OWNER_ASSERTIONS |
                      {"post_arrival_tick", "uuid_preserved", "replacement_identity",
                       "client_tracking"})
        claims = [{
            "card": card("M2-silverfish-NETHER_ENTRY", workers),
            "caseId": target["id"], "entityType": target["entityType"],
            "route": target["route"], "variant": None, "graph": "standalone",
            "observedOutcome": "TRANSFER", "assertions": set(assertions),
            "basicObservation": True, "scope": "row", "observerValidated": True,
        } for workers in (1, 4)]
        resolved = coverage.apply_claims(target, claims, candidate_sha, source_sha, binary_sha)
        self.assertEqual(resolved["disposition"], "TRANSFER_VERIFIED")
        self.assertTrue(resolved["sourceProof"]["applicabilityDemonstrated"])
        self.assertTrue(resolved["fixture"]["constructed"])
        self.assertFalse(target["sourceProof"]["applicabilityDemonstrated"])
        self.assertIsNone(coverage.apply_claims(
            target, claims[:1], candidate_sha, source_sha, binary_sha)["disposition"])
        unowned = copy.deepcopy(claims)
        unowned[1]["observerValidated"] = False
        self.assertIsNone(coverage.apply_claims(
            target, unowned, candidate_sha, source_sha, binary_sha)["disposition"])
        forged = copy.deepcopy(target)
        forged["sourceProof"]["tickReference"]["method"]["sourceSha256"] = "0" * 64
        self.assertIsNone(coverage.apply_claims(
            forged, claims, candidate_sha, source_sha, binary_sha)["disposition"])

    def test_falling_cards_attach_to_exact_material_rows_without_earning_disposition(self) -> None:
        with patch.object(coverage, "legacy_contract_ok", return_value=True):
            for material in ("sand", "gravel", "anvil"):
                witness = card(f"F1-{material}-native-fall-end-entry", 1)
                claims = coverage.legacy_claims(witness, pathlib.Path("unused"), {})
                self.assertEqual(len(claims), 1)
                matched = claims[0]
                target = row()
                target.update({"id": f"V2-FALLING_{material.upper()}-END_ENTRY",
                               "entityType": "minecraft:falling_block",
                               "variant": "minecraft:" + material,
                               "route": "END_ENTRY", "expectedNativeOutcome": None})
                self.assertTrue(coverage.claim_matches(target, matched))
                self.assertNotIn("source_block_removed", matched["assertions"])
                observed = coverage.apply_claims(target, claims, "A" * 64, "B" * 64, "C" * 64)
                self.assertEqual(len(observed["receipts"]["W1"]), 1)
                self.assertEqual(observed["receipts"]["W4"], [])
                self.assertEqual(observed["observedPhoeniumResult"], "TRANSFER")
                self.assertIsNone(observed["disposition"])
                self.assertIn("native outcome unresolved or invalid", observed["unresolvedReasons"])

    def test_falling_source_cell_requires_exact_raw_before_and_after_witness(self) -> None:
        for material in ("sand", "gravel", "anvil"):
            name = f"F1-{material}-native-fall-end-entry"
            uuid = "12345678-1234-1234-1234-123456789abc"
            state = f"Block{{minecraft:{material}}}"
            source = {"uuid": uuid, "object": "source-1", "type": "minecraft:falling_block",
                      "world": "minecraft:overworld", "x": 0.5, "y": 72.96, "z": 0.5,
                      "fallingState": state}
            cell = {"world": "minecraft:overworld", "position": "0 73 0",
                    "material": "minecraft:" + material, "sourceUuid": uuid,
                    "sourceObject": "source-1", "sourceFallingState": state,
                    "beforeCommand": "execute in minecraft:overworld run execute if block 0 73 0 minecraft:" + material,
                    "beforeResponse": "Test passed", "afterCommand":
                    "execute in minecraft:overworld run execute if block 0 73 0 minecraft:air",
                    "afterResponse": "Test passed"}
            receipt = {name + "-source": source, name + "-source-cell": cell}
            witness = coverage.Card(name, 1, "first", pathlib.Path("receipt.json"),
                                    pathlib.Path("result.json"), pathlib.Path("inputs.json"),
                                    {}, receipt_data=receipt)
            self.assertEqual(coverage.falling_source_cell_assertions(witness, material),
                             {"source_block_removed"})
            with patch.object(coverage, "legacy_contract_ok", return_value=True):
                self.assertIn("source_block_removed",
                              coverage.legacy_claims(witness, pathlib.Path("unused"), {})[0]["assertions"])
            for field, bad in (("position", "0 72 0"),
                               ("afterCommand", "execute in minecraft:overworld run execute if block 0 72 0 minecraft:air"),
                               ("afterResponse", "Test failed"),
                               ("sourceUuid", "00000000-0000-0000-0000-000000000000"),
                               ("material", "minecraft:stone")):
                rejected = {**cell, field: bad}
                witness.receipt_data[name + "-source-cell"] = rejected
                self.assertEqual(coverage.falling_source_cell_assertions(witness, material), set(), field)
            witness.receipt_data.pop(name + "-source-cell")
            self.assertEqual(coverage.falling_source_cell_assertions(witness, material), set())

    def test_falling_source_retirement_requires_raw_removal_and_owner_trace(self) -> None:
        name = "F1-sand-native-fall-end-entry"
        uuid = "12345678-1234-1234-1234-123456789abc"
        source = {"uuid": uuid, "object": "source-1", "nativeId": 12,
                  "type": "minecraft:falling_block", "world": "minecraft:overworld",
                  "fallingState": "Block{minecraft:sand}"}
        arrival = {**source, "object": "destination-1", "nativeId": 34,
                   "world": "minecraft:the_end"}
        removed = {**source, "phase": "TRACKING_REMOVE_RETURN", "removed": True,
                   "reason": "CHANGED_DIMENSION"}
        admitted = {**arrival, "phase": "TRACKING_ADD_RETURN", "removed": False,
                    "reason": "null"}
        events = [removed, admitted]
        receipt = {name + "-source": source, name + "-arrival": arrival,
                   "observations": [
                       {"label": name + "-arrival", "entities": [arrival],
                        "fallingEvents": events},
                       {"label": name + "-settled", "entities": [],
                        "fallingEvents": events},
                   ]}
        witness = coverage.Card(name, 1, "first", pathlib.Path("receipt.json"),
                                pathlib.Path("result.json"), pathlib.Path("inputs.json"),
                                {}, receipt_data=receipt)
        self.assertEqual(coverage.falling_source_retired_assertions(witness),
                         {"source_retired"})
        for mutation in (lambda d: d["observations"][0]["fallingEvents"][0].update(reason="DISCARDED"),
                         lambda d: d["observations"][1]["fallingEvents"][0].update(removed=False),
                         lambda d: d["observations"][0]["entities"].append(source),
                         lambda d: d["observations"][0]["fallingEvents"].append(removed),
                         lambda d: d["observations"][1].update(label="wrong-settled"),
                         lambda d: d["observations"][0]["fallingEvents"].reverse()):
            changed = copy.deepcopy(receipt)
            mutation(changed)
            self.assertEqual(coverage.falling_source_retired_assertions(
                dataclasses.replace(witness, receipt_data=changed)), set())

        falling_row = row()
        falling_row.update({"id": "V2-FALLING_SAND-END_ENTRY",
                            "legacyCaseId": "FALLING_SAND-END_ENTRY",
                            "entityType": "minecraft:falling_block", "route": "END_ENTRY",
                            "variant": "minecraft:sand"})
        frozen = {"required_case_ids": ["FALLING_SAND-END_ENTRY"]}
        census = {"rows": [falling_row],
                  "frozenIdMapping": {"FALLING_SAND-END_ENTRY": falling_row["id"]}}
        matched = {"card": witness, "entityType": "minecraft:falling_block",
                   "route": "END_ENTRY", "variant": "minecraft:sand", "graph": "standalone",
                   "observedOutcome": "TRANSFER", "assertions": set(),
                   "basicObservation": True, "scope": "row"}
        with patch.object(coverage, "legacy_claims", return_value=[matched]), \
                patch.object(coverage, "observer_assertions", return_value=coverage.OWNER_ASSERTIONS):
            report = coverage.build_report(census, frozen, "A" * 64, "B" * 64, "C" * 64,
                                           [witness], [], pathlib.Path("unused"), {})
        self.assertIn("source_retired", report["rows"][0]["witnessedAssertions"])
        matched["assertions"] = set()
        with patch.object(coverage, "legacy_claims", return_value=[matched]), \
                patch.object(coverage, "observer_assertions", return_value=set()):
            report = coverage.build_report(census, frozen, "A" * 64, "B" * 64, "C" * 64,
                                           [witness], [], pathlib.Path("unused"), {})
        self.assertNotIn("source_retired", report["rows"][0]["witnessedAssertions"])

    def test_chest_vehicle_rows_require_storage_and_drop_witnesses(self) -> None:
        for entity in ("minecraft:oak_chest_boat", "minecraft:bamboo_chest_raft",
                       "minecraft:chest_minecart"):
            vehicle = row()
            vehicle["entityType"] = entity
            self.assertTrue({"vehicle_state_preserved", "post_arrival_tick",
                             "inventory_preserved", "no_loose_item_duplication"}
                            <= coverage.required_assertions(vehicle, "TRANSFER"))

    def test_gateway_same_dimension_requires_one_existing_native_object(self) -> None:
        pig = row("SAME_DIMENSION_TRANSFER")
        pig.update({"id": "V2-PIG-END_GATEWAY", "legacyCaseId": "PIG-END_GATEWAY",
                    "entityType": "minecraft:pig", "route": "END_GATEWAY"})
        required = coverage.required_assertions(pig, "SAME_DIMENSION_TRANSFER")
        self.assertTrue({"same_native_object", "source_location_vacated", "destination_unique",
                         "gateway_cooldown", "gateway_ticket"} <= required)
        self.assertNotIn("source_retired", required)
        self.assertNotIn("vehicle_state_preserved", required)

    def test_gateway_claim_requires_exact_native_ticket_and_same_object(self) -> None:
        name = coverage.GATEWAY_PIG_CARD
        uuid = "12345678-1234-1234-1234-123456789abc"
        owner = "RegionId[world=WorldId[value=minecraft:the_end], value=stage2-live-world]"
        source_diag = (f"ObjectOwnership[objectId={uuid}, routingKey=RoutingKey["
                       "world=WorldId[value=minecraft:the_end], x=0, z=0], "
                       f"incarnation=0, owner={owner}, state=OWNED]")
        arrival_diag = (f"ObjectOwnership[objectId={uuid}, routingKey=RoutingKey["
                        "world=WorldId[value=minecraft:the_end], x=10, z=10], "
                        f"incarnation=0, owner={owner}, state=OWNED]")
        owner_context = f"Active[region={owner}, lease=LeaseEpoch[value=1]]"
        source = {"uuid": uuid, "type": "minecraft:pig", "world": "minecraft:the_end",
                  "object": "a1", "nativeId": 17, "removed": False,
                  "vehicleUuid": "UNAVAILABLE", "passengerUuids": [],
                  "x": 12.5, "y": 66.0, "z": 8.5, "chunkX": 0, "chunkZ": 0,
                  "ticks": 2, "portalCooldown": 0,
                  "ownershipDiagnostic": source_diag}
        arrival = dict(source, x=160.5, y=65.0, z=160.5, chunkX=10, chunkZ=10,
                       ticks=5, portalCooldown=300,
                       ownershipDiagnostic=arrival_diag)
        later = dict(arrival, ticks=27, portalCooldown=277)
        contact = {"uuid": uuid, "world": "minecraft:the_end", "x": 12, "y": 66, "z": 8,
                   "ownerWorld": "minecraft:the_end", "ownerContext": owner_context}
        ticket = {"uuid": uuid, "world": "minecraft:the_end", "chunkX": 10, "chunkZ": 10,
                  "radius": 3, "ticketType": "minecraft:portal",
                  "ownerWorld": "minecraft:the_end", "ownerContext": owner_context}
        tracking = dict(arrival, phase="TRACKING_UPDATE_RETURN",
                        ownerWorld="minecraft:the_end", ownerContext=owner_context)
        receipt = {name + "-source": source, name + "-arrival": arrival,
                   name + "-later": later, name + "-gatewayContact": contact,
                   name + "-gatewayTicket": ticket, name + "-destinationTracking": tracking,
                   name + "-oldVacancy": True, name + "-clientTracking": True,
                   "observations": [
                       {"label": name + "-wait-1", "entities": [arrival]},
                       {"label": name + "-later-poll", "entities": [later]},
                   ]}
        witness = coverage.dataclasses.replace(card(name, 1), receipt_data=receipt)
        with patch.object(coverage, "legacy_contract_ok", return_value=True):
            claims = coverage.legacy_claims(witness, pathlib.Path("unused"), {})
            self.assertEqual(len(claims), 1)
            self.assertEqual(claims[0]["observedOutcome"], "SAME_DIMENSION_TRANSFER")
            self.assertTrue(coverage.REQUIRED_SAME_DIMENSION_ASSERTIONS
                            <= claims[0]["assertions"])
            for suffix, field, value in (
                ("-gatewayTicket", "ownerWorld", "NONE"),
                ("-gatewayTicket", "ownerContext",
                 "Active[region=RegionId[world=WorldId[value=minecraft:the_end], value=foreign], lease=LeaseEpoch[value=1]]"),
                ("-gatewayTicket", "chunkX", 9),
                ("-destinationTracking", "ownerWorld", "NONE"),
                ("-arrival", "object", "new-object"),
                ("-later", "ticks", 7),
            ):
                tampered = json.loads(json.dumps(receipt))
                tampered[name + suffix][field] = value
                changed = coverage.dataclasses.replace(witness, receipt_data=tampered)
                self.assertFalse(coverage.legacy_claims(changed, pathlib.Path("unused"), {}),
                                 (suffix, field))

    def test_source_only_no_entry_is_exactly_the_pinned_leash_geometry_proof(self) -> None:
        source, binary, candidate = "B" * 64, "C" * 64, "A" * 64
        knot = row("NO_NATIVE_ENTRY_PATH")
        knot.update({"id": "V2-LEASH_KNOT-NETHER_ENTRY",
                     "legacyCaseId": "LEASH_KNOT-NETHER_ENTRY",
                     "entityType": "minecraft:leash_knot",
                     "fixture": {"contactImpossible": True}, "requiredAssertions": [],
                     "sourceProof": {"kind": coverage.LEASH_PROOF_KIND,
                                     "artifact": "LEASH_KNOT_STABLE_NO_ENTRY_SOURCE_PROOF.json",
                                     "artifactSha256": coverage.LEASH_PROOF_SHA,
                                     "sourceJarSha256": source, "mappedBinarySha256": binary,
                                     "nativeOutcome": "NO_NATIVE_ENTRY_PATH",
                                     "reference": "BlockAttachedEntity.tick",
                                     "eligibilityPredicate": "stable fence geometry",
                                     "applicabilityDemonstrated": True}})
        credited = coverage.apply_claims(knot, [], candidate, source, binary)
        self.assertEqual(credited["disposition"], "NO_NATIVE_ENTRY_PATH_VERIFIED")
        self.assertEqual(credited["finalCandidateSha256"], candidate)
        self.assertIn("pinned_source_only", credited["evidenceScope"])
        spoof = dict(knot, entityType="minecraft:interaction")
        self.assertIsNone(coverage.apply_claims(spoof, [], candidate, source, binary)["disposition"])
        spoof = dict(knot, sourceProof=dict(knot["sourceProof"], artifactSha256="0" * 64))
        self.assertIsNone(coverage.apply_claims(spoof, [], candidate, source, binary)["disposition"])

    def test_scan_requires_product_and_current_test_source_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            worktree = root / "worktree"
            source = worktree / "src/clientGameTest/java/Fixture.java"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"test assertion body")
            installed = root / "installed" / "run-w1"
            installed.mkdir(parents=True)
            candidate = installed / "candidate.jar"
            candidate.write_bytes(b"final product bytes")
            product_hash = sha(candidate.read_bytes())
            inputs = {
                "candidate": str(candidate), "candidateSha256": product_hash, "workers": 1,
                "group": "items", "testSourceHashes": {"src\\clientGameTest\\java\\Fixture.java": sha(source.read_bytes())},
            }
            result = {
                "phase": "first", "candidateSha256": product_hash,
                "installedCandidateSha256": product_hash, "pass": True, "clientPass": True,
                "logFailure": False, "normalSave": True, "normalStopRequested": True,
                "serverExitCode": 0, "clientExitCode": 0,
                "sourceWorldChanges": [], "testSourceChanged": [],
            }
            receipt = {"candidateSha256": product_hash, "pass": True, "group": "items",
                       "cards": [{"card": "I2-minecraft:overworld", "result": "PASS"}]}
            for filename, document in (("inputs.json", inputs), ("first-result.json", result),
                                       ("first-client-receipt.json", receipt)):
                (installed / filename).write_text(json.dumps(document), encoding="utf-8")
            cards, rejected = coverage.scan_installed([root / "installed"], product_hash, worktree)
            self.assertEqual([item.name for item in cards], ["I2-minecraft:overworld"])
            self.assertEqual(rejected, [])

            source.write_bytes(b"weakened test assertion body")
            cards, rejected = coverage.scan_installed([root / "installed"], product_hash, worktree)
            self.assertFalse(cards)
            self.assertIn("test source identity", rejected[0]["reason"])

            source.write_bytes(b"test assertion body")
            inputs["cards"] = ["I2-minecraft:the_nether"]
            receipt["selectedCards"] = "I2-minecraft:the_nether"
            (installed / "inputs.json").write_text(json.dumps(inputs), encoding="utf-8")
            (installed / "first-client-receipt.json").write_text(json.dumps(receipt), encoding="utf-8")
            cards, rejected = coverage.scan_installed([root / "installed"], product_hash, worktree)
            self.assertFalse(cards)
            self.assertIn("selected card receipt differs", rejected[0]["reason"])

            inputs["cards"] = []
            receipt.pop("selectedCards")
            (installed / "inputs.json").write_text(json.dumps(inputs), encoding="utf-8")
            (installed / "first-client-receipt.json").write_text(json.dumps(receipt), encoding="utf-8")
            candidate.write_bytes(b"different installed product")
            cards, rejected = coverage.scan_installed([root / "installed"], product_hash, worktree)
            self.assertFalse(cards)
            self.assertIn("installed candidate copy", rejected[0]["reason"])

    def test_legacy_item_player_and_pearl_are_distinct(self) -> None:
        fake_worktree = pathlib.Path("unused")
        with patch.object(coverage, "legacy_contract_ok", return_value=True):
            item = coverage.legacy_claims(card("I2-minecraft:overworld", 1), fake_worktree, {})
            self.assertEqual((item[0]["entityType"], item[0]["route"]),
                             ("minecraft:item", "NETHER_ENTRY"))
            reverse = coverage.legacy_claims(card("I3-3", 4), fake_worktree, {})
            self.assertEqual(reverse[0]["route"], "NETHER_RETURN")
            player = coverage.legacy_claims(card("I1", 1), fake_worktree, {})
            self.assertEqual({item["route"] for item in player}, {"NETHER_ENTRY", "NETHER_RETURN"})
            pearl = coverage.legacy_claims(card("I5-minecraft:overworld", 1), fake_worktree, {})
            self.assertEqual(pearl[0]["scope"], "supporting")
            self.assertFalse(pearl[0]["basicObservation"])
            fireball = coverage.legacy_claims(card("P0-fireball-nether-return", 1), fake_worktree, {})
            self.assertEqual((fireball[0]["entityType"], fireball[0]["route"]),
                             ("minecraft:fireball", "NETHER_RETURN"))
            self.assertNotIn("destination_owner", fireball[0]["assertions"])
            wind = coverage.legacy_claims(card("P1-wind_charge-NETHER_ENTRY", 4), fake_worktree, {})
            self.assertEqual((wind[0]["entityType"], wind[0]["route"]),
                             ("minecraft:wind_charge", "NETHER_ENTRY"))
            self.assertIn("wind_impact_effect", wind[0]["assertions"])
            dragon = coverage.legacy_claims(card("P1-dragon_fireball-NETHER_RETURN", 1), fake_worktree, {})
            self.assertTrue({"dragon_breath_effect", "post_arrival_tick"} <= dragon[0]["assertions"])
            self.assertNotIn("impact_explosion", dragon[0]["assertions"])
            bottle = coverage.legacy_claims(card("P3-experience_bottle-NETHER_RETURN", 4), fake_worktree, {})
            self.assertEqual((bottle[0]["entityType"], bottle[0]["route"]),
                             ("minecraft:experience_bottle", "NETHER_RETURN"))
            self.assertIn("xp_release", bottle[0]["assertions"])
            self.assertFalse(coverage.legacy_claims(card("P3-egg-END_ENTRY", 1), fake_worktree, {}))
            trident = coverage.legacy_claims(card("P2-trident-NETHER_RETURN", 4), fake_worktree, {})
            self.assertEqual((trident[0]["entityType"], trident[0]["route"]),
                             ("minecraft:trident", "NETHER_RETURN"))
            self.assertTrue({"projectile_owner_preserved", "loyalty_effect"} <= trident[0]["assertions"])
            self.assertNotIn("impact_effect", trident[0]["assertions"])
            self.assertFalse(coverage.legacy_claims(card("P1-arrow-NETHER_ENTRY", 1), fake_worktree, {}))

            graph = coverage.legacy_claims(card("G0-pig-chicken-NETHER_ENTRY", 1), fake_worktree, {})
            self.assertEqual(len(graph), 1)
            self.assertEqual(graph[0]["caseId"], "V2-GRAPH_mob-on-mob-NETHER_ENTRY")
            self.assertEqual(graph[0]["graph"], "mob riding mob")
            self.assertTrue({"graph_nodes_exact", "graph_edges_exact", "no_dual_addressability"}
                            <= graph[0]["assertions"])
            self.assertFalse(coverage.legacy_claims(card("G0-pig-chicken-END_ENTRY", 1), fake_worktree, {}))

    def test_graph_owner_witness_requires_both_exact_member_traces(self) -> None:
        graph_card = card("G0-pig-chicken-NETHER_ENTRY", 1)
        graph_card = coverage.dataclasses.replace(graph_card, receipt_data={
            graph_card.name + "-sourcePig": {"uuid": "12345678-1234-1234-1234-123456789abc"},
            graph_card.name + "-sourceChicken": {"uuid": "12345678-1234-1234-1234-123456789def"},
        })
        with patch.object(coverage, "observer_assertions", side_effect=[coverage.OWNER_ASSERTIONS,
                                                                    coverage.OWNER_ASSERTIONS]) as observed:
            self.assertEqual(coverage.graph_observer_assertions(graph_card, "NETHER_ENTRY"),
                             coverage.OWNER_ASSERTIONS)
            self.assertEqual([call.args[1] for call in observed.call_args_list],
                             ["minecraft:pig", "minecraft:chicken"])
        with patch.object(coverage, "observer_assertions", side_effect=[coverage.OWNER_ASSERTIONS, set()]):
            self.assertFalse(coverage.graph_observer_assertions(graph_card, "NETHER_ENTRY"))

    def test_empty_vehicle_claim_requires_bound_snapshots_and_later_ticks(self) -> None:
        name = "V0-oak_boat-NETHER_ENTRY"
        uuid = "12345678-1234-1234-1234-123456789abc"
        source = {"uuid": uuid, "type": "minecraft:oak_boat", "world": "minecraft:overworld",
                  "object": "source", "nativeId": 17, "removed": False, "ticks": 2,
                  "vehicleUuid": "UNAVAILABLE", "passengerUuids": [], "tags": "[stage7_vehicle]"}
        arrival = dict(source, world="minecraft:the_nether", object="destination",
                       nativeId=27, ticks=5)
        later = dict(arrival, ticks=27)
        observations = [
            {"label": name + "-scoped", "entities": [arrival]},
            {"label": name + "-later", "entities": [later]},
        ]
        witness = coverage.dataclasses.replace(card(name, 1), receipt_data={
            name + "-source": source, name + "-arrival": arrival,
            "observations": observations,
        })
        with patch.object(coverage, "legacy_contract_ok", return_value=True):
            claims = coverage.legacy_claims(witness, pathlib.Path("unused"), {})
            self.assertEqual(len(claims), 1)
            self.assertTrue({"vehicle_state_preserved", "post_arrival_tick"} <= claims[0]["assertions"])
            changed = coverage.dataclasses.replace(witness, receipt_data={
                **witness.receipt_data,
                "observations": [observations[0], {"label": name + "-later", "entities": [arrival]}],
            })
            self.assertFalse(coverage.legacy_claims(changed, pathlib.Path("unused"), {}))

    def test_chest_vehicle_claim_requires_raw_storage_and_no_loose_drops(self) -> None:
        name = "V1-oak_chest_boat-items-NETHER_ENTRY"
        uuid = "12345678-1234-1234-1234-123456789abc"
        items = [{"slot": 0, "item": "minecraft:diamond", "count": 7},
                 {"slot": 26, "item": "minecraft:sugar_cane", "count": 3}]
        source = {"uuid": uuid, "type": "minecraft:oak_chest_boat",
                  "world": "minecraft:overworld", "object": "source", "nativeId": 17,
                  "removed": False, "ticks": 0, "tags": "[stage7_vehicle]",
                  "vehicleUuid": "UNAVAILABLE", "passengerUuids": [],
                  "vehicleSlotCount": 27, "vehicleItems": items,
                  "vehicleLootTable": "UNAVAILABLE", "vehicleLootSeed": 0}
        arrival = dict(source, world="minecraft:the_nether", object="destination",
                       nativeId=27, ticks=1)
        later = dict(arrival, ticks=22)
        loose = {key: 0 for key in ("sourceDiamonds", "arrivalDiamonds", "laterDiamonds",
                                    "sourceSugarCane", "arrivalSugarCane", "laterSugarCane")}
        receipt = {name + "-source": source, name + "-arrival": arrival,
                   name + "-later": later, name + "-looseItems": loose,
                   "observations": [{"label": name + "-source", "entities": [source]},
                                    {"label": name + "-wait", "entities": [arrival]},
                                    {"label": name + "-scoped", "entities": [arrival]},
                                    {"label": name + "-later", "entities": [later]}]}
        witness = coverage.dataclasses.replace(card(name, 1), receipt_data=receipt)
        with patch.object(coverage, "legacy_contract_ok", return_value=True):
            claims = coverage.legacy_claims(witness, pathlib.Path("unused"), {})
            self.assertEqual(len(claims), 1)
            self.assertTrue({"inventory_preserved", "no_loose_item_duplication",
                             "vehicle_state_preserved"} <= claims[0]["assertions"])
            tampered = json.loads(json.dumps(receipt))
            tampered[name + "-later"]["vehicleItems"][1]["count"] = 4
            self.assertFalse(coverage.legacy_claims(
                coverage.dataclasses.replace(witness, receipt_data=tampered),
                pathlib.Path("unused"), {}))

            cart_name = "V2-chest_minecart-items-NETHER_ENTRY"
            cart_receipt = json.loads(json.dumps(receipt))
            cart_receipt[cart_name + "-source"] = cart_receipt.pop(name + "-source")
            cart_receipt[cart_name + "-arrival"] = cart_receipt.pop(name + "-arrival")
            cart_receipt[cart_name + "-later"] = cart_receipt.pop(name + "-later")
            cart_receipt[cart_name + "-looseItems"] = cart_receipt.pop(name + "-looseItems")
            for suffix in ("source", "arrival", "later"):
                part = cart_receipt[cart_name + "-" + suffix]
                part["type"] = "minecraft:chest_minecart"
                part["vehicleItems"][1] = {"slot": 26, "item": "minecraft:iron_ingot", "count": 13}
            loose_cart = cart_receipt[cart_name + "-looseItems"]
            for stage in ("source", "arrival", "later"):
                loose_cart[stage + "Iron"] = loose_cart.pop(stage + "SugarCane")
            for state in cart_receipt["observations"]:
                state["label"] = state["label"].replace(name, cart_name)
                state["entities"] = [cart_receipt[cart_name + "-" + stage].copy()
                                     if entity.get("uuid") == uuid else entity
                                     for entity in state["entities"]
                                     for stage in (["source"] if state["label"].endswith("-source") else
                                                   ["later"] if state["label"].endswith("-later") else
                                                   ["arrival"])]
            cart = coverage.dataclasses.replace(card(cart_name, 4), receipt_data=cart_receipt)
            cart_claims = coverage.legacy_claims(cart, pathlib.Path("unused"), {})
            self.assertEqual(len(cart_claims), 1)
            self.assertEqual(cart_claims[0]["caseId"], "V2-CHEST_MINECART-NETHER_ENTRY")
            tampered = json.loads(json.dumps(receipt))
            tampered[name + "-looseItems"]["arrivalDiamonds"] = 7
            self.assertFalse(coverage.legacy_claims(
                coverage.dataclasses.replace(witness, receipt_data=tampered),
                pathlib.Path("unused"), {}))
            tampered = json.loads(json.dumps(receipt))
            tampered["observations"][2]["entities"].append(arrival)
            self.assertFalse(coverage.legacy_claims(
                coverage.dataclasses.replace(witness, receipt_data=tampered),
                pathlib.Path("unused"), {}))
            tampered = json.loads(json.dumps(receipt))
            tampered[name + "-later"]["object"] = "third-incarnation"
            self.assertFalse(coverage.legacy_claims(
                coverage.dataclasses.replace(witness, receipt_data=tampered),
                pathlib.Path("unused"), {}))
            tampered = json.loads(json.dumps(receipt))
            tampered["observations"][1]["entities"].append({
                "type": "minecraft:item", "item": "minecraft:diamond", "count": 7})
            self.assertFalse(coverage.legacy_claims(
                coverage.dataclasses.replace(witness, receipt_data=tampered),
                pathlib.Path("unused"), {}))

    def test_negative_witness_requires_same_native_object_and_no_transaction(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run = pathlib.Path(temp)
            trace = run / "first-portal-transactions.log"
            trace.write_text("", encoding="utf-8")
            name = "N0-interaction-NETHER_ENTRY"
            source = {"uuid": "12345678-1234-1234-1234-123456789abc", "type": "minecraft:interaction",
                      "world": "minecraft:overworld", "object": "123a", "nativeId": 17,
                      "removed": False, "vehicleUuid": None, "passengerUuids": [],
                      "portalProcessPresent": False, "x": 0.5, "y": 66.25, "z": 0.5}
            applicability = {"sourceWorld": "minecraft:overworld", "destinationWorld": "minecraft:the_nether",
                             "portalBlock": "minecraft:nether_portal", "portalX": 0, "portalY": 66,
                             "portalZ": 0, "sourceProofKind": "tick_override_skips_portal_processing",
                             "sourceJarSha256": "B" * 64, "mappedBinarySha256": "C" * 64}
            result = {"txPass": True, "txStatus": {"pending": 0, "violations": 0,
                                                   "candidateSha256": "A" * 64,
                                                   "testSourceSha256": "B" * 64},
                      "candidateSha256": "A" * 64, "txTracePath": str(trace),
                      "txTraceSha256": coverage.digest(trace)}
            fixture = coverage.Card(name, 1, "first", run / "receipt.json", run / "first-result.json",
                                    run / "inputs.json", {}, result,
                                    {name + "-source": source,
                                     name + "-applicability": applicability,
                                     name + "-settledState": {"entities": [source.copy()],
                                                              "noEntryContactEvents": []}},
                                    {"probeSha256": "B" * 64})
            required = {"source_unchanged", "destination_absent", "portal_session_absent", "graph_unchanged"}
            self.assertEqual(coverage.negative_assertions(fixture, "minecraft:interaction", "NETHER_ENTRY"), required)
            duplicate = coverage.dataclasses.replace(fixture, receipt_data={
                name + "-source": source,
                name + "-applicability": applicability,
                name + "-settledState": {"entities": [source.copy(), source.copy()],
                                         "noEntryContactEvents": []},
            })
            self.assertFalse(coverage.negative_assertions(duplicate, "minecraft:interaction", "NETHER_ENTRY"))
            trace.write_text("STAGE7_PORTAL_TX " + json.dumps({"uuid": source["uuid"]}) + "\n", encoding="utf-8")
            coverage._trace_events.cache_clear()
            result["txTraceSha256"] = coverage.digest(trace)
            self.assertNotIn("portal_session_absent",
                             coverage.negative_assertions(fixture, "minecraft:interaction", "NETHER_ENTRY"))

    def test_native_guard_negative_requires_exact_portal_contact(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run = pathlib.Path(temp)
            trace = run / "first-portal-transactions.log"
            trace.write_text("", encoding="utf-8")
            name = "N0-wither-NETHER_ENTRY"
            uuid = "12345678-1234-1234-1234-123456789abc"
            source = {"uuid": uuid, "type": "minecraft:wither", "world": "minecraft:overworld",
                      "object": "123a", "nativeId": 17, "removed": False,
                      "vehicleUuid": "UNAVAILABLE", "passengerUuids": [],
                      "portalProcessPresent": False, "x": 0.5, "y": 66.25, "z": 0.5}
            applicability = {"sourceWorld": "minecraft:overworld", "destinationWorld": "minecraft:the_nether",
                             "portalBlock": "minecraft:nether_portal", "portalX": 0, "portalY": 66,
                             "portalZ": 0, "sourceProofKind": "unconditional_canUsePortal_false",
                             "sourceJarSha256": "B" * 64, "mappedBinarySha256": "C" * 64}
            result = {"txPass": True, "txStatus": {"pending": 0, "violations": 0,
                                                   "candidateSha256": "A" * 64,
                                                   "testSourceSha256": "B" * 64},
                      "candidateSha256": "A" * 64, "txTracePath": str(trace),
                      "txTraceSha256": coverage.digest(trace)}
            receipt = {name + "-source": source, name + "-applicability": applicability,
                       name + "-settledState": {"entities": [source.copy()], "noEntryContactEvents": []}}
            fixture = coverage.Card(name, 1, "first", run / "receipt.json", run / "first-result.json",
                                    run / "inputs.json", {}, result, receipt, {"probeSha256": "B" * 64})
            self.assertNotIn("portal_session_absent",
                             coverage.negative_assertions(fixture, "minecraft:wither", "NETHER_ENTRY"))
            receipt[name + "-settledState"]["noEntryContactEvents"].append({
                "uuid": uuid, "type": "minecraft:wither", "world": "minecraft:overworld",
                "block": "minecraft:nether_portal", "x": 0, "y": 66, "z": 0,
            })
            self.assertIn("portal_session_absent",
                          coverage.negative_assertions(fixture, "minecraft:wither", "NETHER_ENTRY"))

    def test_report_lists_zero_disposition_categories(self) -> None:
        item = row()
        census = {"rows": [item], "frozenIdMapping": {"ITEM-NETHER_ENTRY": item["id"]}}
        frozen = {"required_case_ids": ["ITEM-NETHER_ENTRY"]}
        report = coverage.build_report(census, frozen, "A" * 64, "B" * 64, "C" * 64,
                                       [], [], pathlib.Path("unused"), {})
        self.assertEqual(report["dispositionCounts"]["UNRESOLVED"], 1)
        self.assertEqual({key: report["dispositionCounts"][key] for key in coverage.DISPOSITIONS.values()},
                         {key: 0 for key in coverage.DISPOSITIONS.values()})

    def test_two_worker_transfer_still_needs_owner_assertion(self) -> None:
        weak = {"source_retired", "destination_unique", "tracking_unique"}
        claims = [claim("I2-minecraft:overworld", 1, weak),
                  claim("I2-minecraft:overworld", 4, weak)]
        result = coverage.apply_claims(row(), claims, "A" * 64, "B" * 64, "C" * 64)
        self.assertIsNone(result["disposition"])
        self.assertEqual(result["observedPhoeniumResult"], "TRANSFER")
        self.assertIn("destination_owner", " ".join(result["unresolvedReasons"]))

        strong = weak | {"destination_owner", "item_count_preserved", "native_pickup"}
        claims = [claim("new-card", 1, strong), claim("new-card", 4, strong)]
        for item in claims:
            item["observerValidated"] = True
        result = coverage.apply_claims(row(), claims, "A" * 64, "B" * 64, "C" * 64)
        self.assertEqual(result["disposition"], "TRANSFER_VERIFIED")
        self.assertEqual(result["finalCandidateSha256"], "A" * 64)
        fireball_row = row()
        fireball_row["entityType"] = "minecraft:fireball"
        self.assertIn("impact_explosion", coverage.required_assertions(fireball_row, "TRANSFER"))
        graph_row = row()
        graph_row["entityType"] = None
        graph_row["graph"] = "boat with player rider"
        self.assertTrue({"graph_nodes_exact", "graph_edges_exact", "session_converged"}
                        <= coverage.required_assertions(graph_row, "TRANSFER"))

    def test_no_native_entry_path_needs_pinned_applicability_proof(self) -> None:
        item = row("NO_NATIVE_ENTRY_PATH")
        item["fixture"] = {"contactImpossible": True}
        first = coverage.apply_claims(item, [], "A" * 64, "B" * 64, "C" * 64)
        self.assertIsNone(first["disposition"])
        item["sourceProof"] = {
            "sourceJarSha256": "B" * 64, "mappedBinarySha256": "C" * 64,
            "nativeOutcome": "NO_NATIVE_ENTRY_PATH", "applicabilityDemonstrated": True,
            "reference": {"file": "Entity.java", "method": "canUsePortal", "line": 19,
                          "sourceMethodSha256": "D" * 64, "classBytecodeSha256": "E" * 64},
            "eligibilityPredicate": "type override",
        }
        second = coverage.apply_claims(item, [], "A" * 64, "B" * 64, "C" * 64)
        self.assertIsNone(second["disposition"])
        self.assertIn("missing basic final-byte W1/W4 observation", second["unresolvedReasons"])
        item["requiredAssertions"] = ["no_destination_entity"]
        third = coverage.apply_claims(item, [], "A" * 64, "B" * 64, "C" * 64)
        self.assertIsNone(third["disposition"])
        self.assertIn("source proof does not satisfy", " ".join(third["unresolvedReasons"]))

    def test_unreviewed_explicit_card_cannot_claim_any_assertions(self) -> None:
        data = {"coverage": [{"caseId": "V2-ITEM-NETHER_ENTRY", "entityType": "minecraft:item",
                              "route": "NETHER_ENTRY", "nativeOutcome": "REFUSAL",
                              "observedOutcome": "TRANSFER", "assertions": ["destination_unique"],
                              "basicObservation": True}]}
        claims, errors = coverage.explicit_claims(card("new-item-card", 1, data=data))
        self.assertFalse(claims)
        self.assertTrue(errors)
        graph_data = {"coverage": [{"caseId": "V2-GRAPH_mob-on-mob-NETHER_ENTRY",
                                    "entityType": None, "graph": "mob riding mob",
                                    "route": "NETHER_ENTRY", "nativeOutcome": "TRANSFER",
                                    "observedOutcome": "TRANSFER", "assertions": ["graph_edges"],
                                    "basicObservation": True}]}
        graph_claims, graph_errors = coverage.explicit_claims(card("graph-card", 1, data=graph_data))
        self.assertFalse(graph_claims)
        self.assertIn("unreviewed explicit coverage", graph_errors[0])
        spoof = {"coverage": [{"caseId": "V2-ITEM-NETHER_ENTRY", "entityType": "minecraft:item",
                                "route": "NETHER_ENTRY", "nativeOutcome": "TRANSFER",
                                "observedOutcome": "TRANSFER", "assertions": ["destination_owner"],
                                "basicObservation": True}]}
        self.assertEqual(coverage.explicit_claims(card("spoof", 4, data=spoof))[0], [])
        self.assertEqual(coverage.explicit_claims(card("plain", 1)), ([], []))
        self.assertEqual(coverage.explicit_claims(card("empty", 1, data={"coverage": []})), ([], []))

    def test_legacy_method_change_revokes_card_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            worktree = pathlib.Path(temp)
            source = worktree / coverage.LEGACY_TEST_FILE
            source.parent.mkdir(parents=True)
            source.write_text("class Fixture { void itemCard() { assert true; } }", encoding="utf-8")
            digest = coverage.method_digest(source.read_text(), "itemCard")
            self.assertEqual(digest, coverage.method_digest(source.read_text(), "itemCard"))
            self.assertFalse(coverage.legacy_contract_ok(worktree, {"itemCard": digest}, "item"))
            source.write_text("class Fixture { void itemCard() { assert false; } }", encoding="utf-8")
            self.assertNotEqual(digest, coverage.method_digest(source.read_text(), "itemCard"))

    def test_owner_assertion_requires_complete_matching_transaction_trace(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            run = pathlib.Path(temp)
            trace = run / "first-portal-transactions.log"
            uuid = "12345678-1234-1234-1234-123456789abc"
            phases = ("SOURCE_RETIRING", "ADD_DURING_TELEPORT_HEAD",
                      "CHUNK_MAP_TRACKING_HEAD", "CHUNK_MAP_TRACKING_RETURN",
                      "ADD_DURING_TELEPORT_RETURN")
            events = [{"phase": phase, "uuid": uuid, "type": "entity.minecraft.fireball",
                       "route": "NETHER_RETURN", "generation": 7,
                       "nativeId": 17 if phase == "SOURCE_RETIRING" else 27,
                       "object": "old" if phase == "SOURCE_RETIRING" else "new",
                       "sourceWorld": "minecraft:the_nether",
                       "destinationWorld": "UNAVAILABLE" if phase == "SOURCE_RETIRING" else "minecraft:overworld",
                       "ownerWorld": "minecraft:the_nether" if phase == "SOURCE_RETIRING" else "minecraft:overworld",
                       "candidateSha256": "A" * 64, "testSourceSha256": "B" * 64}
                      for phase in phases]
            trace.write_text("".join("STAGE7_PORTAL_TX " + json.dumps(event) + "\n" for event in events), encoding="utf-8")
            result = {"txPass": True, "txStatus": {"pending": 0, "violations": 0,
                                                   "candidateSha256": "A" * 64,
                                                   "testSourceSha256": "B" * 64},
                      "candidateSha256": "A" * 64, "txTracePath": str(trace),
                      "txTraceSha256": coverage.digest(trace)}
            fixture_card = coverage.Card("P0-fireball-nether-return", 1, "first", run / "receipt.json",
                                         run / "first-result.json", run / "inputs.json",
                                         {"details": "UUID=" + uuid}, result,
                                         {"P0-fireball-nether-return-source": {
                                             "uuid": uuid, "nativeId": 17, "object": "old"},
                                          "P0-fireball-nether-return-arrival": {
                                             "uuid": uuid, "nativeId": 27, "object": "new"}},
                                         {"probeSha256": "B" * 64})
            self.assertIn("destination_owner", coverage.observer_assertions(
                fixture_card, "minecraft:fireball", "NETHER_RETURN"))
            self.assertFalse(coverage.observer_assertions(
                fixture_card, "minecraft:small_fireball", "NETHER_RETURN"))
            forged = coverage.dataclasses.replace(fixture_card, receipt_data={
                **fixture_card.receipt_data,
                "P0-fireball-nether-return-arrival": {
                    "uuid": uuid, "nativeId": 28, "object": "different"}})
            self.assertFalse(coverage.observer_assertions(
                forged, "minecraft:fireball", "NETHER_RETURN"))
            events[2]["ownerWorld"] = "minecraft:the_nether"
            trace.write_text("".join("STAGE7_PORTAL_TX " + json.dumps(event) + "\n" for event in events), encoding="utf-8")
            result["txTraceSha256"] = coverage.digest(trace)
            coverage._trace_events.cache_clear()
            self.assertFalse(coverage.observer_assertions(
                fixture_card, "minecraft:fireball", "NETHER_RETURN"))


if __name__ == "__main__":
    unittest.main()
