"""Stage 7 closeout real-client census: a real installed server (pinned providers, probe observer) PLUS one real Java client.

Usage: python scripts/stage7/client_closeout.py <fresh-name> <workers 1|4> <candidate-jar> <cards|--explore=steps.txt> [--vanilla]

The client is the repository's own Fabric client game-test runtime (src/stage7CloseoutClient), started from the command line with the
offline test identity and driven through a file protocol; it reports what the REAL client sees (its dimension, position, vehicle, loaded
entities, inventory, open screen). The server is the same installed layout the headless census uses and is driven over RCON. The same
cards run on a Phoenium-free control (--vanilla); the importer credits a client row only when the Phoenium observables equal the control's.

A card is K1-<kind>-<ROUTE> (kind = player, player_cooldown, player_sleeping, credits_unseen, <graph shape>, ender_pearl, item, fishing_bobber...).
Run on a machine with nothing else running: the client is started at below-normal priority, 480x270, render distance 2, 20 fps.
"""
import pathlib, subprocess, hashlib, json, shutil, socket, struct, time, sys, datetime, os, re

script_path = pathlib.Path(__file__).resolve()
work = script_path.parents[2]
sys.path.insert(0, str(script_path.parent))
from campaign_paths import AUDIT as audit, BASE_AUDIT as base_audit  # noqa: E402

root = work.parent.parent
pinned = json.loads((root / '.audit/container-close-admission-20260922/PINNED_PROFILE_MANIFEST.json').read_text(encoding='utf-8-sig'))
template = pathlib.Path(pinned['template'])
name = sys.argv[1]
workers = int(sys.argv[2]); assert workers in (1, 4)
candidate = pathlib.Path(sys.argv[3])
census = sys.argv[4]
explore = census.partition('=')[2] if census.startswith('--explore=') else None
vanilla = '--vanilla' in sys.argv
ports = dict(java=25699, bedrock=19199, rcon=25799) if vanilla else dict(java=25698, bedrock=19198, rcon=25798)
run = audit / 'installed' / name
server = run / 'server'
control = run / 'control'
java = pathlib.Path(r'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe')

OW, NETHER, END = 'minecraft:overworld', 'minecraft:the_nether', 'minecraft:the_end'
BELOW_NORMAL = 0x4000


def sha(p):
    with p.open('rb') as s:
        return hashlib.file_digest(s, 'sha256').hexdigest().upper()


class Rcon:
    """One persistent RCON connection (reconnects on failure)."""
    def __init__(self):
        self.s = None; self.n = 10

    def _open(self):
        self.s = socket.create_connection(('127.0.0.1', ports['rcon']), timeout=3); self.s.settimeout(60)
        self._send(1, 3, 'phoenium-stage7-local-test'); self._read()

    def _send(self, i, t, body):
        b = body.encode(); self.s.sendall(struct.pack('<iii', len(b) + 10, i, t) + b + b'\0\0')

    def _recv(self, count):
        b = b''
        while len(b) < count:
            part = self.s.recv(count - len(b))
            if not part: raise EOFError('RCON closed')
            b += part
        return b

    def _read(self):
        length = struct.unpack('<i', self._recv(4))[0]; b = self._recv(length)
        if struct.unpack('<i', b[:4])[0] < 0: raise RuntimeError('RCON authentication failed')
        return b[8:-2].decode(errors='replace')

    def _read_whole(self):
        text = self._read()
        while len(text.encode()) >= 4096 and len(text) % 4096 == 0:
            self.s.settimeout(1.0)
            try: more = self._read()
            except (socket.timeout, TimeoutError): break
            finally: self.s.settimeout(60)
            text += more
        return text

    def __call__(self, command, whole=False):
        for attempt in (0, 1):
            try:
                if self.s is None: self._open()
                self.n += 1; self._send(self.n, 2, command)
                return self._read_whole() if whole else self._read()
            except (OSError, EOFError):
                try: self.s.close()
                except Exception: pass
                self.s = None
                if attempt: raise


rcon = Rcon()


def snapshot_body():
    raw = rcon('stage7-probe snapshot', whole=True)
    return json.loads(raw[raw.index('{'):])


PROBE_EVENT_KEYS = (('contact', 'gatewayContactEvents'), ('ticket', 'gatewayTicketEvents'), ('tracking', 'gatewayTrackingEvents'),
                    ('noEntry', 'noEntryContactEvents'))


def tx_count():
    """Number of STAGE7_PORTAL_TX events so far: card windows into the transaction trace are index ranges over it."""
    raw = rcon('stage7-tx status', whole=True)
    return int(json.loads(raw[raw.index('{'):])['events'])


def probe_event_counts():
    body = snapshot_body()
    return {name: len(body.get(key) or []) for name, key in PROBE_EVENT_KEYS}


def probe_events_since(before):
    body = snapshot_body()
    return {name: (body.get(key) or [])[before[name]:] for name, key in PROBE_EVENT_KEYS}


class Agent:
    """File protocol to the real client (control/cmd.json -> control/resp.json)."""
    def __init__(self): self.n = 0

    def call(self, op, timeout=180, **kw):
        self.n += 1
        cmd = dict(id=self.n, op=op, **kw)
        tmp = control / 'cmd.tmp'
        tmp.write_text(json.dumps(cmd)); os.replace(tmp, control / 'cmd.json')
        deadline = time.monotonic() + timeout
        resp_path = control / 'resp.json'
        while time.monotonic() < deadline:
            if resp_path.exists():
                try: resp = json.loads(resp_path.read_text())
                except ValueError: time.sleep(.05); continue
                resp_path.unlink()
                if resp.get('id') != self.n: continue
                if not resp.get('ok'): raise RuntimeError('client %s failed: %s' % (op, resp.get('error')))
                return resp
            if client is not None and client.poll() is not None: raise RuntimeError('client process exited')
            time.sleep(.05)
        raise TimeoutError('client did not answer ' + op)

    def snapshot(self): return self.call('snapshot')['snapshot']


agent = Agent()
client = None

if census.startswith('--explore='):
    cards = []
else:
    cards = [c for c in census.split(',') if c]

