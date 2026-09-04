"""Isolated production-JAR invalid-demand witness; run after prepareStage3ClientServer.

Uses the existing finite fixture unchanged. The distant EMPTY demand is deliberately
outside that fixture. This is cancellation cleanup evidence, not valid-demand acceptance.
Generated servers and evidence remain ignored under run/terminal-cleanup/.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
prepared = ROOT / "build/stage3-client-server"
target = ROOT / "run/terminal-cleanup" / time.strftime("missing-route-%Y%m%d-%H%M%S")
target.mkdir(parents=True, exist_ok=False)
for name in ("fabric-server-launch.jar", ".fabric", "libraries", "versions", "mods"):
    source = prepared / name
    if source.is_dir():
        shutil.copytree(source, target / name)
    elif source.exists():
        shutil.copy2(source, target / name)
(target / "eula.txt").write_text("eula=true\n")
(target / "server.properties").write_text("\n".join([
    "level-name=invalid-demand", "level-seed=", "online-mode=false",
    "enforce-secure-profile=false", "server-ip=127.0.0.1", "server-port=25585",
    "enable-rcon=false", "max-players=1", "difficulty=peaceful", "gamemode=creative",
    "view-distance=5", "simulation-distance=5", "spawn-protection=0",
    "sync-chunk-writes=true", "pause-when-empty-seconds=-1", ""]))
command = [shutil.which("java"), "-Xmx2G", "-Dphoenium.stage3.explicitTopology=true",
           "-Dphoenium.test.chunkFixtureSupport=true", "-Dphoenium.test.chunkBindingTrace=true",
           "-Dphoenium.test.diagnostics=true", "-Dphoenium.test.missingRouteCleanup=true",
           "-jar", "fabric-server-launch.jar", "nogui"]
log = target / "server-process.log"
def read_log():
    return log.read_text(errors="replace") if log.exists() else ""
def wait_for(predicate, seconds=150):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        text = read_log()
        if predicate(text):
            return text
        if process.poll() is not None:
            raise AssertionError("server exited early: " + text[-3000:])
        time.sleep(0.25)
    raise AssertionError("bounded wait expired: " + read_log()[-3000:])
def send(command):
    process.stdin.write(command + "\n")
    process.stdin.flush()
with log.open("w") as output:
    process = subprocess.Popen(command, cwd=target, stdin=subprocess.PIPE, stdout=output,
                               stderr=subprocess.STDOUT, text=True,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    try:
        wait_for(lambda text: 'Done (' in text)
        send('seed')
        send('phoenium route "minecraft:overworld" 1000 1000')
        send('phoenium diagnostics')
        send('phoenium test-empty-load 1000 1000')
        wait_for(lambda text: 'missingRouteEmpty chunk=1000,1000 done=true unloaded=true' in text)
        send('phoenium diagnostics')
    finally:
        if process.poll() is None:
            send('stop')
            try:
                process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                raise AssertionError("clean shutdown timed out")
text = read_log()
summaries = [line for line in text.splitlines() if "PHOENIUM_CHUNK_BINDING STOPPED" in line]
assert process.returncode == 0, process.returncode
assert summaries, "missing shutdown binding accounting"
assert "reason=CANCELLED" in text
for line in summaries:
    for field in ("retainedGraphs", "returnedIndex", "poiIndex", "synchronousInvocations", "custodyEntries",
                  "lifecycleEntries", "workEntries", "poiEntries", "lightBatches", "lightOccurrences",
                  "lightReceipts", "metadataEntries"):
        match = re.search(r"\b" + field + r"=(\d+)", line)
        assert match and int(match.group(1)) == 0, line
    for field in ("recoveryApplications", "barriers", "duplicateApplications"):
        match = re.search(r"\b" + field + r"=(\d+)", line)
        assert match and int(match.group(1)) == 0, line
assert 'missingRouteEmpty chunk=1000,1000 done=true unloaded=true' in text
assert not re.search(r'PARSED_APPLIED[^\n]*chunk=1000,1000\b', text)
jar = next((target / "mods").glob("phoenium-*.jar"))
manifest = {"kind": "live acceptance: deliberately invalid demand", "productionFullEnabled": False,
            "directory": str(target), "exitCode": process.returncode,
            "cancellations": text.count("reason=CANCELLED"), "stopped": summaries,
            "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "logSha256": hashlib.sha256(log.read_bytes()).hexdigest()}
(target / "acceptance.json").write_text(json.dumps(manifest, indent=2))
print(json.dumps(manifest, indent=2), flush=True)
