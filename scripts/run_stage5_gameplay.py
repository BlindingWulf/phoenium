"""Bounded installed native furnace campaign. New disposable output required each run."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import threading
import time
import psutil
import platform
from run_stage4_acceptance import copy_server, sha256, capture_thread_dump, snapshot_mca

FROZEN_STAGE4 = "43AAC30184E5C77784D7A56590AA23A87DC86438684EB2B2F2577A197F4DD81E"
FROZEN_STAGE3 = "017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--production", type=Path, default=Path("build/libs/phoenium-0.2.0-beta.1.jar"))
    parser.add_argument("--prepared", type=Path, default=Path("build/stage3-client-server"))
    parser.add_argument("--probe", type=Path, default=Path("build/test-harness/stage4-acceptance-probe.jar"))
    parser.add_argument("--workers", type=int, choices=[1, 2, 4, 8], default=2)
    parser.add_argument("--callbacks-per-region", type=int, choices=range(1, 257), default=64)
    parser.add_argument("--reference", action="store_true")
    parser.add_argument("--layout", choices=["distributed", "concentrated"], default="distributed")
    parser.add_argument("--furnaces-per-region", type=int, choices=range(1, 257), default=64)
    parser.add_argument("--warmup", type=int, default=40)
    parser.add_argument("--samples", type=int, choices=range(1, 11), default=2)
    parser.add_argument("--ticks", type=int, default=80)
    parser.add_argument("--distance", type=int, choices=[5, 8], default=5)
    parser.add_argument("--trace", action="store_true")
    parser.add_argument("--callback-trace", action="store_true")
    parser.add_argument("--adverse", action="store_true")
    parser.add_argument("--churn", action="store_true", help="merge, replace actual native incarnations, split and reroute queued inputs before each sample")
    parser.add_argument("--port", type=int, default=25625)
    args = parser.parse_args()
    if args.churn and (args.layout != "distributed" or args.samples > 6 or args.adverse):
        raise SystemExit("churn requires distributed layout, at most six samples, and healthy mode")
    output = args.output.resolve()
    if output.exists():
        raise SystemExit(f"Refusing existing output: {output}")
    expected = sha256(args.production)
    if args.reference:
        if expected != FROZEN_STAGE4 or args.workers != 1:
            raise SystemExit("Stage 5 reference requires exact frozen Stage 4 and one worker")
    elif expected in (FROZEN_STAGE3, FROZEN_STAGE4):
        raise SystemExit("Candidate mode rejects either immutable reference artifact")
    output.mkdir(parents=True)
    server = output / "server"
    copy_server(args.prepared, server, args.production, args.probe, [])
    if args.adverse:
        if args.reference or args.workers < 2: raise ValueError("adverse campaign requires parallel candidate")
        shutil.copy2(Path("build/test-harness/stage4-adverse-stop-probe.jar"), server / "mods/stage4-adverse-stop-probe.jar")
    # Stage 4's copy helper preserves the prepared registration probe. This separate
    # workload uses only its explicitly selected probe and pinned Fabric libraries.
    for pattern in ("full-registration*.jar", "full-live-probe*.jar"):
        for jar in (server / "mods").glob(pattern):
            jar.unlink()
    evidence = output / "evidence"
    evidence.mkdir()
    world = "stage5-disposable"
    (server / "eula.txt").write_text("eula=true\n")
    (server / "server.properties").write_text("\n".join([
        f"level-name={world}", "level-seed=444444", "level-type=minecraft:flat",
        "online-mode=false", "enforce-secure-profile=false", "server-ip=127.0.0.1",
        f"server-port={args.port}", "enable-rcon=false", "max-players=1", "difficulty=peaceful",
        "gamemode=creative", f"view-distance={args.distance}", f"simulation-distance={args.distance}",
        "spawn-protection=0", "sync-chunk-writes=true", "pause-when-empty-seconds=-1", ""]))
    installed = server / "mods/phoenium-0.2.0-stage2.jar"
    identity = {"expectedSha256": expected, "installedSha256": sha256(installed),
                "referenceStage": 4 if args.reference else None,
                "sourceHead": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                "sourceStatus": subprocess.check_output(["git", "status", "--porcelain"], text=True),
                "probeSha256": sha256(args.probe), "workers": args.workers}
    identity["pass"] = identity["expectedSha256"] == identity["installedSha256"]
    (evidence / "production-identity-first.json").write_text(json.dumps(identity, indent=2))
    if not identity["pass"]:
        raise SystemExit("Installed production identity mismatch")
    java = shutil.which("java")
    command = [java, "-Xmx2G", "-Xlog:gc*:file=gc.log:time,uptime,level,tags",
        "-Dphoenium.full.authoritative=true", "-Dphoenium.test.diagnostics=true",
        "-Dphoenium.stage3.explicitTopology=true", "-Dphoenium.test.chunkFixtureSupport=true",
        "-Dphoenium.stage3.chunkTrace=true", "-Dphoenium.stage4.candidate=true",
        "-Dphoenium.stage4.viewDistance=" + str(args.distance),
        "-Dphoenium.stage4.simulationDistance=" + str(args.distance),
        "-Dphoenium.stage5.probe=true", "-Dphoenium.stage5.evidence=" + str(evidence),
        "-Dphoenium.gameplay.workers=" + str(args.workers),
        "-Dphoenium.gameplay.callbacksPerRegion=" + str(args.callbacks_per_region),
        "-Dphoenium.gameplay.trace=" + str(args.trace).lower(),
        "-Dphoenium.stage5.callbackTrace=" + str(args.callback_trace).lower(),
        "-Dphoenium.stage5.adverse=" + str(args.adverse).lower(),
        "-jar", "fabric-server-launch.jar", "nogui"]
    (output / "launch.json").write_text(json.dumps(command, indent=2))
    lines = queue.Queue()
    seen = []
    started = time.monotonic()
    process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    log_path = output / "server.log"
    metrics = []
    metrics_done = threading.Event()
    def sample_process():
        actual = psutil.Process(process.pid)
        while not metrics_done.is_set():
            try:
                memory = actual.memory_info()
                cpu = actual.cpu_times()
                metrics.append({"atSeconds": time.monotonic() - started, "rssBytes": memory.rss,
                    "peakWorkingSetBytes": getattr(memory, "peak_wset", None),
                    "cpuSeconds": cpu.user + cpu.system, "threads": actual.num_threads()})
            except psutil.NoSuchProcess:
                break
            metrics_done.wait(0.1)
    sampler = threading.Thread(target=sample_process, daemon=True)
    sampler.start()
    def reader():
        with log_path.open("w", encoding="utf-8") as log:
            for line in process.stdout:
                log.write(line); log.flush(); lines.put(line)
    threading.Thread(target=reader, daemon=True).start()
    commands = []
    def send(value):
        commands.append({"atSeconds": time.monotonic()-started, "command": value})
        process.stdin.write(value + "\n"); process.stdin.flush()
    def wait(token, timeout=120, expected_failure=False):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                line = lines.get(timeout=0.2); seen.append(line)
                if token in line:
                    print(line.strip(), flush=True); return
                if not expected_failure and ("state=FAILED" in line or "scheduler=FAILED" in line or "Encountered an unexpected exception" in line):
                    raise RuntimeError("Current artifact server failure: " + line.strip())
            except queue.Empty:
                if process.poll() is not None:
                    raise RuntimeError(f"Server exited {process.returncode} before {token}")
        raise TimeoutError("Missing real server receipt: " + token)
    report = {"status": "FAIL", "identity": identity, "command": command}
    try:
        wait("Done (")
        send("stage5 demand"); wait("STAGE5_READY")
        send("stage5 prepare " + str(args.furnaces_per_region)); wait("STAGE5_PREPARED")
        send("stage5 " + ("split" if args.layout == "distributed" else "merge")); wait("STAGE5_TOPOLOGY")
        send(f"stage5 measure warmup {args.warmup}"); wait("STAGE5_MEASURED label=warmup", 240)
        if args.adverse:
            # Finish ordinary generation/unload saves before the probe selects its
            # one deliberately held native write. The probe still requires zero
            # prior custody and exactly one newly retained attempt when armed.
            send("save-all flush"); wait("Saved the game")
            send("stage5 adverse"); wait("PHOENIUM_ADVERSE_STOP ARMED")
            send("stage5 measure expected-failure 10")
            wait("PHOENIUM_ADVERSE_SAVE_SHUTDOWN PASS",90,True); process.wait(timeout=20)
            native_log = log_path.read_text(encoding="utf-8")
            if "Watching Server" in native_log or "server will forcibly shutdown" in native_log:
                raise RuntimeError("adverse cleanup required a watchdog termination")
            if native_log.count("STAGE5_ADVERSE_NATIVE_FAILURE") != 1 or native_log.count("STAGE5_ADVERSE_NATIVE_PEER_RETURN") != 1:
                raise RuntimeError("adverse original-body overlap was incomplete")
            report["status"] = "PASS_ADVERSE_ONLY"
            return
        for sample in range(args.samples):
            if args.churn:
                send("stage5 churn"); wait("STAGE5_CHURN round=" + str(sample + 1))
            label = f"sample-{sample+1}"
            send(f"stage5 measure {label} {args.ticks}"); wait("STAGE5_MEASURED label=" + label, 240)
        send("stage5 dirty"); wait("STAGE5_DIRTY")
        save_started = time.monotonic()
        send("save-all flush"); wait("Saved the game")
        report["nativeSaveReceiptSeconds"] = time.monotonic() - save_started
        send("stage5 snapshot final"); wait("STAGE5_SNAPSHOT label=final")
        stop_started = time.monotonic()
        send("stop"); process.wait(timeout=90)
        report["nativeStopSeconds"] = time.monotonic() - stop_started
        if process.returncode != 0:
            raise RuntimeError(f"Native stop failed: exit {process.returncode}")
        report["mca"] = snapshot_mca(server / world, evidence / "mca-before-restart")
        final = json.loads((evidence / "final.json").read_text())
        if final["health"]["barrierTripped"] or final["health"]["ownershipViolations"]:
            raise RuntimeError("Native health rejected final snapshot")
        if len(final["furnaces"]) != 4 * args.furnaces_per_region:
            raise RuntimeError("Incomplete native furnace coverage")
        report["status"] = "PASS_NATIVE_WORKLOAD_ONLY"
    except Exception as failure:
        report["failure"] = repr(failure)
        if process.poll() is None:
            report["threadDump"] = capture_thread_dump(server, process, "stage5-failure")
            try:
                send("stop"); process.wait(timeout=60)
            except Exception:
                process.kill(); process.wait(timeout=10)
                report["forcedTermination"] = True
    finally:
        metrics_done.set(); sampler.join(timeout=2)
        (output / "process-metrics.json").write_text(json.dumps({"sampleIntervalMillis": 100,
            "samples": metrics, "logicalCpuCount": psutil.cpu_count(), "physicalCpuCount": psutil.cpu_count(logical=False),
            "hostPhysicalMemoryBytes": psutil.virtual_memory().total,
            "platform": platform.platform(), "processor": platform.processor()}, indent=2))
        report.update({"exitCode": process.returncode, "elapsedSeconds": time.monotonic()-started,
            "commands": commands, "serverLog": str(log_path)})
        (output / "report.json").write_text(json.dumps(report, indent=2))
        print(json.dumps({key: report.get(key) for key in ("status", "failure", "exitCode", "elapsedSeconds")}), flush=True)
    if report["status"] not in ("PASS_NATIVE_WORKLOAD_ONLY", "PASS_ADVERSE_ONLY"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