run.mkdir(parents=True, exist_ok=False); server.mkdir(); control.mkdir()
for n, h in pinned['providers'].items(): assert sha(template / 'mods' / n) == h, n
for n, h in pinned['configFilesSha256'].items(): assert sha(template / 'config' / n) == h, n
for n in ['libraries', 'versions']:
    shutil.copytree(template / n, server / n, copy_function=lambda s, d: os.link(s, d))
shutil.copytree(template / 'config', server / 'config')
for n in ['fabric-server-launch.jar', 'eula.txt']: shutil.copy2(template / n, server / n)
(server / 'mods').mkdir()
probe = work / 'build/test-harness/stage7-repair-probe.jar'
if vanilla:
    digest = 'VANILLA'; immutable = pathlib.Path('NONE')
else:
    for n in pinned['providers']: os.link(template / 'mods' / n, server / 'mods' / n)
    digest = sha(candidate)
    immutable = run / ('phoenium-stage7-' + digest[:12].lower() + '.jar'); shutil.copy2(candidate, immutable)
    shutil.copy2(immutable, server / 'mods' / immutable.name)
    shutil.copy2(probe, server / 'mods' / probe.name)
source_world = root / '.audit/astra-max-stage7-20260922/manual-ready/server/STAGE7-LUNA-FINAL'
target_world = server / ('STAGE7-CLIENT-W' + str(workers))
shutil.copytree(source_world, target_world)
shutil.rmtree(target_world / 'players', ignore_errors=True)   # a fresh player: it has not seen the credits, has no spawn, no cooldowns
props = template.joinpath('server.properties').read_text().splitlines()
over = {'server-port': str(ports['java']), 'level-name': target_world.name, 'enable-rcon': 'true', 'rcon.port': str(ports['rcon']),
        'rcon.password': 'phoenium-stage7-local-test', 'motd': 'Phoenium Stage7 closeout client',
        'online-mode': 'false', 'enforce-secure-profile': 'false', 'server-ip': '127.0.0.1', 'pause-when-empty-seconds': '-1',
        'view-distance': '4', 'simulation-distance': '5'}
props = [l.split('=')[0] + '=' + over.pop(l.split('=')[0]) if '=' in l and l.split('=')[0] in over else l for l in props]
props += [k + '=' + v for k, v in over.items()]
(server / 'server.properties').write_text('\n'.join(props) + '\n')
if not vanilla:
    geyser = server / 'config/Geyser-Fabric/config.yml'
    geyser.write_text(geyser.read_text().replace('19172', str(ports['bedrock'])))
else:
    shutil.rmtree(server / 'config')
world_hashes = {str(q.relative_to(source_world)): sha(q) for q in source_world.rglob('*') if q.is_file() and q.name != 'session.lock'}
(run / 'world-copy.json').write_text(json.dumps(dict(source=str(source_world), destination=str(target_world), hashes=world_hashes), indent=2))
census_v2 = json.loads((base_audit / 'PORTAL_BEHAVIOR_CENSUS_V2.json').read_text(encoding='utf-8'))
agent_sources = [work / 'scripts/stage7-closeout-client.gradle'] + [q for q in (work / 'src/stage7CloseoutClient').rglob('*') if q.is_file()]
test_sources = {str(q.relative_to(work)): sha(q) for base in [work / 'src/clientGameTest', work / 'src/test/java/dev/phoenium/stage7repair', work / 'src/stage7RepairHarness']
                for q in base.rglob('*') if q.is_file()}
test_sources.update({str(q.relative_to(work)): sha(q) for q in agent_sources})
test_sources[str(script_path.relative_to(work))] = sha(script_path)
inputs = dict(candidate=str(immutable), candidateSha256=digest, probeSha256=None if vanilla else sha(probe), workers=workers, vanilla=vanilla,
              group='client-closeout', cards=cards, census=census, headless=False, evidence='real-client',
              censusScriptSha256=sha(script_path), testSourceHashes=test_sources,
              head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=work, text=True).strip(),
              sourceStatus=subprocess.check_output(['git', 'status', '--short'], cwd=work, text=True),
              providers=pinned['providers'], configHashes={} if vanilla else {n: sha(server / 'config' / n) for n in pinned['configFilesSha256']})
if vanilla:
    inputs.update(mods=[], sourceJarSha256=census_v2['sourceJarSha256'], mappedBinarySha256=census_v2['mappedBinarySha256'],
                  vanillaServerJarSha256=sha(server / 'versions/26.2/server-26.2.jar'))
(run / 'inputs.json').write_text(json.dumps(inputs, indent=2))

console = run / 'first-server-console.log'
clientlog = run / 'client.log'
command = [str(java), '-Xmx2G', '-Dphoenium.test.diagnostics=true', '-Dphoenium.gameplay.workers=' + str(workers),
           '-Djava.net.useSystemProxies=false', '-Dminecraft.api.session.host=http://127.0.0.1:9',
           '-Dminecraft.api.services.host=http://127.0.0.1:9', '-jar', 'fabric-server-launch.jar', 'nogui']
FAIL_MARKERS = ['scheduler=FAILED', 'Encountered an unexpected exception', 'STAGE7_OBSERVER_FAILED', 'STAGE7_PORTAL_TX_VIOLATION',
                'Mixin apply for mod', 'Error executing task on Server', 'Failed to handle packet', 'Exception ticking',
                'Exception stopping the server']


def logtext(): return console.read_text(errors='replace') if console.exists() else ''
def failed(): t = logtext(); return next((m for m in FAIL_MARKERS if m in t), None)


def nether_fixture(world):
    prefix = 'execute in ' + world + ' run '
    for c in ['forceload add -16 -16 16 16', 'fill -12 64 -12 12 64 12 minecraft:sea_lantern',
              'fill -12 65 -12 12 76 12 minecraft:air', 'fill -12 76 -12 12 76 12 minecraft:glass',
              'fill -12 65 -12 -12 75 12 minecraft:glass', 'fill 12 65 -12 12 75 12 minecraft:glass',
              'fill -12 65 -12 12 75 -12 minecraft:glass', 'fill -12 65 12 12 75 12 minecraft:glass',
              'fill -2 64 0 2 69 0 minecraft:obsidian', 'fill -1 65 0 1 68 0 minecraft:air',
              'setblock -1 65 0 minecraft:fire', 'fill -2 65 -10 2 69 -10 minecraft:stone']:
        rcon(prefix + c)
    return 'Test passed' in rcon(prefix + 'execute if block 0 66 0 minecraft:nether_portal')


