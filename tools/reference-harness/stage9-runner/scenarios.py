"""Stage 9 scenario definitions. `setup` = deterministic RCON commands run once after readiness ({seed},{tier} are
formatted in). `controller` = adapter script under adapters/; `params` are passed to it verbatim.

Every scenario pins the world seed and Phoenix's production server defaults (view 8, simulation 6,
sync-chunk-writes=false) so desktop results transfer to the phone. `allow-flight=true` is a TEST deviation: bot
movement artifacts otherwise trigger vanilla's flight kick (H2 evidence), which would be a harness failure, not load."""

SEED = "-728736150286961811"      # Stage 8 S8-F7 pinned seed (land around spawn; reused for comparability)
PROPS = {"level-seed": SEED, "view-distance": "8", "simulation-distance": "6", "sync-chunk-writes": "false",
         "allow-flight": "true", "difficulty": "normal"}

COMMON_SETUP = [
    "gamerule doDaylightCycle false",          # fixed lighting/mob-spawn conditions across arms
    "gamerule doWeatherCycle false",
    "time set 1000",
    "weather clear",
    "gamerule keepInventory true",
    "gamerule doImmediateRespawn true",
]

JAVA = {"controller": "java_bots.js", "proxy": True, "botVersion": "26.1", "props": PROPS}

SCENARIOS: dict[str, dict] = {
    "idle": {
        "desc": "No bots. Pipeline smoke and idle server cost.",
        "setup": COMMON_SETUP, "props": PROPS,
    },
    "connect": {
        **JAVA,
        "desc": "Connection scaling: staggered joins at world spawn, light look/jump/chat activity, then a reconnect wave "
                "(every 4th bot leaves and rejoins) 60 s after the join phase.",
        "setup": COMMON_SETUP,
        "params": {"behaviour": "connect", "reconnectWaveAfterS": 60},
    },
    "region": {
        **JAVA,
        "desc": "Region-parallel activity: each bot gets its own anchor 320 blocks apart (separate regions), walks tethered "
                "to it, digs/places blocks, hits local animals, chats.",
        "setup": COMMON_SETUP,
        "params": {"behaviour": "spread", "spacing": 320, "tether": 16},
    },
    "groups": {
        **JAVA,
        "desc": "Clustered play (realistic friends server): bots in groups of 5 sharing one anchor, groups 320 blocks apart, "
                "each bot walks tethered 24 blocks around its group anchor, digs/places, hits local animals, chats. Same "
                "per-bot work as 'region' with far fewer distinct loaded areas.",
        "setup": COMMON_SETUP,
        "params": {"behaviour": "spread", "spacing": 320, "tether": 24, "groupSize": 5},
    },
    "cluster": {
        **JAVA,
        "desc": "Single-cluster worst case (owner directive 2026-10-10 item 3: all players at one base): every bot shares one "
                "anchor and walks tethered 16 blocks around it, digs/places, hits local animals, chats. Same per-bot work as "
                "'region'; the whole load sits in one or two engine tiles, so a region-parallel server cannot spread it.",
        "setup": COMMON_SETUP,
        "params": {"behaviour": "spread", "spacing": 320, "tether": 16, "groupSize": 1000},
    },
    "explore": {
        **JAVA,
        "desc": "Chunk generation: creative flight at y=300 outward along evenly spaced rays at ~10 blocks/s "
                "(fresh terrain unless a pregenerated fixture is passed).",
        "setup": COMMON_SETUP,
        "params": {"behaviour": "explore"},
    },
    "combat": {
        **JAVA,
        "desc": "Combat: bots paired in barrier cells at y=200 (x,z=5000+), melee + bow shots, deaths and respawns.",
        "setup": COMMON_SETUP + ["gamerule naturalRegeneration true"],
        "params": {"behaviour": "combat"},
    },
    "mixed": {
        **JAVA,
        "desc": "Mixed editions: Java bots as in 'region' plus 30% SYNTHETIC_OFFLINE_BEDROCK bots (offline bedrock-protocol, "
                "test-only Geyser validate-bedrock-login false, RakNet per-IP throttle off), each moved server-side every "
                "20 s near its own anchor on a ring at r=2600 so the server streams and Geyser translates fresh chunks.",
        "setup": COMMON_SETUP,
        "bedrock": True, "bedrockShare": 0.3, "bedrockController": "bedrock_bots.js",
        "params": {"behaviour": "spread", "spacing": 320, "tether": 16, "moveEveryS": 20, "tetherBedrock": 96},
    },
    "mounted-portal": {
        **JAVA,
        "desc": "Mounted portal travel under load: per-bot saddled pig ridden through a nether portal and back, server-"
                "authoritative rider/vehicle/dimension checks every cycle (Stage 8 S8-F7 lane semantics).",
        "setup": COMMON_SETUP,
        "params": {"behaviour": "mounted-portal"},
    },
}

# HARNESS-1 (2026-10-10): 26.2 answers the legacy gamerule names above with 'Incorrect argument', so every v1 run kept
# the day/weather cycles on (same for every arm). Workload v2 = '<scenario>-v2': the 26.2 names, and run_stage9 fails the
# run on a setup reply that rejects the command (setupCheck). v1 stays as it was for the blocks measured with it.
GAMERULES_26_2 = {"doDaylightCycle": "minecraft:advance_time", "doWeatherCycle": "minecraft:advance_weather",
                  "keepInventory": "minecraft:keep_inventory", "doImmediateRespawn": "minecraft:immediate_respawn",
                  "naturalRegeneration": "minecraft:natural_health_regeneration", "doMobSpawning": "minecraft:spawn_mobs"}
SETUP_REJECTED = r"Incorrect argument|Unknown or incomplete|Unknown command|Expected|Invalid"


def _setup_v2(command: str) -> str:
    parts = command.split()
    if len(parts) == 3 and parts[0] == "gamerule" and parts[1] in GAMERULES_26_2:
        return f"gamerule {GAMERULES_26_2[parts[1]]} {parts[2]}"
    return command


for _name, _definition in list(SCENARIOS.items()):
    SCENARIOS[f"{_name}-v2"] = {**_definition, "setup": [_setup_v2(c) for c in _definition.get("setup", [])],
                                "setupCheck": True,
                                "desc": _definition["desc"] + " [workload v2: 26.2 gamerule names, setup replies checked (HARNESS-1)]"}

# Phase 1 'direct legacy Via run' (owner directive): the region v2 workload with 1.21.11 bots connecting straight to the
# server, so the server's own ViaFabric/ViaVersion/ViaBackwards translate every packet (no ViaProxy in front).
SCENARIOS["region-legacy-v2"] = {**SCENARIOS["region-v2"], "proxy": False, "botVersion": "1.21.11",
                                 "desc": SCENARIOS["region-v2"]["desc"] + " [legacy: 1.21.11 bots direct to the server's Via]"}
