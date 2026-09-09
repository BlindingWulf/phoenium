"""Read saved Stage 6 evidence without starting servers or altering observations."""
import hashlib
import json
from pathlib import Path
import re
import zlib

from run_stage4_acceptance import decode_nbt

ROOT = Path(__file__).resolve().parents[1]
AUDIT = ROOT / "build/stage6-audit-20260909"


def region_records(path):
    data = path.read_bytes()
    for index in range(1024):
        offset = int.from_bytes(data[index * 4:index * 4 + 3], "big") * 4096
        if not offset:
            continue
        size = int.from_bytes(data[offset:offset + 4], "big")
        compression = data[offset + 4]
        if compression != 2:
            raise ValueError(f"Unsupported MCA compression {compression}: {path}")
        yield decode_nbt(zlib.decompress(data[offset + 5:offset + 4 + size]))


def persisted(snapshot):
    orbs, terrain, structures, light = [], {}, [], {}
    for path in sorted(snapshot.rglob("*.mca")):
        for record in region_records(path):
            if path.parent.name == "entities":
                for entity in record.get("Entities", []):
                    if entity.get("id") == "minecraft:experience_orb":
                        mapping = entity.get("clumpedMap")
                        total = (sum(int(value) * count for value, count in mapping.items())
                                 if mapping else entity.get("Value", 0) * entity.get("Count", 1))
                        orbs.append({key: entity[key] for key in ("Value", "Count", "clumpedMap", "Pos") if key in entity} | {"totalXP": total})
            elif path.parent.name == "region":
                x, z = record.get("xPos"), record.get("zPos")
                key = str(path.parent.relative_to(snapshot)) + f"/{x},{z}"
                sections = record.get("sections", [])
                canonical = [(section["Y"], section.get("block_states"), section.get("biomes")) for section in sections]
                terrain[key] = hashlib.sha256(json.dumps(canonical, sort_keys=True).encode()).hexdigest()
                for name, start in record.get("structures", {}).get("starts", {}).items():
                    if start.get("id") != "INVALID":
                        structures.append({"chunk": key, "type": name, "pieces": len(start.get("Children", []))})
                if x == 5 and z == 0 and "overworld" in key:
                    section = next((section for section in sections if section["Y"] == 4), {})
                    packed = section.get("BlockLight", "")
                    if isinstance(packed, str) and packed:
                        data = bytes.fromhex(packed)
                        index = (1 << 8) | (8 << 4) | 9
                        light[key] = {"sample89_65_8": (data[index // 2] >> (4 * (index % 2))) & 15,
                                      "isLightOn": record.get("isLightOn")}
    return {"orbCount": len(orbs), "totalXP": sum(orb["totalXP"] for orb in orbs), "orbs": orbs,
            "terrain": terrain, "structures": structures, "light": light}


def main():
    results = {}
    for directory in sorted((AUDIT / "runs").iterdir()):
        report = directory / "report.json"
        if not report.exists():
            continue
        result = json.loads(report.read_text())
        result["native"] = {}
        result["shutdownReview"] = {}
        for phase in ("first", "restart"):
            snapshot = directory / (phase + "-mca")
            if snapshot.exists():
                result["native"][phase] = persisted(snapshot)
            log = directory / (phase + ".log")
            if log.exists():
                lines = log.read_text(encoding="utf-8").splitlines()
                result["shutdownReview"][phase] = [line for line in lines if any(marker in line for marker in
                    ("PHOENIUM_FULL STOPPED", "PHOENIUM_STAGE4_SHUTDOWN", "PHOENIUM_STAGE2 stopped",
                     "Encountered an unexpected exception", "Exception stopping the server"))]
            phase_report = directory / (phase + "-report.json")
            if phase_report.exists():
                raw = json.loads(phase_report.read_text())
                samples = raw.get("metrics", [])
                result.setdefault("resources", {})[phase] = {
                    "startupSeconds": raw.get("startupSeconds"),
                    "maxRssBytes": max((sample["rssBytes"] for sample in samples), default=None),
                    "maxThreads": max((sample["threads"] for sample in samples), default=None),
                    "lastCpuSeconds": samples[-1]["cpuSeconds"] if samples else None,
                    "note": "Whole-process screening only; unequal startup work is not a speed comparison."}
        results[directory.name] = result
    (AUDIT / "evidence-summary.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(json.dumps({name: {"status": result["status"], "native": {phase: {key: value for key, value in native.items() if key != "terrain"}
        for phase, native in result["native"].items()}} for name, result in results.items() if "clumps" in name or "xp-control" in name}, indent=2))


if __name__ == "__main__":
    main()