def end_fixture(world, y):
    for c in ['forceload add 0 0 15 15', 'fill 4 %d 4 12 %d 12 minecraft:obsidian' % (y - 1, y - 1),
              'fill 4 %d 4 12 %d 12 minecraft:air' % (y, y + 6), 'setblock 8 %d 8 minecraft:end_portal' % y]:
        rcon('execute in %s run %s' % (world, c))


def run_explore(path):
    """Interactive development aid: each line of the steps file is  rcon:<cmd> | agent:<op> <json> | snap | psnap | sleep:<s> | say:<text>."""
    out = []
    for line in pathlib.Path(path).read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#'): continue
        kind, _, rest = line.partition(':') if not line.startswith(('snap', 'psnap')) else (line, '', '')
        if kind == 'rcon': value = rcon(rest, whole=True)
        elif kind == 'agent':
            op, _, extra = rest.partition(' ')
            value = agent.call(op, **(json.loads(extra) if extra else {}))
        elif kind == 'snap': value = agent.snapshot()
        elif kind == 'psnap': value = snapshot_body() if not vanilla else 'vanilla'
        elif kind == 'sleep': time.sleep(float(rest)); value = 'slept'
        else: value = 'IGNORED'
        out.append({'step': line, 'value': value})
        print('STEP', line, '=>', json.dumps(value)[:700], flush=True)
    (run / 'explore-output.json').write_text(json.dumps(out, indent=1))


# ----------------------------------------------------------------------------------------------------------------------------
# K1 cards: the connected PhoeniumTest0 client is the entity (or the rider) that enters the portal.
# ----------------------------------------------------------------------------------------------------------------------------
PLAYER = 'PhoeniumTest0'
ROUTE_LIST = ('NETHER_ENTRY', 'NETHER_RETURN', 'END_ENTRY', 'END_EXIT', 'END_GATEWAY')
SRC_DST = {'NETHER_ENTRY': (OW, NETHER), 'NETHER_RETURN': (NETHER, OW), 'END_ENTRY': (OW, END), 'END_EXIT': (END, OW), 'END_GATEWAY': (END, END)}
CARD = re.compile(r'K1-([a-z_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)')
# where the player stands when it is walked into the route's portal cell
CELL = {'NETHER_ENTRY': '0.5 65 0.5', 'NETHER_RETURN': '0.5 65 0.5', 'END_ENTRY': '8.5 65.1 8.5', 'END_EXIT': '8.5 70.1 8.5', 'END_GATEWAY': '12.5 66 8.5'}
REST = {'NETHER_ENTRY': '-6.5 65 -5.5', 'NETHER_RETURN': '-6.5 65 -5.5', 'END_ENTRY': '5.5 65 5.5', 'END_EXIT': '5.5 70 5.5', 'END_GATEWAY': '16.5 65 8.5'}
state = dict(credits_seen=False, index=0)


def nbt(path, entity=PLAYER):
    out = rcon('data get entity %s %s' % (entity, path), whole=True)
    return out.split('entity data: ', 1)[1].strip() if 'entity data: ' in out else out.strip()


def pstate():
    out = {}
    for key, path in (('dimension', 'Dimension'), ('pos', 'Pos'), ('cooldown', 'PortalCooldown'), ('seenCredits', 'seenCredits'),
                      ('health', 'Health'), ('gamemode', 'playerGameType')):
        try: raw = nbt(path)
        except Exception as error: raw = 'ERR ' + str(error)
        out[key] = raw
    out['dimension'] = out['dimension'].strip('"')
    try: out['posValues'] = [float(v) for v in re.findall(r'-?\d+(?:\.\d+)?(?=d)', out['pos'])]
    except ValueError: out['posValues'] = None
    return out


def cd_of(pst):
    try: return int(re.match(r'-?\d+', pst['cooldown']).group(0))
    except Exception: return None


def probe_members(tag):
    if vanilla: return None
    return [e for e in snapshot_body()['entities'] if tag in e.get('tags', '')]


def observe(tag, with_client=True):
    out = {'server': pstate(), 'probe': probe_members(tag)}
    if with_client: out['client'] = agent.snapshot()
    return out


def place(world, where, wait_cooldown=True, timeout=60):
    """Put the player at rest in `world`, wait until the REAL client is there and the portal cooldown is spent."""
    x, y, z = [float(v) for v in where.split()]
    rcon('execute in %s run tp %s %s %s %s' % (world, PLAYER, x, y, z))
    deadline = time.monotonic() + timeout
    nudge = time.monotonic() + 6
    pst = None
    while time.monotonic() < deadline:
        snap = agent.snapshot()
        pst = pstate()
        near = snap.get('connected') and snap.get('dimension') == world and snap.get('screen') is None \
            and abs(snap['player']['x'] - x) < 2 and abs(snap['player']['z'] - z) < 2
        if near and pst['dimension'] == world and (not wait_cooldown or cd_of(pst) == 0): return snap, pst
        if time.monotonic() > nudge:      # a stale ride or an overwritten teleport: dismount and ask again
            rcon('ride %s dismount' % PLAYER)
            rcon('execute in %s run tp %s %s %s %s' % (world, PLAYER, x, y, z))
            nudge = time.monotonic() + 6
        time.sleep(.25)
    raise RuntimeError('player did not settle in %s at %s: %s' % (world, where, json.dumps(pst)))


def build_fixture(route):
    src, dst = SRC_DST[route]
    if route.startswith('NETHER'):
        for world in (OW, NETHER): assert nether_fixture(world), world
    elif route == 'END_GATEWAY':
        for c in ['forceload add 0 0 24 20', 'fill 8 64 4 24 64 16 minecraft:end_stone', 'fill 8 65 4 24 73 16 minecraft:air',
                  'setblock 12 66 8 minecraft:end_gateway', 'data merge block 12 66 8 {exit_portal:[I;22,66,12],ExactTeleport:1b,Age:200L}']:
            rcon('execute in %s run %s' % (END, c))
    else:
        end_fixture(src, 65 if route == 'END_ENTRY' else 70)


