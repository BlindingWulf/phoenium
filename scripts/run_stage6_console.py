"""Finite, plan-driven local Stage 6 screening; immutable inputs and fresh outputs.

Plans contain first/restart command lists with mandatory literal receipts (or regex).
This driver records observations; its success is never full mod compatibility.
No automatic retry, binary change, assertion relaxation, or forced server kill.
"""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import re
import shutil
import subprocess
import threading
import time
import zipfile

import psutil
from run_stage4_acceptance import capture_thread_dump, inspect_mca, sha256, snapshot_mca

FROZEN = "59BB31DC058351C7F4BC9DB572FEC1DE2B298FF623614FC4CB3EAFAEB5A772D5"
ROOT = Path(__file__).resolve().parents[1]


def inventory(server):
    entries = []
    for path in sorted((server / "mods").glob("*.jar")):
        with zipfile.ZipFile(path) as archive:
            meta = json.loads(archive.read("fabric.mod.json"))
        entries.append(dict(file=path.name, sha256=sha256(path), metadata=meta))
    return entries


def lifecycle(server, output, phase, command, actions, expected, flush_before_stop=True):
    if inventory(server) != expected:
        raise RuntimeError("Installed artifact identity changed before " + phase)
    lines = queue.Queue()
    history = []
    start = time.monotonic()
    report = dict(phase=phase, command=command, commands=[], metrics=[], status="UNRESOLVED")
    process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
        errors="replace", bufsize=1, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    report["pid"] = process.pid
    (output / (phase + "-pid.json")).write_text(json.dumps(dict(pid=process.pid, command=command)))
    complete = threading.Event()

    def read():
        with (output / (phase + ".log")).open("w", encoding="utf-8") as stream:
            for line in process.stdout:
                stream.write(line)
                stream.flush()
                history.append(line.rstrip())
                lines.put(line)

    def sample():
        native = psutil.Process(process.pid)
        while not complete.wait(0.2):
            try:
                cpu = native.cpu_times()
                report["metrics"].append(dict(atSeconds=time.monotonic()-start,
                    rssBytes=native.memory_info().rss, threads=native.num_threads(),
                    cpuSeconds=cpu.user+cpu.system))
            except psutil.NoSuchProcess:
                return

    reader = threading.Thread(target=read, daemon=True)
    sampler = threading.Thread(target=sample, daemon=True)
    reader.start()
    sampler.start()

    def wait_for(receipt, timeout=90, regex=False, readiness_command=None):
        deadline = time.monotonic() + timeout
        next_query = time.monotonic() + 1
        observed = []
        while time.monotonic() < deadline:
            if readiness_command and time.monotonic() >= next_query:
                send(readiness_command)
                next_query = time.monotonic() + 1
            try:
                line = lines.get(timeout=0.1)
            except queue.Empty:
                if process.poll() is not None:
                    raise RuntimeError(f"Server exited {process.returncode} before {receipt}")
                continue
            observed.append(line.rstrip())
            if "<--[HERE]" in line or "Unknown or incomplete command" in line:
                raise RuntimeError("Console rejected command: " + line.strip())
            if "Encountered an unexpected exception" in line or "state=FAILED" in line:
                raise RuntimeError("Server failed: " + line.strip())
            if (re.search(receipt, line) if regex else receipt in line):
                return observed
        raise TimeoutError("Missing server receipt: " + receipt)

    def send(value):
        process.stdin.write(value + "\n")
        process.stdin.flush()

    externally_stopped = False
    try:
        report["startup"] = wait_for("Done (", 120)
        report["startupSeconds"] = time.monotonic() - start
        for action in actions:
            # A unique normal-server marker fences stale console output. The actual
            # requested receipt must follow the command; a marker is not its success.
            if not ("await" in action and action.get("after")):
                marker = "STAGE6_FENCE_" + phase + "_" + str(len(report["commands"]))
                send("say " + marker)
                wait_for(marker)
            item = dict(action=action, atSeconds=time.monotonic()-start)
            report["commands"].append(item)
            if "sendOnly" in action:
                send(action["sendOnly"])
                item["sent"] = True
            elif "await" in action:
                # Observe a real asynchronous receipt even when intervening gameplay
                # commands already consumed it from the console queue.
                prior = next((i for i, line in enumerate(history) if action["after"] in line), None)
                if prior is None:
                    raise RuntimeError("Missing asynchronous start receipt: " + action["after"])
                deadline = time.monotonic() + action.get("timeout", 90)
                while True:
                    observed = list(history[prior + 1:])
                    if any(action["await"] in line for line in observed):
                        item["observed"] = observed
                        break
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError("Missing asynchronous completion receipt: " + action["await"])
                    complete.wait(0.05)
                item["elapsedSeconds"] = time.monotonic()-start-item["atSeconds"]
            elif "capture" in action:
                result = subprocess.run(["jcmd", str(process.pid), *action["capture"]],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=30)
                path = output / (phase + "-" + action["label"] + ".txt")
                path.write_text(result.stdout, encoding="utf-8")
                item.update(exitCode=result.returncode, evidence=str(path))
                if result.returncode:
                    raise RuntimeError("Diagnostic command failed: " + str(action))
            elif action.get("expectExit"):
                send(action["command"])
                deadline = time.monotonic() + action.get("timeout", 90)
                while process.poll() is None and time.monotonic() < deadline:
                    complete.wait(0.05)
                if process.poll() is None:
                    raise TimeoutError("Expected external server stop did not occur: " + str(action))
                if process.returncode != action.get("exitCode", 0):
                    raise RuntimeError("Unexpected external stop exit code: " + str(process.returncode))
                reader.join(timeout=2)
                observed = list(history)
                receipt = action.get("exitReceipt")
                if receipt and not any(receipt in line for line in observed):
                    raise RuntimeError("Missing external-stop receipt: " + receipt)
                item.update(observed=observed, exitCode=process.returncode, externalStop=True)
                externally_stopped = True
                break
            else:
                send(action["command"])
                readiness = action.get("pollReadiness", False)
                if readiness and not re.fullmatch(
                        r"(?:execute if loaded -?\d+ -?\d+ -?\d+|execute unless entity @e\[tag=[A-Za-z0-9_-]+\]) run say [A-Z_]+",
                        action["command"]):
                    raise RuntimeError("Readiness polling accepts only read-only chunk or tagged-entity absence queries")
                item["observed"] = wait_for(action["expect"], action.get("timeout", 90), action.get("regex", False),
                    action["command"] if readiness else None)
                item["elapsedSeconds"] = time.monotonic()-start-item["atSeconds"]
            if action.get("settleSeconds"):
                complete.wait(min(action["settleSeconds"], 30))
        if externally_stopped:
            report["externalStopObserved"] = True
        elif flush_before_stop:
            send("save-all flush")
            report["save"] = wait_for("Saved the game")
            report["threadDump"] = capture_thread_dump(server, process, "stage6-" + phase)
        else:
            report["stopWithOutstandingRequested"] = True
        if not externally_stopped:
            send("stop")
        process.wait(timeout=90)
        if process.returncode != 0:
            raise RuntimeError(f"Nonzero shutdown {process.returncode}")
        reader.join(timeout=2)
        log = (output / (phase + ".log")).read_text(encoding="utf-8")
        if "Encountered an unexpected exception" in log or "Exception stopping the server" in log:
            raise RuntimeError("Server exception recorded despite process exit code zero")
        if any(mod["metadata"]["id"] == "phoenium" for mod in expected):
            stopped = [line for line in log.splitlines() if "PHOENIUM_STAGE2 stopped" in line]
            if len(stopped) != 1 or "lifecycle=STOPPED" not in stopped[0]:
                raise RuntimeError("Missing unique Phoenium stopped receipt")
            for key in ("leases", "duplicate", "bypass", "fallback", "ownershipViolations", "unleased", "barriers"):
                if not re.search(r"\b" + key + r"=0\b", stopped[0]):
                    raise RuntimeError("Phoenium stopped health rejected " + key)
            report["stoppedHealth"] = stopped[0]
            retained_keys = ("graphs", "generationHolders", "structureHolders", "structureWaitingWriters",
                "structureDemandTickets", "returnedIndex", "structureWriters", "structureReaders",
                "expandedOperations", "edges", "plans", "requestedPreparations", "consumers",
                "factBytes", "pendingNotifications", "records", "chargedRecordBytes")
            full = [line for line in log.splitlines() if "PHOENIUM_FULL STOPPED" in line]
            if len(full) != 1:
                raise RuntimeError("Missing unique FULL stopped receipt")
            for key in retained_keys:
                if any(int(value) for value in re.findall(r"\b" + key + r"=(\d+)\b", full[0])):
                    raise RuntimeError("FULL retained state after stop: " + key)
        report["status"] = "PASS_CONSOLE_SAMPLE"
    except Exception as failure:
        report["failure"] = repr(failure)
        if process.poll() is None:
            report["threadDump"] = capture_thread_dump(server, process, "stage6-failed-" + phase)
            try:
                send("stop")
                process.wait(timeout=90)
            except Exception as stop_failure:
                report["shutdownUnsettled"] = repr(stop_failure)
                # Preserve an active write/server for explicit diagnosis, never hide
                # a forced termination behind a successful audit receipt.
    finally:
        complete.set()
        sampler.join(timeout=2)
        reader.join(timeout=2)
        # Preserve the primary server failure even if a later write to its closed
        # console produces an OS-level broken-pipe/invalid-argument exception.
        saved_lines = (output / (phase + ".log")).read_text(encoding="utf-8").splitlines()
        first_failure = next((index for index, line in enumerate(saved_lines) if
            "Encountered an unexpected exception" in line or "Exception stopping the server" in line), None)
        if first_failure is not None:
            report["serverFailureExcerpt"] = saved_lines[first_failure:first_failure + 45]
        report.update(exitCode=process.poll(), elapsedSeconds=time.monotonic()-start)
        (output / (phase + "-report.json")).write_text(json.dumps(report, indent=2))
    print(json.dumps({k: report.get(k) for k in ["phase", "status", "failure", "exitCode", "elapsedSeconds"]}), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--mod", type=Path, action="append", default=[])
    parser.add_argument("--api", type=Path)
    parser.add_argument("--without-phoenium", action="store_true")
    parser.add_argument("--workers", type=int, choices=[1, 4], default=1)
    parser.add_argument("--port", type=int, default=25635)
    parser.add_argument("--production", type=Path, help="Explicit repair candidate; requires --production-sha256")
    parser.add_argument("--production-sha256")
    parser.add_argument("--prepared-server", type=Path, default=ROOT / "build/stage3-client-server")
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text())
    output = args.output.resolve()
    if output.exists():
        raise SystemExit("Refusing existing output " + str(output))
    frozen = ROOT / "run/stage5/final-artifacts/phoenium-0.2.0-stage2.jar"
    if sha256(frozen) != FROZEN:
        raise SystemExit("Frozen production artifact changed")
    if bool(args.production) != bool(args.production_sha256):
        raise SystemExit("Candidate path and expected SHA-256 must be supplied together")
    production = args.production or frozen
    production_hash = args.production_sha256.upper() if args.production else FROZEN
    if sha256(production) != production_hash:
        raise SystemExit("Selected production artifact differs from expected SHA-256")
    output.mkdir(parents=True)
    server = output / "server"
    server.mkdir()
    prepared = args.prepared_server
    for name in ("fabric-server-launch.jar", ".fabric", "libraries", "versions"):
        src = prepared / name
        if src.is_dir():
            shutil.copytree(src, server / name)
        else:
            shutil.copy2(src, server / name)
    (server / "mods").mkdir()
    modules = [args.api] if args.api else list((prepared / "mods").glob("fabric*.jar"))
    for source in modules + args.mod + ([] if args.without_phoenium else [production]):
        target = server / "mods" / source.name
        if target.exists():
            raise RuntimeError("Duplicate artifact filename: " + target.name)
        shutil.copy2(source, target)
        if sha256(source) != sha256(target):
            raise RuntimeError("Copied artifact differs")
    properties = dict({"level-name": "stage6-disposable", "level-seed": "444444",
        "level-type": "minecraft:flat", "online-mode": "false", "enforce-secure-profile": "false",
        "server-ip": "127.0.0.1", "server-port": str(args.port), "enable-rcon": "false",
        "max-players": "2", "difficulty": "peaceful", "gamemode": "creative",
        "view-distance": "5", "simulation-distance": "5", "spawn-protection": "0",
        "sync-chunk-writes": "true", "pause-when-empty-seconds": "-1"}, **plan.get("properties", {}))
    (server / "server.properties").write_text("".join(f"{k}={v}\n" for k, v in properties.items()))
    (server / "eula.txt").write_text("eula=true\n")
    for name, contents in plan.get("configs", {}).items():
        target = (server / name).resolve()
        if not target.is_relative_to(server):
            raise RuntimeError("Config escapes disposable server")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents, encoding="utf-8")
    installed = inventory(server)
    (output / "installed.json").write_text(json.dumps(installed, indent=2))
    (output / "plan.json").write_text(json.dumps(plan, indent=2))
    command = [shutil.which("java"), "-Xmx2G", "-Xlog:gc*:file=gc.log:time,uptime,level,tags",
        "-Dphoenium.full.authoritative=true", "-Dphoenium.test.diagnostics=true",
        "-Dphoenium.stage3.explicitTopology=true", "-Dphoenium.test.chunkFixtureSupport=true",
        "-Dphoenium.stage3.chunkTrace=true", "-Dphoenium.stage4.candidate=true",
        f"-Dphoenium.gameplay.workers={args.workers}", *plan.get("jvm", []),
        "-jar", "fabric-server-launch.jar", "nogui"]
    report = dict(status="UNRESOLVED", planSha256=sha256(args.plan), productionSha256=production_hash,
        phoeniumInstalled=not args.without_phoenium, workers=args.workers,
        sourceHead=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        invocation=__import__("sys").argv, phases=[])
    for phase in ("first", "restart"):
        if phase not in plan:
            continue
        result = lifecycle(server, output, phase, command, plan[phase], installed,
                           flush_before_stop=not plan.get("stopWithOutstanding", False))
        report["phases"].append({k:v for k,v in result.items() if k not in ("metrics", "startup", "commands")})
        if result["status"] != "PASS_CONSOLE_SAMPLE":
            break
        snapshot = output / (phase + "-mca")
        snapshot_mca(server / properties["level-name"], snapshot)
        (output / (phase + "-mca.json")).write_text(json.dumps(inspect_mca(snapshot, plan.get("positions", [])), indent=2))
        if plan.get("requiredFullChunks"):
            from check_stage7_chunky import requested_records
            try:
                verified = requested_records(snapshot, plan["requiredFullChunks"])
            except Exception as failure:
                report["persistenceFailure"] = repr(failure)
                (output / "report.json").write_text(json.dumps(report, indent=2))
                raise
            (output / (phase + "-requested-full.json")).write_text(json.dumps(verified, indent=2))
    else:
        report["status"] = "PASS_CONSOLE_SAMPLE"
    report["controlUnchanged"] = sha256(frozen) == FROZEN
    report["candidateUnchanged"] = sha256(production) == production_hash
    (output / "report.json").write_text(json.dumps(report, indent=2))
    raise SystemExit(0 if report["status"] == "PASS_CONSOLE_SAMPLE" else 1)


if __name__ == "__main__":
    main()
