"""Stage 9 S9-E16: unload durability control (follow-up to autosave gate A 'U', gates/autosave/e15-w5-dag-a3).

Question: when an 8 x 8-chunk corner loses its force-load ticket, do its chunks reach disk through their unload before a
hard kill, with the autosave spread OFF (the shipping configuration)? Gate A's D1 lane (spread ON, tick freeze, C2ME
autoSave PERIODIC) found all 64 corner chunks still at the previous generation more than 10 s after `forceload remove`,
while their entity sections were partly written. This control separates the spread from the chunk system.

One fresh flat L5 server per arm (s8lib.build_server, read-only), Phoenium = S8_CANDIDATE in engine mode (w5 dag), no
player. Per arm: forceload the corner (blocks 128..255), gen 1 = white glass at y 100 + one tagged marker per chunk,
`save-all flush`, edit to gen 2 (orange glass, marker tags), then `forceload remove` the corner and watch for 20 s:
corner chunks still loaded (`execute if loaded` on 16 probes) and corner chunks written (region headers). Then a hard kill
(TerminateProcess) and an offline read of the corner (autosave9e12.py's readers, extracted with ast).
Arms: frozen-periodic (gate A's D1 conditions, spread OFF) | running-periodic (no tick freeze) | running-stock (stock
c2me.toml) | frozen-periodic-spread (spread ON, gate A's D1 conditions reproduced).
PASS (shipping config) = in the spread-OFF arms every corner chunk is unloaded and at gen 2 on disk (blocks and markers)
before the kill. The spread-ON arm is informational.

    set S8_CANDIDATE=<hashed jar>
    python gates/scripts/unloadctl9e16.py <name> <java-port> [--arms a,b,...]
Results: .audit/stage9-20261009/gates/unloadctl/<name>/unloadctl-result.json
"""
import argparse, ast, gzip, json, pathlib, re, struct, sys, time, uuid, zlib
sys.path.insert(0, r'C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage8-compat-20261007\scripts8')
import s8lib as L  # noqa: E402

STAGE9 = pathlib.Path(r'C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage9-20261009')
GATE_A = STAGE9 / 'gates' / 'scripts' / 'autosave9e12.py'
tree = ast.parse(GATE_A.read_text(encoding='utf-8'))
_keep_fn = {'nbt', 'headers', 'region', 'block_at', 'offline_uuid', 'read_world'}
_keep_var = {'USER', 'COLOURS', 'GEN_OF', 'Y', 'AREA', 'AREA_SET', 'CHESTS'}
R = {'gzip': gzip, 'json': json, 're': re, 'struct': struct, 'uuid': uuid, 'zlib': zlib, 'hashlib': __import__('hashlib')}
exec(compile(ast.Module(body=[n for n in tree.body if (isinstance(n, ast.FunctionDef) and n.name in _keep_fn) or (
    isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id in _keep_var for t in n.targets))], type_ignores=[]),
    str(GATE_A), 'exec'), R)
CORNER = [(cx, cz) for cx in range(8, 16) for cz in range(8, 16)]
PROBES = [(cx * 16 + 8, cz * 16 + 8) for cx in (8, 10, 13, 15) for cz in (8, 10, 13, 15)]
ARMS = {'frozen-periodic': (True, 'PERIODIC', False), 'running-periodic': (False, 'PERIODIC', False),
        'running-stock': (False, 'default', False), 'frozen-periodic-spread': (True, 'PERIODIC', True)}

ap = argparse.ArgumentParser()
ap.add_argument('name'); ap.add_argument('port', type=int)
ap.add_argument('--arms', default=','.join(ARMS))
a = ap.parse_args()
root = STAGE9 / 'gates' / 'unloadctl' / a.name
ports = {'java': a.port, 'rcon': a.port + 1, 'bedrock': a.port - 7000, 'voice': a.port - 1500}
res = {'schema': 'stage9-unloadctl-v1', 'name': a.name, 'candidate': str(L.CANDIDATE), 'candidateSha256': L.CANDIDATE_SHA,
       'ports': ports, 'arms': {}, 'checks': []}
ENGINE = ['-Dphoenium.engine.tiles=true', '-Dphoenium.engine.workers=5', '-Dphoenium.engine.schedule=dag']


def check(label, ok, detail=None):
    res['checks'].append({'label': label, 'ok': bool(ok), 'detail': detail})
    print(('PASS ' if ok else 'FAIL ') + label, '' if detail is None else detail, flush=True)


def cmd(srv, text, expect, timeout=30):
    start = len(srv.lines); srv.console(text)
    return srv.wait_for(expect, timeout, start=start)[1]


def many(srv, cmds, expect, timeout=120):
    rx, start = re.compile(expect), len(srv.lines)
    for c in cmds:
        srv.console(c)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if sum(1 for l in srv.lines[start:] if rx.search(l)) >= len(cmds):
            return
        time.sleep(0.1)
    raise TimeoutError(f'{len(cmds)} x {expect}')


def loaded(srv):
    return sum('Test passed' in cmd(srv, f'execute if loaded {x} 100 {z}', r'Test (passed|failed)') for x, z in PROBES)


def corner_on_disk(world):
    o = R['read_world'](world)
    return ({p: o['blocks'].get(p) for p in CORNER}, {p: o['markers'].get(p) for p in CORNER}, o['regionTs'], o['entTs'], o['bad'])


