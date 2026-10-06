#!/usr/bin/env python3
"""Stage 7 mixed W4 storm gate: the bounded storm of `run_storm.py` plus the lanes it cannot drive over RCON alone.

Run from the worktree (nothing else running; the real client is started at below-normal priority):

    python scripts/stage7/mixed_storm.py --name <fresh-name> --candidate <jar> [--no-client]

What one run mixes, all against ONE installed W4 server (pinned providers, probe observer, audited End seed world):

* the whole RCON entity storm: 14 default families + three more mob profiles (pig, cow, zombified piglin) + chest minecarts,
  over all four portal routes (Nether entry/return, End entry/exit) on four lanes each, with the exact per-UUID lifecycle
  accounting of `run_storm.classify_attempt`;
* a same-dimension End gateway lane: items, mobs, vehicles, falling blocks and TNT pushed through a gateway between waves, each
  with the probe's contact, ticket and tracking events and exactly one live object at the exit;
* container contents carried by chest minecarts through the portals (items and slots read back after every transfer);
* periodic `save-all flush` while the storm is running, then a normal stop, a same-world restart and a persistent sentinel;
* a REAL connected client (the repository's own Fabric client game-test agent, the offline test identity `PhoeniumTest0`) that
  plays through the whole run: Nether and End portal trips by the player, normal movement, opening a chest, the End gateway,
  death and respawn, a disconnect/reconnect; and after the restart it reconnects to the saved world and walks.

`stage7FullStormGate` is set by `evaluate_gate` from the receipts only. `--no-client` is a debugging mode that can never grant it.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_storm as rs  # noqa: E402

WORK = rs.WORK
OW, NETHER, END = rs.OW, rs.NETHER, rs.END
ALL_ROUTES = ('NETHER_ENTRY', 'NETHER_RETURN', 'END_ENTRY', 'END_EXIT')
MIXED_ORDER = rs.FAMILIES + rs.MIXED_FAMILIES
PLAYER = 'PhoeniumTest0'
PASSWORD = 'phoenium-stage7-local-test'
BELOW_NORMAL = 0x4000

# The RCON gateway lane sits between End storm lanes 0 and 1 (their footprints are x 26..38 and 58..70) inside the pre-generated,
# force-loaded End chunks, away from the player's own gateway fixture (x 8..24).
GATEWAY = (44, 66, 8)
GATEWAY_EXIT = (54, 66, 12)
GATEWAY_FAMILIES = ('item', 'chicken', 'magma_cube', 'pig', 'cow', 'zombified_piglin', 'oak_boat', 'minecart',
                    'chest_minecart', 'falling_block', 'tnt')
GATEWAY_TAG = 'stage7_gateway_pig'
# the player's fixtures are the closeout ones: a room at the origin of both dimensions, an End portal pair, a gateway
CELL = {'NETHER_ENTRY': '0.5 65 0.5', 'NETHER_RETURN': '0.5 65 0.5', 'END_ENTRY': '8.5 65.1 8.5', 'END_EXIT': '8.5 70.1 8.5',
        'END_GATEWAY': '20.5 66 8.5'}
REST = {'NETHER_ENTRY': '-6.5 65 -5.5', 'NETHER_RETURN': '-6.5 65 -5.5', 'END_ENTRY': '5.5 65 5.5', 'END_EXIT': '5.5 70 5.5',
        'END_GATEWAY': '24.5 65 8.5'}
SRC_DST = {'NETHER_ENTRY': (OW, NETHER), 'NETHER_RETURN': (NETHER, OW), 'END_ENTRY': (OW, END), 'END_EXIT': (END, OW),
           'END_GATEWAY': (END, END)}
CHEST = (-7, 65, -3)
PLAYER_SPAWN = '-6 65 -5'
# Minimum receipts the gate needs (see evaluate_gate)
MIN_PLAYER = {'NETHER_ENTRY': 2, 'NETHER_RETURN': 2, 'END_ENTRY': 2, 'END_EXIT': 2, 'END_GATEWAY': 2}
MIN_MOVEMENT, MIN_CONTAINER, MIN_RESPAWN, MIN_RECONNECT = 3, 2, 1, 1
MIN_GATEWAY_ATTEMPTS = 24
MIN_SAVES = 10
MIN_OVERLAPPED_WAVES = 20


# The server's RCON listener shares ONE console-source buffer between all connections, so two concurrent RCON commands
# (the storm thread and the player lane) can return each other's feedback. Every RCON call in this process takes this lock.
RCON_LOCK = threading.RLock()


class WholeRcon:
    """A persistent RCON connection that reassembles replies split at 4096 characters."""

    def __init__(self, port: int):
        self.port, self.sock, self.n = port, None, 10
        self.lock = RCON_LOCK

    def _recv(self, count: int) -> bytes:
        data = b''
        while len(data) < count:
            part = self.sock.recv(count - len(data))
            if not part:
                raise EOFError('RCON closed')
            data += part
        return data

    def _send(self, ident: int, kind: int, body: str) -> None:
        raw = body.encode()
        self.sock.sendall(struct.pack('<iii', len(raw) + 10, ident, kind) + raw + b'\0\0')

    def _read(self) -> str:
        length = struct.unpack('<i', self._recv(4))[0]
        body = self._recv(length)
        if struct.unpack('<i', body[:4])[0] < 0:
            raise RuntimeError('RCON authentication failed')
        return body[8:-2].decode(errors='replace')

    def _open(self) -> None:
        self.sock = socket.create_connection(('127.0.0.1', self.port), timeout=5)
        self.sock.settimeout(60)
        self._send(1, 3, PASSWORD)
        self._read()

    def __call__(self, text: str, whole: bool = False) -> str:
        with self.lock:
            for attempt in (0, 1):
                try:
                    if self.sock is None:
                        self._open()
                    self.n += 1
                    self._send(self.n, 2, text)
                    reply = self._read()
                    while whole and len(reply.encode()) >= 4096 and len(reply) % 4096 == 0:
                        self.sock.settimeout(1.0)
                        try:
                            more = self._read()
                        except (socket.timeout, TimeoutError):
                            break
                        finally:
                            self.sock.settimeout(60)
                        reply += more
                    return reply
                except (OSError, EOFError):
                    try:
                        self.sock.close()
                    except Exception:
                        pass
                    self.sock = None
                    if attempt:
                        raise
        raise RuntimeError('unreachable')


def persistent_rcon_call(clients: dict):
    """The storm's own per-command RCON connections exhaust the Windows ephemeral ports (WinError 10048 after ~4,000 commands
    in TIME_WAIT); one persistent connection per port, serialised by RCON_LOCK, carries the same commands."""

    def call(self, command: str) -> str:
        with RCON_LOCK:
            client = clients.get(self.port)
            if client is None:
                client = clients[self.port] = WholeRcon(self.port)
            return client(command)

    return call


def snapshot_body(rcon: WholeRcon) -> dict:
    raw = rcon('stage7-probe snapshot', whole=True)
    return json.loads(raw[raw.index('{'):])


def offline_uuid(name: str) -> str:
    import hashlib
    digest = bytearray(hashlib.md5(('OfflinePlayer:' + name).encode()).digest())
    digest[6] = (digest[6] & 0x0f) | 0x30
    digest[8] = (digest[8] & 0x3f) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def gateway_nbt(family: str, value: uuid.UUID, tags: list[str]) -> str:
    """Stationary inside the gateway cell: the gateway fires from `entityInside`, so no Motion."""
    common = rs.uuid_nbt(value) + ',Tags:[' + ','.join(json.dumps(tag) for tag in tags) + ']'
    still = ',NoGravity:1b'
    if family == 'item':
        return '{' + common + ',Item:{id:"minecraft:cobblestone",count:1},PickupDelay:32767s' + still + '}'
    if family in ('chicken', 'magma_cube', 'pig', 'cow', 'zombified_piglin'):
        size = ',Size:0' if family == 'magma_cube' else ''
        return '{' + common + ',PersistenceRequired:1b,NoAI:1b' + still + size + '}'
    if family == 'falling_block':
        return '{' + common + ',BlockState:{Name:"minecraft:sand"},Time:1' + still + '}'
    if family == 'tnt':
        return '{' + common + ',fuse:1200s' + still + '}'
    if family == 'chest_minecart':
        return ('{' + common + still
                + ',Items:[{Slot:0b,id:"minecraft:cobblestone",count:5},{Slot:13b,id:"minecraft:stick",count:3}]}')
    if family in ('oak_boat', 'minecart'):
        return '{' + common + still + '}'
    raise ValueError('unimplemented gateway family ' + family)


def items_intact(response: str) -> bool:
    """The chest minecart's two stacks, by slot, id and count, in whatever field order the NBT printer chose."""
    objects = re.findall(r'\{[^{}]*\}', response)
    cobble = any('minecraft:cobblestone' in o and re.search(r'count: 5\b', o) and re.search(r'Slot: 0b', o) for o in objects)
    stick = any('minecraft:stick' in o and re.search(r'count: 3\b', o) and re.search(r'Slot: 13b', o) for o in objects)
    return bool(cobble and stick)


