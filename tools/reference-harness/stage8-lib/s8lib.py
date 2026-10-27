"""Shared helpers for the Stage 8 runners: hashing, a fresh-server builder, a console-driven server process, RCON, log scans."""
from __future__ import annotations

import csv, datetime, hashlib, json, os, pathlib, re, shutil, socket, struct, subprocess, sys, threading, time

ROOT = pathlib.Path(r'C:\Users\Admin\Downloads\Project PHOENIUM')
EV = ROOT / '.audit/stage8-compat-20261007'
JARS = EV / 'deps/jars'
LOCK = EV / 'deps/STAGE8_DEPENDENCY_LOCK.json'
TEMPLATE = ROOT / '.audit/portal-control-e1f80b7-20260921-200926'   # pinned static-input template (PINNED_PROFILE_MANIFEST)
PINNED = ROOT / '.audit/container-close-admission-20260922/PINNED_PROFILE_MANIFEST.json'
CANDIDATE = ROOT / '.audit/stage7b-vehicle-move-fence-20261007/candidate/phoenium-stage7b-21ae1b5a23da.jar'
CANDIDATE_SHA = '21AE1B5A23DAEC691F30CF7B3CF32EC55F5FD3DF78A4A5E7AF3B61D77E1D406C'
# A Stage 8 fix candidate (new SHA, per the brief's fix procedure) is selected explicitly for a whole run with
# S8_CANDIDATE=<hashed jar>; its SHA is computed from the bytes and recorded by every result file.
if os.environ.get('S8_CANDIDATE'):
    CANDIDATE = pathlib.Path(os.environ['S8_CANDIDATE'])
    CANDIDATE_SHA = hashlib.sha256(CANDIDATE.read_bytes()).hexdigest().upper()
PATCHED_C2ME = ROOT / '.audit/stage7b-vehicle-move-fence-20261007/bedrock-server-21ae1b5a23da/mods/c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar'
PATCHED_C2ME_SHA = '8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53'
JAVA = r'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe'
STAGE8_CONFIG = EV / 'template-config'    # Stage 8 config overlay (Via/SVC/NCR), hashed in STAGE8_CONFIG_LOCK.json

BAD_PATTERNS = ['Mixin apply failed', 'InvalidMixinException', 'InjectionError', 'MixinTransformerError', 'NoSuchMethodError',
                'NoSuchFieldError', 'VerifyError', 'OutOfMemoryError', 'Watchdog', 'ownership violation', r'transaction.*violation',
                'ClassNotFoundException', 'NoClassDefFoundError', 'IncompatibleClassChangeError', 'AbstractMethodError',
                'Critical injection failure', 'injection failure', 'Mixin transformation of', 'Unable to apply mixin']
BAD_RE = re.compile('|'.join(BAD_PATTERNS), re.I)
# Stage 7A-dispositioned exact refusal text (observer allow-list). Counted, never treated as new.
KNOWN_FENCE = 'item-use packet arrived during player dimension transition'


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def sha(p, algo='sha256'):
    h = hashlib.new(algo)
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest().upper()


def lock():
    return {a['slug']: a for a in json.loads(LOCK.read_text())['artifacts']}


def free_port(port, udp=False):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM if udp else socket.SOCK_STREAM)
    try:
        s.bind(('127.0.0.1', port)); return True
    except OSError:
        return False
    finally:
        s.close()


# ----------------------------------------------------------------------------------------------- server construction
STACK_LAYERS = {
    'L0': ['fabric-api'],
    'L1': ['fabric-api', 'c2me-patched', 'lithium', 'scalablelux', 'servercore', 'clumps', 'chunky'],
    'L2': ['fabric-api', 'c2me-patched', 'lithium', 'scalablelux', 'servercore', 'clumps', 'chunky', 'geyser', 'floodgate'],
    'L3': ['fabric-api', 'c2me-patched', 'lithium', 'scalablelux', 'servercore', 'clumps', 'chunky', 'geyser', 'floodgate',
           'viafabric', 'viaversion', 'viabackwards'],
    'L4': ['fabric-api', 'c2me-patched', 'lithium', 'scalablelux', 'servercore', 'clumps', 'chunky', 'geyser', 'floodgate',
           'viafabric', 'viaversion', 'viabackwards', 'simple-voice-chat'],
    'L5': ['fabric-api', 'c2me-patched', 'lithium', 'scalablelux', 'servercore', 'clumps', 'chunky', 'geyser', 'floodgate',
           'viafabric', 'viaversion', 'viabackwards', 'simple-voice-chat', 'no-chat-reports'],
}
FINAL = 'L5'


def mod_source(key):
    if key == 'c2me-patched':
        assert sha(PATCHED_C2ME) == PATCHED_C2ME_SHA
        return PATCHED_C2ME, PATCHED_C2ME_SHA
    a = lock()[key]
    if a['side'] in ('client-only', 'reference'):
        raise SystemExit(f'{key} is {a["side"]}: never installed on the dedicated server')
    p = JARS / a['file']
    assert sha(p) == a['sha256'], key
    return p, a['sha256']


