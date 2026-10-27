"""Stage 9 S9-E13 (R-C2) gates: shared helpers for rc2order9e13 / rc2teleport9e13 / rc2vehicles9e13 / rc2containers9e13.
Extracted from drafts/gate-draft-rc2.md section 6.1 (written for D32 3e1889d).
Lead adaptation 2026-10-10 (D34 d1b03f9 / D35 496a9cc and later): D34 flushes a player's pending move before the same
connection's next non-move packet (phoenium.engine.moveSplit.early, default true = the F1 fix), so the move-split
arms must now show vanilla per-connection causality and evidence of early flushes (moveSplitEarly > 0); vanilla shows
none. moveSplitFailures joins the must-be-zero set. Ports: one gate at a time on the lead block (25750 / 25751 /
18750 / 24250).

Fresh flat L5 server built by the Stage 8 builder (s8lib, read-only), Phoenium = S8_CANDIDATE in engine mode (dag, N tile
threads), one arm:
  check    moveSplit=true, moveSplit.check=1: every parallel flush re-evaluated serially (moveSplitMismatch must stay 0;
           a mismatch is also repaired by the check, so this arm cannot show client-visible effects)
  split    moveSplit=true, moveSplit.check=0: shipping semantics, the client-side audits are the evidence
  vanilla  moveSplit=false: the same jar and scenario, calibrates every client-side probe
every arm with moveSplit.min=2 and the tracker index self-check every tick (check=1, full=1). 32 headless offline bots
(gates/bots/rc2_bots.js; 26.1 through the server's ViaFabric; NODE_PATH = Stage 8 bot modules, read-only), 4 shards.
"""
import json, math, os, pathlib, re, subprocess, sys, threading, time

SCRIPTS8 = pathlib.Path(r'C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage8-compat-20261007\scripts8')
sys.path.insert(0, str(SCRIPTS8))
import s8lib as L  # noqa: E402

STAGE9 = pathlib.Path(r'C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage9-20261009')
BOT = STAGE9 / 'gates' / 'bots' / 'rc2_bots.js'
NODE_MODULES = L.EV / 'tools' / 'bots' / 'node_modules'
VERSION = '26.1'
TRAPS = ('loadTraps', 'writeTraps', 'storageTraps')
MUST_BE_ZERO = ('moveSplitMismatch', 'moveSplitStray', 'moveSplitFailures', 'trackerIndexStale', 'trackerReverseStale',
                'trackerIndexOffThread') + TRAPS
ARMS = {'check': ['-Dphoenium.engine.moveSplit=true', '-Dphoenium.engine.moveSplit.check=1'],
        'split': ['-Dphoenium.engine.moveSplit=true', '-Dphoenium.engine.moveSplit.check=0'],
        'vanilla': ['-Dphoenium.engine.moveSplit=false']}
SELF_CHECKS = ['-Dphoenium.engine.trackerIndex.check=1', '-Dphoenium.engine.trackerIndex.full=1']
VIEW = 6
RANGE = VIEW * 16                 # player and armor-stand range: min(trackingRange, viewDistance * 16) = 96 blocks
NEAR, FAR = 64, 120               # audit bands: must see at <= NEAR (inside the view circle), must not at >= FAR
SURFACE = -60                     # classic flat: grass top at y -61
PREFIX, SHARD = 'S9R', 8
OK_FILL = 'Successfully filled|No blocks'


def wait(seconds):
    time.sleep(seconds)


def now_ms():
    return time.time() * 1000.0                                  # the bots log Date.now(): same machine, same clock


def new_plan(**extra):
    return {'seed': 9013, 'joinIntervalMs': 300, 'clientViewDistance': 10, 'bots': [], **extra}


def add(plan, spec):
    spec['index'] = len(plan['bots'])
    spec['name'] = f'{PREFIX}{spec["index"]:03d}'
    plan['bots'].append(spec)
    return spec


