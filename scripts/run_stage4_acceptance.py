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
import shutil
import struct
import subprocess
import sys
import time
import zlib


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(part)
    return digest.hexdigest().upper()


def inspect_mca(world):
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
                known = [tag for tag in (b"block_ticks", b"fluid_ticks", b"block_events") if tag in nbt]
                digest = hashlib.sha256(nbt).hexdigest().upper()
            except (OSError, zlib.error):
                known, digest = ["decode-error"], None
            chunks.append({"index": index, "offset": offset, "sectors": sectors,
                           "compression": compression, "nbtSha256": digest,
                           "knownTickTags": [tag.decode() for tag in known]})
        records.append({"path": str(path), "sha256": sha256(path),
                        "headerSha256": hashlib.sha256(data[:8192]).hexdigest().upper(),
                        "chunks": chunks})
    return records


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


def run_process(server, trace_path=None, drive=False):
    java = Path(os.environ.get("JAVA_HOME", "")) / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not java.exists():
        java = Path(shutil.which("java") or "java")
    properties = ["-Xmx2G", "-Dphoenium.test.diagnostics=true"]
    if trace_path:
        properties.append("-Dphoenium.stage4.tracePath=" + str(trace_path))
    command = [str(java), *properties, "-jar", "fabric-server-launch.jar", "nogui"]
    log_path = server / ("server-process-restart.log" if not drive else "server-process.log")
    lines = []
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, bufsize=1)
        def send(value):
            process.stdin.write(value + "\n")
            process.stdin.flush()
        while True:
            line = process.stdout.readline()
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
            send("stage4trace run")
            time.sleep(5)
            send("save-all flush")
            time.sleep(3)
            send("stage4trace finish")
            time.sleep(1)
            send("stop")
        elif process.poll() is None:
            try:
                send("stage4trace verify")
                time.sleep(1)
                send("stop")
            except OSError:
                pass
        while process.poll() is None:
            line = process.stdout.readline()
            if line:
                lines.append(line)
                log.write(line)
                log.flush()
            else:
                time.sleep(0.1)
        process.wait(timeout=30)
    return {"command": command, "exitCode": process.returncode,
            "log": str(log_path), "lines": len(lines)}


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
    first = run_process(server, trace, drive=True)
    world = server / args.world
    before_restart = inspect_mca(world)
    (args.output / "mca-before-restart.json").write_text(json.dumps(before_restart, indent=2) + "\n", encoding="utf-8")
    second = run_process(server, drive=False)
    manifest = {"kind": "stage4-installed-acceptance-fixture", "world": args.world,
                "seed": 444444, "viewDistance": args.view_distance, "simulationDistance": args.simulation_distance,
                "productionSha256": sha256(args.production), "probeSha256": sha256(args.probe),
                "production": str(args.production), "probe": str(args.probe),
                "firstRun": {key: value for key, value in first.items() if key != "lines"},
                "restartRun": {key: value for key, value in second.items() if key != "lines"},
                "mcaBeforeRestart": str(args.output / "mca-before-restart.json"),
                "trace": str(trace)}
    (args.output / "acceptance-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))
    return 0 if first["exitCode"] == 0 and second["exitCode"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
