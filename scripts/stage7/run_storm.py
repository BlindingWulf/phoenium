"""Deterministic installed Stage 7 mixed portal storm.

This owns a fresh copied world and its two child server processes. Every summon is
given a deterministic UUID. Transaction observer events, rather than command
success, are the contact and transfer oracle. A failed namespace is never reused.

Example (after building the candidate and stage7RepairHarness):
  python -u scripts/stage7/run_storm.py --name final-w4 --candidate build/libs/phoenium-0.2.0-beta.1.jar

The optional --families and --attempts switches are for bounded diagnostics. The
bounded Nether storm gate requires the default family list, >= 2,000 actual portal
contacts, four lanes, both directions, W4, zero observer violations, and a
same-world restart with a transferred persistent sentinel.

An opt-in --routes END_ENTRY,END_EXIT run copies the normally stopped,
audited disposable N0 W4 fixture with pre-generated End lanes.
Gateway and connected-player contacts require separate observer/client contracts
and are not credited by this RCON runner.
"""

from __future__ import annotations

import argparse
import collections
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import uuid


WORK = Path(__file__).resolve().parents[2]
ROOT = WORK.parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
from campaign_paths import AUDIT, BASE_AUDIT
PINNED = ROOT / '.audit/container-close-admission-20260922/PINNED_PROFILE_MANIFEST.json'
ORACLE = ROOT / '.candidate-worktrees/respawn-lifecycle-repair/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2-sources.jar'
DEFAULT_WORLD_SEED = ROOT / '.audit/astra-max-stage7-20260922/manual-ready/server/STAGE7-LUNA-FINAL'
END_W4_WORLD_SEED = (BASE_AUDIT / 'installed/no-entry-full-scoped-75d6-no-native-entry-w4'
                     / 'server/STAGE7-REPAIR-W4')
END_W4_SEED_RECEIPT = END_W4_WORLD_SEED.parent.parent / 'first-result.json'
END_W4_SEED_RECEIPT_SHA = '6039822E48A137412BDD8675B2FEEF3B5FDC83C8B626C78FB00A5D88B8F7DC90'
END_W4_WORLD_MANIFEST_SHA = '0BB7B5DEE19A40AFEADF997B895DC15E33F6C134A818D1F3F51B532F4F951FFC'
FAMILIES = (
    'item', 'chicken', 'magma_cube', 'fireball', 'small_fireball',
    'dragon_fireball', 'wither_skull', 'wind_charge', 'breeze_wind_charge',
    'falling_block', 'tnt', 'oak_boat', 'minecart', 'boat_pig_graph',
)
DIAGNOSTIC_FAMILIES = ('shulker_bullet',)
# Opt-in families for the mixed gate (mixed_storm.py). They are never part of the frozen default mix.
MIXED_FAMILIES = ('pig', 'cow', 'zombified_piglin', 'chest_minecart')
MOB_PROFILE_FAMILIES = ('chicken', 'magma_cube', 'pig', 'cow', 'zombified_piglin')
STATIONARY_FAMILIES = (
    'chicken', 'magma_cube', 'falling_block', 'tnt',
    'oak_boat', 'minecart', 'boat_pig_graph',
    'pig', 'cow', 'zombified_piglin', 'chest_minecart',
)
# Optional extension points, empty for every default storm. mixed_storm.py registers callables that receive one
# StormContext: after_setup, segment_settled(ctx, segment, outcomes), between_waves(ctx, wave), after_waves,
# after_restart, finalize.
HOOKS: dict = {}
OW = 'minecraft:overworld'
NETHER = 'minecraft:the_nether'
END = 'minecraft:the_end'
DEFAULT_ROUTES = ('NETHER_ENTRY', 'NETHER_RETURN')
END_CROSS_ROUTES = ('END_ENTRY', 'END_EXIT')
ROUTE_WORLDS = {
    'NETHER_ENTRY': (OW, NETHER),
    'NETHER_RETURN': (NETHER, OW),
    'END_ENTRY': (OW, END),
    'END_EXIT': (END, OW),
}
# 32 Nether blocks separate the four portal neighborhoods. Every Nether lane
# lies within the copied seed's pre-generated chunks; a prior pilot proved that
# /forceload of a new distant Nether chunk can block this runtime's server tick.
# The eightfold Overworld coordinates are distinct native portal destinations.
LANES = (32, 64, 96, 128)
DEFAULT_PORTS = {'java': 25734, 'bedrock': 19234, 'rcon': 25834}
JAVA = Path(r'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe')
TX_PATTERN = re.compile(r'STAGE7_PORTAL_TX (\{.*\})')
FAILURE_MARKERS = (
    'scheduler=FAILED', 'Encountered an unexpected exception',
    'PHOENIUM_PASSENGER_IDENTITY_FIRST_FAILURE',
    'STAGE7_OBSERVER_FAILED', 'STAGE7_PORTAL_TX_VIOLATION',
    'A single server tick took', 'Considering it to be crashed',
    'Mixin apply for mod', 'Error executing task on Server',
    'Failed to handle packet', 'Exception ticking world',
    'Could not execute entrypoint',
)
QUERY_PHASES = ('DESTINATION_QUERY', 'DESTINATION_QUERY_RESULT')
QUERY_UUID = re.compile(rb'"uuid":"([^"]+)"')