def wait_arrival(route, timeout=40):
    """Poll the server until the player's dimension changed (gateway: until it moved east of the fixture). Returns pstate or None."""
    src, dst = SRC_DST[route]
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        pst = pstate()
        if route == 'END_GATEWAY':
            if pst['posValues'] and pst['posValues'][0] > 18: return pst
        elif pst['dimension'] == dst: return pst
        if process.poll() is not None or failed(): raise RuntimeError('server failure: ' + str(failed()))
        time.sleep(.1)
    return None


def hold_forward(ticks=15):
    before = agent.snapshot()
    agent.call('hold', key='forward', ticks=ticks)
    after = agent.snapshot()
    return {'clientBefore': before.get('player'), 'clientAfter': after.get('player'), 'server': pstate()}


def ensure_credits_seen():
    """The player's first End exit shows the credits and respawns it (native). Done through the real client."""
    if state['credits_seen']: return
    build_fixture('END_EXIT')
    place(END, REST['END_EXIT'])
    rcon('execute in %s run tp %s %s' % (END, PLAYER, CELL['END_EXIT']))
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if agent.snapshot().get('screen') == 'WinScreen': break
        time.sleep(.3)
    else: raise RuntimeError('End credits never appeared')
    agent.call('wait', ticks=10); agent.call('closeScreen')
    agent.call('waitScreenGone', ticks=1200)
    state['credits_seen'] = True


def fresh_player():
    """Disconnect, drop the saved player data and reconnect: a brand-new player that has not seen the End credits."""
    agent.call('disconnect', timeout=120)
    for q in list((target_world / 'players').rglob('*.dat*')): q.unlink()
    agent.call('connect', timeout=300)
    state['credits_seen'] = False
    place(OW, REST['NETHER_ENTRY'])


def player_card(route, tag):
    """The real client walks into the route's portal cell. Observables: server NBT, the probe's players array, the client's own view."""
    src, dst = SRC_DST[route]
    witness = {}
    if route == 'END_EXIT': ensure_credits_seen()
    build_fixture(route)
    place(src, REST[route])
    rcon('gamemode survival ' + PLAYER)
    rcon('tag %s add %s' % (PLAYER, tag))
    witness['source'] = observe(tag)
    rcon('execute in %s run tp %s %s' % (src, PLAYER, CELL[route]))
    t0 = time.monotonic()
    arrived = wait_arrival(route)
    outcome = 'TRANSFERRED' if arrived else 'STAYED'
    if arrived:
        witness['arrival'] = observe(tag)
        time.sleep(2.5)
        witness['later'] = observe(tag)
        witness['playable'] = hold_forward()
        witness['final'] = observe(tag)
    else:
        time.sleep(3)
        witness['settled'] = observe(tag)
    witness['seconds'] = round(time.monotonic() - t0, 2)
    rcon('tag %s remove %s' % (PLAYER, tag))
    return outcome, witness


def credits_unseen_card(tag):
    witness = {}
    build_fixture('END_EXIT')
    place(END, REST['END_EXIT'])
    rcon('gamemode survival ' + PLAYER)
    rcon('tag %s add %s' % (PLAYER, tag))
    witness['source'] = observe(tag)
    rcon('execute in %s run tp %s %s' % (END, PLAYER, CELL['END_EXIT']))
    deadline = time.monotonic() + 120; shown = None
    while time.monotonic() < deadline:
        snap = agent.snapshot()
        if snap.get('screen') == 'WinScreen': shown = snap; break
        time.sleep(.3)
    witness['creditsShown'] = shown
    if shown is None: return 'STAYED', witness
    witness['creditsServer'] = pstate(); witness['creditsProbe'] = probe_members(tag)
    agent.call('wait', ticks=10); agent.call('closeScreen')
    agent.call('waitScreenGone', ticks=1200)
    state['credits_seen'] = True
    witness['arrival'] = observe(tag)
    time.sleep(2.5)
    witness['later'] = observe(tag)
    witness['playable'] = hold_forward()
    witness['final'] = observe(tag)
    rcon('tag %s remove %s' % (PLAYER, tag))
    return 'TRANSFERRED', witness


# ---- player-rider graphs ----------------------------------------------------------------------------------------------------
ITEMS_BOAT = ',Items:[{Slot:0b,id:"minecraft:diamond",count:7},{Slot:26b,id:"minecraft:sugar_cane",count:3}]'
# shape -> (vehicle type, extra summon NBT, second riders [(type, mounts)]) ; the player always rides the vehicle
PGRAPHS = {
    'pboat': ('oak_boat', '', []),
    'pminecart': ('minecart', '', []),
    'pmob': ('pig', '', []),
    'praft': ('bamboo_raft', '', []),
    'pcamel': ('camel', '', [('villager', 'vehicle')]),
    'pchestboat': ('oak_chest_boat', ITEMS_BOAT, [('villager', 'vehicle')]),
    'cboat': ('oak_boat', '', []),
    'cminecart': ('minecart', '', []),
    'ccamel': ('camel', '', []),
}
CREDITS_SHAPES = {'cboat', 'cminecart', 'ccamel'}


def count_in(world, tag):
    rcon('execute in %s positioned 0 64 0 store result score #c hc if entity @e[tag=%s,distance=0..2000]' % (world, tag))
    found = re.search(r'has (-?\d+)', rcon('scoreboard players get #c hc'))
    return int(found.group(1)) if found else 0


def count_far(world, tag):
    rcon('execute in %s store result score #c hc if entity @e[tag=%s,x=-300,y=0,z=-300,dx=700,dy=300,dz=700]' % (world, tag))
    found = re.search(r'has (-?\d+)', rcon('scoreboard players get #c hc'))
    return int(found.group(1)) if found else 0


