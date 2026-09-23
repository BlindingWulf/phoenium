"""The no-entry plan must distinguish feasible installed cards from the knot proof."""

import unittest

import plan_no_native_entry as plan


class NoEntryPlanTests(unittest.TestCase):
    def test_five_leash_rows_remain_source_only_and_painting_is_planned(self):
        rows = []
        for entity_type in sorted(plan.TYPES):
            for route in plan.ROUTES:
                source_only = entity_type in plan.FENCE_LOCKED
                rows.append({
                    "id": "V2-" + entity_type.removeprefix("minecraft:").upper() + "-" + route,
                    "entityType": entity_type, "route": route, "graph": "standalone",
                    "expectedNativeOutcome": "NO_NATIVE_ENTRY_PATH",
                    "requiredAssertions": [] if source_only else plan.ASSERTIONS.copy(),
                    "sourceProof": {"nativeOutcome": "NO_NATIVE_ENTRY_PATH",
                                    "kind": "stable_fence_occupancy_excludes_portal_contact"
                                    if source_only else "tick_override_skips_portal_processing"},
                })
        result = plan.build_plan({"rows": rows, "sourceJarSha256": "A" * 64,
                                  "mappedBinarySha256": "B" * 64})
        self.assertEqual((result["plannedCount"], result["blockedCount"]), (55, 5))
        knot = [card for card in result["cards"] if card["entityType"] == "minecraft:leash_knot"]
        self.assertTrue(all(card["fixtureState"] == "BLOCKED"
                            and card["requiredWorkers"] == []
                            and card["creditableWithoutInstalledWitness"] for card in knot))
        painting = [card for card in result["cards"] if card["entityType"] == "minecraft:painting"]
        self.assertTrue(all(card["fixtureState"] == "PLANNED"
                            and not card["creditableWithoutInstalledWitness"]
                            and "SOUTH-facing" in card["placement"]["note"] for card in painting))


if __name__ == "__main__":
    unittest.main()
