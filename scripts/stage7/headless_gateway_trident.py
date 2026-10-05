"""Headless gateway-cooldown capture for projectiles (derived from headless_effects.py; the effect census itself is unchanged there).

A projectile's native portal cooldown after an End gateway teleport lasts about one tick, so a once-per-card snapshot usually misses it. This variant
summons and snapshots in ONE pipelined RCON write (source at tick 0 in the gateway cell) and then polls snapshots over a single persistent RCON
connection until the entity has arrived, so the arrival record is the first one taken after the teleport tick. Everything after the capture is identical.

Original description: Headless effect census (projectiles and TNT): real installed server, pinned providers, probe observer, no client.

Usage: python scripts/stage7/headless_effects.py <fresh-name> <workers 1|4> <candidate-jar> <cards> [--vanilla] [--routes R1,R2] [--types=a,b]

A card is D1-<kind>-<ROUTE>. The entity is summoned at rest in the source portal cell exactly like an E0 card (NoGravity), the harness
waits for it to arrive, lets it tick for more than twenty ticks (post_arrival_tick evidence), and only THEN summons a cluster of
no-AI pig targets (plus an owner pig, and for a shulker bullet the target pig) around it in the destination. The projectile then
does what native code does: hit a target, explode, release a cloud or orbs, return to its owner. The harness records the state of every
target and the counts of the effect entities after the effect has played out. The same cards run on a Phoenium-free control
(--vanilla); the importer credits an impact effect only when the Phoenium observables equal the control's AND the control actually exhibits
the effect. A TNT card with a near-expiry fuse has its targets placed before the transfer, because the TNT explodes before the harness
could react to its arrival.
Not driven here: a thrower (a player or mob launching the projectile) and a player-owned pearl teleport.
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
types = next((a.partition('=')[2].split(',') for a in sys.argv if a.startswith('--types=')), None)
routes = next((a.partition('=')[2].split(',') for a in sys.argv if a.startswith('--routes=')), ['END_GATEWAY'])
noentry_pairs = set()
vanilla = '--vanilla' in sys.argv   # Phoenium-free native control: empty mods directory, same fixture
ports = dict(java=25697, bedrock=19197, rcon=25797) if vanilla else dict(java=25696, bedrock=19196, rcon=25796)
run = audit / 'installed' / name
server = run / 'server'
java = pathlib.Path(r'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe')

OW, NETHER, END = 'minecraft:overworld', 'minecraft:the_nether', 'minecraft:the_end'
CHEST_BOAT_ITEMS = ',Items:[{Slot:0b,id:"minecraft:diamond",count:7},{Slot:26b,id:"minecraft:sugar_cane",count:3}]'
CHEST_MINECART_ITEMS = ',Items:[{Slot:0b,id:"minecraft:diamond",count:7},{Slot:26b,id:"minecraft:iron_ingot",count:13}]'


def type_nbt(etype):
    if etype == 'chest_minecart': return CHEST_MINECART_ITEMS
    if 'chest_boat' in etype or 'chest_raft' in etype: return CHEST_BOAT_ITEMS
    return NBT.get(etype, '')


# NoAI keeps wandering/flying mobs in the portal cell so the outcome is decided by the portal, not by a random walk
# (portal handling and block effects live in Entity.baseTick / LivingEntity.aiStep, not in goal selection).
# Types whose native behaviour depends on gravity (ArmorStand.hasPhysics() = !marker && !noGravity -> noPhysics).
GRAVITY_TYPES = {'armor_stand'}
NBT = {
    'area_effect_cloud': ',Duration:1200,Radius:2.0f,Particle:{type:"minecraft:effect",color:16711680}',
    'experience_orb': ',Value:5s',
    'firework_rocket': ',LifeTime:400,FireworksItem:{id:"minecraft:firework_rocket",count:1,components:{"minecraft:fireworks":{flight_duration:3}}}',
    'evoker_fangs': ',Warmup:0', 'end_crystal': ',ShowBottom:0b',
    'ominous_item_spawner': ',item:{id:"minecraft:arrow",count:1},spawn_item_after_ticks:400L',
    'item': ',Item:{id:"minecraft:diamond",count:1},PickupDelay:32767',
    'falling_block': ',BlockState:{Name:"minecraft:sand"},Time:1', 'tnt': ',fuse:400s',
}


# Kind -> (entity id, extra NBT, does it carry an owner, hits a target by itself immediately). Every kind is summoned at rest (NoGravity) in the portal cell.
FIREWORK = ',LifeTime:400,FireworksItem:{id:"minecraft:firework_rocket",count:1,components:{"minecraft:fireworks":{flight_duration:3,explosions:[{shape:"large_ball",colors:[I;16711680]}]}}}'
KINDS = {
    'arrow': ('arrow', '', True),
    'spectral_arrow': ('spectral_arrow', '', True),
    'trident': ('trident', ',item:{id:"minecraft:trident",count:1,components:{"minecraft:enchantments":{"minecraft:loyalty":3}}}', True),
    'fireball': ('fireball', '', True),
    'small_fireball': ('small_fireball', '', True),
    'dragon_fireball': ('dragon_fireball', '', True),
    'wither_skull': ('wither_skull', '', True),
    'wind_charge': ('wind_charge', '', True),
    'breeze_wind_charge': ('breeze_wind_charge', '', True),
    'shulker_bullet': ('shulker_bullet', '', True),
    'egg': ('egg', '', True),
    'snowball': ('snowball', '', True),
    'splash_potion': ('splash_potion', ',Item:{id:"minecraft:splash_potion",count:1,components:{"minecraft:potion_contents":{potion:"minecraft:poison"}}}', True),
    'lingering_potion': ('lingering_potion', ',Item:{id:"minecraft:lingering_potion",count:1,components:{"minecraft:potion_contents":{potion:"minecraft:poison"}}}', True),
    'experience_bottle': ('experience_bottle', '', True),
    'firework_rocket': ('firework_rocket', FIREWORK, False),
    'tnt': ('tnt', ',fuse:160s', False),
    'tnt_near_expiry': ('tnt', ',fuse:14s', False),
}
# Kinds whose effect must be armed immediately after arrival (the rocket accelerates upward) and kinds with targets placed before the transfer.
QUICK = {'firework_rocket'}
PREPLACED = {'tnt_near_expiry'}
# Where the entity arrives (E0 evidence), per route, so a pre-placed cluster is under it. Verified against nbtArrival of every card.
ARRIVAL = {'NETHER_ENTRY': (0.5, 65.0, 0.5), 'NETHER_RETURN': (0.5, 65.0, 0.5), 'END_ENTRY': (100.5, 50.0, 0.5),
           'END_EXIT': (8.5, 71.0, 5.5), 'END_GATEWAY': (22.5, 66.0, 12.5)}
# Cluster offsets (dx, dz): the nether portal frame is in the x axis, so the cluster runs along z there.
# Targets stand in a line AHEAD of the projectile (distance in blocks along its flight direction), the owner pig behind it.
# The nether portal frame is in the x axis, so the flight there is along +z; in the End it is along +x.
AHEAD = {'hit': [2.0, 4.0, 6.0, 8.0, 10.0], 'blast': [3.0, 5.0, 7.0, 9.0, 10.5]}
DIRECTION = {'NETHER': (0.0, 1.0), 'END': (1.0, 0.0)}
BLAST_KINDS = {'tnt', 'tnt_near_expiry'}
# Zero velocity after every portal: the harness gives a thrown kind its flight back (a projectile at rest never reaches a target).
# The bullet homes on its Target, a firework rocket accelerates by itself and TNT explodes on its fuse.
FLIGHT = 0.5
SELF_PROPELLED = {'shulker_bullet', 'firework_rocket', 'tnt', 'tnt_near_expiry'}
WATCH = ['area_effect_cloud', 'experience_orb', 'chicken']
TARGET_ID = 9100


def uuid_nbt(card_index, role):
    return '[I;%d,%d,%d,%d]' % (TARGET_ID, card_index, 0, role)


def pig(world, x, y, z, i, card_index, role=1):
    rcon('execute in %s run summon minecraft:pig %.2f %.2f %.2f {Tags:["stage7_target","stage7_t%d"],NoAI:1b,NoGravity:1b,Silent:1b,PortalCooldown:1000000,UUID:%s}'
         % (world, x, y, z, i, uuid_nbt(card_index, role)))


def owner_pig(world, x, y, z, card_index):
    rcon('execute in %s run summon minecraft:pig %.2f %.2f %.2f {Tags:["stage7_target","stage7_owner"],NoAI:1b,NoGravity:1b,Silent:1b,PortalCooldown:1000000,UUID:%s}'
         % (world, x, y, z, uuid_nbt(card_index, 2)))


def place_cluster(world, family, pos, card_index, owner, spacing='hit'):
    """Targets in a line ahead of a position (index 0 is the nearest and the shulker bullet's Target) and the owner pig behind it."""
    dx, dz = DIRECTION[family]
    for i, ahead in enumerate(AHEAD[spacing]):
        pig(world, pos[0] + dx * ahead, pos[1], pos[2] + dz * ahead, i, card_index, role=1 if i == 0 else 10 + i)
    if owner:
        owner_pig(world, pos[0] - dx * 4.5, pos[1], pos[2] - dz * 4.5, card_index)


def parse_pos(text):
    found = re.search(r'Pos: \[(-?[0-9.]+)d, (-?[0-9.]+)d, (-?[0-9.]+)d\]', text)
    return tuple(float(v) for v in found.groups()) if found else None


def effect_ids(text):
    start = text.find('active_effects: [')
    if start < 0: return []
    depth, end = 0, start + len('active_effects: ')
    for index in range(end, len(text)):
        depth += {'[': 1, ']': -1}.get(text[index], 0)
        if depth == 0: end = index; break
    return sorted(set(re.findall(r'id: "minecraft:([a-z_]+)"', text[start:end + 1])))


def target_state(world, tag):
    text = rcon('execute in %s positioned 0 64 0 run data get entity @e[tag=%s,distance=0..2000,limit=1]' % (world, tag), whole=True).strip()
    if 'has the following entity data' not in text: return {'alive': False}
    health = re.search(r'Health: (-?[0-9.]+)f', text)
    fire = re.search(r'Fire: (-?\d+)s', text)
    pos = parse_pos(text)
    hurt = re.search(r'(?:HurtByTimestamp|last_hurt_by_mob_timestamp): (-?\d+)', text)
    return {'alive': True, 'hurtBy': bool(hurt and int(hurt.group(1)) > 0), 'health': float(health.group(1)) if health else None, 'fire': int(fire.group(1)) if fire else 0,
            'effects': effect_ids(text), 'pos': [round(v, 1) for v in pos] if pos else None}


def count_entities(world, selector):
    rcon('execute in %s positioned 0 64 0 store result score #c hc if entity %s' % (world, selector))
    found = re.search(r'has (-?\d+)', rcon('scoreboard players get #c hc'))
    return int(found.group(1)) if found else 0


def observe(worlds, etype, count):
    """Targets, owner, effect-entity counts and the projectile itself, per dimension."""
    result = {}
    for world, label in worlds:
        entry = {'targets': {'t%d' % i: target_state(world, 'stage7_t%d' % i) for i in range(count)},
                 'owner': target_state(world, 'stage7_owner'),
                 'counts': {kind: count_entities(world, '@e[type=%s,distance=0..2000]' % kind) for kind in WATCH + [etype]}}
        result[label] = entry
    return result


def count_in(world, tag, gateway_far=False):
    """Number of tagged entities in a world, via a scoreboard (an `execute if entity` reply only says whether any matched)."""
    if gateway_far:
        selector = '@e[tag=%s,x=19,y=0,z=-100,dx=200,dy=300,dz=300]' % tag
        rcon('execute in %s store result score #c hc if entity %s' % (world, selector))
    else:
        rcon('execute in %s positioned 0 64 0 store result score #c hc if entity @e[tag=%s,distance=0..2000]' % (world, tag))
    found = re.search(r'has (-?\d+)', rcon('scoreboard players get #c hc'))
    return int(found.group(1)) if found else 0


def nbt_of(world, tag):
    return rcon('execute in %s positioned 0 64 0 run data get entity @e[tag=%s,distance=0..2000,limit=1]' % (world, tag), whole=True).strip()


class HotRcon:
    """One persistent RCON connection; send() writes any number of commands in one TCP write and reads that many replies."""
    def __init__(self):
        self.s = socket.create_connection(('127.0.0.1', ports['rcon']), timeout=3); self.s.settimeout(60); self.n = 10
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

    def send(self, commands):
        for command in commands:
            self.n += 1; self._send(self.n, 2, command)
        return [self._read_whole() for _ in commands]

    def close(self): self.s.close()


def body_of(raw): return json.loads(raw[raw.index('{'):])


def snapshot_body():
    raw = rcon('stage7-probe snapshot', whole=True)
    return json.loads(raw[raw.index('{'):])


def loose_items(body):
    totals = {}
    for e in body['entities']:
        if e.get('type') == 'minecraft:item':
            totals[e.get('item', '?')] = totals.get(e.get('item', '?'), 0) + e.get('count', 0)
    return totals


def snapshot_members(tag, body=None):
    """Raw probe entities carrying this card's tag, from every dimension (Phoenium runs only)."""
    if vanilla: return None
    body = body or snapshot_body()
    return [e for e in body['entities'] if tag in e.get('tags', '')]


def gateway_events(members, body):
    ids = {m.get('uuid', '').lower() for m in members}
    return {k: [e for e in body.get(key, []) if e.get('uuid', '').lower() in ids]
            for k, key in (('contact', 'gatewayContactEvents'), ('ticket', 'gatewayTicketEvents'), ('tracking', 'gatewayTrackingEvents'))}


def sha(p):
    with p.open('rb') as s:
        return hashlib.file_digest(s, 'sha256').hexdigest().upper()


def rcon(command, whole=False):
    with socket.create_connection(('127.0.0.1', ports['rcon']), timeout=3) as s:
        s.settimeout(60)

        def packet(i, t, body):
            b = body.encode(); s.sendall(struct.pack('<iii', len(b) + 10, i, t) + b + b'\0\0')

        def read():
            def n(count):
                b = b''
                while len(b) < count:
                    part = s.recv(count - len(b))
                    if not part: raise EOFError('RCON closed')
                    b += part
                return b
            length = struct.unpack('<i', n(4))[0]; b = n(length)
            if struct.unpack('<i', b[:4])[0] < 0: raise RuntimeError('RCON authentication failed')
            return b[8:-2].decode(errors='replace')
        def read_whole():
            # The server splits long replies into 4096-character packets; keep reading while a full chunk was just received.
            # (The packet-length cap on the server side rejects any sentinel request, so no end marker can be used.)
            text = read()
            while len(text.encode()) >= 4096 and len(text) % 4096 == 0:
                s.settimeout(1.0)
                try: more = read()
                except (socket.timeout, TimeoutError): break
                finally: s.settimeout(60)
                text += more
            return text
        packet(1, 3, 'phoenium-stage7-local-test'); read(); packet(2, 2, command)
        return read_whole() if whole else read()


run.mkdir(parents=True, exist_ok=False); server.mkdir()
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
target_world = server / ('STAGE7-HEADLESS-W' + str(workers))
shutil.copytree(source_world, target_world)
props = template.joinpath('server.properties').read_text().splitlines()
over = {'server-port': str(ports['java']), 'level-name': target_world.name, 'enable-rcon': 'true', 'rcon.port': str(ports['rcon']),
        'rcon.password': 'phoenium-stage7-local-test', 'motd': 'Phoenium Stage7 headless effects',
        # With nobody online 26.2 pauses the tick loop after 60 s: entities would stop ticking and nothing could transfer.
        'pause-when-empty-seconds': '-1'}
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
def selected(card):
    kind, route = re.fullmatch(r'D1-([a-z_]+)-([A-Z_]+)', card).groups()
    return (not types or kind in types) and route in routes
card_list = [c for c in census.split(',') if selected(c)]
test_sources = {str(q.relative_to(work)): sha(q) for base in [work / 'src/clientGameTest', work / 'src/test/java/dev/phoenium/stage7repair', work / 'src/stage7RepairHarness']
                for q in base.rglob('*') if q.is_file()}
test_sources[str(script_path.relative_to(work))] = sha(script_path)
inputs = dict(noentryFrom=next((a.partition('=')[2] for a in sys.argv if a.startswith('--noentry-from=')), None), noentryPairs=sorted('%s-%s' % pair for pair in noentry_pairs), candidate=str(immutable), candidateSha256=digest, probeSha256=None if vanilla else sha(probe), workers=workers, vanilla=vanilla,
              group='headless-gateway-trident', cards=card_list, census=census, routes=routes, headless=True, evidence='server-headless',
              censusScriptSha256=sha(script_path), testSourceHashes=test_sources,
              head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=work, text=True).strip(),
              sourceStatus=subprocess.check_output(['git', 'status', '--short'], cwd=work, text=True),
              providers=pinned['providers'], configHashes={} if vanilla else {n: sha(server / 'config' / n) for n in pinned['configFilesSha256']})