def graph_card(shape, route, tag):
    vehicle, extra, others = PGRAPHS[shape]
    src, dst = SRC_DST[route]
    witness = {'shape': shape}
    credits = shape in CREDITS_SHAPES
    if credits:
        assert route == 'END_EXIT', 'credits shapes only exist on END_EXIT'
        if state['credits_seen']: fresh_player()
    elif route == 'END_EXIT': ensure_credits_seen()
    build_fixture(route)
    place(src, REST[route])
    rcon('gamemode survival ' + PLAYER)
    rcon('tag %s add %s' % (PLAYER, tag))
    x, y, z = [float(v) for v in REST[route].split()]
    summon = rcon('execute in %s run summon minecraft:%s %s %s %s {Tags:["stage7_mob","%s"],NoGravity:1b,NoAI:1b,Silent:1b%s}'
                  % (src, vehicle, x + 1.5, y, z, tag, extra))
    pick = '@e[tag=%s,type=minecraft:%s,limit=1]' % (tag, vehicle)
    witness['ride'] = [rcon('ride %s mount %s' % (PLAYER, pick))]
    total = 2
    for other, target in others:
        rcon('execute in %s run summon minecraft:%s %s %s %s {Tags:["stage7_mob","%s"],NoGravity:1b,NoAI:1b,Silent:1b}' % (src, other, x + 1.5, y, z + 1.5, tag))
        witness['ride'].append(rcon('ride @e[tag=%s,type=minecraft:%s,limit=1] mount %s' % (tag, other, pick)))
        total += 1
    witness['summon'] = summon.strip()[:80]
    time.sleep(.5)
    witness['source'] = observe(tag)
    witness['seated'] = [rcon('execute as %s on vehicle if entity @s[tag=%s]' % (PLAYER, tag))]
    rcon('execute in %s run tp %s %s' % (src, pick, CELL[route]))
    far = route == 'END_GATEWAY'
    counts = {'source': None, 'destination': None}
    t0 = time.monotonic(); outcome = 'STAYED'
    while time.monotonic() - t0 < 14:
        if process.poll() is not None or failed(): raise RuntimeError('server failure during graph card: ' + str(failed()))
        if far:
            pst = pstate()
            counts['destination'] = total if (pst['posValues'] and pst['posValues'][0] > 18) else 0
            counts['source'] = total - counts['destination']
        else:
            counts['destination'] = count_in(dst, tag); counts['source'] = count_in(src, tag)
        if counts['destination'] == total: outcome = 'TRANSFERRED'; break
        time.sleep(.25)
    else:
        if counts['destination'] == 0 and counts['source'] == total: outcome = 'STAYED'
        else: outcome = 'PARTIAL'
    witness['counts'] = dict(counts, total=total)
    if outcome == 'TRANSFERRED':
        witness['arrival'] = observe(tag)
        time.sleep(3)
        witness['later'] = observe(tag)
        witness['seatedLater'] = [rcon('execute as %s on vehicle if entity @s[tag=%s]' % (PLAYER, tag))]
        witness['ride'].append(rcon('ride %s dismount' % PLAYER)); time.sleep(.5)
        witness['playable'] = hold_forward()
        witness['final'] = observe(tag)
    else:
        time.sleep(3)
        witness['settled'] = observe(tag)
        rcon('ride %s dismount' % PLAYER)
    witness['seconds'] = round(time.monotonic() - t0, 2)
    rcon('tag %s remove %s' % (PLAYER, tag))
    rcon('execute in %s run kill @e[tag=%s,type=!minecraft:player]' % (src, tag))
    rcon('execute in %s run kill @e[tag=%s,type=!minecraft:player]' % (dst, tag))
    return outcome, witness


# ---- cooldown / pearl / item / bobber cards ---------------------------------------------------------------------------------
OTHER = {'NETHER_ENTRY': NETHER, 'NETHER_RETURN': OW, 'END_ENTRY': END, 'END_EXIT': OW, 'END_GATEWAY': OW}   # a world the player is parked in first


def east_count(selector_tags):
    rcon('execute in %s store result score #c hc if entity @e[tag=%s,x=18,y=0,z=0,dx=300,dy=300,dz=300]' % (END, selector_tags))
    found = re.search(r'has (-?\d+)', rcon('scoreboard players get #c hc'))
    return int(found.group(1)) if found else 0


def player_uuid_nbt():
    raw = nbt('UUID')
    return raw.strip()


def sample_series(route, seconds, interval=.25):
    """(t, dimension, portalCooldown, x) samples of the connected player."""
    src, dst = SRC_DST[route]
    series = []; t0 = time.monotonic()
    while time.monotonic() - t0 < seconds:
        try:
            dim = nbt('Dimension').strip('"'); cd = nbt('PortalCooldown'); pos = nbt('Pos')
        except Exception as error:
            dim = cd = pos = 'ERR ' + str(error)
        series.append([round(time.monotonic() - t0, 2), dim, cd, pos])
        if process.poll() is not None or failed(): raise RuntimeError('server failure during cooldown card: ' + str(failed()))
        time.sleep(interval)
    return series


REVERSE = {'NETHER_ENTRY': 'NETHER_RETURN', 'NETHER_RETURN': 'NETHER_ENTRY', 'END_ENTRY': 'END_EXIT', 'END_EXIT': 'END_ENTRY', 'END_GATEWAY': 'END_ENTRY'}


def wait_arrival_fast(route, timeout=40):
    """Like wait_arrival but with no sleep: the arrival cooldown is only 10 ticks."""
    src, dst = SRC_DST[route]
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        dim = nbt('Dimension').strip('"')
        if dim == dst: return dim
        if process.poll() is not None or failed(): raise RuntimeError('server failure: ' + str(failed()))
    return None


