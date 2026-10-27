"""Stage 9 MEM-4 world-generation equality gate for memory candidates (owner GC/memory directive 2026-10-11).

D36 (S9-M1) drops ProtoChunk.noiseChunk after CARVERS; C2ME lowMemoryMode stops pinning chunks loaded from disk, so
partly generated chunks are unloaded, saved and reloaded mid-generation. Neither may change the world. Every arm runs
the same three-server-run script on a fresh world with the scenarios' pinned seed, `tick freeze` before any chunk is
forced (no random / scheduled block ticks, entities frozen: the saved world = generation output). Squares are
16x16 chunks around five centres C (spawn area + four far biome areas):

  run 1  fresh:     force S1 = C, wait until every forced chunk is ENTITY_TICKING (`execute if loaded`);
         unload:    `forceload remove all`, `tick unfreeze` (nothing forced, nobody online: no chunk ticks; lets the
                    1-tick "unknown" tickets expire, which `tick freeze` never does), wait until the memory probe's C2ME
                    holder census drops (chunks unloaded and saved; S1's generation rim stays on disk as protos at
                    intermediate statuses), `tick freeze` again;
         reload:    `save-all flush`, record the statuses on disk of the S4 squares, force S4 = C shifted 16 chunks
                    south (adjacent: reloads S1's rim protos from disk in the same run and finishes them); unforce, stop.
  run 2  restart:   record the statuses on disk of the S2 / S3 squares, force S2 = C shifted east (resumes run 1's
                    rim after a restart);
         interrupt: `forceload add` generates synchronously in 26.2 (setChunkForced -> getChunk) and can never be
                    caught mid-generation, so: unforce, `tick unfreeze` (nothing forced, nobody online: no chunk
                    ticks), let Chunky (part of L5) generate the first S3 square (C shifted north) asynchronously and
                    `stop` the server once it reports >= 24 processed chunks; record the statuses on disk of S3.
  run 3  finish:    `chunky cancel`, force all S3 squares, wait, unforce, flush, stop.

Then the saved chunks of S1+S4+S2+S3 (5,120 forced chunks) are compared against the first arm field by field:
sections[Y].block_states / biomes, Heightmaps, structures (starts + references), block_entities, Status, and the
generated entities (id + position per chunk; UUIDs and random attributes are not compared). block_ticks /
fluid_ticks / PostProcessing are reported, light and timestamps ignored. Arms without overrides are repeat controls.
A path counts as exercised only if the recorded on-disk statuses show intermediate (non-full) protos for it.

    python wgeq9m.py <out-id> <stack> --arm base --arm base2 --arm "d36lm|jar=<path>|c2me=chunkSystem.lowMemoryMode=true"

Outputs: gates/wgeq/<out-id>/<arm>/{r1,r2,r3}/ (server.log, jvm/gc.log, memprobe.jsonl), <arm>/digest.json,
result.json. Exit 0 = PASS (controls identical to each other, every candidate identical to the first arm, every path
exercised in every arm, no bad log lines, clean saves); 1 = FAIL / could not run; 3 = INCONCLUSIVE (controls differ).
TEST ONLY: offline, loopback, no players.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import io
import json
import pathlib
import re
import shutil
import struct
import sys
import time
import traceback
import zlib

TOOLS = pathlib.Path(r"C:\Users\Admin\Downloads\Project PHOENIUM\tools\stage9")
sys.path.insert(0, str(TOOLS))
from run_stage9 import PORTS  # noqa: E402
from scenarios import SEED, SETUP_REJECTED  # noqa: E402
from lib import stack, sampler  # noqa: E402
from lib.server import Server  # noqa: E402
from lib.rcon import Rcon  # noqa: E402

GATES = pathlib.Path(__file__).resolve().parents[1]
PROBE = GATES / "memprobe" / "build" / "stage9-memprobe-1.0.0.jar"
PROBE_SHA = "43180768D3F738EF12CDA5A76BDF49062370E502953A7718527DEC228C7ECC56"
CENTRES = [(0, 0), (40, -24), (-56, 32), (96, 80), (-120, -104)]
SETS = {"S1": CENTRES, "S4": [(x, z - 16) for x, z in CENTRES], "S2": [(x + 16, z) for x, z in CENTRES],
        "S3": [(x, z + 16) for x, z in CENTRES]}
COMPARED = ("block_states", "biomes", "Heightmaps", "structures", "block_entities", "Status", "entities")
IGNORED = {"LastUpdate", "InhabitedTime", "isLightOn", "DataVersion", "xPos", "yPos", "zPos", "sections"}
FULL = "minecraft:full"


def sha256(p: pathlib.Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest().upper()


def chunks_of(centres) -> list[tuple[int, int]]:
    return [(cx + dx, cz + dz) for cx, cz in centres for dx in range(-8, 8) for dz in range(-8, 8)]


ALL = [c for name in ("S1", "S4", "S2", "S3") for c in chunks_of(SETS[name])]


# ---- minimal NBT / region reader (read-only) -------------------------------------------------------------------
def _nbt(buf: io.BytesIO, tag: int):
    r = buf.read
    if tag == 1:
        return struct.unpack(">b", r(1))[0]
    if tag == 2:
        return struct.unpack(">h", r(2))[0]
    if tag == 3:
        return struct.unpack(">i", r(4))[0]
    if tag == 4:
        return struct.unpack(">q", r(8))[0]
    if tag == 5:
        return struct.unpack(">f", r(4))[0]
    if tag == 6:
        return struct.unpack(">d", r(8))[0]
    if tag == 7:
        return bytes(r(struct.unpack(">i", r(4))[0]))
    if tag == 8:
        return r(struct.unpack(">H", r(2))[0]).decode("utf-8", "surrogatepass")
    if tag == 9:
        inner, n = struct.unpack(">bi", r(5))
        return [_nbt(buf, inner) for _ in range(n)]
    if tag == 10:
        out = {}
        while True:
            t = r(1)[0]
            if t == 0:
                return out
            name = r(struct.unpack(">H", r(2))[0]).decode("utf-8", "surrogatepass")
            out[name] = _nbt(buf, t)
    if tag == 11:
        return bytes(r(4 * struct.unpack(">i", r(4))[0]))
    if tag == 12:
        return bytes(r(8 * struct.unpack(">i", r(4))[0]))
    raise ValueError(f"bad NBT tag {tag}")


def read_chunk(folder: pathlib.Path, cx: int, cz: int):
    path = folder / f"r.{cx >> 5}.{cz >> 5}.mca"
    if not path.exists():
        return None
    with open(path, "rb") as f:
        f.seek(4 * ((cx & 31) + (cz & 31) * 32))
        loc = struct.unpack(">I", f.read(4))[0]
        if loc == 0:
            return None
        f.seek((loc >> 8) * 4096)
        length, kind = struct.unpack(">ib", f.read(5))
        data = f.read(length - 1)
    if kind & 0x80:
        data = (folder / f"c.{cx}.{cz}.mcc").read_bytes()
        kind &= 0x7F
    if kind == 2:
        data = zlib.decompress(data)
    elif kind == 1:
        import gzip
        data = gzip.decompress(data)
    elif kind != 3:
        raise ValueError(f"unsupported chunk compression {kind} at {cx},{cz}")
    buf = io.BytesIO(data)
    assert buf.read(1)[0] == 10
    buf.read(struct.unpack(">H", buf.read(2))[0])
    return _nbt(buf, 10)


def canon(v) -> str:
    if isinstance(v, (bytes, bytearray)):
        return "b:" + hashlib.sha1(v).hexdigest()
    if isinstance(v, dict):
        return "{" + ",".join(f"{k}:{canon(v[k])}" for k in sorted(v)) + "}"
    if isinstance(v, list):
        return "[" + ",".join(canon(x) for x in v) + "]"
    return repr(v)


def h(s: str) -> str:
    return hashlib.sha1(s.encode()).hexdigest()


def digest_chunk(nbt: dict) -> dict:
    d: dict = {}
    for s in nbt.get("sections", []):
        for key in ("block_states", "biomes"):
            if key in s:
                d[f"{key}@{s.get('Y')}"] = h(canon(s[key]))
    for key, value in nbt.items():
        if key in IGNORED:
            continue
        if key == "block_entities":
            value = sorted(value, key=lambda be: (be.get("x"), be.get("y"), be.get("z")))
        if key == "structures" and isinstance(value, dict) and isinstance(value.get("References"), dict):
            # References are a LongSet per structure in vanilla (ChunkAccess.structuresRefences); their serialized
            # order follows the set's hash-table layout, which differs after a reload. Compare them as sets
            # (wgeq2 finding: 14 chunks, same 2 mineshaft references, different order). Starts stay exact.
            refs = {k: b"".join(sorted(v[i:i + 8] for i in range(0, len(v), 8))) if isinstance(v, (bytes, bytearray)) else v
                    for k, v in value["References"].items()}
            value = {**value, "References": refs}
        d[key] = h(canon(value))
    return d


def digest_entities(nbt: dict | None) -> str:
    ents = sorted((e.get("id"), *[round(p, 3) for p in e.get("Pos", [])]) for e in (nbt or {}).get("Entities", []))
    return h(repr(ents))


def field_of(key: str) -> str:
    return key.split("@", 1)[0]


def world_dirs(server_dir: pathlib.Path, level: str) -> tuple[pathlib.Path, pathlib.Path]:
    base = server_dir / level / "dimensions" / "minecraft" / "overworld"
    return base / "region", base / "entities"


def status_map(region: pathlib.Path, chunks) -> dict:
    """Saved status per chunk; a chunk being rewritten while the server runs is re-read once, else 'unreadable'."""
    out = {}
    for cx, cz in chunks:
        for attempt in (0, 1):
            try:
                nbt = read_chunk(region, cx, cz)
                out[f"{cx},{cz}"] = str(nbt.get("Status")) if nbt else "absent"
                break
            except (ValueError, struct.error, zlib.error, AssertionError, OSError, IndexError):
                out[f"{cx},{cz}"] = "unreadable"
                time.sleep(1)
    return out


def statuses(region: pathlib.Path, chunks) -> dict:
    return dict(sorted(collections.Counter(status_map(region, chunks).values()).items()))


def intermediate(hist: dict) -> int:
    return sum(n for s, n in hist.items() if s not in (FULL, "absent"))


# ---- server-run helpers ----------------------------------------------------------------------------------------
class Run:
    def __init__(self, arm_dir: pathlib.Path, name: str, server_dir: pathlib.Path, stack_id: str, heap: str, sysprops: dict):
        self.dir = arm_dir / name
        self.probe = self.dir / "memprobe.jsonl"
        props = dict(sysprops, **{"stage9.memprobe.out": str(self.probe)})
        self.srv = Server(server_dir, self.dir, heap=heap, sysprops=props, jfr=False)
        self.stack_id, self.rcon, self.rec = stack_id, None, {"run": name}

    def __enter__(self):
        self.srv.start()
        self.srv.wait_for(r"Done \(", timeout=900)
        if stack.STACKS[self.stack_id]["phoenium"]:
            _, line = self.srv.wait_for(r"PHOENIUM_STAGE2 started lifecycle=RUNNING", timeout=120)
            self.rec["phoeniumStart"] = line[line.find("lifecycle="):][:200]
        self.rec["phoeniumMem"] = [l[l.find("PHOENIUM_MEM"):][:200] for l in self.srv.lines if "PHOENIUM_MEM" in l][:3]
        self.rcon = Rcon("127.0.0.1", PORTS["rcon"], "stage9-local-test").connect()
        self.rec["freeze"] = self.cmd("tick freeze")
        if "frozen" not in self.rec["freeze"].lower():
            raise RuntimeError(f"tick freeze not confirmed: {self.rec['freeze']!r}")
        return self

    def cmd(self, text: str) -> str:
        reply = self.rcon.command(text)[:200]
        if re.search(SETUP_REJECTED, reply):
            raise RuntimeError(f"rejected: {text!r} -> {reply!r}")
        return reply

    def force(self, centres) -> None:
        for cx, cz in centres:
            x0, z0, x1, z1 = (cx - 8) * 16, (cz - 8) * 16, (cx + 7) * 16 + 15, (cz + 7) * 16 + 15
            reply = self.cmd(f"forceload add {x0} {z0} {x1} {z1}")
            if "Marked" not in reply:
                raise RuntimeError(f"forceload not applied: {reply!r}")

    def pending(self, chunks) -> list:
        return [(cx, cz) for cx, cz in chunks if "Test passed" not in self.rcon.command(f"execute if loaded {cx * 16} 0 {cz * 16}")]

    def wait_loaded(self, chunks, key: str, timeout: float = 1200) -> None:
        t0, left = time.time(), list(chunks)
        while left and time.time() - t0 < timeout:
            left = self.pending(left)
            if left:
                time.sleep(3)
        self.rec[key] = round(time.time() - t0, 1)
        if left:
            raise RuntimeError(f"{len(left)} forced chunks never reached ENTITY_TICKING, e.g. {left[:5]}")

    def holders(self) -> int | None:
        if not self.probe.exists():
            return None
        last = None
        for line in self.probe.read_text(encoding="utf-8", errors="replace").splitlines()[-200:]:
            try:
                row = json.loads(line)
            except ValueError:
                continue
            if row.get("kind") == "deep" and row.get("level") == "minecraft:overworld":
                last = row.get("holders")
        return last

    def stop(self, flush: bool = True) -> None:
        self.rec["unforce"] = self.cmd("forceload remove all")
        if flush:
            self.rec["save"] = self.cmd("save-all flush")
        self.rcon.close()
        self.rcon = None
        self.rec["stop"] = self.srv.stop()

    def __exit__(self, *exc):
        if exc[0] is not None:
            self.rec["error"] = "".join(traceback.format_exception(*exc))[-1500:]
            try:
                if self.rcon:
                    self.rcon.close()
            except OSError:
                pass
            if self.srv.proc and self.srv.proc.poll() is None:
                self.rec["stop"] = self.srv.stop()
        self.rec["badLines"] = self.srv.bad[:20]
        return False


def apply_c2me(toml: pathlib.Path, overrides: list[str]) -> None:
    """Set c2me.toml keys: "section.key=value", or "key=value" for a top-level key (before the first [section])."""
    lines = toml.read_text(encoding="utf-8").splitlines()
    for kv in overrides:
        key, value = kv.split("=", 1)
        section, key_name = key.rsplit(".", 1) if "." in key else ("", key)
        if section:
            start, end = next(i for i, l in enumerate(lines) if l.strip() == f"[{section}]"), len(lines)
        else:
            start, end = -1, next(i for i, l in enumerate(lines) if l.strip().startswith("["))
        hit = next(i for i in range(start + 1, end) if re.match(rf"\s*{re.escape(key_name)}\s*=", lines[i]))
        lines[hit] = f"{re.match(r'\s*', lines[hit]).group(0)}{key_name} = {value}"
    toml.write_text("\n".join(lines) + "\n", encoding="utf-8")


def run_arm(out: pathlib.Path, stack_id: str, name: str, jar: str | None, c2me: list[str], heap: str) -> dict:
    arm_dir = out / name
    arm_dir.mkdir(parents=True)
    server_dir = arm_dir / "server"
    level = f"S9-wgeq-{name}"
    res: dict = {"arm": name, "jar": None, "c2me": c2me, "runs": [], "paths": {}}
    res["install"] = stack.build(stack_id, server_dir, PORTS, level, synthetic_bedrock=False,
                                 props={"level-seed": SEED, "view-distance": "8", "simulation-distance": "6"})
    shutil.copy2(PROBE, server_dir / "mods" / PROBE.name)
    if jar:
        p = pathlib.Path(jar)
        old = [m for m in (server_dir / "mods").iterdir() if m.name.lower().startswith("phoenium")]
        if len(old) != 1:
            raise SystemExit(f"jar override needs exactly one Phoenium jar, found {old}")
        old[0].unlink()
        shutil.copy2(p, server_dir / "mods" / p.name)
        res["jar"] = {"name": p.name, "sha256": sha256(p), "replaced": old[0].name}
    if c2me:
        toml = server_dir / "config" / "c2me.toml"
        apply_c2me(toml, c2me)
        res["c2meTomlSha256"] = sha256(toml)
    sysprops = dict(stack.STACKS[stack_id].get("sysprops", {}))
    region, entities = world_dirs(server_dir, level)
    try:
        # run 1: fresh generation, in-run unload, in-run reload of the rim protos
        r1 = Run(arm_dir, "r1", server_dir, stack_id, heap, sysprops)
        res["runs"].append(r1.rec)
        with r1:
            r1.force(SETS["S1"])
            r1.wait_loaded(chunks_of(SETS["S1"]), "s1LoadS")
            time.sleep(12)
            peak = r1.holders()
            r1.cmd("forceload remove all")
            time.sleep(3)
            # `tick freeze` also skips ticket expiry (purgeStaleTickets): the 1-tick "unknown" tickets that FULL chunks
            # pick up would pin them forever (wgeq2 finding). Unfreeze only while nothing is forced and nobody is online:
            # no chunk is block/entity-ticking, so nothing changes the world; the stale tickets expire, chunks unload.
            r1.rec["unfreeze"] = r1.cmd("tick unfreeze")
            t0, trace = time.time(), []
            while time.time() - t0 < 300:
                time.sleep(10)
                trace.append(r1.holders())
                if peak and trace[-1] is not None and trace[-1] <= max(300, 0.2 * peak):
                    break
            r1.rec["refreeze"] = r1.cmd("tick freeze")
            if "frozen" not in r1.rec["refreeze"].lower():
                raise RuntimeError(f"tick freeze not confirmed: {r1.rec['refreeze']!r}")
            r1.rec["unload"] = {"holdersAtPeak": peak, "trace": trace, "seconds": round(time.time() - t0)}
            r1.cmd("save-all flush")
            time.sleep(3)
            res["paths"]["inRunReload.S4beforeReload"] = statuses(region, chunks_of(SETS["S4"]))
            res["paths"]["inRunReload.S1afterUnload"] = statuses(region, chunks_of(SETS["S1"]))
            r1.force(SETS["S4"])
            r1.wait_loaded(chunks_of(SETS["S4"]), "s4LoadS")
            r1.stop()
        # run 2: restart resume (S2) and interrupted generation (S3)
        res["paths"]["restart.S2beforeResume"] = statuses(region, chunks_of(SETS["S2"]))
        s3_before = status_map(region, chunks_of(SETS["S3"]))
        res["paths"]["restart.S3beforeResume"] = dict(sorted(collections.Counter(s3_before.values()).items()))
        r2 = Run(arm_dir, "r2", server_dir, stack_id, heap, sysprops)
        res["runs"].append(r2.rec)
        with r2:
            r2.force(SETS["S2"])
            r2.wait_loaded(chunks_of(SETS["S2"]), "s2LoadS")
            # Interrupted generation. `forceload add` generates synchronously in 26.2 (ServerLevel.setChunkForced ->
            # getChunk), so it can never be caught mid-generation (wgeq2 finding). Chunky (part of L5, so in every arm)
            # generates asynchronously. Nothing is forced and nobody is online during this phase, so the server may
            # tick normally: no chunk is block/entity-ticking and nothing changes the world.
            r2.cmd("forceload remove all")
            time.sleep(3)
            r2.rec["unfreeze"] = r2.cmd("tick unfreeze")
            cx, cz = SETS["S3"][0]
            r2.rec["chunky"] = [r2.rcon.command(c)[:200] for c in
                                ("chunky world minecraft:overworld", "chunky shape square", f"chunky center {cx * 16} {cz * 16}",
                                 "chunky radius 120")]
            start = r2.rcon.command("chunky start")[:200]
            r2.rec["chunky"].append(start)
            if "start" not in start.lower():
                raise RuntimeError(f"chunky did not start: {start!r}")
            t0, progress, processed = time.time(), [], 0
            while time.time() - t0 < 60:      # stop while the square is mid-generation
                time.sleep(0.25)
                p = r2.rcon.command("chunky progress")[:200]
                progress.append(p)
                m = re.search(r"Processed: (\d+)", p)
                processed = int(m.group(1)) if m else processed
                if processed >= 24 or (not m and time.time() - t0 > 1.5):
                    break
            r2.rec["interruptedAt"] = {"chunkyProcessed": processed, "progress": progress[-2:], "seconds": round(time.time() - t0, 1)}
            r2.stop(flush=False)
        s3_after = status_map(region, chunks_of(SETS["S3"]))
        res["paths"]["interrupted.S3afterStop"] = dict(sorted(collections.Counter(s3_after.values()).items()))
        # chunks whose generation advanced in run 2 but did not finish: interrupted mid-generation
        res["paths"]["interrupted.advancedNotFull"] = sum(1 for k, v in s3_after.items() if v != s3_before.get(k) and v != FULL)
        # run 3: finish the interrupted squares
        r3 = Run(arm_dir, "r3", server_dir, stack_id, heap, sysprops)
        res["runs"].append(r3.rec)
        with r3:
            r3.rec["chunkyCancel"] = [r3.rcon.command("chunky cancel")[:200], r3.rcon.command("chunky confirm")[:200]]
            r3.force(SETS["S3"])
            r3.wait_loaded(chunks_of(SETS["S3"]), "s3LoadS")
            r3.stop()
    except Exception:
        res["error"] = traceback.format_exc()[-1500:]
    if "error" in res or any("error" in r for r in res["runs"]):
        res.setdefault("error", next((r["error"] for r in res["runs"] if "error" in r), "?"))
        return res
    unload = res["runs"][0].get("unload") or {}
    res["exercised"] = {
        "inRunReload": intermediate(res["paths"]["inRunReload.S4beforeReload"]),
        "restartResume": intermediate(res["paths"]["restart.S2beforeResume"]),
        "interrupted": res["paths"]["interrupted.advancedNotFull"],
        "unloaded": int(bool(unload.get("trace")) and unload["trace"][-1] is not None
                        and unload["trace"][-1] <= max(300, 0.2 * (unload.get("holdersAtPeak") or 0)))}
    digests, final = {}, collections.Counter()
    for cx, cz in ALL:
        nbt = read_chunk(region, cx, cz)
        if nbt is None:
            final["absent"] += 1
            continue
        d = digest_chunk(nbt)
        d["entities"] = digest_entities(read_chunk(entities, cx, cz))
        digests[f"{cx},{cz}"] = d
        final[str(nbt.get("Status"))] += 1
    res["finalStatuses"], res["chunks"] = dict(final), len(digests)
    res["allSaved"] = all((r.get("stop") or {}).get("allDimensionsSaved") for r in res["runs"])
    res["badLines"] = [l for r in res["runs"] for l in r.get("badLines", [])]
    (arm_dir / "digest.json").write_text(json.dumps(digests), encoding="utf-8")
    for name_ in ("libraries", "versions"):
        shutil.rmtree(server_dir / name_, ignore_errors=True)
    return res


def compare(a: dict, b: dict) -> dict:
    diff: dict = collections.defaultdict(set)
    samples: dict = collections.defaultdict(list)
    for pos in sorted(set(a) | set(b)):
        da, db = a.get(pos), b.get(pos)
        if da is None or db is None:
            diff["<missing>"].add(pos)
            continue
        for key in sorted(set(da) | set(db)):
            if da.get(key) != db.get(key):
                f = field_of(key)
                diff[f].add(pos)
                if len(samples[f]) < 8:
                    samples[f].append(f"{pos}:{key}")
    counts = {f: len(v) for f, v in diff.items()}
    compared = {f: n for f, n in counts.items() if f in COMPARED or f == "<missing>"}
    return {"chunksDifferingByField": compared, "otherFields": {f: n for f, n in counts.items() if f not in compared},
            "samples": dict(samples), "equal": not compared}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("out_id")
    ap.add_argument("stack", choices=sorted(stack.STACKS))
    ap.add_argument("--arm", action="append", required=True, help="name[|jar=<path>][|c2me=<section.key=value>]...")
    ap.add_argument("--heap", default="4G")
    ap.add_argument("--c2me-all", action="append", default=[],
                    help="c2me.toml override applied to EVERY arm (test condition, e.g. globalExecutorParallelism=1 for "
                         "deterministic feature order); arms are controls/candidates by their own overrides only")
    a = ap.parse_args()
    out = GATES / "wgeq" / a.out_id
    out.mkdir(parents=True, exist_ok=False)
    if sha256(PROBE) != PROBE_SHA:
        raise SystemExit("memprobe jar hash mismatch")
    if sampler.port_in_use(PORTS["java"]) or sampler.port_in_use(PORTS["rcon"]):
        raise SystemExit("lead ports busy")
    result: dict = {"schema": "stage9-wgeq-v2", "id": a.out_id, "stack": a.stack, "seed": SEED, "sets": SETS,
                    "compared": COMPARED, "arms": [], "comparisons": {}}
    arms = []
    for spec in a.arm:
        parts = spec.split("|")
        arms.append((parts[0], next((p[4:] for p in parts[1:] if p.startswith("jar=")), None),
                     [p[5:] for p in parts[1:] if p.startswith("c2me=")]))
    digests = {}
    result["c2meAll"] = a.c2me_all
    for name, jar, c2me in arms:
        print(time.strftime("%H:%M:%S"), "arm", name, flush=True)
        res = run_arm(out, a.stack, name, jar, list(a.c2me_all) + c2me, a.heap)
        result["arms"].append(res)
        (out / "result.json").write_text(json.dumps(result, indent=1), encoding="utf-8")
        if "error" in res:
            result["verdict"] = f"FAIL: arm {name} did not run"
            (out / "result.json").write_text(json.dumps(result, indent=1), encoding="utf-8")
            print(result["verdict"], res["error"][-600:], flush=True)
            return 1
        digests[name] = json.loads((out / name / "digest.json").read_text(encoding="utf-8"))
        print(time.strftime("%H:%M:%S"), name, json.dumps({k: res.get(k) for k in ("exercised", "finalStatuses")}), flush=True)
    ref = arms[0][0]
    for name, _, _ in arms[1:]:
        result["comparisons"][f"{ref}~{name}"] = compare(digests[ref], digests[name])
    controls = [n for n, j, c in arms[1:] if not j and not c]
    candidates = [n for n, j, c in arms[1:] if j or c]
    problems = []
    for r in result["arms"]:
        if r.get("badLines"):
            problems.append(f"{r['arm']}: bad log lines")
        if not r.get("allSaved"):
            problems.append(f"{r['arm']}: a run did not save cleanly")
        if r.get("chunks") != len(ALL) or set(r.get("finalStatuses", {})) != {FULL}:
            problems.append(f"{r['arm']}: forced chunks not all saved FULL {r.get('finalStatuses')}")
        for path, n in (r.get("exercised") or {}).items():
            if not n:
                problems.append(f"{r['arm']}: path {path} not exercised")
    if problems:
        result["verdict"], rc = "FAIL: " + "; ".join(problems), 1
    elif not controls:
        result["verdict"], rc = "FAIL: no repeat control arm", 1
    elif any(not result["comparisons"][f"{ref}~{n}"]["equal"] for n in controls):
        result["verdict"], rc = "INCONCLUSIVE: the controls differ (generation is not run-to-run identical)", 3
    elif all(result["comparisons"][f"{ref}~{n}"]["equal"] for n in candidates):
        result["verdict"], rc = (f"PASS: {len(ALL)} forced chunks identical on {list(COMPARED)} in every arm; "
                                 "fresh, in-run unload/reload, restart resume and interrupted generation exercised"), 0
    else:
        result["verdict"], rc = "FAIL: a candidate arm saved a different world", 1
    (out / "result.json").write_text(json.dumps(result, indent=1), encoding="utf-8")
    print(result["verdict"], json.dumps(result["comparisons"])[:1500], flush=True)
    return rc


if __name__ == "__main__":
    sys.exit(main())
