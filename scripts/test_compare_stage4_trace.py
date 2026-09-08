"""Negative controls for the observable projection; no installed acceptance is fabricated."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
import compare_stage4_trace as comparator

UUID = "00000001-0000-0002-0000-000300000004"
BASE = {"startedAt": 100, "result": "PASS", "events": [
    "tick.schedule type=Block{minecraft:note_block} pos=BlockPos{x=8, y=65, z=8} trigger=102 priority=HIGH order=1",
    "tick.schedule type=WaterFluid$Source@ab pos=BlockPos{x=121, y=65, z=8} trigger=105 priority=NORMAL order=800",
    "tick.schedule type=WaterFluid$Source@ab pos=BlockPos{x=521, y=65, z=8} trigger=105 priority=NORMAL order=801",
    "block.dispatch pos=BlockPos{x=8, y=65, z=8} a=1 b=7 contextRegion=NONE route=REFERENCE",
    "block.dispatch.result pos=BlockPos{x=8, y=65, z=8} a=1 b=7 result=true",
    f"tracking.pair recipient=player entityId=17 uuid={UUID} gameTime=100 contextRegion=NONE route=REFERENCE",
    f"tracking.delivered recipient=player uuid={UUID} entityId=17 packet=Move fields={{entityId=17, xa=1}} gameTime=101",
    "tick.native.unpack pos=BlockPos{x=520, y=65, z=9} type=Block{minecraft:note_block} delay=316 currentTick=285 priority=NORMAL",
    "save.serialization.ticks chunk=[7, 0] gameTime=612 blocks=[SavedTick[type=Block{minecraft:stone_button}, pos=BlockPos{x=120, y=65, z=9}, delay=188, priority=LOW]] fluids=[]",
    "full.ready label=far center=32,0 radius=6 target=169 candidates=187",
]}

class ProjectionTest(unittest.TestCase):
    def project(self, report):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trace.json"
            path.write_text(json.dumps(report))
            return comparator.load(path)[1]

    def changed(self, old, new):
        report = copy.deepcopy(BASE)
        report["events"] = [event.replace(old, new) for event in report["events"]]
        return report

    def test_runtime_clock_identity_and_native_global_counter_normalization(self):
        report = self.changed("entityId=17", "entityId=31")
        report["events"] = [event.replace("order=800", "order=1200").replace("order=801", "order=1201")
                            .replace("gameTime=101", "gameTime=103") for event in report["events"]]
        self.assertEqual(self.project(BASE), self.project(report))

    def test_original_callback_order_is_strict(self):
        report = copy.deepcopy(BASE)
        report["events"][3:5] = reversed(report["events"][3:5])
        self.assertNotEqual(self.project(BASE), self.project(report))

    def test_packet_effect_order_is_strict(self):
        report = copy.deepcopy(BASE)
        report["events"][5:7] = reversed(report["events"][5:7])
        self.assertNotEqual(self.project(BASE), self.project(report))

    def test_callback_priority_explicit_order_ties_payload_and_saved_delay_stay_strict(self):
        for old, new in [("priority=HIGH", "priority=LOW"), ("order=1", "order=2"),
                         ("order=801", "order=800"), ("result=true", "result=false"),
                         ("xa=1", "xa=2"), ("delay=188", "delay=187"),
                         ("currentTick=285", "currentTick=286"), ("trigger=102", "trigger=103")]:
            with self.subTest(old=old):
                self.assertNotEqual(self.project(BASE), self.project(self.changed(old, new)))

    def test_duplicate_effect_is_not_deduplicated(self):
        report = copy.deepcopy(BASE)
        report["events"].insert(7, report["events"][6])
        self.assertNotEqual(self.project(BASE), self.project(report))

    def test_foreign_packet_identity_is_rejected(self):
        with self.assertRaises(ValueError):
            self.project(self.changed("fields={entityId=17", "fields={entityId=18"))

    def test_incomplete_full_and_unknown_events_are_rejected(self):
        with self.assertRaises(ValueError):
            self.project(self.changed("target=169", "target=120"))
        report = copy.deepcopy(BASE)
        report["events"].append("unknown.effect real=true")
        with self.assertRaises(ValueError):
            self.project(report)

    def test_internal_scan_and_intermediate_save_do_not_change_final_effects(self):
        report = copy.deepcopy(BASE)
        report["events"][:0] = ["tracking.recipient player=absent gameTime=100",
            "save.serialization.ticks chunk=[7, 0] gameTime=101 blocks=[] fluids=[]"]
        self.assertEqual(self.project(BASE), self.project(report))

if __name__ == "__main__":
    unittest.main()
