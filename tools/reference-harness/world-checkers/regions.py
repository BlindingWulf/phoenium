"""Independent on-disk evidence of what a world has persisted: counts chunks in the overworld's region files (.mca
location tables) and reads each chunk's saved Status, without starting Minecraft.

  python regions.py <local_world_dir> [--radius-blocks 512 --center 0 0]
Library: count(world_dir, radius, cx, cz) -> dict
"""
from __future__ import annotations

import gzip
import io
import json
import struct
import sys
import zlib
from pathlib import Path


def _nbt_find_status(data: bytes):
    """Minimal NBT scan: returns the root compound's "Status" string (or None)."""
    key = b"\x08\x00\x06Status"
    i = data.find(key)
    if i < 0:
        return None
    j = i + len(key)
    n = struct.unpack(">H", data[j:j + 2])[0]
    return data[j + 2:j + 2 + n].decode("utf-8", "replace")


def region_dir(world: Path) -> Path:
    for p in (world / "dimensions" / "minecraft" / "overworld" / "region", world / "region"):
        if p.is_dir():
            return p
    return world / "region"


def count(world: Path, radius: int | None = None, cx: int = 0, cz: int = 0, statuses: bool = True) -> dict:
    rd = region_dir(world)
    chunks = 0
    inside = 0
    status = {}
    bad = 0
    lo_c = (cx - radius) >> 4 if radius is not None else None
    hi_c = (cx + radius) >> 4 if radius is not None else None
    lo_z = (cz - radius) >> 4 if radius is not None else None
    hi_z = (cz + radius) >> 4 if radius is not None else None
    for f in sorted(rd.glob("r.*.*.mca")):
        _, rx, rz, _ = f.name.split(".")
        rx, rz = int(rx), int(rz)
        data = f.read_bytes()
        if len(data) < 8192:
            continue
        for idx in range(1024):
            off = struct.unpack(">I", data[idx * 4:idx * 4 + 4])[0]
            sector, count_ = off >> 8, off & 0xFF
            if sector == 0 or count_ == 0:
                continue
            chunks += 1
            x = rx * 32 + (idx % 32)
            z = rz * 32 + (idx // 32)
            if radius is not None and lo_c <= x <= hi_c and lo_z <= z <= hi_z:
                inside += 1
            if not statuses:
                continue
            p = sector * 4096
            try:
                length = struct.unpack(">I", data[p:p + 4])[0]
                comp = data[p + 4]
                body = data[p + 5:p + 4 + length]
                if comp == 2:
                    raw = zlib.decompress(body)
                elif comp == 1:
                    raw = gzip.decompress(body)
                elif comp == 3:
                    raw = body
                else:                       # 4 = LZ4 (not decoded here) or external chunk
                    status["undecoded"] = status.get("undecoded", 0) + 1
                    continue
                s = _nbt_find_status(raw) or "?"
                status[s] = status.get(s, 0) + 1
            except Exception:
                bad += 1
    out = {"regionFiles": len(list(rd.glob("r.*.*.mca"))), "chunks": chunks, "badChunks": bad, "statuses": status}
    if radius is not None:
        side = hi_c - lo_c + 1
        out.update({"expectedInRadius": side * side, "presentInRadius": inside})
    return out


if __name__ == "__main__":
    a = sys.argv[1:]
    r = int(a[a.index("--radius-blocks") + 1]) if "--radius-blocks" in a else None
    c = (int(a[a.index("--center") + 1]), int(a[a.index("--center") + 2])) if "--center" in a else (0, 0)
    print(json.dumps(count(Path(a[0]), r, *c), indent=1))
