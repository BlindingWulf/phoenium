"""Failure-oriented checks for the server-headless evidence path (scan_headless, witnesses, classification)."""

from __future__ import annotations

import copy
import dataclasses
import hashlib
import json
import pathlib
import tempfile
import unittest

import build_portal_coverage as coverage
import classify_census

CANDIDATE = "A" * 64
PROBE = "B" * 64
UUID = "12345678-1234-1234-1234-123456789abc"
_CARD_COUNTER = 0


def sha_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def trace_event(phase: str, **extra) -> dict:
    event = {"phase": phase, "uuid": UUID, "type": "entity.minecraft.pig", "route": "NETHER_ENTRY", "generation": 1,
             "sourceWorld": "minecraft:overworld", "destinationWorld": "minecraft:the_nether",
             "ownerWorld": "minecraft:the_nether", "object": "new", "nativeId": 9,
             "candidateSha256": CANDIDATE, "testSourceSha256": PROBE}
    event.update(extra)
    return event


def transfer_trace() -> list[dict]:
    return [
        trace_event("SOURCE_RETIRING", ownerWorld="minecraft:overworld", object="old", nativeId=8),
        trace_event("SOURCE_RETIRED", ownerWorld="minecraft:overworld", object="old", nativeId=8),
        trace_event("ADD_DURING_TELEPORT_HEAD"),
        trace_event("CHUNK_MAP_TRACKING_HEAD"),
        trace_event("CHUNK_MAP_TRACKING_RETURN"),
        trace_event("ADD_DURING_TELEPORT_RETURN"),
    ]


def member(world: str, **extra) -> dict:
    base = {"uuid": UUID, "type": "minecraft:pig", "world": world, "object": "new", "nativeId": 9, "removed": False,
            "ticks": 5, "x": 0.5, "y": 65.0, "z": 0.5, "portalProcessPresent": False, "portalCooldown": 0,
            "vehicleUuid": "UNAVAILABLE", "passengerUuids": [], "chunkX": 0, "chunkZ": 0}
    base.update(extra)
    return base


def make_card(directory: pathlib.Path, name: str, outcome: str, witness: dict, events: list[dict],
              type_name: str = "pig") -> coverage.Card:
    # _trace_events is cached by path, so every card gets its own directory (as every real run does).
    global _CARD_COUNTER
    _CARD_COUNTER += 1
    directory = directory / f"card{_CARD_COUNTER}"
    directory.mkdir()
    trace = directory / "headless-portal-transactions.log"
    trace.write_text("".join("[00:00:00] [Server thread/INFO]: STAGE7_PORTAL_TX " + json.dumps(event) + "\n"
                             for event in events), encoding="utf-8")
    result = {"phase": "headless", "candidateSha256": CANDIDATE, "txPass": True, "txTracePath": str(trace),
              "txTraceSha256": coverage.digest(trace),
              "txStatus": {"pending": 0, "violations": 0, "candidateSha256": CANDIDATE, "testSourceSha256": PROBE}}
    data = {"card": name, "outcome": outcome, "witness": witness, "details": "UUID=" + UUID}
    return coverage.Card(name, 1, "headless", directory / "headless-receipt.json", directory / "headless-result.json",
                         directory / "inputs.json", data, result, {"cards": [data]}, {"probeSha256": PROBE},
                         "server-headless")


def good_transfer_witness() -> dict:
    return {"source": [member("minecraft:overworld", object="old", nativeId=8, ticks=0)],
            "arrival": [member("minecraft:the_nether", ticks=5)], "later": [member("minecraft:the_nether", ticks=30)]}


class HeadlessTransferTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def assertions(self, witness: dict, events: list[dict]) -> set[str]:
        card = make_card(self.dir, "E0-pig-NETHER_ENTRY", "TRANSFERRED", witness, events)
        return coverage.headless_transfer_assertions(card, "minecraft:pig", "NETHER_ENTRY")

    def test_complete_transfer_is_credited_and_claimed(self) -> None:
        card = make_card(self.dir, "E0-pig-NETHER_ENTRY", "TRANSFERRED", good_transfer_witness(), transfer_trace())
        assertions = coverage.headless_transfer_assertions(card, "minecraft:pig", "NETHER_ENTRY")
        self.assertEqual(assertions, {"destination_unique", "post_arrival_tick", "source_retired", "uuid_preserved",
                                      "replacement_identity", "tracking_unique"})
        claims = coverage.headless_claims(card, [])
        self.assertEqual(len(claims), 1)
        self.assertEqual((claims[0]["entityType"], claims[0]["route"], claims[0]["observedOutcome"]),
                         ("minecraft:pig", "NETHER_ENTRY", "TRANSFER"))
        self.assertEqual(card.reference()["evidence"], "server-headless")

    def test_second_tagged_member_or_wrong_world_removes_credit(self) -> None:
        witness = good_transfer_witness()
        duplicate = copy.deepcopy(witness)
        duplicate["arrival"].append(member("minecraft:overworld", object="old", nativeId=8))
        self.assertEqual(self.assertions(duplicate, transfer_trace()), set())
        wrong = copy.deepcopy(witness)
        wrong["later"] = [member("minecraft:overworld", ticks=30)]
        self.assertEqual(self.assertions(wrong, transfer_trace()), set())

    def test_stalled_ticks_or_replaced_object_removes_post_arrival_tick(self) -> None:
        stalled = good_transfer_witness()
        stalled["later"] = [member("minecraft:the_nether", ticks=25)]
        self.assertNotIn("post_arrival_tick", self.assertions(stalled, transfer_trace()))
        swapped = good_transfer_witness()
        swapped["later"] = [member("minecraft:the_nether", ticks=40, object="third")]
        self.assertNotIn("post_arrival_tick", self.assertions(swapped, transfer_trace()))

    def test_trace_must_match_arrival_identity_owner_and_single_tracking(self) -> None:
        events = transfer_trace()
        self.assertNotIn("source_retired", self.assertions(
            good_transfer_witness(), [dict(e, object="other") if e["phase"] == "ADD_DURING_TELEPORT_HEAD" else e for e in events]))
        wrong_owner = [dict(e, ownerWorld="minecraft:the_nether") if e["phase"] == "SOURCE_RETIRED" else e for e in events]
        self.assertNotIn("source_retired", self.assertions(good_transfer_witness(), wrong_owner))
        double_tracking = events + [trace_event("CHUNK_MAP_TRACKING_HEAD"), trace_event("CHUNK_MAP_TRACKING_RETURN")]
        self.assertNotIn("tracking_unique", self.assertions(good_transfer_witness(), double_tracking))
        no_replacement = [dict(e, object="same", nativeId=9) if e["phase"] in ("SOURCE_RETIRING", "SOURCE_RETIRED") else e
                          for e in events]
        self.assertNotIn("replacement_identity", self.assertions(good_transfer_witness(), no_replacement))
        violation = [dict(e, violation="wrong owner") if e["phase"] == "CHUNK_MAP_TRACKING_HEAD" else e for e in events]
        self.assertNotIn("source_retired", self.assertions(good_transfer_witness(), violation))

    def test_incomplete_generation_and_tampered_trace_are_refused(self) -> None:
        truncated = [e for e in transfer_trace() if e["phase"] != "CHUNK_MAP_TRACKING_RETURN"]
        self.assertNotIn("source_retired", self.assertions(good_transfer_witness(), truncated))
        card = make_card(self.dir, "E0-pig-NETHER_ENTRY", "TRANSFERRED", good_transfer_witness(), transfer_trace())
        card.result_data["txTraceSha256"] = "0" * 64
        self.assertEqual(coverage.headless_transfer_assertions(card, "minecraft:pig", "NETHER_ENTRY") & {"source_retired"}, set())

    def test_special_families_never_get_generic_transfer_claims(self) -> None:
        for kind in ("item", "falling_block", "tnt", "arrow", "player"):
            card = make_card(self.dir, f"E0-{kind}-NETHER_ENTRY", "TRANSFERRED", good_transfer_witness(), transfer_trace())
            self.assertEqual(coverage.headless_claims(card, []), [], kind)


