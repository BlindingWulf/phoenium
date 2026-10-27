#!/usr/bin/env python3
"""Fetch the C2ME binary fixtures that 36 unit tests in dev.phoenium.chunk read (stdlib only, hash-verified).

The tests read these paths relative to the repository root (they are git-ignored, and `gradlew clean` deletes the
build/ ones, so run this script again after a clean):

  build/stage6-audit-20260909/research/artifacts/c2me/c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar   upstream C2ME (Modrinth)
  build/stage6-audit-20260909/research/c2me-mixins/c2me-fabric-rewrites-chunk-system-...jar     nested in the upstream jar
  build/stage8-entry/META-INF/jars/c2me-fabric-base-mc26.2-0.4.2-alpha.0.43-all.jar             nested in the upstream jar
  ../C2ME-locked-settlement/build/patched/c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar   Phoenium's patched C2ME
                                                                                                 (sibling directory!)

Usage:  python scripts/fetch_test_fixtures.py [--upstream-jar FILE] [--settlement-jar FILE] [--offline]
  --upstream-jar / --settlement-jar use local copies instead of downloading (still hash-checked).
C2ME is MIT-licensed (https://github.com/RelativityMC/C2ME-fabric); the settlement2 patches are in docs/patches/.
"""
import argparse
import hashlib
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UPSTREAM = ("https://cdn.modrinth.com/data/VSNURh3q/versions/jSMMstCy/c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar",
            "B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B")
SETTLEMENT = ("https://github.com/BlindingWulf/phoenium/releases/download/v0.2.0-beta.1/"
              "c2me-0.4.2-alpha.0.43%2B26.2-phoenium-settlement2.jar",
              "8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53")
OUTER = ROOT / "build/stage6-audit-20260909/research/artifacts/c2me/c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar"
NESTED = {
    "META-INF/jars/c2me-fabric-rewrites-chunk-system-mc26.2-0.4.2-alpha.0.43.jar":
        (ROOT / "build/stage6-audit-20260909/research/c2me-mixins/c2me-fabric-rewrites-chunk-system-mc26.2-0.4.2-alpha.0.43.jar",
         "76CEDA178849978682473C285B0A354BF611021EB59DD5FAB7ED18AC327D1F48"),
    "META-INF/jars/c2me-fabric-base-mc26.2-0.4.2-alpha.0.43-all.jar":
        (ROOT / "build/stage8-entry/META-INF/jars/c2me-fabric-base-mc26.2-0.4.2-alpha.0.43-all.jar",
         "734D62AAD5440ACFFD3BBCB6A8C76904261AC250C223CBF90B90396D0FE377EB"),
}
PATCHED = ROOT.parent / "C2ME-locked-settlement/build/patched/c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest().upper()


def place(target: Path, url: str, want: str, local: str | None, offline: bool) -> None:
    if target.is_file() and sha256(target) == want:
        print(f"ok      {target}")
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(target.suffix + ".part")
    if local:
        shutil.copyfile(local, tmp)
    elif offline:
        sys.exit(f"missing {target} (offline; pass a local copy)")
    else:
        print(f"fetch   {url}")
        req = urllib.request.Request(url, headers={"User-Agent": "phoenium-fixture-fetch/1"})
        with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
    got = sha256(tmp)
    if got != want:
        tmp.unlink()
        sys.exit(f"SHA-256 mismatch for {target.name}: expected {want}, got {got}")
    tmp.replace(target)
    print(f"placed  {target}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--upstream-jar")
    ap.add_argument("--settlement-jar")
    ap.add_argument("--offline", action="store_true")
    a = ap.parse_args()
    place(OUTER, *UPSTREAM, a.upstream_jar, a.offline)
    with zipfile.ZipFile(OUTER) as z:
        for entry, (target, want) in NESTED.items():
            if target.is_file() and sha256(target) == want:
                print(f"ok      {target}")
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(z.read(entry))
            if sha256(target) != want:
                sys.exit(f"SHA-256 mismatch for nested {entry}")
            print(f"placed  {target}")
    place(PATCHED, *SETTLEMENT, a.settlement_jar, a.offline)
    print("C2ME test fixtures ready.")


if __name__ == "__main__":
    main()
