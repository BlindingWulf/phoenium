"""Inspect the actual release archive without loading or changing Minecraft."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

def audit(jar, root):
    with ZipFile(jar) as archive:
        names = archive.namelist()
        metadata = json.loads(archive.read("fabric.mod.json"))
        forbidden = [name for name in names if any(part in name.lower() for part in
            ("org/junit/", "clienttest/", "clientobs/", "stage4probe/", "stage4-acceptance",
             "stage5probe/", "testmixin/", "testharness/", "full-registration-probe", "adversestop/", "instrumentation/"))]
        forbidden_references = [name for name in names if name.endswith(".class") and any(
            marker in archive.read(name) for marker in
            (b"org/junit/", b"dev/phoenium/clienttest/", b"dev/phoenium/stage4probe/", b"dev/phoenium/stage5probe/", b"dev/phoenium/testmixin/"))]
        mixins = {name: json.loads(archive.read(name)) for name in metadata["mixins"]}
    props = dict(line.split("=", 1) for line in (root / "gradle.properties").read_text().splitlines() if "=" in line)
    pinned = {"minecraft_version": "26.2", "loader_version": "0.19.5", "fabric_version": "0.159.0+26.2", "loom_version": "1.17.20"}
    checks = {"serverOnly": metadata["environment"] == "server" and set(metadata["entrypoints"]) == {"server"},
              "pinnedDependencies": all(props.get(k) == v for k, v in pinned.items()),
              "nativeJavaAndMinecraft": metadata["depends"]["minecraft"] == "26.2" and metadata["depends"]["java"] == ">=25",
              "noTestClasses": not forbidden, "noTestReferences": not forbidden_references,
              "noClientMixins": all(not value.get("client") for value in mixins.values())}
    return {"kind": "stage4-production-packaging-audit", "jar": str(jar.resolve()),
            "sha256": hashlib.sha256(jar.read_bytes()).hexdigest().upper(), "entries": len(names),
            "checks": checks, "forbiddenEntries": forbidden, "forbiddenReferences": forbidden_references,
            "pinned": pinned, "metadata": metadata, "result": "PASS" if all(checks.values()) else "PARTIAL"}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=Path("build/libs/phoenium-0.2.0-stage2.jar"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.jar, Path(__file__).resolve().parents[1])
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["result"] == "PASS" else 1)
