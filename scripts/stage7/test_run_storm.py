"""Fail-closed trace accounting checks for the installed storm parser."""

import importlib.util
import io
from pathlib import Path
import json
import tempfile
import unittest


path = Path(__file__).with_name('run_storm.py')
spec = importlib.util.spec_from_file_location('run_storm', path)
storm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storm)


class StormAccountingTest(unittest.TestCase):
    def setUp(self):
        self.attempt = {'uuid': '00000000-0000-0000-0000-000000000001',
                        'family': 'fireball', 'source': storm.OW, 'region': 0}
        self.product = 'CANDIDATE'
        self.probe = 'PROBE'

    def event(self, phase, *, route='NETHER_ENTRY', destination='minecraft:the_nether',
              generation=1):
        return {'schema': 'stage7-portal-tx-v1', 'uuid': self.attempt['uuid'],
                'phase': phase, 'generation': generation, 'route': route,
                'sourceWorld': storm.OW, 'destinationWorld': destination,
                'candidateSha256': self.product, 'testSourceSha256': self.probe}

    def complete(self):
        return [self.event('CAPTURED_HURTING_PROJECTILE_TICK'),
                self.event('SOURCE_RETIRING'), self.event('SOURCE_RETIRED'),
                self.event('ADD_DURING_TELEPORT_RETURN'),
                self.event('CHUNK_MAP_TRACKING_RETURN'),
                self.event('TRANSFER_SETTLED_AFTER_SUFFIX')]

    def classify(self, events):
        return storm.classify_attempt(self.attempt, events, self.product, self.probe)

    def test_transfer_requires_complete_native_lifecycle(self):
        self.assertEqual(self.classify(self.complete())['outcome'], 'TRANSFER')
        with_native_head = self.complete() + [self.event('SOURCE_RETIRING')]
        self.assertEqual(self.classify(with_native_head)['outcome'], 'TRANSFER')
        for phase in ('SOURCE_RETIRED', 'ADD_DURING_TELEPORT_RETURN',
                      'CHUNK_MAP_TRACKING_RETURN', 'TRANSFER_SETTLED_AFTER_SUFFIX'):
            partial = [event for event in self.complete() if event['phase'] != phase]
            self.assertEqual(self.classify(partial)['outcome'], 'MISSING', phase)

    def test_refusal_is_only_explicit_native_refusal(self):
        self.assertEqual(self.classify([self.event('CAPTURED'),
                                        self.event('SETTLED_NATIVE_NO_TRANSFER')])['outcome'], 'REFUSAL')
        self.assertEqual(self.classify([self.event('CAPTURED')])['outcome'], 'MISSING')
        self.assertEqual(self.classify([])['outcome'], 'MISSING')

    def test_wrong_destination_route_and_hash_do_not_credit(self):
        for corrupt in ('route', 'destinationWorld', 'candidateSha256', 'testSourceSha256'):
            events = self.complete()
            target = events[0] if corrupt == 'route' else events[3]
            target[corrupt] = 'WRONG'
            with self.assertRaises(RuntimeError, msg=corrupt):
                self.classify(events)

    def test_duplicate_admission_does_not_credit(self):
        events = self.complete()
        events.append(self.event('ADD_DURING_TELEPORT_RETURN'))
        self.assertEqual(self.classify(events)['outcome'], 'MISSING')

    def test_boat_graph_requires_child_transfer_and_exact_live_edges(self):
        root = {'uuid': self.attempt['uuid'], 'family': 'boat_pig_graph',
                'source': storm.OW, 'region': 0, 'route': 'NETHER_ENTRY', 'index': 104}
        child = storm.graph_child_attempt(root)
        self.assertEqual(child['uuid'], str(storm.uuid.uuid5(storm.uuid.UUID(root['uuid']), 'passenger')))

        def member_events(member_uuid, capture_phase, source_object, destination_object):
            rows = []
            for phase in (capture_phase, 'SOURCE_RETIRING', 'SOURCE_RETIRED',
                          'ADD_DURING_TELEPORT_RETURN', 'CHUNK_MAP_TRACKING_RETURN',
                          'TRANSFER_SETTLED_AFTER_SUFFIX'):
                row = self.event(phase)
                row['uuid'] = member_uuid
                row['sourceObject'] = source_object
                row['object'] = (destination_object if phase in
                                 ('ADD_DURING_TELEPORT_RETURN', 'CHUNK_MAP_TRACKING_RETURN')
                                 else source_object)
                row['nanoTime'] = ({'CAPTURED_BOAT_TICK': 1,
                                    'CAPTURED_GRAPH_PASSENGER': 2,
                                    'SOURCE_RETIRING': 3,
                                    'SOURCE_RETIRED': 8 if member_uuid == root['uuid'] else 4,
                                    'ADD_DURING_TELEPORT_RETURN': 12 if member_uuid == root['uuid'] else 6,
                                    'CHUNK_MAP_TRACKING_RETURN': 11 if member_uuid == root['uuid'] else 5,
                                    'TRANSFER_SETTLED_AFTER_SUFFIX': 15}
                                   [phase])
                rows.append(row)
            if member_uuid == root['uuid']:
                attached = self.event('PASSENGER_ATTACHED')
                attached.update(uuid=member_uuid, sourceObject=source_object,
                                object=destination_object, nanoTime=13)
                rows.append(attached)
            return rows

        root_events = member_events(root['uuid'], 'CAPTURED_BOAT_TICK', 'source-boat', 'dest-boat')
        child_events = member_events(child['uuid'], 'CAPTURED_GRAPH_PASSENGER',
                                     'source-pig', 'dest-pig')
        root_outcome = storm.classify_attempt(root, root_events, self.product, self.probe)
        child_outcome = storm.classify_attempt(child, child_events, self.product, self.probe)
        self.assertEqual((root_outcome['outcome'], child_outcome['outcome']),
                         ('TRANSFER', 'TRANSFER'))
        boat = {'uuid': root['uuid'], 'object': 'dest-boat', 'world': storm.NETHER,
                'type': 'minecraft:oak_boat', 'removed': False, 'vehicleUuid': 'UNAVAILABLE',
                'passengerUuids': [child['uuid']]}
        pig = {'uuid': child['uuid'], 'object': 'dest-pig', 'world': storm.NETHER,
               'type': 'minecraft:pig', 'removed': False, 'vehicleUuid': root['uuid'],
               'passengerUuids': []}
        snapshot = {'schema': 'stage7-graph-snapshot-v1',
                    'rootUuid': root['uuid'], 'childUuid': child['uuid'],
                    'entities': [boat, pig]}
        receipt = storm.graph_snapshot_receipt(root, root_outcome, child_outcome,
                                               root_events, child_events,
                                               snapshot, 'Test passed')
        self.assertEqual(receipt['destinationEdges']['boatPassengers'], [child['uuid']])
        self.assertEqual(receipt['root']['sourceObject'], 'source-boat')
        self.assertEqual(receipt['child']['destinationObject'], 'dest-pig')
        self.assertLess(receipt['nativeOrderNanos']['childRetired'],
                        receipt['nativeOrderNanos']['rootRetired'])
        with self.assertRaisesRegex(RuntimeError, 'one settled transfer'):
            storm.graph_snapshot_receipt(root, root_outcome,
                                         storm.classify_attempt(child, [], self.product, self.probe),
                                         root_events, [], snapshot, 'Test passed')
        for bad in ({**snapshot, 'entities': [boat, {**pig, 'vehicleUuid': 'UNAVAILABLE'}]},
                    {**snapshot, 'entities': [boat, pig, dict(pig)]},
                    {**snapshot, 'entities': [boat, {**pig, 'object': 'source-pig'}]},
                    {**snapshot, 'childUuid': root['uuid']}):
            with self.assertRaises(RuntimeError):
                storm.graph_snapshot_receipt(root, root_outcome, child_outcome,
                                             root_events, child_events, bad, 'Test passed')
        reversed_retirement = [dict(event) for event in child_events]
        next(event for event in reversed_retirement if event['phase'] == 'SOURCE_RETIRED')['nanoTime'] = 9
        with self.assertRaisesRegex(RuntimeError, 'child-first order'):
            storm.graph_snapshot_receipt(root, root_outcome, child_outcome,
                                         root_events, reversed_retirement,
                                         snapshot, 'Test passed')
        self.assertIn('PHOENIUM_PASSENGER_IDENTITY_FIRST_FAILURE', storm.FAILURE_MARKERS)

    def test_plan_is_seeded_and_mixes_regions_and_directions(self):
        first = storm.planned_attempts(2704, 16, ('item', 'fireball'))
        second = storm.planned_attempts(2704, 16, ('item', 'fireball'))
        self.assertEqual(first, second)
        self.assertEqual(len({row['uuid'] for row in first}), 16)
        self.assertEqual({row['source'] for row in first}, {storm.OW, storm.NETHER})
        self.assertEqual({row['region'] for row in first}, {0, 1, 2, 3})
        self.assertEqual({row['source'] for row in first[:4]}, {storm.OW})
        self.assertEqual({row['source'] for row in first[4:8]}, {storm.NETHER})

    def test_portal_integrity_command_checks_all_twelve_blocks(self):
        check = storm.portal_check_command(storm.OW, 1)
        self.assertEqual(check.count('if block '), 12)
        for y in range(65, 69):
            for x in (511, 512, 513):
                self.assertIn(f'if block {x} {y} 0 minecraft:nether_portal', check)

    def test_portal_validation_repairs_only_damaged_lane(self):
        damaged = storm.portal_check_command(storm.OW, 1)
        seen = []
        def fake_rcon(command):
            seen.append(command)
            if command == damaged and seen.count(damaged) == 1:
                return 'Test failed'
            return 'Test passed' if 'if block ' in command else 'Changed block'
        with tempfile.TemporaryDirectory() as folder:
            repairs = storm.ensure_lanes(fake_rcon, Path(folder) / 'commands.jsonl')
        self.assertEqual(len(repairs), 1)
        self.assertEqual((repairs[0]['dimension'], repairs[0]['region']), (storm.OW, 1))
        self.assertEqual(seen.count(damaged), 2)
        self.assertEqual(sum(' run setblock ' in command for command in seen), 1)

    def test_console_pump_preserves_lifecycle_and_failure_while_counting_queries(self):
        first = b'[x] STAGE7_PORTAL_TX {"phase":"DESTINATION_QUERY","uuid":"u"}\n'
        second = b'[y] STAGE7_PORTAL_TX {"phase":"DESTINATION_QUERY_RESULT","uuid":"u"}\n'
        captured = b'[z] STAGE7_PORTAL_TX {"phase":"CAPTURED","uuid":"u"}\n'
        violation = b'[error] STAGE7_PORTAL_TX_VIOLATION {"phase":"NATIVE_UNRETAINED","uuid":"u"}\n'
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / 'console.log'
            summary = Path(folder) / 'queries.json'
            pump = storm.ConsolePump(io.BytesIO(first + second + first + captured + violation), log, summary)
            pump.drain()
            self.assertIsNone(pump.error)
            self.assertEqual(log.read_bytes(), captured + violation)
            data = json.loads(summary.read_text())
            self.assertEqual(data['suppressedLineCount'], 3)
            self.assertEqual(data['byUuidPhase']['u/DESTINATION_QUERY']['count'], 2)
            self.assertEqual(data['byUuidPhase']['u/DESTINATION_QUERY']['firstLine'], first.decode().strip())
            self.assertEqual(data['byUuidPhase']['u/DESTINATION_QUERY']['lastLine'], first.decode().strip())
            self.assertEqual(data['byUuidPhase']['u/DESTINATION_QUERY_RESULT']['count'], 1)

    def test_shulker_diagnostic_has_one_direct_tick_candidate_and_is_not_default(self):
        value = storm.deterministic_uuid(2704, 0)
        planned = storm.planned_attempts(2704, 1, ('shulker_bullet',))
        self.assertEqual(planned[0]['uuid'], str(value))
        self.assertEqual(planned[0]['route'], 'NETHER_ENTRY')
        self.assertNotIn('shulker_bullet', storm.FAMILIES)
        nbt = storm.summoning_nbt('shulker_bullet', value, ['stage7_storm'])
        self.assertIn('NoGravity:1b', nbt)
        self.assertIn('Motion:[0.0d,0.0d,-0.8d]', nbt)
        self.assertEqual(storm.spawn_z('shulker_bullet'), 3.5)

    def test_stationary_families_start_inside_portal(self):
        for family in storm.STATIONARY_FAMILIES:
            self.assertEqual(storm.spawn_z(family), .5, family)
        self.assertEqual(storm.spawn_z('item'), 3.5)

    def test_tnt_card_uses_dimension_scoped_selector_and_exact_fuse(self):
        command = storm.tnt_fuse_query_command(storm.NETHER, 'stage7_storm_0', 32)
        self.assertEqual(command,
            'execute in minecraft:the_nether positioned 32 66 0 run '
            'data get entity @e[tag=stage7_storm_0,type=minecraft:tnt,distance=..4096,limit=1] fuse')
        self.assertEqual(storm.parse_tnt_fuse('Primed TNT has the following entity data: 1198s'), 1198)
        with self.assertRaises(RuntimeError):
            storm.parse_tnt_fuse('No entity was found')

    def test_tnt_card_requires_destination_owned_suffix_and_post_arrival_tick(self):
        attempt = {'uuid': self.attempt['uuid'], 'family': 'tnt', 'index': 0,
                   'source': storm.OW, 'region': 0, 'route': 'NETHER_ENTRY'}
        outcome = {'outcome': 'TRANSFER', 'completeGenerations': [9]}
        event = {'generation': 9, 'phase': 'TNT_TELEPORT_SUFFIX',
                 'ownerWorld': storm.NETHER}
        commands = []
        def fake_rcon(command):
            commands.append(command)
            if ' data get entity ' in command:
                return f'Primed TNT has the following entity data: {1201 - sum(" data get entity " in c for c in commands)}s'
            return 'Test passed'
        with tempfile.TemporaryDirectory() as folder:
            receipt = storm.tnt_card_receipt(fake_rcon, Path(folder) / 'commands.jsonl',
                                             attempt, [event], outcome)
            self.assertEqual((receipt['arrivalFuse'], receipt['postArrivalFuse']), (1200, 1199))
            self.assertEqual(receipt['usedPortalWitness']['ownerWorld'], storm.NETHER)
            with self.assertRaises(RuntimeError):
                storm.tnt_card_receipt(fake_rcon, Path(folder) / 'commands.jsonl',
                                       attempt, [{**event, 'ownerWorld': storm.OW}], outcome)
        self.assertTrue(any('execute in minecraft:overworld positioned 256 66 0 run '
                            'execute unless entity @e[tag=stage7_storm_0,distance=..4096]' == c
                            for c in commands))

    def test_client_test_source_drift_is_metadata_while_observer_is_gated(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            client = root / 'src/clientGameTest/Card.java'
            observer = root / 'src/test/java/dev/phoenium/stage7repair/Observer.java'
            client.parent.mkdir(parents=True)
            observer.parent.mkdir(parents=True)
            client.write_text('before')
            observer.write_text('same')
            launch = {str(client.relative_to(root)): storm.sha(client),
                      str(observer.relative_to(root)): storm.sha(observer)}
            manifest = {'testSourceHashes': launch}
            client.write_text('after')
            old_work = storm.WORK
            storm.WORK = root
            try:
                result = {}
                storm.record_test_source_drift(result, manifest)
            finally:
                storm.WORK = old_work
            self.assertEqual(result['clientTestSourceChanges'], [str(client.relative_to(root))])
            self.assertEqual(result['executedTestSourceChanges'], [])
            self.assertNotEqual(result['clientTestSourceLaunchSha256'],
                                result['clientTestSourceEndSha256'])
            self.assertEqual(result['clientTestSourceDrift'][str(client.relative_to(root))]
                             ['launchSha256'], launch[str(client.relative_to(root))])

    def test_dimension_scoped_entity_commands_use_world_limited_selector(self):
        for operation in ('kill', 'present', 'absent'):
            command = storm.scoped_tag_command(storm.NETHER, 'stage7_storm_wave_0', operation)
            self.assertIn('execute in minecraft:the_nether positioned 0 66 0 run ', command)
            self.assertIn('@e[tag=stage7_storm_wave_0,distance=..4096]', command)
        teleport = storm.scoped_tag_command(storm.NETHER, 'stage7_storm_restart_sentinel',
                                            'teleport', center=(32, 66, 0), radius=16,
                                            destination=(72, 65, 5))
        self.assertEqual(teleport,
            'execute in minecraft:the_nether positioned 32 66 0 run '
            'tp @e[tag=stage7_storm_restart_sentinel,distance=..16] 72 65 5')
        source_absent = storm.scoped_tag_command(storm.OW, 'stage7_storm_restart_sentinel',
                                                 'absent', center=(256, 66, 0), radius=16)
        self.assertIn('execute in minecraft:overworld positioned 256 66 0', source_absent)
        self.assertIn('distance=..16', source_absent)

    def test_selector_preflight_checks_both_worlds_before_wave(self):
        seen = []
        def fake_rcon(command):
            seen.append(command)
            return 'No entity was found' if ' run kill ' in command else 'Test passed'
        with tempfile.TemporaryDirectory() as folder:
            receipts = storm.preflight_selectors(fake_rcon, Path(folder) / 'commands.jsonl')
        self.assertEqual(len(receipts), 6)
        self.assertEqual(len(seen), 6)
        self.assertEqual(sum('minecraft:overworld' in command for command in seen), 3)
        self.assertEqual(sum('minecraft:the_nether' in command for command in seen), 3)
        self.assertTrue(all('distance=..' in command for command in seen))

    def test_graph_segments_keep_exact_snapshot_below_rcon_limit(self):
        wave = [{'index': index, 'family': family} for index, family in enumerate(
            ['item', 'item', 'boat_pig_graph', 'boat_pig_graph',
             'tnt', 'tnt', 'tnt', 'tnt', 'boat_pig_graph'])]
        segments = storm.inspection_segments(wave)
        self.assertEqual([[entry['index'] for entry in segment] for segment in segments],
                         [[0, 1], [2], [3], [4, 5, 6, 7], [8]])
        self.assertEqual([entry for segment in segments for entry in segment], wave)
        self.assertTrue(all(len(segment) == 1 for segment in segments
                            if segment[0]['family'] == 'boat_pig_graph'))
        self.assertTrue(all(len(segment) <= len(storm.LANES) for segment in segments))

    def test_graph_probe_scopes_only_two_exact_uuids(self):
        root = '00000000-0000-0000-0000-000000000001'
        child = '00000000-0000-0000-0000-000000000002'
        self.assertEqual(storm.graph_probe_command(root, child),
                         'stage7-probe graph ' + root + ' ' + child)
        with self.assertRaises(ValueError):
            storm.graph_probe_command(root, '@e')

    def test_growing_log_parser_waits_for_complete_line_and_preserves_first_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / 'console.log'
            event = self.event('CAPTURED')
            payload = 'STAGE7_PORTAL_TX ' + json.dumps(event)
            log.write_bytes(payload[:25].encode())
            monitor = storm.TransactionMonitor(log)
            monitor.scan()
            self.assertFalse(monitor.by_uuid)
            with log.open('ab') as output:
                output.write(payload[25:].encode() + b'\n')
                output.write(b'[server] scheduler=FAILED first cause\n')
                output.write(b'[server] Encountered an unexpected exception secondary\n')
            monitor.scan()
            self.assertEqual(monitor.events(self.attempt['uuid'])[0]['phase'], 'CAPTURED')
            self.assertIn('scheduler=FAILED first cause', monitor.first_failure)


if __name__ == '__main__':
    unittest.main()
