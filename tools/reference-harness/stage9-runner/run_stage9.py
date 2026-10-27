"""Stage 9 benchmark runner: one authoritative run = one stack x one scenario x one bot tier.

    python tools/stage9/run_stage9.py --stack A-generic --scenario idle --tier 0 --warmup 60 --measure 120

Every run gets a unique run id and an immutable directory under .audit/stage9-20261009/runs/<run-id>/ containing
metadata.json, server.log, telemetry-raw/, cpu.jsonl, bots.jsonl, jvm/{server.jfr,gc.log,jcmd-*.txt}, summary.{json,csv}.
Receipts are written atomically; a crashed run keeps whatever evidence it produced plus an ABORTED marker."""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import os
import pathlib
import platform
import re
import subprocess
import sys
import threading
import time
import traceback

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import psutil  # noqa: E402

from lib import analyze, jfrtop, sampler, stack  # noqa: E402
from lib.rcon import Rcon  # noqa: E402
from lib.server import Server  # noqa: E402
from scenarios import SCENARIOS, SETUP_REJECTED  # noqa: E402

AUDIT = stack.ROOT / ".audit" / "stage9-20261009"
PORTS = {"java": 25750, "rcon": 25850, "bedrock": 19250, "voice": 24550, "proxy": 25751}
NODE = "node"
VIAPROXY = AUDIT / "harness" / "viaproxy" / "vendor" / "v3.4.14" / "ViaProxy-3.4.14.jar"
VIAPROXY_SHA = "2894BBFB2F4342F8FDE887AF6C689313EFD5B0E46A8006775EEA0E21E2948AB5"


def utc() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def write_json(path: pathlib.Path, data: dict) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, indent=2, default=str), encoding="utf-8")
    os.replace(tmp, path)


def git_head(path: pathlib.Path) -> str | None:
    try:
        out = subprocess.run(["git", "-C", str(path), "rev-parse", "HEAD"], capture_output=True, text=True, timeout=10)
        dirty = subprocess.run(["git", "-C", str(path), "status", "--porcelain"], capture_output=True, text=True, timeout=10)
        return out.stdout.strip() + ("+dirty" if dirty.stdout.strip() else "")
    except OSError:
        return None


def other_servers() -> list[dict]:
    found = []
    for p in psutil.process_iter(["pid", "name", "cmdline"]):
        try:
            if not (p.info["name"] or "").lower().startswith("java"):
                continue
            cmd = " ".join(p.info["cmdline"] or [])
        except (psutil.AccessDenied, psutil.NoSuchProcess):
            continue
        if "fabric-server-launch.jar" in cmd or ("server.jar" in cmd and "nogui" in cmd):
            found.append({"pid": p.info["pid"], "cmd": cmd[:200]})
    return found


def prune_static(server_dir: pathlib.Path, install: dict) -> dict:
    """After a finished run, delete only the static inputs this runner copied in (pinned, hash-recorded in metadata):
    libraries/, versions/, .fabric/ (derived cache) and the mod jars. Logs, config, world and telemetry stay."""
    import shutil
    removed = {}
    for name in ("libraries", "versions", ".fabric"):
        target = server_dir / name
        if target.is_dir():
            removed[name] = sum(f.stat().st_size for f in target.rglob("*") if f.is_file())
            shutil.rmtree(target)
    for jar, digest in install["mods"].items():
        path = server_dir / "mods" / jar
        if path.is_file() and stack.sha256(path) == digest:
            removed[f"mods/{jar}"] = path.stat().st_size
            path.unlink()
    launcher = server_dir / "fabric-server-launch.jar"
    if launcher.is_file():
        removed["fabric-server-launch.jar"] = launcher.stat().st_size
        launcher.unlink()
    return {"removedBytes": sum(removed.values()), "removed": sorted(removed)}


