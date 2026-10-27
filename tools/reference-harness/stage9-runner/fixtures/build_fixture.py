"""Build an immutable, hashed, pregenerated world fixture for Stage 9.

    python tools/stage9/fixtures/build_fixture.py --name pregen-r2560-v1 --radius 2560

Generation runs on the generic stack + Chunky (vanilla worldgen, no optimisation mods) with the Stage 9 pinned seed and
Phoenix server defaults, so every benchmark arm loads byte-identical chunks. The fixture directory gets world/,
FIXTURE.json (identity) and MANIFEST.sha256 (one line per file). Never edit a fixture after it is written."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import pathlib
import shutil
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from lib import stack  # noqa: E402
from lib.rcon import Rcon  # noqa: E402
from lib.server import Server  # noqa: E402
from scenarios import PROPS, SEED  # noqa: E402

AUDIT = stack.ROOT / ".audit" / "stage9-20261009"
PORTS = {"java": 25760, "rcon": 25860, "bedrock": 19260, "voice": 24560}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--radius", type=int, default=2560)
    ap.add_argument("--nether-radius", type=int, default=400)
    args = ap.parse_args()
    out = AUDIT / "fixtures" / args.name
    if out.exists():
        raise SystemExit(f"fixture exists (immutable): {out}")
    work = AUDIT / "fixtures" / f".build-{args.name}"
    level = "fixture"
    install = stack.build("FX-chunky", work / "server", PORTS, level, synthetic_bedrock=False,
                          props={**PROPS, "level-seed": SEED})
    srv = Server(work / "server", work, heap="6G", jfr=False)
    srv.start()
    try:
        srv.wait_for(r"Done \(", timeout=600)
        rcon = Rcon("127.0.0.1", PORTS["rcon"], "stage9-local-test").connect()
        for dim, radius in (("minecraft:overworld", args.radius), ("minecraft:the_nether", args.nether_radius)):
            for cmd in (f"chunky world {dim}", "chunky shape square", "chunky center 0 0", f"chunky radius {radius}",
                        "chunky start"):
                print(cmd, "->", rcon.command(cmd)[:120])
            srv.wait_for(rf"Task finished for {dim}", timeout=4 * 3600, start=len(srv.lines))
            print("finished", dim)
        rcon.command("save-all flush")
        rcon.close()
    finally:
        stop = srv.stop()
    print("stop", stop)
    if not stop.get("allDimensionsSaved"):
        raise SystemExit("server did not save cleanly; fixture rejected")
    out.mkdir(parents=True)
    shutil.copytree(work / "server" / level, out / "world")
    lines, total = [], 0
    for f in sorted((out / "world").rglob("*")):
        if f.is_file():
            if f.name == "session.lock":
                continue
            lines.append(f"{stack.sha256(f)}  {f.relative_to(out).as_posix()}")
            total += f.stat().st_size
    (out / "world" / "session.lock").unlink(missing_ok=True)
    manifest = "\n".join(lines) + "\n"
    (out / "MANIFEST.sha256").write_text(manifest)
    identity = {"name": args.name, "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                "seed": SEED, "overworldRadiusBlocks": args.radius, "netherRadiusBlocks": args.nether_radius,
                "shape": "square", "center": [0, 0], "generatorStack": "FX-chunky", "generatorMods": install["mods"],
                "serverProperties": PROPS, "files": len(lines), "bytes": total,
                "manifestSha256": hashlib.sha256(manifest.encode()).hexdigest().upper(),
                "rule": "immutable; runs copy world/ into their own server dir; never edit in place"}
    (out / "FIXTURE.json").write_text(json.dumps(identity, indent=2))
    shutil.rmtree(work)
    print(json.dumps({k: identity[k] for k in ("name", "files", "bytes", "manifestSha256")}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
