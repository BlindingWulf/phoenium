"""Lightweight dragon post-restart witness.

Starts a copy of an already-saved installed-run server (the world is a real copy, everything else is hard-linked),
force-loads the End fight chunk and reads the dragon's position a handful of times. No client, no sampler thread: a few
RCON reads spaced seconds apart. Passes when the dragon exists, occupies at least three distinct positions, and the
transaction trace and server log show no ownership violation or chunk-protocol failure.

usage: dragon_witness.py <new-name> <source-run-name> <workers 1|4>
"""
from __future__ import annotations

import json
import os
import pathlib
import shutil
import socket
import struct
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from campaign_paths import AUDIT

JAVA = r'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe'
PASSWORD = 'phoenium-stage7-local-test'
JAVA_PORT, RCON_PORT, BEDROCK_PORT = 25686, 25786, 19186


def rcon(command):
    with socket.create_connection(('127.0.0.1', RCON_PORT), timeout=2) as s:
        s.settimeout(60)

        def packet(i, t, body):
            b = body.encode()
            s.sendall(struct.pack('<iii', len(b) + 10, i, t) + b + b'\0\0')

        def read():
            def n(count):
                b = b''
                while len(b) < count:
                    part = s.recv(count - len(b))
                    if not part: raise EOFError('RCON closed')
                    b += part
                return b
            b = n(struct.unpack('<i', n(4))[0])
            if struct.unpack('<i', b[:4])[0] < 0: raise RuntimeError('RCON authentication failed')
            return b[8:-2].decode(errors='replace')
        packet(1, 3, PASSWORD); read(); packet(2, 2, command); return read()


def main():
    name, source_name, workers = sys.argv[1], sys.argv[2], sys.argv[3]
    source = AUDIT / 'installed' / source_name / 'server'
    run = AUDIT / 'installed' / name
    server = run / 'server'
    if run.exists(): raise RuntimeError('namespace exists: ' + str(run))
    world = next(p for p in source.iterdir() if p.is_dir() and p.name.startswith('STAGE7-'))
    shutil.copytree(source, server, ignore=shutil.ignore_patterns('logs', 'crash-reports'),
                    copy_function=lambda s, d: shutil.copy2(s, d) if world in pathlib.Path(s).parents else os.link(s, d))
    (server / 'world.lock').unlink(missing_ok=True)
    (server / world.name / 'session.lock').unlink(missing_ok=True)
    props = (server / 'server.properties').read_text().splitlines()
    overrides = {'server-port': str(JAVA_PORT), 'rcon.port': str(RCON_PORT)}
    props = [k + '=' + overrides[k] if (k := line.split('=')[0]) in overrides else line for line in props]
    (server / 'server.properties').write_text('\n'.join(props) + '\n')
    geyser = server / 'config/Geyser-Fabric/config.yml'
    geyser.write_text(geyser.read_text().replace('19184', str(BEDROCK_PORT)))
    console = run / 'server-console.log'
    command = [JAVA, '-Xmx2G', '-Dphoenium.test.diagnostics=true', '-Dphoenium.gameplay.workers=' + workers,
               '-Djava.net.useSystemProxies=false', '-Dminecraft.api.session.host=http://127.0.0.1:9',
               '-Dminecraft.api.services.host=http://127.0.0.1:9', '-jar', 'fabric-server-launch.jar', 'nogui']
    with console.open('w') as out:
        proc = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT)
    result = dict(name=name, source=source_name, workers=int(workers), samples=[], pass_=False)
    try:
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline and 'Done (' not in console.read_text(errors='replace'): time.sleep(1)
        if 'Done (' not in console.read_text(errors='replace'): raise RuntimeError('server did not start')
        end = 'execute in minecraft:the_end run '
        result['forceload'] = rcon(end + 'forceload add -64 -64 64 64')
        time.sleep(15)
        for i in range(5):
            time.sleep(6)
            pos = rcon(end + 'data get entity @e[type=minecraft:ender_dragon,limit=1] Pos')
            phase = rcon(end + 'data get entity @e[type=minecraft:ender_dragon,limit=1] DragonPhase')
            result['samples'].append(dict(t=round(time.monotonic() - deadline, 1), pos=pos, phase=phase))
        result['tx'] = rcon('stage7-tx status')
    finally:
        try: rcon('stop')
        except Exception: pass
        try: proc.wait(timeout=120)
        except subprocess.TimeoutExpired: proc.kill()
    log = console.read_text(errors='replace')
    positions = {s['pos'] for s in result['samples'] if 'entity' not in s['pos'].lower() or 'has the following' in s['pos']}
    result['distinctPositions'] = len(positions)
    result['badLog'] = [l for l in log.splitlines() if 'ChunkProtocolException' in l or 'ownership violation' in l.lower()
                        or 'Encountered an unexpected exception' in l]
    summary = [l for l in log.splitlines() if 'STAGE7_PORTAL_TX_SUMMARY' in l]
    result['txSummary'] = summary[-1] if summary else None
    result['exit'] = proc.returncode
    result['pass_'] = (result['distinctPositions'] >= 3 and not result['badLog'] and proc.returncode == 0
                       and bool(summary) and '"violations":0' in summary[-1])
    (run / 'result.json').write_text(json.dumps(result, indent=1))
    print(json.dumps({k: result[k] for k in ('distinctPositions', 'badLog', 'exit', 'pass_')}))
    return 0 if result['pass_'] else 1


if __name__ == '__main__':
    sys.exit(main())