for arm in a.arms.split(','):
    frozen, mode, spread = ARMS[arm]
    run = root / arm
    level = f'S9E16-UNLOAD-{a.name}-{arm}'
    L.build_server(run / 'server', L.STACK_LAYERS[L.FINAL], ports, level,
                   extra_props={'level-type': 'minecraft:flat', 'generator-settings': '{}', 'difficulty': 'peaceful',
                                'spawn-protection': '0', 'gamemode': 'creative', 'view-distance': '4',
                                'simulation-distance': '4', 'pause-when-empty-seconds': '-1'})
    c2me = run / 'server' / 'config' / 'c2me.toml'
    if mode != 'default':
        t = c2me.read_text(encoding='utf-8')
        t2 = re.sub(r'(\[generalOptimizations\.autoSave\][^\[]*?\bmode = )"default"', rf'\1"{mode}"', t, count=1, flags=re.S)
        assert t2 != t, 'c2me autoSave section'
        c2me.write_text(t2, encoding='utf-8')
    world = run / 'server' / level
    props = ENGINE + (['-Dphoenium.engine.autosaveSpread=true'] if spread else [])
    srv = L.Server(run / 'server', 'console.log', workers=1, extra_props=props).start()
    rec = {'frozen': frozen, 'c2meAutoSave': mode, 'spread': spread, 'loadedAfterRemove': [], 'writtenAfterRemove': []}
    res['arms'][arm] = rec
    try:
        rec['start'] = srv.wait_for(r'PHOENIUM_STAGE2 started lifecycle=RUNNING', 900)[1][-160:]
        srv.wait_for(r'Done \(', 180)
        if frozen:
            cmd(srv, 'tick freeze', 'The game is frozen')
        cmd(srv, 'forceload add 128 128 255 255', r'Marked|No chunks were marked')
        deadline = time.time() + 120
        while loaded(srv) < len(PROBES):
            if time.time() > deadline:
                raise TimeoutError('corner not loaded')
            time.sleep(0.5)
        cmd(srv, 'fill 128 100 128 255 100 255 minecraft:white_stained_glass', 'Successfully filled')
        many(srv, [f'summon minecraft:marker {cx * 16 + 8} 101 {cz * 16 + 8} {{Tags:["dg","g0"]}}' for cx, cz in CORNER], 'Summoned new')
        cmd(srv, 'save-all flush', 'Saved the game', 120)
        base_blocks, base_marks, base_ts, base_ets, bad = corner_on_disk(world)
        rec['base'] = {'blocksGen0': sum(v == 0 for v in base_blocks.values()), 'markersG0': sum(v == [[0]] for v in base_marks.values()),
                       'bad': bad[:3]}
        h0 = R['headers'](world)
        cmd(srv, 'fill 128 100 128 255 100 255 minecraft:orange_stained_glass', 'Successfully filled')
        cmd(srv, 'tag @e[type=minecraft:marker,tag=dg] remove g0', r'(?i)tag')
        cmd(srv, 'tag @e[type=minecraft:marker,tag=dg] add g1', r'(?i)tag')
        time.sleep(2)
        h1 = R['headers'](world)
        rec['writtenBeforeRemove'] = sum(1 for k, v in h1.items() if h0.get(k) != v)
        t_remove = time.time()
        cmd(srv, 'forceload remove 128 128 255 255', r'Unmarked')
        for _ in range(4):
            time.sleep(5)
            rec['loadedAfterRemove'].append(loaded(srv))
            h = R['headers'](world)
            rec['writtenAfterRemove'].append({'region': sum(1 for (k, cx, cz), v in h.items() if k == 'region' and (cx, cz) in CORNER and h0.get((k, cx, cz)) != v),
                                             'entities': sum(1 for (k, cx, cz), v in h.items() if k == 'entities' and (cx, cz) in CORNER and h0.get((k, cx, cz)) != v)})
        rec['secondsWatched'] = round(time.time() - t_remove, 1)
        start = len(srv.lines); srv.console('phoenium diagnostics')
        rec['diagnostics'] = srv.wait_for(r'mode=engine-tiles lifecycle=', 30, start=start)[1][-400:]
    finally:
        srv.proc.kill(); rec['killRc'] = srv.proc.wait(60); srv._t.join(10); srv.logf.close()
    blocks, marks, _, _, bad = corner_on_disk(world)
    rec['afterKill'] = {'blocksGen1': sum(v == 1 for v in blocks.values()), 'blocksGen0': sum(v == 0 for v in blocks.values()),
                        'markersG1': sum(v == [[1]] for v in marks.values()), 'markersG0': sum(v == [[0]] for v in marks.values()),
                        'bad': bad[:3]}
    print(arm, json.dumps({k: rec[k] for k in ('base', 'writtenBeforeRemove', 'loadedAfterRemove', 'writtenAfterRemove', 'afterKill')}), flush=True)
    ok = rec['base']['blocksGen0'] == 64 and rec['afterKill']['blocksGen1'] == 64 and rec['afterKill']['markersG1'] == 64
    label = f'{arm}: corner unloaded and at the new generation on disk before the kill (blocks and markers)'
    if spread:
        res.setdefault('info', {})[arm] = {'ok': ok, 'afterKill': rec['afterKill'], 'loadedAfterRemove': rec['loadedAfterRemove']}
    else:
        check(label, ok and rec['loadedAfterRemove'][-1] == 0, rec['afterKill'])

res['pass'] = bool(res['checks']) and all(c['ok'] for c in res['checks'])
root.mkdir(parents=True, exist_ok=True)
(root / 'unloadctl-result.json').write_text(json.dumps(res, indent=2, default=str), encoding='utf-8')
print(json.dumps({'name': a.name, 'pass': res['pass'], 'checks': [(c['label'][:40], c['ok']) for c in res['checks']], 'info': res.get('info')}, indent=1, default=str))
sys.exit(0 if res['pass'] else 1)