def gateway_receipt(attempt: dict, final_x: float | None, members: list[dict], body: dict) -> dict:
    """Credit one gateway attempt only with the exit position, exactly one live object, and the contact/ticket/tracking events."""
    ids = {attempt['uuid']}
    events = {kind: [e for e in body.get(key, []) if e.get('uuid', '').lower() in ids]
              for kind, key in (('contact', 'gatewayContactEvents'), ('ticket', 'gatewayTicketEvents'),
                                ('tracking', 'gatewayTrackingEvents'))}
    mine = [m for m in members if m.get('uuid', '').lower() in ids]
    problems = []
    if final_x is None or final_x <= GATEWAY[0] + 6:
        problems.append('did not reach the exit')
    if len(mine) != 1:
        problems.append('live object count %d' % len(mine))
    elif mine[0].get('world') != END or mine[0].get('removed'):
        problems.append('object not live in the End')
    for kind, rows in events.items():
        if not rows:
            problems.append('no ' + kind + ' event')
        elif any(row.get('ownerWorld') != END for row in rows):
            problems.append(kind + ' owner is not the End')
    return {'uuid': attempt['uuid'], 'family': attempt['family'], 'finalX': final_x, 'live': len(mine),
            'eventCounts': {k: len(v) for k, v in events.items()}, 'problems': problems, 'ok': not problems}