def cooldown_card(route, tag):
    """A REAL portal arrival gives the player a 10-tick portal cooldown that is refreshed every tick it stands inside a portal. The player arrives through the
    reverse route and is then put into this route's portal cell inside that window: it must stay (native refusal). A cross-dimension /tp does not set the cooldown."""
    src, dst = SRC_DST[route]
    rev = REVERSE[route]
    rsrc, rdst = SRC_DST[rev]
    assert rdst == src, (route, rev)
    witness = {'reverse': rev}
    if END in (src, rsrc, dst) and route != 'END_GATEWAY' or rev == 'END_EXIT': ensure_credits_seen()
    build_fixture(rev); build_fixture(route)
    if rev == 'END_EXIT': rcon('execute in %s run spawnpoint %s 8 65 8' % (OW, PLAYER))   # the exit arrives inside the End portal cell
    place(rsrc, REST[rev])
    rcon('gamemode survival ' + PLAYER)
    rcon('tag %s add %s' % (PLAYER, tag))
    witness['source'] = observe(tag)
    rcon('execute in %s run tp %s %s' % (rsrc, PLAYER, CELL[rev]))
    arrived = wait_arrival_fast(rev)
    rcon('execute in %s run tp %s %s' % (src, PLAYER, CELL[route]))
    witness['arrivedViaReverse'] = bool(arrived)
    witness['series'] = sample_series(route, 6)
    witness['held'] = observe(tag)
    held = all(row[1] == src for row in witness['series'])
    outcome = 'STAYED' if held else 'TRANSFERRED'
    rcon('tag %s remove %s' % (PLAYER, tag))
    if rev == 'END_EXIT': rcon('execute in %s run spawnpoint %s 8 65 5' % (OW, PLAYER))
    return outcome, witness


def launch(route):
    """(x, y, z, motion) that carries a non-living entity THROUGH the route's portal cell, the way the headless census does."""
    x, y, z = [float(v) for v in CELL[route].split()]
    if route == 'END_GATEWAY': return x, y, z, ''
    if route.startswith('NETHER'): return 0.5, 66.0, -1.5, ',Motion:[0.0d,0.0d,0.2d]'
    # the End portal block only reaches 6/16..12/16 of its cell: the entity must overlap that band while it crosses
    return x - 2.5, y + 0.4, z, ',Motion:[0.2d,0.0d,0.0d]'


def stop_motion(world, selector):
    rcon('execute in %s run data merge entity %s {Motion:[0.0d,0.0d,0.0d]}' % (world, selector))


def inventory_count(snap, item):
    return (snap.get('inventory') or {}).get(item, 0)


def pearl_card(route, tag, override=False):
    """An ender pearl owned by the connected player enters the portal; once it lands in the destination it teleports its owner there."""
    src, dst = SRC_DST[route]
    witness = {}
    if route == 'END_EXIT': ensure_credits_seen()
    build_fixture(route)
    place(src, REST[route])
    rcon('gamemode creative ' + PLAYER)
    rcon('tag %s add %s' % (PLAYER, tag))
    owner = player_uuid_nbt()
    witness['owner'] = owner
    x, y, z, motion = launch(route)
    if override:
        # the pearl rides a pig that itself never uses a portal: a throwable may use one while it is a passenger
        rcon('execute in %s run summon minecraft:pig %s %s %s {Tags:["stage7_mob","%s","k1_pig"],Silent:1b,PortalCooldown:1000000,NoGravity:1b,Age:-24000%s}' % (src, x, y - (0.4 if not route.startswith('NETHER') and route != 'END_GATEWAY' else 0), z, tag, motion.replace('0.2d', '0.4d')))
    rcon('execute in %s run summon minecraft:ender_pearl %s %s %s {Tags:["stage7_mob","%s"],NoGravity:1b,Owner:%s%s}' % (src, x, y, z, tag, owner, motion))
    if override:
        witness['ride'] = rcon('ride @e[tag=%s,type=minecraft:ender_pearl,limit=1] mount @e[tag=k1_pig,limit=1]' % tag)
    witness['source'] = observe(tag)
    t0 = time.monotonic(); outcome = 'STAYED'
    far = route == 'END_GATEWAY'
    while time.monotonic() - t0 < 14:
        if process.poll() is not None or failed(): raise RuntimeError('server failure during pearl card: ' + str(failed()))
        if far: moved = east_count(tag + ',type=minecraft:ender_pearl') >= 1
        else: moved = count_in(dst, tag + ',type=minecraft:ender_pearl') >= 1 and count_in(src, tag + ',type=minecraft:ender_pearl') == 0
        if moved: outcome = 'TRANSFERRED'; break
        time.sleep(.25)
    witness['seconds_to_transfer'] = round(time.monotonic() - t0, 2)
    if outcome == 'TRANSFERRED':
        stop_motion(dst, '@e[tag=%s,type=minecraft:ender_pearl,limit=1]' % tag)
        time.sleep(1)
        witness['arrival'] = observe(tag)
        time.sleep(1.5)
        witness['later'] = observe(tag)
        before = pstate()
        rcon('execute in %s run data merge entity @e[tag=%s,type=minecraft:ender_pearl,limit=1] {NoGravity:0b}' % (dst, tag))
        deadline = time.monotonic() + 15
        landed = None
        while time.monotonic() < deadline:
            pst = pstate()
            if pst['dimension'] == dst and pst['posValues'] and before['posValues'] and (pst['dimension'] != before['dimension'] or
                    sum(abs(a - b) for a, b in zip(pst['posValues'], before['posValues'])) > 2):
                landed = pst; break
            time.sleep(.2)
        witness['teleport'] = landed
        witness['later_before'] = before
        time.sleep(2.5)
        witness['afterTeleport'] = observe(tag)
        witness['teleported'] = landed is not None
    else:
        time.sleep(3)
        witness['settled'] = observe(tag)
        witness['where'] = {w: rcon('execute in %s positioned 0 64 0 run data get entity @e[tag=%s,distance=0..2000,limit=1]' % (w, tag), whole=True)[:600] for w in (OW, NETHER, END)}
    rcon('tag %s remove %s' % (PLAYER, tag))
    rcon('execute in %s run kill @e[tag=%s,type=!minecraft:player]' % (src, tag))
    rcon('execute in %s run kill @e[tag=%s,type=!minecraft:player]' % (dst, tag))
    rcon('gamemode survival ' + PLAYER)
    return outcome, witness


