"""Pinned-source guards for the three ordinary End falling-block obligations."""

from __future__ import annotations

import copy
import json
import pathlib
import sys
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import portal_native_census as census


ROOT = pathlib.Path(__file__).resolve().parents[4]
AUDIT = ROOT / ".audit/sol-ultra-final-campaign-20260923"
VANILLA = (ROOT / ".candidate-worktrees/respawn-lifecycle-repair/.gradle/loom-cache/"
           "minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2")


class FallingEndSourceTests(unittest.TestCase):
    def test_exact_native_path_and_three_conditional_transfer_rows(self) -> None:
        source = VANILLA / "minecraft-merged-043a8b3edf-26.2-sources.jar"
        mapped = VANILLA / "minecraft-merged-043a8b3edf-26.2.jar"
        self.assertEqual(census.file_sha(source), census.PINNED_SOURCE_SHA)
        self.assertEqual(census.file_sha(mapped), census.PINNED_BINARY_SHA)
        with zipfile.ZipFile(source) as sources, zipfile.ZipFile(mapped) as binary:
            refs = census.falling_end_entry_source_refs(sources, binary)
        self.assertEqual({key: row["line"] for key, row in refs.items()}, {
            "fallingBlockTick": 49, "fallingEntityCreation": 92,
            "fallingEntityTick": 148, "fallingEntityTeleport": 353,
            "endPortalEntry": 67, "endPortalDestination": 80,
            "crossDimensionCreation": 3069, "registration": 413,
        })
        inventory = json.loads((AUDIT / "PINNED_NATIVE_INVENTORY.json").read_text())
        inventory["fallingEndEntrySourceRefs"] = refs
        frozen = json.loads((ROOT / ".audit/sol-ultra-portal-respawn-20260922-192748/"
                             "PORTAL_REQUIRED_CASES.json").read_text())
        generated = census.build_census(inventory, frozen)
        rows = {row["id"]: row for row in generated["rows"]}
        for material in ("SAND", "GRAVEL", "ANVIL"):
            row = rows[f"V2-FALLING_{material}-END_ENTRY"]
            self.assertEqual(row["expectedNativeOutcome"], "TRANSFER")
            proof = row["sourceProof"]
            self.assertEqual(proof["kind"], "conditional_ordinary_falling_end_entry_transfer")
            self.assertEqual(proof["carriedBlockState"], "minecraft:" + material.lower())
            self.assertEqual(proof["reference"], refs["fallingEntityTick"])
            self.assertFalse(proof["applicabilityDemonstrated"])
        self.assertIsNone(rows["V2-ADDED-FALLING_STATE_MINECRAFT_SAND-END_EXIT"]["expectedNativeOutcome"])
        self.assertIsNone(rows["V2-ADDED-FALLING_STATE_MINECRAFT_RED_SAND-END_ENTRY"]["expectedNativeOutcome"])

        changed = copy.deepcopy(inventory)
        changed["fallingEndEntrySourceRefs"]["fallingEntityTick"]["sourceMethodSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "falling End transfer path changed"):
            census.build_census(changed, frozen)

        changed = copy.deepcopy(inventory)
        next(row for row in changed["fallingBlockStates"]
             if row["blockState"] == "minecraft:sand")["nativeProducer"] = "OtherBlock"
        with self.assertRaisesRegex(ValueError, "falling End transfer path changed"):
            census.build_census(changed, frozen)


if __name__ == "__main__":
    unittest.main()
