"""One bounded existing-client-ladder attempt (at most two same-seed lifecycles).

Run after the baseline build and prepareStage3ClientServer. Uses only a disposable
server copy. This is diagnostic reproduction, never fresh-seed FULL acceptance.
The original failed save is unavailable; preserve that limitation in the result.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "build/stage3-client-server"
OUTPUT = ROOT / "run/nonself-input-decision" / time.strftime("ticking-%Y%m%d-%H%M%S")
SERVER = OUTPUT / "server"
SERVER.mkdir(parents=True, exist_ok=False)
for name in ("fabric-server-launch.jar", ".fabric", "libraries", "versions", "mods"):
    source = SOURCE / name
    if source.is_dir():
        shutil.copytree(source, SERVER / name)
    elif source.exists():
        shutil.copy2(source, SERVER / name)
artifact = next((SERVER / "mods").glob("phoenium-*.jar"))
artifact_hash = hashlib.sha256(artifact.read_bytes()).hexdigest().upper()
built = ROOT / "build/libs" / artifact.name
assert artifact_hash == hashlib.sha256(built.read_bytes()).hexdigest().upper(), "stale prepared server artifact"
environment = os.environ.copy()
environment["PHOENIUM_DIAGNOSTIC_SEED"] = "-663839099974294500"
environment["PHOENIUM_DIAGNOSTIC_SERVER_DIR"] = str(SERVER)
print(str(OUTPUT), flush=True)
with (OUTPUT / "gradle.log").open("w") as log:
    # No shell-built file operations; Windows batch wrapper executes only this fixed build task.
    completed = subprocess.run([str(ROOT / "gradlew.bat"), "runStage3ClientGameTest", "--no-daemon"],
                               cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT,
                               timeout=600, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
for source in (ROOT / "build/client-gametest/evidence/STAGE3-CLIENT.json",
               ROOT / "build/client-gametest/evidence/STAGE3-CLIENT-world-seeds.txt",
               ROOT / "run/stage3-client-gametest/logs/latest.log"):
    if source.exists():
        shutil.copy2(source, OUTPUT / source.name)
text = (SERVER / "server-process.log").read_text(errors="replace")
manifest = {"seed": environment["PHOENIUM_DIAGNOSTIC_SEED"], "artifactSha256": artifact_hash,
            "originalReproducerArtifactSha256": "46E37BBE91CBCCD958656DA125ADC10ABCCA6DC090A2D0DE86E4BD79C6E7ACC2",
            "exitCode": completed.returncode, "priorSaveAvailable": False,
            "limitation": "Regenerated exact seed, same client actions; missing original save and timing.",
            "tickingFailureObserved": "Ticking block entity" in text,
            "productionFull": "DISABLED", "server": str(SERVER)}
(OUTPUT / "manifest.json").write_text(json.dumps(manifest, indent=2))
print(json.dumps(manifest, indent=2), flush=True)
