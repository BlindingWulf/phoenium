"""Exact native furnace NBT and saved-reader readback, using preserved MCA copies."""
import argparse
import gzip
import json
from pathlib import Path
import zlib
from run_stage4_acceptance import decode_nbt, sha256, block_state_at
from compare_stage5_gameplay import require, validate_intervals

def chunk_nbt(root, x, z):
    matches = [p for p in Path(root).rglob(f"r.{x >> 5}.{z >> 5}.mca")
               if p.parent.name == "region" and "overworld" in p.parts]
    require(len(matches) == 1, "missing/ambiguous overworld region file")
    path = matches[0]; data = path.read_bytes(); index = (x & 31) + (z & 31) * 32
    offset = int.from_bytes(data[index*4:index*4+3], "big") * 4096
    require(offset >= 8192, "missing native chunk record")
    size = int.from_bytes(data[offset:offset+4], "big"); kind = data[offset+4]
    raw = data[offset+5:offset+4+size]
    require(len(raw) == size-1 and kind in (1,2,3), "invalid native chunk payload")
    decoded = decode_nbt(gzip.decompress(raw) if kind == 1 else zlib.decompress(raw) if kind == 2 else raw)
    require(isinstance(decoded,dict) and decoded["xPos"] == x and decoded["zPos"] == z, "wrong native chunk identity")
    return decoded, {"file":str(path),"sha256":sha256(path),"chunkX":x,"chunkZ":z}

def validate(saved, mca, reloaded=None, after=None):
    saved = json.loads(Path(saved).read_text()); receipts = {}; actual = []
    for row in saved["furnaces"]:
        key = (row["x"] >> 4, row["z"] >> 4)
        if key not in receipts: receipts[key] = chunk_nbt(mca,*key)
        nbt, _ = receipts[key]
        values = [be for be in nbt.get("block_entities",[]) if all(be.get(k)==row[k] for k in ("x","y","z"))]
        require(len(values)==1 and values[0]["id"]=="minecraft:furnace", "missing/duplicate/wrong saved block entity")
        be = values[0]; items = be.get("Items",[])
        require(len({item["Slot"] for item in items})==len(items), "duplicate saved inventory slot")
        slots = {item["Slot"]:item for item in items}
        for slot,itemid,field in ((0,"minecraft:iron_ore","ingredient"),(1,"minecraft:coal","fuel"),(2,"minecraft:iron_ingot","output")):
            item = slots.get(slot)
            require((item is None and row[field]==0) or (item is not None and item["id"]==itemid and item["count"]==row[field]),
                    "changed native saved inventory " + field)
        require(set(slots) <= {0,1,2}, "unexpected saved inventory slot")
        for native,field in (("lit_time_remaining","lit"),("cooking_time_spent","cooking"),("cooking_total_time","total")):
            require(be[native]==row[field], "changed native saved furnace timer " + field)
        state = block_state_at(nbt,row)
        require(state["Name"]=="minecraft:furnace" and state["Properties"]["lit"]==str(row["lit"]>0).lower(), "saved native block state differs")
        actual.append(be)
    if reloaded:
        reload = json.loads(Path(reloaded).read_text())
        require(reload["gameTime"]==saved["gameTime"] and reload["furnaces"]==saved["furnaces"], "native saved reader changed furnace state/clock")
    result = {"status":"PASS", "savedFurnaces":len(actual),"nativeBlockEntities":actual,
        "mcaReceipts":[receipt for _,receipt in receipts.values()],"savedReaderVerified":bool(reloaded)}
    if after:
        snapshot = json.loads(Path(after).read_text())
        result["afterRestartBodies"] = validate_intervals(snapshot["gameplay"],True)
        require(sum(row["output"] for row in snapshot["furnaces"])>sum(row["output"] for row in saved["furnaces"]), "reloaded furnaces did not complete work")
    return result

if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--saved",required=True);parser.add_argument("--mca",required=True)
    parser.add_argument("--reloaded");parser.add_argument("--after");parser.add_argument("--output",required=True)
    args=parser.parse_args()
    try: result=validate(args.saved,args.mca,args.reloaded,args.after)
    except Exception as error: result={"status":"FAIL","failure":str(error)}
    Path(args.output).write_text(json.dumps(result,indent=2)); print(json.dumps({k:v for k,v in result.items() if k not in ("nativeBlockEntities","afterRestartBodies")}))
    if result["status"]!="PASS":raise SystemExit(1)
