"""Stage 9 DET-1 v2: the world-state diff probe with the vanilla randoms reseeded (needs D27+: `phoenium test reseed`).

determinism9e8.py (v1) measured the noise floor: two identical 1-thread runs already diverge (150/160 positions) because
every entity owns an unseeded RandomSource and LivingEntity draws its initial yaw from it. v2 removes both:
- the mobs are summoned with an explicit Rotation;
- `phoenium test reseed <seed>` (test diagnostics only) reseeds every loaded entity's own random from (seed, UUID) and
  every level's own random from (seed, dimension) on the server thread, right before `tick step`.
Everything else is v1: fresh flat server, seed 424242, engine mode with the given tile threads and schedule dag,
protections off, frozen setup, 160 mobs with fixed UUIDs at fixed positions (tag d), spawning off, fixed time and weather,
`tick step 200`, full NBT dump. The w1 vs w1 pair must be identical before a w5 / w7 difference counts as a race.

    set S8_CANDIDATE=<hashed jar>
    python gates/scripts/determinism9e8b.py <name> <engine-workers> <base-port>
    python gates/scripts/determinism9e8b.py --compare <name-a> <name-b> [...]
Results: .audit/stage9-20261009/gates/determinism/<name>/positions.json, determinism-result.json; compare -> stdout + json
(the compare also counts motion, rotation and on-ground differences).
"""
import json, math, pathlib, re, sys, time

STAGE9 = pathlib.Path(r'C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage9-20261009')
OUT = STAGE9 / 'gates' / 'determinism'
SEED = '424242'
STEPS = 200


def compare(names):
    runs = {n: json.loads((OUT / n / 'positions.json').read_text(encoding='utf-8')) for n in names}
    base = names[0]
    result = {'schema': 'stage9-determinism-compare-v2', 'base': base, 'pairs': []}
    for other in names[1:]:
        a, b = runs[base]['entities'], runs[other]['entities']
        common = sorted(set(a) & set(b))
        moved = [u for u in common if math.dist(a[u]['pos'], b[u]['pos']) > 1e-6]
        far = [u for u in common if math.dist(a[u]['pos'], b[u]['pos']) > 1.0]
        health = [u for u in common if abs(a[u]['health'] - b[u]['health']) > 1e-6]
        motion = [u for u in common if a[u]['motion'] != b[u]['motion']]
        rotation = [u for u in common if a[u]['rotation'] != b[u]['rotation']]
        ground = [u for u in common if a[u]['onGround'] != b[u]['onGround']]
        pair = {'a': base, 'b': other, 'workersA': runs[base]['workers'], 'workersB': runs[other]['workers'],
                'entitiesA': len(a), 'entitiesB': len(b), 'common': len(common), 'onlyA': len(set(a) - set(b)),
                'onlyB': len(set(b) - set(a)), 'positionDiffers': len(moved), 'positionDiffersOver1Block': len(far),
                'healthDiffers': len(health), 'motionDiffers': len(motion), 'rotationDiffers': len(rotation),
                'onGroundDiffers': len(ground),
                'maxDistance': round(max((math.dist(a[u]['pos'], b[u]['pos']) for u in common), default=0.0), 4),
                'firstDiffering': [{'uuid': u, 'kind': a[u].get('kind'), 'a': a[u]['pos'], 'b': b[u]['pos']} for u in moved[:5]]}
        result['pairs'].append(pair)
        print(json.dumps(pair))
    (OUT / f'compare-{"-vs-".join(names)}.json').write_text(json.dumps(result, indent=1), encoding='utf-8')


if sys.argv[1] == '--compare':
    compare(sys.argv[2:])
    sys.exit(0)

sys.path.insert(0, r'C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage8-compat-20261007\scripts8')
import s8lib as L  # noqa: E402

name, workers, base = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
ENGINE = ['-Dphoenium.engine.tiles=true', f'-Dphoenium.engine.workers={workers}', '-Dphoenium.engine.schedule=dag',
          '-Dphoenium.test.diagnostics=true']
run = OUT / name
ports = {'java': base, 'rcon': base + 1, 'bedrock': base - 7000, 'voice': base - 1500}
for k, p in ports.items():
    if not L.free_port(p, udp=k in ('bedrock', 'voice')):
        sys.exit(f'port busy {k}={p}')
manifest = L.build_server(run / 'server', L.STACK_LAYERS[L.FINAL], ports, f'S9E8-DET-{name}',
                          extra_props={'level-type': 'minecraft:flat', 'generator-settings': '{}', 'level-seed': SEED,
                                       'difficulty': 'easy', 'spawn-protection': '0', 'gamemode': 'creative',
                                       'view-distance': '4', 'simulation-distance': '4'})
res = {'schema': 'stage9-determinism-run-v2', 'name': name, 'workers': workers, 'engineProps': ENGINE, 'seed': SEED,
       'steps': STEPS, 'utc': L.now(), 'candidate': str(L.CANDIDATE), 'candidateSha256': L.CANDIDATE_SHA, 'manifest': manifest}
s = L.Server(run / 'server', 'console.log', workers=1, extra_props=ENGINE).start()


def command(text, expect=None, timeout=60):
    start = len(s.lines)
    s.console(text)
    if expect:
        return s.wait_for(expect, timeout, start=start)
    return None


