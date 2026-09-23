"""Failure-oriented checks for the final-byte Stage 7 coverage gate."""

from __future__ import annotations

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