def build_server(server: pathlib.Path, mods: list[str], ports: dict, level: str, *, extra_props=None, candidate=CANDIDATE,
                 candidate_sha=CANDIDATE_SHA, extra_jars=()):
    """Fresh server directory: static inputs copied from the pinned template, pinned config hashes verified, Stage 8 config
    overlay applied, exactly one Phoenium JAR, fresh world (blank seed)."""
    if server.exists():
        raise SystemExit(f'server path exists (immutable runs): {server}')
    pinned = json.loads(PINNED.read_text(encoding='utf-8-sig'))
    for n, h in pinned['configFilesSha256'].items():
        assert sha(TEMPLATE / 'config' / n) == h, n
    server.mkdir(parents=True)
    for n in ('libraries', 'versions'):
        shutil.copytree(TEMPLATE / n, server / n)
    shutil.copytree(TEMPLATE / 'config', server / 'config')
    if STAGE8_CONFIG.exists():
        for f in STAGE8_CONFIG.rglob('*'):
            if f.is_file():
                d = server / 'config' / f.relative_to(STAGE8_CONFIG)
                d.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(f, d)
    for n in ('fabric-server-launch.jar', 'eula.txt'):
        shutil.copy2(TEMPLATE / n, server / n)
    (server / 'mods').mkdir()
    installed = {}
    for k in mods:
        p, h = mod_source(k)
        shutil.copy2(p, server / 'mods' / p.name)
        installed[p.name] = h
    if candidate is not None:
        assert sha(candidate) == candidate_sha
        name = 'phoenium-stage7b-' + candidate_sha[:12].lower() + '.jar'
        shutil.copy2(candidate, server / 'mods' / name)
        installed[name] = candidate_sha
    for p in extra_jars:
        shutil.copy2(p, server / 'mods' / pathlib.Path(p).name)
        installed[pathlib.Path(p).name] = sha(p)
    assert len([n for n in os.listdir(server / 'mods') if n.lower().startswith('phoenium')]) == (1 if candidate else 0)
    props = (TEMPLATE / 'server.properties').read_text().splitlines()
    ov = {'server-port': str(ports['java']), 'level-name': level, 'level-seed': '', 'online-mode': 'false',
          'enforce-secure-profile': 'false', 'server-ip': '127.0.0.1', 'enable-rcon': 'true', 'rcon.port': str(ports['rcon']),
          'rcon.password': 'phoenium-stage8-local-test', 'motd': f'Phoenium Stage 8 {level}', 'max-players': '8',
          'query.port': str(ports['java'])}
    ov.update(extra_props or {})
    out = []
    for line in props:
        k = line.split('=')[0]
        if '=' in line and not line.startswith('#') and k in ov:
            out.append(k + '=' + ov.pop(k))
        else:
            out.append(line)
    out += [k + '=' + v for k, v in ov.items()]
    (server / 'server.properties').write_text('\n'.join(out) + '\n')
    g = server / 'config/Geyser-Fabric/config.yml'
    if 'geyser' in mods:
        t = g.read_text()
        assert '19172' in t
        g.write_text(t.replace('port: 19172', f'port: {ports["bedrock"]}'))
    vc = server / 'config/voicechat/voicechat-server.properties'
    if vc.exists():
        t = vc.read_text()
        t = re.sub(r'(?m)^port=.*$', f'port={ports.get("voice", 24454)}', t)
        vc.write_text(t)
    manifest = {'mods': installed, 'serverProperties': sha(server / 'server.properties'),
                'config': {str(f.relative_to(server / 'config')): sha(f) for f in (server / 'config').rglob('*') if f.is_file()}}
    return manifest


# ----------------------------------------------------------------------------------------------- process + RCON
class Rcon:
    def __init__(self, port, password='phoenium-stage8-local-test'):
        self.port, self.password, self.sock, self.lock = port, password, None, threading.Lock()

    def _pkt(self, rid, typ, body):
        b = body.encode('utf-8') + b'\x00\x00'
        self.sock.sendall(struct.pack('<iii', len(b) + 8, rid, typ) + b)

    def _read(self):
        def exact(n):
            d = b''
            while len(d) < n:
                c = self.sock.recv(n - len(d))
                if not c:
                    raise ConnectionError('rcon closed')
                d += c
            return d
        n = struct.unpack('<i', exact(4))[0]
        d = exact(n)
        rid, typ = struct.unpack('<ii', d[:8])
        return rid, d[8:-2].decode('utf-8', 'replace')

    def connect(self):
        self.sock = socket.create_connection(('127.0.0.1', self.port), timeout=60)
        self._pkt(1, 3, self.password)
        rid, _ = self._read()
        if rid == -1:
            raise RuntimeError('rcon auth failed')

    def __call__(self, cmd):
        with self.lock:
            if self.sock is None:
                self.connect()
            self._pkt(2, 2, cmd)
            rid, body = self._read()
            # long replies are split in 4096-byte packets; drain with a short timeout
            self.sock.settimeout(0.15)
            try:
                while True:
                    _, more = self._read()
                    body += more
            except (socket.timeout, TimeoutError):
                pass
            finally:
                self.sock.settimeout(60)
            return body

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
        self.sock = None


