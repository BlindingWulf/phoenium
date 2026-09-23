"""Pinned 26.2 source guard for the exact Silverfish Nether caller prefix."""

from __future__ import annotations

import copy
import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import portal_native_census as census


ROOT = pathlib.Path(__file__).resolve().parents[4]
AUDIT = ROOT / ".audit/sol-ultra-final-campaign-20260923"
FROZEN = ROOT / ".audit/sol-ultra-portal-respawn-20260922-192748/PORTAL_REQUIRED_CASES.json"


class SilverfishNativeSourceTests(unittest.TestCase):
    def test_exact_prefix_proves_only_two_conditional_nether_routes(self) -> None:
        inventory = json.loads((AUDIT / "PINNED_NATIVE_INVENTORY.json").read_text())
        frozen = json.loads(FROZEN.read_text())
        self.assertEqual(inventory["sourceJarSha256"], census.PINNED_SOURCE_SHA)
        self.assertEqual(inventory["mappedBinarySha256"], census.PINNED_BINARY_SHA)
        generated = census.build_census(inventory, frozen)
        rows = {row["id"]: row for row in generated["rows"]}
        for route in ("NETHER_ENTRY", "NETHER_RETURN"):
            row = rows["V2-SILVERFISH-" + route]
            self.assertEqual(row["legacyCaseId"], "SILVERFISH-" + route)
            self.assertEqual(row["expectedNativeOutcome"], "TRANSFER")
            self.assertEqual(row["sourceProof"]["kind"],
                             "conditional_silverfish_prefix_nether_transfer")
            self.assertFalse(row["sourceProof"]["applicabilityDemonstrated"])
            self.assertEqual(row["sourceProof"]["tickReference"]["method"]["lexicalSuffixBytes"], 5)
        for route in ("END_ENTRY", "END_EXIT", "END_GATEWAY"):
            self.assertIsNone(rows["V2-SILVERFISH-" + route]["expectedNativeOutcome"])
        self.assertEqual(len(generated["frozenIdMapping"]), 843)
        self.assertEqual(generated["frozenIdMapping"]["SILVERFISH-NETHER_ENTRY"],
                         "V2-SILVERFISH-NETHER_ENTRY")

        changed = copy.deepcopy(inventory)
        silverfish = next(entity for entity in changed["entityTypes"]
                          if entity["entityType"] == "minecraft:silverfish")
        silverfish["methodReferences"]["tick"]["method"]["lexicalSuffixBytes"] = 6
        with self.assertRaisesRegex(ValueError, "pinned Silverfish Nether caller chain changed"):
            census.build_census(changed, frozen)


if __name__ == "__main__":
    unittest.main()