def stay_witness(type_name: str = "block_display", ticks: int = 100) -> dict:
    source = member("minecraft:overworld", type=f"minecraft:{type_name}", object="s", nativeId=3, ticks=0)
    return {"source": [source], "settled": [dict(source, ticks=ticks)], "noEntryContactEvents": []}


def proof_row(entity: str, kind: str, route: str = "NETHER_ENTRY") -> dict:
    return {"entityType": f"minecraft:{entity}", "route": route, "variant": None, "graph": "standalone",
            "expectedNativeOutcome": "NO_NATIVE_ENTRY_PATH",
            "sourceProof": {"kind": kind, "sourceJarSha256": "1" * 64, "mappedBinarySha256": "2" * 64}}


class HeadlessNegativeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def negative(self, witness: dict, entity: str = "block_display", route: str = "NETHER_ENTRY", events=None) -> set[str]:
        card = make_card(self.dir, f"E0-{entity}-{route}", "STAYED", witness, events or [])
        return coverage.headless_negative_assertions(card, f"minecraft:{entity}", route)

    def test_static_entity_with_no_contact_and_no_trace_is_credited(self) -> None:
        self.assertEqual(self.negative(stay_witness()),
                         {"source_unchanged", "destination_absent", "graph_unchanged", "portal_session_absent"})

    def test_trace_events_or_contact_for_a_non_guard_type_break_the_witness(self) -> None:
        self.assertNotIn("portal_session_absent", self.negative(stay_witness(), events=[trace_event("CAPTURED")]))
        contact = stay_witness()
        contact["noEntryContactEvents"] = [{"uuid": UUID, "type": "minecraft:block_display", "world": "minecraft:overworld",
                                            "block": "minecraft:nether_portal", "x": 0, "y": 65, "z": 0}]
        self.assertNotIn("portal_session_absent", self.negative(contact))

    def test_guard_type_must_be_visited_by_the_portal_block(self) -> None:
        self.assertNotIn("portal_session_absent", self.negative(stay_witness("wither"), "wither"))
        visited = stay_witness("wither")
        visited["noEntryContactEvents"] = [{"uuid": UUID, "type": "minecraft:wither", "world": "minecraft:overworld",
                                            "block": "minecraft:nether_portal", "x": 0, "y": 65, "z": 0}]
        self.assertIn("portal_session_absent", self.negative(visited, "wither"))

    def test_moved_changed_or_barely_ticked_entity_is_refused(self) -> None:
        drifted = stay_witness()
        drifted["settled"][0]["x"] = 5.5
        self.assertEqual(self.negative(drifted), set())
        replaced = stay_witness()
        replaced["settled"][0]["object"] = "other"
        self.assertEqual(self.negative(replaced), set())
        self.assertEqual(self.negative(stay_witness(ticks=10)), set())
        self.assertNotEqual(self.negative(stay_witness("lightning_bolt", ticks=10), "lightning_bolt"), set())
        session = stay_witness()
        session["settled"][0]["portalProcessPresent"] = True
        self.assertEqual(self.negative(session), set())

    def test_claim_requires_a_pinned_proof_row_and_binds_the_proof_kind(self) -> None:
        card = make_card(self.dir, "E0-block_display-NETHER_ENTRY", "STAYED", stay_witness(), [])
        self.assertEqual(coverage.headless_claims(card, []), [])
        rows = [proof_row("block_display", "tick_override_skips_portal_processing")]
        claims = coverage.headless_claims(card, rows)
        self.assertEqual(len(claims), 1)
        self.assertEqual(claims[0]["observedOutcome"], "NO_NATIVE_ENTRY_PATH")
        self.assertEqual(claims[0]["applicability"]["sourceProofKind"], "tick_override_skips_portal_processing")
        self.assertEqual((claims[0]["applicability"]["portalX"], claims[0]["applicability"]["portalY"]), (0, 65))
        self.assertTrue(coverage.claim_matches(dict(rows[0], id="x"), claims[0]))
        other = [proof_row("block_display", "unconditional_canUsePortal_false")]
        self.assertTrue(coverage.claim_matches(dict(other[0], id="x"), coverage.headless_claims(card, other)[0]))
        self.assertEqual(coverage.headless_claims(card, [dict(rows[0], expectedNativeOutcome="TRANSFER")]), [])

    def test_native_discard_needs_absence_everywhere_and_no_portal_activity(self) -> None:
        source = member("minecraft:the_end", type="minecraft:llama_spit", object="s", nativeId=3, ticks=0,
                        x=8.5, y=70.25, z=8.5)
        gone = {"source": [source], "afterWindow": []}
        card = make_card(self.dir, "E0-llama_spit-END_EXIT", "GONE", gone, [])
        self.assertEqual(coverage.headless_discard_assertions(card, "minecraft:llama_spit", "END_EXIT"),
                         {"destination_absent", "portal_session_absent", "native_discard"})
        for forged in ({"source": [source], "afterWindow": [source]}, {"source": [source]},
                       {"source": [dict(source, x=2.5)], "afterWindow": []}):
            card = make_card(self.dir, "E0-llama_spit-END_EXIT", "GONE", forged, [])
            self.assertEqual(coverage.headless_discard_assertions(card, "minecraft:llama_spit", "END_EXIT"), set())
        card = make_card(self.dir, "E0-llama_spit-END_EXIT", "GONE", gone, [trace_event("CAPTURED")])
        self.assertEqual(coverage.headless_discard_assertions(card, "minecraft:llama_spit", "END_EXIT"), set())


def gateway_witness() -> dict:
    owner = "Active[region=RegionId[world=WorldId[value=minecraft:the_end], value=stage2-live-world], lease=x]"
    diagnostic = "ObjectOwnership[objectId=a, owner=RegionId[world=WorldId[value=minecraft:the_end], value=stage2-live-world]]"
    source = member("minecraft:the_end", x=12.5, y=66.0, z=8.5, ticks=0, ownershipDiagnostic=diagnostic)
    arrival = member("minecraft:the_end", x=22.5, y=66.0, z=12.5, ticks=5, chunkX=1, chunkZ=0, portalCooldown=300,
                     ownershipDiagnostic=diagnostic)
    return {
        "source": [source], "arrival": [arrival], "later": [dict(arrival, ticks=40, portalCooldown=265)],
        "gateway": {
            "contact": [{"uuid": UUID, "world": "minecraft:the_end", "x": 12, "y": 66, "z": 8,
                         "ownerWorld": "minecraft:the_end", "ownerContext": owner}],
            "ticket": [{"uuid": UUID, "world": "minecraft:the_end", "ticketType": "minecraft:portal", "chunkX": 1, "chunkZ": 0,
                        "ownerWorld": "minecraft:the_end", "ownerContext": owner}],
            "tracking": [dict(source, phase="TRACKING_ADD_RETURN", ownerWorld="minecraft:the_end", ownerContext=owner),
                         dict(arrival, phase="TRACKING_UPDATE_RETURN", ownerWorld="minecraft:the_end", ownerContext=owner)],
        },
    }


class HeadlessGatewayTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def gateway(self, witness: dict) -> set[str]:
        card = make_card(self.dir, "E0-pig-END_GATEWAY", "TRANSFERRED", witness, [])
        return coverage.headless_gateway_assertions(card, "minecraft:pig")

    def test_same_object_gateway_is_fully_credited_and_claimed(self) -> None:
        assertions = self.gateway(gateway_witness())
        self.assertTrue(coverage.REQUIRED_SAME_DIMENSION_ASSERTIONS | {"post_arrival_tick"} <= assertions, assertions)
        card = make_card(self.dir, "E0-pig-END_GATEWAY", "TRANSFERRED", gateway_witness(), [])
        claims = coverage.headless_claims(card, [])
        self.assertEqual(claims[0]["observedOutcome"], "SAME_DIMENSION_TRANSFER")
        self.assertTrue(claims[0]["headlessOwner"])

    def test_replacement_object_or_tracking_add_in_destination_is_refused(self) -> None:
        witness = gateway_witness()
        witness["later"][0]["object"] = "replacement"
        self.assertEqual(self.gateway(witness), set())
        replaced = gateway_witness()
        replaced["gateway"]["tracking"].append(dict(replaced["arrival"][0], phase="TRACKING_ADD_RETURN",
                                                   ownerWorld="minecraft:the_end",
                                                   ownerContext=replaced["gateway"]["tracking"][1]["ownerContext"]))
        self.assertNotIn("tracking_unique", self.gateway(replaced))
        removed = gateway_witness()
        removed["gateway"]["tracking"].append(dict(removed["source"][0], phase="TRACKING_REMOVE_RETURN"))
        self.assertNotIn("tracking_unique", self.gateway(removed))

    def test_repeated_gateway_contact_and_a_short_vehicle_cooldown_are_credited(self) -> None:
        witness = gateway_witness()
        witness["gateway"]["contact"].append(dict(witness["gateway"]["contact"][0]))
        witness["arrival"][0]["portalCooldown"] = 5
        witness["later"][0]["portalCooldown"] = 0
        assertions = self.gateway(witness)
        self.assertTrue(coverage.REQUIRED_SAME_DIMENSION_ASSERTIONS | {"post_arrival_tick"} <= assertions, assertions)
        mismatched = gateway_witness()
        mismatched["gateway"]["contact"].append(dict(mismatched["gateway"]["contact"][0], x=99))
        self.assertNotIn("source_owner", self.gateway(mismatched))

    def test_missing_ticket_or_vacancy_or_cooldown_removes_the_matching_credit(self) -> None:
        witness = gateway_witness()
        witness["gateway"]["ticket"] = []
        self.assertNotIn("gateway_ticket", self.gateway(witness))
        near = gateway_witness()
        near["arrival"][0]["x"] = 13.5
        near["later"][0]["x"] = 13.5
        self.assertNotIn("source_location_vacated", self.gateway(near))
        cold = gateway_witness()
        cold["arrival"][0]["portalCooldown"] = 0
        self.assertNotIn("gateway_cooldown", self.gateway(cold))
        foreign = gateway_witness()
        foreign["gateway"]["ticket"][0]["ownerContext"] = "Active[region=RegionId[world=WorldId[value=minecraft:the_end], value=other]]"
        self.assertNotIn("gateway_ticket", self.gateway(foreign))


def vehicle_witness(chest: bool = False, minecart: bool = False, loose: dict | None = None) -> dict:
    items = ([{"slot": 0, "item": "minecraft:diamond", "count": 7},
              {"slot": 26, "item": "minecraft:iron_ingot" if minecart else "minecraft:sugar_cane",
               "count": 13 if minecart else 3}] if chest else [])
    extra = ({"vehicleSlotCount": 27, "vehicleItems": items, "vehicleLootTable": "UNAVAILABLE", "vehicleLootSeed": 0}
             if chest else {})
    tags = "[stage7_h_0, stage7_mob]"
    name = "minecraft:chest_minecart" if minecart else "minecraft:oak_chest_boat" if chest else "minecraft:oak_boat"
    return {"source": [member("minecraft:overworld", type=name, object="old", nativeId=8, ticks=0, tags=tags, **extra)],
            "arrival": [member("minecraft:the_nether", type=name, ticks=5, tags=tags, **extra)],
            "later": [member("minecraft:the_nether", type=name, ticks=30, tags=tags, **extra)],
            "looseSource": loose or {}, "looseArrival": loose or {}, "looseLater": loose or {}}


class HeadlessVehicleTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def card(self, name: str, entity: str, witness: dict) -> coverage.Card:
        events = [dict(e, type=f"entity.minecraft.{entity}") for e in transfer_trace()]
        return make_card(self.dir, name, "TRANSFERRED", witness, events)

    def test_empty_boat_gets_state_and_a_claim(self) -> None:
        card = self.card("E0-oak_boat-NETHER_ENTRY", "oak_boat", vehicle_witness())
        self.assertEqual(coverage.headless_vehicle_extras(card, "minecraft:oak_boat"), {"vehicle_state_preserved"})
        claims = coverage.headless_claims(card, [])
        self.assertEqual(len(claims), 1)
        self.assertIn("vehicle_state_preserved", claims[0]["assertions"])

    def test_chest_vehicle_needs_exact_contents_and_no_loose_stacks(self) -> None:
        for entity, minecart in (("oak_chest_boat", False), ("chest_minecart", True)):
            card = self.card(f"E0-{entity}-NETHER_ENTRY", entity, vehicle_witness(chest=True, minecart=minecart))
            self.assertEqual(coverage.headless_vehicle_extras(card, f"minecraft:{entity}"),
                             {"vehicle_state_preserved", "inventory_preserved", "no_loose_item_duplication"}, entity)
            self.assertEqual(len(coverage.headless_claims(card, [])), 1, entity)
        dropped = self.card("E0-oak_chest_boat-NETHER_ENTRY", "oak_chest_boat",
                            vehicle_witness(chest=True, loose={"minecraft:diamond": 7}))
        self.assertNotIn("no_loose_item_duplication", coverage.headless_vehicle_extras(dropped, "minecraft:oak_chest_boat"))
        self.assertEqual(coverage.headless_claims(dropped, []), [])
        altered = vehicle_witness(chest=True)
        altered["later"][0]["vehicleItems"][0]["count"] = 6
        card = self.card("E0-oak_chest_boat-NETHER_ENTRY", "oak_chest_boat", altered)
        self.assertNotIn("inventory_preserved", coverage.headless_vehicle_extras(card, "minecraft:oak_chest_boat"))
        self.assertEqual(coverage.headless_claims(card, []), [])

    def test_passenger_or_changed_tags_break_vehicle_state(self) -> None:
        ridden = vehicle_witness()
        ridden["arrival"][0]["passengerUuids"] = [UUID]
        card = self.card("E0-oak_boat-NETHER_ENTRY", "oak_boat", ridden)
        self.assertNotIn("vehicle_state_preserved", coverage.headless_vehicle_extras(card, "minecraft:oak_boat"))
        retagged = vehicle_witness()
        retagged["later"][0]["tags"] = "[stage7_mob]"
        card = self.card("E0-oak_boat-NETHER_ENTRY", "oak_boat", retagged)
        self.assertNotIn("vehicle_state_preserved", coverage.headless_vehicle_extras(card, "minecraft:oak_boat"))

    def test_missing_source_checkpoint_is_tolerated_but_a_malformed_one_is_not(self) -> None:
        witness = vehicle_witness()
        witness["source"] = []
        card = self.card("E0-oak_boat-NETHER_ENTRY", "oak_boat", witness)
        self.assertEqual(coverage.headless_vehicle_extras(card, "minecraft:oak_boat"), {"vehicle_state_preserved"})
        witness["source"] = [member("minecraft:overworld"), member("minecraft:overworld")]
        card = self.card("E0-oak_boat-NETHER_ENTRY", "oak_boat", witness)
        self.assertEqual(coverage.headless_vehicle_extras(card, "minecraft:oak_boat"), set())


