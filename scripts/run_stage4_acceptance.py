"""Run the disposable Stage 4 installed trace fixture and capture disk evidence.

The runner copies a prepared Fabric server into a new directory, installs one
hash-selected production JAR and the test-only probe, drives the fixture through
the console, and restarts the same world once.  It never mutates the prepared
server or a historical run.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import queue
import threading
import time
import zlib

try:
    import psutil
except ImportError:
    psutil = None


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(part)
    return digest.hexdigest().upper()


class NbtReader:
    """Small dependency-free NBT reader used only for persisted tick diagnostics."""
    def __init__(self, payload):
        self.payload = payload
        self.offset = 0

    def take(self, size):
        value = self.payload[self.offset:self.offset + size]
        if len(value) != size:
            raise ValueError("truncated NBT payload")
        self.offset += size
        return value

    def u8(self):
        return self.take(1)[0]

    def i8(self):
        return struct.unpack(">b", self.take(1))[0]

    def i16(self):
        return struct.unpack(">h", self.take(2))[0]

    def i32(self):
        return struct.unpack(">i", self.take(4))[0]

    def i64(self):
        return struct.unpack(">q", self.take(8))[0]

    def string(self):
        size = struct.unpack(">H", self.take(2))[0]
        return self.take(size).decode("utf-8")

    def value(self, tag):
        if tag == 1:
            return self.i8()
        if tag == 2:
            return self.i16()
        if tag == 3:
            return self.i32()
        if tag == 4:
            return self.i64()
        if tag == 5:
            return struct.unpack(">f", self.take(4))[0]
        if tag == 6:
            return struct.unpack(">d", self.take(8))[0]
        if tag == 7:
            return self.take(max(0, self.i32())).hex()
        if tag == 8:
            return self.string()
        if tag == 9:
            element_tag = self.u8()
            return [self.value(element_tag) for _ in range(max(0, self.i32()))]
        if tag == 10:
            result = {}
            while True:
                child_tag = self.u8()
                if child_tag == 0:
                    return result
                # Python evaluates assignment RHS before the subscript target.
                # Read the compound name first or value() consumes the payload
                # before the name and desynchronizes the entire NBT stream.
                name = self.string()
                result[name] = self.value(child_tag)
        if tag == 11:
            return [self.i32() for _ in range(max(0, self.i32()))]
        if tag == 12:
            return [self.i64() for _ in range(max(0, self.i32()))]
        raise ValueError(f"unsupported NBT tag {tag}")

    def root(self):
        tag = self.u8()
        self.string()  # root name
        return self.value(tag)


def decode_nbt(payload):
    try:
        return NbtReader(payload).root()
    except (UnicodeDecodeError, ValueError, struct.error):
        return None


def tick_records(root, key):
    values = root.get(key, []) if isinstance(root, dict) else []
    if not isinstance(values, list):
        return []
    return [value for value in values if isinstance(value, dict)]


def expected_pending_tick(trace_path):
    try:
        report = json.loads(trace_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    for event in report.get("events", []):
        if "type=Block{" in event and "order=99" in event:
            position = re.search(r"pos=BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}", event)
            trigger = re.search(r"trigger=(-?\d+)", event)
            block = re.search(r"type=Block\{([^}]+)\}", event)
            priority = re.search(r"priority=([A-Z_]+)", event)
            order = re.search(r"order=(-?\d+)", event)
            if position and trigger and block and priority and order:
                save_times = []
                for saved in report.get("events", []):
                    saved_chunk = re.search(
                        r"save\.serialization\.ticks chunk=ChunkPos\{x=(-?\d+), z=(-?\d+)\} gameTime=(-?\d+)",
                        saved)
                    if saved_chunk and (int(saved_chunk.group(1)), int(saved_chunk.group(2))) == (
                            int(position.group(1)) >> 4, int(position.group(3)) >> 4):
                        save_times.append(int(saved_chunk.group(3)))
                priority_value = {"EXTREMELY_HIGH": -4, "VERY_HIGH": -3, "HIGH": -2,
                                  "NORMAL": 0, "LOW": 1, "VERY_LOW": 2,
                                  "EXTREMELY_LOW": 3}.get(priority.group(1))
                if priority_value is None or not save_times:
                    return None
                return {"position": {"x": int(position.group(1)),
                                      "y": int(position.group(2)),
                                      "z": int(position.group(3))},
                        "chunk": {"x": int(position.group(1)) >> 4,
                                  "z": int(position.group(3)) >> 4},
                        "id": block.group(1), "priority": priority.group(1),
                        "priorityValue": priority_value, "order": int(order.group(1)),
                        "trigger": int(trigger.group(1)),
                        "saveGameTime": save_times[-1],
                        "startedAt": int(report.get("startedAt", 0))}
    return None


def block_state_at(decoded, position):
    """Read the exact palette state at one world position from a saved chunk."""
    if not isinstance(decoded, dict):
        return None
    level = decoded.get("Level", decoded)
    if not isinstance(level, dict):
        return None
    sections = level.get("sections", level.get("Sections", []))
    if not isinstance(sections, list):
        return None
    section_y = position["y"] >> 4
    local_index = ((position["y"] & 15) << 8) | ((position["z"] & 15) << 4) | (position["x"] & 15)
    for section in sections:
        if not isinstance(section, dict) or section.get("Y") != section_y:
            continue
        states = section.get("block_states", section.get("BlockStates"))
        if not isinstance(states, dict):
            return None
        palette = states.get("palette", states.get("Palette", []))
        if not isinstance(palette, list) or not palette:
            return None
        if len(palette) == 1:
            palette_index = 0
        else:
            data = states.get("data", states.get("Data", []))
            if not isinstance(data, list):
                return None
            bits = max(4, (len(palette) - 1).bit_length())
            start = local_index * bits
            word = start >> 6
            shift = start & 63
            if word >= len(data):
                return None
            palette_index = (int(data[word]) >> shift) & ((1 << bits) - 1)
            if shift + bits > 64 and word + 1 < len(data):
                palette_index |= (int(data[word + 1]) << (64 - shift)) & ((1 << bits) - 1)
        return palette[palette_index] if palette_index < len(palette) else None
    return None


def inspect_mca(world, state_positions=()):
    records = []
    for path in sorted(path for path in world.rglob("*.mca") if path.parent.name == "region"):
        data = path.read_bytes()
        chunks = []
        for index in range(1024):
            location = data[index * 4:index * 4 + 4]
            if len(location) < 4:
                continue
            offset = int.from_bytes(location[:3], "big")
            sectors = location[3]
            if not offset or not sectors:
                continue
            begin = offset * 4096
            if begin + 5 > len(data):
                chunks.append({"index": index, "offset": offset, "sectors": sectors,
                               "compression": None, "nbtSha256": None,
                               "knownTickTags": ["invalid-offset"]})
                continue
            length = struct.unpack(">I", data[begin:begin + 4])[0]
            compression = data[begin + 4]
            if length < 1 or begin + 4 + length > len(data):
                chunks.append({"index": index, "offset": offset, "sectors": sectors,
                               "compression": compression, "nbtSha256": None,
                               "knownTickTags": ["invalid-length"]})
                continue
            payload = data[begin + 5:begin + 4 + length]
            try:
                if compression == 1:
                    nbt = gzip.decompress(payload)
                elif compression == 2:
                    nbt = zlib.decompress(payload)
                else:
                    nbt = payload
                decoded = decode_nbt(nbt)
                known = [tag for tag in (b"block_ticks", b"fluid_ticks", b"block_events") if tag in nbt]
                digest = hashlib.sha256(nbt).hexdigest().upper()
            except (OSError, zlib.error):
                known, digest = ["decode-error"], None
            chunks.append({"index": index, "offset": offset, "sectors": sectors,
                           "compression": compression, "nbtSha256": digest,
                           "knownTickTags": [tag.decode() for tag in known],
                           "blockTicks": tick_records(decoded, "block_ticks"),
                           "fluidTicks": tick_records(decoded, "fluid_ticks"),
                           "blockStates": {f"{position['x']},{position['y']},{position['z']}": block_state_at(decoded, position)
                                           for position in state_positions
                                           if (position["x"] >> 4) & 31 == index % 32
                                           and (position["z"] >> 4) & 31 == index // 32}})
        records.append({"path": str(path), "sha256": sha256(path),
                        "headerSha256": hashlib.sha256(data[:8192]).hexdigest().upper(),
                        "chunks": chunks})
    return records


def snapshot_mca(world, target):
    """Preserve the exact stopped-world region bytes before starting restart."""
    copied = []
    for path in sorted(world.rglob("*.mca")):
        relative = path.relative_to(world)
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, destination)
        copied.append(str(relative))
    return copied


def pending_tick_readback(records, expected):
    result = {"status": "NO_EXPECTATION", "expected": expected, "matches": [], "records": 0}
    if expected is None:
        return result
    all_ticks = []
    for region in records:
        for chunk in region.get("chunks", []):
            for tick in chunk.get("blockTicks", []):
                if not isinstance(tick, dict):
                    continue
                item = {"region": region["path"], "chunkIndex": chunk["index"],
                        "_state": chunk.get("blockStates", {}).get(
                            f"{expected['position']['x']},{expected['position']['y']},{expected['position']['z']}"),
                        **tick}
                all_ticks.append(item)
    result["records"] = len(all_ticks)
    expected_position = expected["position"]
    expected_chunk = expected["chunk"]
    for tick in all_ticks:
        x = tick.get("x")
        z = tick.get("z")
        y = tick.get("y")
        if x != expected_position["x"]:
            continue
        if z != expected_position["z"] or y != expected_position["y"]:
            continue
        if tick.get("i") != expected["id"] or tick.get("p") != expected["priorityValue"] \
                or tick.get("o") != expected["order"]:
            continue
        deadline = tick.get("t")
        if deadline != expected["trigger"] - expected["saveGameTime"]:
            continue
        result["matches"].append(tick)
        tick["deadlineMatch"] = True
        tick["chunkMatch"] = chunk_index_for(expected_chunk) == tick.get("chunkIndex")
        tick["powered"] = isinstance(tick.get("_state"), dict) and \
            tick["_state"].get("Properties", {}).get("powered") == "true"
    result["status"] = "PRESENT" if result["matches"] else "MISSING"
    result["deadlineMatch"] = any(item.get("deadlineMatch") for item in result["matches"])
    result["exactIdentity"] = any(item.get("i") == expected["id"] and
                                   item.get("p") == expected["priorityValue"] and
                                   item.get("o") == expected["order"] and
                                   item.get("t") == expected["trigger"] - expected["saveGameTime"]
                                   for item in result["matches"])
    state_key = f"{expected_position['x']},{expected_position['y']},{expected_position['z']}"
    result["poweredState"] = any(item.get("powered", False) for item in result["matches"])
    result["chunkMatch"] = any(chunk.get("index") == chunk_index_for(expected_chunk)
                               and state_key in chunk.get("blockStates", {})
                               for region in records for chunk in region.get("chunks", []))
    return result


def chunk_index_for(chunk):
    return (chunk["x"] & 31) + ((chunk["z"] & 31) * 32)


def copy_server(source, target, production, probe, extras):
    target.mkdir(parents=True)
    for name in ("fabric-server-launch.jar", ".fabric", "libraries", "versions"):
        source_path = source / name
        if source_path.is_dir():
            shutil.copytree(source_path, target / name)
        else:
            shutil.copy2(source_path, target / name)
    mods = target / "mods"
    mods.mkdir()
    for item in (source / "mods").iterdir():
        if item.suffix == ".jar" and not item.name.startswith(("phoenium-", "full-live-probe", "stage4-acceptance")):
            shutil.copy2(item, mods / item.name)
    shutil.copy2(production, mods / production.name)
    shutil.copy2(probe, mods / "stage4-acceptance-probe.jar")
    for extra in extras:
        shutil.copy2(extra, mods / extra.name)


def wait_for(process, lines, token, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if token in lines:
            return True
        if process.poll() is not None:
            return False
        time.sleep(0.1)
    return token in lines


def read_until(process, read_line, log, lines, token, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and process.poll() is None:
        if any(token in line for line in lines):
            return True
        line = read_line(0.1)
        if line:
            lines.append(line)
            log.write(line)
            log.flush()
    return any(token in line for line in lines)


def capture_thread_dump(server, process, label):
    """Bounded diagnostic for a server that stops producing fixture barriers."""
    output = server / ("thread-dump-" + label + ".txt")
    commands = [["jcmd", str(process.pid), "Thread.print", "-l"],
                ["jstack", "-l", str(process.pid)]]
    for command in commands:
        try:
            result = subprocess.run(command, cwd=server, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    timeout=20, check=False)
        except (OSError, subprocess.TimeoutExpired) as failure:
            output.write_text("command=" + " ".join(command) + "\\n" + str(failure) + "\\n",
                              encoding="utf-8")
            continue
        output.write_text("command=" + " ".join(command) + "\\n" + result.stdout,
                          encoding="utf-8")
        return str(output)
    return str(output)


def run_process(server, trace_path=None, drive=False, explicit_topology=False, topology_lifecycle=False):
    java = Path(os.environ.get("JAVA_HOME", "")) / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not java.exists():
        java = Path(shutil.which("java") or "java")
    properties = ["-Xmx2G", "-Dphoenium.test.diagnostics=true",
                  "-Dphoenium.stage4.candidate=" + str(bool(explicit_topology)).lower()]
    if explicit_topology:
        properties.append("-Dphoenium.stage3.explicitTopology=true")
        # The production fixture otherwise publishes only its narrow default
        # A/B strip before the initial spawn is known.  Keep the spawn support
        # domain identical on the initial and restart processes.
        properties.append("-Dphoenium.test.chunkFixtureSupport=true")
        if topology_lifecycle:
            # The lifecycle fixture's deferred B event lives in the established far B interior.
            properties.append("-Dphoenium.stage3.chunkTrace=true")
    if trace_path:
        properties.append("-Dphoenium.stage4.tracePath=" + str(trace_path))
    command = [str(java), *properties, "-jar", "fabric-server-launch.jar", "nogui"]
    log_path = server / ("server-process-restart.log" if not drive else "server-process.log")
    lines = []
    reload_effect = None
    reload_barrier = False
    runner_error = None
    phase_started = time.monotonic()
    peak_rss = None

    def sample_process(process):
        nonlocal peak_rss
        if psutil is None:
            return
        try:
            rss = psutil.Process(process.pid).memory_info().rss
        except (OSError, psutil.Error):
            return
        peak_rss = rss if peak_rss is None else max(peak_rss, rss)

    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, bufsize=1)
        output_queue = queue.Queue()
        def pump_output():
            for output_line in iter(process.stdout.readline, ""):
                output_queue.put(output_line)
            output_queue.put(None)
        threading.Thread(target=pump_output, name="stage4-server-output", daemon=True).start()
        def read_line(timeout=0.1):
            try:
                return output_queue.get(timeout=timeout)
            except queue.Empty:
                return ""
        def send(value):
            process.stdin.write(value + "\n")
            process.stdin.flush()
        while True:
            sample_process(process)
            line = read_line(0.1)
            if line:
                lines.append(line)
                log.write(line)
                log.flush()
                if "Done (" in line and drive:
                    break
                if "Done (" in line and not drive:
                    break
            elif process.poll() is not None:
                break
            elif not drive:
                break
        if drive and process.poll() is None:
            try:
                send("stage4trace run")
                if topology_lifecycle:
                    # Keep the merge/split request adjacent to arm so the pending block/fluid
                    # work and block event cross the real coordinator boundary before they run.
                    send("stage4trace lifecycle")
                    send("stage4trace split-far")
                time.sleep(5)
                send("save-all flush")
                time.sleep(3)
                send("stage4trace finish")
                time.sleep(1)
                send("stop")
            except OSError as failure:
                runner_error = repr(failure)
                dump_path = capture_thread_dump(server, process, "drive-error") if process.poll() is None else None
        elif process.poll() is None:
            try:
                send("stage4trace reload")
                reload_started = read_until(process, read_line, log, lines, "STAGE4_TRACE_RELOAD", 30)
                send("stage4trace await effect 450")
                reload_barrier = read_until(process, read_line, log, lines,
                                            "STAGE4_TRACE_BARRIER label=effect", 180)
                if not reload_barrier and process.poll() is None:
                    dump_path = capture_thread_dump(server, process, "reload-barrier")
                else:
                    dump_path = None
                send("stage4trace verify")
                effect_observed = read_until(process, read_line, log, lines,
                                             "STAGE4_TRACE_RELOAD_EFFECT", 30)
                effect_lines = [line for line in lines if "STAGE4_TRACE_RELOAD_EFFECT" in line]
                if effect_lines:
                    reload_effect = "reached=true" in effect_lines[-1]
                if not reload_started:
                    reload_effect = False
                if not effect_observed and process.poll() is None:
                    dump_path = capture_thread_dump(server, process, "reload-effect")
                send("stop")
            except OSError:
                runner_error = "restart command pipe closed"
                dump_path = capture_thread_dump(server, process, "runner-error") if process.poll() is None else None
        while process.poll() is None:
            sample_process(process)
            line = read_line(0.1)
            if line:
                lines.append(line)
                log.write(line)
                log.flush()
        process.wait(timeout=30)
        sample_process(process)
    gc_events = sum(1 for line in lines if "[gc" in line.lower() or " gc(" in line.lower())
    return {"command": command, "exitCode": process.returncode,
            "log": str(log_path), "lines": len(lines),
            "elapsedSeconds": round(time.monotonic() - phase_started, 3),
            "peakRssBytes": peak_rss,
            "gcEventsObserved": gc_events,
            "resourceMeasurement": "psutil process RSS; unavailable" if psutil is None else "psutil process RSS",
            "gcMeasurement": "log pattern only; GC logging disabled",
            "threadDump": locals().get("dump_path"),
            "runnerError": runner_error,
            "reloadBarrierObserved": reload_barrier,
            "reloadEffectReached": reload_effect}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--production", type=Path, required=True)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--extra-mod", action="append", type=Path, default=[])
    parser.add_argument("--world", default="stage4-trace-smoke")
    parser.add_argument("--world-source", type=Path)
    parser.add_argument("--port", type=int, default=25615)
    parser.add_argument("--view-distance", type=int, choices=range(2, 33), default=8,
                        help="installed server view distance (use 5 or 8 for the Stage 4 campaign)")
    parser.add_argument("--simulation-distance", type=int, choices=range(2, 33), default=8,
                        help="installed server simulation distance (use 5 or 8 for the Stage 4 campaign)")
    parser.add_argument("--explicit-topology", action="store_true",
                        help="enable the stable explicit disjoint A/B production topology")
    parser.add_argument("--topology-lifecycle", action="store_true",
                        help="request the installed coordinator-backed A/B merge and split fixture")
    args = parser.parse_args()
    args.output = args.output.resolve()
    if args.output.exists():
        raise SystemExit(f"refusing existing output: {args.output}")
    for path in (args.prepared, args.production, args.probe):
        if not path.exists():
            raise SystemExit(f"missing input: {path}")
    args.output.mkdir(parents=True)
    server = args.output / "server"
    copy_server(args.prepared, server, args.production, args.probe, args.extra_mod)
    (server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server / "server.properties").write_text("\n".join([
        f"level-name={args.world}", "level-seed=444444", "level-type=minecraft:flat", "online-mode=false",
        "enforce-secure-profile=false", "server-ip=127.0.0.1", f"server-port={args.port}",
        "enable-rcon=false", "max-players=1", "difficulty=peaceful", "gamemode=creative",
        f"view-distance={args.view_distance}", f"simulation-distance={args.simulation_distance}", "spawn-protection=0",
        "sync-chunk-writes=true", "pause-when-empty-seconds=-1", ""]), encoding="utf-8")
    if args.world_source:
        if not (args.world_source / "level.dat").exists():
            raise SystemExit(f"world source has no level.dat: {args.world_source}")
        shutil.copytree(args.world_source, server / args.world)
    trace = args.output / "trace.json"
    first = run_process(server, trace, drive=True, explicit_topology=args.explicit_topology,
                        topology_lifecycle=args.topology_lifecycle)
    world = server / args.world
    mca_snapshot = args.output / "pre-restart-world"
    snapshot_files = snapshot_mca(world, mca_snapshot)
    expected_tick = expected_pending_tick(trace)
    before_restart = inspect_mca(mca_snapshot,
                                 [expected_tick["position"]] if expected_tick else [])
    (args.output / "mca-before-restart.json").write_text(json.dumps(before_restart, indent=2) + "\n", encoding="utf-8")
    pending = pending_tick_readback(before_restart, expected_tick)
    (args.output / "pending-tick-readback.json").write_text(json.dumps(pending, indent=2) + "\n", encoding="utf-8")
    second = run_process(server, drive=False, explicit_topology=args.explicit_topology,
                         topology_lifecycle=args.topology_lifecycle)
    try:
        trace_report = json.loads(trace.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        trace_report = {}
    trace_events = trace_report.get("events", []) if isinstance(trace_report, dict) else []
    lifecycle_evidence = {
        "topology.merge": any(item == "topology.merge" or item.startswith("topology.merge ")
                              for item in trace_events if isinstance(item, str)),
        "topology.split": any(item == "topology.split" or item.startswith("topology.split ")
                              for item in trace_events if isinstance(item, str)),
        "topology.body": any(item == "topology.body" or item.startswith("topology.body ")
                             for item in trace_events if isinstance(item, str)),
        "topology.body.owner.a": any(item.startswith("topology.body ") and "contextRegion=stage3-fixture-a" in item
                                     for item in trace_events if isinstance(item, str)),
        "topology.body.owner.b": any(item.startswith("topology.body ") and "contextRegion=stage3-fixture-b" in item
                                     for item in trace_events if isinstance(item, str)),
        "block.deferred": any(item == "block.deferred" or item.startswith("block.deferred ")
                              for item in trace_events if isinstance(item, str)),
        "tracking.body": any(item == "tracking.body" or item.startswith("tracking.body ")
                              for item in trace_events if isinstance(item, str)),
    }
    manifest = {"kind": "stage4-installed-acceptance-fixture", "world": args.world,
                "seed": 444444, "viewDistance": args.view_distance, "simulationDistance": args.simulation_distance,
                "explicitTopology": args.explicit_topology,
                "topologyLifecycle": args.topology_lifecycle,
                "topologyLifecycleEvidence": lifecycle_evidence,
                "runtimeMeasurement": {"jvm": first["command"][0], "heap": "-Xmx2G",
                                        "gcLogging": "disabled; log pattern count only",
                                        "rss": "psutil peak process RSS when available"},
                "fixtureProperties": [property for property in first["command"]
                                      if property.startswith("-Dphoenium.")],
                "productionSha256": sha256(args.production), "probeSha256": sha256(args.probe),
                "production": str(args.production), "probe": str(args.probe),
                "firstRun": {key: value for key, value in first.items() if key != "lines"},
                "restartRun": {key: value for key, value in second.items() if key != "lines"},
                "mcaBeforeRestart": str(args.output / "mca-before-restart.json"),
                "mcaSnapshot": str(mca_snapshot),
                "mcaSnapshotFiles": snapshot_files,
                "pendingTickReadback": str(args.output / "pending-tick-readback.json"),
                "pendingTickGate": pending["status"] == "PRESENT" and pending.get("deadlineMatch", False)
                                   and pending.get("exactIdentity", False)
                                   and pending.get("chunkMatch", False)
                                   and pending.get("poweredState", False),
                "reloadEffectGate": second.get("reloadEffectReached", False) is True,
                "trace": str(trace)}
    (args.output / "acceptance-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))
    return 0 if (first["exitCode"] == 0 and second["exitCode"] == 0
                 and manifest["pendingTickGate"] and manifest["reloadEffectGate"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
