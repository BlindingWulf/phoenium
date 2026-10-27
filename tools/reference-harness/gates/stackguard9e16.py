"""Stage 9 S9-E16 (D35+): supported-stack startup guard gate (S8-F1 / layer L0).

Three fresh servers from the pinned template (s8lib.build_server, used read-only), Phoenium = S8_CANDIDATE:
  A  layer L0 (Fabric API only, no C2ME), default mode, no override: the server must refuse to start. Pass = the process
     exits by itself before 'Done (', the console has vanilla's 'Failed to start the minecraft server' with the guard's
     message ("Phoenium needs the patched C2ME"), and no world started ('Preparing level' absent). The exit code is recorded
     only: vanilla's Main logs a failed start and returns, so the JVM exits 0 (e16-guard1 asserted non-zero: script error).
  B  layer L0, -Dphoenium.allowUnsupportedStack=true (test override): starts ('Done ('), logs 'PHOENIUM_STACK unsupported,
     started only because', stops cleanly (rc 0). L0 stays an unsupported configuration (S8-F1); B only proves the override.
  C  layer L5 (the certified stack), engine mode w5 dag, no override: logs 'PHOENIUM_STACK supported: c2me
     0.4.2-alpha.0.43-phoenium-settlement2', the engine start line, 'Done (', clean stop with traps 0, no BAD_RE line.

    set S8_CANDIDATE=<hashed jar>
    python gates/scripts/stackguard9e16.py <name> <java-port>
Results: .audit/stage9-20261009/gates/stackguard/<name>/stackguard-result.json
"""
import json, pathlib, re, socket, sys, time
sys.path.insert(0, r'C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage8-compat-20261007\scripts8')
import s8lib as L  # noqa: E402

TRAPS = ('loadTraps', 'writeTraps', 'storageTraps')
REFUSAL = 'Phoenium needs the patched C2ME'
name, port = sys.argv[1], int(sys.argv[2])
root = pathlib.Path(r'C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage9-20261009\gates\stackguard') / name
ports = {'java': port, 'rcon': port + 100, 'bedrock': port - 6500, 'voice': port - 1000}   # lead 25750 -> 25850 / 19250 / 24750
for p, kind in ((ports['java'], socket.SOCK_STREAM), (ports['rcon'], socket.SOCK_STREAM), (ports['bedrock'], socket.SOCK_DGRAM),
                (ports['voice'], socket.SOCK_DGRAM)):
    s = socket.socket(socket.AF_INET, kind); s.bind(('127.0.0.1', p)); s.close()
res = {'schema': 'stage9-stackguard-v1', 'name': name, 'candidate': str(L.CANDIDATE), 'candidateSha256': L.CANDIDATE_SHA,
       'ports': ports, 'arms': {}, 'checks': []}


def check(label, ok, detail=None):
    res['checks'].append({'label': label, 'ok': bool(ok), 'detail': detail})
    print(('PASS ' if ok else 'FAIL ') + label, '' if detail is None else detail, flush=True)


def server(arm, layer, props):
    manifest = L.build_server(root / arm / 'server', L.STACK_LAYERS[layer], ports, f'S9E16-GUARD-{name}-{arm}')
    srv = L.Server(root / arm / 'server', 'console.log', workers=1, extra_props=props).start()
    res['arms'][arm] = {'layer': layer, 'props': props, 'manifestMods': sorted(manifest.get('mods', {}) if isinstance(manifest, dict) else [])}
    return srv


def crashes(arm):
    d = root / arm / 'server' / 'crash-reports'
    return sorted(str(c) for c in d.glob('*')) if d.exists() else []


# ---- A: L0 without the override must refuse to start
srv = server('A', 'L0', [])
deadline = time.time() + 240
while srv.proc.poll() is None and time.time() < deadline:
    time.sleep(0.5)
exited = srv.proc.poll() is not None
if not exited:
    res['arms']['A']['stopRc'] = srv.stop()