BOAT_UUID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
VILLAGER_UUID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"


def graph_members(world: str, ticks: int, boat_object: str = "boat-new", rider_object: str = "villager-new") -> list[dict]:
    boat = member(world, uuid=BOAT_UUID, type="minecraft:oak_boat", object=boat_object, nativeId=21, ticks=ticks,
                  passengerUuids=[VILLAGER_UUID])
    villager = member(world, uuid=VILLAGER_UUID, type="minecraft:villager", object=rider_object, nativeId=22, ticks=ticks,
                      vehicleUuid=BOAT_UUID)
    return [boat, villager]


def node_trace(uuid: str, kind: str, old: tuple, new: tuple, generation: int) -> list[dict]:
    events = transfer_trace()
    for event in events:
        event.update(uuid=uuid, type=f"entity.minecraft.{kind}", generation=generation)
        if event["phase"] in ("SOURCE_RETIRING", "SOURCE_RETIRED"):
            event.update(object=old[0], nativeId=old[1])
        else:
            event.update(object=new[0], nativeId=new[1])
    return events


def graph_witness() -> dict:
    source = [dict(m, world="minecraft:overworld", object=o, nativeId=n, ticks=0)
              for m, (o, n) in zip(graph_members("minecraft:overworld", 0), (("boat-old", 11), ("villager-old", 12)))]
    return {"nodes": 2, "counts": {"source": 0, "destination": 2, "total": 2}, "source": source,
            "arrival": graph_members("minecraft:the_nether", 4), "later": graph_members("minecraft:the_nether", 30)}


def graph_events() -> list[dict]:
    return (node_trace(VILLAGER_UUID, "villager", ("villager-old", 12), ("villager-new", 22), 1)
            + node_trace(BOAT_UUID, "oak_boat", ("boat-old", 11), ("boat-new", 21), 2))


class HeadlessGraphTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def card(self, witness: dict, events: list[dict], name: str = "G0-boat_mob-NETHER_ENTRY") -> coverage.Card:
        return make_card(self.dir, name, "TRANSFERRED", witness, events)

    def test_boat_with_villager_is_credited_exactly_and_claims_the_ledger_graph_name(self) -> None:
        card = self.card(graph_witness(), graph_events())
        assertions, owners = coverage.headless_graph_assertions(card, "boat_mob", "NETHER_ENTRY")
        self.assertTrue({"graph_nodes_exact", "graph_edges_exact", "no_dual_addressability", "source_retired",
                         "tracking_unique", "destination_unique", "post_arrival_tick"} <= assertions, assertions)
        self.assertTrue(owners)
        claims = coverage.headless_claims(card, [])
        self.assertEqual((claims[0]["graph"], claims[0]["entityType"], claims[0]["observedOutcome"]),
                         ("boat with mob rider", None, "TRANSFER"))

    def test_wrong_shape_or_broken_edges_or_missing_node_are_refused(self) -> None:
        card = self.card(graph_witness(), graph_events())
        self.assertNotIn("graph_nodes_exact", coverage.headless_graph_assertions(card, "boat_two", "NETHER_ENTRY")[0])
        broken = graph_witness()
        broken["arrival"][1]["vehicleUuid"] = "UNAVAILABLE"
        card = self.card(broken, graph_events())
        self.assertEqual(coverage.headless_graph_assertions(card, "boat_mob", "NETHER_ENTRY"), (set(), False))
        missing = graph_witness()
        missing["arrival"] = missing["arrival"][:1]
        self.assertEqual(coverage.headless_graph_assertions(self.card(missing, graph_events()), "boat_mob", "NETHER_ENTRY"),
                         (set(), False))
        stuck = graph_witness()
        stuck["arrival"][0]["world"] = "minecraft:overworld"
        self.assertEqual(coverage.headless_graph_assertions(self.card(stuck, graph_events()), "boat_mob", "NETHER_ENTRY"),
                         (set(), False))

    def test_every_node_needs_its_own_complete_generation_and_owner_phases(self) -> None:
        only_boat = node_trace(BOAT_UUID, "oak_boat", ("boat-old", 11), ("boat-new", 21), 2)
        assertions, owners = coverage.headless_graph_assertions(self.card(graph_witness(), only_boat),
                                                                 "boat_mob", "NETHER_ENTRY")
        self.assertNotIn("source_retired", assertions)
        self.assertFalse(owners)
        wrong_owner = [dict(e, ownerWorld="minecraft:the_nether") if e["phase"] == "SOURCE_RETIRED" and e["uuid"] == VILLAGER_UUID
                       else e for e in graph_events()]
        assertions, owners = coverage.headless_graph_assertions(self.card(graph_witness(), wrong_owner),
                                                                 "boat_mob", "NETHER_ENTRY")
        self.assertNotIn("source_retired", assertions)
        self.assertEqual(coverage.headless_claims(self.card(graph_witness(), wrong_owner), []), [])

    def test_stalled_child_ticks_remove_post_arrival_tick_and_the_claim(self) -> None:
        stalled = graph_witness()
        stalled["later"][1]["ticks"] = 10
        card = self.card(stalled, graph_events())
        self.assertNotIn("post_arrival_tick", coverage.headless_graph_assertions(card, "boat_mob", "NETHER_ENTRY")[0])

    def test_player_graph_names_have_no_headless_shape(self) -> None:
        for name in ("boat with player rider", "minecart with player rider", "player riding mob",
                     "boat with player rider credits unseen"):
            self.assertNotIn(name, coverage.GRAPH_ROW_SHAPE)
        self.assertEqual(set(coverage.GRAPH_SHAPES), set(coverage.GRAPH_SHAPE_ROW))


