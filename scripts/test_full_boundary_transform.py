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
        # The prepared Stage 3 directory also contains the separate authoritative FULL
        # fixture.  This transformation probe deliberately runs baseline/controlled mode,
        # so its server entrypoint must not be installed here.
        if name == "mods":
            shutil.copytree(source, TARGET / name, ignore=shutil.ignore_patterns("full-live-probe.jar"))
        else:
            shutil.copytree(source, TARGET / name)
    else:
        shutil.copy2(source, TARGET / name)
artifact = next((TARGET / "mods").glob("phoenium-*.jar"))
controlled = os.environ.get("PHOENIUM_FULL_REGISTRATION_PROBE") == "1"
if controlled:
    shutil.copy2(ROOT / "build/test-harness/full-registration-probe.jar", TARGET / "mods/full-registration-probe.jar")
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
if controlled:
    manifest["kind"] = "installed production registrar; finite controlled world/generation fixtures"
    manifest["testScopedHarness"] = True
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
        wrapper = next(part for part in parts if " full(" in part)
        assert wrapper.count("FullRegistrationRuntime.registerAsync:") == 1, "missing exact FULL registrar"
        holder_export = TARGET / ".mixin.out/class/net/minecraft/server/level/GenerationChunkHolder.class"
        holder = subprocess.run([shutil.which("javap"), "-p", "-c", str(holder_export)],
                                capture_output=True, text=True, check=True).stdout
        (TARGET / "GenerationChunkHolder.javap.txt").write_text(holder)
        assert "FullRegistrationRuntime.applyWinning:" in holder and "FullRegistrationRuntime.attach:" in holder
        assert "phoenium$generationRefs" in holder and "phoenium$generationSaveSync" in holder
        clock_export = TARGET / ".mixin.out/class/net/minecraft/world/level/storage/PrimaryLevelData.class"
        clock = subprocess.run([shutil.which("javap"), "-p", "-c", str(clock_export)],
                               capture_output=True, text=True, check=True).stdout
        (TARGET / "PrimaryLevelData.javap.txt").write_text(clock)
        assert "FullPreparationClock.beforeWrite:" in clock and "FullPreparationClock.afterWrite:" in clock
        text = log.read_text(errors="replace")
        assert "Encountered an unexpected exception" not in text, "server crashed despite a zero process exit"
        assert "Exception stopping the server" not in text, "server shutdown failed despite a zero process exit"
        assert not list((TARGET / "crash-reports").glob("*.txt")), "server wrote a crash report"
        assert any("Seed:" in line for line in text.splitlines()), "original seed command was not observed"
        if controlled:
            probe = [line for line in text.splitlines() if "PHOENIUM_FULL_REGISTRATION_PROBE PASS" in line]
            assert len(probe) == 1, "selected installed registration fixture did not pass exactly once"
            required_registration_fields = (
                "cases=8", "selectedPreparation=true", "originalGenerationTask=true", "originalHolder=true",
                "productionRegistrar=true", "proto=true", "inMemoryImposter=true", "clockWriter=true",
                "postLoad=0", "publicFull=0", "terminalGraphs=0", "terminalIndexes=0",
                "terminalGenerationHolders=0", "terminalCustody=0", "controlledWorldEndpoints=true",
                "controlledLowerGenerationOutputs=true", "javaagent=false",
            )
            assert all(field in probe[0] for field in required_registration_fields), (
                "installed registration probe omitted an expected branch/counter: " + probe[0]
            )
            manifest["controlledProbe"] = probe
            input_probe = [line for line in text.splitlines() if "PHOENIUM_FULL_INPUT_BINDING_PROBE PASS" in line]
            assert len(input_probe) == 1, "selected production discovery/input consumer did not pass exactly once"
            required_input_fields = (
                "cases=21", "productionDiscovery=true", "exactInputConsumer=true", "proto=true",
                "inMemoryImposter=true", "bothPairOrders=true", "largerWindow=true", "savedWriter=true",
                "actualDemand=true", "capacityToProgress=true", "completeAcknowledgement=true",
                "biomeRollback=true", "inputSatisfied=true", "aggregate=NOT_READY",
                "postLoad=0", "publicFull=0", "terminalInputPlans=0", "terminalInputConsumers=0",
                "javaagent=false",
            )
            assert all(field in input_probe[0] for field in required_input_fields), (
                "installed input probe omitted an expected branch/counter: " + input_probe[0]
            )
            manifest["initializedInputProbe"] = input_probe
            published_probe = [line for line in text.splitlines() if "PHOENIUM_FULL_PUBLISHED_INPUT_PROBE PASS" in line]
            assert len(published_probe) == 1, "original published target/source consumer did not pass exactly once"
            required_published_fields = (
                "cases=18", "targetOriginalFull=true", "exactResultWrapper=true", "baselineOriginHonest=true",
                "proto=true", "inMemoryImposter=true", "diskReload=false", "targetProducerSettled=true",
                "sourceInput=SATISFIED", "sourceAggregate=NOT_READY", "sourceFinishingClaims=0", "sourceSuffix=0",
                "sourcePublicFull=0", "independentConsumers=true", "originalSavePins=true", "originalUnload=true",
                "repeatedCycles=34", "originalPublishedResidentRecordsAfterAck=1",
                "pendingSelectedRecordsAfterAck=2", "totalRecordsAfterAck=3", "usesAfterAck=0",
                "pendingSelectedExecutionsAfterAck=2", "trueTeardownRecords=0", "javaagent=false",
            )
            assert all(field in published_probe[0] for field in required_published_fields), (
                "installed published-input probe omitted an expected branch/counter: " + published_probe[0]
            )
            manifest["publishedInputProbe"] = published_probe
            finishing_probe = [line for line in text.splitlines() if "PHOENIUM_FULL_FINISHING_PROBE PASS" in line]
            assert len(finishing_probe) == 1, "installed FULL finishing fixture did not pass exactly once"
            required_finishing_fields = (
                "cases=42", "queryCases=13", "serviceCases=7", "d1Cases=7", "contextCases=13", "publicationCases=2",
                "originalFinishing=true", "separatePublication=true",
                "cyclicPreparedPair=true", "bothPairOrders=true",
                "proto=true", "inMemoryImposter=true", "diskReload=false",
                "originalDifficulty=true", "originalCollision=true",
                "originalShulkerImmediate=true", "originalLaterRegistration=true",
                "originalMovingPiston=true", "savedVillagerOriginalBiomeCacheAlias=true",
                "originalCachedAbsentBiomeFallback=true", "originalReadyAbsent=true",
                "recursiveStorageContext=true", "retainedBlockNbt=true", "originalAbsentBlockBranch=true",
                "sameWorldSelector=true", "sameWorldEntityNbt=true",
                "caughtQueryBarrier=true", "noPrivateCacheEntries=true",
                "claimedOperationRequired=true", "controlledWorldEndpoints=true",
                "controlledLowerGenerationOutputs=true", "javaagent=false",
            )
            assert all(field in finishing_probe[0] for field in required_finishing_fields), (
                "installed FULL finishing probe omitted an expected branch/counter: " + finishing_probe[0]
            )
            manifest["finishingProbe"] = finishing_probe
        input_exports = {}
        for name, selectors in {
            "world/level/chunk/ProtoChunk": ["phoenium$savedInput", "FullSavedInput.beforeWrite", "FullSavedInput.written"],
            "world/level/chunk/storage/SerializableChunkData": ["FullSavedInput.reload"],
            "world/level/chunk/LevelChunk": ["phoenium$postLoad"],
            "server/level/ChunkMap": ["phoenium$visibleInput", "FullInputNotifications.published", "ChunkHolderLifetime.retired"],
            "server/level/GenerationChunkHolder": ["FullPublishedInputs.proposing", "FullPublishedInputs.completed", "FullPublishedInputs.replacing", "phoenium$lifetime"],
            "server/level/ServerChunkCache": ["phoenium$inputThread"],
            "nbt/CompoundTag": ["phoenium$savedMap"],
            "nbt/ListTag": ["phoenium$savedList"],
        }.items():
            path = TARGET / ".mixin.out/class/net/minecraft" / (name + ".class")
            disassembly = subprocess.run([shutil.which("javap"), "-p", "-c", str(path)], capture_output=True, text=True, check=True).stdout
            for selector in selectors:
                assert selector in disassembly, (name, selector)
            (TARGET / (name.rsplit("/", 1)[-1] + "-inputs.javap.txt")).write_text(disassembly)
            input_exports[name] = digest(path)
        manifest["inputProducerClassHashes"] = input_exports
        stopped = [line for line in text.splitlines() if "PHOENIUM_CHUNK_BINDING STOPPED" in line]
        assert stopped, "missing real cleanup accounting"
        for line in stopped:
            for name in ("retainedGraphs", "returnedIndex", "poiIndex", "custodyEntries", "workEntries", "lifecycleEntries"):
                assert re.search(r"\b" + name + r"=0\b", line), line
        manifest.update({"result": "PASS", "exitCode": process.returncode, "stopped": stopped,
                         "seedLines": [line for line in text.splitlines() if "Seed:" in line],
                         "transformedClassSha256": digest(exported), "holderClassSha256": digest(holder_export),
                         "clockClassSha256": digest(clock_export)})
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