def delta(after, before, key):
    return int(after.get(key, 0) or 0) - int(before.get(key, 0) or 0)


def counters(line):
    out = {}
    for k, v in re.findall(r'(\w+)=(\S+)', line or ''):
        out[k] = int(v) if v.lstrip('-').isdigit() else v
    return out


def by_bot(rows):
    out = {}
    for r in rows:
        out.setdefault(r['bot'], []).append(r)
    return out


def last_ids(rows):
    ids = {}
    for r in rows:
        if r['event'] in ('spawn', 'self') and 'id' in r:
            ids[r['bot']] = r['id']
    return ids


class Shards:
    """The bot shards: one node process per 8 bots, the same command line to each, DONE/READY lines on stdout."""

    def __init__(self, gate, plan):
        self.gate, self.next_id, self.procs = gate, 0, []
        path = gate.run / 'plan.json'
        path.write_text(json.dumps(plan, indent=1))
        env = dict(os.environ, NODE_PATH=str(NODE_MODULES))
        n = len(plan['bots'])
        for k, first in enumerate(range(0, n, SHARD)):
            out = gate.run / f'bots-shard{k}.jsonl'
            p = subprocess.Popen(['node', str(BOT), '--host', '127.0.0.1', '--port', str(gate.ports['java']), '--version', VERSION,
                                  '--plan', str(path), '--first', str(first), '--count', str(min(SHARD, n - first)), '--out', str(out)],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env, cwd=gate.run)
            buf = []
            threading.Thread(target=lambda p=p, buf=buf: [buf.append(l.rstrip()) for l in p.stdout], daemon=True).start()
            self.procs.append((p, buf, out))

    def marks(self):
        return [len(buf) for _, buf, _ in self.procs]

    def _wait(self, prefix, timeout, marks):
        deadline, got = time.time() + timeout, [None] * len(self.procs)
        while time.time() < deadline:
            for i, (p, buf, _) in enumerate(self.procs):
                if got[i] is None:
                    for line in buf[marks[i]:]:
                        if line.startswith(prefix):
                            got[i] = line
                            break
                        if line.startswith('ERROR'):
                            raise RuntimeError(f'bot shard {i}: {line}')
                    if got[i] is None and p.poll() is not None:
                        raise RuntimeError(f'bot shard {i} exited ({p.returncode})')
            if all(got):
                return got
            time.sleep(0.1)
        raise TimeoutError(f'bot shards did not print {prefix!r}: {got}')

    def ready(self, timeout=240):
        lines = self._wait('READY', timeout, [0] * len(self.procs))
        return sum(int(re.search(r'READY (\d+)/', l).group(1)) for l in lines)

    def post(self, command):
        self.next_id += 1
        for p, _, _ in self.procs:
            p.stdin.write(f'{self.next_id} {command}\n')
            p.stdin.flush()
        return self.next_id

    def wait_done(self, cid, marks, timeout):
        lines = self._wait(f'DONE {cid} ', timeout, marks)
        ok = sum(int(re.search(r'ok=(\d+)', l).group(1)) for l in lines)
        fail = sum(int(re.search(r'fail=(\d+)', l).group(1)) for l in lines)
        return ok, fail

    def send(self, command, timeout):
        marks = self.marks()
        return self.wait_done(self.post(command), marks, timeout)

    def events(self):
        rows = []
        for _, _, out in self.procs:
            if out.exists():
                for line in out.read_text(encoding='utf-8', errors='replace').splitlines():
                    try:
                        rows.append(json.loads(line))
                    except ValueError:
                        pass                                    # a line still being written
        rows.sort(key=lambda r: r['ts'])
        return rows

    def close(self):
        for p, _, _ in self.procs:
            if p.poll() is None:
                try:
                    p.stdin.write('0 quit\n'); p.stdin.flush()
                except OSError:
                    pass
        for p, buf, _ in self.procs:
            try:
                p.wait(20)
            except subprocess.TimeoutExpired:
                p.kill()
        (self.gate.run / 'bots-stdout.log').write_text('\n'.join('\n'.join(buf) for _, buf, _ in self.procs) + '\n')