class Server:
    def __init__(self, server: pathlib.Path, log_name: str, jvm=('-Xmx2G',), workers=1, extra_props=()):
        self.dir, self.log_path = server, server.parent / log_name
        self.jvm, self.workers, self.extra = list(jvm), workers, list(extra_props)
        self.proc, self.lines, self._t = None, [], None

    def cmdline(self):
        return [JAVA, *self.jvm, '-Dphoenium.test.diagnostics=true', f'-Dphoenium.gameplay.workers={self.workers}',
                *self.extra, '-Djava.net.useSystemProxies=false', '-Dminecraft.api.session.host=http://127.0.0.1:9',
                '-Dminecraft.api.services.host=http://127.0.0.1:9', '-jar', 'fabric-server-launch.jar', 'nogui']

    def start(self):
        self.logf = open(self.log_path, 'w', encoding='utf-8', errors='replace')
        self.logf.write('# cmd: ' + ' '.join(self.cmdline()) + '\n')
        self.proc = subprocess.Popen(self.cmdline(), cwd=self.dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', bufsize=1,
                                     creationflags=0x4000)  # BELOW_NORMAL_PRIORITY_CLASS keeps the desktop responsive
        self._t = threading.Thread(target=self._pump, daemon=True)
        self._t.start()
        return self

    def _pump(self):
        for line in self.proc.stdout:
            self.lines.append(line.rstrip('\n'))
            self.logf.write(line); self.logf.flush()

    def wait_for(self, pattern, timeout=600, start=0):
        rx = re.compile(pattern)
        t0 = time.time()
        while time.time() - t0 < timeout:
            for i in range(start, len(self.lines)):
                if rx.search(self.lines[i]):
                    return i, self.lines[i]
            if self.proc.poll() is not None:
                raise RuntimeError(f'server exited ({self.proc.returncode}) while waiting for {pattern}')
            time.sleep(0.25)
        raise TimeoutError(pattern)

    def console(self, cmd):
        self.proc.stdin.write(cmd + '\n'); self.proc.stdin.flush()

    def stop(self, timeout=300):
        try:
            self.console('stop')
        except OSError:
            pass
        try:
            rc = self.proc.wait(timeout)
        except subprocess.TimeoutExpired:
            self.proc.kill(); rc = 'KILLED'
        self._t.join(10)
        self.logf.close()
        return rc


def loaded_mods(lines):
    out, inside = {}, False
    for l in lines:
        if re.search(r'Loading \d+ mods:', l):
            inside = True; continue
        if inside:
            m = re.match(r'\s+(?:\|-- |\\-- |- )(\S+) (\S+)', l)
            if m:
                out[m.group(1)] = m.group(2)
            elif not l.startswith('\t') and not l.startswith(' '):
                break
    return out


# Exact diagnostic-only lines excluded from the bad-pattern gate (each documented in the final report):
#  - Mixin's class exporter (-Dmixin.debug.export, used only for the mixin inventory) logs this INFO when the optional Fernflower
#    decompiler is absent; it is not a mixin/linkage failure.
DIAGNOSTIC_ONLY = ['[main/INFO]: Fernflower could not be loaded, exported classes will not be decompiled. NoClassDefFoundError: '
                   'org/jetbrains/java/decompiler/main/extern/IResultSaver']


def scan(lines):
    bad = [l for l in lines if BAD_RE.search(l) and not any(d in l for d in DIAGNOSTIC_ONLY)]
    errs = [l for l in lines if re.search(r'/(ERROR|FATAL)\]', l)]
    warns = [l for l in lines if re.search(r'/WARN\]', l)]
    exc = [l for l in lines if re.search(r'Exception|\tat ', l)]
    fence = sum(1 for l in lines if KNOWN_FENCE in l)
    return {'badPatternLines': bad, 'errorLines': errs, 'warnCount': len(warns), 'warnLines': warns[:400],
            'exceptionLines': exc[:400], 'exceptionLineCount': len(exc), 'knownFenceRefusals': fence}


def norm(l):
    l = re.sub(r'^\[\d\d:\d\d:\d\d\] ', '', l)
    l = re.sub(r'\b\d+(\.\d+)?(ms|s)\b', '<t>', l)
    l = re.sub(r'#\d+|-\d+\]|\d{3,}', '<n>', l)
    return l
