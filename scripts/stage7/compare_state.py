#!/usr/bin/env python3
"""Vanilla-vs-Phoenium entity state differential over two headless census runs.

Both runs record `data get entity` output right after arrival (`nbtArrival`). This tool parses the SNBT, drops fields that
legitimately depend on timing or on the object identity, and reports every remaining difference per card. A difference is
a lead for investigation, not a verdict; fields that always differ for a given reason belong in VOLATILE below with a
comment saying why.

Usage: compare_state.py <vanilla run dir> <phoenium run dir> [--out report.json]
"""
from __future__ import annotations

import collections
import json
import pathlib
import re
import sys

# Fields that depend on how many ticks elapsed between the transfer and the RCON read, or on random state.
VOLATILE = {
    "Air", "FallDistance", "fall_distance", "HurtTime", "HurtByTimestamp", "PortalCooldown", "Age", "ticksFrozen", "Fire",
    "OnGround", "Motion", "Pos", "Rotation", "DeathTime", "AbsorptionAmount", "InLove", "Brain", "Health",
    "Time", "fuse", "Fuse", "life", "LifeTicks", "Lifetime", "CustomName", "Tags", "UUID", "ForcedAge",
    "attributes", "active_effects", "HandDropChances", "ArmorDropChances", "sleeping_pos", "last_hurt_by_mob_timestamp",
    "ageLocked", "EggLayTime", "TicksFrozen", "Delay", "life_ticks", "WarmupDelay", "Warmup",
}
TOKEN = re.compile(r"""\s*(?:(?P<str>"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(?P<punct>[{}\[\],:;])|(?P<word>[^\s{}\[\],:;"']+))""")


def parse(text: str):
    tokens = [m for m in TOKEN.finditer(text)]
    position = 0

    def take():
        nonlocal position
        position += 1
        return tokens[position - 1]

    def peek():
        return tokens[position] if position < len(tokens) else None

    def value():
        token = take()
        if token.group("punct") == "{":
            result = {}
            while True:
                nxt = peek()
                if nxt.group("punct") == "}":
                    take()
                    return result
                key = take()
                key_text = key.group("str")[1:-1] if key.group("str") else key.group("word")
                colon = take()
                assert colon.group("punct") == ":", colon.group(0)
                result[key_text] = value()
                if peek().group("punct") == ",":
                    take()
        if token.group("punct") == "[":
            items = []
            nxt = peek()
            if nxt.group("word") in ("I", "B", "L") and tokens[position + 1].group("punct") == ";":
                items.append(f"{take().group('word')};")
                take()
            while True:
                nxt = peek()
                if nxt.group("punct") == "]":
                    take()
                    return items
                items.append(value())
                if peek().group("punct") == ",":
                    take()
        if token.group("str"):
            return token.group("str")[1:-1]
        return token.group("word")

    return value()


def flatten(node, prefix: str = "") -> dict[str, str]:
    out: dict[str, str] = {}
    if isinstance(node, dict):
        for key, child in node.items():
            if key in VOLATILE:
                continue
            out.update(flatten(child, f"{prefix}.{key}" if prefix else key))
    elif isinstance(node, list):
        for index, child in enumerate(node):
            out.update(flatten(child, f"{prefix}[{index}]"))
    else:
        out[prefix] = str(node)
    return out


def body(text: str) -> str:
    return text[text.index("{"):]


def load(run: pathlib.Path) -> dict[str, dict]:
    receipt = json.loads((run / "headless-receipt.json").read_text(encoding="utf-8"))
    return {card["card"]: card for card in receipt["cards"]}


def main() -> int:
    vanilla, phoenium = load(pathlib.Path(sys.argv[1])), load(pathlib.Path(sys.argv[2]))
    differences: dict[str, dict] = {}
    field_counts: collections.Counter = collections.Counter()
    compared = 0
    for name in sorted(set(vanilla) & set(phoenium)):
        v, p = vanilla[name], phoenium[name]
        if v["outcome"] != "TRANSFERRED" or p["outcome"] != "TRANSFERRED":
            continue
        v_text, p_text = v["witness"].get("nbtArrival"), p["witness"].get("nbtArrival")
        if not v_text or not p_text or "{" not in v_text or "{" not in p_text:
            continue
        compared += 1
        try:
            flat_v, flat_p = flatten(parse(body(v_text))), flatten(parse(body(p_text)))
        except (AssertionError, AttributeError, IndexError) as error:
            differences[name] = {"parseError": str(error)}
            continue
        changed = {key: (flat_v.get(key), flat_p.get(key)) for key in sorted(set(flat_v) | set(flat_p))
                   if flat_v.get(key) != flat_p.get(key)}
        if changed:
            differences[name] = changed
            for key in changed:
                field_counts[re.sub(r"\[\d+\]", "[]", key)] += 1
    print(f"compared {compared} transferred cards; {len(differences)} with state differences")
    for key, count in field_counts.most_common(25):
        print(f"  {count:4d} cards differ in {key}")
    for name, changed in list(differences.items())[:25]:
        print(" ", name, dict(list(changed.items())[:4]))
    if "--out" in sys.argv:
        pathlib.Path(sys.argv[sys.argv.index("--out") + 1]).write_text(json.dumps(
            {"compared": compared, "fieldCounts": field_counts, "differences": differences}, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