def item_card(route, tag):
    """A stack of 5 diamonds crosses the portal, then the connected player walks onto it and picks it up natively."""
    src, dst = SRC_DST[route]
    witness = {}
    if route == 'END_EXIT': ensure_credits_seen()
    build_fixture(route)
    place(src, REST[route])
    rcon('gamemode survival ' + PLAYER)
    rcon('clear ' + PLAYER)
    rcon('tag %s add %s' % (PLAYER, tag))
    witness['inventoryBefore'] = agent.snapshot().get('inventory')
    x, y, z, motion = launch(route)
    rcon('execute in %s run summon minecraft:item %s %s %s {Tags:["stage7_mob","%s"],NoGravity:1b,Item:{id:"minecraft:diamond",count:5},PickupDelay:32767s%s}' % (src, x, y, z, tag, motion))
    witness['source'] = observe(tag)
    t0 = time.monotonic(); outcome = 'STAYED'
    while time.monotonic() - t0 < 14:
        if process.poll() is not None or failed(): raise RuntimeError('server failure during item card: ' + str(failed()))
        if route == 'END_GATEWAY': moved = east_count(tag) == 1
        else: moved = count_in(dst, tag + ',type=minecraft:item') == 1 and count_in(src, tag + ',type=minecraft:item') == 0
        if moved: outcome = 'TRANSFERRED'; break
        time.sleep(.25)
    witness['seconds_to_transfer'] = round(time.monotonic() - t0, 2)
    if outcome == 'TRANSFERRED':
        stop_motion(dst, '@e[tag=%s,type=minecraft:item,limit=1]' % tag)
        time.sleep(1)
        witness['arrival'] = observe(tag)
        time.sleep(1.5)
        witness['later'] = observe(tag)
        raw = rcon('execute in %s run data get entity @e[tag=%s,type=minecraft:item,limit=1] Pos' % (dst, tag), whole=True)
        found = re.findall(r'-?\d+(?:\.\d+)?(?=d)', raw)
        witness['itemPos'] = found
        rcon('execute in %s run data merge entity @e[tag=%s,type=minecraft:item,limit=1] {PickupDelay:0s}' % (dst, tag))
        if len(found) == 3:
            # the player moves to the item (cross-dimension where the route changes world); the portal's arrival cooldown keeps it from re-entering
            rcon('execute in %s run tp %s %s %s %s' % (dst, PLAYER, found[0], float(found[1]) + .0, found[2]))
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if count_in(dst, tag) == 0 and count_in(src, tag) == 0: break
            time.sleep(.25)
        time.sleep(1.5)
        witness['picked'] = observe(tag)
        witness['diamonds'] = inventory_count(witness['picked']['client'], 'minecraft:diamond')
        witness['serverInventory'] = nbt('Inventory')
    else:
        time.sleep(3)
        witness['settled'] = observe(tag)
        witness['where'] = {w: rcon('execute in %s positioned 0 64 0 run data get entity @e[tag=%s,distance=0..2000,limit=1]' % (w, tag), whole=True)[:600] for w in (OW, NETHER, END)}
    rcon('tag %s remove %s' % (PLAYER, tag))
    rcon('clear ' + PLAYER)
    rcon('execute in %s run kill @e[tag=%s,type=!minecraft:player]' % (src, tag))
    rcon('execute in %s run kill @e[tag=%s,type=!minecraft:player]' % (dst, tag))
    return outcome, witness


def bobber_card(route, tag):
    """The real client casts a fishing rod; the bobber is carried into the portal cell. Natively a bobber can never use a portal (canUsePortal is false)."""
    src, dst = SRC_DST[route]
    witness = {}
    if route == 'END_EXIT': ensure_credits_seen()
    build_fixture(route)
    place(src, REST[route])
    rcon('gamemode survival ' + PLAYER)
    rcon('clear ' + PLAYER)
    rcon('tag %s add %s' % (PLAYER, tag))
    rcon('item replace entity %s weapon.mainhand with minecraft:fishing_rod' % PLAYER)
    agent.call('look', yaw=0, pitch=60)
    agent.call('wait', ticks=10)
    witness['before'] = observe(tag)
    agent.call('press', key='use')
    agent.call('wait', ticks=10)
    bobbers = lambda world: int(re.search(r'has (-?\d+)', (rcon('execute in %s store result score #c hc if entity @e[type=minecraft:fishing_bobber,x=-300,y=-100,z=-300,dx=700,dy=500,dz=700]' % world) and rcon('scoreboard players get #c hc'))).group(1))
    witness['cast'] = {'source': bobbers(src), 'destination': bobbers(dst) if dst != src else None}
    outcome = 'STAYED'
    if witness['cast']['source'] == 1:
        x, y, z = CELL[route].split()
        rcon('execute in %s run tp @e[type=minecraft:fishing_bobber,limit=1] %s %s %s' % (src, x, y, z))
        witness['carried'] = observe(tag)
        t0 = time.monotonic()
        witness['series'] = []
        while time.monotonic() - t0 < 4:     # 80 ticks: far beyond the portal delay of a normal entity
            witness['series'].append([round(time.monotonic() - t0, 2), bobbers(src), bobbers(dst) if dst != src else None])
            time.sleep(.5)
        if any(s[2] for s in witness['series']): outcome = 'TRANSFERRED'
        elif not any(s[1] for s in witness['series']): outcome = 'VANISHED'
        witness['settled'] = observe(tag)
        agent.call('press', key='use')
    else:
        witness['castFailed'] = True
    rcon('tag %s remove %s' % (PLAYER, tag))
    rcon('clear ' + PLAYER)
    rcon('kill @e[type=minecraft:fishing_bobber]')
    return outcome, witness



def run_card(card):
    m = CARD.fullmatch(card)
    assert m, card
    kind, route = m.groups()
    tag = 'k1_%d' % state['index']; state['index'] += 1
    tx0 = None if vanilla else tx_count()
    ev0 = None if vanilla else probe_event_counts()
    if kind == 'player': outcome, witness = player_card(route, tag)
    elif kind == 'credits_unseen' and route == 'END_EXIT': outcome, witness = credits_unseen_card(tag)
    elif kind in PGRAPHS: outcome, witness = graph_card(kind, route, tag)
    elif kind == 'player_cooldown': outcome, witness = cooldown_card(route, tag)
    elif kind == 'ender_pearl': outcome, witness = pearl_card(route, tag)
    elif kind == 'ender_pearl_override': outcome, witness = pearl_card(route, tag, override=True)
    elif kind == 'item': outcome, witness = item_card(route, tag)
    elif kind == 'fishing_bobber': outcome, witness = bobber_card(route, tag)
    else: raise NotImplementedError(card)
    rcon('kill @e[tag=stage7_mob]')
    if not vanilla:
        witness['probeEvents'] = probe_events_since(ev0)
    entry = dict(card=card, outcome=outcome, witness=witness)
    if not vanilla: entry['txRange'] = [tx0, tx_count()]
    members = (witness.get('arrival') or witness.get('source') or {}).get('probe') or []
    entry['details'] = 'UUID=' + members[0]['uuid'] if members else 'client-run'
    result['cards'].append(entry)
    print('CARD', card, outcome, flush=True)