class ScanHeadlessTests(unittest.TestCase):
    def build_run(self, root: pathlib.Path, mutate=None, vanilla: bool = False) -> pathlib.Path:
        worktree = root / "work"
        script = worktree / "scripts" / "stage7" / "headless_census.py"
        script.parent.mkdir(parents=True)
        script.write_text("# census\n", encoding="utf-8")
        source = worktree / "src" / "t.java"
        source.parent.mkdir(parents=True)
        source.write_text("class T {}\n", encoding="utf-8")
        jar = root / "candidate.jar"
        jar.write_bytes(b"candidate")
        candidate = coverage.digest(jar)
        run = root / "installed" / "run1"
        run.mkdir(parents=True)
        card = {"card": "E0-pig-NETHER_ENTRY", "outcome": "TRANSFERRED", "witness": good_transfer_witness()}
        inputs = {"vanilla": vanilla, "candidate": str(jar), "candidateSha256": candidate, "group": "headless-census",
                  "evidence": "server-headless", "headless": True, "workers": 1, "cards": ["E0-pig-NETHER_ENTRY"],
                  "censusScriptSha256": coverage.digest(script), "probeSha256": PROBE,
                  "testSourceHashes": {"src/t.java": coverage.digest(source)}}
        result = {"phase": "headless", "candidateSha256": candidate, "installedCandidateSha256": candidate, "pass": True,
                  "txPass": True, "logFailure": None, "normalSave": True, "normalStopRequested": True, "serverExitCode": 0,
                  "sourceWorldChanges": [], "testSourceChanged": [], "configChanges": []}
        receipt = {"group": "headless-census", "evidence": "server-headless", "pass": True, "candidateSha256": candidate,
                   "cards": [card]}
        if mutate:
            mutate(inputs, result, receipt)
        for name, doc in (("inputs.json", inputs), ("headless-result.json", result), ("headless-receipt.json", receipt)):
            (run / name).write_text(json.dumps(doc), encoding="utf-8")
        self.worktree, self.candidate = worktree, candidate
        return root / "installed"

    def scan(self, **kwargs):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            installed = self.build_run(root, **kwargs)
            return coverage.scan_headless([installed], self.candidate, self.worktree)

    def test_clean_run_yields_one_labelled_card_with_uuid(self) -> None:
        cards, rejected = self.scan()
        self.assertEqual((len(cards), rejected), (1, []))
        self.assertEqual((cards[0].evidence, cards[0].workers, cards[0].data["details"][:5]), ("server-headless", 1, "UUID="))

    def test_vanilla_controls_are_not_phoenium_evidence(self) -> None:
        self.assertEqual(self.scan(vanilla=True), ([], []))

    def test_every_integrity_failure_rejects_the_whole_run(self) -> None:
        mutations = {
            "script": lambda i, r, c: i.update(censusScriptSha256="0" * 64),
            "pass": lambda i, r, c: r.update(**{"pass": False}),
            "tx": lambda i, r, c: r.update(txPass=False),
            "save": lambda i, r, c: r.update(normalSave=False),
            "forced": lambda i, r, c: r.update(forcedOwnServerStop=True),
            "world": lambda i, r, c: r.update(sourceWorldChanges=["level.dat"]),
            "testsrc": lambda i, r, c: i.update(testSourceHashes={"src/t.java": "0" * 64}),
            "candidate": lambda i, r, c: r.update(installedCandidateSha256="0" * 64),
            "label": lambda i, r, c: i.update(evidence="real-client"),
            "workers": lambda i, r, c: i.update(workers=2),
            "cards": lambda i, r, c: i.update(cards=["E0-pig-END_ENTRY"]),
        }
        for name, mutate in mutations.items():
            cards, rejected = self.scan(mutate=mutate)
            self.assertEqual((cards, len(rejected)), ([], 1), name)


class ClassifyTests(unittest.TestCase):
    def census(self) -> dict:
        def row(entity: str, route: str, expected=None, variant=None) -> dict:
            return {"id": f"V2-{entity.upper()}-{route}", "entityType": f"minecraft:{entity}", "route": route,
                    "variant": variant, "graph": "standalone", "expectedNativeOutcome": expected, "sourceProof": None,
                    "nativeControlReceipt": None, "requiredAssertions": []}
        return {"sourceJarSha256": "1" * 64, "mappedBinarySha256": "2" * 64,
                "rows": [row("pig", "NETHER_ENTRY"), row("pig", "END_GATEWAY"), row("block_display", "NETHER_ENTRY"),
                         row("area_effect_cloud", "NETHER_ENTRY"), row("cow", "NETHER_ENTRY", "TRANSFER"),
                         row("sand", "NETHER_ENTRY", variant="x")]}

    def control(self, root: pathlib.Path, outcomes: dict[str, str]) -> pathlib.Path:
        run = root / "ctl"
        run.mkdir()
        cards = [{"card": name, "outcome": value} for name, value in outcomes.items()]
        (run / "headless-result.json").write_text(json.dumps({"pass": True, "normalSave": True}), encoding="utf-8")
        (run / "headless-receipt.json").write_text(json.dumps({"pass": True, "cards": cards}), encoding="utf-8")
        (run / "inputs.json").write_text(json.dumps({"vanilla": True, "mods": [], "sourceJarSha256": "1" * 64,
                                                      "mappedBinarySha256": "2" * 64, "cards": list(outcomes)}), encoding="utf-8")
        return run

    def classify(self, outcomes: dict[str, str], proofs: bool = False):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            census = root / "v2.json"
            census.write_text(json.dumps(self.census()), encoding="utf-8")
            args = ["--census", str(census), "--control", str(self.control(root, outcomes)), "--out-dir", str(root / "out")]
            if proofs:
                proof_file = root / "proofs.json"
                proof_file.write_text(json.dumps({"sourceJarSha256": "1" * 64, "mappedBinarySha256": "2" * 64,
                                                  "proofs": {"minecraft:area_effect_cloud": {"kind": "no_physics_blocks_effects"},
                                                             "minecraft:block_display": {"kind": "k"}}}), encoding="utf-8")
                args += ["--no-entry-proofs", str(proof_file)]
            code = classify_census.main(args)
            return code, json.loads((root / "out" / "PORTAL_BEHAVIOR_CENSUS_V3.json").read_text(encoding="utf-8")), root

    def test_transferred_controls_fix_the_native_outcome_per_route_kind(self) -> None:
        code, census, _ = self.classify({"E0-pig-NETHER_ENTRY": "TRANSFERRED", "E0-pig-END_GATEWAY": "TRANSFERRED"})
        rows = {row["id"]: row for row in census["rows"]}
        self.assertEqual(code, 0)
        self.assertEqual(rows["V2-PIG-NETHER_ENTRY"]["expectedNativeOutcome"], "TRANSFER")
        self.assertEqual(rows["V2-PIG-END_GATEWAY"]["expectedNativeOutcome"], "SAME_DIMENSION_TRANSFER")
        self.assertEqual(rows["V2-PIG-NETHER_ENTRY"]["nativeControlReceipt"]["card"], "E0-pig-NETHER_ENTRY")

    def test_a_stay_is_never_turned_into_a_verdict_without_a_proof(self) -> None:
        _, census, _ = self.classify({"E0-pig-NETHER_ENTRY": "STAYED", "E0-block_display-NETHER_ENTRY": "STAYED"})
        rows = {row["id"]: row for row in census["rows"]}
        self.assertIsNone(rows["V2-PIG-NETHER_ENTRY"]["expectedNativeOutcome"])
        self.assertIsNone(rows["V2-BLOCK_DISPLAY-NETHER_ENTRY"]["expectedNativeOutcome"])

    def test_proof_plus_stay_classifies_no_entry_and_proof_plus_transfer_conflicts(self) -> None:
        code, census, _ = self.classify({"E0-area_effect_cloud-NETHER_ENTRY": "STAYED"}, proofs=True)
        rows = {row["id"]: row for row in census["rows"]}
        self.assertEqual(rows["V2-AREA_EFFECT_CLOUD-NETHER_ENTRY"]["expectedNativeOutcome"], "NO_NATIVE_ENTRY_PATH")
        self.assertEqual(rows["V2-AREA_EFFECT_CLOUD-NETHER_ENTRY"]["fixture"]["contactImpossible"], True)
        code, census, _ = self.classify({"E0-area_effect_cloud-NETHER_ENTRY": "TRANSFERRED"}, proofs=True)
        self.assertEqual(code, 1)
        self.assertEqual(census["classification"]["controlConflicts"][0]["id"], "V2-AREA_EFFECT_CLOUD-NETHER_ENTRY")

    def test_existing_transfer_row_conflicting_with_the_control_is_reported(self) -> None:
        code, census, _ = self.classify({"E0-cow-NETHER_ENTRY": "STAYED"})
        self.assertEqual(code, 1)
        self.assertEqual(census["classification"]["controlConflicts"][0]["id"], "V2-COW-NETHER_ENTRY")

    def test_variant_rows_and_non_vanilla_controls_are_never_classified(self) -> None:
        _, census, root = self.classify({"E0-sand-NETHER_ENTRY": "TRANSFERRED"})
        self.assertIsNone({row["id"]: row for row in census["rows"]}["V2-SAND-NETHER_ENTRY"]["expectedNativeOutcome"])
        with tempfile.TemporaryDirectory() as temp:
            run = self.control(pathlib.Path(temp), {"E0-pig-NETHER_ENTRY": "TRANSFERRED"})
            inputs = json.loads((run / "inputs.json").read_text(encoding="utf-8"))
            inputs["mods"] = ["phoenium.jar"]
            (run / "inputs.json").write_text(json.dumps(inputs), encoding="utf-8")
            with self.assertRaises(SystemExit):
                classify_census.load_control(run, "1" * 64, "2" * 64)


