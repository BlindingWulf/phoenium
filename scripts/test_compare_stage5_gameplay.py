import copy
import unittest
from compare_stage5_gameplay import canonical, canonical_callbacks, validate_intervals

FURNACE = dict(x=0, y=65, z=0, ingredient=64, fuel=63, output=0, lit=1599, cooking=2, total=200)
HEALTH = dict(duplicates=0, unexpectedBypasses=0, fallbacks=0, ownershipViolations=0,
              unleasedExecutions=0, hardBarriers=0, producerThreadChunkMutations=0,
              barrierTripped=False, lifecycle="RUNNING", intercepted=2, dispatched=2, executed=2, activeLeases=0)
BASE = dict(label="final", layout="distributed", gameTime=102, furnaces=[FURNACE], health=HEALTH,
            ticks=2, startGameTime=100, tickNanos=[10, 12])
BODY = dict(identity="furnace:0:tick1", region="a", lease=1, generation=1, operation=1,
            thread=1, selectedAt=0, claimStart=1, bodyStart=2, bodyEnd=6, claimEnd=8, completed=True)
CALLBACK = dict(x=0, y=65, z=0, events=[[101,"before",64,64,0,0,0,200],
    [101,"after",64,63,0,1600,1,200], [102,"before",64,63,0,1600,1,200], [102,"after",64,63,0,1599,2,200]])

class NativeComparisonTest(unittest.TestCase):
    def test_state_changes_and_row_order_are_not_normalized(self):
        baseline = canonical(BASE, 100)
        for field in ("ingredient", "fuel", "output", "lit", "cooking", "total", "x"):
            changed = copy.deepcopy(BASE); changed["furnaces"][0][field] += 1
            self.assertNotEqual(baseline, canonical(changed, 100), field)
        changed = copy.deepcopy(BASE); changed["furnaces"].append({**FURNACE, "x": 16})
        other = copy.deepcopy(changed); other["furnaces"].reverse()
        self.assertNotEqual(canonical(changed,100), canonical(other,100))

    def test_changed_clock_duplicates_unknown_fields_and_bad_health_fail(self):
        changes = [lambda s: s.update(gameTime=103), lambda s: s["tickNanos"].pop(),
            lambda s: s["furnaces"].append(copy.deepcopy(FURNACE)),
            lambda s: s["furnaces"][0].update(extra=1), lambda s: s["health"].update(activeLeases=1),
            lambda s: s["health"].update(executed=1), lambda s: s["health"].update(barrierTripped=True)]
        for change in changes:
            value = copy.deepcopy(BASE); change(value)
            with self.assertRaises(ValueError): canonical(value,100)

    def test_only_absolute_clock_origin_is_removed(self):
        changed = copy.deepcopy(BASE); changed.update(gameTime=202, startGameTime=200)
        self.assertEqual(canonical(BASE,100),canonical(changed,200))

    def test_native_callback_trace_and_negative_controls(self):
        canonical_callbacks([CALLBACK], [FURNACE], 100, 2)
        mutations = [lambda b: b["events"].pop(), lambda b: b["events"].reverse(),
            lambda b: b["events"][2].__setitem__(0,103), lambda b: b["events"][2].__setitem__(3,64),
            lambda b: b["events"][-1].__setitem__(4,1), lambda b: b.update(x=1)]
        for mutate in mutations:
            value = copy.deepcopy(CALLBACK); mutate(value)
            with self.assertRaises(ValueError): canonical_callbacks([value], [FURNACE],100,2)

    def test_overlap_requires_distinct_regions_threads_and_valid_claims(self):
        def measure(bodies):
            return dict(failedBodies=0,activeBodies=0,intervalCapacity=4096,intervals=bodies)
        peer = {**BODY,"identity":"peer:tick1","region":"b","thread":2}
        self.assertTrue(validate_intervals(measure([BODY,peer]),True)["naturalOverlapExamples"])
        with self.assertRaises(ValueError): validate_intervals(measure([BODY]),True)
        for change in (dict(region="a"), dict(thread=1), dict(claimEnd=3), dict(completed=False), dict(identity=BODY["identity"])):
            with self.assertRaises(ValueError): validate_intervals(measure([BODY,{**peer,**change}]),True)
        later = {**BODY,"lease":2,"identity":"later","bodyStart":7,"bodyEnd":8,"claimStart":5,"claimEnd":9}
        with self.assertRaises(ValueError): validate_intervals(measure([BODY,later]))

if __name__ == "__main__": unittest.main()
