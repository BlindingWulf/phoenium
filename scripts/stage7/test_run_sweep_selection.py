"""Selection preflight must fail before creating an installed server instance."""

import pathlib
import tempfile
import unittest
from unittest.mock import patch

import run_sweep


class SelectionTests(unittest.TestCase):
    def test_current_exact_fireball_card_is_bound(self):
        self.assertIn("projectile-fireball", run_sweep.available_groups())
        self.assertEqual(run_sweep.validate_cards("P0-fireball-nether-return", ["projectile-fireball"]),
                         ["P0-fireball-nether-return"])
        with self.assertRaisesRegex(RuntimeError, "no exact client selection branch"):
            run_sweep.validate_cards("UNBOUND-projectile", ["projectile-fireball"])

    def test_arrow_diagnostic_selects_each_exact_bound_card(self):
        self.assertIn("arrow-trident", run_sweep.available_groups())
        self.assertEqual(run_sweep.validate_cards("P2-arrow-NETHER_RETURN", ["arrow-trident"]),
                         ["P2-arrow-NETHER_RETURN"])
        self.assertEqual(run_sweep.validate_cards("P2-trident-NETHER_RETURN", ["arrow-trident"]),
                         ["P2-trident-NETHER_RETURN"])
        with self.assertRaisesRegex(RuntimeError, "no exact client selection branch"):
            run_sweep.validate_cards("P2-shulker_bullet-NETHER_RETURN", ["arrow-trident"])

    def test_throwable_diagnostic_selects_only_exact_bound_card(self):
        self.assertIn("throwable-projectiles", run_sweep.available_groups())
        self.assertEqual(run_sweep.validate_cards("P3-experience_bottle-NETHER_RETURN", ["throwable-projectiles"]),
                         ["P3-experience_bottle-NETHER_RETURN"])
        with self.assertRaisesRegex(RuntimeError, "no exact client selection branch"):
            run_sweep.validate_cards("P3-egg-NETHER_RETURN", ["throwable-projectiles"])

    def test_empty_vehicle_selection_is_exact(self):
        self.assertIn("vehicle-empty", run_sweep.available_groups())
        for card in ("V0-oak_boat-NETHER_ENTRY", "V0-oak_boat-NETHER_RETURN",
                     "V0-minecart-NETHER_ENTRY", "V0-minecart-NETHER_RETURN"):
            self.assertEqual(run_sweep.validate_cards(card, ["vehicle-empty"]), [card])
        with self.assertRaisesRegex(RuntimeError, "no exact client selection branch"):
            run_sweep.validate_cards("V0-chest_boat-NETHER_ENTRY", ["vehicle-empty"])

    def test_no_entry_selection_uses_exact_planned_card_and_rejects_blocked_fixture(self):
        self.assertIn("no-native-entry", run_sweep.available_groups())
        self.assertEqual(run_sweep.validate_cards("N0-interaction-NETHER_ENTRY", ["no-native-entry"]),
                         ["N0-interaction-NETHER_ENTRY"])
        with self.assertRaisesRegex(RuntimeError, "no exact client selection branch"):
            run_sweep.validate_cards("N0-leash_knot-NETHER_ENTRY", ["no-native-entry"])
        with self.assertRaisesRegex(RuntimeError, "no exact client selection branch"):
            run_sweep.validate_cards("N0-egg-NETHER_ENTRY", ["no-native-entry"])

    def test_multiple_groups_or_duplicate_cards_rejected(self):
        with self.assertRaises(ValueError):
            run_sweep.validate_cards("P0-fireball-nether-return", ["projectile-fireball", "legacy"])
        with self.assertRaises(ValueError):
            run_sweep.validate_cards("P0-fireball-nether-return,P0-fireball-nether-return", ["projectile-fireball"])

    def test_missing_client_selection_property_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            work = pathlib.Path(temp)
            source = work / "src/clientGameTest/java/dev/phoenium/clienttest/Stage7RepairClientTest.java"
            source.parent.mkdir(parents=True)
            source.write_text('if (group.equals("projectile-fireball")) {}', encoding="utf-8")
            gradle = work / "scripts/stage7-repair-client.gradle"
            gradle.parent.mkdir(parents=True)
            gradle.write_text("'cards'", encoding="utf-8")
            with patch.object(run_sweep, "WORK", work), patch.object(run_sweep, "CLIENT_SOURCE", source):
                with self.assertRaisesRegex(RuntimeError, "does not implement"):
                    run_sweep.validate_cards("P0-fireball-nether-return", ["projectile-fireball"])


if __name__ == "__main__":
    unittest.main()