def dyn_regions(landed: dict | None = None, targets: bool = False) -> dict:
    result = {}
    for dimension, labels in coverage.FALLING_REGION_LABELS.items():
        entry = {"regions": {label: {"blocks": 0, "loaded": True} for label in labels},
                 "items": 0, "itemIds": [], "carriedItems": 0, "fallingBlocks": 0}
        if targets:
            entry["targets"], entry["targetsUndamaged"] = 9, 9
        result[dimension] = entry
    for (dimension, label), blocks in (landed or {}).items():
        result[dimension]["regions"][label]["blocks"] = blocks
    return result


def dyn_witness(variant: str = "block", landed: dict | None = None, damage: bool = False, spec: str | None = None,
                nbt: str | None = None) -> dict:
    block = coverage.FALLING_CARRIED_BLOCK.get(variant, variant)
    landing = variant == "landing_damage"
    observables = dyn_regions(landed, targets=landing)
    if landing and damage:
        for entry in observables.values():
            entry["targetsUndamaged"] = 8
    return {"carriedSpec": spec or "minecraft:" + block, "variant": variant, "baseline": dyn_regions(None, targets=landing),
            "observables": observables,
            "nbtSource": nbt if nbt is not None else 'BlockState: {Name: "minecraft:%s"}' % block,
            "source": [member("minecraft:overworld", type="minecraft:falling_block", object="old", nativeId=8, ticks=0)]}


def dyn_trace(route: str = "NETHER_ENTRY", destination: str = "minecraft:the_nether") -> list[dict]:
    source = coverage.ROUTE_SOURCE_WORLD[route]
    result = []
    for event in transfer_trace():
        retired = event["phase"] in ("SOURCE_RETIRING", "SOURCE_RETIRED")
        result.append(dict(event, type="entity.minecraft.falling_block", route=route, sourceWorld=source,
                           destinationWorld=destination, ownerWorld=source if retired else destination))
    return result


class HeadlessDynamicTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.temp.name)
        self.previous_root = coverage.CONTROL_ROOT
        coverage.CONTROL_ROOT = self.dir

    def tearDown(self) -> None:
        coverage.CONTROL_ROOT = self.previous_root
        self.temp.cleanup()

    def row(self, name: str, control_witness: dict, variant: str = "minecraft:block", route: str = "NETHER_ENTRY",
            outcome: str = "TRANSFERRED") -> dict:
        folder = self.dir / "native-controls" / "ctl"
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "headless-result.json").write_text("{}", encoding="utf-8")
        receipt = folder / "headless-receipt.json"
        receipt.write_text(json.dumps({"cards": [{"card": name, "outcome": outcome, "witness": control_witness}]}),
                           encoding="utf-8")
        return {"id": "V2-FALLING_BLOCK-" + route, "entityType": "minecraft:falling_block", "route": route,
                "variant": variant, "graph": "standalone", "expectedNativeOutcome": "TRANSFER",
                "nativeControlReceipt": {"card": name, "path": "native-controls/ctl/headless-result.json",
                                         "receiptSha256": coverage.digest(receipt)}}

    def claims(self, name: str, witness: dict, control: dict, route: str = "NETHER_ENTRY",
               variant: str = "minecraft:block", trace: list[dict] | None = None):
        row = self.row(name, control, variant, route)
        destination = coverage.ROUTE_DESTINATION_WORLD[route]
        card = make_card(self.dir, name, "TRANSFERRED", witness,
                         trace if trace is not None else dyn_trace(route, destination), "falling_block")
        return coverage.headless_claims(card, [row])

    def test_matching_landing_and_control_yield_a_full_row_claim(self) -> None:
        landed = {("nether", "main"): 1}
        claims = self.claims("D0-falling-block-NETHER_ENTRY", dyn_witness(landed=landed), dyn_witness(landed=landed))
        self.assertEqual(len(claims), 1)
        self.assertEqual(claims[0]["variant"], "minecraft:block")
        self.assertEqual(claims[0]["observedOutcome"], "TRANSFER")
        self.assertTrue({"source_retired", "destination_unique", "tracking_unique", "source_block_removed",
                         "carried_state", "native_landing"} <= claims[0]["assertions"], claims[0]["assertions"])

    def test_a_different_world_than_the_control_removes_the_claim(self) -> None:
        own = dyn_witness(landed={("nether", "main"): 1})
        control = dyn_witness(landed={("nether", "main"): 1})
        control["observables"]["overworld"]["carriedItems"] = 1
        self.assertEqual(self.claims("D0-falling-block-NETHER_ENTRY", own, control), [])

    def test_world_wide_loose_items_are_not_compared_but_carried_drops_are(self) -> None:
        landed = {("nether", "main"): 1}
        own, control = dyn_witness(landed=landed), dyn_witness(landed=landed)
        own["observables"]["overworld"]["items"] = 4      # random leaf-decay drops far from the fixture
        own["baseline"]["end"]["items"] = 2
        self.assertEqual(len(self.claims("D0-falling-block-NETHER_ENTRY", own, control)), 1)

    def test_landing_in_the_wrong_place_or_twice_or_with_a_leftover_is_not_credited(self) -> None:
        for landed in ({("overworld", "main"): 1}, {("nether", "main"): 2}, {("nether", "main"): 0},
                       {("nether", "main"): 1, ("overworld", "main"): 1}):
            bad = dyn_witness(landed=landed)
            self.assertEqual(self.claims("D0-falling-block-NETHER_ENTRY", bad, bad), [], landed)
        leftover = dyn_witness(landed={("nether", "main"): 1})
        leftover["observables"]["nether"]["fallingBlocks"] = 1
        self.assertEqual(self.claims("D0-falling-block-NETHER_ENTRY", leftover, leftover), [])
        good = dyn_witness(landed={("nether", "main"): 1})
        self.assertEqual(len(self.claims("D0-falling-block-NETHER_ENTRY", good, good)), 1)

    def test_unloaded_region_or_missing_baseline_is_not_credited(self) -> None:
        unloaded = dyn_witness(landed={("nether", "main"): 1})
        unloaded["observables"]["nether"]["regions"]["main"]["loaded"] = False
        self.assertEqual(self.claims("D0-falling-block-NETHER_ENTRY", unloaded, unloaded), [])
        no_base = dyn_witness(landed={("nether", "main"): 1})
        del no_base["baseline"]
        self.assertEqual(self.claims("D0-falling-block-NETHER_ENTRY", no_base, no_base), [])

    def test_wrong_carried_state_is_not_credited(self) -> None:
        landed = {("nether", "main"): 1}
        wrong = dyn_witness(landed=landed, nbt='BlockState: {Name: "minecraft:stone"}')
        self.assertEqual(self.claims("D0-falling-block-NETHER_ENTRY", wrong, dyn_witness(landed=landed)), [])
        # anvil-like variants carry a block-state property that must appear in the entity NBT
        spec = "minecraft:anvil[facing=east]"
        props = dyn_witness("anvil", landed, spec=spec,
                            nbt='BlockState: {Name: "minecraft:anvil", Properties: {facing: "east"}}')
        self.assertEqual(len(self.claims("D0-falling-anvil-NETHER_ENTRY", props, props, variant="minecraft:anvil")), 1)
        bare = dict(props, nbtSource='BlockState: {Name: "minecraft:anvil"}')
        self.assertEqual(self.claims("D0-falling-anvil-NETHER_ENTRY", bare, props, variant="minecraft:anvil"), [])

    def test_control_must_be_hash_bound_and_transfer_the_same_card(self) -> None:
        landed = {("nether", "main"): 1}
        name = "D0-falling-block-NETHER_ENTRY"
        row = self.row(name, dyn_witness(landed=landed))
        card = make_card(self.dir, name, "TRANSFERRED", dyn_witness(landed=landed), dyn_trace(), "falling_block")
        self.assertEqual(len(coverage.headless_claims(card, [row])), 1)
        stayed = self.row(name, dyn_witness(landed=landed), outcome="STAYED")
        self.assertEqual(coverage.headless_claims(card, [stayed]), [])
        other = self.row("D0-falling-sand-NETHER_ENTRY", dyn_witness(landed=landed))
        self.assertEqual(coverage.headless_claims(card, [other]), [])
        # tamper with the receipt after the row recorded its hash
        row = self.row(name, dyn_witness(landed=landed))
        (self.dir / "native-controls" / "ctl" / "headless-receipt.json").write_text(
            json.dumps({"cards": [{"card": name, "outcome": "TRANSFERRED",
                                   "witness": dyn_witness(landed={("nether", "main"): 3})}]}), encoding="utf-8")
        self.assertEqual(coverage.headless_claims(card, [row]), [])

    def test_trace_must_be_a_complete_generation_between_the_right_worlds(self) -> None:
        landed = {("nether", "main"): 1}
        name = "D0-falling-block-NETHER_ENTRY"
        incomplete = [event for event in dyn_trace() if event["phase"] != "CHUNK_MAP_TRACKING_RETURN"]
        self.assertEqual(self.claims(name, dyn_witness(landed=landed), dyn_witness(landed=landed), trace=incomplete), [])
        wrong = dyn_trace(destination="minecraft:overworld")
        self.assertEqual(self.claims(name, dyn_witness(landed=landed), dyn_witness(landed=landed), trace=wrong), [])
        violating = dyn_trace()
        violating[2]["violation"] = "x"
        self.assertEqual(self.claims(name, dyn_witness(landed=landed), dyn_witness(landed=landed), trace=violating), [])

    def test_landing_damage_needs_a_control_that_shows_the_damage(self) -> None:
        landed = {("nether", "main"): 1}
        name = "D0-falling-landing_damage-NETHER_ENTRY"
        quiet = dyn_witness("landing_damage", landed, damage=False)
        self.assertEqual(self.claims(name, quiet, quiet, variant="landing_damage"), [])
        hurt = dyn_witness("landing_damage", landed, damage=True)
        claims = self.claims(name, hurt, hurt, variant="landing_damage")
        self.assertEqual(len(claims), 1)
        self.assertEqual(claims[0]["variant"], "landing_damage")
        self.assertEqual(self.claims(name, quiet, hurt, variant="landing_damage"), [])

    def test_end_entry_must_land_on_the_spawn_platform(self) -> None:
        name = "D0-falling-block-END_ENTRY"
        on_platform = dyn_witness(landed={("end", "platform"): 1})
        claims = self.claims(name, on_platform, on_platform, route="END_ENTRY")
        self.assertEqual(len(claims), 1)
        self.assertIn("end_platform", claims[0]["assertions"])
        elsewhere = dyn_witness(landed={("end", "fixture"): 1})
        self.assertEqual(self.claims(name, elsewhere, elsewhere, route="END_ENTRY"), [])

    def test_variant_spelling_round_trips_between_card_names_and_ledger_rows(self) -> None:
        for card_variant, row_variant in (("block", "minecraft:block"), ("sand", "minecraft:sand"),
                                          ("landing_damage", "landing_damage")):
            self.assertEqual(coverage.falling_row_variant(card_variant), row_variant)
            self.assertEqual(coverage.falling_variant_name(row_variant), card_variant)