result = {'command': command, 'startUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'candidateSha256': digest, 'cards': []}
process = None
try:
    with console.open('wb') as output:
        process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            if process.poll() is not None or failed(): raise RuntimeError('server bootstrap failed: ' + str(failed()))
            if 'Done (' in logtext() and (vanilla or 'Started Geyser on 127.0.0.1:' + str(ports['bedrock']) in logtext()): break
            time.sleep(.3)
        else: raise RuntimeError('bootstrap timeout')
        for world in [OW, NETHER]:
            assert nether_fixture(world), world
        rcon('scoreboard objectives add hc dummy')
        for rule in ('mob_griefing', 'mob_drops', 'spawn_mobs'):
            rcon('gamerule minecraft:%s false' % rule)
        rcon('setworldspawn 8 65 5')
        for c in ['forceload add -16 -16 16 16', 'forceload add 0 0 24 20']:
            rcon('execute in %s run %s' % (OW, c))
        for c in ['forceload add 90 -10 110 10', 'forceload add 0 0 24 20']:
            rcon('execute in %s run %s' % (END, c))
        clientcmd = [str(work / 'gradlew.bat'), '--no-daemon', '-I', str(work / 'scripts/stage7-closeout-client.gradle'), 'runClientGameTest',
                     '--console=plain', '-PcloseoutControl=' + str(control), '-PcloseoutAddress=127.0.0.1:' + str(ports['java'])]
        result['clientCommand'] = clientcmd
        with clientlog.open('wb') as clientout:
            client = subprocess.Popen(clientcmd, cwd=work, stdout=clientout, stderr=subprocess.STDOUT,
                                      creationflags=subprocess.CREATE_NO_WINDOW | BELOW_NORMAL)
            print('CLIENT', client.pid, flush=True)
            deadline = time.monotonic() + 1500
            while not (control / 'ready').exists():
                if client.poll() is not None: raise RuntimeError('client exited before it connected; see client.log')
                if process.poll() is not None or failed(): raise RuntimeError('server failure while the client connected: ' + str(failed()))
                if time.monotonic() > deadline: raise RuntimeError('client did not connect in 25 minutes')
                time.sleep(1)
            result['playerList'] = rcon('list')
            print('CLIENT READY', result['playerList'], flush=True)
            try:
                if explore: run_explore(explore)
                for card in cards:
                    run_card(card)
                result['harnessPass'] = True
            finally:
                (control / 'stop').write_text('stop')
                try: client.wait(timeout=120)
                except subprocess.TimeoutExpired: client.kill()
        result['clientExitCode'] = client.returncode
        if vanilla:
            result['txPass'] = True
        else:
            for _ in range(60):
                raw = rcon('stage7-tx status'); result['txStatusRaw'] = raw
                tx = json.loads(raw[raw.index('{'):]); result['txStatus'] = tx
                if tx.get('violations', 0) > 0 or tx.get('pending', 0) == 0: break
                time.sleep(.5)
            result['txPass'] = tx.get('violations') == 0 and tx.get('pending') == 0
except BaseException as error:
    result['failure'] = str(error); print('FAILED', error, flush=True)
    import traceback; result['failureTrace'] = traceback.format_exc(); print(result['failureTrace'], flush=True)
finally:
    if client is not None and client.poll() is None: client.kill()
    if process and process.poll() is None:
        try: process.stdin.write(b'save-all\nstop\n'); process.stdin.flush(); result['normalStopRequested'] = True
        except (BrokenPipeError, OSError): pass
        try: process.wait(timeout=90)
        except subprocess.TimeoutExpired: process.terminate(); process.wait(timeout=15); result['forcedOwnServerStop'] = True
    result['serverExitCode'] = None if process is None else process.poll()
    result['logFailure'] = failed(); result['normalSave'] = 'All dimensions are saved' in logtext()
    result['phase'] = 'client-closeout'
    tx_lines = [line for line in logtext().splitlines() if 'STAGE7_PORTAL_TX' in line]
    tx_trace = run / 'client-portal-transactions.log'
    tx_trace.write_text('\n'.join(tx_lines) + ('\n' if tx_lines else ''))
    result['txTracePath'] = str(tx_trace); result['txTraceSha256'] = sha(tx_trace); result['txTraceLineCount'] = len(tx_lines)
    result['sourceWorldChanges'] = [n for n, h in world_hashes.items() if sha(source_world / n) != h]
    result['providerHashes'] = {} if vanilla else {n: sha(server / 'mods' / n) for n in pinned['providers']}
    result['installedCandidateSha256'] = digest if vanilla else sha(server / 'mods' / immutable.name)
    result['testSourceChanged'] = [n for n, h in test_sources.items() if sha(work / n) != h]
    result['configChanges'] = [] if vanilla else [n for n, h in inputs['configHashes'].items() if sha(server / 'config' / n) != h]
    result['pass'] = bool(result.get('harnessPass') and result.get('txPass') and not result.get('failure') and not result['logFailure']
                          and result['normalSave'] and not result.get('forcedOwnServerStop') and not result['sourceWorldChanges']
                          and not result['testSourceChanged'] and not result['configChanges'] and result['installedCandidateSha256'] == digest)
    receipt = {'schema': 'stage7-client-receipt-v1', 'evidence': 'real-client', 'group': 'client-closeout',
               'pass': bool(result['pass']), 'candidateSha256': digest, 'cards': result['cards']}
    (run / 'headless-receipt.json').write_text(json.dumps(receipt, indent=2))
    result['cards'] = [{k: v for k, v in c.items() if k != 'witness'} for c in result['cards']]
    (run / 'headless-result.json').write_text(json.dumps(result, indent=2))
    print('PASS' if result['pass'] else 'FAIL', result.get('failure'), flush=True)
sys.exit(0 if result['pass'] else 1)