else:
    srv._t.join(10); srv.logf.close()
lines = list(srv.lines)
res['arms']['A'].update({'exited': exited, 'rc': srv.proc.returncode, 'refusalLine': next((l for l in lines if REFUSAL in l), None),
                         'done': any('Done (' in l for l in lines), 'crashReports': crashes('A'),
      'failedStart': any('Failed to start the minecraft server' in l for l in lines),
      'preparingLevel': any('Preparing level' in l for l in lines)})
check('A: L0 without the override refuses to start (exits by itself, failed start with the guard message, no world, no Done)',
      exited and res['arms']['A']['failedStart'] and res['arms']['A']['refusalLine'] and not res['arms']['A']['preparingLevel']
      and not res['arms']['A']['done'],
      {k: res['arms']['A'][k] for k in ('exited', 'rc', 'failedStart', 'preparingLevel', 'done')})

# ---- B: L0 with the test override starts and stops cleanly
srv = server('B', 'L0', ['-Dphoenium.allowUnsupportedStack=true'])
try:
    _, warn = srv.wait_for(r'PHOENIUM_STACK unsupported, started only because', 300)
    _, done = srv.wait_for(r'Done \(', 300)
    res['arms']['B'].update({'warn': warn[-220:], 'done': done[-120:]})
finally:
    res['arms']['B']['stopRc'] = srv.stop()
res['arms']['B']['crashReports'] = crashes('B')
check('B: L0 with -Dphoenium.allowUnsupportedStack=true starts, warns and stops cleanly',
      res['arms']['B'].get('warn') and res['arms']['B'].get('done') and res['arms']['B']['stopRc'] == 0 and not res['arms']['B']['crashReports'],
      {'stopRc': res['arms']['B']['stopRc']})

# ---- C: the certified stack is accepted (engine shipping config)
engine = ['-Dphoenium.engine.tiles=true', '-Dphoenium.engine.workers=5', '-Dphoenium.engine.schedule=dag']
srv = server('C', L.FINAL, engine)
try:
    _, ok = srv.wait_for(r'PHOENIUM_STACK supported: c2me \S*phoenium-settlement2', 300)
    _, running = srv.wait_for(r'PHOENIUM_STAGE2 started lifecycle=RUNNING', 900)
    _, done = srv.wait_for(r'Done \(', 300)
    res['arms']['C'].update({'supported': ok[-200:], 'running': running, 'done': done[-120:]})   # full start line (guard1 cut it)
finally:
    res['arms']['C']['stopRc'] = srv.stop()
lines = list(srv.lines)
stop = next((l for l in reversed(lines) if 'PHOENIUM_STAGE2 stopped' in l), '')
traps = {k: int(m.group(1)) for k in TRAPS for m in [re.search(rf'\b{k}=(\d+)', stop)] if m}
bad = [l for l in lines if L.BAD_RE.search(l)]
res['arms']['C'].update({'traps': traps, 'bad': bad[:10], 'crashReports': crashes('C')})
check('C: the certified L5 stack is accepted (supported line, engine RUNNING, Done, clean stop, traps 0, no bad line)',
      res['arms']['C'].get('supported') and 'mode=engine-tiles' in res['arms']['C'].get('running', '') and res['arms']['C'].get('done')
      and res['arms']['C']['stopRc'] == 0 and len(traps) == 3 and not any(traps.values()) and not bad and not res['arms']['C']['crashReports'],
      {'stopRc': res['arms']['C']['stopRc'], 'traps': traps, 'bad': len(bad)})

res['pass'] = len(res['checks']) == 3 and all(c['ok'] for c in res['checks'])
root.mkdir(parents=True, exist_ok=True)
(root / 'stackguard-result.json').write_text(json.dumps(res, indent=2, default=str), encoding='utf-8')
print(json.dumps({'name': name, 'pass': res['pass'], 'checks': [(c['label'][:2], c['ok']) for c in res['checks']]}, indent=1))
sys.exit(0 if res['pass'] else 1)