def preflight(args, ports: dict) -> dict:
    report: dict = {"at": utc()}
    busy = [f"{k}:{v}" for k, v in ports.items() if sampler.port_in_use(v, udp=k in ("bedrock", "voice"))]
    report["portsBusy"] = busy
    report["otherServers"] = other_servers()
    report["tcpStates"] = sampler.tcp_state_counts()
    psutil.cpu_percent(None)
    idle = [psutil.cpu_percent(1.0) for _ in range(5)]
    report["hostIdleCpuPct"] = round(sum(idle) / len(idle), 1)
    report["hostAvailMB"] = round(psutil.virtual_memory().available / 2**20)
    problems = []
    if busy:
        problems.append(f"ports busy: {busy}")
    if report["otherServers"] and not args.allow_concurrent:
        problems.append(f"another Minecraft server is running: {report['otherServers']}")
    if report["hostIdleCpuPct"] > args.max_idle_cpu and not args.allow_concurrent:
        problems.append(f"host not idle: {report['hostIdleCpuPct']}% > {args.max_idle_cpu}%")
    if report["tcpStates"].get("TIME_WAIT", 0) > 1000:
        problems.append(f"TIME_WAIT pressure: {report['tcpStates']['TIME_WAIT']}")
    report["problems"] = problems
    return report


class Controller:
    """Bot controller subprocess. Protocol: it appends JSONL events to bots.jsonl; on stdout it prints
    `S9CTL joined <n>` when the join phase is over and `S9CTL done` at exit; on stdin it accepts `teardown`."""

    def __init__(self, cmd: list[str], run_dir: pathlib.Path, log_name: str = "bot-controller.log"):
        self.log = open(run_dir / log_name, "w", encoding="utf-8", errors="replace")
        self.proc = subprocess.Popen(cmd, cwd=HERE, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
        self.joined = threading.Event()
        self.joined_count = 0
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self) -> None:
        assert self.proc.stdout is not None
        for line in self.proc.stdout:
            self.log.write(line)
            self.log.flush()
            match = re.match(r"S9CTL joined (\d+)", line)
            if match:
                self.joined_count = int(match.group(1))
                self.joined.set()

    def teardown(self, timeout: float = 120) -> int | str:
        try:
            assert self.proc.stdin is not None
            self.proc.stdin.write("teardown\n")
            self.proc.stdin.flush()
        except OSError:
            pass
        try:
            return self.proc.wait(timeout)
        except subprocess.TimeoutExpired:
            for child in psutil.Process(self.proc.pid).children(recursive=True):
                child.kill()
            self.proc.kill()
            return "KILLED"
        finally:
            self.log.close()


