"""Exercise one preserved vault through the ordinary server ticker on a disposable copy.

This is a bounded server-side diagnostic for the saved-world ticking regression.  It copies the
actual failed reproduction world without modifying it, force-loads one stored FULL vault chunk
through the normal command, and records the original vault ticker/output-neighbor path.  The
script does not turn a no-error run into proof: the manifest reports the observed counters and
clean process outcome separately.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import re

ROOT = Path(__file__).resolve().parents[1]
PREPARED = ROOT / "build/stage3-client-server"
PROBE = ROOT / "build/test-harness/full-live-probe.jar"
PRESERVED_SERVER = ROOT.parent / "Phoenium" / "run/nonself-input-decision/ticking-20260905-122520/server"
PRESERVED_WORLD_NAME = "STAGE3-CLIENT-1-1788629132903"
PRESERVED_WORLD = PRESERVED_SERVER / PRESERVED_WORLD_NAME
OUTPUT = ROOT / "run/nonself-input-decision" / time.strftime("saved-vault-%Y%m%d-%H%M%S")
SERVER = OUTPUT / "server"
WORLD_NAME = "STAGE3-SAVED-VAULT-" + str(int(time.time() * 1000))

assert (PREPARED / "fabric-server-launch.jar").exists(), "prepared server is missing its launcher"
assert PROBE.exists(), "build fullAcceptanceHarness before running this diagnostic"
assert (PRESERVED_WORLD / "level.dat").exists(), "preserved failed world is unavailable"
OUTPUT.mkdir(parents=True, exist_ok=False)
SERVER.mkdir()
for name in ("fabric-server-launch.jar", ".fabric", "libraries", "versions", "mods"):
    source = PREPARED / name
    if source.is_dir():
        if name == "mods":
            shutil.copytree(source, SERVER / name, ignore=shutil.ignore_patterns("full-live-probe.jar"))
        else:
            shutil.copytree(source, SERVER / name)
    else:
        shutil.copy2(source, SERVER / name)
shutil.copy2(PROBE, SERVER / "mods/full-live-probe.jar")
shutil.copytree(PRESERVED_WORLD, SERVER / WORLD_NAME)

artifact = next((SERVER / "mods").glob("phoenium-*.jar"))
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest().upper()
built = ROOT / "build/libs" / artifact.name
assert digest(artifact) == digest(built), "prepared server has a stale production artifact"
(SERVER / "eula.txt").write_text("eula=true\n")
(SERVER / "server.properties").write_text("\n".join([
    "level-name=" + WORLD_NAME, "level-seed=", "online-mode=false",
    "enforce-secure-profile=false", "server-ip=127.0.0.1", "server-port=25565",
    "enable-rcon=false", "max-players=1", "difficulty=peaceful", "gamemode=creative",
    "view-distance=5", "simulation-distance=5", "spawn-protection=0",
    "sync-chunk-writes=true", "pause-when-empty-seconds=-1", "spawn-animals=false",
    "spawn-npcs=false", ""]))

java = str(Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java.exe") if os.name == "nt" else "java"
if os.name == "nt" and not Path(java).exists():
    java = shutil.which("java")
command = [java, "-Xmx2G", "-Dphoenium.stage3.explicitTopology=true",
           "-Dphoenium.test.vaultDiagnostic=true", "-Dphoenium.test.diagnostics=true",
           "-jar", "fabric-server-launch.jar", "nogui"]
log = SERVER / "server-process.log"
manifest = {
    "kind": "preserved saved-world vault ticker diagnostic",
    "sourceWorld": str(PRESERVED_WORLD), "world": WORLD_NAME,
    "seed": -663839099974294500, "artifactSha256": digest(artifact),
    "originalFailedArtifactSha256": "1E368F022797143C6948C7A0DEBEC429C0D233D870DCFABB5CAF846C880F9F22",
    "vaultChunk": [142, -31], "vaultPosition": [2272, 7, -496],
    "sourceWorldUntouched": True, "command": command,
}
print(str(OUTPUT), flush=True)
process = None
failure = None
try:
    with log.open("w") as output:
        process = subprocess.Popen(command, cwd=SERVER, stdin=subprocess.PIPE, stdout=output,
                                   stderr=subprocess.STDOUT, text=True,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        def issue(command_line):
            if process.poll() is not None:
                return
            try:
                process.stdin.write(command_line + "\n")
                process.stdin.flush()
            except BrokenPipeError:
                pass
        deadline = time.monotonic() + 150
        while True:
            current_log = log.read_text(errors="replace")
            if "Done (" in current_log or process.poll() is not None:
                break
            if time.monotonic() > deadline:
                raise AssertionError("server startup timeout")
            time.sleep(0.25)
        if process.poll() is None:
            issue("forceload add 2272 -496 2287 -481")
            time.sleep(30)
            issue("phoenium vaultprobe")
            time.sleep(2)
            issue("stop")
        try:
            process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
except BaseException as exception:
    failure = repr(exception)
finally:
    if process is not None and process.poll() is None:
        process.kill()
        process.wait()

text = log.read_text(errors="replace") if log.exists() else ""
summary_lines = [line for line in text.splitlines() if "PHOENIUM_VAULT_DIAGNOSTICS" in line]
summary = summary_lines[-1] if summary_lines else ""
counter_values = {key: int(value) for key, value in re.findall(r"\b(\w+)=(\d+)\b", summary)}
cleanup_lines = [line for line in text.splitlines() if "PHOENIUM_CHUNK_BINDING STOPPED" in line]
zero_barriers = any(re.search(r"\bbarriers=0\b", line) for line in cleanup_lines)
zero_cleanup = bool(cleanup_lines) and all(
    re.search(r"\b%s=0\b" % key, line) for line in cleanup_lines for key in
    ("retainedGraphs", "returnedIndex", "poiIndex", "custodyEntries", "workEntries", "lifecycleEntries"))
manifest.update({
    "exitCode": None if process is None else process.returncode,
    "failure": failure,
    "forceLoadSuccess": "Marked chunk [142, -31] in minecraft:overworld to be force loaded" in text,
    "vaultTickObserved": "PHOENIUM_VAULT_TICK" in text,
    "vaultNeighborObserved": "PHOENIUM_VAULT_NEIGHBOR" in text,
    "diagnosticSummary": summary_lines,
    "vaultCounters": counter_values,
    "positiveChosenVault": counter_values.get("dirtyCompleted", 0) > 0
        and counter_values.get("crossChunkReads", 0) > 0
        and counter_values.get("ownerScopes", 0) > 0
        and counter_values.get("ordinaryScopes", 0) > 0,
    "tickingFailureObserved": "Ticking block entity" in text,
    "cleanupLines": cleanup_lines,
    "zeroBarriers": zero_barriers,
    "zeroCleanupResidue": zero_cleanup,
    "cleanShutdown": process is not None and process.returncode == 0 and "Stopping the server" in text
        and "Encountered an unexpected exception" not in text and "Exception stopping the server" not in text
        and zero_barriers and zero_cleanup,
    "serverLog": str(log),
})
(OUTPUT / "manifest.json").write_text(json.dumps(manifest, indent=2))
print(json.dumps(manifest, indent=2), flush=True)
if failure is not None:
    raise SystemExit(failure)