class Gate:
    def __init__(self, kind, schema, props=None):
        if len(sys.argv) < 4:
            sys.exit(f'usage: python {pathlib.Path(sys.argv[0]).name} <name> <engine-workers> <base-port> '
                     '[--arm=check|split|vanilla] [--require-causality] [extra -D property ...]')
        self.name, self.workers, self.base = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
        opts = sys.argv[4:]
        self.arm = next((o.split('=', 1)[1] for o in opts if o.startswith('--arm=')), 'check')
        assert self.arm in ARMS, self.arm
        self.flags = {o for o in opts if o.startswith('--') and '=' not in o}
        extra = [o for o in opts if o.startswith('-D')]
        self.engine = ['-Dphoenium.engine.tiles=true', f'-Dphoenium.engine.workers={self.workers}', '-Dphoenium.engine.schedule=dag',
                       '-Dphoenium.engine.moveSplit.min=2', *ARMS[self.arm], *SELF_CHECKS, *extra]
        self.kind = kind
        self.run = STAGE9 / 'gates' / kind / self.name
        self.ports = {'java': self.base, 'rcon': self.base + 1, 'bedrock': self.base - 7000, 'voice': self.base - 1500}
        for k, p in self.ports.items():
            if not L.free_port(p, udp=k in ('bedrock', 'voice')):
                sys.exit(f'port busy {k}={p}')
        sp = {'level-type': 'minecraft:flat', 'generator-settings': '{}', 'spawn-protection': '0', 'gamemode': 'creative',
              'difficulty': 'peaceful', 'view-distance': str(VIEW), 'simulation-distance': '4', 'max-players': '40',
              'entity-broadcast-range-percentage': '100', 'allow-flight': 'true'}
        sp.update(props or {})
        manifest = L.build_server(self.run / 'server', L.STACK_LAYERS[L.FINAL], self.ports, f'S9E13-{kind}-{self.name}', extra_props=sp)
        self.res = {'schema': schema, 'name': self.name, 'arm': self.arm, 'flags': sorted(self.flags), 'engineWorkers': self.workers,
                    'engineProps': self.engine, 'serverProps': sp, 'utc': L.now(), 'candidate': str(L.CANDIDATE),
                    'candidateSha256': L.CANDIDATE_SHA, 'ports': self.ports, 'manifest': manifest, 'checks': [],
                    'diagnostics': {}, 'evidence': {}, 'informational': {}, 'audits': {}}
        self.ev, self.info = self.res['evidence'], self.res['informational']
        self.s = L.Server(self.run / 'server', 'console.log', workers=1, extra_props=self.engine).start()
        self.rcon = L.Rcon(self.ports['rcon'])
        self.bots = None
        self.plan = None

    # ---- plumbing
    def check(self, label, ok, detail=None):
        self.res['checks'].append({'label': label, 'ok': bool(ok), 'detail': detail})
        print(('PASS ' if ok else 'FAIL ') + label, '' if detail is None else detail, flush=True)

    def fail(self, e):
        self.res['failure'] = repr(e)
        print('FAILURE', repr(e), flush=True)

    def rc(self, command, expect=None):
        reply = self.rcon(command)
        if expect and not re.search(expect, reply):
            raise RuntimeError(f'rcon {command!r} -> {reply[-200:]!r}')
        return reply

    def summon(self, kind, x, y, z, nbt='', dim='overworld'):
        self.rc(f'execute in minecraft:{dim} run summon minecraft:{kind} {x} {y} {z} {{{nbt}}}', 'Summoned new')

    def forceload(self, x0, z0, x1, z1, dim='overworld'):
        for bx in range(x0, x1 + 1, 256):
            for bz in range(z0, z1 + 1, 256):
                self.rc(f'execute in minecraft:{dim} run forceload add {bx} {bz} {min(bx + 255, x1)} {min(bz + 255, z1)}', 'Marked|No chunks were marked')

    def clear_box(self, x0, z0, x1, z1):
        """Air from the surface up 5 blocks and grass under it, in 64 x 64 pieces (flat worlds can carry villages)."""
        for bx in range(x0, x1 + 1, 64):
            for bz in range(z0, z1 + 1, 64):
                ex, ez = min(bx + 63, x1), min(bz + 63, z1)
                self.fill(f'fill {bx} {SURFACE} {bz} {ex} {SURFACE + 5} {ez} minecraft:air')
                self.fill(f'fill {bx} {SURFACE - 1} {bz} {ex} {SURFACE - 1} {ez} minecraft:grass_block')

    def fill(self, command, timeout=60):
        """A fill that waits for force-loaded chunks still loading ('That position is not loaded')."""
        deadline = time.time() + timeout
        while True:
            reply = self.rcon(command)
            if re.search(OK_FILL, reply):
                return reply
            if 'not loaded' not in reply or time.time() > deadline:
                raise RuntimeError(f'rcon {command!r} -> {reply[-200:]!r}')
            time.sleep(0.5)

    def unforce(self, x0, z0, x1, z1, dim='overworld'):
        for bx in range(x0, x1 + 1, 256):
            for bz in range(z0, z1 + 1, 256):
                self.rc(f'execute in minecraft:{dim} run forceload remove {bx} {bz} {min(bx + 255, x1)} {min(bz + 255, z1)}',
                        'Unmarked|No chunks were removed')

    def diag(self, label):
        start = len(self.s.lines)
        self.s.console('phoenium diagnostics')
        line = self.s.wait_for(r'mode=engine-tiles lifecycle=', 30, start=start)[1]
        values = counters(line.split('mode=engine-tiles', 1)[1])
        self.res['diagnostics'][label] = values
        return values

    def invariants(self, label, previous=None):
        d = self.diag(label)
        bad = {k: d.get(k) for k in MUST_BE_ZERO if d.get(k) != 0}
        self.check(f'{label}: moveSplit / tracker index faults and traps 0', not bad, bad or None)
        if previous is not None:
            self.check(f'{label}: tracker index self-check ran', d.get('trackerIndexChecks', 0) > previous.get('trackerIndexChecks', 0),
                       (previous.get('trackerIndexChecks'), d.get('trackerIndexChecks')))
        return d

    # ---- lifecycle
    def boot(self):
        start = self.s.wait_for(r'PHOENIUM_STAGE2 started lifecycle=RUNNING', 900)[1]
        self.s.wait_for(r'Done \(', 120)
        self.res['engineStart'] = start
        want = 'false' if self.arm == 'vanilla' else 'true'
        self.check('engine mode, tile threads and moveSplit as armed', 'mode=engine-tiles' in start and f'workers={self.workers}' in start
                   and (self.workers == 0 or 'parallel=TILES' in start) and f'moveSplit={want}' in start, start[-300:])
        for rule, value in (('spawn_mobs', 'false'), ('advance_time', 'false'), ('advance_weather', 'false'),
                            ('player_movement_check', 'false'), ('log_admin_commands', 'true'), ('immediate_respawn', 'true')):
            self.rc(f'gamerule minecraft:{rule} {value}', 'is now set to|already')
        self.rc('time set 6000', r'Set minecraft:overworld to 6000 tick|Set the time')   # 26.2: 'Set minecraft:overworld to 6000 tick(s)'
        # 26.2 keeps no spawn chunks loaded and nobody is online yet: force-load the probe chunk until it answers
        self.rc('forceload add 8 8', 'Marked|No chunks were marked')
        deadline = time.time() + 60
        surface = self.rc(f'execute if block 8 {SURFACE - 1} 8 minecraft:grass_block')
        while 'not loaded' in surface and time.time() < deadline:
            time.sleep(0.5)
            surface = self.rc(f'execute if block 8 {SURFACE - 1} 8 minecraft:grass_block')
        self.rc('forceload remove 8 8', 'Unmarked|No chunks were removed')
        self.check('classic flat surface (grass at y -61)', 'Test passed' in surface, surface[-120:])
        if 'Test passed' not in surface:
            raise RuntimeError('unexpected flat surface')
        self.invariants('boot')

    def join(self, plan, op):
        self.plan = plan
        self.bots = Shards(self, plan)
        joined = self.bots.ready()
        self.check(f'bots: {joined}/{len(plan["bots"])} joined', joined == len(plan['bots']), joined)
        if joined != len(plan['bots']):
            raise RuntimeError('not every bot joined')
        for b in plan['bots']:
            if op:
                self.rc(f'op {b["name"]}', 'Made|Nothing changed')
            if 'home' in b:
                x, y, z = b['home']
                self.rc(f'execute in minecraft:overworld run tp {b["name"]} {x + 0.5 if float(x).is_integer() else x} {y} '
                        f'{z + 0.5 if float(z).is_integer() else z}', 'Teleported')

    def truth(self, names):
        out = {}
        for n in names:
            pos = re.search(r'\[(-?[\d.E-]+)d, (-?[\d.E-]+)d, (-?[\d.E-]+)d\]', self.rc(f'data get entity {n} Pos'))
            dim = re.search(r'"minecraft:([a-z_]+)"', self.rc(f'data get entity {n} Dimension'))
            out[n] = {'pos': [float(v) for v in pos.groups()] if pos else None, 'dim': dim.group(1) if dim else None}
        return out

    def audit(self, label, stands=None, freeze=False):
        """Quiescent pairing audit: stop, 6 s of keepalive moves, dump every bot's raw-packet view, compare with RCON truth."""
        self.bots.send('stop', 60)
        wait(6)
        self.bots.send(f'dump {label}', 60)
        rows = self.bots.events()
        dumps = {r['bot']: r for r in rows if r['event'] == 'dump' and r.get('tag') == label}
        alive = {n: d for n, d in dumps.items() if d.get('alive')}
        truth = self.truth([b['name'] for b in self.plan['bots']])   # every plan name: an offline one has pos None
        ids = {n: d['id'] for n, d in alive.items()}
        unpaired, ghosts, stale, unknown, stand_bad, drift = [], [], [], [], [], []
        for o, od in alive.items():
            to = truth[o]
            if to['pos'] is None:
                continue
            if math.dist(to['pos'], od['pos']) > 2.0:
                drift.append((o, to['pos'], od['pos']))
            seen = {e.get('who'): e for e in od['seen'] if e['type'] == 'player'}
            for p, tp in truth.items():
                if p == o or tp['pos'] is None:
                    continue
                same = tp['dim'] == to['dim']
                dist = math.hypot(tp['pos'][0] - to['pos'][0], tp['pos'][2] - to['pos'][2])
                if same and dist <= NEAR and p not in seen:
                    unpaired.append((o, p, round(dist, 1)))
                if (not same or dist >= FAR) and p in seen:
                    ghosts.append((o, p, round(dist, 1), to['dim'], tp['dim']))
                if p in seen and seen[p]['id'] != ids.get(p):
                    stale.append((o, p, seen[p]['id'], ids.get(p)))
            unknown += [(o, w) for w in seen if w not in truth or truth[w]['pos'] is None]   # seen but not online
            if stands is not None:
                got = [e['pos'] for e in od['seen'] if e['type'] == 'armor_stand' and e.get('pos')]
                for s in stands:
                    dist = math.hypot(s[0] - to['pos'][0], s[2] - to['pos'][2])
                    has = any(abs(g[0] - s[0]) < 1 and abs(g[2] - s[2]) < 1 for g in got)
                    if to['dim'] == 'overworld' and dist <= NEAR and not has:
                        stand_bad.append((o, 'unpaired', s))
                    if (to['dim'] != 'overworld' or dist >= FAR) and has:
                        stand_bad.append((o, 'ghost', s))
        self.res['audits'][label] = {'observers': len(alive), 'unpaired': len(unpaired), 'ghosts': len(ghosts), 'stale': len(stale),
                                     'unknown': len(unknown), 'stands': len(stand_bad), 'clientDrift': drift[:10]}
        self.check(f'{label}: every player within {NEAR} blocks paired (no player left unpaired)', not unpaired, unpaired[:20] or None)
        self.check(f'{label}: nobody seen across dimensions or beyond {FAR} blocks (no ghost)', not ghosts, ghosts[:20] or None)
        self.check(f'{label}: every seen player has its current entity id and is online', not stale and not unknown,
                   (stale + unknown)[:20] or None)
        if stands is not None:
            self.check(f'{label}: armor-stand grid paired exactly in the bands', not stand_bad, stand_bad[:20] or None)
        self.invariants(label)
        return alive, truth

    def finish(self, min_checks):
        if self.bots is not None:
            self.bots.close()
        try:
            self.rcon.close()
        except Exception:  # noqa: BLE001
            pass
        self.res['exit'] = self.s.stop()
        lines = list(self.s.lines)
        stop = next((l for l in reversed(lines) if 'PHOENIUM_STAGE2 stopped' in l), None)
        vals = counters((stop or '').split('PHOENIUM_STAGE2 stopped', 1)[-1])
        self.res['engineStop'] = stop
        self.res['stopFaults'] = {k: vals[k] for k in MUST_BE_ZERO if k in vals}
        self.res['stopMoveSplit'] = {k: v for k, v in vals.items() if k.startswith('moveSplit')}
        ms = self.res['stopMoveSplit']
        early = ms.get('moveSplitEarly', 0) > 0                     # D34+: the F1 early flush ran (a move then a non-move packet)
        arm_ok = {'check': ms.get('moveSplitDeferred', 0) > 0 and ms.get('moveSplitParallel', 0) > 0 and ms.get('moveSplitChecks', 0) > 0
                           and early,
                  'split': ms.get('moveSplitDeferred', 0) > 0 and ms.get('moveSplitParallel', 0) > 0 and ms.get('moveSplitChecks', 1) == 0
                           and early,
                  'vanilla': ms.get('moveSplitDeferred', 1) == 0 and ms.get('moveSplitFlushes', 1) == 0
                             and ms.get('moveSplitEarly', 1) == 0}[self.arm]
        self.check(f'stop: moveSplit evidence for the {self.arm} arm', arm_ok, ms)
        self.res['faultLines'] = [l for l in lines if 'PHOENIUM_ENGINE moveSplit mismatch' in l or 'PHOENIUM_ENGINE trackerIndex stale' in l][:20]
        crash = sorted((self.run / 'server/crash-reports').glob('*')) if (self.run / 'server/crash-reports').exists() else []
        self.res['crashReports'] = [str(c) for c in crash]
        self.res['scan'] = L.scan(lines)
        self.res['pass'] = (not self.res.get('failure') and all(c['ok'] for c in self.res['checks'])
                            and len(self.res['checks']) >= min_checks and self.res['exit'] == 0 and not crash
                            and not self.res['scan']['badPatternLines'] and len(self.res['stopFaults']) == len(MUST_BE_ZERO)
                            and sum(self.res['stopFaults'].values()) == 0 and not self.res['faultLines'])
        self.res['endUtc'] = L.now()
        (self.run / f'{self.kind}-result.json').write_text(json.dumps(self.res, indent=1))
        print(json.dumps({k: self.res.get(k) for k in ('name', 'arm', 'pass', 'exit', 'crashReports', 'stopFaults', 'evidence',
                                                       'informational', 'audits')}, indent=1),
              sum(c['ok'] for c in self.res['checks']), '/', len(self.res['checks']), 'checks')