def run(args) -> int:
    scenario = SCENARIOS[args.scenario]
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    run_id = args.run_id or f"{stamp}-{args.stack}-{args.scenario}-t{args.tier}-s{args.seed}"
    run_dir = AUDIT / "runs" / run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    (run_dir / "RUNNING").write_text(utc())
    meta: dict = {"schema": "stage9-run-v1", "runId": run_id, "createdAt": utc(), "args": vars(args),
                  "stack": args.stack, "scenario": args.scenario, "scenarioDef": scenario, "tier": args.tier,
                  "seed": args.seed, "ports": PORTS, "harnessCommit": git_head(HERE),
                  "host": {"platform": platform.platform(), "cpu": platform.processor(), "logicalCpus": psutil.cpu_count(),
                           "physicalCpus": psutil.cpu_count(logical=False), "ramMB": round(psutil.virtual_memory().total / 2**20)},
                  "verdict": None}
    pre = preflight(args, PORTS)
    meta["preflight"] = pre
    write_json(run_dir / "metadata.json", meta)
    if pre["problems"]:
        (run_dir / "ABORTED").write_text("preflight: " + "; ".join(pre["problems"]))
        (run_dir / "RUNNING").unlink()
        print("PREFLIGHT FAILED:", pre["problems"])
        return 2

    server_dir = run_dir / "server"
    level = f"S9-{run_id[-40:]}"
    meta["install"] = stack.build(args.stack, server_dir, PORTS, level, synthetic_bedrock=scenario.get("bedrock", False),
                                  props=scenario.get("props"))
    if args.fixture:
        fixture = pathlib.Path(args.fixture)
        import shutil
        shutil.copytree(fixture / "world", server_dir / level)
        meta["fixture"] = json.loads((fixture / "FIXTURE.json").read_text())
    write_json(run_dir / "metadata.json", meta)

    sysprops = dict(stack.STACKS[args.stack].get("sysprops", {}))
    if scenario.get("bedrock"):
        # SYNTHETIC_OFFLINE_BEDROCK lane only (H4): every synthetic client is 127.0.0.1, and Geyser's per-address RakNet
        # throttle (10 connections, 3 attempts per 4 s) would cap the lane at 10. Test server only; never production.
        sysprops["Geyser.RakRateLimitingDisabled"] = "true"
    srv = Server(server_dir, run_dir, heap=args.heap, xms=args.xms, sysprops=sysprops, extra_jvm=args.jvm_arg,
                 jfr=not args.no_jfr)
    smp = sampler.Sampler(run_dir / "cpu.jsonl")
    ctl: Controller | None = None
    bctl: Controller | None = None
    proxy: subprocess.Popen | None = None
    rcon: Rcon | None = None
    phases: dict = {"serverStart": time.time() * 1000}
    crashed = False
    try:
        srv.start()
        smp.watch("server", srv.pid)
        smp.start()
        srv.wait_for(r"Done \(", timeout=900)
        phases["done"] = time.time() * 1000
        if "geyser" in stack.STACKS[args.stack]["mods"]:
            srv.wait_for(r"Run /geyser help", timeout=300)      # Stage 8: connecting before this hangs the handshake
        if stack.STACKS[args.stack]["phoenium"]:
            _, line = srv.wait_for(r"PHOENIUM_STAGE2 started lifecycle=RUNNING", timeout=120)
            meta["phoeniumStart"] = re.search(r"lifecycle=\S+ scheduler=\S+ workers=\d+ .*? parallel=\S+.*", line).group(0).strip()
        rcon = Rcon("127.0.0.1", PORTS["rcon"], "stage9-local-test").connect()
        for command in scenario.get("setup", []):
            reply = rcon.command(command.format(seed=args.seed, tier=args.tier))
            meta.setdefault("setupReplies", []).append({"cmd": command, "reply": reply[:200]})
            if scenario.get("setupCheck") and re.search(SETUP_REJECTED, reply):   # workload v2 (HARNESS-1)
                raise RuntimeError(f"setup command rejected: {command!r} -> {reply[:160]!r}")
        phases["setupDone"] = time.time() * 1000
        if args.tier > 0 and scenario.get("proxy"):
            proxy_dir = run_dir / "viaproxy"
            proxy_dir.mkdir()
            if stack.sha256(VIAPROXY) != VIAPROXY_SHA:
                raise SystemExit("ViaProxy jar hash mismatch")
            proxy_log = open(run_dir / "viaproxy.log", "w", encoding="utf-8", errors="replace")
            proxy = subprocess.Popen([srv.cmd[0], "-Xmx2G", "-jar", str(VIAPROXY), "cli",
                                      "--bind-address", f"127.0.0.1:{PORTS['proxy']}",
                                      "--target-address", f"127.0.0.1:{PORTS['java']}", "--target-version", "26.2",
                                      "--auth-method", "NONE", "--proxy-online-mode", "false", "--chat-signing", "false",
                                      "--log-ips", "false"], cwd=proxy_dir, stdout=proxy_log, stderr=subprocess.STDOUT)
            meta["proxy"] = {"jar": VIAPROXY.name, "sha256": VIAPROXY_SHA, "clientVersion": scenario["botVersion"],
                             "serverVersion": "26.2", "note": "client-side translation on the load generator"}
            for _ in range(120):
                if sampler.port_in_use(PORTS["proxy"]):
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("ViaProxy did not start listening")
            smp.watch("proxy", proxy.pid)
        bedrock_n = round(args.tier * scenario.get("bedrockShare", 0)) if args.tier > 0 else 0
        meta["editions"] = {"java": args.tier - bedrock_n, "bedrockSynthetic": bedrock_n}
        if args.tier > 0:
            config = {"host": "127.0.0.1", "port": PORTS["proxy"] if scenario.get("proxy") else PORTS["java"], "bedrockPort": PORTS["bedrock"], "rconPort": PORTS["rcon"],
                      "rconPassword": "stage9-local-test", "tier": args.tier - bedrock_n, "seed": args.seed, "scenario": args.scenario,
                      "params": scenario.get("params", {}), "out": str(run_dir / "bots.jsonl"),
                      "version": scenario.get("botVersion", args.bot_version), "joinIntervalMs": args.join_interval_ms}
            write_json(run_dir / "controller-config.json", config)
            ctl = Controller([NODE, str(HERE / "adapters" / scenario["controller"]), str(run_dir / "controller-config.json")],
                             run_dir)
            smp.watch("bots", ctl.proc.pid)
            if bedrock_n:
                write_json(run_dir / "controller-config-bedrock.json", {**config, "tier": bedrock_n})
                bctl = Controller([NODE, str(HERE / "adapters" / scenario["bedrockController"]), str(run_dir / "controller-config-bedrock.json")],
                                  run_dir, "bot-controller-bedrock.log")
                smp.watch("bedrockBots", bctl.proc.pid)
            join_budget = 120 + args.tier * args.join_interval_ms / 1000 * 2
            if not ctl.joined.wait(join_budget):
                meta["joinPhase"] = f"controller did not report joined within {join_budget:.0f}s"
            if bctl is not None and not bctl.joined.wait(max(30.0, join_budget - (time.time() * 1000 - phases["setupDone"]) / 1000)):
                meta["bedrockJoinPhase"] = "bedrock controller did not report joined in time"
            phases["joined"] = time.time() * 1000
        warmup_start = time.time()
        meta["jcmdWarmupStart"] = srv.jcmd("VM.flags").strip()[:4000]
        time.sleep(args.warmup)
        phases["measureStart"] = time.time() * 1000
        engine_marks = rcon is not None and stack.STACKS[args.stack].get("sysprops", {}).get("phoenium.engine.tiles") == "true"
        if engine_marks:  # S9-E8: the engine counters at the window edges (server.log), diffed by analyze.engine_window
            meta["markStart"] = rcon.command("phoenium mark measureStart")[:120]
        if rcon is not None:  # entity census at the window edges (every stack: equal work, analyze.census)
            meta["censusStart"] = analyze.census(rcon.command)
        (run_dir / "jvm" / "jcmd-measure-start-codecache.txt").write_text(srv.jcmd("Compiler.codecache"))
        deadline = time.time() + args.measure
        while time.time() < deadline:
            if srv.proc.poll() is not None:
                crashed = True
                break
            time.sleep(1)
        phases["measureEnd"] = time.time() * 1000
        if not crashed and engine_marks:
            try:
                meta["markEnd"] = rcon.command("phoenium mark measureEnd")[:120]
            except Exception as error:  # noqa: BLE001  (diagnostic only)
                meta["markEnd"] = f"rcon failed: {error}"[:200]
        if not crashed and rcon is not None:
            meta["censusEnd"] = analyze.census(rcon.command)
        if not crashed:
            (run_dir / "jvm" / "jcmd-measure-end-codecache.txt").write_text(srv.jcmd("Compiler.codecache"))
            (run_dir / "jvm" / "jcmd-measure-end-heap.txt").write_text(srv.jcmd("GC.heap_info"))
            (run_dir / "jvm" / "jcmd-vm-flags.txt").write_text(srv.jcmd("VM.flags", "-all"))
            # live-object histogram (forces a full GC) strictly AFTER measureEnd, so no gated metric sees the pause
            try:
                histogram = srv.jcmd("GC.class_histogram", timeout=180).splitlines()
                (run_dir / "jvm" / "jcmd-measure-end-histogram.txt").write_text("\n".join(histogram[:120]) + "\n")
            except Exception as error:  # noqa: BLE001  (diagnostic only; never fails a run)
                meta["histogramError"] = str(error)[:300]
            meta["warmupS"] = round(phases["measureStart"] / 1000 - warmup_start, 1)
    except Exception as error:  # evidence is kept; the verdict says why
        crashed = True
        meta["exception"] = "".join(traceback.format_exception(error))[-4000:]
    finally:
        if ctl is not None:
            meta["controllerExit"] = ctl.teardown()
        if bctl is not None:
            meta["bedrockControllerExit"] = bctl.teardown()
        if proxy is not None:
            proxy.terminate()
            try:
                meta["proxyExit"] = proxy.wait(30)
            except subprocess.TimeoutExpired:
                proxy.kill()
                meta["proxyExit"] = "KILLED"
        phases["teardownDone"] = time.time() * 1000
        if rcon is not None:
            try:
                meta["finalList"] = rcon.command("list")[:300]
            except Exception as error:  # noqa: BLE001
                meta["finalList"] = f"rcon failed: {error}"
            meta["rconCommands"] = rcon.commands_sent
            rcon.close()
        if srv.proc is not None and srv.proc.poll() is None:
            meta["stop"] = srv.stop()
        else:
            meta["stop"] = {"exitCode": srv.proc.returncode if srv.proc else None, "alreadyExited": True}
            crashed = True
        smp.stop()
        phases["end"] = time.time() * 1000
        meta["phases"] = phases
        stopped = [l for l in srv.lines if "PHOENIUM_STAGE2 stopped" in l]
        if stopped:
            m = re.search(r"ownershipViolations=(\d+) unleased=(\d+)", stopped[-1])
            engine = analyze.engine_stop(stopped[-1])
            if m:
                meta["phoeniumStop"] = {"ownershipViolations": int(m.group(1)), "unleased": int(m.group(2))}
            else:
                meta["phoeniumStop"] = engine if engine is not None else stopped[-1][:300]
        meta["badLogLines"] = srv.bad[:50]
        meta["tcpStatesAfter"] = sampler.tcp_state_counts()
        orphans = [p.pid for p in psutil.process_iter() if p.pid in (srv.pid, ctl.proc.pid if ctl else -1, bctl.proc.pid if bctl else -1,
                                                                      proxy.pid if proxy else -1)]
        meta["orphans"] = orphans
        write_json(run_dir / "phases.json", phases)
        write_json(run_dir / "metadata.json", meta)

    window = (phases.get("measureStart", phases["end"]), phases.get("measureEnd", phases["end"]))
    summary: dict = {"runId": run_id, "stack": args.stack, "scenario": args.scenario, "tier": args.tier, "seed": args.seed}
    try:
        summary["ticks"] = analyze.tick_metrics(run_dir, window)
    except Exception as error:  # noqa: BLE001
        summary["ticks"] = {"ticks": 0, "error": str(error)}
    try:
        summary["gc"] = analyze.gc_metrics(run_dir, window)
    except Exception as error:  # noqa: BLE001
        summary["gc"] = {"available": False, "reason": str(error)}
    summary["process"] = analyze.process_metrics(run_dir, window)
    try:
        if (run_dir / "jvm" / "server.jfr").exists() and "measureStart" in phases:
            server_thread = jfrtop.hotspots(run_dir).get("Server thread", {})
            summary["hotspots"] = {"serverThreadSamples": server_thread.get("samples", 0),
                                   "inclusiveTop": server_thread.get("inclusive", [])[:12],
                                   "selfTop": server_thread.get("self", [])[:8]}
    except Exception as error:  # noqa: BLE001
        summary["hotspots"] = {"error": str(error)}
    try:  # whole-recording allocation views (jfr view is fast; runs after teardown, never during measurement)
        if (run_dir / "jvm" / "server.jfr").exists():
            views = [subprocess.run([analyze.JFR, "view", "--width", "200", view, str(run_dir / "jvm" / "server.jfr")],
                                    capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=300).stdout
                     for view in ("allocation-by-class", "allocation-by-thread", "allocation-by-site")]
            (run_dir / "jvm" / "allocation-views.txt").write_text("\n".join(views))
    except Exception as error:  # noqa: BLE001
        meta["allocationViewsError"] = str(error)[:300]
    summary["bots"] = analyze.bot_metrics(run_dir, args.tier)
    try:  # S9-E8: the measure window's engine counters (two phoenium marks); None before D26
        workers = stack.STACKS[args.stack].get("sysprops", {}).get("phoenium.engine.workers")
        summary["engineWindow"] = analyze.engine_window(run_dir, int(workers) if workers else None)
    except Exception as error:  # noqa: BLE001
        summary["engineWindow"] = {"error": str(error)[:300]}
    ownership_bad = isinstance(meta.get("phoeniumStop"), dict) and (meta["phoeniumStop"].get("ownershipViolations") or 0) > 0
    engine_stop = meta.get("phoeniumStop") if isinstance(meta.get("phoeniumStop"), dict) and "traps" in meta["phoeniumStop"] else None
    traps_bad = [f"Phoenium engine traps {engine_stop['traps']} > 0 (load/write/storage)"] if engine_stop and engine_stop["traps"] else []
    traps_bad += [f"Phoenium engine self-check faults {engine_stop['indexFaults']} > 0 (tracker index, spawn memo, protection memberships)"] \
        if engine_stop and engine_stop.get("indexFaults") else []
    bad = srv.bad + (["Phoenium ownershipViolations > 0"] if ownership_bad else []) + traps_bad
    summary["verdict"] = analyze.verdicts(args.tier, summary["ticks"], summary["gc"], summary["process"], summary["bots"],
                                          bad, crashed or bool(meta.get("exception")), args.controller_cores_limit)
    summary["stop"] = meta.get("stop")
    if stack.STACKS[args.stack]["phoenium"]:
        summary["phoenium"] = {"start": meta.get("phoeniumStart"), "stop": meta.get("phoeniumStop")}
    write_json(run_dir / "summary.json", summary)
    flat = {"runId": run_id, "stack": args.stack, "scenario": args.scenario, "tier": args.tier, "seed": args.seed,
            **{f"ticks.{k}": v for k, v in summary["ticks"].items() if not isinstance(v, (dict, list))},
            **{f"gc.{k}": v for k, v in summary["gc"].items() if not isinstance(v, (dict, list))},
            **({f"phoenium.{k}": engine_stop.get(k) for k in (*analyze.ENGINE_TRAPS, "traps", *analyze.ENGINE_SERVICES,
                                                                *analyze.ENGINE_TRACKER, *analyze.ENGINE_MEMO, "indexFaults")} if engine_stop else {}),
            **analyze.efficiency(args.tier, summary["process"], summary["gc"]),
            **analyze.census_columns(meta),
            **({f"win.{k}": v for k, v in summary["engineWindow"].items() if not isinstance(v, (dict, list))}
               if isinstance(summary.get("engineWindow"), dict) else {}),
            "correctness": summary["verdict"]["CORRECTNESS"], "performance": summary["verdict"]["PERFORMANCE"],
            "harness": summary["verdict"]["HARNESS_HEALTH"]}
    with open(run_dir / "summary.csv", "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(flat))
        writer.writeheader()
        writer.writerow(flat)
    meta["verdict"] = summary["verdict"]
    if not args.keep_server and not meta.get("orphans"):
        meta["pruned"] = prune_static(server_dir, meta["install"])
    write_json(run_dir / "metadata.json", meta)
    (run_dir / "RUNNING").unlink(missing_ok=True)
    (run_dir / "COMPLETE").write_text(utc())
    print(json.dumps({"runId": run_id, "verdict": summary["verdict"], "ticks": summary["ticks"]}, indent=1))
    return 0


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--stack", required=True, choices=sorted(stack.STACKS))
    p.add_argument("--scenario", required=True, choices=sorted(SCENARIOS))
    p.add_argument("--tier", type=int, default=0)
    p.add_argument("--seed", type=int, default=9001)
    p.add_argument("--warmup", type=int, default=120)
    p.add_argument("--measure", type=int, default=300)
    p.add_argument("--heap", default="4G")
    p.add_argument("--xms", default=None)
    p.add_argument("--jvm-arg", action="append", default=[], help="extra JVM option, e.g. --jvm-arg=-XX:G1HeapRegionSize=8m (repeatable)")
    p.add_argument("--fixture", default=None, help="fixture dir containing world/ and FIXTURE.json")
    p.add_argument("--bot-version", default="26.2")
    p.add_argument("--join-interval-ms", type=int, default=1000)
    p.add_argument("--controller-cores-limit", type=float, default=3.0)
    p.add_argument("--max-idle-cpu", type=float, default=20.0)
    p.add_argument("--allow-concurrent", action="store_true", help="NON-authoritative: skip idle/other-server checks")
    p.add_argument("--no-jfr", action="store_true")
    p.add_argument("--keep-server", action="store_true", help="keep the static server inputs after the run")
    p.add_argument("--run-id", default=None)
    return run(p.parse_args())


if __name__ == "__main__":
    sys.exit(main())