class Agent:
    """File protocol to the real client agent (control/cmd.json -> control/resp.json)."""

    def __init__(self, control: Path):
        self.control, self.n, self.client = control, 0, None
        self.lock = threading.Lock()

    def call(self, op: str, timeout: float = 180, **kw):
        with self.lock:
            self.n += 1
            tmp = self.control / 'cmd.tmp'
            tmp.write_text(json.dumps(dict(id=self.n, op=op, **kw)))
            import os
            os.replace(tmp, self.control / 'cmd.json')
            deadline = time.monotonic() + timeout
            path = self.control / 'resp.json'
            while time.monotonic() < deadline:
                if path.exists():
                    try:
                        resp = json.loads(path.read_text())
                    except ValueError:
                        time.sleep(.05)
                        continue
                    path.unlink()
                    if resp.get('id') != self.n:
                        continue
                    if not resp.get('ok'):
                        raise RuntimeError('client %s failed: %s' % (op, resp.get('error')))
                    return resp
                if self.client is not None and self.client.poll() is not None:
                    raise RuntimeError('client process exited')
                time.sleep(.05)
            raise TimeoutError('client did not answer ' + op)

    def snapshot(self) -> dict:
        return self.call('snapshot')['snapshot']


class Mixed:
    """All mutable state of one mixed run; the hooks are bound methods."""

    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.gateway_rows: list[dict] = []
        self.gateway_index = 0
        self.container_rows: list[dict] = []
        self.saves: list[dict] = []
        self.wave = -1
        self.lane_error: str | None = None
        self.lane_thread: threading.Thread | None = None
        self.lane_done = threading.Event()
        self.player_rows: list[dict] = []
        self.client: subprocess.Popen | None = None
        self.agent: Agent | None = None
        self.rcon: WholeRcon | None = None
        self.credits_seen = False
        self.restart_row: dict | None = None
        self.first_wave_with_client: int | None = None
        self.last_wave_with_client: int | None = None

    # ------------------------------------------------------------------ fixtures
    def server_command(self, ctx, text: str, contains: str | None = None) -> str:
        return rs.command(ctx.rcon, ctx.journal, text, contains=contains)

    def build_gateway(self, ctx) -> None:
        x, y, z = GATEWAY
        for text in (f'forceload add {x-4} 4 {x+12} 16', f'fill {x-4} 64 4 {x+12} 64 16 minecraft:end_stone',
                     f'fill {x-4} 65 4 {x+12} 73 16 minecraft:air', f'setblock {x} {y} {z} minecraft:end_gateway',
                     f'data merge block {x} {y} {z} {{exit_portal:[I;{GATEWAY_EXIT[0]},{GATEWAY_EXIT[1]},{GATEWAY_EXIT[2]}],'
                     f'ExactTeleport:1b,Age:200L}}'):
            self.server_command(ctx, f'execute in {END} run ' + text)
        self.server_command(ctx, f'execute in {END} run execute if block {x} {y} {z} minecraft:end_gateway',
                            contains='Test passed')

    def build_player_fixtures(self, ctx) -> None:
        rcon = self.rcon
        for world in (OW, NETHER):
            prefix = 'execute in ' + world + ' run '
            for text in ('forceload add -16 -16 16 16', 'fill -12 64 -12 12 64 12 minecraft:sea_lantern',
                         'fill -12 65 -12 12 76 12 minecraft:air', 'fill -12 76 -12 12 76 12 minecraft:glass',
                         'fill -12 65 -12 -12 75 12 minecraft:glass', 'fill 12 65 -12 12 75 12 minecraft:glass',
                         'fill -12 65 -12 12 75 -12 minecraft:glass', 'fill -12 65 12 12 75 12 minecraft:glass',
                         'fill -2 64 0 2 69 0 minecraft:obsidian', 'fill -1 65 0 1 68 0 minecraft:air',
                         'setblock -1 65 0 minecraft:fire', 'fill -2 65 -10 2 69 -10 minecraft:stone'):
                self.server_command(ctx, prefix + text)
            if 'Test passed' not in rcon(prefix + 'execute if block 0 66 0 minecraft:nether_portal'):
                raise RuntimeError('player Nether portal did not light in ' + world)
        for text in ('forceload add 0 0 24 20',):
            self.server_command(ctx, f'execute in {OW} run ' + text)
        # one End portal pair per direction, like the closeout cards
        for world, y in ((OW, 65), (END, 70)):
            for text in ('forceload add 0 0 24 20', 'fill 4 %d 4 12 %d 12 minecraft:obsidian' % (y - 1, y - 1),
                         'fill 4 %d 4 12 %d 12 minecraft:air' % (y, y + 6), 'setblock 8 %d 8 minecraft:end_portal' % y):
                self.server_command(ctx, f'execute in {world} run ' + text)
        # east of the End portal platform (x 4..12): its air fill must not erase that portal or its obsidian floor
        for text in ('forceload add 0 0 36 20', 'fill 16 64 4 34 64 16 minecraft:end_stone', 'fill 16 65 4 34 73 16 minecraft:air',
                     'setblock 20 66 8 minecraft:end_gateway',
                     'data merge block 20 66 8 {exit_portal:[I;30,66,12],ExactTeleport:1b,Age:200L}'):
            self.server_command(ctx, f'execute in {END} run ' + text)
        x, y, z = CHEST
        self.server_command(ctx, f'execute in {OW} run setblock {x} {y} {z} minecraft:chest')
        self.server_command(ctx, f'execute in {OW} run data merge block {x} {y} {z} '
                                 '{Items:[{Slot:0b,id:"minecraft:cobblestone",count:7}]}')

    # ------------------------------------------------------------------ client
    def start_client(self, ctx) -> None:
        control = ctx.run_dir / 'client-control'
        control.mkdir()
        self.agent = Agent(control)
        command = [str(WORK / 'gradlew.bat'), '--no-daemon', '-I', str(WORK / 'scripts/stage7-closeout-client.gradle'),
                   'runClientGameTest', '--console=plain', '-PcloseoutControl=' + str(control),
                   '-PcloseoutAddress=127.0.0.1:' + str(self.args.java_port)]
        ctx.result['clientCommand'] = command
        log = (ctx.run_dir / 'client.log').open('wb')
        self.client = subprocess.Popen(command, cwd=WORK, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW | BELOW_NORMAL)
        self.agent.client = self.client
        ctx.result['clientPid'] = self.client.pid
        print('MIXED CLIENT', self.client.pid, flush=True)
        deadline = time.monotonic() + 1500
        while not (control / 'ready').exists():
            if self.client.poll() is not None:
                raise RuntimeError('client exited before it connected; see client.log')
            if ctx.first_process.poll() is not None:
                raise RuntimeError('server exited while the client connected')
            ctx.monitor.require_healthy()
            if time.monotonic() > deadline:
                raise RuntimeError('client did not connect in 25 minutes')
            time.sleep(1)
        ctx.result['playerListAfterConnect'] = self.rcon('list')
        print('MIXED CLIENT READY', ctx.result['playerListAfterConnect'], flush=True)

    def shutdown(self) -> None:
        try:
            if self.agent is not None:
                (self.agent.control / 'stop').write_text('stop')
        except OSError:
            pass
        if self.client is not None:
            try:
                self.client.wait(timeout=120)
            except subprocess.TimeoutExpired:
                self.client.kill()

    # ------------------------------------------------------------------ player lane
    def nbt(self, path: str, entity: str = PLAYER) -> str:
        out = self.rcon('data get entity %s %s' % (entity, path), whole=True)
        return out.split('entity data: ', 1)[1].strip() if 'entity data: ' in out else out.strip()

    def pstate(self) -> dict:
        out = {}
        for key, path in (('dimension', 'Dimension'), ('pos', 'Pos'), ('cooldown', 'PortalCooldown'),
                          ('seenCredits', 'seenCredits'), ('health', 'Health')):
            try:
                out[key] = self.nbt(path)
            except Exception as error:  # an offline player answers with text, never raise from a poll
                out[key] = 'ERR ' + str(error)
        out['dimension'] = out['dimension'].strip('"')
        try:
            out['posValues'] = [float(v) for v in re.findall(r'-?\d+(?:\.\d+)?(?=d)', out['pos'])]
        except ValueError:
            out['posValues'] = None
        return out

    @staticmethod
    def cooldown_of(pst: dict) -> int | None:
        try:
            return int(re.match(r'-?\d+', pst['cooldown']).group(0))
        except Exception:
            return None

    def lane_check(self, ctx) -> None:
        if ctx.first_process.poll() is not None:
            raise RuntimeError('server exited during the player lane')
        ctx.monitor.require_healthy()

    def place(self, ctx, world: str, where: str, wait_cooldown: bool = True, timeout: float = 90):
        x, y, z = [float(v) for v in where.split()]
        self.rcon('execute in %s run tp %s %s %s %s' % (world, PLAYER, x, y, z))
        deadline = time.monotonic() + timeout
        nudge = time.monotonic() + 6
        pst = None
        while time.monotonic() < deadline:
            self.lane_check(ctx)
            snap = self.agent.snapshot()
            pst = self.pstate()
            near = (snap.get('connected') and snap.get('dimension') == world and snap.get('screen') is None
                    and abs(snap['player']['x'] - x) < 2 and abs(snap['player']['z'] - z) < 2)
            if near and pst['dimension'] == world and (not wait_cooldown or self.cooldown_of(pst) == 0):
                return snap, pst
            if time.monotonic() > nudge:
                self.rcon('ride %s dismount' % PLAYER)
                self.rcon('execute in %s run tp %s %s %s %s' % (world, PLAYER, x, y, z))
                nudge = time.monotonic() + 6
            time.sleep(.25)
        raise RuntimeError('player did not settle in %s at %s: %s' % (world, where, json.dumps(pst)))

    def wait_arrival(self, ctx, route: str, timeout: float = 60):
        src, dst = SRC_DST[route]
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.lane_check(ctx)
            pst = self.pstate()
            if route == 'END_GATEWAY':
                if pst['posValues'] and pst['posValues'][0] > 26:
                    return pst
            elif pst['dimension'] == dst:
                return pst
            time.sleep(.1)
        return None

    def record(self, kind: str, ok: bool, **fields) -> dict:
        row = {'kind': kind, 'ok': ok, 'wave': self.wave, 'utc': rs.utc(), **fields}
        self.player_rows.append(row)
        print('MIXED PLAYER', kind, 'ok' if ok else 'FAILED', 'wave', self.wave, flush=True)
        return row

    def resist(self) -> None:
        # an exploding projectile that arrives at the world spawn must not end the player lane; the gate is not about damage
        self.rcon('gamemode survival ' + PLAYER)
        self.rcon('effect give %s minecraft:resistance 1000000 4 true' % PLAYER)
        self.rcon('effect give %s minecraft:saturation 1000000 1 true' % PLAYER)

    def portal_trip(self, ctx, route: str) -> None:
        src, dst = SRC_DST[route]
        if route == 'END_EXIT' and not self.credits_seen:
            self.ensure_credits_seen(ctx)
        started = self.wave
        self.place(ctx, src, REST[route])
        self.resist()
        before = {'server': self.pstate(), 'client': self.agent.snapshot()}
        self.rcon('execute in %s run tp %s %s' % (src, PLAYER, CELL[route]))
        t0 = time.monotonic()
        arrived = self.wait_arrival(ctx, route)
        if not arrived:
            self.record('portal-' + route, False, reason='STAYED', before=before, waveStart=started)
            raise RuntimeError('player stayed at the %s portal' % route)
        time.sleep(2.5)
        after_server, after_client = self.pstate(), self.agent.snapshot()
        good = (after_client.get('connected') and after_client.get('screen') in (None,)
                and (route == 'END_GATEWAY' or after_client.get('dimension') == dst)
                and after_server['dimension'] == dst)
        self.record('portal-' + route, bool(good), seconds=round(time.monotonic() - t0, 2), waveStart=started,
                    before=before, afterServer=after_server, afterClient=after_client)
        if not good:
            raise RuntimeError('player arrived but the real client disagrees for %s: %s' % (route, after_client))

    def ensure_credits_seen(self, ctx) -> None:
        self.place(ctx, END, REST['END_EXIT'])
        self.resist()
        self.rcon('execute in %s run tp %s %s' % (END, PLAYER, CELL['END_EXIT']))
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            self.lane_check(ctx)
            if self.agent.snapshot().get('screen') == 'WinScreen':
                break
            time.sleep(.3)
        else:
            self.record('credits-exit', False, server=self.pstate(), client=self.agent.snapshot())
            raise RuntimeError('End credits never appeared')
        self.agent.call('wait', ticks=10)
        self.agent.call('closeScreen')
        self.agent.call('waitScreenGone', ticks=1200)
        self.credits_seen = True
        self.record('credits-exit', True, server=self.pstate())

    def movement(self, ctx) -> None:
        self.place(ctx, OW, REST['NETHER_ENTRY'])
        self.resist()
        before = self.agent.snapshot()
        self.agent.call('look', yaw=-90.0, pitch=0.0)
        self.agent.call('hold', key='forward', ticks=20)
        self.agent.call('hold', key='jump', ticks=6)
        after = self.agent.snapshot()
        moved = abs(after['player']['x'] - before['player']['x']) + abs(after['player']['z'] - before['player']['z'])
        server = self.pstate()
        self.record('movement', moved > 0.5 and bool(after['player']['alive']), moved=round(moved, 3),
                    clientBefore=before['player'], clientAfter=after['player'], server=server)
        if moved <= 0.5:
            raise RuntimeError('the real client did not move')

    def container(self, ctx) -> None:
        x, y, z = CHEST
        self.place(ctx, OW, REST['NETHER_ENTRY'])
        self.resist()
        self.agent.call('look', yaw=0.0, pitch=21.0)
        self.agent.call('press', key='use', ticks=4)
        opened = None
        for _ in range(40):
            snap = self.agent.snapshot()
            if snap.get('screen'):
                opened = snap
                break
            time.sleep(.25)
        contents = self.rcon('execute in %s run data get block %d %d %d Items' % (OW, x, y, z), whole=True)
        ok = bool(opened) and 'Container' in str(opened.get('screen')) and 'cobblestone' in contents
        self.record('container', ok, screen=opened.get('screen') if opened else None, contents=contents[:300])
        self.agent.call('closeScreen')
        closed = self.agent.snapshot()
        if not ok:
            raise RuntimeError('the real client could not open the chest: %s' % (opened and opened.get('screen')))
        if closed.get('screen') is not None:
            raise RuntimeError('chest screen did not close')

    def respawn(self, ctx) -> None:
        self.place(ctx, OW, REST['NETHER_ENTRY'])
        self.resist()
        self.rcon('execute in %s run spawnpoint %s %s' % (OW, PLAYER, PLAYER_SPAWN))
        self.rcon('effect clear ' + PLAYER)
        self.rcon('kill ' + PLAYER)
        shown = None
        for _ in range(60):
            self.lane_check(ctx)
            snap = self.agent.snapshot()
            if snap.get('screen') and 'Death' in str(snap.get('screen')):
                shown = snap
                break
            time.sleep(.25)
        if shown is None:
            self.record('respawn', False, reason='no death screen')
            raise RuntimeError('death screen never appeared')
        self.agent.call('respawn')
        deadline = time.monotonic() + 60
        pst = snap = None
        while time.monotonic() < deadline:
            self.lane_check(ctx)
            snap, pst = self.agent.snapshot(), self.pstate()
            alive = pst['health'].strip().rstrip('f') not in ('0.0', '0', '')
            if snap.get('connected') and snap.get('screen') is None and alive and pst['dimension'] == OW:
                break
            time.sleep(.3)
        else:
            self.record('respawn', False, server=pst, client=snap)
            raise RuntimeError('player did not respawn: %s' % pst)
        self.resist()
        near = pst['posValues'] and abs(pst['posValues'][0] + 6) < 6 and abs(pst['posValues'][2] + 5) < 6
        self.record('respawn', bool(near), deathScreen=shown.get('screen'), server=pst, client=snap)
        if not near:
            raise RuntimeError('player respawned away from its spawn point: %s' % pst)

    def reconnect(self, ctx) -> None:
        self.place(ctx, OW, REST['NETHER_ENTRY'])
        before = self.pstate()
        self.agent.call('disconnect', timeout=120)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline and 'There are 0 of' not in self.rcon('list'):
            time.sleep(.3)
        self.agent.call('connect', timeout=300)
        self.resist()
        pst, snap = self.pstate(), self.agent.snapshot()
        same = (pst['dimension'] == before['dimension'] and pst['posValues'] and before['posValues']
                and abs(pst['posValues'][0] - before['posValues'][0]) < 3 and snap.get('connected'))
        self.record('reconnect', bool(same), before=before, after=pst, client=snap)
        if not same:
            raise RuntimeError('reconnected player is not where it was: %s -> %s' % (before, pst))

    def lane_script(self, ctx):
        yield 'portal-NETHER_ENTRY', lambda: self.portal_trip(ctx, 'NETHER_ENTRY')
        yield 'portal-NETHER_RETURN', lambda: self.portal_trip(ctx, 'NETHER_RETURN')
        yield 'movement', lambda: self.movement(ctx)
        yield 'container', lambda: self.container(ctx)
        yield 'portal-END_ENTRY', lambda: self.portal_trip(ctx, 'END_ENTRY')
        yield 'portal-END_EXIT', lambda: self.portal_trip(ctx, 'END_EXIT')
        yield 'portal-END_GATEWAY', lambda: self.portal_trip(ctx, 'END_GATEWAY')
        yield 'respawn', lambda: self.respawn(ctx)
        yield 'reconnect', lambda: self.reconnect(ctx)
        yield 'movement', lambda: self.movement(ctx)
        yield 'portal-NETHER_ENTRY', lambda: self.portal_trip(ctx, 'NETHER_ENTRY')
        yield 'portal-NETHER_RETURN', lambda: self.portal_trip(ctx, 'NETHER_RETURN')
        yield 'container', lambda: self.container(ctx)
        yield 'portal-END_ENTRY', lambda: self.portal_trip(ctx, 'END_ENTRY')
        yield 'portal-END_EXIT', lambda: self.portal_trip(ctx, 'END_EXIT')
        yield 'portal-END_GATEWAY', lambda: self.portal_trip(ctx, 'END_GATEWAY')
        yield 'movement', lambda: self.movement(ctx)

    def lane_main(self, ctx) -> None:
        try:
            self.rcon('gamemode survival ' + PLAYER)
            self.rcon('execute in %s run spawnpoint %s %s' % (OW, PLAYER, PLAYER_SPAWN))
            self.first_wave_with_client = max(self.wave, 0)
            for name, step in self.lane_script(ctx):
                step()
            self.last_wave_with_client = self.wave
        except BaseException as error:  # reported by the main thread at the next wave boundary
            import traceback
            self.lane_error = '%s\n%s' % (error, traceback.format_exc())
            print('MIXED PLAYER LANE FAILED', error, flush=True)
            (ctx.run_dir / 'player-lane-failure.json').write_text(json.dumps(
                {'error': self.lane_error, 'rows': self.player_rows}, indent=2, default=str) + '\n')
        finally:
            self.lane_done.set()

    # ------------------------------------------------------------------ hooks
    def after_setup(self, ctx) -> None:
        self.rcon = WholeRcon(self.args.rcon_port)
        self.build_gateway(ctx)
        if self.args.no_client:
            return
        self.build_player_fixtures(ctx)
        self.start_client(ctx)
        self.lane_thread = threading.Thread(target=self.lane_main, args=(ctx,), name='mixed-player-lane', daemon=True)
        self.lane_thread.start()

    def segment_settled(self, ctx, segment: list[dict], outcomes: list[dict]) -> None:
        for attempt, outcome in zip(segment, outcomes):
            if attempt['family'] != 'chest_minecart' or outcome['outcome'] != 'TRANSFER':
                continue
            response = self.server_command(ctx, f'data get entity {attempt["uuid"]} Items')
            row = {'uuid': attempt['uuid'], 'route': attempt['route'], 'region': attempt['region'],
                   'ok': items_intact(response), 'response': response[:400]}
            self.container_rows.append(row)
            if not row['ok']:
                raise RuntimeError('chest minecart contents changed in transit: %s' % row)

    def gateway_attempt(self, ctx, wave: int) -> None:
        family = GATEWAY_FAMILIES[self.gateway_index % len(GATEWAY_FAMILIES)]
        index = self.gateway_index
        self.gateway_index += 1
        value = rs.deterministic_uuid(ctx.args.seed, 1_000_000 + index)
        tag = f'stage7_storm_gw_{index}'
        # `stage7_vehicle_*` is what the pinned probe's relevant() filter admits for every entity type
        tags = ['stage7_storm', 'stage7_storm_gateway', 'stage7_vehicle_gateway', tag, GATEWAY_TAG]
        x, y, z = GATEWAY
        attempt = {'uuid': str(value), 'family': family, 'index': index}
        self.server_command(ctx, f'execute in {END} run summon {rs.entity_id(family)} {x + .5} {y} {z + .5} '
                                 + gateway_nbt(family, value, tags), contains='Summoned new')
        deadline = time.monotonic() + 15
        final_x = None
        while time.monotonic() < deadline:
            ctx.monitor.require_healthy()
            reply = self.server_command(ctx, f'data get entity {value} Pos[0]')
            found = re.search(r'(-?\d+(?:\.\d+)?)d\s*$', reply.strip())
            if found:
                final_x = float(found.group(1))
                if final_x > x + 6:
                    break
            time.sleep(.25)
        time.sleep(.5)
        body = snapshot_body(self.rcon)
        members = [e for e in body['entities'] if tag in e.get('tags', '')]
        row = gateway_receipt(attempt, final_x, members, body)
        row['wave'] = wave
        self.gateway_rows.append(row)
        self.server_command(ctx, rs.scoped_tag_command(END, tag, 'kill', center=(x, y, z), radius=64))
        self.server_command(ctx, rs.scoped_tag_command(END, tag, 'absent', center=(x, y, z), radius=64),
                            contains='Test passed')
        if not row['ok']:
            raise RuntimeError('gateway attempt failed: %s' % row)

    def between_waves(self, ctx, wave: int) -> None:
        self.wave = wave
        if self.lane_error:
            raise RuntimeError('player lane failed: ' + self.lane_error)
        if wave % self.args.gateway_every == self.args.gateway_every - 1:
            self.gateway_attempt(ctx, wave)
        if wave % self.args.save_every == self.args.save_every - 1:
            reply = self.server_command(ctx, 'save-all flush')
            self.saves.append({'wave': wave, 'reply': reply[:120], 'ok': 'Saved' in reply})
            if 'Saved' not in reply:
                raise RuntimeError('save-all flush did not report a save: %r' % reply)
            rs.require_status(ctx.rcon, ctx.product_sha, ctx.probe_sha)

    def after_waves(self, ctx) -> None:
        # the storm is over; let the player script finish its last steps, then park the client
        if self.lane_thread is not None:
            deadline = time.monotonic() + 1200
            while not self.lane_done.is_set() and time.monotonic() < deadline:
                ctx.monitor.require_healthy()
                time.sleep(1)
            if not self.lane_done.is_set():
                raise RuntimeError('player lane did not finish within 20 minutes after the waves')
            if self.lane_error:
                raise RuntimeError('player lane failed: ' + self.lane_error)
            self.rcon('effect clear ' + PLAYER)
            final = {'server': self.pstate(), 'client': self.agent.snapshot()}
            self.record('final-state', bool(final['client'].get('connected')), **final)
            self.agent.call('disconnect', timeout=120)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline and 'There are 0 of' not in self.rcon('list'):
                time.sleep(.3)
            ctx.result['playerPosition'] = 'disconnected before save/stop'

    def after_restart(self, ctx) -> None:
        if self.args.no_client or self.agent is None:
            return
        self.agent.call('connect', timeout=600)
        pst, snap = self.pstate(), self.agent.snapshot()
        status = rs.require_status(ctx.rcon, ctx.product_sha, ctx.probe_sha)
        ctx.result['restartTxStatus'] = status
        before = self.agent.snapshot()
        self.agent.call('look', yaw=-90.0, pitch=0.0)
        self.agent.call('hold', key='forward', ticks=20)
        after = self.agent.snapshot()
        moved = abs(after['player']['x'] - before['player']['x']) + abs(after['player']['z'] - before['player']['z'])
        self.restart_row = {'server': pst, 'client': snap, 'moved': round(moved, 3),
                            'ok': bool(snap.get('connected')) and moved > 0.5 and pst['dimension'] in (OW, NETHER, END)}
        self.agent.call('disconnect', timeout=120)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline and 'There are 0 of' not in self.rcon('list'):
            time.sleep(.3)
        if not self.restart_row['ok']:
            raise RuntimeError('player is not usable after the same-world restart: %s' % self.restart_row)

    def finalize(self, ctx) -> None:
        result = ctx.result
        result['mixed'] = {
            'gatewayAttempts': len(self.gateway_rows), 'gatewayOk': sum(r['ok'] for r in self.gateway_rows),
            'gatewayRows': self.gateway_rows, 'containerMinecarts': len(self.container_rows),
            'containerMinecartsOk': sum(r['ok'] for r in self.container_rows), 'saves': self.saves,
            'playerRows': self.player_rows, 'restartPlayer': self.restart_row, 'laneError': self.lane_error,
            'playerEventPhases': sorted({e['phase'] for e in ctx.monitor.events(offline_uuid(PLAYER))}),
            'firstWaveWithClient': self.first_wave_with_client, 'lastWaveWithClient': self.last_wave_with_client,
            'noClient': self.args.no_client,
        }
        result['stage7FullStormConditions'] = evaluate_gate(result, self.args)
        result['stage7FullStormGate'] = all(result['stage7FullStormConditions'].values())
        result['uncoveredStormFirstBlocker'] = None if result['stage7FullStormGate'] else next(
            k for k, v in result['stage7FullStormConditions'].items() if not v)
        result['uncoveredStormScope'] = [] if result['stage7FullStormGate'] else [
            k for k, v in result['stage7FullStormConditions'].items() if not v]