if vanilla:
    # Phoenium-free native control: empty mods directory, same launcher/libraries/world fixture.
    inputs.update(mods=[], sourceJarSha256=census_v2['sourceJarSha256'], mappedBinarySha256=census_v2['mappedBinarySha256'],
                  vanillaServerJarSha256=sha(server / 'versions/26.2/server-26.2.jar'))
(run / 'inputs.json').write_text(json.dumps(inputs, indent=2))

console = run / 'first-server-console.log'
command = [str(java), '-Xmx2G', '-Dphoenium.test.diagnostics=true', '-Dphoenium.gameplay.workers=' + str(workers),
           '-Djava.net.useSystemProxies=false', '-Dminecraft.api.session.host=http://127.0.0.1:9',
           '-Dminecraft.api.services.host=http://127.0.0.1:9', '-jar', 'fabric-server-launch.jar', 'nogui']
FAIL_MARKERS = ['scheduler=FAILED', 'Encountered an unexpected exception', 'STAGE7_OBSERVER_FAILED', 'STAGE7_PORTAL_TX_VIOLATION',
                'Mixin apply for mod', 'Error executing task on Server', 'Failed to handle packet', 'Exception ticking',
                'Exception stopping the server']


def logtext(): return console.read_text(errors='replace') if console.exists() else ''
def failed(): t = logtext(); return next((m for m in FAIL_MARKERS if m in t), None)


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
        def nether_fixture(world):
            # Rebuilt before EVERY Nether-route card: a block-destroying entity (ender dragon, wither, end crystal, TNT...)
            # in an earlier card would otherwise leave later cards without a portal and report a bogus STAYED.
            prefix = 'execute in ' + world + ' run '
            for c in ['forceload add -16 -16 16 16', 'fill -12 64 -12 12 64 12 minecraft:sea_lantern',
                      'fill -12 65 -12 12 76 12 minecraft:air', 'fill -12 76 -12 12 76 12 minecraft:glass',
                      'fill -12 65 -12 -12 75 12 minecraft:glass', 'fill 12 65 -12 12 75 12 minecraft:glass',
                      'fill -12 65 -12 12 75 -12 minecraft:glass', 'fill -12 65 12 12 75 12 minecraft:glass',
                      'fill -2 64 0 2 69 0 minecraft:obsidian', 'fill -1 65 0 1 68 0 minecraft:air',
                      'setblock -1 65 0 minecraft:fire', 'fill -2 65 -10 2 69 -10 minecraft:stone']:
                rcon(prefix + c)
            return 'Test passed' in rcon(prefix + 'execute if block 0 66 0 minecraft:nether_portal')
        for world in [OW, NETHER]:
            assert nether_fixture(world), world
        rcon('scoreboard objectives add hc dummy')
        rcon('gamerule minecraft:mob_griefing false')
        # Killing a wither drops a nether star that then sits in the portal; the observer cannot close a capture whose entity
        # is removed externally, so no card may leave loot behind.
        rcon('gamerule minecraft:mob_drops false')
        rcon('setworldspawn 8 65 5')
        rcon('gamerule minecraft:spawn_mobs false')
        for dbg in filter(None, os.environ.get('HC_DEBUG_CMDS', '').split(';;')):
            print('DEBUG', dbg, '=>', rcon(dbg), flush=True)
        end_ready = set()
        for c in ['forceload add -16 -16 16 16', 'forceload add 0 0 24 20']:
            rcon('execute in %s run %s' % (OW, c))
        for c in ['forceload add 90 -10 110 10', 'forceload add 0 0 24 20']:
            rcon('execute in %s run %s' % (END, c))
        for card in census.split(','):
            m = re.fullmatch(r'D1-([a-z_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)', card)
            assert m, card
            kind, route = m.groups()
            if types and kind not in types: continue
            if route not in routes: continue
            etype, extra, has_owner = KINDS[kind]
            src, dst = {'NETHER_ENTRY': (OW, NETHER), 'NETHER_RETURN': (NETHER, OW), 'END_ENTRY': (OW, END), 'END_EXIT': (END, OW), 'END_GATEWAY': (END, END)}[route]
            family = 'NETHER' if route.startswith('NETHER') else 'END'
            label = {OW: 'overworld', NETHER: 'nether', END: 'end'}
            index = len(result['cards'])
            tag = 'stage7_d_%d' % index
            at = '0.5 65 0.5'
            fixture_ok = True
            if route.startswith('NETHER'):
                for world in (OW, NETHER):
                    rebuilt = nether_fixture(world)
                    if world == src: fixture_ok = rebuilt
            else:
                end_ready.discard((src, 65)); end_ready.discard((src, 70)); end_ready.discard((END, 'gateway'))
            if route == 'END_GATEWAY':
                for c in ['forceload add 0 0 24 20', 'fill 8 64 4 24 64 16 minecraft:end_stone', 'fill 8 65 4 24 73 16 minecraft:air',
                          'setblock 12 66 8 minecraft:end_gateway',
                          'data merge block 12 66 8 {exit_portal:[I;22,66,12],ExactTeleport:1b,Age:200L}']:
                    rcon('execute in ' + END + ' run ' + c)
                at = '12.5 66 8.5'
            elif route.startswith('END'):
                y = 65 if route == 'END_ENTRY' else 70
                for c in ['forceload add 0 0 15 15', 'fill 4 %d 4 12 %d 12 minecraft:obsidian' % (y - 1, y - 1),
                          'fill 4 %d 4 12 %d 12 minecraft:air' % (y, y + 6), 'setblock 8 %d 8 minecraft:end_portal' % y]:
                    rcon('execute in ' + src + ' run ' + c)
                at = '8.5 %d.25 8.5' % y
            if route == 'END_EXIT':   # the overworld terrain at the world spawn is unknown: give the arrival area a known floor and room
                for c in ['forceload add 0 0 15 15', 'fill 0 70 0 16 70 12 minecraft:stone', 'fill 0 71 0 16 90 12 minecraft:air']:
                    rcon('execute in %s run %s' % (OW, c))
            # keep every dimension clear of leftovers from the previous card
            for world in (OW, NETHER, END):
                for sel in ('item', 'falling_block', 'area_effect_cloud', 'experience_orb', 'chicken', 'arrow', 'spectral_arrow', 'trident', 'tnt'):
                    rcon('execute in %s run kill @e[type=%s]' % (world, sel))
                rcon('execute in %s run kill @e[tag=stage7_target]' % world)
            count = len(AHEAD['hit'])
            witness = {'kind': kind, 'family': family}
            worlds = [(src, label[src])] if src == dst else [(src, label[src]), (dst, label[dst])]
            if kind in PREPLACED and fixture_ok:
                place_cluster(dst, family, ARRIVAL[route], index, False, 'blast')
            owner_nbt = ',Owner:' + uuid_nbt(index, 2) if has_owner else ''
            target_nbt = ',Target:' + uuid_nbt(index, 1) if kind == 'shulker_bullet' else ''
            if has_owner and fixture_ok:   # the owner exists only in the destination: the link must survive the transfer and resolve there
                pass
            summon_command = ('execute in %s run summon minecraft:%s %s {Tags:["stage7_mob","%s"%s],NoGravity:1b,NoAI:1b%s%s%s}'
                              % (src, etype, at, tag, ',"stage7_gateway_pig"' if route == 'END_GATEWAY' else '', extra, owner_nbt, target_nbt))
            hot_source = hot_arrival = None
            if fixture_ok and not vanilla:
                hot = HotRcon()
                try:
                    summon, first_raw = hot.send([summon_command, 'stage7-probe snapshot'])
                    hot_source = body_of(first_raw)
                    t_hot = time.monotonic()
                    while time.monotonic() - t_hot < 8:
                        body = body_of(hot.send(['stage7-probe snapshot'])[0])
                        member = next(iter(snapshot_members(tag, body) or []), None)
                        if member is not None and member.get('x', 0) > 18:
                            hot_arrival = body; break
                finally: hot.close()
            else:
                summon = rcon(summon_command)
            outcome = 'GONE' if fixture_ok else 'FIXTURE_INVALID'; where = 'NONE'
            if fixture_ok:
                witness['nbtSource'] = nbt_of(src, tag)
                source_body = hot_source if not vanilla else None
                witness['source'] = snapshot_members(tag, source_body)
            t0 = time.monotonic()
            while fixture_ok and time.monotonic() - t0 < 8:
                if process.poll() is not None or failed(): raise RuntimeError('server failure during %s: %s' % (card, failed()))
                if route == 'END_GATEWAY':
                    pos = rcon('execute in %s positioned 0 64 0 run data get entity @e[tag=%s,distance=0..2000,limit=1] Pos[0]' % (END, tag))
                    got = re.search(r'(-?[0-9.]+)d$', pos.strip())
                    if got and float(got.group(1)) > 18: outcome, where = 'TRANSFERRED', END; break
                    if got: outcome, where = 'STAYED', END
                    time.sleep(.1); continue
                if 'Test passed' in rcon('execute in %s positioned 0 64 0 if entity @e[tag=%s,distance=0..2000]' % (dst, tag)):
                    outcome, where = 'TRANSFERRED', dst; break
                if 'Test passed' in rcon('execute in %s positioned 0 64 0 if entity @e[tag=%s,distance=0..2000]' % (src, tag)):
                    outcome, where = 'STAYED', src
                time.sleep(.1)
            if outcome == 'TRANSFERRED' or kind in PREPLACED:
                if where != 'NONE':
                    witness['nbtArrival'] = nbt_of(where, tag)
                    if not vanilla:
                        arrival_body = hot_arrival or snapshot_body()
                        witness['arrival'] = snapshot_members(tag, arrival_body)
                        if route == 'END_GATEWAY': witness['gateway'] = gateway_events(witness['arrival'], arrival_body)
            if outcome == 'TRANSFERRED' and kind not in QUICK and kind not in PREPLACED and not vanilla:
                first = next((e['ticks'] for e in witness['arrival'] if e.get('world') == where), None)
                deadline_later = time.monotonic() + 10
                while time.monotonic() < deadline_later:
                    later_body = snapshot_body()
                    witness['later'] = snapshot_members(tag, later_body)
                    now = next((e['ticks'] for e in witness['later'] if e.get('world') == where), None)
                    if first is not None and now is not None and now > first + 20: break
                    time.sleep(.1)
                witness['nbtLater'] = nbt_of(where, tag)
            elif outcome == 'TRANSFERRED' and kind not in QUICK and kind not in PREPLACED:
                time.sleep(2)   # vanilla control: the same dwell, without a probe
                witness['nbtLater'] = nbt_of(where, tag)
            if outcome == 'TRANSFERRED' and kind not in PREPLACED:
                # Arm the effect where the entity is NOW.
                pos = parse_pos(witness.get('nbtLater') or witness['nbtArrival'])
                witness['armedAt'] = list(pos) if pos else None
                if pos:
                    place_cluster(dst, family, pos, index, has_owner, 'blast' if kind in BLAST_KINDS else 'hit')
                    if kind not in SELF_PROPELLED:
                        dx, dz = DIRECTION[family]
                        rcon('execute in %s positioned 0 64 0 run data merge entity @e[tag=%s,distance=0..2000,limit=1] {Motion:[%.2fd,0.0d,%.2fd]}'
                             % (dst, tag, FLIGHT * dx, FLIGHT * dz))
            if fixture_ok and outcome == 'TRANSFERRED':
                time.sleep(7)   # let the native effect (hit, explosion, cloud, orbs, return) play out
                witness['effects'] = observe(worlds, etype, count)
                witness['effectsSettledTicks'] = 140
                for world, name in worlds:
                    witness['effects'][name]['projectile'] = {'alive': count_entities(world, '@e[tag=%s,distance=0..2000]' % tag) > 0}
                    if witness['effects'][name]['projectile']['alive']:
                        text = nbt_of(world, tag)
                        witness['effects'][name]['projectile']['pos'] = list(parse_pos(text) or ())
            result['cards'].append(dict(card=card, outcome=outcome, world=where, summon=summon.strip()[:80], witness=witness))
            for w in (OW, NETHER, END):
                rcon('execute in %s run kill @e[tag=stage7_mob]' % w)
                rcon('execute in %s run kill @e[tag=stage7_target]' % w)
            if len(result['cards']) % 20 == 0: print('PROGRESS', len(result['cards']), flush=True)
        if vanilla:
            result['txPass'] = True
        else:
            for _ in range(60):
                raw = rcon('stage7-tx status'); result['txStatusRaw'] = raw
                tx = json.loads(raw[raw.index('{'):]); result['txStatus'] = tx
                if tx.get('violations', 0) > 0 or tx.get('pending', 0) == 0: break
                time.sleep(.5)
            result['txPass'] = tx.get('violations') == 0 and tx.get('pending') == 0
        result['harnessPass'] = True
