"""Headless defect-finding census: real installed server (pinned providers, probe observer), no client window.

Usage: python scripts/stage7/headless_census.py <fresh-name> <workers 1|4> <candidate-jar> <census> [--types a,b,c] [--routes R1,R2]

A census is a list of E0-style cards (<type>-<ROUTE>): the entity is summoned in the source dimension's portal cell,
the harness waits for the native transfer and records TRANSFERRED / STAYED / GONE. Only server health is required
(no crash, no observer violation, normal save). The output is DATA for finding crashes, never ledger evidence: the
importer only accepts real-client receipts.
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
vanilla = '--vanilla' in sys.argv   # Phoenium-free native control: empty mods directory, same fixture
ports = dict(java=25687, bedrock=19187, rcon=25787) if vanilla else dict(java=25686, bedrock=19186, rcon=25786)
run = audit / 'installed' / name
server = run / 'server'
java = pathlib.Path(r'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe')

OW, NETHER, END = 'minecraft:overworld', 'minecraft:the_nether', 'minecraft:the_end'
NBT = {
    'area_effect_cloud': ',Duration:1200,Radius:2.0f,Particle:{type:"minecraft:effect",color:16711680}',
    'experience_orb': ',Value:5s',
    'firework_rocket': ',LifeTime:400,FireworksItem:{id:"minecraft:firework_rocket",count:1,components:{"minecraft:fireworks":{flight_duration:3}}}',
    'evoker_fangs': ',Warmup:0', 'end_crystal': ',ShowBottom:0b',
    'ominous_item_spawner': ',item:{id:"minecraft:arrow",count:1},spawn_item_after_ticks:400L',
    'item': ',Item:{id:"minecraft:diamond",count:1},PickupDelay:32767',
    'falling_block': ',BlockState:{Name:"minecraft:sand"},Time:1', 'tnt': ',fuse:400s',
}


def sha(p):
    with p.open('rb') as s:
        return hashlib.file_digest(s, 'sha256').hexdigest().upper()


def rcon(command):
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
        packet(1, 3, 'phoenium-stage7-local-test'); read(); packet(2, 2, command); return read()


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
        'rcon.password': 'phoenium-stage7-local-test', 'motd': 'Phoenium Stage7 headless census',
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
(run / 'inputs.json').write_text(json.dumps(dict(candidate=str(immutable), candidateSha256=digest, probeSha256=sha(probe), workers=workers, vanilla=vanilla,
    census=census, routes=routes, headless=True,
    head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=work, text=True).strip()), indent=2))

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
        for world in [OW, NETHER]:
            prefix = 'execute in ' + world + ' run '
            for c in ['forceload add -16 -16 16 16', 'fill -12 64 -12 12 64 12 minecraft:sea_lantern',
                      'fill -12 65 -12 12 76 12 minecraft:air', 'fill -12 76 -12 12 76 12 minecraft:glass',
                      'fill -12 65 -12 -12 75 12 minecraft:glass', 'fill 12 65 -12 12 75 12 minecraft:glass',
                      'fill -12 65 -12 12 75 -12 minecraft:glass', 'fill -12 65 12 12 75 12 minecraft:glass',
                      'fill -2 64 0 2 69 0 minecraft:obsidian', 'fill -1 65 0 1 68 0 minecraft:air',
                      'setblock -1 65 0 minecraft:fire', 'fill -2 65 -10 2 69 -10 minecraft:stone',
                      'execute if block 0 66 0 minecraft:nether_portal']:
                response = rcon(prefix + c)
                if 'execute if block' in c: assert 'Test passed' in response, (prefix + c, response)
        rcon('setworldspawn 8 65 5')
        rcon('gamerule minecraft:spawn_mobs false')
        for dbg in filter(None, os.environ.get('HC_DEBUG_CMDS', '').split(';;')):
            print('DEBUG', dbg, '=>', rcon(dbg), flush=True)
        end_ready = set()
        for card in census.split(','):
            m = re.fullmatch(r'E0-([a-z_]+)-(NETHER_ENTRY|NETHER_RETURN|END_ENTRY|END_EXIT|END_GATEWAY)', card)
            assert m, card
            etype, route = m.groups()
            if types and etype not in types: continue
            if route not in routes: continue
            src, dst = {'NETHER_ENTRY': (OW, NETHER), 'NETHER_RETURN': (NETHER, OW), 'END_ENTRY': (OW, END), 'END_EXIT': (END, OW), 'END_GATEWAY': (END, END)}[route]
            tag = 'stage7_h_%d' % len(result['cards'])
            at = '0.5 65 0.5'
            if route == 'END_GATEWAY':
                # Same-dimension End gateway with an exact exit eight blocks east, inside the forceloaded square.
                if (END, 'gateway') not in end_ready:
                    for c in ['forceload add 0 0 16 16', 'fill 8 64 4 24 64 16 minecraft:end_stone', 'fill 8 65 4 24 73 16 minecraft:air',
                              'setblock 12 66 8 minecraft:end_gateway',
                              'data merge block 12 66 8 {exit_portal:[I;22,66,12],ExactTeleport:1b,Age:200L}']:
                        rcon('execute in ' + END + ' run ' + c)
                    end_ready.add((END, 'gateway'))
                at = '12.5 66 8.5'
            elif route.startswith('END'):
                y = 65 if route == 'END_ENTRY' else 70
                if (src, y) not in end_ready:
                    for c in ['forceload add 0 0 15 15', 'fill 4 %d 4 12 %d 12 minecraft:obsidian' % (y - 1, y - 1),
                              'fill 4 %d 4 12 %d 12 minecraft:air' % (y, y + 6), 'setblock 8 %d 8 minecraft:end_portal' % y]:
                        rcon('execute in ' + src + ' run ' + c)
                    end_ready.add((src, y))
                at = '8.5 %d.25 8.5' % y
            summon = rcon('execute in %s run summon minecraft:%s %s {Tags:["stage7_mob","%s"],NoGravity:1b%s}' % (src, etype, at, tag, NBT.get(etype, '')))
            outcome = 'GONE'; where = 'NONE'
            t0 = time.monotonic()
            while time.monotonic() - t0 < 8:
                if process.poll() is not None or failed(): raise RuntimeError('server failure during %s: %s' % (card, failed()))
                if route == 'END_GATEWAY':
                    pos = rcon('execute in %s positioned 0 64 0 run data get entity @e[tag=%s,distance=0..2000,limit=1] Pos[0]' % (END, tag))
                    got = re.search(r'(-?[0-9.]+)d$', pos.strip())
                    if got and float(got.group(1)) > 18: outcome, where = 'TRANSFERRED', END; break
                    if got: outcome, where = 'STAYED', END
                    time.sleep(.25); continue
                if 'Test passed' in rcon('execute in %s positioned 0 64 0 if entity @e[tag=%s,distance=0..2000]' % (dst, tag)):
                    outcome, where = 'TRANSFERRED', dst; break
                if 'Test passed' in rcon('execute in %s positioned 0 64 0 if entity @e[tag=%s,distance=0..2000]' % (src, tag)):
                    outcome, where = 'STAYED', src
                time.sleep(.25)
            info = ''
            if outcome != 'TRANSFERRED':
                info = rcon('execute in %s positioned 0 64 0 run data get entity @e[tag=%s,distance=0..2000,limit=1] Pos' % (src, tag)).strip()[:120]
            result['cards'].append(dict(card=card, outcome=outcome, world=where, summon=summon.strip()[:80], info=info))
            for w in (OW, NETHER, END): rcon('execute in %s run kill @e[tag=stage7_mob]' % w)
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
        result['clientPass'] = True
except BaseException as error:
    result['failure'] = str(error); print('FAILED', error, flush=True)
finally:
    if process and process.poll() is None:
        try: process.stdin.write(b'save-all\nstop\n'); process.stdin.flush(); result['normalStopRequested'] = True
        except (BrokenPipeError, OSError): pass
        try: process.wait(timeout=90)
        except subprocess.TimeoutExpired: process.terminate(); process.wait(timeout=15); result['forcedOwnServerStop'] = True
    result['serverExitCode'] = None if process is None else process.poll()
    result['logFailure'] = failed(); result['normalSave'] = 'All dimensions are saved' in logtext()
    result['pass'] = bool(result.get('clientPass') and result.get('txPass') and not result.get('failure') and not result['logFailure'] and result['normalSave'])
    (run / 'first-result.json').write_text(json.dumps(result, indent=2))
    summary = {}
    for c in result['cards']: summary.setdefault(c['outcome'], []).append(c['card'])
    print(json.dumps({k: (v if len(v) < 40 else str(len(v)) + ' cards') for k, v in summary.items()}, indent=1))
    print('PASS' if result['pass'] else 'FAIL', result.get('failure'), flush=True)
sys.exit(0 if result['pass'] else 1)