if __name__ == "__main__":
    unittest.main()


def fx_state(alive: bool = True, health: float | None = 10.0, effects: tuple = (), hurt: bool = False, pos: tuple = (0.5, 65.0, 2.5)) -> dict:
    return {"alive": alive, "health": health if alive else None, "fire": 0, "effects": list(effects), "hurtBy": hurt,
            "pos": list(pos) if alive else None}


def fx_dimension(damage: bool = True, counts: dict | None = None, effects: tuple = (), projectile_alive: bool = False) -> dict:
    targets = {"t%d" % i: fx_state(pos=(0.5, 65.0, 2.5 + 2 * i)) for i in range(5)}
    if damage:
        targets["t0"] = fx_state(health=9.0, effects=effects, pos=(0.5, 65.0, 2.5))
    return {"targets": targets, "owner": fx_state(pos=(0.5, 65.0, -4.0)),
            "counts": dict({"area_effect_cloud": 0, "experience_orb": 0, "chicken": 0, "arrow": 0}, **(counts or {})),
            "projectile": {"alive": projectile_alive}}


def fx_witness(kind: str = "arrow", owner: str = "Owner: [I; 9100, 2, 0, 2]", arrival_owner: str | None = None,
               damage: bool = True, fuse: tuple | None = None, route: str = "NETHER_ENTRY", **dimension) -> dict:
    entity = "minecraft:" + kind
    arrival_owner = owner if arrival_owner is None else arrival_owner
    def nbt(extra: str, fuse_value: int | None) -> str:
        fuse_text = "" if fuse_value is None else "fuse: %ds, " % fuse_value
        return "Thing has the following entity data: {NoGravity: 1b, %s%s%s}" % (fuse_text, extra, "")
    source_fuse, arrival_fuse, later_fuse = fuse if fuse else (None, None, None)
    destination = coverage.WORLD_LABEL[coverage.ROUTE_DESTINATION_WORLD[route]]
    return {"kind": kind, "armedAt": [0.5, 65.0, 0.5],
            "nbtSource": nbt(owner, source_fuse), "nbtArrival": nbt(arrival_owner, arrival_fuse),
            "nbtLater": nbt(arrival_owner, later_fuse),
            "source": [member("minecraft:overworld", type=entity, object="old", nativeId=8, ticks=0)],
            "arrival": [member(coverage.ROUTE_DESTINATION_WORLD[route], type=entity, ticks=5)],
            "later": [member(coverage.ROUTE_DESTINATION_WORLD[route], type=entity, ticks=30)],
            "effects": {destination: fx_dimension(damage, **dimension)}}


def fx_trace(kind: str, route: str = "NETHER_ENTRY") -> list[dict]:
    source, destination = coverage.ROUTE_SOURCE_WORLD[route], coverage.ROUTE_DESTINATION_WORLD[route]
    return [dict(event, type="entity.minecraft." + kind, route=route, sourceWorld=source, destinationWorld=destination,
                 ownerWorld=source if event["phase"] in ("SOURCE_RETIRING", "SOURCE_RETIRED") else destination)
            for event in transfer_trace()]


class HeadlessEffectsTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.temp.name)
        self.previous_root = coverage.CONTROL_ROOT
        coverage.CONTROL_ROOT = self.dir

    def tearDown(self) -> None:
        coverage.CONTROL_ROOT = self.previous_root
        self.temp.cleanup()

    def claims(self, kind: str, witness: dict, control: dict, route: str = "NETHER_ENTRY", variant=None, trace=None):
        name = f"D1-{kind}-{route}"
        folder = self.dir / "native-controls" / "ctl"
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "headless-result.json").write_text("{}", encoding="utf-8")
        receipt = folder / "headless-receipt.json"
        receipt.write_text(json.dumps({"cards": [{"card": name, "outcome": "TRANSFERRED", "witness": control}]}), encoding="utf-8")
        entity_type, row_variant, _ = coverage.EFFECT_KINDS[kind]
        row = {"id": "V2-" + kind.upper() + "-" + route, "entityType": entity_type, "route": route, "variant": row_variant,
               "graph": "standalone",
               "expectedNativeOutcome": "SAME_DIMENSION_TRANSFER" if route == "END_GATEWAY" else "TRANSFER",
               "effectsControlReceipt": {"card": name, "path": "native-controls/ctl/headless-result.json",
                                         "receiptSha256": coverage.digest(receipt)}}
        card = make_card(self.dir, name, "TRANSFERRED", witness, trace if trace is not None else fx_trace(kind, route), kind)
        return coverage.headless_claims(card, [row])

    def test_matching_effect_with_preserved_owner_is_credited(self) -> None:
        claims = self.claims("arrow", fx_witness(), fx_witness())
        self.assertEqual(len(claims), 1)
        self.assertTrue({"impact_effect", "projectile_owner_preserved", "source_retired", "destination_unique",
                         "tracking_unique", "post_arrival_tick"} <= claims[0]["assertions"], claims[0]["assertions"])

    def test_a_different_world_than_the_control_removes_the_claim(self) -> None:
        other = fx_witness()
        other["effects"]["nether"]["targets"]["t1"] = fx_state(health=9.0, pos=(0.5, 65.0, 4.5))
        self.assertEqual(self.claims("arrow", other, fx_witness()), [])
        flag = fx_witness()
        flag["effects"]["nether"]["targets"]["t2"]["hurtBy"] = True
        self.assertEqual(self.claims("arrow", flag, fx_witness()), [])

    def test_a_control_without_the_effect_proves_nothing(self) -> None:
        quiet = fx_witness(damage=False)
        self.assertEqual(self.claims("arrow", quiet, quiet), [])

    def test_a_lost_or_changed_owner_is_not_credited(self) -> None:
        lost = fx_witness(arrival_owner="")
        self.assertEqual(self.claims("arrow", lost, fx_witness()), [])
        self.assertEqual(self.claims("arrow", lost, lost), [])
        changed = fx_witness(arrival_owner="Owner: [I; 1, 2, 3, 4]")
        self.assertEqual(self.claims("arrow", changed, changed), [])

    def test_the_effect_is_judged_per_kind(self) -> None:
        cloud = fx_witness("dragon_fireball", damage=False, counts={"area_effect_cloud": 1})
        self.assertEqual(len(self.claims("dragon_fireball", cloud, cloud)), 1)
        none = fx_witness("dragon_fireball", damage=True)
        self.assertEqual(self.claims("dragon_fireball", none, none), [])
        glow = fx_witness("spectral_arrow", effects=("glowing",))
        self.assertEqual(len(self.claims("spectral_arrow", glow, glow)), 1)
        plain = fx_witness("spectral_arrow")
        self.assertEqual(self.claims("spectral_arrow", plain, plain), [])
        hurt_only = fx_witness("egg", damage=False)
        hurt_only["effects"]["nether"]["targets"]["t0"]["hurtBy"] = True
        self.assertEqual(len(self.claims("egg", hurt_only, hurt_only)), 1)
        orbs = fx_witness("experience_bottle", damage=False, counts={"experience_orb": 3})
        self.assertEqual(len(self.claims("experience_bottle", orbs, orbs)), 1)

    def test_tnt_fuse_must_continue_not_reset(self) -> None:
        good = fx_witness("tnt", owner="", fuse=(160, 158, 118), counts={"tnt": 0})
        self.assertEqual(len(self.claims("tnt", good, good)), 1)
        reset = fx_witness("tnt", owner="", fuse=(160, 80, 40), counts={"tnt": 0})
        self.assertEqual(self.claims("tnt", reset, good), [])
        self.assertEqual(self.claims("tnt", reset, reset), [])
        standing = fx_witness("tnt", owner="", fuse=(160, 158, 158), counts={"tnt": 0})
        self.assertEqual(self.claims("tnt", standing, standing), [])
        remaining = fx_witness("tnt", owner="", fuse=(160, 158, 118), counts={"tnt": 1})
        self.assertEqual(self.claims("tnt", remaining, remaining), [])

    def test_shulker_bullet_keeps_exactly_what_the_native_control_keeps(self) -> None:
        owner, target = "Owner: [I; 9100, 9, 0, 2]", "Owner: [I; 9100, 9, 0, 2], Target: [I; 9100, 9, 0, 1]"
        native = fx_witness("shulker_bullet", owner=target, arrival_owner=owner, damage=False)
        claims = self.claims("shulker_bullet", native, native)
        self.assertEqual(len(claims), 1)
        self.assertIn("owner_target_preserved", claims[0]["assertions"])
        kept = fx_witness("shulker_bullet", owner=target, arrival_owner=target, damage=False)
        self.assertEqual(self.claims("shulker_bullet", kept, native), [])

    def test_tampered_control_and_bad_trace_remove_the_claim(self) -> None:
        witness = fx_witness()
        self.assertEqual(len(self.claims("arrow", witness, witness)), 1)
        broken = fx_trace("arrow")
        broken[2]["violation"] = "x"
        self.assertEqual(self.claims("arrow", witness, witness, trace=broken), [])
        incomplete = [event for event in fx_trace("arrow") if event["phase"] != "CHUNK_MAP_TRACKING_RETURN"]
        self.assertEqual(self.claims("arrow", witness, witness, trace=incomplete), [])
        receipt = self.dir / "native-controls" / "ctl" / "headless-receipt.json"
        name = "D1-arrow-NETHER_ENTRY"
        row = {"id": "x", "entityType": "minecraft:arrow", "route": "NETHER_ENTRY", "variant": None, "graph": "standalone",
               "expectedNativeOutcome": "TRANSFER",
               "effectsControlReceipt": {"card": name, "path": "native-controls/ctl/headless-result.json",
                                         "receiptSha256": coverage.digest(receipt)}}
        receipt.write_text(json.dumps({"cards": [{"card": name, "outcome": "TRANSFERRED", "witness": fx_witness(damage=False)}]}),
                           encoding="utf-8")
        card = make_card(self.dir, name, "TRANSFERRED", witness, fx_trace("arrow"), "arrow")
        self.assertEqual(coverage.headless_claims(card, [row]), [])

    def test_gateway_needs_the_gateway_witness(self) -> None:
        witness = fx_witness("arrow", route="END_GATEWAY")
        self.assertEqual(self.claims("arrow", witness, witness, route="END_GATEWAY"), [])

    def test_rows_and_cards_resolve_by_kind_and_variant(self) -> None:
        self.assertEqual(coverage.effects_kind_of_row({"entityType": "minecraft:tnt", "variant": "fuse_near_expiry"}), "tnt_near_expiry")
        self.assertEqual(coverage.effects_kind_of_row({"entityType": "minecraft:tnt", "variant": None}), "tnt")
        self.assertIsNone(coverage.effects_kind_of_row({"entityType": "minecraft:tnt", "variant": "other"}))
        self.assertIsNone(coverage.effects_kind_of_row({"entityType": "minecraft:arrow", "graph": "boat+arrow"}))
