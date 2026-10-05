"""Pure-logic checks for the mixed storm gate: nothing here starts a server or a client."""

import unittest
import uuid
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mixed_storm as mixed  # noqa: E402
import run_storm as storm  # noqa: E402


class Args:
    workers = 4


def passing_result():
    counts = {f + '/' + r: {} for f in mixed.MIXED_ORDER for r in mixed.ALL_ROUTES}
    rows = []
    for route, need in mixed.MIN_PLAYER.items():
        rows += [{'kind': 'portal-' + route, 'ok': True} for _ in range(need)]
    rows += [{'kind': 'movement', 'ok': True}] * 3 + [{'kind': 'container', 'ok': True}] * 2
    rows += [{'kind': 'respawn', 'ok': True}, {'kind': 'reconnect', 'ok': True}]
    return {
        'routes': list(mixed.ALL_ROUTES), 'familyRouteCounts': counts, 'attemptCount': 100, 'actualTransfers': 100,
        'actualRefusals': 0, 'actualContacts': 100, 'graphChildTransfers': 6, 'expectedGraphTransfers': 6,
        'ownerViolations': 0, 'firstTxStatus': {'violations': 0, 'pending': 0},
        'restartTxStatus': {'violations': 0, 'pending': 0}, 'playerListAfterConnect': 'There are 1 of a max',
        'firstSaved': True, 'firstExitCode': 0, 'restartSaved': True, 'restartExitCode': 0, 'sourceWorldChanges': [],
        'configChanges': [], 'globalSentinelAfterRestart': 'Test passed. Count: 1',
        'mixed': {'gatewayAttempts': 30, 'gatewayOk': 30, 'containerMinecarts': 8, 'containerMinecartsOk': 8,
                  'saves': [{'ok': True}] * 10, 'playerRows': rows, 'restartPlayer': {'ok': True}, 'laneError': None,
                  'firstWaveWithClient': 0, 'lastWaveWithClient': 40, 'noClient': False},
    }


class MixedGateTest(unittest.TestCase):
    def test_complete_receipts_grant_every_condition(self):
        conditions = mixed.evaluate_gate(passing_result(), Args())
        self.assertTrue(all(conditions.values()), [k for k, v in conditions.items() if not v])

    def test_each_missing_lane_blocks_the_gate(self):
        cases = {
            'gatewayLane': lambda r: r['mixed'].update(gatewayOk=29),
            'containerContents': lambda r: r['mixed'].update(containerMinecartsOk=7),
            'periodicSaves': lambda r: r['mixed'].update(saves=[{'ok': True}] * 9),
            'realClientConnected': lambda r: r['mixed'].update(noClient=True),
            'playerRespawn': lambda r: r['mixed']['playerRows'].remove({'kind': 'respawn', 'ok': True}),
            'playerLaneCleanAndOverlapped': lambda r: r['mixed'].update(lastWaveWithClient=5),
            'restartedWorldPlayable': lambda r: r['mixed'].update(restartPlayer={'ok': False}),
            'everyAttemptTransferred': lambda r: r.update(actualRefusals=1),
            'noOwnerViolations': lambda r: r['restartTxStatus'].update(violations=1),
            'noPendingTransitions': lambda r: r['firstTxStatus'].update(pending=1),
            'restartSentinel': lambda r: r.update(globalSentinelAfterRestart='Test failed'),
            'allFourRoutesPlanned': lambda r: r.update(routes=['NETHER_ENTRY']),
        }
        for name, mutate in cases.items():
            result = passing_result()
            mutate(result)
            self.assertFalse(mixed.evaluate_gate(result, Args())[name], name)

    def test_no_client_run_can_never_grant_the_gate(self):
        result = passing_result()
        result['mixed']['noClient'] = True
        self.assertFalse(all(mixed.evaluate_gate(result, Args()).values()))

    def test_a_player_portal_trip_that_failed_does_not_count(self):
        result = passing_result()
        result['mixed']['playerRows'] = [dict(r, ok=False) if r['kind'] == 'portal-END_EXIT' else r
                                         for r in result['mixed']['playerRows']]
        self.assertFalse(mixed.evaluate_gate(result, Args())['playerPortalsBothDimensions'])


class GatewayReceiptTest(unittest.TestCase):
    attempt = {'uuid': 'aaaaaaaa-0000-0000-0000-000000000001', 'family': 'pig', 'index': 0}

    def body(self, world=mixed.END):
        row = {'uuid': self.attempt['uuid'], 'ownerWorld': world}
        return {'gatewayContactEvents': [row, row], 'gatewayTicketEvents': [row], 'gatewayTrackingEvents': [row]}

    def member(self, **kw):
        return dict({'uuid': self.attempt['uuid'], 'world': mixed.END, 'removed': False}, **kw)

    def test_exit_one_live_object_and_all_three_event_kinds_credit(self):
        row = mixed.gateway_receipt(self.attempt, 54.5, [self.member()], self.body())
        self.assertTrue(row['ok'], row)
        self.assertEqual(row['eventCounts'], {'contact': 2, 'ticket': 1, 'tracking': 1})

    def test_missing_exit_duplicate_or_wrong_owner_do_not_credit(self):
        self.assertFalse(mixed.gateway_receipt(self.attempt, 44.5, [self.member()], self.body())['ok'])
        self.assertFalse(mixed.gateway_receipt(self.attempt, 54.5, [self.member(), self.member()], self.body())['ok'])
        self.assertFalse(mixed.gateway_receipt(self.attempt, 54.5, [self.member()], self.body('minecraft:overworld'))['ok'])
        body = self.body()
        body['gatewayTicketEvents'] = []
        self.assertFalse(mixed.gateway_receipt(self.attempt, 54.5, [self.member()], body)['ok'])


class SmallHelpersTest(unittest.TestCase):
    def test_chest_minecart_items_need_both_stacks_in_their_slots(self):
        good = ('x has the following entity data: [{count: 5, id: "minecraft:cobblestone", Slot: 0b}, '
                '{count: 3, id: "minecraft:stick", Slot: 13b}]')
        self.assertTrue(mixed.items_intact(good))
        self.assertFalse(mixed.items_intact(good.replace('count: 5', 'count: 4')))
        self.assertFalse(mixed.items_intact(good.replace('Slot: 13b', 'Slot: 2b')))
        self.assertFalse(mixed.items_intact('No entity was found'))

    def test_offline_uuid_is_a_version_three_name_uuid(self):
        value = mixed.offline_uuid('PhoeniumTest0')
        self.assertEqual(len(value), 36)
        self.assertEqual(value[14], '3')

    def test_gateway_summons_are_stationary_and_tagged_for_the_probe(self):
        for family in mixed.GATEWAY_FAMILIES:
            nbt = mixed.gateway_nbt(family, uuid.UUID(int=1), ['a', mixed.GATEWAY_TAG])
            self.assertNotIn('Motion', nbt, family)
            self.assertIn(mixed.GATEWAY_TAG, nbt)

    def test_mixed_families_are_opt_in_and_stationary(self):
        for family in storm.MIXED_FAMILIES:
            self.assertNotIn(family, storm.FAMILIES)
            self.assertIn(family, storm.STATIONARY_FAMILIES)
            self.assertTrue(storm.summoning_nbt(family, uuid.UUID(int=2), ['t']).startswith('{'))
        self.assertEqual(len(storm.FAMILIES), 14)  # the frozen default mix is untouched

    def test_default_storm_has_no_hooks(self):
        self.assertEqual(storm.HOOKS, {})


if __name__ == '__main__':
    unittest.main()