def evaluate_gate(result: dict, args) -> dict:
    """Every condition of the directive's mixed-storm list, from receipts only. All must hold for the gate."""
    mixed = result['mixed']
    rows = mixed['playerRows']
    played = {}
    for row in rows:
        if row['ok'] and row['kind'].startswith('portal-'):
            played[row['kind'][7:]] = played.get(row['kind'][7:], 0) + 1
    counts = result.get('familyRouteCounts', {})
    families = {key.split('/')[0] for key in counts}
    routes = {key.split('/')[1] for key in counts}
    overlapped = 0
    if mixed['firstWaveWithClient'] is not None and mixed['lastWaveWithClient'] is not None:
        overlapped = max(0, mixed['lastWaveWithClient'] - mixed['firstWaveWithClient'] + 1)
    mob_profiles = {f for f in families if f in rs.MOB_PROFILE_FAMILIES}
    first, restart = result.get('firstTxStatus') or {}, result.get('restartTxStatus') or {}
    kinds = lambda name: sum(1 for r in rows if r['ok'] and r['kind'] == name)  # noqa: E731
    return {
        'workersIsFour': args.workers == 4,
        'allFourRoutesPlanned': set(result.get('routes', [])) == set(ALL_ROUTES) and routes == set(ALL_ROUTES),
        'allFamiliesPresent': families == set(MIXED_ORDER),
        'everyAttemptTransferred': result.get('attemptCount', 0) > 0
        and result.get('actualTransfers') == result.get('attemptCount') and result.get('actualRefusals') == 0
        and result.get('actualContacts', 0) >= result.get('attemptCount', 1),
        'graphChildrenAccounted': result.get('graphChildTransfers') == result.get('expectedGraphTransfers')
        and result.get('expectedGraphTransfers', 0) > 0,
        'multipleMobProfiles': len(mob_profiles) >= 3,
        'noOwnerViolations': result.get('ownerViolations') == 0 and first.get('violations') == 0
        and restart.get('violations') == 0,
        'noPendingTransitions': first.get('pending') == 0 and restart.get('pending') == 0,
        'gatewayLane': mixed['gatewayAttempts'] >= MIN_GATEWAY_ATTEMPTS and mixed['gatewayOk'] == mixed['gatewayAttempts'],
        'containerContents': mixed['containerMinecarts'] > 0 and mixed['containerMinecartsOk'] == mixed['containerMinecarts'],
        'periodicSaves': len(mixed['saves']) >= MIN_SAVES and all(s['ok'] for s in mixed['saves']),
        'realClientConnected': not mixed['noClient'] and bool(result.get('playerListAfterConnect')),
        'playerPortalsBothDimensions': all(played.get(route, 0) >= need for route, need in MIN_PLAYER.items()),
        'playerMovement': kinds('movement') >= MIN_MOVEMENT,
        'playerContainerOpened': kinds('container') >= MIN_CONTAINER,
        'playerRespawn': kinds('respawn') >= MIN_RESPAWN,
        'playerReconnect': kinds('reconnect') >= MIN_RECONNECT,
        'playerLaneCleanAndOverlapped': mixed['laneError'] is None and overlapped >= MIN_OVERLAPPED_WAVES,
        'cleanSaveStopRestart': bool(result.get('firstSaved')) and result.get('firstExitCode') == 0
        and bool(result.get('restartSaved')) and result.get('restartExitCode') == 0
        and result.get('sourceWorldChanges') == [] and not result.get('configChanges'),
        'restartedWorldPlayable': bool((mixed.get('restartPlayer') or {}).get('ok')),
        'restartSentinel': result.get('globalSentinelAfterRestart') == 'Test passed. Count: 1',
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--name', required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--probe', type=Path, default=WORK / 'build/test-harness/stage7-repair-probe.jar')
    parser.add_argument('--seed', type=int, default=2704)
    parser.add_argument('--attempts', type=int, default=4608)
    parser.add_argument('--wave-size', type=int, default=8)
    parser.add_argument('--wave-timeout', type=float, default=60)
    parser.add_argument('--workers', type=int, choices=(1, 4), default=4)
    parser.add_argument('--gateway-every', type=int, default=8)
    parser.add_argument('--save-every', type=int, default=25)
    parser.add_argument('--no-client', action='store_true', help='debug only: never grants the gate')
    parser.add_argument('--java-port', type=int, default=25736)
    parser.add_argument('--bedrock-port', type=int, default=19236)
    parser.add_argument('--rcon-port', type=int, default=25836)
    args = parser.parse_args()
    args.families = ','.join(MIXED_ORDER)
    args.routes = ','.join(ALL_ROUTES)
    args.minimum_contacts = args.attempts
    args.tnt_card = False
    args.server_properties = {} if args.no_client else {'view-distance': '4', 'simulation-distance': '5'}
    mixed = Mixed(args)
    rs.Rcon.__call__ = persistent_rcon_call({})

    # The monitor is incremental (offset + tail), so the storm thread and the player lane must not scan at the same time:
    # two concurrent scans read the same bytes and every event in them is counted twice.
    scan_lock = threading.RLock()
    original_scan = rs.TransactionMonitor.scan

    def serialized_scan(self) -> None:
        with scan_lock:
            original_scan(self)

    rs.TransactionMonitor.scan = serialized_scan
    rs.HOOKS.update({'after_setup': mixed.after_setup, 'segment_settled': mixed.segment_settled,
                     'between_waves': mixed.between_waves, 'after_waves': mixed.after_waves,
                     'after_restart': mixed.after_restart, 'finalize': mixed.finalize})
    original = rs.prepare

    def prepare(*a, **k):
        server, manifest = original(*a, **k)
        # a fresh player: the audited seed may hold saved player data
        shutil.rmtree(server / 'STAGE7-STORM-W4' / 'players', ignore_errors=True)
        return server, manifest

    rs.prepare = prepare
    try:
        return rs.run(args)
    finally:
        mixed.shutdown()


if __name__ == '__main__':
    sys.exit(main())
