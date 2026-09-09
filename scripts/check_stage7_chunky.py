"""Independent native-record and concurrent original-body checks for the bounded repair."""
import gzip
import json
from pathlib import Path
import zlib
from run_stage4_acceptance import decode_nbt, sha256


def requested_records(snapshot, positions):
    regions = {}
    records = []
    for position in positions:
        x, z = position["x"], position["z"]
        path = Path(snapshot) / f"dimensions/minecraft/overworld/region/r.{x >> 5}.{z >> 5}.mca"
        if path not in regions:
            regions[path] = path.read_bytes()
        data = regions[path]
        index = (x & 31) + (z & 31) * 32
        offset = int.from_bytes(data[index * 4:index * 4 + 3], "big") * 4096
        if not offset:
            raise AssertionError(f"Requested chunk {(x, z)} has no saved native record")
        length = int.from_bytes(data[offset:offset + 4], "big")
        payload = data[offset + 5:offset + 4 + length]
        compression = data[offset + 4]
        raw = gzip.decompress(payload) if compression == 1 else zlib.decompress(payload) if compression == 2 else payload
        if compression not in (1, 2, 3) or length < 1 or offset + 4 + length > len(data):
            raise AssertionError(f"Invalid native record for {(x, z)}")
        nbt = decode_nbt(raw)
        if (nbt.get("xPos"), nbt.get("zPos")) != (x, z) or nbt.get("Status") != "minecraft:full":
            raise AssertionError(f"Requested record {(x, z)} lacks its exact FULL result")
        if nbt.get("isLightOn") != 1 or not nbt.get("sections"):
            raise AssertionError(f"Requested record {(x, z)} lacks saved light/sections")
        records.append(dict(x=x, z=z, status=nbt["Status"], light=nbt["isLightOn"],
                            sections=len(nbt["sections"]), dataVersion=nbt.get("DataVersion")))
    return dict(status="PASS_REQUESTED_FULL_RECORDS", count=len(records), records=records,
                regions=[dict(path=str(p), sha256=sha256(p)) for p in regions])


def concurrent_activity(path):
    data = json.loads(Path(path).read_text())
    requests = data["requests"]
    if data["overflow"] or not requests or any(not r.get("endNanos") or r.get("failure") for r in requests):
        raise AssertionError("Missing, overflowed, failed or unacknowledged Chunky observations")
    def outstanding(nanos):
        return [r["id"] for r in requests if r["startNanos"] < nanos < r["endNanos"]]
    activity = [dict(event, outstandingRequestIds=outstanding(event["nanos"])) for event in data["activity"]
                if outstanding(event["nanos"])]
    before = {}
    progressed = []
    for event in activity:
        if event["kind"] != "furnace":
            continue
        key = tuple(event[k] for k in ("gameTime", "x", "y", "z"))
        if event["phase"] == "before":
            before[key] = event
        elif key in before:
            prior = before[key]
            common = set(prior["outstandingRequestIds"]) & set(event["outstandingRequestIds"])
            if common and (event["cooking"] > prior["cooking"] or event["output"] > prior["output"]):
                if prior["owner"] == "NONE" or prior["owner"] != event["owner"]:
                    raise AssertionError("Original furnace body lacks one retained owner")
                progressed.append(dict(before=prior, after=event, outstandingThroughout=sorted(common)))
    redstone = [e for e in activity if e["kind"] == "redstone"]
    # The declared circuit crosses chunk -7/-6 at x=-96; demand is chunks 6..14.
    boundary = any(e["x"] < -96 for e in redstone) and any(e["x"] >= -96 for e in redstone)
    if not progressed or not boundary or any(e["owner"] == "NONE" for e in redstone):
        raise AssertionError(f"Concurrent coverage incomplete: progressing bodies={len(progressed)}, boundary={boundary}")
    return dict(status="PASS_CONCURRENT_ORIGINAL_ACTIVITY", requestCount=len(requests),
                uniqueChunks=len({(r["x"], r["z"]) for r in requests}), progressedFurnaceBodies=len(progressed),
                furnaceOwners=sorted({e["before"]["owner"] for e in progressed}),
                redstoneWrites=len(redstone), boundaryActivity=boundary, bodies=progressed, redstone=redstone)
