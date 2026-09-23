"""Pinned Minecraft 26.2 registry/caller census and fail-closed drift check.

The generated v2 census is an obligation ledger, not a coverage certificate.  No
disposition is assigned until a source proof or final-byte observation earns it.
The committed baseline is intentionally tied to one mapped source and binary.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import zipfile


PINNED_SOURCE_SHA = "055D5A99D49917A03F68007A1AC2F1D0CBD84E28A16D953C6FFC943CE3E15E1E"
PINNED_BINARY_SHA = "BA0569DEEC34D82FB3B31BC71F2400EE735E5711D4C4F54CE5ED9A789EA52892"
PINNED_IDS_SHA = "82AFD9E021B4C7070DF8D72D63E62991BD151BA9EA1E1780227F3FEBD0994C91"
ROUTES = ("NETHER_ENTRY", "NETHER_RETURN", "END_ENTRY", "END_EXIT", "END_GATEWAY")
METHODS = ("tick", "baseTick", "rideTick", "handlePortal", "teleport", "canUsePortal", "canTeleport")
GRAPH_SHAPES = {
    "boat-empty": "boat, no passengers",
    "boat-mob-rider": "boat with mob rider",
    "boat-player-rider": "boat with player rider",
    "boat-two-riders": "boat with two riders",
    "minecart-empty": "minecart, no passengers",
    "minecart-mob-rider": "minecart with mob rider",
    "minecart-player-rider": "minecart with player rider",
    "mob-on-mob": "mob riding mob",
    "player-on-mob": "player riding mob",
    "nested-graph": "nested passenger graph",
}
PORTAL_SOURCE = {
    "NETHER_ENTRY": ("net/minecraft/world/level/block/NetherPortalBlock.java", "entityInside", "getPortalDestination", "minecraft:overworld", "minecraft:the_nether"),
    "NETHER_RETURN": ("net/minecraft/world/level/block/NetherPortalBlock.java", "entityInside", "getPortalDestination", "minecraft:the_nether", "minecraft:overworld"),
    "END_ENTRY": ("net/minecraft/world/level/block/EndPortalBlock.java", "entityInside", "getPortalDestination", "minecraft:overworld", "minecraft:the_end"),
    "END_EXIT": ("net/minecraft/world/level/block/EndPortalBlock.java", "entityInside", "getPortalDestination", "minecraft:the_end", "respawnData.dimension()"),
    "END_GATEWAY": ("net/minecraft/world/level/block/EndGatewayBlock.java", "entityInside", "getPortalDestination", "minecraft:the_end", "minecraft:the_end"),
}
ALWAYS_NO_ENTRY = {"minecraft:ender_dragon", "minecraft:wither", "minecraft:fishing_bobber"}
NO_TICK_ENTRY = {"minecraft:block_display", "minecraft:glow_item_frame", "minecraft:interaction",
                 "minecraft:item_display", "minecraft:item_frame", "minecraft:leash_knot",
                 "minecraft:marker", "minecraft:painting", "minecraft:text_display"}
SOURCE_PROVEN_HURTING_NETHER = {
    "minecraft:fireball", "minecraft:small_fireball", "minecraft:dragon_fireball",
    "minecraft:wither_skull", "minecraft:wind_charge", "minecraft:breeze_wind_charge",
}
SOURCE_PROVEN_THROWABLE_NETHER = {
    "minecraft:egg", "minecraft:snowball", "minecraft:splash_potion",
    "minecraft:lingering_potion", "minecraft:experience_bottle",
}
SOURCE_PROVEN_ARROW_NETHER = {
    "minecraft:arrow": "net.minecraft.world.entity.projectile.arrow.Arrow",
    "minecraft:spectral_arrow": "net.minecraft.world.entity.projectile.arrow.SpectralArrow",
    "minecraft:trident": "net.minecraft.world.entity.projectile.arrow.ThrownTrident",
}
SOURCE_PROVEN_SHULKER_NETHER = {"minecraft:shulker_bullet"}
SOURCE_PROVEN_MINECART_NETHER = {"minecraft:minecart", "minecraft:chest_minecart"}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def file_sha(path: pathlib.Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest().upper()


def dump(path: pathlib.Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def source_member(binary_name: str) -> str:
    return binary_name.split("$", 1)[0].replace(".", "/") + ".java"


def source_method(source: str, method: str) -> dict | None:
    """Find a declaration and its lexical body; never claim semantic equivalence."""
    pattern = re.compile(r"\b(?:public|protected|private)\s+(?:[\w<>.?@\[\]]+\s+)*" + re.escape(method) + r"\s*\(")
    for match in pattern.finditer(source):
        start = match.start()
        opening = source.find("{", match.end())
        semicolon = source.find(";", match.end())
        if opening < 0 or (0 <= semicolon < opening):
            continue
        depth = 0
        closing = None
        for index in range(opening, len(source)):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    closing = index + 1
                    break
        if closing is None:
            continue
        body = source[start:closing]
        anchors = list(re.finditer(r"\b(?:this\.|super\.)?(?:handlePortal|baseTick|rideTick|tick)\s*\(", body))
        anchor = anchors[-1] if anchors else None
        suffix = body[anchor.end():] if anchor else ""
        return {
            "line": source.count("\n", 0, start) + 1,
            "sourceSha256": sha(body.encode()),
            "continuationAnchor": anchor.group(0) if anchor else None,
            "lexicalSuffixSha256": sha(suffix.encode()) if anchor else None,
            "lexicalSuffixBytes": len(suffix.encode()),
            "directCalls": sorted(set(re.findall(r"\b(?:this\.|super\.)?(?:handlePortal|baseTick|rideTick|tick)\s*\(", body))),
            "unconditionalReturnFalse": bool(re.fullmatch(r"\s*return\s+false\s*;\s*", body[body.find("{") + 1:body.rfind("}")])),
            "unconditionalReturnTrue": bool(re.fullmatch(r"\s*return\s+true\s*;\s*", body[body.find("{") + 1:body.rfind("}")])),
        }
    return None


def inspect_classes(javap: pathlib.Path, jar: pathlib.Path, class_names: set[str], members: set[str]) -> dict:
    known: dict[str, dict] = {}

    def binary(name: str) -> str:
        if name.replace(".", "/") + ".class" in members:
            return name
        parts = name.split(".")
        for i in range(len(parts) - 1, 0, -1):
            candidate = ".".join(parts[:i]) + "$" + "$".join(parts[i:])
            if candidate.replace(".", "/") + ".class" in members:
                return candidate
        return name

    def inspect(names: set[str]) -> None:
        requested = sorted({binary(name) for name in names if name not in known and name.startswith("net.minecraft.")})
        if not requested:
            return
        process = subprocess.run([str(javap), "-p", "-classpath", str(jar), *requested],
                                 capture_output=True, text=True, check=True)
        for block in process.stdout.split("Compiled from ")[1:]:
            lines = block.splitlines()
            header = next((line for line in lines if line.endswith("{") and (" class " in line or " interface " in line)), None)
            if not header:
                continue
            owner = re.search(r"\b(?:class|interface)\s+([\w.$]+)", header).group(1)
            parent = re.search(r"\bextends\s+([\w.$]+)", header)
            declared = sorted({hit.group(1) for line in lines for hit in [re.search(
                r"\b(tick|baseTick|rideTick|handlePortal|teleport|canUsePortal|canTeleport)\(", line)] if hit})
            known[owner] = {"super": parent.group(1) if parent else None, "declared": declared}

    inspect(class_names)
    frontier = {row["super"] for row in known.values() if row["super"]}
    while frontier:
        before = len(known)
        inspect(frontier)
        if len(known) == before:
            break
        frontier = {row["super"] for row in known.values() if row["super"] and row["super"] not in known}
    return known


def falling_materials(sources: zipfile.ZipFile) -> list[dict]:
    blocks = sources.read("net/minecraft/world/level/block/Blocks.java").decode()
    dye = sources.read("net/minecraft/world/item/DyeColor.java").decode()
    ids = sources.read("net/minecraft/references/BlockItemIds.java").decode()
    assert 'createSimpleColored("concrete_powder")' in ids
    assert "new ConcretePowderBlock(CONCRETE.pick(color), p)" in blocks
    colors = re.findall(r'^\s*([A-Z_]+)\(\d+,\s*"([a-z_]+)"', dye, re.M)
    assert len(colors) == 16, f"expected 16 pinned dye colors, got {len(colors)}"
    result = []
    constructors = ("SandBlock", "ColoredFallingBlock", "AnvilBlock", "DragonEggBlock", "BrushableBlock", "ScaffoldingBlock", "PointedDripstoneBlock", "SulfurSpikeBlock")
    declarations = list(re.finditer(r"public static final Block\s+([A-Z0-9_]+)\s*=\s*register\(", blocks))
    for index, match in enumerate(declarations):
        end = declarations[index + 1].start() if index + 1 < len(declarations) else len(blocks)
        fragment = blocks[match.start():end].split(";", 1)[0]
        ctor = next((name for name in constructors if re.search(
            rf"\b(?:new\s+{name}\s*\(|{name}::new\b)", fragment)), None)
        if ctor is None:
            continue
        name = match.group(1).lower()
        if ctor == "BrushableBlock" and name not in ("suspicious_sand", "suspicious_gravel"):
            continue
        if ctor == "PointedDripstoneBlock" and name != "pointed_dripstone":
            continue
        if ctor == "SulfurSpikeBlock":
            assert name == "sulfur_spike"
            sulfur = sources.read("net/minecraft/world/level/block/SulfurSpikeBlock.java").decode()
            assert re.search(r"class\s+SulfurSpikeBlock\s+extends\s+SpeleothemBlock\b", sulfur)
            speleothem = sources.read("net/minecraft/world/level/block/SpeleothemBlock.java").decode()
            assert "FallingBlockEntity.fall(level, fallPos, fallState)" in speleothem
        result.append({"blockState": "minecraft:" + name, "nativeProducer": ctor,
                       "sourceReference": {"file": "net/minecraft/world/level/block/Blocks.java",
                                           "line": blocks.count("\n", 0, match.start()) + 1},
                       **({"nativeFallCall": {"file": "net/minecraft/world/level/block/SpeleothemBlock.java",
                                               "line": speleothem.count("\n", 0,
                                                   speleothem.index("FallingBlockEntity.fall(level, fallPos, fallState)")) + 1}}
                          if ctor == "SulfurSpikeBlock" else {})})
    for _, color in colors:
        result.append({"blockState": f"minecraft:{color}_concrete_powder", "nativeProducer": "ConcretePowderBlock",
                       "sourceReference": {"file": "net/minecraft/world/level/block/Blocks.java", "line": 3682}})
    result.sort(key=lambda row: row["blockState"])
    names = {row["blockState"] for row in result}
    must = {"minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:anvil",
            "minecraft:chipped_anvil", "minecraft:damaged_anvil", "minecraft:dragon_egg",
            "minecraft:suspicious_sand", "minecraft:suspicious_gravel", "minecraft:scaffolding",
            "minecraft:pointed_dripstone", "minecraft:sulfur_spike"}
    assert must <= names, f"falling-state producer discovery missed {must - names}"
    assert len(names) == len(result), "duplicate falling block states"
    return result


def build_inventory(source_path: pathlib.Path, binary_path: pathlib.Path, javap: pathlib.Path) -> dict:
    source_sha, binary_sha = file_sha(source_path), file_sha(binary_path)
    if source_sha != PINNED_SOURCE_SHA or binary_sha != PINNED_BINARY_SHA:
        raise ValueError(f"mapped oracle fingerprint mismatch: source={source_sha}, binary={binary_sha}")
    with zipfile.ZipFile(source_path) as sources, zipfile.ZipFile(binary_path) as binary:
        entity_source = sources.read("net/minecraft/world/entity/EntityTypes.java").decode()
        ids_source = sources.read("net/minecraft/world/entity/EntityTypeIds.java").decode()
        registered = re.findall(r"public static final EntityType<([^>]+)>\s+(\w+)\s*=\s*register\(", entity_source)
        id_map = dict(re.findall(r'public static final ResourceKey<EntityType<\?>>\s+(\w+)\s*=\s*create\("([^"]+)"\)', ids_source))
        if len(registered) != 158 or {name for _, name in registered} != set(id_map):
            raise ValueError("pinned registry changed or parser no longer covers all registrations")
        imports = {name.rsplit(".", 1)[-1]: name for name in re.findall(r"import\s+(net\.minecraft\.[\w.]+);", entity_source)}
        members = set(binary.namelist())
        def binary_name(name: str) -> str:
            if name.replace(".", "/") + ".class" in members:
                return name
            parts = name.split(".")
            for i in range(len(parts) - 1, 0, -1):
                guess = ".".join(parts[:i]) + "$" + "$".join(parts[i:])
                if guess.replace(".", "/") + ".class" in members:
                    return guess
            raise ValueError(f"registry class absent from mapped binary: {name}")
        types = {}
        for java_type, constant in registered:
            simple = java_type.split(".")[0]
            source_name = imports.get(simple, "net.minecraft.world.entity." + simple) + java_type[len(simple):]
            types["minecraft:" + id_map[constant]] = binary_name(source_name)
        known = inspect_classes(javap, binary_path, set(types.values()), members)
        def chain(name: str) -> list[str]:
            result = []
            while name in known and name not in result:
                result.append(name)
                name = known[name]["super"]
            return result
        source_cache = {}
        def source_text(name: str) -> str:
            member = source_member(name)
            if member not in source_cache:
                source_cache[member] = sources.read(member).decode() if member in sources.namelist() else ""
            return source_cache[member]
        rows = []
        for entity_id, concrete in sorted(types.items()):
            hierarchy = chain(concrete)
            declaring = {method: next((owner for owner in hierarchy if method in known[owner]["declared"]), None)
                         for method in METHODS}
            references = {}
            for method, owner in declaring.items():
                if owner is None:
                    references[method] = None
                    continue
                src = source_text(owner)
                method_info = source_method(src, method)
                member = owner.replace(".", "/") + ".class"
                references[method] = {"owner": owner, "file": source_member(owner),
                                      "classBytecodeSha256": sha(binary.read(member)),
                                      "method": method_info}
            profile_material = json.dumps({method: references[method] for method in
                                           ("tick", "baseTick", "rideTick", "handlePortal", "teleport")}, sort_keys=True)
            rows.append({"entityType": entity_id, "concreteClass": concrete, "hierarchy": hierarchy,
                         "declaringMethods": declaring, "methodReferences": references,
                         "callerProfile": "caller-" + sha(profile_material.encode())[:16],
                         "suffixEquivalenceProof": None,
                         "eligibilityOverrideOwner": declaring["canUsePortal"],
                         "teleportEligibilityOverrideOwner": declaring["canTeleport"]})
        portal_classes = []
        for member in sources.namelist():
            if not member.startswith("net/minecraft/world/level/block/") or not member.endswith(".java"):
                continue
            src = sources.read(member).decode()
            if re.search(r"\bimplements\s+(?:[\w<>?,. ]+,\s*)?Portal\b", src) and "getPortalDestination(" in src:
                class_member = member.removesuffix(".java") + ".class"
                portal_classes.append({"class": member.removesuffix(".java").replace("/", "."),
                                       "sourceFile": member, "sourceSha256": sha(src.encode()),
                                       "classBytecodeSha256": sha(binary.read(class_member)) if class_member in members else None,
                                       "entryMethod": source_method(src, "entityInside"),
                                       "destinationMethod": source_method(src, "getPortalDestination")})
        portal_classes.sort(key=lambda row: row["class"])
        if {row["class"].rsplit(".", 1)[-1] for row in portal_classes} != {
                "NetherPortalBlock", "EndPortalBlock", "EndGatewayBlock"}:
            raise ValueError("portal implementation inventory differs from pinned three implementations")
        materials = falling_materials(sources)
        transfer_factories = {}
        for entity_id in sorted(SOURCE_PROVEN_HURTING_NETHER | SOURCE_PROVEN_THROWABLE_NETHER
                                | set(SOURCE_PROVEN_ARROW_NETHER) | SOURCE_PROVEN_SHULKER_NETHER):
            constant = entity_id.removeprefix("minecraft:").upper()
            concrete_simple = types[entity_id].rsplit(".", 1)[-1]
            declaration = re.search(r"public static final EntityType<" + re.escape(concrete_simple)
                                    + r">\s+" + re.escape(constant) + r"\s*=\s*register\(", entity_source)
            if declaration is None:
                raise ValueError(f"missing native factory registration: {entity_id}")
            following = entity_source[declaration.start():]
            next_declaration = re.search(r"\n\s*public static final EntityType<", following[1:])
            fragment = following[:next_declaration.start() + 1] if next_declaration else following
            if f"{concrete_simple}::new" not in fragment:
                raise ValueError(f"native factory changed: {entity_id}")
            transfer_factories[entity_id] = {
                "file": "net/minecraft/world/entity/EntityTypes.java",
                "line": entity_source.count("\n", 0, declaration.start()) + 1,
                "registeredConstructor": concrete_simple + "::new",
                "declarationSha256": sha(fragment.encode()),
            }
        primitive_specs = (
            ("net.minecraft.world.entity.Entity", "setAsInsidePortal",
             "if (this.isOnPortalCooldown())"),
            ("net.minecraft.world.entity.Entity", "canTeleport",
             "passenger instanceof ServerPlayer player && !player.seenCredits"),
            ("net.minecraft.world.entity.PortalProcessor", "processPortalTeleportation",
             "allowedToTeleport && this.portalTime++"),
            ("net.minecraft.world.entity.LivingEntity", "canUsePortal",
             "!this.isSleeping()"),
            ("net.minecraft.world.entity.monster.creaking.Creaking", "canUsePortal",
             "!this.isHeartBound() && super.canUsePortal(ignorePassenger)"),
        )
        eligibility_primitives = {}
        for owner, method, required_text in primitive_specs:
            src = source_text(owner)
            if required_text not in src:
                raise ValueError(f"native eligibility predicate changed: {owner}.{method}")
            info = source_method(src, method)
            if not info:
                raise ValueError(f"native eligibility method absent: {owner}.{method}")
            eligibility_primitives[owner + "." + method] = {
                "file": source_member(owner), "line": info["line"],
                "sourceMethodSha256": info["sourceSha256"],
                "classBytecodeSha256": sha(binary.read(owner.replace(".", "/") + ".class")),
                "predicateText": required_text,
            }
        handle_portal_calls = []
        for member in sources.namelist():
            if not member.endswith(".java"):
                continue
            text = sources.read(member).decode()
            handle_portal_calls.extend({"file": member, "line": line_number, "source": line.strip()}
                                       for line_number, line in enumerate(text.splitlines(), 1)
                                       if ".handlePortal(" in line)
        if len(handle_portal_calls) != 7:
            raise ValueError(f"pinned direct handlePortal call-site set changed: {len(handle_portal_calls)}")
        return {"schemaVersion": 2, "gameVersion": "Minecraft 26.2", "sourceJarSha256": source_sha,
                "mappedBinarySha256": binary_sha, "registeredTypeCount": len(rows),
                "registeredTypeIds": list(types), "callerProfileCount": len({row["callerProfile"] for row in rows}),
                "entityTypes": rows, "portalImplementations": portal_classes,
                "fallingBlockStates": materials, "handlePortalDirectCallSites": handle_portal_calls,
                "portalEligibilityPrimitives": eligibility_primitives,
                "nativeNetherTransferFactories": transfer_factories,
                "scope": "Pinned vanilla 26.2 mapped source/binary and detectable registry/caller/portal drift; not arbitrary mod code."}


def drift_signature(inventory: dict) -> dict:
    """Fields that change when any pinned native assumption changes."""
    return {key: inventory[key] for key in ("sourceJarSha256", "mappedBinarySha256", "registeredTypeCount",
                                            "registeredTypeIds", "callerProfileCount", "entityTypes",
                                            "portalImplementations", "fallingBlockStates", "handlePortalDirectCallSites",
                                            "portalEligibilityPrimitives", "nativeNetherTransferFactories")}


def build_census(inventory: dict, frozen: dict) -> dict:
    ids = frozen["required_case_ids"]
    ids_sha = sha(("\n".join(ids) + "\n").encode())
    if (len(ids) != 843 or ids_sha != PINNED_IDS_SHA
            or frozen["required_case_ids_sha256"] != ids_sha):
        raise ValueError("frozen 843-case obligation identity changed")
    type_rows = {row["entityType"]: row for row in inventory["entityTypes"]}
    rows = []
    mapping = {}

    def add(legacy: str | None, entity_type: str | None, variant: str | None, route: str,
            graph: str, reason: str) -> None:
        v2_id = "V2-" + legacy if legacy else "V2-ADDED-" + reason.upper().replace(":", "_").replace("/", "_").replace(" ", "_") + "-" + route
        if legacy:
            mapping[legacy] = v2_id
        profile = type_rows.get(entity_type) if entity_type else None
        portal_file, entry_method, destination_method, source_dim, destination_dim = PORTAL_SOURCE[route]
        override = profile["eligibilityOverrideOwner"] if profile else None
        no_entry = entity_type in ALWAYS_NO_ENTRY or entity_type in NO_TICK_ENTRY
        expected = "NO_NATIVE_ENTRY_PATH" if no_entry else None
        source_proof = None
        native_control = None
        if entity_type in ALWAYS_NO_ENTRY:
            method_ref = profile["methodReferences"]["canUsePortal"]
            if not method_ref["method"]["unconditionalReturnFalse"]:
                raise ValueError(f"unconditional no-entry proof invalidated: {entity_type}")
            source_proof = {"kind": "unconditional_canUsePortal_false",
                            "sourceJarSha256": inventory["sourceJarSha256"],
                            "mappedBinarySha256": inventory["mappedBinarySha256"],
                            "nativeOutcome": expected,
                            "reference": {"file": method_ref["file"], "method": "canUsePortal",
                                          "line": method_ref["method"]["line"],
                                          "sourceMethodSha256": method_ref["method"]["sourceSha256"],
                                          "classBytecodeSha256": method_ref["classBytecodeSha256"]},
                            "eligibilityPredicate": "canUsePortal(false) returns false for this concrete type",
                            "applicabilityDemonstrated": True,
                            "artifact": "NATIVE_NO_ENTRY_SOURCE_PROOFS.json"}
        elif entity_type in NO_TICK_ENTRY:
            tick_ref = profile["methodReferences"]["tick"]
            calls = tick_ref["method"]["directCalls"]
            if any("baseTick(" in call or "handlePortal(" in call or "super.tick(" in call for call in calls):
                raise ValueError(f"no-tick-entry proof invalidated: {entity_type}")
            if any(call["file"] == tick_ref["file"] for call in inventory["handlePortalDirectCallSites"]):
                raise ValueError(f"direct handlePortal call found in no-tick-entry class: {entity_type}")
            source_proof = {"kind": "tick_override_skips_portal_processing",
                            "sourceJarSha256": inventory["sourceJarSha256"],
                            "mappedBinarySha256": inventory["mappedBinarySha256"],
                            "nativeOutcome": expected,
                            "reference": {"file": tick_ref["file"], "method": "tick",
                                          "line": tick_ref["method"]["line"],
                                          "sourceMethodSha256": tick_ref["method"]["sourceSha256"],
                                          "classBytecodeSha256": tick_ref["classBytecodeSha256"]},
                            "eligibilityPredicate": "declaring tick does not call super.tick, baseTick, or handlePortal; no direct portal call in class",
                            "applicabilityDemonstrated": True,
                            "artifact": "NATIVE_NO_ENTRY_SOURCE_PROOFS.json"}
            if entity_type == "minecraft:leash_knot":
                # A surviving knot must occupy a fence block. Its entire native
                # hitbox lies inside that block, and no portal block is a fence;
                # the independently pinned geometry/tag proof closes the
                # otherwise unconstructible stable-contact fixture.
                source_proof["kind"] = "stable_fence_occupancy_excludes_portal_contact"
                source_proof["artifact"] = "LEASH_KNOT_STABLE_NO_ENTRY_SOURCE_PROOF.json"
                source_proof["artifactSha256"] = (
                    "1412E119E1479551C76B574E01D6C90ECDCF750D3E5A7312D9BAA9EB1D3FF43C")
                source_proof["eligibilityPredicate"] = (
                    "A stable native leash knot requires a fence at its own block; "
                    "its 0.375 x 0.5 bounding box is contained within that block, "
                    "which cannot simultaneously be a Nether, End, or gateway portal; "
                    "its inherited tick skips portal processing")
        conditional_key = None
        condition = None
        if entity_type == "minecraft:creaking" and variant == "heart_bound":
            expected = "REFUSAL"
            conditional_key = "net.minecraft.world.entity.monster.creaking.Creaking.canUsePortal"
            condition = "isHeartBound() is true; canUsePortal(false) returns false at portal collision"
        elif entity_type == "minecraft:player" and variant == "sleeping":
            expected = "REFUSAL"
            conditional_key = "net.minecraft.world.entity.LivingEntity.canUsePortal"
            condition = "isSleeping() is true; canUsePortal(false) returns false at portal collision"
        elif entity_type == "minecraft:player" and variant == "portal_cooldown":
            expected = "REFUSAL"
            conditional_key = "net.minecraft.world.entity.Entity.setAsInsidePortal"
            condition = "portalCooldown > 0 at collision; setAsInsidePortal declines processor entry"
        elif graph.endswith("credits unseen") and route == "END_EXIT":
            expected = "REFUSAL"
            conditional_key = "net.minecraft.world.entity.Entity.canTeleport"
            condition = "root carries ServerPlayer with seenCredits=false; End exit destination is Overworld"
        if conditional_key:
            primitive = inventory["portalEligibilityPrimitives"][conditional_key]
            source_proof = {"kind": "conditional_native_refusal",
                            "sourceJarSha256": inventory["sourceJarSha256"],
                            "mappedBinarySha256": inventory["mappedBinarySha256"],
                            "nativeOutcome": expected,
                            "reference": {"file": primitive["file"], "method": conditional_key.rsplit(".", 1)[1],
                                          "line": primitive["line"],
                                          "sourceMethodSha256": primitive["sourceMethodSha256"],
                                          "classBytecodeSha256": primitive["classBytecodeSha256"]},
                            "eligibilityPredicate": condition,
                            "applicabilityDemonstrated": False,
                            "artifact": "NATIVE_CONDITIONAL_REFUSAL_SOURCE_PROOFS.json"}
        if graph == "mob riding mob" and route == "NETHER_ENTRY":
            # This frozen shape is exercised by the exact installed pig + chicken fixture.
            # Passenger canUsePortal is intentionally irrelevant: native recursion invokes
            # chicken.teleport(transitionAsPassenger) directly after root ejection.
            pig = type_rows["minecraft:pig"]
            chicken = type_rows["minecraft:chicken"]
            if (pig["concreteClass"] != "net.minecraft.world.entity.animal.pig.Pig"
                    or chicken["concreteClass"] != "net.minecraft.world.entity.animal.chicken.Chicken"
                    or pig["declaringMethods"]["tick"] != "net.minecraft.world.entity.Mob"
                    or pig["declaringMethods"]["canUsePortal"] != "net.minecraft.world.entity.LivingEntity"
                    or pig["declaringMethods"]["canTeleport"] != "net.minecraft.world.entity.Entity"
                    or pig["declaringMethods"]["teleport"] != "net.minecraft.world.entity.Entity"
                    or chicken["declaringMethods"]["teleport"] != "net.minecraft.world.entity.Entity"):
                raise ValueError("pinned pig/chicken graph caller or eligibility changed")
            profile = pig
            override = pig["eligibilityOverrideOwner"]
            expected = "TRANSFER"
            handle = pig["methodReferences"]["handlePortal"]
            eligibility = pig["methodReferences"]["canUsePortal"]
            source_proof = {"kind": "conditional_pig_chicken_graph_nether_transfer",
                            "sourceJarSha256": inventory["sourceJarSha256"],
                            "mappedBinarySha256": inventory["mappedBinarySha256"],
                            "nativeOutcome": expected,
                            "reference": {"file": handle["file"], "method": "handlePortal",
                                          "line": handle["method"]["line"],
                                          "sourceMethodSha256": handle["method"]["sourceSha256"],
                                          "classBytecodeSha256": handle["classBytecodeSha256"]},
                            "rootEligibilityReference": {"file": eligibility["file"],
                                                         "method": "canUsePortal",
                                                         "line": eligibility["method"]["line"],
                                                         "sourceMethodSha256": eligibility["method"]["sourceSha256"],
                                                         "classBytecodeSha256": eligibility["classBytecodeSha256"]},
                            "rootCallerProfile": pig["callerProfile"],
                            "passengerTeleportReference": chicken["methodReferences"]["teleport"],
                            "registrationReference": "EntityTypes.java:271-278 Chicken::new; 718-720 Pig::new",
                            "portalReference": "NetherPortalBlock.java:132-143,225-228 supplies absolute Nether XYZ",
                            "graphReference": "Entity.java:3069-3102 copies/ejects, teleports chicken, creates pig, reattaches child",
                            "eligibilityPredicate": "alive awake pig root, chicken passenger, cooldown elapsed, "
                                                    "valid Overworld Nether portal and enabled Nether destination; "
                                                    "pig canUsePortal(false) and canTeleport inherited; both registered "
                                                    "factories create in destination; child teleport is native recursion",
                            "applicabilityDemonstrated": False,
                            "artifact": "NATIVE_CONDITIONAL_TRANSFER_SOURCE_PROOFS.json"}
        if entity_type in SOURCE_PROVEN_HURTING_NETHER and route in ("NETHER_ENTRY", "NETHER_RETURN") and variant is None:
            assert profile is not None
            methods = profile["declaringMethods"]
            if (methods["canUsePortal"] != "net.minecraft.world.entity.Entity"
                    or methods["canTeleport"] != "net.minecraft.world.entity.Entity"
                    or methods["teleport"] != "net.minecraft.world.entity.Entity"
                    or "net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile"
                    not in profile["hierarchy"]):
                raise ValueError(f"native transfer caller/eligibility changed: {entity_type}")
            expected = "TRANSFER"
            factory = inventory["nativeNetherTransferFactories"][entity_type]
            handle = profile["methodReferences"]["handlePortal"]
            source_proof = {"kind": "conditional_hurting_projectile_nether_transfer",
                            "sourceJarSha256": inventory["sourceJarSha256"],
                            "mappedBinarySha256": inventory["mappedBinarySha256"],
                            "nativeOutcome": expected,
                            "reference": {"file": handle["file"], "method": "handlePortal",
                                          "line": handle["method"]["line"],
                                          "sourceMethodSha256": handle["method"]["sourceSha256"],
                                          "classBytecodeSha256": handle["classBytecodeSha256"]},
                            "registrationReference": factory,
                            "portalReference": "NetherPortalBlock.entityInside/getPortalDestination, pinned source lines 112-143",
                            "creationReference": "Entity.teleportCrossDimension/EntityType.create, pinned source lines 3069-3093 and 296-305",
                            "eligibilityPredicate": "alive standalone projectile, no cooldown, valid Nether portal and destination; "
                                                    "Entity.canUsePortal(false), Entity.canTeleport, and Entity.teleport inherited; "
                                                    "registered exact class constructor available in enabled destination",
                            "applicabilityDemonstrated": False,
                            "artifact": "NATIVE_CONDITIONAL_TRANSFER_SOURCE_PROOFS.json"}
        if entity_type in SOURCE_PROVEN_THROWABLE_NETHER and route in ("NETHER_ENTRY", "NETHER_RETURN") and variant is None:
            assert profile is not None
            methods = profile["declaringMethods"]
            if (methods["tick"] != "net.minecraft.world.entity.projectile.ThrowableProjectile"
                    or methods["canUsePortal"] != "net.minecraft.world.entity.projectile.ThrowableProjectile"
                    or not profile["methodReferences"]["canUsePortal"]["method"]["unconditionalReturnTrue"]
                    or methods["canTeleport"] != "net.minecraft.world.entity.Entity"
                    or methods["teleport"] != "net.minecraft.world.entity.Entity"
                    or "net.minecraft.world.entity.projectile.ThrowableProjectile" not in profile["hierarchy"]):
                raise ValueError(f"native throwable transfer caller/eligibility changed: {entity_type}")
            expected = "TRANSFER"
            handle = profile["methodReferences"]["handlePortal"]
            eligibility = profile["methodReferences"]["canUsePortal"]
            tick = profile["methodReferences"]["tick"]
            source_proof = {"kind": "conditional_throwable_projectile_nether_transfer",
                            "sourceJarSha256": inventory["sourceJarSha256"],
                            "mappedBinarySha256": inventory["mappedBinarySha256"],
                            "nativeOutcome": expected,
                            "reference": {"file": handle["file"], "method": "handlePortal",
                                          "line": handle["method"]["line"],
                                          "sourceMethodSha256": handle["method"]["sourceSha256"],
                                          "classBytecodeSha256": handle["classBytecodeSha256"]},
                            "eligibilityReference": {"file": eligibility["file"], "method": "canUsePortal",
                                                     "line": eligibility["method"]["line"],
                                                     "sourceMethodSha256": eligibility["method"]["sourceSha256"],
                                                     "classBytecodeSha256": eligibility["classBytecodeSha256"]},
                            "tickReference": {"file": tick["file"], "method": "tick",
                                              "line": tick["method"]["line"],
                                              "sourceMethodSha256": tick["method"]["sourceSha256"],
                                              "classBytecodeSha256": tick["classBytecodeSha256"]},
                            "registrationReference": inventory["nativeNetherTransferFactories"][entity_type],
                            "portalReference": "NetherPortalBlock.entityInside/getPortalDestination, pinned source lines 112-143",
                            "creationReference": "Entity.teleportCrossDimension/EntityType.create, pinned source lines 3069-3093 and 296-305",
                            "eligibilityPredicate": "alive standalone exact throwable, cooldown elapsed, valid Nether portal and "
                                                    "destination; ThrowableProjectile.canUsePortal(false) returns true, "
                                                    "Entity.canTeleport and Entity.teleport inherited, registered exact "
                                                    "constructor available in enabled destination; native impact suffix "
                                                    "remains independently observable",
                            "applicabilityDemonstrated": False,
                            "artifact": "NATIVE_CONDITIONAL_TRANSFER_SOURCE_PROOFS.json"}
        if entity_type in SOURCE_PROVEN_ARROW_NETHER and route in ("NETHER_ENTRY", "NETHER_RETURN") and variant is None:
            assert profile is not None
            methods = profile["declaringMethods"]
            direct_step = [site for site in inventory["handlePortalDirectCallSites"]
                           if site["file"] == "net/minecraft/world/entity/projectile/arrow/AbstractArrow.java"
                           and "handlePortal(" in site["source"]]
            if (methods["tick"] != SOURCE_PROVEN_ARROW_NETHER[entity_type]
                    or methods["canUsePortal"] != "net.minecraft.world.entity.Entity"
                    or methods["canTeleport"] != "net.minecraft.world.entity.Entity"
                    or methods["teleport"] != "net.minecraft.world.entity.Entity"
                    or "net.minecraft.world.entity.projectile.arrow.AbstractArrow" not in profile["hierarchy"]
                    or len(direct_step) != 1):
                raise ValueError(f"native arrow transfer caller/eligibility changed: {entity_type}")
            expected = "TRANSFER"
            handle = profile["methodReferences"]["handlePortal"]
            tick = profile["methodReferences"]["tick"]
            source_proof = {"kind": "conditional_arrow_projectile_nether_transfer",
                            "sourceJarSha256": inventory["sourceJarSha256"],
                            "mappedBinarySha256": inventory["mappedBinarySha256"],
                            "nativeOutcome": expected,
                            "reference": {"file": handle["file"], "method": "handlePortal",
                                          "line": handle["method"]["line"],
                                          "sourceMethodSha256": handle["method"]["sourceSha256"],
                                          "classBytecodeSha256": handle["classBytecodeSha256"]},
                            "directEntryReference": direct_step[0],
                            "tickReference": {"file": tick["file"], "method": "tick",
                                              "line": tick["method"]["line"],
                                              "sourceMethodSha256": tick["method"]["sourceSha256"],
                                              "classBytecodeSha256": tick["classBytecodeSha256"]},
                            "registrationReference": inventory["nativeNetherTransferFactories"][entity_type],
                            "portalReference": "NetherPortalBlock.entityInside/getPortalDestination, pinned source lines 112-143",
                            "creationReference": "Entity.teleportCrossDimension/EntityType.create, pinned source lines 3069-3093 and 296-305",
                            "eligibilityPredicate": "alive standalone exact arrow projectile in flight with no cooldown; "
                                                    "AbstractArrow.stepMoveAndHit calls handlePortal after contact; "
                                                    "Entity.canUsePortal(false), canTeleport, and teleport inherited; "
                                                    "registered constructor available in enabled Nether destination",
                            "applicabilityDemonstrated": False,
                            "artifact": "NATIVE_CONDITIONAL_TRANSFER_SOURCE_PROOFS.json"}
        if entity_type in SOURCE_PROVEN_SHULKER_NETHER and route in ("NETHER_ENTRY", "NETHER_RETURN") and variant is None:
            assert profile is not None
            methods = profile["declaringMethods"]
            tick = profile["methodReferences"]["tick"]
            direct = [call for call in inventory["handlePortalDirectCallSites"]
                      if call["file"] == tick["file"] and "this.handlePortal(" in call["source"]]
            if (profile["concreteClass"] != "net.minecraft.world.entity.projectile.ShulkerBullet"
                    or methods["tick"] != profile["concreteClass"]
                    or methods["baseTick"] != "net.minecraft.world.entity.Entity"
                    or methods["handlePortal"] != "net.minecraft.world.entity.Entity"
                    or methods["canUsePortal"] != "net.minecraft.world.entity.Entity"
                    or methods["canTeleport"] != "net.minecraft.world.entity.Entity"
                    or methods["teleport"] != "net.minecraft.world.entity.Entity"
                    or len(direct) != 1 or "super.tick(" not in tick["method"]["directCalls"]):
                raise ValueError("native Shulker Bullet caller or eligibility changed")
            expected = "TRANSFER"
            handle = profile["methodReferences"]["handlePortal"]
            source_proof = {"kind": "conditional_shulker_bullet_nether_transfer",
                            "sourceJarSha256": inventory["sourceJarSha256"],
                            "mappedBinarySha256": inventory["mappedBinarySha256"],
                            "nativeOutcome": expected,
                            "reference": {"file": handle["file"], "method": "handlePortal",
                                          "line": handle["method"]["line"],
                                          "sourceMethodSha256": handle["method"]["sourceSha256"],
                                          "classBytecodeSha256": handle["classBytecodeSha256"]},
                            "directEntryReference": direct[0],
                            "tickReference": {"file": tick["file"], "method": "tick",
                                              "line": tick["method"]["line"],
                                              "sourceMethodSha256": tick["method"]["sourceSha256"],
                                              "classBytecodeSha256": tick["classBytecodeSha256"],
                                              "lexicalSuffixSha256": tick["method"]["lexicalSuffixSha256"]},
                            "registrationReference": inventory["nativeNetherTransferFactories"][entity_type],
                            "portalReference": "NetherPortalBlock.entityInside/getPortalDestination, pinned source lines 112-143",
                            "creationReference": "Entity.teleportCrossDimension/EntityType.create, pinned source lines 3069-3093 and 296-305",
                            "eligibilityPredicate": "alive standalone Shulker Bullet with no cooldown, clear portal contact "
                                                    "and enabled Nether destination; ShulkerBullet.tick calls "
                                                    "Entity.baseTick and then handlePortal directly; Entity.canUsePortal(false), "
                                                    "canTeleport and teleport inherited; registered constructor available",
                            "applicabilityDemonstrated": False,
                            "artifact": "NATIVE_CONDITIONAL_TRANSFER_SOURCE_PROOFS.json"}
        if (profile is not None and entity_type not in ALWAYS_NO_ENTRY and variant is None
                and route in ("NETHER_ENTRY", "NETHER_RETURN") and expected is None):
            # This is deliberately narrower than the whole Mob hierarchy. A subtype
            # that overrides tick/baseTick or portal eligibility needs its own
            # source proof and continuation audit, even if it happens to transfer.
            methods = profile["declaringMethods"]
            mob = "net.minecraft.world.entity.Mob"
            living = "net.minecraft.world.entity.LivingEntity"
            entity = "net.minecraft.world.entity.Entity"
            if (methods["tick"] == mob and methods["baseTick"] == mob
                    and methods["handlePortal"] == entity
                    and methods["canUsePortal"] == living
                    and methods["canTeleport"] == entity
                    and methods["teleport"] == entity
                    and mob in profile["hierarchy"]):
                tick = profile["methodReferences"]["tick"]
                base_tick = profile["methodReferences"]["baseTick"]
                eligibility = profile["methodReferences"]["canUsePortal"]
                handle = profile["methodReferences"]["handlePortal"]
                if ("super.tick(" not in tick["method"]["directCalls"]
                        or "super.baseTick(" not in base_tick["method"]["directCalls"]
                        or entity_type not in inventory["registeredTypeIds"]):
                    raise ValueError(f"inherited mob Nether caller chain changed: {entity_type}")
                expected = "TRANSFER"
                source_proof = {
                    "kind": "conditional_inherited_mob_nether_transfer",
                    "sourceJarSha256": inventory["sourceJarSha256"],
                    "mappedBinarySha256": inventory["mappedBinarySha256"],
                    "nativeOutcome": expected,
                    "registeredTypeId": entity_type,
                    "concreteClass": profile["concreteClass"],
                    "reference": {"file": handle["file"], "method": "handlePortal",
                                  "line": handle["method"]["line"],
                                  "sourceMethodSha256": handle["method"]["sourceSha256"],
                                  "classBytecodeSha256": handle["classBytecodeSha256"]},
                    "tickReference": tick,
                    "baseTickReference": base_tick,
                    "eligibilityReference": eligibility,
                    "inheritedChainReference": "Mob.java:341-346 -> LivingEntity.java:2739-2741 -> "
                                               "Entity.java:515-532; Mob.java:281-283 -> "
                                               "LivingEntity.java:412-422 -> Entity.java:519-532",
                    "portalReference": "NetherPortalBlock.java:112-143,225-228; nonplayer transition time is zero",
                    "creationReference": "Entity.java:3069-3093 and EntityType.java:296-305",
                    "eligibilityPredicate": "alive awake standalone exact registered mob, cooldown elapsed, "
                                            "valid Nether portal and enabled destination; non-Peaceful world "
                                            "if the type requires it; Mob.tick calls LivingEntity.tick, "
                                            "Mob.baseTick calls Entity.baseTick and handlePortal; "
                                            "LivingEntity.canUsePortal and Entity.canTeleport permit this state; "
                                            "destination factory creates the concrete type",
                    "applicabilityDemonstrated": False,
                    "artifact": "NATIVE_CONDITIONAL_TRANSFER_SOURCE_PROOFS.json",
                }
        if (entity_type in SOURCE_PROVEN_MINECART_NETHER and variant is None
                and graph == "standalone" and route in ("NETHER_ENTRY", "NETHER_RETURN")):
            assert profile is not None
            methods = profile["declaringMethods"]
            entity = "net.minecraft.world.entity.Entity"
            minecart = "net.minecraft.world.entity.vehicle.minecart.Minecart"
            minecart_chest = "net.minecraft.world.entity.vehicle.minecart.MinecartChest"
            abstract = "net.minecraft.world.entity.vehicle.minecart.AbstractMinecart"
            container = "net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer"
            plain = entity_type == "minecraft:minecart"
            inherited = type_rows["minecraft:chest_minecart"]
            tick = profile["methodReferences"]["tick"]
            abstract_tick = inherited["methodReferences"]["tick"]
            direct = [call for call in inventory["handlePortalDirectCallSites"]
                      if call["file"] == abstract_tick["file"] and "this.handlePortal(" in call["source"]]
            if (methods["tick"] != (minecart if plain else abstract)
                    or methods["baseTick"] != entity
                    or not all(methods[name] == entity for name in
                               ("handlePortal", "canUsePortal", "canTeleport", "teleport"))
                    or abstract not in profile["hierarchy"]
                    or profile["concreteClass"] != (minecart if plain else minecart_chest)
                    or not plain and container not in profile["hierarchy"]
                    or inherited["declaringMethods"]["tick"] != abstract
                    or plain and "super.tick(" not in tick["method"]["directCalls"]
                    or "this.handlePortal(" not in abstract_tick["method"]["directCalls"]
                    or len(direct) != 1):
                raise ValueError(f"pinned exact minecart portal caller changed: {entity_type}")
            handle = profile["methodReferences"]["handlePortal"]
            expected = "TRANSFER"
            source_proof = {
                "kind": ("conditional_empty_plain_minecart_nether_transfer" if plain
                         else "conditional_chest_minecart_nether_transfer"),
                "sourceJarSha256": inventory["sourceJarSha256"],
                "mappedBinarySha256": inventory["mappedBinarySha256"],
                "nativeOutcome": expected,
                "registeredTypeId": entity_type,
                "concreteClass": profile["concreteClass"],
                "reference": {"file": handle["file"], "method": "handlePortal",
                              "line": handle["method"]["line"],
                              "sourceMethodSha256": handle["method"]["sourceSha256"],
                              "classBytecodeSha256": handle["classBytecodeSha256"]},
                "directEntryReference": direct[0],
                "outerTickReference": tick,
                "abstractTickReference": abstract_tick,
                "registrationReference": (
                    "EntityTypes.java:639-642 MINECART Minecart::new" if plain else
                    "EntityTypes.java:267-270 CHEST_MINECART MinecartChest::new")
                    + "; pinned source JAR SHA binds this constructor",
                "portalReference": "NetherPortalBlock.java:112-143,225-228; nonplayer transition time is zero",
                "eligibilityPredicate": (
                    "alive empty standalone exact plain minecart" if plain else
                    "alive standalone exact chest minecart with stable inventory or latent loot table")
                    + ", no cooldown, valid Nether portal and enabled destination; "
                    + ("Minecart.tick invokes " if plain else "MinecartChest inherits ")
                    + "AbstractMinecart.tick, whose direct handlePortal call enters "
                    "Entity.handlePortal; inherited Entity.canUsePortal and canTeleport "
                    "permit this state; registered factory creates the concrete destination type",
                "applicabilityDemonstrated": False,
                "artifact": "NATIVE_CONDITIONAL_TRANSFER_SOURCE_PROOFS.json",
            }
        if (profile is not None and variant is None and graph == "standalone"
                and route in ("NETHER_ENTRY", "NETHER_RETURN") and expected is None):
            methods = profile["declaringMethods"]
            boat = "net.minecraft.world.entity.vehicle.boat.AbstractBoat"
            entity = "net.minecraft.world.entity.Entity"
            if (methods["tick"] == boat and methods["baseTick"] == entity
                    and all(methods[name] == entity for name in
                            ("handlePortal", "canUsePortal", "canTeleport", "teleport"))
                    and boat in profile["hierarchy"]):
                tick = profile["methodReferences"]["tick"]
                handle = profile["methodReferences"]["handlePortal"]
                if (entity_type not in inventory["registeredTypeIds"]
                        or "super.tick(" not in tick["method"]["directCalls"]
                        or tick["method"]["lexicalSuffixBytes"] == 0):
                    raise ValueError(f"pinned empty boat portal caller changed: {entity_type}")
                expected = "TRANSFER"
                source_proof = {
                    "kind": "conditional_empty_boat_nether_transfer",
                    "sourceJarSha256": inventory["sourceJarSha256"],
                    "mappedBinarySha256": inventory["mappedBinarySha256"],
                    "nativeOutcome": expected,
                    "registeredTypeId": entity_type,
                    "concreteClass": profile["concreteClass"],
                    "reference": {"file": handle["file"], "method": "handlePortal",
                                  "line": handle["method"]["line"],
                                  "sourceMethodSha256": handle["method"]["sourceSha256"],
                                  "classBytecodeSha256": handle["classBytecodeSha256"]},
                    "tickReference": tick,
                    "nativeContinuation": "AbstractBoat.java:206-288; suffix after super.tick "
                                          "has lexical SHA " + tick["method"]["lexicalSuffixSha256"],
                    "portalReference": "NetherPortalBlock.java:112-143,225-228; nonplayer transition time is zero",
                    "creationReference": "Entity.java:3069-3093 and EntityType.java:296-305; "
                                         "EntityTypes.java boat/raft registered factory",
                    "eligibilityPredicate": "alive empty standalone registered boat/raft, no cooldown, "
                                            "valid at least three-wide Nether portal and enabled destination; "
                                            "AbstractBoat.tick invokes Entity.tick/baseTick/handlePortal; "
                                            "Entity.canUsePortal and canTeleport permit this state; "
                                            "destination factory creates the concrete type",
                    "applicabilityDemonstrated": False,
                    "artifact": "NATIVE_CONDITIONAL_TRANSFER_SOURCE_PROOFS.json",
                }
        if entity_type == "minecraft:pig" and variant is None and route == "END_GATEWAY":
            gateway = next(portal for portal in inventory["portalImplementations"]
                           if portal["class"] == "net.minecraft.world.level.block.EndGatewayBlock")
            if (profile["declaringMethods"]["canUsePortal"] != "net.minecraft.world.entity.LivingEntity"
                    or profile["declaringMethods"]["teleport"] != "net.minecraft.world.entity.Entity"
                    or gateway["entryMethod"]["line"] != 95
                    or gateway["destinationMethod"]["line"] != 108):
                raise ValueError("pinned pig gateway eligibility or portal implementation changed")
            expected = "SAME_DIMENSION_TRANSFER"
            source_proof = {
                "kind": "native_control_pig_end_gateway_same_dimension",
                "sourceJarSha256": inventory["sourceJarSha256"],
                "mappedBinarySha256": inventory["mappedBinarySha256"],
                "nativeOutcome": expected,
                "reference": {"file": gateway["sourceFile"], "method": "entityInside",
                              "line": gateway["entryMethod"]["line"],
                              "sourceMethodSha256": gateway["entryMethod"]["sourceSha256"],
                              "classBytecodeSha256": gateway["classBytecodeSha256"]},
                "destinationReference": gateway["destinationMethod"],
                "eligibilityPredicate": "alive standalone pig contacts an uncooling End gateway "
                                        "with configured far exit; native same-dimension teleport "
                                        "keeps UUID and sets cooldown",
                "applicabilityDemonstrated": True,
                "artifact": "native-gateway-control-001/result.json",
            }
            native_control = {
                "sourceJarSha256": inventory["sourceJarSha256"],
                "mappedBinarySha256": inventory["mappedBinarySha256"],
                "observedOutcome": expected,
                "path": "native-gateway-control-001/result.json",
                "sha256": "F1F7335B599CDCA7E9AC6DE36B93D744BAE4B2A6F14922994E47ED893095865F",
            }
        predicates = ["alive at contact", "not a passenger unless type override permits",
                      "portal processor ready; cooldown elapsed", "valid portal geometry and destination world",
                      "source and destination owner context valid"]
        if override == "net.minecraft.world.entity.LivingEntity":
            predicates.append("not sleeping")
        elif override == "net.minecraft.world.entity.monster.creaking.Creaking":
            predicates.extend(["not sleeping", "not heart-bound"])
        elif override == "net.minecraft.world.entity.projectile.ThrowableProjectile":
            predicates.append("throwable override permits portal use despite passenger state")
        if route == "END_EXIT":
            predicates.append("End respawnData destination exists; player credits and vehicle passenger rule checked")
        if route == "END_GATEWAY":
            predicates.append("gateway block entity exists, is not cooling down, and supplies an exit position")
        semantic_assertions = (["No destination identity or leaked portal session; preserve the native source and passenger graph state."]
                               if expected in ("REFUSAL", "NO_NATIVE_ENTRY_PATH") else
                               ["Move the same native entity to the gateway exit under its destination owner; "
                                "vacate its old location without duplicating its UUID or tracker."]
                               if expected == "SAME_DIMENSION_TRANSFER" else
                               ["After eligible transfer, retire source and publish one destination incarnation under destination owner."])
        if entity_type == "minecraft:falling_block":
            semantic_assertions += ["Preserve carried block state and native landing, drop, and damage behavior."]
        if entity_type == "minecraft:item":
            semantic_assertions += ["Preserve stack and pickup behavior after arrival."]
        if entity_type == "minecraft:tnt":
            semantic_assertions += ["Preserve fuse, explosion, and native portal destruction behavior."]
        if profile and any(".projectile." in class_name for class_name in profile["hierarchy"]):
            semantic_assertions += ["Preserve owner/target links and native impact or explosion effects after arrival."]
        if entity_type == "minecraft:player" or "player" in graph:
            semantic_assertions += ["Settle player session, packets, reconnect, and post-transfer playability."]
        if graph != "standalone":
            semantic_assertions += ["Account for every passenger node and reattach native graph edges exactly once."]
        if route == "END_GATEWAY":
            semantic_assertions += ["Prove same-dimension gateway coordinates, portal ticket, and cooldown behavior."]
        required_assertions = (["source_unchanged", "destination_absent", "portal_session_absent", "graph_unchanged"]
                               if expected in ("REFUSAL", "NO_NATIVE_ENTRY_PATH")
                               and entity_type != "minecraft:leash_knot" else [])
        rows.append({"id": v2_id, "legacyCaseId": legacy, "entityType": entity_type,
                     "variant": variant, "route": route, "graph": graph,
                     "sourceDimension": source_dim, "destinationDimension": destination_dim,
                     "callerProfile": profile["callerProfile"] if profile else None,
                     "eligibility": {"predicates": predicates, "overrideOwner": override,
                                     "resolvedForFixture": False},
                     "sourceReference": {"portalFile": portal_file, "entryMethod": entry_method,
                                         "destinationMethod": destination_method,
                                         "callerMethods": profile["methodReferences"] if profile else None},
                     "sourceProof": source_proof, "nativeControlReceipt": native_control,
                     "expectedNativeOutcome": expected, "observedPhoeniumResult": None,
                     "fixture": ({"contactImpossible": True, "reason": "No native portal entry path"} if no_entry else
                                 {"contactImpossible": False, "statePredicate": condition, "constructed": False}
                                 if conditional_key else
                                 {"contactImpossible": False, "requiredPredicates": source_proof["eligibilityPredicate"],
                                  "constructed": False}
                                 if expected == "TRANSFER" and source_proof else None),
                     "receipts": {"W1": [], "W4": []},
                     "finalCandidateSha256": None, "semanticAssertions": semantic_assertions,
                     "requiredAssertions": required_assertions,
                     "disposition": None,
                     "unresolvedReasons": (["Native-required transfer awaits exact final-byte W1/W4 semantic observation"]
                                           if expected == "TRANSFER" else
                                           ["Conditional state not yet demonstrated in a fixture", "No final-byte W1/W4 negative observation"]
                                           if conditional_key else
                                           ["No final-byte row observation or qualifying no-entry proof receipt"] if no_entry else
                                           ["Native outcome and fixture applicability require proof", "No final-byte row observation"]),
                     "generationReason": reason if not legacy else None})

    for old_id in ids:
        prefix, route = old_id.rsplit("-", 1)
        if route not in ROUTES:
            raise ValueError(f"unknown frozen route: {old_id}")
        if prefix.startswith("FALLING_"):
            material = prefix.removeprefix("FALLING_").lower()
            add(old_id, "minecraft:falling_block", "minecraft:" + material, route, "standalone", "frozen falling state")
        elif prefix.startswith("GRAPH_"):
            shape = prefix.removeprefix("GRAPH_")
            if shape not in GRAPH_SHAPES:
                raise ValueError(f"unknown frozen graph: {shape}")
            add(old_id, None, None, route, GRAPH_SHAPES[shape], "frozen graph")
        else:
            entity = "minecraft:" + prefix.lower()
            if entity not in type_rows:
                raise ValueError(f"frozen entity absent from pinned registry: {entity}")
            add(old_id, entity, None, route, "standalone", "frozen registered type")
    if list(mapping) != ids:
        raise AssertionError("old ID to v2 mapping is incomplete or out of order")
    existing = {(row["variant"], row["route"]) for row in rows if row["entityType"] == "minecraft:falling_block" and row["variant"]}
    for material in inventory["fallingBlockStates"]:
        for route in ROUTES:
            pair = (material["blockState"], route)
            if pair not in existing:
                add(None, "minecraft:falling_block", material["blockState"], route, "standalone",
                    "falling state " + material["blockState"])
    for entity, variant, routes in (
        ("minecraft:creaking", "heart_bound", ROUTES),
        ("minecraft:creaking", "heart_unbound", ROUTES),
        ("minecraft:player", "sleeping", ROUTES),
        ("minecraft:player", "portal_cooldown", ROUTES),
        ("minecraft:player", "credits_unseen", ("END_EXIT",)),
        ("minecraft:ender_pearl", "passenger_override", ROUTES),
        ("minecraft:tnt", "fuse_near_expiry", ROUTES),
        ("minecraft:falling_block", "landing_damage", ROUTES),
    ):
        if entity in type_rows:
            for route in routes:
                add(None, entity, variant, route, "standalone", f"state {entity} {variant}")
    for shape in ("chest boat with inventory and mob", "chest boat with player and second passenger",
                  "bamboo raft with player", "chest raft with inventory and two riders",
                  "furnace minecart with rider", "hopper minecart with inventory and rider",
                  "command block minecart with rider", "player riding camel with second passenger",
                  "nested vehicle and mob graph"):
        for route in ROUTES:
            add(None, None, None, route, shape, "graph " + shape)
    for entity, shape in (("minecraft:oak_boat", "boat with player rider credits unseen"),
                          ("minecraft:minecart", "minecart with player rider credits unseen"),
                          ("minecraft:camel", "mountable mob with player rider credits unseen")):
        add(None, entity, "credits_unseen_passenger", "END_EXIT", shape, "graph " + shape)
    row_ids = [row["id"] for row in rows]
    if len(set(row_ids)) != len(row_ids):
        raise ValueError("duplicate generated v2 obligation ID")
    source_proofs = {row["entityType"]: row["sourceProof"] for row in rows
                     if row["sourceProof"] and row["sourceProof"]["nativeOutcome"] == "NO_NATIVE_ENTRY_PATH"}
    conditional_proofs = {row["id"]: row["sourceProof"] for row in rows
                          if row["sourceProof"] and row["sourceProof"]["nativeOutcome"] == "REFUSAL"}
    transfer_proofs = {row["id"]: row["sourceProof"] for row in rows
                       if row["sourceProof"] and row["sourceProof"]["nativeOutcome"] == "TRANSFER"}
    vocabulary = ["TRANSFER_VERIFIED", "NATIVE_REFUSAL_VERIFIED",
                  "NO_NATIVE_ENTRY_PATH_VERIFIED", "ROUTE_NOT_APPLICABLE_VERIFIED",
                  "SAME_DIMENSION_TRANSFER_VERIFIED"]
    counts = {name: 0 for name in vocabulary}
    counts["UNRESOLVED"] = len(rows)
    return {"schemaVersion": 2, "gameVersion": "Minecraft 26.2",
            "sourceJarSha256": inventory["sourceJarSha256"], "mappedBinarySha256": inventory["mappedBinarySha256"],
            "registeredTypeCount": inventory["registeredTypeCount"], "callerProfileCount": inventory["callerProfileCount"],
            "frozenCaseCount": len(ids), "frozenIdsSha256": ids_sha,
            "frozenIdMapping": mapping, "addedCaseCount": len(rows) - len(ids),
            "dispositionVocabulary": vocabulary, "dispositionCounts": counts,
            "nativeNoEntrySourceProofs": source_proofs,
            "nativeConditionalRefusalSourceProofs": conditional_proofs,
            "nativeConditionalTransferSourceProofs": transfer_proofs, "rows": rows}


def write_report(path: pathlib.Path, census: dict, drift: dict) -> None:
    categories = collections.Counter(row["disposition"] or "UNRESOLVED" for row in census["rows"])
    explicit_counts = {name: categories[name] for name in census["dispositionVocabulary"]}
    explicit_counts["UNRESOLVED"] = categories["UNRESOLVED"]
    lines = ["# Pinned 26.2 portal behavior census v2", "",
             f"Registry: {census['registeredTypeCount']} types; caller fingerprints: {census['callerProfileCount']}.",
             f"Frozen IDs: {census['frozenCaseCount']} mapped exactly; added obligations: {census['addedCaseCount']}.",
             f"Drift gate: {'PASS' if drift['pass'] else 'FAIL'}. Dispositions: {explicit_counts}.", "",
             "The lexical suffix hashes detect changed source tails; they do not establish semantic equivalence.",
             "A null disposition is an open obligation. The source-derived no-entry expectation alone does not earn one.", "",
             "| Old ID | V2 ID | Native expectation | PHOENIUM observation | Disposition |",
             "|---|---|---|---|---|"]
    for row in census["rows"]:
        if row["legacyCaseId"]:
            lines.append(f"| {row['legacyCaseId']} | {row['id']} | {row['expectedNativeOutcome'] or 'UNRESOLVED'} | "
                         f"{row['observedPhoeniumResult'] or 'UNRESOLVED'} | {row['disposition'] or 'UNRESOLVED'} |")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    here = pathlib.Path(__file__).resolve()
    root = here.parents[3]
    vanilla = root / ".candidate-worktrees/respawn-lifecycle-repair/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2"
    parser.add_argument("--source-jar", type=pathlib.Path, default=vanilla / "minecraft-merged-043a8b3edf-26.2-sources.jar")
    parser.add_argument("--mapped-jar", type=pathlib.Path, default=vanilla / "minecraft-merged-043a8b3edf-26.2.jar")
    parser.add_argument("--frozen", type=pathlib.Path, default=root / ".audit/sol-ultra-portal-respawn-20260922-192748/PORTAL_REQUIRED_CASES.json")
    parser.add_argument("--output-dir", type=pathlib.Path, default=root / ".audit/sol-ultra-final-campaign-20260923")
    parser.add_argument("--baseline", type=pathlib.Path, default=here.with_name("pinned-native-26.2-baseline.json"))
    parser.add_argument("--javap", type=pathlib.Path, default=pathlib.Path(r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\javap.exe"))
    parser.add_argument("--write-baseline", action="store_true", help="Explicit initial baseline creation only")
    parser.add_argument("--drift-gate", action="store_true", help="Fail on native registry/caller/portal drift only")
    parser.add_argument("--gate", action="store_true", help="Fail on any drift or unclassified row")
    args = parser.parse_args()
    inventory = build_inventory(args.source_jar, args.mapped_jar, args.javap)
    signature = drift_signature(inventory)
    if args.write_baseline:
        if args.baseline.exists():
            raise ValueError("baseline already exists; do not silently replace it")
        dump(args.baseline, signature)
    baseline = json.loads(args.baseline.read_text(encoding="utf-8")) if args.baseline.exists() else None
    changed = [] if signature == baseline else [key for key in signature if baseline is None or signature[key] != baseline.get(key)]
    drift = {"schemaVersion": 1, "pass": not changed, "changedFields": changed,
             "sourceJarSha256": inventory["sourceJarSha256"], "mappedBinarySha256": inventory["mappedBinarySha256"],
             "baseline": str(args.baseline), "scope": inventory["scope"]}
    frozen = json.loads(args.frozen.read_text(encoding="utf-8"))
    census = build_census(inventory, frozen)
    output = args.output_dir
    dump(output / "PINNED_NATIVE_INVENTORY.json", inventory)
    dump(output / "PINNED_NATIVE_DRIFT_REPORT.json", drift)
    dump(output / "PORTAL_BEHAVIOR_CENSUS_V2.json", census)
    dump(output / "NATIVE_NO_ENTRY_SOURCE_PROOFS.json",
         {"schemaVersion": 1, "sourceJarSha256": inventory["sourceJarSha256"],
          "mappedBinarySha256": inventory["mappedBinarySha256"],
          "proofsByEntityType": census["nativeNoEntrySourceProofs"]})
    dump(output / "NATIVE_CONDITIONAL_REFUSAL_SOURCE_PROOFS.json",
         {"schemaVersion": 1, "sourceJarSha256": inventory["sourceJarSha256"],
          "mappedBinarySha256": inventory["mappedBinarySha256"],
          "proofsByRowId": census["nativeConditionalRefusalSourceProofs"]})
    dump(output / "NATIVE_CONDITIONAL_TRANSFER_SOURCE_PROOFS.json",
         {"schemaVersion": 1, "sourceJarSha256": inventory["sourceJarSha256"],
          "mappedBinarySha256": inventory["mappedBinarySha256"],
          "proofsByRowId": census["nativeConditionalTransferSourceProofs"]})
    write_report(output / "PORTAL_BEHAVIOR_CENSUS_V2.md", census, drift)
    summary = {"driftPass": drift["pass"], "registeredTypes": inventory["registeredTypeCount"],
               "callerProfiles": inventory["callerProfileCount"], "portalImplementations": len(inventory["portalImplementations"]),
               "fallingBlockStates": len(inventory["fallingBlockStates"]), "frozenIds": census["frozenCaseCount"],
               "addedRows": census["addedCaseCount"], "unresolvedRows": sum(row["disposition"] is None for row in census["rows"])}
    print(json.dumps(summary, indent=2))
    if args.drift_gate and not drift["pass"]:
        return 1
    if args.gate and (not drift["pass"] or summary["unresolvedRows"]):
        return 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, KeyError, OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"PINNED NATIVE CENSUS FAILED: {error}", file=sys.stderr)
        sys.exit(2)
