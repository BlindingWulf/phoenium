"""Stage 9 MEM-2 chunk-retention / GC-recovery experiment (owner directive 2026-10-11). NOT an X1 run and never changes
the X1 workload: a separate driver that reuses the harness (tools/stage9: stack.build, Server, Controller, Sampler,
Rcon) read-only and adds the MEM-2 memory probe jar (gates/memprobe) to the server.

Per cycle (all cycles identical): join (Java bots via ViaProxy + synthetic Bedrock bots, as mixed-v2) -> stress (Bedrock
mover on) -> quiet (mover paused, bots stay) -> leave (every bot disconnects) -> idle (nobody online) -> gc (one
recorded diagnostic full GC: jcmd GC.class_histogram, full list) + jcmd VM.native_memory summary + GC.heap_info.
The full GC is a diagnostic only (it separates reachable live objects from garbage); it is logged in phases.json.

    python memcycle9m.py <out-id> <stack> [--heap 8G] [--cycles 3] [--java 35] [--bedrock 15] [--stress 180]
                         [--quiet 60] [--idle 120] [--pattern tp|walk] [--jvm-arg=-XX:...] [--sysprop k=v]

Outputs: gates/memcycle/<out-id>/ (server.log, jvm/gc.log, jvm/server.jfr, memprobe.jsonl, cpu.jsonl, phases.json,
histo-c<N>.txt, nmt-c<N>-<phase>.txt, result.json). Exit 0 = the experiment ran (it is a measurement, not a gate);
exit 1 = the server died or a phase could not run (result.json says why).
TEST ONLY: offline bots on loopback, synthetic Bedrock with Geyser login validation off and the RakNet throttle off.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import threading
import subprocess
import sys
import time
import traceback

TOOLS = pathlib.Path(r"C:\Users\Admin\Downloads\Project PHOENIUM\tools\stage9")
sys.path.insert(0, str(TOOLS))
import psutil  # noqa: E402
from run_stage9 import Controller, PORTS, NODE, VIAPROXY, VIAPROXY_SHA  # noqa: E402
from scenarios import SCENARIOS, SETUP_REJECTED  # noqa: E402
from lib import stack, sampler  # noqa: E402
from lib.server import Server  # noqa: E402
from lib.rcon import Rcon  # noqa: E402

GATES = pathlib.Path(__file__).resolve().parents[1]
PROBE = GATES / "memprobe" / "build" / "stage9-memprobe-1.0.0.jar"
PROBE_SHA = "43180768D3F738EF12CDA5A76BDF49062370E502953A7718527DEC228C7ECC56"
BEDROCK_CTL = GATES / "bots" / "memcycle_bedrock.js"


def now_ms() -> float:
    return time.time() * 1000


def sha256(p: pathlib.Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest().upper()


class Proc:
    """Server process memory beyond the heap: RSS / private bytes (psutil) every 2 s -> procmem.jsonl."""

    def __init__(self, pid: int, out: pathlib.Path):
        self.p, self.f, self.stop_flag = psutil.Process(pid), open(out, "w", encoding="utf-8"), False
        threading.Thread(target=self.loop, daemon=True).start()

    def loop(self):
        while not self.stop_flag:
            try:
                m = self.p.memory_info()
                row = {"t": now_ms(), "rssMB": round(m.rss / 2**20, 1), "privateMB": round(getattr(m, "private", 0) / 2**20, 1),
                       "vmsMB": round(m.vms / 2**20, 1), "threads": self.p.num_threads()}
                self.f.write(json.dumps(row) + "\n")
                self.f.flush()
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                return
            time.sleep(2)

    def close(self):
        self.stop_flag = True
        time.sleep(0.1)
        self.f.close()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("out_id")
    ap.add_argument("stack", choices=sorted(stack.STACKS))
    ap.add_argument("--heap", default="8G")
    ap.add_argument("--cycles", type=int, default=3)
    ap.add_argument("--java", type=int, default=35)
    ap.add_argument("--bedrock", type=int, default=15)
    ap.add_argument("--stress", type=int, default=180)
    ap.add_argument("--quiet", type=int, default=60)
    ap.add_argument("--idle", type=int, default=120)
    ap.add_argument("--pattern", choices=("tp", "walk"), default="tp")
    ap.add_argument("--jvm-arg", action="append", default=[])
    ap.add_argument("--sysprop", action="append", default=[])
    ap.add_argument("--seed", type=int, default=9001)
    ap.add_argument("--jfr-settings", default="profile", help="JFR settings (profile: allocation/old-object stack traces)")
    ap.add_argument("--stress-histo-cycle", type=int, default=-1,
                    help="cycle whose stress end also takes a recorded diagnostic full-GC histogram (default: the last)")
    ap.add_argument("--c2me", action="append", default=[],
                    help="experiment-only c2me.toml override <section.key>=<toml value>, e.g. chunkSystem.lowMemoryMode=true")
    ap.add_argument("--prop", action="append", default=[], help="experiment-only server.properties override key=value")
    ap.add_argument("--phoenium-jar", default=None,
                    help="replace the stack's Phoenium jar with this registered candidate jar (experiment-only A/B)")
    a = ap.parse_args()

    out = GATES / "memcycle" / a.out_id
    out.mkdir(parents=True, exist_ok=False)
    scen = SCENARIOS["mixed-v2"]
    params = dict(scen["params"])
    if a.pattern == "walk":
        params.update({"pattern": "walk", "walkStep": 6, "walkEveryMs": 1000, "walkRadius": 400})
    result: dict = {"schema": "stage9-memcycle-v1", "id": a.out_id, "args": vars(a), "ports": PORTS,
                    "probe": {"jar": PROBE.name, "sha256": PROBE_SHA}, "phases": [], "problems": []}
    if sha256(PROBE) != PROBE_SHA:
        raise SystemExit("memprobe jar hash mismatch")
    if sampler.port_in_use(PORTS["java"]) or sampler.port_in_use(PORTS["rcon"]):
        raise SystemExit("lead ports busy")

    server_dir = out / "server"
    level = f"S9-{a.out_id[-40:]}"
    props = dict(scen.get("props") or {})
    for kv in a.prop:
        k, v = kv.split("=", 1)
        props[k] = v
    result["install"] = stack.build(a.stack, server_dir, PORTS, level, synthetic_bedrock=True, props=props)
    shutil.copy2(PROBE, server_dir / "mods" / PROBE.name)
    if a.phoenium_jar:
        jar = pathlib.Path(a.phoenium_jar)
        old = [p for p in (server_dir / "mods").iterdir() if p.name.lower().startswith("phoenium")]
        if len(old) != 1:
            raise SystemExit(f"--phoenium-jar needs a stack with exactly one Phoenium jar, found {old}")
        old[0].unlink()
        shutil.copy2(jar, server_dir / "mods" / jar.name)
        result["phoeniumJarOverride"] = {"jar": jar.name, "sha256": sha256(jar), "replaced": old[0].name}
    if a.c2me:
        toml = server_dir / "config" / "c2me.toml"
        lines = toml.read_text(encoding="utf-8").splitlines()
        for kv in a.c2me:
            key, value = kv.split("=", 1)
            section, name = key.rsplit(".", 1)
            header = f"[{section}]"
            start = next(i for i, l in enumerate(lines) if l.strip() == header)
            hit = next(i for i in range(start + 1, len(lines)) if re.match(rf"\s*{re.escape(name)}\s*=", lines[i]))
            indent = re.match(r"\s*", lines[hit]).group(0)
            lines[hit] = f"{indent}{name} = {value}"
        toml.write_text("\n".join(lines) + "\n", encoding="utf-8")
        result["c2meOverrides"] = a.c2me
    sysprops = dict(stack.STACKS[a.stack].get("sysprops", {}))
    sysprops["Geyser.RakRateLimitingDisabled"] = "true"   # TEST ONLY (synthetic Bedrock lane on loopback)
    sysprops["stage9.memprobe.out"] = str(out / "memprobe.jsonl")
    for kv in a.sysprop:
        k, v = kv.split("=", 1)
        sysprops[k] = v
    jvm = ["-XX:NativeMemoryTracking=summary", *a.jvm_arg]
    srv = Server(server_dir, out, heap=a.heap, sysprops=sysprops, extra_jvm=jvm, jfr=True, jfr_settings=a.jfr_settings)
    stress_histo = a.cycles if a.stress_histo_cycle < 0 else a.stress_histo_cycle
    smp = sampler.Sampler(out / "cpu.jsonl")
    proxy = rcon = pm = None
    marks: list = []

    def mark(name: str, **extra):
        row = {"name": name, "t": now_ms(), **extra}
        marks.append(row)
        (out / "phases.json").write_text(json.dumps(marks, indent=1))
        print(time.strftime("%H:%M:%S"), name, json.dumps(extra)[:200], flush=True)

    rc = 0
    try:
        srv.start()
        smp.watch("server", srv.pid)
        smp.start()
        pm = Proc(srv.pid, out / "procmem.jsonl")
        mark("serverStart")
        srv.wait_for(r"Done \(", timeout=900)
        if "geyser" in stack.STACKS[a.stack]["mods"]:
            srv.wait_for(r"Run /geyser help", timeout=300)
        if stack.STACKS[a.stack]["phoenium"]:
            _, line = srv.wait_for(r"PHOENIUM_STAGE2 started lifecycle=RUNNING", timeout=120)
            result["phoeniumStart"] = line[line.find("lifecycle="):][:300]
        mark("done")
        rcon = Rcon("127.0.0.1", PORTS["rcon"], "stage9-local-test").connect()
        for command in scen.get("setup", []):
            reply = rcon.command(command.format(seed=a.seed, tier=a.java + a.bedrock))
            if re.search(SETUP_REJECTED, reply):
                raise RuntimeError(f"setup rejected: {command!r} -> {reply[:160]!r}")
        if stack.sha256(VIAPROXY) != VIAPROXY_SHA:
            raise SystemExit("ViaProxy jar hash mismatch")
        (out / "viaproxy").mkdir()
        proxy = subprocess.Popen([srv.cmd[0], "-Xmx2G", "-jar", str(VIAPROXY), "cli", "--bind-address", f"127.0.0.1:{PORTS['proxy']}",
                                  "--target-address", f"127.0.0.1:{PORTS['java']}", "--target-version", "26.2", "--auth-method", "NONE",
                                  "--proxy-online-mode", "false", "--chat-signing", "false", "--log-ips", "false"],
                                 cwd=out / "viaproxy", stdout=open(out / "viaproxy.log", "w"), stderr=subprocess.STDOUT)
        for _ in range(120):
            if sampler.port_in_use(PORTS["proxy"]):
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("ViaProxy did not start listening")
        smp.watch("proxy", proxy.pid)
        mark("setupDone")
        (out / "jvm" / "nmt-c0-baseline.txt").write_text(srv.jcmd("VM.native_memory", "summary"))

        for c in range(1, a.cycles + 1):
            base = {"host": "127.0.0.1", "bedrockPort": PORTS["bedrock"], "rconPort": PORTS["rcon"], "rconPassword": "stage9-local-test",
                    "seed": a.seed, "scenario": "mixed-v2", "params": params, "version": scen.get("botVersion", "26.1"),
                    "joinIntervalMs": 1000}
            jcfg = {**base, "port": PORTS["proxy"], "tier": a.java, "out": str(out / f"bots-c{c}.jsonl")}
            bcfg = {**base, "port": PORTS["java"], "tier": a.bedrock, "out": str(out / f"bots-c{c}.jsonl")}
            (out / f"java-c{c}.json").write_text(json.dumps(jcfg))
            (out / f"bedrock-c{c}.json").write_text(json.dumps(bcfg))
            mark("join", cycle=c)
            jctl = Controller([NODE, str(TOOLS / "adapters" / "java_bots.js"), str(out / f"java-c{c}.json")], out, f"java-c{c}.log")
            bctl = None
            if a.bedrock:
                env = dict(os.environ, NODE_PATH=str(TOOLS / "adapters" / "node_modules"), STAGE9_ADAPTERS=str(TOOLS / "adapters"))
                bctl = Controller.__new__(Controller)
                bctl.log = open(out / f"bedrock-c{c}.log", "w", encoding="utf-8", errors="replace")
                bctl.proc = subprocess.Popen([NODE, str(BEDROCK_CTL), str(out / f"bedrock-c{c}.json")], cwd=TOOLS, env=env,
                                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                             encoding="utf-8", errors="replace", bufsize=1)
                bctl.joined, bctl.joined_count = threading.Event(), 0
                threading.Thread(target=bctl._pump, daemon=True).start()
            budget = 120 + (a.java + a.bedrock) * 2
            jok = jctl.joined.wait(budget)
            bok = bctl.joined.wait(max(30.0, budget)) if bctl else True
            mark("stress", cycle=c, javaJoined=jctl.joined_count, bedrockJoined=bctl.joined_count if bctl else 0, javaOk=jok, bedrockOk=bok)
            time.sleep(a.stress)
            if srv.proc.poll() is not None:
                raise RuntimeError(f"server died in stress of cycle {c}")
            (out / "jvm" / f"nmt-c{c}-stress.txt").write_text(srv.jcmd("VM.native_memory", "summary"))
            if c == stress_histo:
                mark("gcStress", cycle=c, note="diagnostic full GC under load (jcmd GC.class_histogram), recorded")
                t = time.time()
                (out / "jvm" / f"histo-c{c}-stress.txt").write_text(srv.jcmd("GC.class_histogram", timeout=300))
                mark("gcStressDone", cycle=c, histogramS=round(time.time() - t, 1))
            if bctl:
                bctl.proc.stdin.write("pause\n")
                bctl.proc.stdin.flush()
            mark("quiet", cycle=c)
            time.sleep(a.quiet)
            mark("leave", cycle=c)
            jexit = jctl.teardown()
            bexit = bctl.teardown() if bctl else None
            mark("idle", cycle=c, javaExit=jexit, bedrockExit=bexit, players=rcon.command("list")[:120])
            time.sleep(a.idle)
            if srv.proc.poll() is not None:
                raise RuntimeError(f"server died in idle of cycle {c}")
            mark("gc", cycle=c, note="diagnostic full GC (jcmd GC.class_histogram)", players=rcon.command("list")[:120])
            t = time.time()
            histo = srv.jcmd("GC.class_histogram", timeout=300)
            (out / "jvm" / f"histo-c{c}.txt").write_text(histo)
            heap = srv.jcmd("GC.heap_info")
            (out / "jvm" / f"heap-c{c}.txt").write_text(heap)
            (out / "jvm" / f"nmt-c{c}-idle.txt").write_text(srv.jcmd("VM.native_memory", "summary"))
            mark("gcDone", cycle=c, histogramS=round(time.time() - t, 1), heapInfo=" ".join(heap.split())[:240])
    except Exception as error:  # evidence is kept; result.json says why
        rc = 1
        result["problems"].append("".join(traceback.format_exception(error))[-3000:])
    finally:
        for ctl_name in ("jctl", "bctl"):
            ctl = locals().get(ctl_name)
            if ctl is not None and getattr(ctl, "proc", None) is not None and ctl.proc.poll() is None:
                try:
                    ctl.teardown(30)
                except Exception:  # noqa: BLE001
                    pass
        if proxy is not None:
            proxy.terminate()
            try:
                proxy.wait(30)
            except subprocess.TimeoutExpired:
                proxy.kill()
        if rcon is not None:
            try:
                rcon.close()
            except Exception:  # noqa: BLE001
                pass
        if srv.proc is not None and srv.proc.poll() is None:
            result["stop"] = srv.stop()
        else:
            result["stop"] = {"exitCode": srv.proc.returncode if srv.proc else None, "alreadyExited": True}
            rc = 1
        if pm:
            pm.close()
        smp.stop()
        mark("end")
        result["phases"] = marks
        result["badLogLines"] = srv.bad[:50]
        stopped = [l for l in srv.lines if "PHOENIUM_STAGE2 stopped" in l]
        result["phoeniumStop"] = stopped[-1][:4000] if stopped else None
        # keep evidence compact: drop the bulky static server inputs, keep logs/world/config
        for name in ("libraries", "versions"):
            shutil.rmtree(server_dir / name, ignore_errors=True)
        (out / "result.json").write_text(json.dumps(result, indent=1))
    print("RESULT", "OK" if rc == 0 else "PROBLEM", out)
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
