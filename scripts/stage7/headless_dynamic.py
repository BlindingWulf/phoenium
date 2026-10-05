"""Headless dynamic-family census (falling blocks first): real installed server, pinned providers, probe observer, no client.

Usage: python scripts/stage7/headless_dynamic.py <fresh-name> <workers 1|4> <candidate-jar> <cards> [--vanilla] [--routes R1,R2]

A card is D0-falling-<variant>-<ROUTE>. A falling-block entity (variant = carried block; "block" = an unnatural emerald block,
"landing_damage" = a hurting gold block (an anvil would be chipped at random) with pig targets in the destination) is launched at the source portal, the harness lets it
transfer AND land, and records OBSERVABLES: before and after the card, per dimension and named region, how many blocks of
the exact carried state exist (a barrier swap, because a no-op fill counts nothing), the loose item stacks, the leftover falling
blocks and the target health. The same cards run on a Phoenium-free control (--vanilla); the importer credits native landing
only when the Phoenium observables equal the control's AND the control actually exhibits the behaviour.
Not driven here: the conversion of a placed block into an entity (vanilla FallingBlock code, before any portal is involved).
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
routes = next((a.partition('=')[2].split(',') for a in sys.argv if a.startswith('--routes=')), ['NETHER_ENTRY', 'NETHER_RETURN', 'END_ENTRY', 'END_EXIT', 'END_GATEWAY'])
noentry_pairs = set()
vanilla = '--vanilla' in sys.argv   # Phoenium-free native control: empty mods directory, same fixture
ports = dict(java=25691, bedrock=19191, rcon=25791) if vanilla else dict(java=25690, bedrock=19190, rcon=25790)
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


# Launch point and motion per route: the portal cell is the same as in the other headless scripts.
LAUNCH = {
    'NETHER_ENTRY': ('0.5 66.0 -1.5', ',Motion:[0.0d,0.0d,0.2d]'),
    'NETHER_RETURN': ('0.5 66.0 -1.5', ',Motion:[0.0d,0.0d,0.2d]'),
    'END_ENTRY': ('8.5 70.0 8.5', ''),
    'END_EXIT': ('8.5 75.0 8.5', ''),
    'END_GATEWAY': ('12.5 72.0 8.5', ''),
}
# Named regions (x1 y1 z1 x2 y2 z2) scanned for the carried block in each dimension, before and after a card.
REGIONS = {
    OW: {'main': '-12 60 -12 12 90 12'},
    NETHER: {'main': '-12 60 -12 12 90 12'},
    END: {'platform': '90 40 -10 110 60 10',       # vanilla End spawn platform
          'fixture': '0 60 0 19 90 20',            # around the End portals and the source side of the gateway
          'gateway_exit': '20 60 10 24 90 14'},    # below the ExactTeleport exit of the gateway
}
# Non-default block-state properties, so a landed block proves the whole carried state, not only the block type.
STATE_PROPS = {'anvil': {'facing': 'east'}, 'chipped_anvil': {'facing': 'east'}, 'damaged_anvil': {'facing': 'east'},
               'suspicious_sand': {'dusted': '2'}, 'suspicious_gravel': {'dusted': '2'}}
# Variant -> (carried block, extra falling-block NBT). "block" and "landing_damage" are the two ledger variants that are not a block id.
SPECIAL = {'block': ('emerald_block', ''),
           'landing_damage': ('gold_block', ',FallHurtAmount:2.0f,FallHurtMax:40,HurtEntities:1b,fall_distance:2.0d')}
# Pig targets (NoAI, NoGravity) in the destination of a landing_damage card: a 3x3 grid around the expected landing cell.
TARGET_GRID = {'NETHER_ENTRY': (0, 65, 0), 'NETHER_RETURN': (0, 65, 0), 'END_ENTRY': (100, 49, 0),
               'END_EXIT': (8, 71, 5), 'END_GATEWAY': (22, 65, 12)}


def state_spec(block):
    props = STATE_PROPS.get(block)
    if not props: return 'minecraft:' + block, '{Name:"minecraft:%s"}' % block
    return ('minecraft:%s[%s]' % (block, ','.join('%s=%s' % kv for kv in props.items())),
            '{Name:"minecraft:%s",Properties:{%s}}' % (block, ','.join('%s:"%s"' % kv for kv in props.items())))


def count_blocks(world, spec):
    """Blocks of one exact state per named region: swap them for barriers (a fill only counts CHANGED blocks), then swap back."""
    result = {}
    for label, region in REGIONS[world].items():
        text = rcon('execute in %s run fill %s minecraft:barrier replace %s' % (world, region, spec))
        found = re.search(r'filled (\d+) block', text)
        loaded = 'not loaded' not in text.lower() and 'outside' not in text.lower()
        count = int(found.group(1)) if found else 0
        if found: rcon('execute in %s run fill %s %s replace minecraft:barrier' % (world, region, spec))
        result[label] = {'blocks': count, 'loaded': loaded}
    return result


def count_entities(world, selector):
    rcon('execute in %s positioned 0 64 0 store result score #c hc if entity %s' % (world, selector))
    found = re.search(r'has (-?\d+)', rcon('scoreboard players get #c hc'))
    return int(found.group(1)) if found else 0


def item_ids(world):
    text = rcon('execute in %s positioned 0 64 0 run data get entity @e[type=item,distance=0..2000]' % world, whole=True)
    return sorted(re.findall(r'id: "([^"]+)"', text))


def observables(spec, targets, item):
    result = {}
    for world, label in ((OW, 'overworld'), (NETHER, 'nether'), (END, 'end')):
        entry = {'regions': count_blocks(world, spec),
                 'items': count_entities(world, '@e[type=item,distance=0..2000]'),
                 'itemIds': item_ids(world),
                 'carriedItems': count_entities(world, '@e[type=item,distance=0..2000,nbt={Item:{id:"minecraft:%s"}}]' % item),
                 'fallingBlocks': count_entities(world, '@e[type=falling_block,distance=0..2000]')}
        if targets:
            entry['targets'] = count_entities(world, '@e[tag=stage7_target,distance=0..2000]')
            entry['targetsUndamaged'] = count_entities(world, '@e[tag=stage7_target,distance=0..2000,nbt={Health:10.0f}]')
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
        'rcon.password': 'phoenium-stage7-local-test', 'motd': 'Phoenium Stage7 headless dynamic',
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
    kind, route = re.fullmatch(r'D0-([a-z0-9_]+-[a-z0-9_]+)-([A-Z_]+)', card).groups()
    return (not types or kind in types) and route in routes
card_list = [c for c in census.split(',') if selected(c)]
test_sources = {str(q.relative_to(work)): sha(q) for base in [work / 'src/clientGameTest', work / 'src/test/java/dev/phoenium/stage7repair', work / 'src/stage7RepairHarness']
                for q in base.rglob('*') if q.is_file()}
test_sources[str(script_path.relative_to(work))] = sha(script_path)
inputs = dict(noentryFrom=next((a.partition('=')[2] for a in sys.argv if a.startswith('--noentry-from=')), None), noentryPairs=sorted('%s-%s' % pair for pair in noentry_pairs), candidate=str(immutable), candidateSha256=digest, probeSha256=None if vanilla else sha(probe), workers=workers, vanilla=vanilla,
              group='headless-dynamic', cards=card_list, census=census, routes=routes, headless=True, evidence='server-headless',
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
        # Every counted region must be loaded and free of natural blocks of the carried type (the world copy has natural sand), so
        # the overworld counting region is cleared once and the End regions are forceloaded before the first baseline.
        for c in ['forceload add -16 -16 16 16', 'fill -12 60 -12 12 90 12 minecraft:air']:
            rcon('execute in %s run %s' % (OW, c))
        for c in ['forceload add 90 -10 110 10', 'forceload add 0 0 24 20']:
            rcon('execute in %s run %s' % (END, c))
        for card in census.split(','):
            m = re.fullmatch(r'D0-falling-([a-z0-9_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)', card)
            assert m, card
            variant, route = m.groups()
            if types and variant not in types: continue
            if route not in routes: continue
            src, dst = {'NETHER_ENTRY': (OW, NETHER), 'NETHER_RETURN': (NETHER, OW), 'END_ENTRY': (OW, END), 'END_EXIT': (END, OW), 'END_GATEWAY': (END, END)}[route]
            tag = 'stage7_d_%d' % len(result['cards'])
            fixture_ok = True
            if route.startswith('NETHER'):
                for world in (OW, NETHER):
                    rebuilt = nether_fixture(world)
                    if world == src: fixture_ok = rebuilt
            else:
                end_ready.discard((src, 65)); end_ready.discard((src, 70)); end_ready.discard((END, 'gateway'))
            if route == 'END_GATEWAY':
                for c in ['forceload add 0 0 24 16', 'fill 8 64 4 24 64 16 minecraft:end_stone', 'fill 8 65 4 24 73 16 minecraft:air',
                          'setblock 12 66 8 minecraft:end_gateway',
                          'data merge block 12 66 8 {exit_portal:[I;22,80,12],ExactTeleport:1b,Age:200L}']:   # exit 15 blocks above the floor so a falling block can be snapshotted in flight
                    rcon('execute in ' + END + ' run ' + c)
            elif route.startswith('END'):
                y = 65 if route == 'END_ENTRY' else 70
                for c in ['forceload add 0 0 15 15', 'fill 4 %d 4 12 %d 12 minecraft:obsidian' % (y - 1, y - 1),
                          'fill 4 %d 4 12 %d 12 minecraft:air' % (y, y + 6), 'setblock 8 %d 8 minecraft:end_portal' % y]:
                    rcon('execute in ' + src + ' run ' + c)
            # keep every dimension clear of leftovers from the previous card
            for world in (OW, NETHER, END):
                rcon('execute in %s run kill @e[type=item]' % world)
                rcon('execute in %s run kill @e[type=falling_block]' % world)
                rcon('execute in %s run kill @e[tag=stage7_target]' % world)
            block, extra = SPECIAL.get(variant, (variant, ''))
            spec, state_nbt = state_spec(block)
            landing = variant == 'landing_damage'
            if landing and fixture_ok:
                if route == 'END_EXIT':   # the overworld terrain is unknown: give the landing column a known floor
                    for c in ['forceload add 0 0 15 15', 'fill 5 70 2 11 70 8 minecraft:stone', 'fill 5 71 2 11 90 8 minecraft:air']:
                        rcon('execute in %s run %s' % (OW, c))
                cx, cy, cz = TARGET_GRID[route]
                for dx in (-1, 0, 1):
                    for dz in (-1, 0, 1):
                        rcon('execute in %s run summon minecraft:pig %d.5 %d.0 %d.5 {Tags:[\"stage7_target\"],NoAI:1b,NoGravity:1b,Silent:1b,PortalCooldown:1000000}'
                             % (dst, cx + dx, cy, cz + dz))
            witness = {'carriedSpec': spec, 'variant': variant}
            if fixture_ok:
                witness['baseline'] = observables(spec, landing, block)
            at, motion = LAUNCH[route]
            summon = rcon('execute in %s run summon minecraft:falling_block %s {Tags:["stage7_mob","%s"%s],BlockState:%s,Time:1%s%s}'
                          % (src, at, tag, ',"stage7_gateway_pig"' if route == 'END_GATEWAY' else '', state_nbt, extra, motion))
            outcome = 'GONE' if fixture_ok else 'FIXTURE_INVALID'; where = 'NONE'
            if fixture_ok:
                witness['nbtSource'] = nbt_of(src, tag)
                source_body = snapshot_body() if not vanilla else None
                witness['source'] = snapshot_members(tag, source_body)
            # Best-effort arrival witness: a falling block may land before the harness sees it in the destination, so the
            # importer relies on the transaction trace for identity and on the observables for the result.
            t0 = time.monotonic()
            while fixture_ok and time.monotonic() - t0 < 6:
                if process.poll() is not None or failed(): raise RuntimeError('server failure during %s: %s' % (card, failed()))
                far = route == 'END_GATEWAY'
                if count_in(dst, tag, far):
                    where = dst; break
                if not far and not count_in(src, tag): break
                time.sleep(.1)
            if where != 'NONE':
                witness['nbtArrival'] = nbt_of(where, tag)
                if not vanilla:
                    arrival_body = snapshot_body()
                    witness['arrival'] = snapshot_members(tag, arrival_body)
                    if route == 'END_GATEWAY': witness['gateway'] = gateway_events(witness['arrival'], arrival_body)
            if fixture_ok:
                time.sleep(6)   # let the native landing / drop / damage behaviour play out
                witness['observables'] = observables(spec, landing, block)
                witness['observablesSettledTicks'] = 120
                after, before = witness['observables'], witness['baseline']
                def delta(world, label, area=None):
                    regions = after[world]['regions']
                    return sum(regions[r]['blocks'] - before[world]['regions'][r]['blocks']
                               for r in regions if area is None or r in area)
                if route == 'END_GATEWAY':
                    landed_dst, landed_src = delta('end', None, ('gateway_exit',)), delta('end', None, ('fixture',))
                else:
                    landed_dst = delta({OW: 'overworld', NETHER: 'nether', END: 'end'}[dst], None)
                    landed_src = delta({OW: 'overworld', NETHER: 'nether', END: 'end'}[src], None)
                outcome = 'TRANSFERRED' if landed_dst > 0 and landed_src <= 0 else 'STAYED' if landed_src > 0 else 'GONE'
            result['cards'].append(dict(card=card, outcome=outcome, world=where, summon=summon.strip()[:80], witness=witness))
            for w in (OW, NETHER, END):
                rcon('execute in %s run kill @e[tag=stage7_mob]' % w)
                rcon('execute in %s run kill @e[tag=stage7_target]' % w)
                rcon('execute in %s run kill @e[type=item]' % w)
                for region in REGIONS[w].values(): rcon('execute in %s run fill %s minecraft:air replace %s' % (w, region, spec))
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
    receipt = {'schema': 'stage7-headless-receipt-v1', 'evidence': 'server-headless', 'group': 'headless-dynamic',
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
