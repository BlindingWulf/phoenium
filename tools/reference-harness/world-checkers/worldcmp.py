"""Terrain equivalence between two worlds (same seed): per-chunk hashes of block states, biomes and heightmaps,
read straight from the overworld region files. Used to check C2ME native vs Java-fallback generation.

  python worldcmp.py <worldA> <worldB> [--radius-blocks 256]
Only chunks with Status minecraft:full that exist in both worlds are compared. Reports identical / differing chunks
and, per differing chunk, which component differs (blocks, biomes, heightmaps). Entities, block entities, lighting and
timestamps are ignored (they legitimately differ run to run).
"""
from __future__ import annotations

import gzip
import hashlib
import json
import struct
import sys
import zlib
from pathlib import Path

import regions


class R:
    def __init__(self, b):
        self.b, self.i = b, 0

    def u(self, fmt):
        v = struct.unpack_from(">" + fmt, self.b, self.i)
        self.i += struct.calcsize(">" + fmt)
        return v[0]

    def s(self):
        n = self.u("H")
        v = self.b[self.i:self.i + n].decode("utf-8", "replace")
        self.i += n
        return v


def payload(r: R, t: int):
    if t == 1: return r.u("b")
    if t == 2: return r.u("h")
    if t == 3: return r.u("i")
    if t == 4: return r.u("q")
    if t == 5: return r.u("f")
    if t == 6: return r.u("d")
    if t == 7:
        n = r.u("i"); v = r.b[r.i:r.i + n]; r.i += n; return bytes(v)
    if t == 8: return r.s()
    if t == 9:
        et = r.u("b"); n = r.u("i"); return [payload(r, et) for _ in range(n)]
    if t == 10:
        d = {}
        while True:
            tt = r.u("b")
            if tt == 0:
                return d
            k = r.s()
            d[k] = payload(r, tt)
    if t == 11:
        n = r.u("i"); v = r.b[r.i:r.i + 4 * n]; r.i += 4 * n; return bytes(v)
    if t == 12:
        n = r.u("i"); v = r.b[r.i:r.i + 8 * n]; r.i += 8 * n; return bytes(v)
    raise ValueError(f"bad tag {t}")


def read_root(raw: bytes):
    r = R(raw)
    t = r.u("b"); r.s()
    return payload(r, t)


def canon(v) -> bytes:
    return json.dumps(v, sort_keys=True, default=lambda b: b.hex()).encode()


def chunk_digests(world: Path, radius=None):
    rd = regions.region_dir(world)
    out = {}
    for f in sorted(rd.glob("r.*.*.mca")):
        _, rx, rz, _ = f.name.split(".")
        rx, rz = int(rx), int(rz)
        data = f.read_bytes()
        if len(data) < 8192:
            continue
        for idx in range(1024):
            off = struct.unpack(">I", data[idx * 4:idx * 4 + 4])[0]
            if off == 0:
                continue
            x, z = rx * 32 + idx % 32, rz * 32 + idx // 32
            if radius is not None and not (-(radius >> 4) <= x <= radius >> 4 and -(radius >> 4) <= z <= radius >> 4):
                continue
            p = (off >> 8) * 4096
            length = struct.unpack(">I", data[p:p + 4])[0]
            comp = data[p + 4]
            body = data[p + 5:p + 4 + length]
            raw = zlib.decompress(body) if comp == 2 else gzip.decompress(body) if comp == 1 else body if comp == 3 else None
            if raw is None:
                continue
            root = read_root(raw)
            if root.get("Status") != "minecraft:full":
                continue
            secs = sorted(root.get("sections", []), key=lambda s: s.get("Y", 0))
            blocks = hashlib.sha256(canon([[s.get("Y"), s.get("block_states")] for s in secs])).hexdigest()[:16]
            biomes = hashlib.sha256(canon([[s.get("Y"), s.get("biomes")] for s in secs])).hexdigest()[:16]
            hm = hashlib.sha256(canon({k: v for k, v in (root.get("Heightmaps") or {}).items()
                                       if k in ("WORLD_SURFACE_WG", "OCEAN_FLOOR_WG", "WORLD_SURFACE", "OCEAN_FLOOR")})).hexdigest()[:16]
            out[(x, z)] = (blocks, biomes, hm)
    return out


def compare(a: Path, b: Path, radius=None):
    da, db = chunk_digests(a, radius), chunk_digests(b, radius)
    common = sorted(set(da) & set(db))
    diff = {"blocks": 0, "biomes": 0, "heightmaps": 0}
    differing = []
    for k in common:
        if da[k] != db[k]:
            parts = [n for n, x, y in zip(("blocks", "biomes", "heightmaps"), da[k], db[k]) if x != y]
            for p in parts:
                diff[p] += 1
            differing.append({"chunk": k, "differs": parts})
    return {"chunksA": len(da), "chunksB": len(db), "compared": len(common), "identical": len(common) - len(differing),
            "differingChunks": len(differing), "componentDiffs": diff, "examples": differing[:15]}


if __name__ == "__main__":
    a = sys.argv[1:]
    rad = int(a[a.index("--radius-blocks") + 1]) if "--radius-blocks" in a else None
    print(json.dumps(compare(Path(a[0]), Path(a[1]), rad), indent=1, default=str))