entities = {}
kinds = ['zombie', 'cow', 'pig', 'sheep', 'chicken', 'skeleton', 'cow', 'zombie']
try:
    res['engineStart'] = s.wait_for(r'PHOENIUM_STAGE2 started lifecycle=RUNNING', 900)[1]
    s.wait_for(r'Done \(', 120)
    command('tick freeze', 'The game is frozen')
    command('gamerule minecraft:spawn_mobs false', 'false')
    command('time set 14000', r'Set .* to \d+ tick|Set the time')
    command('weather clear 1000000', 'weather|Weather')
    command('forceload add -32 -32 159 159', 'Marked|already')
    for _ in range(240):
        start = len(s.lines)
        s.console('execute if loaded 150 64 150')
        if 'Test passed' in s.wait_for(r'Test (passed|failed)', 30, start=start)[1]:
            break
        time.sleep(0.5)
    for x0 in (0, 64):
        for z0 in (0, 64):
            command(f'fill {x0} 64 {z0} {x0 + 63} 64 {z0 + 63} minecraft:grass_block', 'Successfully filled|No blocks')
            command(f'fill {x0} 65 {z0} {x0 + 63} 70 {z0 + 63} minecraft:air', 'Successfully filled|No blocks')
    for box in ('0 65 0 127 66 0', '0 65 127 127 66 127', '0 65 0 0 66 127', '127 65 0 127 66 127'):
        command(f'fill {box} minecraft:cobblestone_wall', 'Successfully filled|No blocks')
    for i in range(160):
        x, z = 44.5 + (i % 16) * 2.5, 44.5 + (i // 16) * 4.0
        command(f'summon minecraft:{kinds[i % len(kinds)]} {x} 65 {z} '
                f'{{UUID:[I;424242,{i},7,9],Tags:["d"],PersistenceRequired:1b,Rotation:[0f,0f]}}', 'Summoned new')
    time.sleep(2)
    res['nonTagged'] = command('execute if entity @e[tag=!d]', r'Test (passed|failed)')[1][-80:]
    res['reseed'] = command(f'phoenium test reseed {SEED}', r'PHOENIUM_RESEED .*')[1][-120:]
    command(f'tick step {STEPS}', 'Stepping|step')
    time.sleep(STEPS / 20 + 8)
    start = len(s.lines)
    s.console('execute as @e[tag=d] run data get entity @s')
    deadline = time.time() + 60
    while time.time() < deadline:
        lines = s.lines[start:]
        found = [l for l in lines if 'has the following entity data' in l]
        if len(found) >= 160:
            break
        time.sleep(0.5)
    for line in s.lines[start:]:
        if 'has the following entity data' not in line:
            continue
        uuid = re.search(r'UUID: \[I; (-?\d+), (-?\d+), (-?\d+), (-?\d+)\]', line)
        pos = re.search(r'Pos: \[(-?[\d.E-]+)d, (-?[\d.E-]+)d, (-?[\d.E-]+)d\]', line)
        motion = re.search(r'Motion: \[(-?[\d.E-]+)d, (-?[\d.E-]+)d, (-?[\d.E-]+)d\]', line)
        rotation = re.search(r'Rotation: \[(-?[\d.E-]+)f, (-?[\d.E-]+)f\]', line)
        health = re.search(r'Health: (-?[\d.E-]+)f', line)
        ground = re.search(r'OnGround: (\d)b', line)
        kind = re.search(r'\]: (\w[\w ]*?) has the following entity data', line)
        if not (uuid and pos):
            continue
        key = ','.join(uuid.groups())
        entities[key] = {'kind': kind.group(1) if kind else None, 'pos': [float(v) for v in pos.groups()],
                         'motion': [float(v) for v in motion.groups()] if motion else None,
                         'rotation': [float(v) for v in rotation.groups()] if rotation else None,
                         'health': float(health.group(1)) if health else -1.0,
                         'onGround': ground.group(1) if ground else None}
    res['entities'] = len(entities)
    res['tickQuery'] = command('tick query', r'game is|tick', 30)[1][-200:]
except Exception as e:  # noqa: BLE001
    res['failure'] = repr(e)
    print('FAILURE', repr(e), flush=True)
finally:
    res['exit'] = s.stop()
stop = next((l for l in reversed(s.lines) if 'PHOENIUM_STAGE2 stopped' in l), '')
res['engineStop'] = stop
res['stopTraps'] = {k: int(m.group(1)) for k in ('loadTraps', 'writeTraps', 'storageTraps') for m in [re.search(rf'\b{k}=(\d+)', stop)] if m}
(run / 'positions.json').write_text(json.dumps({'name': name, 'workers': workers, 'entities': entities}, indent=1), encoding='utf-8')
reseeded = re.search(r'entities=(\d+) refused=(\d+)', res.get('reseed') or '')
res['pass'] = (not res.get('failure') and res['exit'] == 0 and len(entities) == 160 and sum(res['stopTraps'].values()) == 0
               and reseeded is not None and int(reseeded.group(1)) >= 160 and reseeded.group(2) == '0')
(run / 'determinism-result.json').write_text(json.dumps(res, indent=1), encoding='utf-8')
print(json.dumps({k: res.get(k) for k in ('name', 'workers', 'pass', 'entities', 'reseed', 'nonTagged', 'exit', 'stopTraps', 'failure')}))
