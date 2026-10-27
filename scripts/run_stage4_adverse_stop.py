"""Run the existing native pending-write/failure probe against the exact built production JAR."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
PREPARED = ROOT / "build/stage3-client-server"
TARGET = ROOT / "run/stage4" / time.strftime("adverse-final-%Y%m%d-%H%M%S")
PROBE = ROOT / "build/test-harness/stage4-adverse-stop-probe.jar"
PRODUCTION = ROOT / "build/libs/phoenium-0.2.0-beta.1.jar"
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest().upper()

assert PROBE.is_file() and PRODUCTION.is_file(), "build production and adverseStopHarness first"
TARGET.mkdir(parents=True, exist_ok=False)
for name in ("fabric-server-launch.jar", ".fabric", "libraries", "versions"):
    source = PREPARED / name
    if source.is_dir():
        shutil.copytree(source, TARGET / name)
    else:
        shutil.copy2(source, TARGET / name)
(TARGET / "mods").mkdir()
for source in (PREPARED / "mods").glob("fabric-*.jar"):
    shutil.copy2(source, TARGET / "mods" / source.name)
shutil.copy2(PRODUCTION, TARGET / "mods" / PRODUCTION.name)
shutil.copy2(PROBE, TARGET / "mods" / PROBE.name)
assert digest(TARGET / "mods" / PRODUCTION.name) == digest(PRODUCTION), "wrong installed production"
(TARGET / "eula.txt").write_text("eula=true\n")
(TARGET / "server.properties").write_text("\n".join([
    "level-name=adverse-native", "level-seed=444444", "level-type=minecraft:flat",
    "online-mode=false", "enforce-secure-profile=false", "server-ip=127.0.0.1",
    "server-port=25586", "enable-rcon=false", "max-players=1", "difficulty=peaceful",
    "gamemode=creative", "view-distance=5", "simulation-distance=5", "spawn-protection=0",
    "sync-chunk-writes=true", "pause-when-empty-seconds=-1", ""]))
java = shutil.which("java")
command = [java, "-Xmx2G", "-Dphoenium.test.diagnostics=true", "-jar", "fabric-server-launch.jar", "nogui"]
log = TARGET / "server-process.log"
manifest = {"kind": "stage4-adverse-native-save-shutdown", "directory": str(TARGET),
    "productionSha256": digest(PRODUCTION), "probeSha256": digest(PROBE), "command": command,
    "modules": {path.name: digest(path) for path in (TARGET / "mods").glob("*.jar")},
    "log": str(log), "durablePersistenceClaim": False, "result": "PARTIAL"}
manifest_path = TARGET / "acceptance-manifest.json"
manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
print(str(TARGET), flush=True)
with log.open("w") as output:
    process = subprocess.Popen(command, cwd=TARGET, stdout=output, stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    try:
        process.wait(timeout=150)
        text = log.read_text(errors="replace")
        manifest.update(exitCode=process.returncode, logSha256=digest(log),
            armed=text.count("PHOENIUM_ADVERSE_STOP ARMED"),
            passMarkers=text.count("PHOENIUM_ADVERSE_SAVE_SHUTDOWN PASS"),
            failMarkers=text.count("PHOENIUM_ADVERSE_SAVE_SHUTDOWN FAIL"))
        assert manifest["armed"] == 1 and manifest["passMarkers"] == 1 and manifest["failMarkers"] == 0, \
            "native adverse probe did not prove exact pending-write, acknowledgement and cleanup gates"
        manifest["result"] = "PASS"
    except BaseException as failure:
        manifest["failure"] = str(failure)
        raise
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=15)
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
print(json.dumps(manifest, indent=2))