def sha(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()


def hash_manifest(entries: dict[str, str]) -> str:
    canonical = json.dumps(entries, sort_keys=True, separators=(',', ':')).encode('utf-8')
    return hashlib.sha256(canonical).hexdigest().upper()


def client_test_source(name: str) -> bool:
    return Path(name).parts[:2] == ('src', 'clientGameTest')


def record_test_source_drift(result: dict, manifest: dict) -> None:
    launch = manifest['testSourceHashes']
    end = {name: sha(WORK / name) if (WORK / name).is_file() else 'MISSING'
           for name in launch}
    result['testSourceChanges'] = [name for name in launch if launch[name] != end[name]]
    result['clientTestSourceChanges'] = [name for name in result['testSourceChanges']
                                         if client_test_source(name)]
    result['executedTestSourceChanges'] = [name for name in result['testSourceChanges']
                                           if not client_test_source(name)]
    result['clientTestSourceLaunchSha256'] = hash_manifest(
        {name: digest for name, digest in launch.items() if client_test_source(name)})
    result['clientTestSourceEndSha256'] = hash_manifest(
        {name: digest for name, digest in end.items() if client_test_source(name)})
    result['clientTestSourceDrift'] = {
        name: {'launchSha256': launch[name], 'endSha256': end[name]}
        for name in result['clientTestSourceChanges']}


def utc() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def deterministic_uuid(seed: int, index: int) -> uuid.UUID:
    return uuid.uuid5(uuid.NAMESPACE_URL, f'phoenium-stage7-storm:{seed}:{index}')


def uuid_nbt(value: uuid.UUID) -> str:
    words = struct.unpack('>4i', value.bytes)
    return 'UUID:[I;' + ','.join(str(word) for word in words) + ']'


def tx_events(log: Path) -> list[dict]:
    """Only exact observer JSON lines count; malformed lines fail closed."""
    result = []
    for line_number, line in enumerate(log.open(encoding='utf-8', errors='replace'), 1):
        match = TX_PATTERN.search(line)
        if match:
            try:
                event = json.loads(match.group(1))
            except json.JSONDecodeError as error:
                raise RuntimeError(f'malformed observer event at {log}:{line_number}') from error
            if event.get('schema') != 'stage7-portal-tx-v1':
                raise RuntimeError(f'unexpected observer schema at {log}:{line_number}')
            result.append(event)
    return result


class TransactionMonitor:
    """Incrementally read a growing console; a 2,000-contact run is log-heavy."""

    def __init__(self, log: Path):
        self.log = log
        self.offset = 0
        self.tail = b''
        self.by_uuid = collections.defaultdict(list)
        self.first_failure = None

    def scan(self) -> None:
        with self.log.open('rb') as stream:
            stream.seek(self.offset)
            data = stream.read()
            self.offset = stream.tell()
        if not data:
            return
        lines = (self.tail + data).split(b'\n')
        self.tail = lines.pop()
        for raw in lines:
            line = raw.decode('utf-8', errors='replace')
            if self.first_failure is None and any(marker in line for marker in FAILURE_MARKERS):
                self.first_failure = line.strip()
            match = TX_PATTERN.search(line)
            if match:
                try:
                    event = json.loads(match.group(1))
                except json.JSONDecodeError as error:
                    raise RuntimeError(f'malformed observer event in {self.log}') from error
                if event.get('schema') != 'stage7-portal-tx-v1':
                    raise RuntimeError(f'unexpected observer schema in {self.log}')
                self.by_uuid[event.get('uuid')].append(event)

    def events(self, value: str) -> list[dict]:
        self.scan()
        return self.by_uuid[value]

    def require_healthy(self) -> None:
        self.scan()
        if self.first_failure:
            raise RuntimeError(f'first server failure: {self.first_failure}')


class ConsolePump:
    """Drain stdout without persisting millions of redundant query witnesses.

    The installed observer still receives and validates every event. Only the
    on-disk console omits the two repetitive destination-query phases; exact
    counts and first/last raw lines are saved separately for each UUID/phase.
    All other lines, including violations and native lifecycle phases, are
    written byte-for-byte to the console log.
    """

    def __init__(self, source, log: Path, summary: Path):
        self.source = source
        self.log = log
        self.summary = summary
        self.error = None
        self.thread = threading.Thread(target=self.drain, name='stage7-storm-console-pump', daemon=True)

    def start(self) -> None:
        self.thread.start()

    def join(self, timeout: float = 20) -> None:
        self.thread.join(timeout)
        if self.thread.is_alive():
            raise RuntimeError(f'storm console pump did not finish: {self.log}')
        if self.error:
            raise RuntimeError(f'storm console pump failed: {self.error}')

    def drain(self) -> None:
        counts = collections.defaultdict(lambda: {'count': 0, 'firstLine': None, 'lastLine': None})
        total = 0
        try:
            with self.log.open('wb', buffering=0) as output:
                for raw in self.source:
                    phase = next((name for name in QUERY_PHASES
                                  if b'STAGE7_PORTAL_TX {' in raw
                                  and f'"phase":"{name}"'.encode() in raw), None)
                    match = QUERY_UUID.search(raw) if phase else None
                    if match is None:
                        output.write(raw)
                        continue
                    key = match.group(1).decode('ascii') + '/' + phase
                    entry = counts[key]
                    line = raw.decode('utf-8', errors='replace').rstrip('\r\n')
                    entry['count'] += 1
                    if entry['firstLine'] is None:
                        entry['firstLine'] = line
                    entry['lastLine'] = line
                    total += 1
            self.summary.write_text(json.dumps({
                'schema': 'stage7-storm-query-summary-v1',
                'suppressedLineCount': total,
                'byUuidPhase': dict(counts),
            }, indent=2) + '\n', encoding='utf-8')
        except BaseException as error:
            self.error = repr(error)


def classify_attempt(attempt: dict, events: list[dict], product_sha: str,
                     probe_sha: str) -> dict:
    """Credit one exact UUID only after native retirement, admission, and settlement."""
    relevant = [e for e in events if e.get('uuid') == attempt['uuid']]
    for event in relevant:
        if event.get('candidateSha256') != product_sha or event.get('testSourceSha256') != probe_sha:
            raise RuntimeError(f'observer hash mismatch for {attempt["uuid"]}')
    generations = collections.defaultdict(list)
    for event in relevant:
        generations[event.get('generation')].append(event)
    expected_source = attempt['source']
    expected_route = attempt.get('route',
                                 'NETHER_ENTRY' if expected_source == OW else 'NETHER_RETURN')
    route_source, expected_dest = ROUTE_WORLDS[expected_route]
    if expected_source != route_source:
        raise RuntimeError(f'planned route/source mismatch for {attempt["uuid"]}')
    complete = []
    refused = []
    for generation, group in generations.items():
        phases = [event['phase'] for event in group]
        capture = [event for event in group if event['phase'].startswith('CAPTURED')]
        if not capture:
            continue
        if any(event.get('route') != expected_route or event.get('sourceWorld') != expected_source
               for event in capture):
            raise RuntimeError(f'wrong route/source for {attempt["uuid"]}: {capture}')
        if 'SETTLED_NATIVE_NO_TRANSFER' in phases:
            refused.append(generation)
            continue
        required = ('SOURCE_RETIRED', 'ADD_DURING_TELEPORT_RETURN',
                    'CHUNK_MAP_TRACKING_RETURN', 'TRANSFER_SETTLED_AFTER_SUFFIX')
        # SOURCE_RETIRING has two witnesses: the product's native-stage marker
        # and the observer's Entity.setRemoved HEAD. It is one retirement, not
        # two entity removals; SOURCE_RETIRED and admission are single events.
        if phases.count('SOURCE_RETIRING') >= 1 and all(phases.count(phase) == 1 for phase in required):
            admission = next(e for e in group if e['phase'] == 'ADD_DURING_TELEPORT_RETURN')
            tracking = next(e for e in group if e['phase'] == 'CHUNK_MAP_TRACKING_RETURN')
            if admission.get('destinationWorld') != expected_dest or tracking.get('destinationWorld') != expected_dest:
                raise RuntimeError(f'wrong destination for {attempt["uuid"]}: {expected_dest}')
            complete.append(generation)
    return {
        'uuid': attempt['uuid'], 'family': attempt['family'], 'source': expected_source,
        'route': expected_route, 'region': attempt['region'], 'contactCount':
        sum(e['phase'].startswith('CAPTURED') for e in relevant),
        'completeGenerations': complete, 'refusedGenerations': refused,
        'observedPhases': {str(key): [e['phase'] for e in value] for key, value in generations.items()},
        'outcome': 'TRANSFER' if complete else 'REFUSAL' if refused else 'MISSING',
    }


def graph_child_attempt(root: dict) -> dict:
    """The native graph child transfers recursively but adds no new portal contact."""
    if root['family'] != 'boat_pig_graph':
        raise ValueError('graph child requested for a standalone storm attempt')
    return {'uuid': str(uuid.uuid5(uuid.UUID(root['uuid']), 'passenger')),
            'family': 'pig_graph_child', 'source': root['source'],
            'region': root['region'], 'route': root['route']}


def graph_member_witness(outcome: dict, events: list[dict], expected_capture: str) -> dict:
    """Bind the one retired source object to the one tracked replacement object."""
    if outcome['outcome'] != 'TRANSFER' or len(outcome['completeGenerations']) != 1:
        raise RuntimeError(f'graph member lacks one settled transfer: {outcome}')
    generation = outcome['completeGenerations'][0]
    group = [event for event in events if event.get('generation') == generation]
    capture = [event for event in group if event.get('phase') == expected_capture]
    tracked = [event for event in group if event.get('phase') == 'CHUNK_MAP_TRACKING_RETURN']
    admitted = [event for event in group if event.get('phase') == 'ADD_DURING_TELEPORT_RETURN']
    if len(capture) != 1 or len(tracked) != 1 or len(admitted) != 1:
        raise RuntimeError(f'graph member native witnesses are incomplete: {outcome["uuid"]}')
    source_object = capture[0].get('sourceObject')
    destination_object = tracked[0].get('object')
    if not source_object or not destination_object or source_object == destination_object \
            or admitted[0].get('object') != destination_object \
            or tracked[0].get('sourceObject') != source_object:
        raise RuntimeError(f'graph member replacement identity changed: {outcome["uuid"]}')
    return {'uuid': outcome['uuid'], 'generation': generation,
            'sourceObject': source_object, 'destinationObject': destination_object,
            'capturePhase': expected_capture}


def graph_snapshot_receipt(root: dict, root_outcome: dict, child_outcome: dict,
                           root_events: list[dict], child_events: list[dict],
                           snapshot: dict, source_absence: str) -> dict:
    """Require both exact replacement UUIDs and their ordered vehicle edges."""
    if 'Test passed' not in source_absence:
        raise RuntimeError(f'boat graph source still addressable: {root["uuid"]}')
    child = graph_child_attempt(root)
    if (snapshot.get('schema') != 'stage7-graph-snapshot-v1'
            or snapshot.get('rootUuid') != root['uuid']
            or snapshot.get('childUuid') != child['uuid']):
        raise RuntimeError(f'boat graph probe selected another UUID pair: {root["uuid"]}')
    root_witness = graph_member_witness(root_outcome, root_events, 'CAPTURED_BOAT_TICK')
    child_witness = graph_member_witness(child_outcome, child_events, 'CAPTURED_GRAPH_PASSENGER')
    def one_time(outcome: dict, events: list[dict], phase: str) -> int:
        selected = [event.get('nanoTime') for event in events
                    if event.get('generation') == outcome['completeGenerations'][0]
                    and event.get('phase') == phase]
        if len(selected) != 1 or not isinstance(selected[0], int):
            raise RuntimeError(f'boat graph lacks one timed {phase}: {outcome["uuid"]}')
        return selected[0]

    child_retired = one_time(child_outcome, child_events, 'SOURCE_RETIRED')
    root_retired = one_time(root_outcome, root_events, 'SOURCE_RETIRED')
    child_admitted = one_time(child_outcome, child_events, 'ADD_DURING_TELEPORT_RETURN')
    root_admitted = one_time(root_outcome, root_events, 'ADD_DURING_TELEPORT_RETURN')
    attached = one_time(root_outcome, root_events, 'PASSENGER_ATTACHED')
    if not child_retired < root_retired or not child_admitted < root_admitted < attached:
        raise RuntimeError(f'boat graph lost native child-first order: {root["uuid"]}')
    destination = ROUTE_WORLDS[root['route']][1]
    rows = snapshot.get('entities')
    if not isinstance(rows, list):
        raise RuntimeError('boat graph probe snapshot lacks entities')
    selected = {}
    for member in (root_witness, child_witness):
        matches = [row for row in rows if row.get('uuid') == member['uuid']]
        if len(matches) != 1:
            raise RuntimeError(f'boat graph member has {len(matches)} published rows: {member["uuid"]}')
        row = matches[0]
        if row.get('world') != destination or row.get('removed') is not False \
                or row.get('object') != member['destinationObject']:
            raise RuntimeError(f'boat graph member is not the observed live replacement: {row}')
        selected[member['uuid']] = row
    boat, pig = selected[root['uuid']], selected[child['uuid']]
    if boat.get('type') != 'minecraft:oak_boat' or pig.get('type') != 'minecraft:pig' \
            or boat.get('vehicleUuid') != 'UNAVAILABLE' \
            or boat.get('passengerUuids') != [child['uuid']] \
            or pig.get('vehicleUuid') != root['uuid'] or pig.get('passengerUuids') != []:
        raise RuntimeError(f'boat graph destination edge mismatch: boat={boat} pig={pig}')
    return {'rootUuid': root['uuid'], 'childUuid': child['uuid'], 'route': root['route'],
            'sourceAbsence': source_absence, 'destinationWorld': destination,
            'root': root_witness, 'child': child_witness,
            'nativeOrderNanos': {'childRetired': child_retired, 'rootRetired': root_retired,
                                 'childAdmitted': child_admitted, 'rootAdmitted': root_admitted,
                                 'passengerAttached': attached},
             'destinationEdges': {'boatPassengers': boat['passengerUuids'],
                                  'pigVehicle': pig['vehicleUuid']}}


def graph_probe_command(root_uuid: str, child_uuid: str) -> str:
    # The unfiltered snapshot accumulates projectile/falling histories and
    # exceeds Minecraft's 4096-character RCON reply limit after earlier waves.
    # The test-only graph endpoint emits only the requested live UUID rows;
    # graph_snapshot_receipt still proves exact identity and reciprocal edges.
    return f'stage7-probe graph {uuid.UUID(root_uuid)} {uuid.UUID(child_uuid)}'


class StormContext:
    """What an opt-in hook may use; created only when a hook is registered."""

    def __init__(self, **values):
        self.__dict__.update(values)


def call_hook(name: str, ctx, *arguments):
    hook = HOOKS.get(name)
    if hook is not None:
        return hook(ctx, *arguments)
    return None


class Rcon:
    def __init__(self, port: int, password: str):
        self.port = port
        self.password = password

    def __call__(self, command: str) -> str:
        with socket.create_connection(('127.0.0.1', self.port), timeout=5) as sock:
            sock.settimeout(60)

            def send(packet_id: int, kind: int, value: str) -> None:
                body = value.encode('utf-8')
                sock.sendall(struct.pack('<iii', len(body) + 10, packet_id, kind) + body + b'\0\0')

            def receive() -> tuple[int, str]:
                def exact(count: int) -> bytes:
                    data = b''
                    while len(data) < count:
                        chunk = sock.recv(count - len(data))
                        if not chunk:
                            raise EOFError(f'RCON closed for {command}')
                        data += chunk
                    return data
                count = struct.unpack('<i', exact(4))[0]
                if not 10 <= count <= 1_000_000:
                    raise RuntimeError(f'bad RCON packet size {count}')
                body = exact(count)
                return struct.unpack('<i', body[:4])[0], body[8:-2].decode(errors='replace')

            send(1, 3, self.password)
            authorized, _ = receive()
            if authorized != 1:
                raise RuntimeError('RCON authentication failed')
            send(2, 2, command)
            packet_id, response = receive()
            if packet_id != 2:
                raise RuntimeError(f'RCON response ID mismatch for {command}: {packet_id}')
            return response


def summoning_nbt(family: str, value: uuid.UUID, tags: list[str]) -> str:
    common = uuid_nbt(value) + ',Tags:[' + ','.join(json.dumps(tag) for tag in tags) + ']'
    motion = ',Motion:[0.0d,0.0d,-0.8d]'
    if family == 'item':
        return '{' + common + ',Item:{id:"minecraft:cobblestone",count:1},PickupDelay:32767s' + motion + '}'
    if family in ('pig', 'cow', 'zombified_piglin'):
        return '{' + common + ',PersistenceRequired:1b,NoAI:1b,NoGravity:1b' + motion + '}'
    if family == 'chest_minecart':
        return ('{' + common + ',NoGravity:1b' + motion
                + ',Items:[{Slot:0b,id:"minecraft:cobblestone",count:5},{Slot:13b,id:"minecraft:stick",count:3}]}')
    if family in ('chicken', 'magma_cube'):
        size = ',Size:0' if family == 'magma_cube' else ''
        return '{' + common + ',PersistenceRequired:1b,NoAI:1b,NoGravity:1b' + size + motion + '}'
    if family in ('fireball', 'small_fireball', 'dragon_fireball', 'wither_skull',
                  'wind_charge', 'breeze_wind_charge'):
        return '{' + common + ',Motion:[0.0d,0.0d,-0.4d]' + '}'
    if family == 'shulker_bullet':
        # Exact direct-tick caller diagnostic. With no target, pinned native
        # ShulkerBullet.tick retains its initial motion and reaches the portal.
        return '{' + common + ',NoGravity:1b,Motion:[0.0d,0.0d,-0.8d]}'
    if family == 'falling_block':
        return '{' + common + ',BlockState:{Name:"minecraft:sand"},Time:1,NoGravity:1b' + motion + '}'
    if family == 'tnt':
        return '{' + common + ',fuse:1200s,NoGravity:1b' + motion + '}'
    if family in ('oak_boat', 'minecart'):
        return '{' + common + ',NoGravity:1b' + motion + '}'
    if family == 'boat_pig_graph':
        child = uuid.uuid5(value, 'passenger')
        passenger_tags = ','.join(json.dumps(tag) for tag in tags)
        return ('{' + common + ',NoGravity:1b' + motion
                + ',Passengers:[{id:"minecraft:pig",' + uuid_nbt(child)
                + ',Tags:[' + passenger_tags + '],NoAI:1b,PersistenceRequired:1b}]}')
    raise ValueError(f'unimplemented family {family}')


def entity_id(family: str) -> str:
    return 'minecraft:oak_boat' if family == 'boat_pig_graph' else 'minecraft:' + family


def planned_attempts(seed: int, count: int, families: tuple[str, ...],
                     routes: tuple[str, ...] = DEFAULT_ROUTES) -> list[dict]:
    if not routes or len(set(routes)) != len(routes) or any(route not in ROUTE_WORLDS for route in routes):
        raise ValueError('unknown or repeated storm route')
    result = []
    for index in range(count):
        family = families[(index // (len(LANES) * len(routes))) % len(families)]
        route_slot = index % (len(LANES) * len(routes))
        region = route_slot % len(LANES)
        route = routes[route_slot // len(LANES)]
        source = ROUTE_WORLDS[route][0]
        value = deterministic_uuid(seed, index)
        result.append({'index': index, 'uuid': str(value), 'family': family,
                       'region': region, 'source': source, 'route': route})
    return result


def inspection_segments(wave: list[dict]) -> list[list[dict]]:
    """Keep exact graph snapshots below the installed probe's 4096-character RCON limit.

    A graph member pair needs the full probe snapshot for native object identities
    and reciprocal passenger edges. Clean that pair after its receipt, before the
    next pair enters the same global snapshot. Other families retain four lanes
    per segment; all attempts, routes, workers, and outcome checks are unchanged.
    """
    segments = []
    offset = 0
    while offset < len(wave):
        if wave[offset]['family'] == 'boat_pig_graph':
            size = 1
        else:
            size = 0
            while (offset + size < len(wave) and size < len(LANES)
                   and wave[offset + size]['family'] != 'boat_pig_graph'):
                size += 1
        segments.append(wave[offset:offset + size])
        offset += size
    return segments


def lane_x(source: str, region: int) -> int:
    return LANES[region] * (8 if source == OW else 1)


def portal_origin(attempt: dict) -> tuple[int, int, int]:
    """Return the exact source block, keeping End portals away from Nether lanes."""
    route = attempt['route']
    source, _ = ROUTE_WORLDS[route]
    if source != attempt['source']:
        raise ValueError(f'route/source mismatch: {route}/{attempt["source"]}')
    if route in DEFAULT_ROUTES:
        return lane_x(source, attempt['region']), 66, 0
    return LANES[attempt['region']], 65, 8


def spawn_position(attempt: dict) -> tuple[float, float, float]:
    x, y, z = portal_origin(attempt)
    if attempt['route'] in END_CROSS_ROUTES:
        # EndPortalBlock is a horizontal contact surface. The pinned I0 item
        # fixture enters at Y=65.25 inside the exact portal block.
        return x + .5, y + .25, z + .5
    return x + .5, y, z + spawn_z(attempt['family'])


def spawn_z(family: str) -> float:
    # NoAI and vehicle/block physics may suppress or redirect initial Motion.
    # Start those families within the portal rather than assuming travel from
    # the approach lane. Items and projectiles still enter under native motion.
    return .5 if family in STATIONARY_FAMILIES else 3.5


def preexisting_chunks(world: Path, dimension: str) -> set[tuple[int, int]]:
    """Read only Anvil location tables; do not hydrate/generate test chunks."""
    result = set()
    directory = world / 'dimensions/minecraft' / dimension / 'region'
    for file in directory.glob('r.*.*.mca'):
        _, region_x, region_z, _ = file.name.split('.')
        base_x, base_z = int(region_x) * 32, int(region_z) * 32
        with file.open('rb') as stream:
            header = stream.read(4096)
        if len(header) != 4096:
            raise RuntimeError(f'truncated Anvil header: {file}')
        for slot in range(1024):
            if int.from_bytes(header[slot * 4:slot * 4 + 3], 'big'):
                result.add((base_x + slot % 32, base_z + slot // 32))
    return result


def check_nether_lane_seed(world: Path) -> list[tuple[int, int]]:
    available = preexisting_chunks(world, 'the_nether')
    needed = sorted({(chunk_x, chunk_z) for x in LANES
                   for chunk_x in range((x - 16) >> 4, ((x + 16) >> 4) + 1)
                   for chunk_z in range((-16) >> 4, (16 >> 4) + 1)})
    missing = [position for position in needed if position not in available]
    if missing:
        raise RuntimeError(f'Nether storm lane would force-load ungenerated chunks: {missing}')
    return needed


def check_end_lane_seed(world: Path) -> list[tuple[int, int]]:
    """Reject the C2ME ungenerated-chunk wait before starting any End route."""
    available = preexisting_chunks(world, 'the_end')
    needed = {(chunk_x, chunk_z) for x in LANES
              for chunk_x in range((x - 16) >> 4, ((x + 16) >> 4) + 1)
              for chunk_z in range((8 - 16) >> 4, ((8 + 16) >> 4) + 1)}
    # Native End entry creates the central arrival platform around (100, 50, 0).
    needed.update((chunk_x, chunk_z)
                  for chunk_x in range((100 - 16) >> 4, ((100 + 16) >> 4) + 1)
                  for chunk_z in range((-16) >> 4, (16 >> 4) + 1))
    missing = sorted(needed - available)
    if missing:
        raise RuntimeError(f'End storm lane/platform would load ungenerated chunks: {missing}')
    return sorted(needed)


def first_failure(log: Path) -> str | None:
    for line in log.open(encoding='utf-8', errors='replace'):
        if any(marker in line for marker in FAILURE_MARKERS):
            return line.strip()
    return None


def wait_ready(process: subprocess.Popen, log: Path, port: int, timeout: float = 240) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.storm_pump.error:
            raise RuntimeError(f'console pump failed during bootstrap: {process.storm_pump.error}')
        if process.poll() is not None:
            raise RuntimeError(f'server exited during bootstrap: {process.returncode}')
        first = first_failure(log)
        if first:
            raise RuntimeError(f'server bootstrap first failure: {first}')
        data = log.read_text(encoding='utf-8', errors='replace')
        if 'Done (' in data and f'Started Geyser on 127.0.0.1:{port}' in data:
            return
        time.sleep(.3)
    raise RuntimeError('server bootstrap timeout')


def require_status(rcon: Rcon, product_sha: str, probe_sha: str) -> dict:
    raw = rcon('stage7-tx status')
    try:
        result = json.loads(raw[raw.index('{'):])
    except (ValueError, json.JSONDecodeError) as error:
        raise RuntimeError(f'unreadable stage7-tx status: {raw}') from error
    if result.get('candidateSha256') != product_sha or result.get('testSourceSha256') != probe_sha:
        raise RuntimeError(f'stage7-tx hash mismatch: {result}')
    if result.get('violations'):
        raise RuntimeError(f'stage7-tx observer violation: {result.get("firstViolation")}')
    return result


def command(rcon: Rcon, journal: Path, text: str, *, contains: str | None = None) -> str:
    response = rcon(text)
    with journal.open('a', encoding='utf-8') as stream:
        stream.write(json.dumps({'utc': utc(), 'command': text, 'response': response}) + '\n')
    if response.startswith(('An unexpected error occurred', 'Unknown or incomplete command')):
        raise RuntimeError(f'RCON execution failed: {text!r}: {response!r}')
    if contains and contains not in response:
        raise RuntimeError(f'command failed: {text!r}: {response!r}')
    return response


def scoped_tag_command(dimension: str, tag: str, operation: str, *,
                       center: tuple[float, float, float] = (0, 66, 0),
                       radius: int = 4096,
                       destination: tuple[int, int, int] | None = None) -> str:
    """Make an @e selector world-limited under pinned 26.2 selector rules.

    `execute in` alone changes CommandSourceStack.level, but an unqualified
    @e enumerates every server world. A distance option calls
    EntitySelectorParser.setWorldLimited (pinned source line 110), after which
    EntitySelector.findEntities uses only sender.getLevel (lines 168-175).
    """
    if not re.fullmatch(r'[a-zA-Z0-9_]+', tag):
        raise ValueError(f'invalid entity tag {tag!r}')
    if not 0 < radius <= 4096:
        raise ValueError('scoped selector radius must be within the 4096-block fixture envelope')
    x, y, z = center
    selector = f'@e[tag={tag},distance=..{radius}]'
    prefix = f'execute in {dimension} positioned {x} {y} {z} run '
    if operation == 'kill':
        return prefix + 'kill ' + selector
    if operation == 'present':
        return prefix + 'execute if entity ' + selector
    if operation == 'absent':
        return prefix + 'execute unless entity ' + selector
    if operation == 'teleport' and destination is not None:
        return prefix + 'tp ' + selector + ' ' + ' '.join(str(axis) for axis in destination)
    raise ValueError(f'invalid scoped entity operation {operation!r}')


def tnt_fuse_query_command(dimension: str, tag: str, center_x: int) -> str:
    if not re.fullmatch(r'[a-zA-Z0-9_]+', tag):
        raise ValueError(f'invalid entity tag {tag!r}')
    return (f'execute in {dimension} positioned {center_x} 66 0 run '
            f'data get entity @e[tag={tag},type=minecraft:tnt,distance=..4096,limit=1] fuse')


def parse_tnt_fuse(response: str) -> int:
    match = re.search(r': (-?\d+)s\s*$', response)
    if not match:
        raise RuntimeError(f'TNT fuse NBT receipt missing short value: {response!r}')
    return int(match.group(1))


def tnt_card_receipt(rcon: Rcon, journal: Path, attempt: dict,
                     events: list[dict], outcome: dict, timeout: float = 12) -> dict:
    """Observe the exact retired source, destination suffix, and live TNT after arrival.

    `usedPortal=true` is inferred from the pinned native callsite's ICONST_1 and
    the observed destination-owned `setUsedPortal` invocation. The private
    field is not serialized, so the receipt states that evidence precisely.
    """
    if attempt['family'] != 'tnt' or outcome['outcome'] != 'TRANSFER':
        raise ValueError('TNT card requires one settled TNT transfer')
    source = attempt['source']
    destination = NETHER if source == OW else OW
    tag = f'stage7_storm_{attempt["index"]}'
    generation = outcome['completeGenerations'][0]
    suffix = [event for event in events
              if event.get('generation') == generation and event.get('phase') == 'TNT_TELEPORT_SUFFIX']
    if len(suffix) != 1 or suffix[0].get('ownerWorld') != destination:
        raise RuntimeError(f'TNT destination-owned usedPortal suffix missing: {attempt["uuid"]} {suffix}')
    source_x = lane_x(source, attempt['region'])
    destination_x = lane_x(destination, attempt['region'])
    source_absence = command(rcon, journal,
        scoped_tag_command(source, tag, 'absent', center=(source_x, 66, 0), radius=4096),
        contains='Test passed')
    destination_presence = command(rcon, journal,
        scoped_tag_command(destination, tag, 'present', center=(destination_x, 66, 0), radius=4096),
        contains='Test passed')
    query = tnt_fuse_query_command(destination, tag, destination_x)
    before_text = command(rcon, journal, query)
    before = parse_tnt_fuse(before_text)
    if not 0 < before <= 1200:
        raise RuntimeError(f'TNT arrived with impossible fuse {before}: {attempt["uuid"]}')
    deadline = time.monotonic() + timeout
    after_text, after = before_text, before
    while time.monotonic() < deadline and after >= before:
        time.sleep(.1)
        after_text = command(rcon, journal, query)
        after = parse_tnt_fuse(after_text)
    if not 0 < after < before:
        raise RuntimeError(f'TNT had no post-arrival native tick: {attempt["uuid"]} {before}->{after}')
    return {'uuid': attempt['uuid'], 'route': attempt['route'], 'generation': generation,
            'sourceAbsence': source_absence, 'destinationPresence': destination_presence,
            'usedPortalWitness': {'phase': 'TNT_TELEPORT_SUFFIX', 'ownerWorld': destination,
                                  'setterArgumentTrueByPinnedBytecode': True},
            'arrivalFuse': before, 'postArrivalFuse': after,
            'arrivalFuseResponse': before_text, 'postArrivalFuseResponse': after_text}


def portal_check_command(source: str, region: int) -> str:
    x = lane_x(source, region)
    conditions = ' '.join(f'if block {block_x} {y} 0 minecraft:nether_portal'
                          for y in range(65, 69) for block_x in range(x - 1, x + 2))
    return f'execute in {source} run execute {conditions}'


def end_portal_check_command(source: str, region: int) -> str:
    if source not in (OW, END):
        raise ValueError('End portal source must be Overworld or End')
    x = LANES[region]
    return f'execute in {source} run execute if block {x} 65 8 minecraft:end_portal'


def build_lane(rcon: Rcon, journal: Path, source: str, region: int,
               *, force_load: bool) -> None:
    x = lane_x(source, region)
    prefix = f'execute in {source} run '
    operations = [
        f'fill {x-12} 64 -12 {x+12} 64 12 minecraft:sea_lantern',
        f'fill {x-12} 65 -12 {x+12} 76 12 minecraft:air',
        f'fill {x-2} 64 0 {x+2} 69 0 minecraft:obsidian',
        f'fill {x-1} 65 0 {x+1} 68 0 minecraft:air',
        f'setblock {x} 65 0 minecraft:fire',
    ]
    if force_load:
        operations.insert(0, f'forceload add {x-16} -16 {x+16} 16')
    for operation in operations:
        command(rcon, journal, prefix + operation)
    command(rcon, journal, portal_check_command(source, region), contains='Test passed')


def build_end_lane(rcon: Rcon, journal: Path, source: str, region: int,
                   *, force_load: bool) -> None:
    x = LANES[region]
    prefix = f'execute in {source} run '
    operations = [
        f'fill {x-6} 64 2 {x+6} 64 14 minecraft:obsidian',
        f'fill {x-6} 65 2 {x+6} 76 14 minecraft:air',
        f'setblock {x} 65 8 minecraft:end_portal',
    ]
    if force_load:
        operations.insert(0, f'forceload add {x-16} -8 {x+16} 24')
    for operation in operations:
        command(rcon, journal, prefix + operation)
    command(rcon, journal, end_portal_check_command(source, region), contains='Test passed')


def setup_lanes(rcon: Rcon, journal: Path,
                routes: tuple[str, ...] = DEFAULT_ROUTES) -> None:
    # End-only runs need only lane zero of the Nether pair for the saved
    # restart sentinel. Avoid generating three unrelated Overworld lanes.
    nether_regions = range(len(LANES)) if any(route in DEFAULT_ROUTES for route in routes) else range(1)
    for source in (OW, NETHER):
        for region in nether_regions:
            build_lane(rcon, journal, source, region, force_load=True)
    for route in routes:
        if route not in END_CROSS_ROUTES:
            continue
        source = ROUTE_WORLDS[route][0]
        for region in range(len(LANES)):
            build_end_lane(rcon, journal, source, region, force_load=True)


def ensure_lanes(rcon: Rcon, journal: Path,
                 routes: tuple[str, ...] = DEFAULT_ROUTES) -> list[dict]:
    """Validate all twelve blocks per portal; repair only damaged test lanes."""
    repaired = []
    nether_regions = range(len(LANES)) if any(route in DEFAULT_ROUTES for route in routes) else range(1)
    for source in (OW, NETHER):
        for region in nether_regions:
            query = portal_check_command(source, region)
            response = command(rcon, journal, query)
            if response == 'Test passed':
                continue
            if response != 'Test failed':
                raise RuntimeError(f'unreadable portal integrity check: {source} lane {region}: {response!r}')
            build_lane(rcon, journal, source, region, force_load=False)
            repaired.append({'dimension': source, 'region': region,
                             'x': lane_x(source, region), 'before': response})
    for route in routes:
        if route not in END_CROSS_ROUTES:
            continue
        source = ROUTE_WORLDS[route][0]
        for region in range(len(LANES)):
            query = end_portal_check_command(source, region)
            response = command(rcon, journal, query)
            if response == 'Test passed':
                continue
            if response != 'Test failed':
                raise RuntimeError(f'unreadable End portal integrity check: {route} lane {region}: {response!r}')
            build_end_lane(rcon, journal, source, region, force_load=False)
            repaired.append({'dimension': source, 'route': route, 'region': region,
                             'x': LANES[region], 'z': 8, 'before': response})
    return repaired


def preflight_selectors(rcon: Rcon, journal: Path,
                        routes: tuple[str, ...] = DEFAULT_ROUTES) -> dict:
    """Check the exact world-limited selector grammar before wave summons."""
    absent_tag = 'stage7_storm_selector_preflight'
    receipts = {}
    for dimension in (OW, NETHER):
        receipts['empty-' + dimension] = command(rcon, journal,
            scoped_tag_command(dimension, absent_tag, 'absent'), contains='Test passed')
        receipts['kill-' + dimension] = command(rcon, journal,
            scoped_tag_command(dimension, absent_tag, 'kill'))
        if receipts['kill-' + dimension] != 'No entity was found':
            raise RuntimeError(f'selector preflight unexpectedly found {absent_tag} in {dimension}')
    receipts['source-absence'] = command(rcon, journal,
        scoped_tag_command(OW, absent_tag, 'absent',
                           center=(lane_x(OW, 0), 66, 0), radius=16),
        contains='Test passed')
    receipts['destination-absence'] = command(rcon, journal,
        scoped_tag_command(NETHER, absent_tag, 'absent',
                           center=(72, 65, 5), radius=8), contains='Test passed')
    if any(route in END_CROSS_ROUTES for route in routes):
        receipts['empty-' + END] = command(rcon, journal,
            scoped_tag_command(END, absent_tag, 'absent'), contains='Test passed')
        receipts['kill-' + END] = command(rcon, journal,
            scoped_tag_command(END, absent_tag, 'kill'))
        if receipts['kill-' + END] != 'No entity was found':
            raise RuntimeError(f'selector preflight unexpectedly found {absent_tag} in {END}')
        receipts['end-lane-absence'] = command(rcon, journal,
            scoped_tag_command(END, absent_tag, 'absent', center=(LANES[0], 65, 8), radius=16),
            contains='Test passed')
        # The End seed was produced by a prior installed audit. It must not
        # contain any storm UUID/tag before this new namespace begins.
        for dimension in (OW, NETHER, END):
            receipts['inherited-storm-absence-' + dimension] = command(rcon, journal,
                scoped_tag_command(dimension, 'stage7_storm', 'absent'),
                contains='Test passed')
    return receipts


def launch(server: Path, console: Path, workers: int) -> subprocess.Popen:
    args = [str(JAVA), '-Xmx2G', '-Dphoenium.test.diagnostics=true',
            f'-Dphoenium.gameplay.workers={workers}', '-Djava.net.useSystemProxies=false',
            '-Dminecraft.api.session.host=http://127.0.0.1:9',
            '-Dminecraft.api.services.host=http://127.0.0.1:9',
            '-jar', 'fabric-server-launch.jar', 'nogui']
    console.touch(exist_ok=False)
    process = subprocess.Popen(args, cwd=server, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               creationflags=subprocess.CREATE_NO_WINDOW)
    process.storm_pump = ConsolePump(process.stdout, console,
        console.with_name(console.stem + '-query-summary.json'))
    process.storm_pump.start()
    return process


def require_pump_healthy(process: subprocess.Popen) -> None:
    if process.storm_pump.error:
        raise RuntimeError(f'storm console pump failed: {process.storm_pump.error}')


def stop(process: subprocess.Popen, log: Path, result: dict, phase: str) -> None:
    if process.poll() is None:
        try:
            process.stdin.write(b'save-all flush\nstop\n')
            process.stdin.flush()
            result[phase + 'NormalStopRequested'] = True
        except (BrokenPipeError, OSError):
            pass
        try:
            process.wait(timeout=90)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=20)
            result[phase + 'ForcedStop'] = True
    try:
        process.storm_pump.join()
    except RuntimeError as error:
        result[phase + 'PumpError'] = str(error)
    result[phase + 'ExitCode'] = process.poll()
    result[phase + 'Saved'] = 'All dimensions are saved' in log.read_text(encoding='utf-8', errors='replace')


def require_fresh_copy_target(run: Path, target: Path) -> Path:
    """Resolve every recursive copy destination under the new audit namespace."""
    resolved_run = run.resolve(strict=True)
    resolved_target = target.resolve(strict=False)
    if (not resolved_target.is_relative_to(resolved_run)
            or resolved_target == resolved_run or target.exists()):
        raise RuntimeError(f'recursive copy target is not fresh inside storm namespace: {target}')
    return target


def prepare(args: argparse.Namespace, run: Path, candidate: Path, probe: Path,
            pinned: dict, product_sha: str, probe_sha: str,
            routes: tuple[str, ...] = DEFAULT_ROUTES) -> tuple[Path, dict]:
    run.mkdir(parents=True, exist_ok=False)
    server = run / 'server'
    server.mkdir()
    template = Path(pinned['template'])
    for name, digest in pinned['providers'].items():
        if sha(template / 'mods' / name) != digest:
            raise RuntimeError(f'pinned provider drift: {name}')
    for name, digest in pinned['configFilesSha256'].items():
        if sha(template / 'config' / name) != digest:
            raise RuntimeError(f'pinned config drift: {name}')
    for name in ('libraries', 'versions'):
        shutil.copytree(template / name, require_fresh_copy_target(run, server / name),
                        copy_function=lambda source, dest: os.link(source, dest))
    shutil.copytree(template / 'config', require_fresh_copy_target(run, server / 'config'))
    for name in ('fabric-server-launch.jar', 'eula.txt'):
        shutil.copy2(template / name, server / name)
    (server / 'mods').mkdir()
    for name in pinned['providers']:
        os.link(template / 'mods' / name, server / 'mods' / name)
    installed_name = 'phoenium-stage7-storm-' + product_sha[:12].lower() + '.jar'
    shutil.copy2(candidate, server / 'mods' / installed_name)
    shutil.copy2(probe, server / 'mods' / 'stage7-repair-probe.jar')
    end_cross = any(route in END_CROSS_ROUTES for route in routes)
    source_world = END_W4_WORLD_SEED if end_cross else DEFAULT_WORLD_SEED
    target_world = require_fresh_copy_target(run, server / 'STAGE7-STORM-W4')
    if not source_world.is_dir():
        raise RuntimeError(f'missing pinned disposable world seed: {source_world}')
    if end_cross:
        if source_world.resolve() != END_W4_WORLD_SEED.resolve():
            raise RuntimeError('End storm seed is not the audited W4 fixture')
        if sha(END_W4_SEED_RECEIPT) != END_W4_SEED_RECEIPT_SHA:
            raise RuntimeError('audited End W4 seed receipt hash drift')
        seed_receipt = json.loads(END_W4_SEED_RECEIPT.read_text(encoding='utf-8'))
        if not (seed_receipt.get('pass') is True and seed_receipt.get('txPass') is True
                and seed_receipt.get('normalSave') is True
                and seed_receipt.get('serverExitCode') == 0):
            raise RuntimeError('audited End W4 seed did not pass and save/stop normally')
    nether_chunks = check_nether_lane_seed(source_world)
    end_chunks = check_end_lane_seed(source_world) if end_cross else []
    shutil.copytree(source_world, target_world)
    source_hashes = {str(file.relative_to(source_world)): sha(file) for file in source_world.rglob('*')
                     if file.is_file() and file.name != 'session.lock'}
    world_manifest_sha = hash_manifest(source_hashes)
    if end_cross and world_manifest_sha != END_W4_WORLD_MANIFEST_SHA:
        raise RuntimeError('audited End W4 source world hash drift')
    if any(sha(target_world / name) != digest for name, digest in source_hashes.items()):
        raise RuntimeError('isolated world copy did not match source')
    properties = template.joinpath('server.properties').read_text().splitlines()
    overrides = {'server-port': str(args.java_port), 'level-name': target_world.name,
                 'enable-rcon': 'true', 'rcon.port': str(args.rcon_port),
                 'rcon.password': 'phoenium-stage7-local-test',
                 'motd': 'Phoenium Stage7 isolated mixed portal storm'}
    overrides.update(getattr(args, 'server_properties', None) or {})
    properties = [line.split('=')[0] + '=' + overrides.pop(line.split('=')[0])
                  if '=' in line and line.split('=')[0] in overrides else line for line in properties]
    properties += [key + '=' + value for key, value in overrides.items()]
    (server / 'server.properties').write_text('\n'.join(properties) + '\n')
    geyser = server / 'config/Geyser-Fabric/config.yml'
    data = geyser.read_text()
    if '19172' not in data:
        raise RuntimeError('pinned Geyser bind absent')
    geyser.write_text(data.replace('19172', str(args.bedrock_port)))
    git_head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=WORK, text=True).strip()
    git_status = subprocess.check_output(['git', 'status', '--short'], cwd=WORK, text=True)
    hashes = {}
    for base in (WORK / 'src/clientGameTest', WORK / 'src/test/java/dev/phoenium/stage7repair',
                 WORK / 'src/stage7RepairHarness'):
        hashes.update({str(file.relative_to(WORK)): sha(file) for file in base.rglob('*') if file.is_file()})
    manifest = {
        'schema': 'stage7-storm-input-v1', 'utc': utc(), 'candidate': str(candidate),
        'candidateSha256': product_sha, 'installedCandidateName': installed_name,
        'testProbeSha256': probe_sha, 'nativeSourceSha256': sha(ORACLE),
        'stormHarnessSha256': sha(Path(__file__)),
        'pinnedProviderHashes': pinned['providers'],
        'installedConfigHashes': {name: sha(server / 'config' / name)
                                  for name in pinned['configFilesSha256']},
        'serverPropertiesSha256': sha(server / 'server.properties'),
        'worldSource': str(source_world), 'worldSourceHashes': source_hashes,
        'worldSourceManifestSha256': world_manifest_sha,
        'worldSeedKind': 'audited-n0-w4-end-pregenerated' if end_cross else 'manual-ready-nether',
        'worldSeedReceipt': str(END_W4_SEED_RECEIPT) if end_cross else None,
        'worldSeedReceiptSha256': sha(END_W4_SEED_RECEIPT) if end_cross else None,
        'head': git_head, 'sourceStatus': git_status, 'testSourceHashes': hashes,
        'clientTestSourceLaunchSha256': hash_manifest(
            {name: digest for name, digest in hashes.items() if client_test_source(name)}),
        'seed': args.seed, 'attempts': args.attempts, 'waveSize': args.wave_size,
        'families': args.families, 'routes': list(routes),
        'lanes': list(LANES), 'workers': args.workers,
        'queryConsoleCompression': {'suppressedPhases': list(QUERY_PHASES),
                                    'perUuidPhaseCountAndFirstLastRawLines': True,
                                    'allOtherLinesRetained': True},
        'stationaryFamiliesSpawnZ': .5, 'movingFamiliesSpawnZ': 3.5,
        'preexistingNetherFixtureChunks': nether_chunks,
        'preexistingEndFixtureChunks': end_chunks,
        'ports': {'java': args.java_port, 'bedrock': args.bedrock_port,
                  'rcon': args.rcon_port},
    }
    (run / 'inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return server, manifest


def check_idle_ports(args: argparse.Namespace) -> None:
    for port, kind in ((args.java_port, socket.SOCK_STREAM),
                       (args.rcon_port, socket.SOCK_STREAM),
                       (args.bedrock_port, socket.SOCK_DGRAM)):
        with socket.socket(socket.AF_INET, kind) as sock:
            sock.bind(('127.0.0.1', port))


def verify_source_unchanged(manifest: dict) -> list[str]:
    source_world = Path(manifest['worldSource'])
    return [name for name, digest in manifest['worldSourceHashes'].items()
            if sha(source_world / name) != digest]


def run(args: argparse.Namespace) -> int:
    monotonic_start = time.monotonic()
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.name):
        raise ValueError('name must use ASCII letters, numbers, underscore or hyphen')
    if args.attempts <= 0 or args.wave_size <= 0 or args.minimum_contacts <= 0 or args.wave_timeout <= 0:
        raise ValueError('attempts, wave size, minimum contacts, and wave timeout must be positive')
    if args.minimum_contacts > args.attempts:
        raise ValueError('minimum contacts cannot exceed attempts')
    families = tuple(args.families.split(','))
    if not families or len(set(families)) != len(families) \
            or any(family not in FAMILIES + DIAGNOSTIC_FAMILIES + MIXED_FAMILIES for family in families):
        raise ValueError('unknown or repeated storm family')
    routes = tuple(args.routes.split(','))
    if not routes or len(set(routes)) != len(routes) or any(route not in ROUTE_WORLDS for route in routes):
        raise ValueError('unknown or repeated storm route; gateway needs a separate same-object receipt')
    if args.tnt_card and (families != ('tnt',) or args.attempts != 1 or args.workers != 1):
        raise ValueError('TNT post-arrival tick card requires W1, one TNT, and the loaded first lane')
    if args.tnt_card and routes != DEFAULT_ROUTES:
        raise ValueError('TNT post-arrival card is only fixtured for the default Nether route pair')
    candidate = args.candidate.resolve(strict=True)
    probe = args.probe.resolve(strict=True)
    product_sha, probe_sha = sha(candidate), sha(probe)
    if sha(ORACLE) != '055D5A99D49917A03F68007A1AC2F1D0CBD84E28A16D953C6FFC943CE3E15E1E':
        raise RuntimeError('mapped native source drift')
    pinned = json.loads(PINNED.read_text(encoding='utf-8-sig'))
    check_idle_ports(args)
    output_root = AUDIT / 'storm'
    output_root.mkdir(parents=True, exist_ok=True)
    run_dir = output_root / args.name
    if run_dir.exists():
        raise RuntimeError(f'storm namespace already exists; preserve it: {run_dir}')
    server, manifest = prepare(args, run_dir, candidate, probe, pinned, product_sha, probe_sha,
                               routes)
    rcon = Rcon(args.rcon_port, 'phoenium-stage7-local-test')
    result = {'schema': 'stage7-storm-result-v1', 'startUtc': utc(),
              'candidateSha256': product_sha, 'testProbeSha256': probe_sha,
              'routes': list(routes), 'worldSeedKind': manifest['worldSeedKind'],
              'worldSeedReceiptSha256': manifest['worldSeedReceiptSha256'],
              'worldSourceManifestSha256': manifest['worldSourceManifestSha256'],
              'firstFailure': None, 'pass': False}
    first_log = run_dir / 'first-server-console.log'
    restart_log = run_dir / 'restart-server-console.log'
    journal = run_dir / 'commands.jsonl'
    first_process = None
    restart_process = None
    try:
        first_process = launch(server, first_log, args.workers)
        result['firstPid'] = first_process.pid
        print('STORM SERVER', first_process.pid, args.name, flush=True)
        wait_ready(first_process, first_log, args.bedrock_port)
        monitor = TransactionMonitor(first_log)
        setup_lanes(rcon, journal, routes)
        ctx = StormContext(args=args, rcon=rcon, journal=journal, monitor=monitor, result=result,
                           run_dir=run_dir, server=server, first_process=first_process,
                           routes=routes, product_sha=product_sha, probe_sha=probe_sha,
                           manifest=manifest, completed=None)
        call_hook('after_setup', ctx)
        result['lastTxStatus'] = require_status(rcon, product_sha, probe_sha)
        result['selectorPreflight'] = preflight_selectors(rcon, journal, routes)
        planned = planned_attempts(args.seed, args.attempts, families, routes)
        (run_dir / 'planned-attempts.json').write_text(json.dumps(planned, indent=2) + '\n')
        completed = []
        observed_threads = set()
        observed_owner_regions = set()
        max_observed_pending = 0
        result['portalIntegrityChecks'] = 0
        result['portalRepairs'] = []
        result['completedSegmentCleanups'] = 0
        result['graphChildTransfers'] = 0
        result['graphMemberReceipts'] = []
        if args.tnt_card:
            result['tntCardReceipts'] = []
        for wave_number, start in enumerate(range(0, len(planned), args.wave_size)):
            wave = planned[start:start + args.wave_size]
            started = time.monotonic()
            wave_results = []
            segment_receipts = []
            for segment_number, segment in enumerate(inspection_segments(wave)):
                require_pump_healthy(first_process)
                monitor.require_healthy()
                repairs = ensure_lanes(rcon, journal, routes)
                result['portalIntegrityChecks'] += (2 * (len(LANES) if any(
                    route in DEFAULT_ROUTES for route in routes) else 1)
                    + len(LANES) * sum(route in END_CROSS_ROUTES for route in routes))
                result['portalRepairs'].extend({'wave': wave_number, 'segment': segment_number,
                                                **repair} for repair in repairs)
                segment_receipt = {'segment': segment_number, 'attemptIndices':
                                   [attempt['index'] for attempt in segment],
                                   'portalRepairs': repairs}
                segment_receipts.append(segment_receipt)
                segment_tag = f'stage7_storm_segment_{wave_number}_{segment_number}'
                for attempt in segment:
                    if first_process.poll() is not None:
                        raise RuntimeError(f'server exited while summoning wave {wave_number} segment {segment_number}')
                    require_pump_healthy(first_process)
                    monitor.require_healthy()
                    family = attempt['family']
                    source = attempt['source']
                    x, y, z = spawn_position(attempt)
                    value = uuid.UUID(attempt['uuid'])
                    tags = ['stage7_storm', f'stage7_storm_wave_{wave_number}',
                            segment_tag, f'stage7_storm_{attempt["index"]}']
                    if family == 'boat_pig_graph':
                        tags.append('stage7_vehicle_storm')
                    nbt = summoning_nbt(family, value, tags)
                    spawn = (f'execute in {source} run summon {entity_id(family)} '
                             f'{x} {y} {z} {nbt}')
                    command(rcon, journal, spawn, contains='Summoned new')
                deadline = time.monotonic() + args.wave_timeout
                last = []
                graph_outcomes = {}
                settled_status = None
                while time.monotonic() < deadline:
                    if first_process.poll() is not None:
                        raise RuntimeError(f'server exited while settling wave {wave_number} segment {segment_number}')
                    require_pump_healthy(first_process)
                    monitor.require_healthy()
                    settled_status = require_status(rcon, product_sha, probe_sha)
                    result['lastTxStatus'] = settled_status
                    max_observed_pending = max(max_observed_pending, settled_status.get('pending', 0))
                    last = [classify_attempt(attempt, monitor.events(attempt['uuid']),
                                             product_sha, probe_sha) for attempt in segment]
                    graph_outcomes = {
                        attempt['uuid']: classify_attempt(
                            child, monitor.events(child['uuid']), product_sha, probe_sha)
                        for attempt in segment if attempt['family'] == 'boat_pig_graph'
                        for child in (graph_child_attempt(attempt),)
                    }
                    if all(item['outcome'] in ('TRANSFER', 'REFUSAL') for item in last) \
                            and all(item['outcome'] in ('TRANSFER', 'REFUSAL')
                                    for item in graph_outcomes.values()) \
                            and settled_status.get('pending') == 0:
                        break
                    time.sleep(.5)
                segment_receipt['graphChildOutcomes'] = graph_outcomes
                wave_results.extend(last)
                (run_dir / f'wave-{wave_number:04d}.json').write_text(json.dumps({
                    'wave': wave_number, 'durationSeconds': time.monotonic() - started,
                    'segments': segment_receipts, 'outcomes': wave_results}, indent=2) + '\n')
                observed_so_far = completed + last
                partial = collections.defaultdict(lambda: {'attempts': 0, 'contacts': 0,
                                                            'transfers': 0, 'refusals': 0,
                                                            'missing': 0})
                for item in observed_so_far:
                    key = item['family'] + '/' + item['route']
                    partial[key]['attempts'] += 1
                    partial[key]['contacts'] += item['contactCount']
                    partial[key]['transfers'] += item['outcome'] == 'TRANSFER'
                    partial[key]['refusals'] += item['outcome'] == 'REFUSAL'
                    partial[key]['missing'] += item['outcome'] == 'MISSING'
                result['observedFamilyRouteCounts'] = dict(partial)
                result['observedContacts'] = sum(item['contactCount'] for item in observed_so_far)
                result['ownerViolations'] = settled_status.get('violations') if settled_status else None
                missing = [item for item in last if item['outcome'] == 'MISSING']
                if missing:
                    first = missing[0]
                    raise RuntimeError(f'wave {wave_number} segment {segment_number} missing native contact/settlement: '
                                       f'{first["family"]} {first["route"]} UUID={first["uuid"]} '
                                       f'phases={first["observedPhases"]}')
                refused = [item for item in last if item['outcome'] == 'REFUSAL']
                if refused:
                    first = refused[0]
                    raise RuntimeError(f'wave {wave_number} segment {segment_number} native refusal: '
                                       f'{first["family"]} {first["route"]} UUID={first["uuid"]}')
                for root_uuid, child_outcome in graph_outcomes.items():
                    if child_outcome['outcome'] != 'TRANSFER':
                        raise RuntimeError(f'wave {wave_number} segment {segment_number} graph child '
                                           f'{child_outcome["outcome"]}: root={root_uuid} '
                                           f'child={child_outcome["uuid"]} '
                                           f'phases={child_outcome["observedPhases"]}')
                if settled_status is None or settled_status.get('pending') != 0:
                    raise RuntimeError(f'wave {wave_number} segment {segment_number} left pending transactions: '
                                       f'{settled_status}')
                for attempt in segment:
                    for event in monitor.events(attempt['uuid']):
                        if event['phase'].startswith('CAPTURED'):
                            observed_threads.add(event.get('thread', 'UNAVAILABLE'))
                            context = event.get('ownerContext', 'UNAVAILABLE')
                            observed_owner_regions.add(context.split(', lease=', 1)[0])
                completed.extend(last)
                ctx.completed = completed
                call_hook('segment_settled', ctx, segment, last)
                for attempt, outcome in zip(segment, last):
                    if attempt['family'] != 'boat_pig_graph':
                        continue
                    child = graph_child_attempt(attempt)
                    child_outcome = graph_outcomes[attempt['uuid']]
                    source_absence = command(rcon, journal, scoped_tag_command(
                        attempt['source'], f'stage7_storm_{attempt["index"]}', 'absent',
                        center=portal_origin(attempt)),
                        contains='Test passed')
                    snapshot = json.loads(command(rcon, journal,
                        graph_probe_command(attempt['uuid'], child['uuid'])))
                    receipt = graph_snapshot_receipt(
                        attempt, outcome, child_outcome,
                        monitor.events(attempt['uuid']), monitor.events(child['uuid']),
                        snapshot, source_absence)
                    segment_receipt.setdefault('graphMembers', []).append(receipt)
                    result['graphMemberReceipts'].append(receipt)
                    result['graphChildTransfers'] += 1
                if graph_outcomes:
                    monitor.require_healthy()
                    result['lastTxStatus'] = require_status(rcon, product_sha, probe_sha)
                if args.tnt_card:
                    for attempt, outcome in zip(segment, last):
                        receipt = tnt_card_receipt(rcon, journal, attempt,
                                                   monitor.events(attempt['uuid']), outcome)
                        result['tntCardReceipts'].append(receipt)
                        segment_receipt.setdefault('tntCards', []).append(receipt)
                    monitor.require_healthy()
                    result['lastTxStatus'] = require_status(rcon, product_sha, probe_sha)
                # Retired objects and destination replacements are cleaned
                # before the opposite route can use a portal they might damage.
                for dimension in (OW, NETHER) + ((END,) if any(
                        route in END_CROSS_ROUTES for route in routes) else ()):
                    command(rcon, journal, scoped_tag_command(dimension, segment_tag, 'kill'))
                segment_receipt['globalCleanup'] = command(
                    rcon, journal, f'execute unless entity @e[tag={segment_tag}]',
                    contains='Test passed')
                result['completedSegmentCleanups'] += 1
                for attempt in segment:
                    monitor.by_uuid.pop(attempt['uuid'], None)
                    if attempt['family'] == 'boat_pig_graph':
                        monitor.by_uuid.pop(graph_child_attempt(attempt)['uuid'], None)
                (run_dir / f'wave-{wave_number:04d}.json').write_text(json.dumps({
                    'wave': wave_number, 'durationSeconds': time.monotonic() - started,
                    'segments': segment_receipts, 'outcomes': wave_results}, indent=2) + '\n')
            status = require_status(rcon, product_sha, probe_sha)
            print('STORM WAVE', wave_number, len(completed), 'events', status['events'],
                  'elapsed', round(time.monotonic() - started, 2), flush=True)
            call_hook('between_waves', ctx, wave_number)
        grouped = collections.defaultdict(lambda: {'attempts': 0, 'contacts': 0,
                                                     'transfers': 0, 'refusals': 0})
        for item in completed:
            key = item['family'] + '/' + item['route']
            grouped[key]['attempts'] += 1
            grouped[key]['contacts'] += item['contactCount']
            grouped[key]['transfers'] += item['outcome'] == 'TRANSFER'
            grouped[key]['refusals'] += item['outcome'] == 'REFUSAL'
        result['familyRouteCounts'] = dict(grouped)
        result['attemptCount'] = len(completed)
        result['actualContacts'] = sum(item['contactCount'] for item in completed)
        result['actualTransfers'] = sum(item['outcome'] == 'TRANSFER' for item in completed)
        result['actualRefusals'] = sum(item['outcome'] == 'REFUSAL' for item in completed)
        result['expectedGraphTransfers'] = sum(item['family'] == 'boat_pig_graph'
                                               for item in completed)
        if result['graphChildTransfers'] != result['expectedGraphTransfers'] \
                or len(result['graphMemberReceipts']) != result['expectedGraphTransfers']:
            raise RuntimeError('boat graph child witness count differs from completed root attempts')
        result['laneCount'] = len(LANES)
        result['routes'] = list(routes)
        call_hook('after_waves', ctx)
        result['observedWorkerThreads'] = sorted(observed_threads)
        result['observedOwnerRegions'] = sorted(observed_owner_regions)
        result['maxObservedPendingTransactions'] = max_observed_pending
        if result['actualContacts'] < args.minimum_contacts:
            raise RuntimeError(f'only {result["actualContacts"]} actual contacts; '
                               f'minimum is {args.minimum_contacts}')
        result['netherStormGate'] = False
        result['stage7FullStormGate'] = False
        result['uncoveredStormFirstBlocker'] = (
            'End gateway needs a concise per-UUID same-object/ticket/tracking receipt; '
            'connected player requires a real client session'
            if any(route in END_CROSS_ROUTES for route in routes) else
            'this default Nether route plan has no End portal/gateway lane or connected player session')
        result['uncoveredStormScope'] = [
            'End entry and exit require a separate opt-in run using the audited End W4 world seed'
            if not any(route in END_CROSS_ROUTES for route in routes) else
            'This run does not include a same-dimension End gateway contact',
            'A real connected player is not part of this independent RCON fixture',
            'Graph admission is represented only by one boat-plus-pig shape',
            'Same-dimension gateway requires its own same-object relocation outcome contract',
            'Per-entity final identity, client visibility, and native post-impact effects remain exact-card obligations',
        ]
        for dimension in (OW, NETHER) + ((END,) if any(
                route in END_CROSS_ROUTES for route in routes) else ()):
            result['emptyAfterWaveCleanup-' + dimension] = command(
                rcon, journal, scoped_tag_command(dimension, 'stage7_storm', 'absent'),
                contains='Test passed')
        # A transferred persistent witness remains in the saved world. Use a
        # distinct tag and UUID so the restart cannot accidentally credit a
        # newly summoned replacement or one of the cleaned storm waves.
        sentinel = deterministic_uuid(args.seed, args.attempts + 1)
        sentinel_tag = 'stage7_storm_restart_sentinel'
        source = OW
        x = lane_x(source, 0)
        nbt = summoning_nbt('chicken', sentinel, ['stage7_storm', sentinel_tag])
        command(rcon, journal,
                f'execute in {source} run summon minecraft:chicken {x + .5} 66 {spawn_z("chicken")} {nbt}',
                contains='Summoned new')
        sentinel_attempt = {'uuid': str(sentinel), 'family': 'chicken', 'source': source, 'region': 0}
        deadline = time.monotonic() + args.wave_timeout
        while time.monotonic() < deadline:
            monitor.require_healthy()
            require_status(rcon, product_sha, probe_sha)
            observed = classify_attempt(sentinel_attempt, monitor.events(str(sentinel)), product_sha, probe_sha)
            if observed['outcome'] == 'TRANSFER':
                break
            time.sleep(.5)
        else:
            raise RuntimeError(f'persistent sentinel failed portal transfer: {observed}')
        result['restartSentinel'] = observed
        result['sentinelRelocation'] = command(rcon, journal,
            scoped_tag_command(NETHER, sentinel_tag, 'teleport',
                               center=(32, 66, 0), radius=16, destination=(72, 65, 5)),
            contains='Teleported Chicken')
        before_restart = command(rcon, journal,
                                 scoped_tag_command(NETHER, sentinel_tag, 'present',
                                                    center=(72, 65, 5), radius=8),
                                 contains='Test passed')
        result['sentinelBeforeRestart'] = before_restart
        status = require_status(rcon, product_sha, probe_sha)
        if status.get('pending') != 0:
            raise RuntimeError(f'pending storm transaction before save: {status}')
        result['firstTxStatus'] = status
        result['firstHealth'] = command(rcon, journal, 'phoenium diagnostics')
        if 'lifecycle=RUNNING' not in result['firstHealth'] or 'scheduler=RUNNING' not in result['firstHealth']:
            raise RuntimeError(f'storm server unhealthy before save: {result["firstHealth"]}')
        command(rcon, journal, 'save-all flush')
        stop(first_process, first_log, result, 'first')
        if result['firstExitCode'] != 0 or not result['firstSaved'] or result.get('firstPumpError'):
            raise RuntimeError('storm server did not save/stop normally')
        first = first_failure(first_log)
        if first:
            raise RuntimeError(f'first server failure after stop: {first}')
        restart_process = launch(server, restart_log, args.workers)
        result['restartPid'] = restart_process.pid
        wait_ready(restart_process, restart_log, args.bedrock_port)
        restart_status = require_status(rcon, product_sha, probe_sha)
        result['restartTxStatus'] = restart_status
        result['restartHealth'] = command(rcon, journal, 'phoenium diagnostics')
        if 'lifecycle=RUNNING' not in result['restartHealth'] or 'scheduler=RUNNING' not in result['restartHealth']:
            raise RuntimeError(f'same-world restart server unhealthy: {result["restartHealth"]}')
        # `Done` precedes entity-chunk hydration in this pinned server. The
        # sentinel was moved to x72,z5 before save; chunk (4,0) belongs to the
        # already-verified pre-generated Nether lanes. Reapply its force-load
        # ticket and wait only for that exact saved tag to become addressable.
        result['restartSentinelChunk'] = {'dimension': NETHER, 'x': 4, 'z': 0,
                                          'blockX': 72, 'blockZ': 5,
                                          'preexistingInSeed': (4, 0) in manifest['preexistingNetherFixtureChunks']}
        if not result['restartSentinelChunk']['preexistingInSeed']:
            raise RuntimeError('sentinel restart chunk was not pre-generated in seed')
        result['restartForceLoadBefore'] = command(rcon, journal,
            f'execute in {NETHER} run forceload query 72 5')
        result['restartForceLoadAdd'] = command(rcon, journal,
            f'execute in {NETHER} run forceload add 72 5')
        result['restartForceLoadAfter'] = command(rcon, journal,
            f'execute in {NETHER} run forceload query 72 5')
        query = scoped_tag_command(NETHER, sentinel_tag, 'present',
                                   center=(72, 65, 5), radius=8)
        restart_wait_start = time.monotonic()
        restart_deadline = restart_wait_start + args.wave_timeout
        poll_count = 0
        while time.monotonic() < restart_deadline:
            restart_status = require_status(rcon, product_sha, probe_sha)
            result['restartTxStatus'] = restart_status
            response = command(rcon, journal, query)
            poll_count += 1
            if 'Test passed' in response:
                result['sentinelAfterRestart'] = response
                break
            time.sleep(.5)
        result['restartSentinelPollCount'] = poll_count
        result['restartSentinelWaitSeconds'] = round(time.monotonic() - restart_wait_start, 3)
        if 'sentinelAfterRestart' not in result:
            raise RuntimeError(f'saved sentinel did not load after restart: {response!r}; '
                               f'forceLoad={result["restartForceLoadAfter"]!r}')
        result['globalSentinelAfterRestart'] = command(rcon, journal,
            f'execute if entity @e[tag={sentinel_tag}]')
        if result['globalSentinelAfterRestart'] != 'Test passed. Count: 1' \
                or result['sentinelAfterRestart'] != 'Test passed. Count: 1':
            raise RuntimeError('restart sentinel must be exactly one global entity '
                               'and exactly one entity in the saved Nether chunk')
        result['sourceSentinelAfterRestart'] = command(rcon, journal,
            scoped_tag_command(OW, sentinel_tag, 'absent',
                               center=(lane_x(OW, 0), 66, 0), radius=16), contains='Test passed')
        call_hook('after_restart', ctx)
        stop(restart_process, restart_log, result, 'restart')
        if result['restartExitCode'] != 0 or not result['restartSaved'] or result.get('restartPumpError'):
            raise RuntimeError('same-world restart did not save/stop normally')
        result['sourceWorldChanges'] = verify_source_unchanged(manifest)
        result['worldSeedReceiptEndSha256'] = (
            sha(Path(manifest['worldSeedReceipt'])) if manifest['worldSeedReceipt'] else None)
        record_test_source_drift(result, manifest)
        result['configChanges'] = [name for name, digest in manifest['installedConfigHashes'].items()
                                   if sha(server / 'config' / name) != digest]
        result['installedCandidateSha256'] = sha(server / 'mods' / manifest['installedCandidateName'])
        result['installedProbeSha256'] = sha(server / 'mods' / 'stage7-repair-probe.jar')
        result['stormHarnessSha256'] = sha(Path(__file__))
        result['providerHashes'] = {name: sha(server / 'mods' / name)
                                    for name in manifest['pinnedProviderHashes']}
        if (result['sourceWorldChanges'] or result['executedTestSourceChanges']
                or result['configChanges']
                or result['worldSeedReceiptEndSha256'] != manifest['worldSeedReceiptSha256']):
            raise RuntimeError('source world, executed observer source, or installed config changed during storm')
        if result['installedCandidateSha256'] != product_sha or result['installedProbeSha256'] != probe_sha \
                or result['stormHarnessSha256'] != manifest['stormHarnessSha256'] \
                or result['providerHashes'] != manifest['pinnedProviderHashes']:
            raise RuntimeError('installed bytes changed during storm')
        result['netherStormGate'] = (routes == DEFAULT_ROUTES
                                     and set(families) == set(FAMILIES)
                                     and result['actualContacts'] >= 2000
                                     and args.workers == 4
                                     and result['ownerViolations'] == 0
                                     and result['firstTxStatus']['violations'] == 0
                                     and result['restartTxStatus']['violations'] == 0)
        result['endCrossPilotPass'] = (set(routes) == set(END_CROSS_ROUTES)
                                       and len(routes) == 2 and args.workers == 4
                                       and result['actualContacts'] >= 8
                                       and result['ownerViolations'] == 0)
        call_hook('finalize', ctx)
        result['pass'] = True
    except BaseException as error:
        result['triggerFailure'] = str(error)
        result['firstFailure'] = (first_failure(first_log) if first_log.exists() else None) or str(error)
        print('STORM FAILED', result['firstFailure'], flush=True)
    finally:
        if first_process is not None and 'firstExitCode' not in result:
            stop(first_process, first_log, result, 'first')
        if restart_process is not None and 'restartExitCode' not in result:
            stop(restart_process, restart_log, result, 'restart')
        if 'testSourceChanges' not in result:
            try:
                record_test_source_drift(result, manifest)
            except OSError as audit_error:
                result['testSourceDriftAuditError'] = str(audit_error)
        result['endUtc'] = utc()
        result['durationSeconds'] = round(time.monotonic() - monotonic_start, 3)
        result['artifacts'] = {
            'inputs': 'inputs.json', 'plannedAttempts': 'planned-attempts.json',
            'commands': 'commands.jsonl', 'wavePattern': 'wave-*.json',
            'firstConsole': first_log.name, 'restartConsole': restart_log.name,
            'firstQuerySummary': first_log.stem + '-query-summary.json',
            'restartQuerySummary': restart_log.stem + '-query-summary.json',
            'firstTransactionTrace': 'first-portal-transactions.log',
            'restartTransactionTrace': 'restart-portal-transactions.log',
            'result': 'result.json',
        }
        for phase, log in (('first', first_log), ('restart', restart_log)):
            if log.exists():
                result[phase + 'ConsoleSha256'] = sha(log)
                result[phase + 'FirstLogFailure'] = first_failure(log)
                query_summary = log.with_name(log.stem + '-query-summary.json')
                if query_summary.exists():
                    result[phase + 'QuerySummarySha256'] = sha(query_summary)
                    result[phase + 'SuppressedQueryLines'] = json.loads(
                        query_summary.read_text(encoding='utf-8'))['suppressedLineCount']
                trace = run_dir / (phase + '-portal-transactions.log')
                tx_count = 0
                with log.open(encoding='utf-8', errors='replace') as source, \
                        trace.open('w', encoding='utf-8') as output:
                    for line in source:
                        if 'STAGE7_PORTAL_TX' in line:
                            output.write(line)
                            tx_count += 1
                result[phase + 'TxTraceSha256'] = sha(trace)
                result[phase + 'TxTraceLines'] = tx_count
        (run_dir / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print('STORM RESULT', str(run_dir / 'result.json'), 'PASS' if result['pass'] else 'FAIL', flush=True)
    return 0 if result['pass'] else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--probe', type=Path,
                        default=WORK / 'build/test-harness/stage7-repair-probe.jar')
    parser.add_argument('--seed', type=int, default=2704)
    parser.add_argument('--attempts', type=int, default=2240)
    parser.add_argument('--minimum-contacts', type=int, default=2000)
    parser.add_argument('--wave-size', type=int, default=8)
    parser.add_argument('--wave-timeout', type=float, default=45)
    parser.add_argument('--families', default=','.join(FAMILIES))
    parser.add_argument('--routes', default=','.join(DEFAULT_ROUTES),
                        help='ordered cross-dimension route cycle; supported: '
                             + ','.join(ROUTE_WORLDS))
    parser.add_argument('--tnt-card', action='store_true',
                        help='W1 first-lane card requiring destination-owned usedPortal suffix and fuse tick')
    parser.add_argument('--workers', type=int, choices=(1, 4), default=4)
    parser.add_argument('--java-port', type=int, default=DEFAULT_PORTS['java'])
    parser.add_argument('--bedrock-port', type=int, default=DEFAULT_PORTS['bedrock'])
    parser.add_argument('--rcon-port', type=int, default=DEFAULT_PORTS['rcon'])
    return run(parser.parse_args())


if __name__ == '__main__':
    sys.exit(main())