except BaseException as error:
    result['failure'] = str(error); print('FAILED', error, flush=True)
    import traceback; result['failureTrace'] = traceback.format_exc(); print(result['failureTrace'], flush=True)
finally:
    if process and process.poll() is None:
        try: process.stdin.write(b'save-all\nstop\n'); process.stdin.flush(); result['normalStopRequested'] = True
        except (BrokenPipeError, OSError): pass
        try: process.wait(timeout=90)
        except subprocess.TimeoutExpired: process.terminate(); process.wait(timeout=15); result['forcedOwnServerStop'] = True
    result['serverExitCode'] = None if process is None else process.poll()
    result['logFailure'] = failed(); result['normalSave'] = 'All dimensions are saved' in logtext()
    result['phase'] = 'headless'
    tx_lines = [line for line in logtext().splitlines() if 'STAGE7_PORTAL_TX' in line]
    tx_trace = run / 'headless-portal-transactions.log'
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
    receipt = {'schema': 'stage7-headless-receipt-v1', 'evidence': 'server-headless', 'group': 'headless-gateway-trident',
               'pass': bool(result['pass']), 'candidateSha256': digest, 'cards': result['cards']}
    (run / 'headless-receipt.json').write_text(json.dumps(receipt, indent=2))
    result['cards'] = [{k: v for k, v in c.items() if k != 'witness'} for c in result['cards']]
    (run / 'headless-result.json').write_text(json.dumps(result, indent=2))
    (run / 'first-result.json').write_text(json.dumps(result, indent=2))   # compare_census.py compatibility
    summary = {}
    for c in result['cards']: summary.setdefault(c['outcome'], []).append(c['card'])
    print(json.dumps({k: (v if len(v) < 40 else str(len(v)) + ' cards') for k, v in summary.items()}, indent=1))
    print('PASS' if result['pass'] else 'FAIL', result.get('failure'), flush=True)
sys.exit(0 if result['pass'] else 1)
