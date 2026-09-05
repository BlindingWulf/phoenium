"""Boot the exact built JAR on disposable Fabric and inspect its actual transformed FULL boundary.

This is transformation/baseline regression evidence. Authoritative FULL remains disabled.
No accounts, client login, profile requests, persistent world edits or production settings are used.
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
PREPARED = ROOT / "build/stage3-client-server"
TARGET = ROOT / "run/full-production-integration" / time.strftime("transform-%Y%m%d-%H%M%S")
TARGET.mkdir(parents=True, exist_ok=False)
for name in ("fabric-server-launch.jar", ".fabric", "libraries", "versions", "mods"):
    source = PREPARED / name
    if source.is_dir():
        shutil.copytree(source, TARGET / name)
    else:
        shutil.copy2(source, TARGET / name)
artifact = next((TARGET / "mods").glob("phoenium-*.jar"))
built = ROOT / "build/libs" / artifact.name
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest().upper()
assert digest(artifact) == digest(built), "prepared server has a stale production artifact"
(TARGET / "eula.txt").write_text("eula=true\n")
(TARGET / "server.properties").write_text("\n".join([
    "level-name=boundary-transform", "level-seed=", "online-mode=false",
    "enforce-secure-profile=false", "server-ip=127.0.0.1", "server-port=25585",
    "enable-rcon=false", "max-players=1", "difficulty=peaceful", "gamemode=creative",
    "view-distance=5", "simulation-distance=5", "spawn-protection=0",
    "sync-chunk-writes=true", "pause-when-empty-seconds=-1", ""]))
command = [shutil.which("java"), "-Xmx2G", "-Dmixin.debug.export=true", "-Dmixin.debug.countInjections=true",
           "-Dphoenium.test.chunkBindingTrace=true", "-Dphoenium.test.diagnostics=true",
           "-jar", "fabric-server-launch.jar", "nogui"]
log = TARGET / "server-process.log"
manifest = {"kind": "transformed boundary; baseline mode", "productionFullEnabled": False,
            "directory": str(TARGET), "jarSha256": digest(artifact),
            "modules": {path.name: digest(path) for path in (TARGET / "mods").glob("*.jar")},
            "command": command}
print(str(TARGET), flush=True)
with log.open("w") as output:
    process = subprocess.Popen(command, cwd=TARGET, stdin=subprocess.PIPE, stdout=output,
                               stderr=subprocess.STDOUT, text=True,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    try:
        deadline = time.monotonic() + 150
        while "Done (" not in log.read_text(errors="replace"):
            if process.poll() is not None:
                raise AssertionError("server exited before startup")
            if time.monotonic() > deadline:
                raise AssertionError("server startup timeout")
            time.sleep(0.25)
        process.stdin.write("seed\nphoenium diagnostics\nstop\n")
        process.stdin.flush()
        process.wait(timeout=60)
        assert process.returncode == 0, process.returncode
        exported = TARGET / ".mixin.out/class/net/minecraft/world/level/chunk/status/ChunkStatusTasks.class"
        assert exported.exists(), "actual transformed class was not exported"
        inspected = subprocess.run([shutil.which("javap"), "-p", "-c", str(exported)],
                                   capture_output=True, text=True, check=True)
        (TARGET / "ChunkStatusTasks.javap.txt").write_text(inspected.stdout)
        parts = re.split(r"\n  (?:public|private|protected|static)", inspected.stdout)
        prep = next(part for part in parts if "phoenium$prepareFull(" in part)
        finish = next(part for part in parts if "phoenium$finishFull(" in part)
        assert "runPostLoad" not in prep and "setLoaded" not in prep and "onChunkLoad" not in prep
        assert prep.count("replaceProtoChunk:") == 1 and prep.count("setFullStatus:") == 1
        assert finish.count("runPostLoad:") == 1 and finish.count("setLoaded:") == 1
        assert len(re.findall(r'onChunkLoad"?:', finish)) == 1, "installed Fabric return callback was not retained exactly once"
        assert "replaceProtoChunk:" not in finish
        text = log.read_text(errors="replace")
        stopped = [line for line in text.splitlines() if "PHOENIUM_CHUNK_BINDING STOPPED" in line]
        assert stopped, "missing real cleanup accounting"
        for line in stopped:
            for name in ("retainedGraphs", "returnedIndex", "poiIndex", "custodyEntries", "workEntries", "lifecycleEntries"):
                assert re.search(r"\b" + name + r"=0\b", line), line
        manifest.update({"result": "PASS", "exitCode": process.returncode, "stopped": stopped,
                         "seedLines": [line for line in text.splitlines() if "Seed:" in line],
                         "transformedClassSha256": digest(exported)})
    except BaseException as failure:
        manifest.update({"result": "FAIL", "failure": repr(failure)})
        raise
    finally:
        if process.poll() is None:
            process.stdin.write("stop\n"); process.stdin.flush()
            try:
                process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()
        manifest["exitCode"] = process.returncode
        manifest["logSha256"] = digest(log)
        (TARGET / "acceptance.json").write_text(json.dumps(manifest, indent=2))
print(json.dumps(manifest, indent=2), flush=True)
