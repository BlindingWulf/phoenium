"""Stage 9 server stacks. Every stack is built from the SAME pinned Stage 8 inputs (template static files, pinned
configs, Stage 8 config overlay, dependency lock), so arms differ only in the mod list and JVM/system properties.
The Stage 9 telemetry jar is added to every arm identically."""
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import re
import shutil
import sys

# 2026-10-23 storage: immutable stack files (libraries/, versions/, launcher, mod jars) are hard-linked from the shared
# content-addressed store instead of copied per run (docs/STORAGE.md). PHOENIUM_CAS_DISABLE=1 restores plain copies.
sys.path.insert(0, r"C:\Users\Admin\Downloads\Project PHOENIUM\tools\storage")
try:
    import phoenium_cas as _cas  # noqa: E402
except ImportError:  # pragma: no cover - store tooling missing: behave exactly as before
    _cas = None


def _stage_tree(src, dst):
    return _cas.place_tree(src, dst) if _cas else shutil.copytree(src, dst)


def _stage_file(src, dst):
    return _cas.place(src, dst) if _cas else shutil.copy2(src, dst)


ROOT = pathlib.Path(r"C:\Users\Admin\Downloads\Project PHOENIUM")
S8 = ROOT / ".audit" / "stage8-compat-20261007"
JARS = S8 / "deps" / "jars"
LOCK = S8 / "deps" / "STAGE8_DEPENDENCY_LOCK.json"
TEMPLATE = ROOT / ".audit" / "portal-control-e1f80b7-20260921-200926"          # Stage 8 pinned static-input template
PINNED = ROOT / ".audit" / "container-close-admission-20260922" / "PINNED_PROFILE_MANIFEST.json"
STAGE8_OVERLAY = S8 / "template-config"
PATCHED_C2ME = ROOT / ".audit" / "stage7b-vehicle-move-fence-20261007" / "bedrock-server-21ae1b5a23da" / "mods" / \
    "c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar"
PATCHED_C2ME_SHA = "8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53"
PHOENIUM_384E = ROOT / ".audit" / "stage8-f7-fix-20261008" / "candidate" / "phoenium-stage8-f7-384E6ED993CE.jar"
PHOENIUM_384E_SHA = "384E6ED993CE7EFBED473AFCA72A0C9EE7CF1EA1D4F82B7B636E02C6E130B2BC"
CANDIDATES = ROOT / ".audit" / "stage9-20261009" / "candidates"      # Stage 9 Phoenium candidates (new ids, never 384E)
PHOENIUM_D1 = (CANDIDATES / "phoenium-stage9-d1-2BE571A7BFD9.jar",
               "2BE571A7BFD9E69142359A992231AB1FE5746123B6AFE96FAF7105EA90A554D9")   # cfca8ae
PHOENIUM_D2 = (CANDIDATES / "phoenium-stage9-d2-79F9DE260EF0.jar",
               "79F9DE260EF0C5ABA929B3767FCBCC7B4E85477A4CEF0B133FF681E27B43ECBA")   # 248a370 = D1 + identity index
PHOENIUM_D3 = (CANDIDATES / "phoenium-stage9-d3-322FDE4155CF.jar",
               "322FDE4155CF77A48726EB7EDE359DB88F983A63179C17A6100B36952D7E15B2")   # bd7f393 = D2 + O(1) session check
PHOENIUM_D4 = (CANDIDATES / "phoenium-stage9-d4-842DFB9B093F.jar",
               "842DFB9B093F0532E1A7ACA01C93291CF382CD524001CC4914A5826543E98A79")   # 206b2c9 = D3 + hot-path guards
PHOENIUM_D5 = (CANDIDATES / "phoenium-stage9-d5-CDC673180C36.jar",
               "CDC673180C367C46030F9E6A6D590F80C89AE38F8125C2E5484F64EC96A602E9")   # 8601b23 = D4 + move marks
PHOENIUM_D6 = (CANDIDATES / "phoenium-stage9-d6-96F9FFA3FF8E.jar",
               "96F9FFA3FF8E99CE5A43121F402CB76CBD689E54958D5CA09A53EA11AEF690C2")   # 78e9558 = D5 + O(1) route worlds
PHOENIUM_D7 = (CANDIDATES / "phoenium-stage9-d7-0DCCDEF640D9.jar",
               "0DCCDEF640D97860DCBCBCE4BA9686E304298DD107089D36C78FE69AAF25C2D6")   # ceb286f = D6 + uniform-world paths, ticket leak
PHOENIUM_D8 = (CANDIDATES / "phoenium-stage9-d8-4BBA4092B2AE.jar",
               "4BBA4092B2AEBB0073EBDFF44F46357EEE00A31A2C6DF6C27E68A7EF81AFC7CF")   # c087802 = D7 + no live readiness wait
PHOENIUM_D9 = (CANDIDATES / "phoenium-stage9-d9-856ABD2464F7.jar",
               "856ABD2464F74042692A691FAB9F54D399DF645AE2F86876D3EAEEF38871407D")   # 92efa58 = D8 + FastThreadLocal slots
PHOENIUM_D10 = (CANDIDATES / "phoenium-stage9-d10-20CC503C1073.jar",
                "20CC503C1073721F5C599654E9580D5B2D4690C0EEF710C77099DF68F9BE6A64")   # 3c8439b = D9 + direct owner turns
PHOENIUM_D11 = (CANDIDATES / "phoenium-stage9-d11-153BBB6337D4.jar",
                "153BBB6337D44C640559298A333FF173003CF2BF78F4597D70E0CF74A4455615")   # 217389b = D10 + coalesced owner turns
PHOENIUM_D12 = (CANDIDATES / "phoenium-stage9-d12-81FD742D42BC.jar",
                "81FD742D42BC8B0DDC5B14986083D11086412FBF08CA8C496BFF1A674D4FCF3C")   # 8d91592 = D11 + move/ownership paths
PHOENIUM_D13 = (CANDIDATES / "phoenium-stage9-d13-89D8781465F2.jar",
                "89D8781465F24BC8E174C62041E72AE5D17B800095954A5472F857B168A3A7A4")   # 5894d58 = D12 + chunk turns, getters, C2ME bound accessors
PHOENIUM_D14 = (CANDIDATES / "phoenium-stage9-d14-629FA177548D.jar",
                "629FA177548DA583AB7615275C44D47E7C44FCEFDC81F9F1E2B7F0FD1CB060C7")   # 5a1aee3 = D13 + lock-free leases, lean move/tracker paths, owned spawn queries
PHOENIUM_D15 = (CANDIDATES / "phoenium-stage9-d15-D59B1831602D.jar",
                "D59B1831602D9E9B60F844A3F1A6FC7013409D4E14139E29445E18A25F0E52C7")   # cdc08b5 = D14 + shared block-entity turns, per-scope FULL answer, world tables
PHOENIUM_D16 = (CANDIDATES / "phoenium-stage9-d16-6073251C80DB.jar",
                "6073251C80DBD594239C979AC73A340BE54D7D76BB641D08EF08E4818FEDDEE0")   # ccaa944 = D15 + uncopied move selections, live ownership index, per-turn owner check
PHOENIUM_D17 = (CANDIDATES / "phoenium-stage9-d17-DDD3604D9B49.jar",
                "DDD3604D9B498DDDB5ADEFD207747AB7BA46052B99839A980863E0EE9A053715")   # 3c88fe0 = D16 + canonical WorldIds, retained owner answers, checked tracking cuts
PHOENIUM_D18 = (CANDIDATES / "phoenium-stage9-d18-D69CCD945C82.jar",
                "D69CCD945C826E3260F8C3DA35E3A17214185FA749AE02BC251BF38F60DC3D9B")   # 849ff78 = D17 + spatial tracker move selection, BE query diagnostic on request
PHOENIUM_D19 = (CANDIDATES / "phoenium-stage9-d19-6F091E17A252.jar",
                "6F091E17A25228056C8C1F9A3CCA29F2C0C296A0EF5BB34816E7EC2AE37DB5B3")   # 2270822 = D18 + packet work drained before tickServer, tracker move-path counters
PHOENIUM_D20 = (CANDIDATES / "phoenium-stage9-d20-1D0379E58A06.jar",
                "1D0379E58A06D4562BB151C4FC6D6A7093C79FCA1E19BFDE1014CC17F97E3A04")   # 6c03c1e = S9-E1 colored-tile engine, entity phase (engine mode by flag)
PHOENIUM_D21 = (CANDIDATES / "phoenium-stage9-d21-8EB180EEDE22.jar",
                "8EB180EEDE22B983FE568149F3377BD015BB0E79E1A7E089B11E855D97142408")   # ad10e27 = S9-E3: chunk phase in tiles, spawn overlay, services
PHOENIUM_D22 = (CANDIDATES / "phoenium-stage9-d22-C9E91817F988.jar",
                "C9E91817F988E158D51EC49F644FFCB32CA7500FD8FF5B1DA63688274E619CED")   # 48767af = S9-E4: tracker pre-filter, villagers+bees in tiles, POI services
PHOENIUM_D24 = (CANDIDATES / "phoenium-stage9-d24-B454191AD53F.jar",
                "B454191AD53F5F94D10A50518338A392F4B5A8110225A59771B8E735198F8189")   # c2b6263 = S9-E6: exact tracker index (ChunkMap.move / moved-player pass visit only what the filter passes)
PHOENIUM_D25 = (CANDIDATES / "phoenium-stage9-d25-F68B39A43FEC.jar",
                "F68B39A43FECF02BE4B80C23E82A197390EC6A30123BDF749A1A39674A8A2596")   # dcacf96 = S9-E7: spawn-state memo (createState looks each chunk up once per call)
PHOENIUM_D26 = (CANDIDATES / "phoenium-stage9-d26-3811F811E5A8.jar",
                "3811F811E5A87C75E3F1D90ACF5C031D4D135566F8108F55C76B535D4FF8E4D9")   # a4232f0 = S9-E8: memberships follow at the transition + self-check, section timers, critical path, phoenium mark
PHOENIUM_D27 = (CANDIDATES / "phoenium-stage9-d27-B3034F084803.jar",
                "B3034F0848037A4DBFD8242B8872B63668482971F49F23DBF7DF19D8DAB42AF7")   # 8d808cf = S9-E8b: outermost section timers, pearlOwnerStale, spawnCapOvershoot, test-only reseed
PHOENIUM_D28 = (CANDIDATES / "phoenium-stage9-d28-1993F3A620FD.jar",
                "1993F3A620FD8BF615EF2F1D5363A73D4876032BA88157431748E9E138DDA5DA")   # 583f786 = S9-E9: tile-thread sends wait for the end-of-tick flush, parallel tracker sends (per-thread outboxes, per-connection delivery), sliced spawn count, tracker sub-timers
PHOENIUM_D29 = (CANDIDATES / "phoenium-stage9-d29-B1F9D7BF7025.jar",
                "B1F9D7BF702560D4FA1E7BFCF4FFBC85BCEDAC06EC5B8694D9A2BCB1B642A681")   # a1cd4c3 = S9-E10: D28 + player/broadcast sub-timers (MovePackets, PlayerMove, PlayerTicks, ChunkBroadcast), diagnostics only
PHOENIUM_D30 = (CANDIDATES / "phoenium-stage9-d30-32D5AFE7C155.jar",
                "32D5AFE7C1553AB3ED98E11C0758243E224EB00362D571B934D43B4C2BB0A3E8")   # 117cf2a = S9-E11: D29 + waiting properties (spins, idleParkUs, dagWake/dagParkUs), D29 defaults
PHOENIUM_D31 = (CANDIDATES / "phoenium-stage9-d31-4624CB544A5F.jar",
                "4624CB544A5FEF1790A71E54AB256193F72E34AE6B825814024161E9CA3D708E")   # 15c27ee = S9-E12: D30 + incremental autosave (phoenium.engine.autosaveSpread, default off)
PHOENIUM_D32 = (CANDIDATES / "phoenium-stage9-d32-EBAB88745664.jar",
                "EBAB887456648D8DCB340B6A9B0F21916D6462CF9CD929DA9F632522C433BA2F")   # 3e1889d = S9-E13: D31 + R-C2 move split (phoenium.engine.moveSplit, default off)
PHOENIUM_D33 = (CANDIDATES / "phoenium-stage9-d33-CECD3984AF7D.jar",
                "CECD3984AF7D0E049034F14A9FFB1973CDC96C1C7316379542842BCE39C0EDAC")   # 518506c = S9-E14: D32 + player saves in the spread autosave, review fixes (save-off entity drain, waypoint order, per-move failure recovery)
PHOENIUM_D34 = (CANDIDATES / "phoenium-stage9-d34-14D790AB4E6D.jar",
                "14D790AB4E6D3EBCF87578B82DCF6697F76F248E6BD945A92261AD3AE38C507D")   # d1b03f9 = S9-E15: D33 + R-C2 F1 early flush (moveSplit.early, default true), autosave tick budget
#   (autosaveSpread.tickBudgetUs, default 2000) + per-key outcome counters, spawn memo check on pinned chunk availability
PHOENIUM_D35 = (CANDIDATES / "phoenium-stage9-d35-254417DAC55A.jar",
                "254417DAC55A45878491297198B15622AB595C8C25E1830E9A8B28A639996E66")   # 496a9cc = S9-E16: D34 + supported-stack startup guard (refuses to start without the
#   patched C2ME unless -Dphoenium.allowUnsupportedStack=true); runtime paths identical to D34
PHOENIUM_D39 = (CANDIDATES / "phoenium-stage9-d39-2743E6B15B27.jar",
                "2743E6B15B27525E297D7B7E9C1927469935BF7C21C90C9C1098E52CAA21561C")   # d374880 = S9-M6 (MEM-6 EXPERIMENT, m6-arms only): D35 + C2ME worldgen tasks release their action once started
PHOENIUM_D42P = (CANDIDATES / "phoenium-stage9-d39d36-3A44EAF6BF90.jar",
                 "3A44EAF6BF9069E0572208EFDE524BEF849F659A1DF473ADDAC72C145DF0BBAF")   # 9c37f8c = D39 3335e36 + D36 41815a1 (mem.releaseNoise); provisional D42, id pending owner
PHOENIUM_D43 = (CANDIDATES / "phoenium-stage9-d43-D055242F95CC.jar",
                "D055242F95CC7DB5E58626C985706442E12CBF4AA791E2C3897B74B1451C1A8D")   # fd6abb0 = D42 + S9-E19 width-serial phases; EXPERIMENTAL
PHOENIUM_D45 = (CANDIDATES / "phoenium-stage9-d45-F861BE01BF9A.jar",
                "F861BE01BF9AD31326025A049B886282D269E8D4B66B1388A9F605BDE75DB168")   # 0cb648b = D43 + Stage 11 entity-hook gate v2.1 (RC1, private)
PHOENIUM_D40B = (CANDIDATES / "phoenium-stage9-d40-25E170F5B280.jar",
                 "25E170F5B280C07972AD5EECEC8AD456B2F0D475FCFDA8AEAA38AFC271015CD8")   # d6da911 = D40 0697b52 (FlowSched queue compaction) + review fixes R1-R4; EXPERIMENTAL
ENGINE = {"phoenium.engine.tiles": "true"}
# every engine self-check every tick (correctness evidence, not for timing)
CHECK1 = {"phoenium.engine.trackerIndex.check": "1", "phoenium.engine.trackerIndex.full": "1", "phoenium.engine.spawnMemo.check": "1",
          "phoenium.engine.protection.check": "1", "phoenium.engine.trackerSend.min": "1", "phoenium.engine.spawnSlices.min": "32"}
AUTOSAVE = {"phoenium.engine.autosaveSpread": "true"}
MOVESPLIT = {"phoenium.engine.moveSplit": "true"}
# S9-E15 (D34) A/B switches: D33 behaviour for the autosave budget and for the move split
AUTOSAVE_D33 = {"phoenium.engine.autosaveSpread.tickBudgetUs": "0"}
NOEARLY = {"phoenium.engine.moveSplit.early": "false"}
LIVECHECK = {"phoenium.engine.spawnMemo.liveCheck": "true"}
# S9-E11 waiting configs: eff1 = 1024 spins, 50 ms idle parks, dag wake; eff2 = 128 spins and the same
EFF1 = {"phoenium.engine.spins": "1024", "phoenium.engine.idleParkUs": "50000", "phoenium.engine.dagWake": "true"}
EFF2 = {"phoenium.engine.spins": "128", "phoenium.engine.idleParkUs": "50000", "phoenium.engine.dagWake": "true"}
# eff0 = only the 50 ms idle parks (a published job unparks every tile thread, so no added latency is expected)
EFF0 = {"phoenium.engine.idleParkUs": "50000"}
# eff3 = D29's 8192 spins, then dag wake instead of 20 us polling, and 50 ms idle parks
EFF3 = {"phoenium.engine.idleParkUs": "50000", "phoenium.engine.dagWake": "true"}
TELEMETRY =ROOT / "tools" / "stage9" / "telemetry-mod" / "build" / "stage9-telemetry-1.1.0.jar"
# 1.1.0 (2026-10-10) adds each tick's frame (processPacketsAndTick) to ticks.csv; tickServer timing (MSPT) is unchanged
TELEMETRY_SHA = "929900992C16ECBA35BAEFEFE6EDF50AB468C7364C80C0AFE20A80A2BC498535"
EXTRA_JARS = ROOT / ".audit" / "stage9-20261009" / "deps" / "jars"   # Stage 9 additions (each pinned by SHA-256 below)

GENERIC = ["fabric-api", "geyser", "floodgate"]
L5 = ["fabric-api", "c2me-patched", "lithium", "scalablelux", "servercore", "clumps", "chunky", "geyser", "floodgate",
      "viafabric", "viaversion", "viabackwards", "simple-voice-chat", "no-chat-reports"]

VMP = "extra:vmp-fabric-mc26.2-0.2.0+beta.7.236-all.jar:E1BAEB5E1A0948C9FC49415B93EBAAC4007355094B432EA151B3C8CCE7FFE678"
FERRITE = "extra:ferritecore-9.0.0-fabric.jar:213966C72ED967ACC7392BEB28A866FBA301FF56B9976C2E7801C2DB7DE6BF22"
KRYPTON = "extra:krypton-0.3.1.jar:5EA8901561973D29E51E751469D52D92100F348AB461E1186F67012E93420C48"
MOONRISE = "extra:Moonrise-Fabric-1.1.2+151ce65.jar:134E45FFB1CFB3B356C53A5FF0EAA04D87D585C8B5C75F2EC253FEAB3786C369"
PROBE2 = "extra:stage9-memprobe-1.1.0.jar:33F08E3DC8AB41D621207780F940B501A5AD4C5DB9C31A5C7E6BF0C577C8AC32"   # MEM-6 measurement-only probe v2 (m6-arms harness only, never X1)
ASYNC = "extra:async-fabric-0.2.5+alpha-26.2.jar:8AC3C058916FEE134FDB715BC72269D0E29CFE1C39001D0AE5527A1DA444C022"   # GPL, comparison only

# id -> definition. "phoenium": jar path + sha or None. "props": server.properties overrides. "sysprops": -D flags.
STACKS: dict[str, dict] = {
    "A-generic": {"mods": GENERIC, "phoenium": None,
                  "desc": "Generic Phoenix-equivalent: Fabric API + Geyser + Floodgate, no optimisation mods, no Phoenium"},
    "FX-chunky": {"mods": GENERIC + ["chunky"], "phoenium": None,
                  "desc": "Fixture builder only: generic stack + Chunky (vanilla generation, stack-neutral fixture)"},
    # Baseline B: one optimisation mod at a time on top of the generic stack, then the winners combined.
    "B-lithium": {"mods": GENERIC + ["lithium"], "phoenium": None, "desc": "Generic + Lithium"},
    "B-vmp": {"mods": GENERIC + [VMP], "phoenium": None, "desc": "Generic + VMP (entity tracking / many players)"},
    "B-ferrite": {"mods": GENERIC + [FERRITE], "phoenium": None, "desc": "Generic + FerriteCore (memory)"},
    "B-krypton": {"mods": GENERIC + [KRYPTON], "phoenium": None, "desc": "Generic + Krypton (network stack)"},
    "B-c2me": {"mods": GENERIC + ["c2me-upstream"], "phoenium": None, "desc": "Generic + upstream C2ME 0.4.2-alpha.0.43"},
    "B-moonrise": {"mods": GENERIC + [MOONRISE], "phoenium": None, "desc": "Generic + Moonrise (Paper chunk system port)"},
    # Moonrise combinations, added one mod at a time (Moonrise declares breaks c2me/starlight, so never with L5/Phoenium)
    "B-moon-lith": {"mods": GENERIC + [MOONRISE, "lithium"], "phoenium": None, "desc": "Generic + Moonrise + Lithium"},
    "B-moon-lith-ferr": {"mods": GENERIC + [MOONRISE, "lithium", FERRITE], "phoenium": None,
                         "desc": "Generic + Moonrise + Lithium + FerriteCore"},
    "B-moon-lith-ferr-kryp": {"mods": GENERIC + [MOONRISE, "lithium", FERRITE, KRYPTON], "phoenium": None,
                              "desc": "Generic + Moonrise + Lithium + FerriteCore + Krypton"},
    "L5-control": {"mods": L5, "phoenium": None,
                   "desc": "Stage 8 L5 provider stack WITHOUT Phoenium (isolates Phoenium's own effect)"},
    # Owner stack pricing (2026-10-09 course correction): L5 + FerriteCore, and each optional group removed one at a time.
    # These price a mod; they never remove one by default.
    "L5-ferr": {"mods": L5 + [FERRITE], "phoenium": None, "desc": "L5 + FerriteCore (declares no conflicts with L5)"},
    # Competitor arm (2026-10-10, owner approved the download): Async parallel entity ticking on the owner stack.
    "L5-async": {"mods": L5 + [ASYNC], "phoenium": None,
                 "desc": "L5 + Async 0.2.5+alpha-26.2, shipped defaults (parallel entity ticks; comparison only)"},
    "L5-noVia": {"mods": [m for m in L5 if m not in ("viafabric", "viaversion", "viabackwards")], "phoenium": None,
                 "desc": "L5 minus ViaFabric/ViaVersion/ViaBackwards (prices legacy-client support)"},
    "L5-noVoice": {"mods": [m for m in L5 if m != "simple-voice-chat"], "phoenium": None,
                   "desc": "L5 minus Simple Voice Chat (prices voice)"},
    "L5-noSLux": {"mods": [m for m in L5 if m != "scalablelux"], "phoenium": None,
                  "desc": "L5 minus ScalableLux (vanilla light engine with C2ME)"},
    "L5-noSC": {"mods": [m for m in L5 if m != "servercore"], "phoenium": None, "desc": "L5 minus ServerCore"},
    "C-384E-w1": {"mods": L5, "phoenium": (PHOENIUM_384E, PHOENIUM_384E_SHA), "sysprops": {"phoenium.gameplay.workers": "1"},
                  "desc": "Frozen Stage 8 certified stack, Phoenium 384E6ED9, 1 gameplay worker (parallel OFF)"},
    "C-384E-w4": {"mods": L5, "phoenium": (PHOENIUM_384E, PHOENIUM_384E_SHA), "sysprops": {"phoenium.gameplay.workers": "4"},
                  "desc": "Frozen Stage 8 certified stack, Phoenium 384E6ED9, 4 gameplay workers (parallel ON)"},
    "D1-w1": {"mods": L5, "phoenium": PHOENIUM_D1, "sysprops": {"phoenium.gameplay.workers": "1"},
              "desc": "Stage 9 candidate D1 (cfca8ae): 384E + no-passenger getEffectiveRange fast path, 1 worker"},
    "D1-w4": {"mods": L5, "phoenium": PHOENIUM_D1, "sysprops": {"phoenium.gameplay.workers": "4"},
              "desc": "Stage 9 candidate D1 (cfca8ae), 4 workers"},
    "D2-w1": {"mods": L5, "phoenium": PHOENIUM_D2, "sysprops": {"phoenium.gameplay.workers": "1"},
              "desc": "Stage 9 candidate D2 (248a370): D1 + O(1) tracker identity lookup (S9-F1), 1 worker"},
    "D2-w4": {"mods": L5, "phoenium": PHOENIUM_D2, "sysprops": {"phoenium.gameplay.workers": "4"},
              "desc": "Stage 9 candidate D2 (248a370), 4 workers"},
    "D3-w1": {"mods": L5, "phoenium": PHOENIUM_D3, "sysprops": {"phoenium.gameplay.workers": "1"},
              "desc": "Stage 9 candidate D3 (bd7f393): D2 + O(1) tracking-session validation (S9-F2 step 1), 1 worker"},
    "D3-w4": {"mods": L5, "phoenium": PHOENIUM_D3, "sysprops": {"phoenium.gameplay.workers": "4"},
              "desc": "Stage 9 candidate D3 (bd7f393), 4 workers"},
    "D4-w1": {"mods": L5, "phoenium": PHOENIUM_D4, "sysprops": {"phoenium.gameplay.workers": "1"},
              "desc": "Stage 9 candidate D4 (206b2c9): D3 + move pre-filter, revision-guarded player projections, "
                      "observation guards, readiness cursor; 1 worker"},
    "D4-w4": {"mods": L5, "phoenium": PHOENIUM_D4, "sysprops": {"phoenium.gameplay.workers": "4"},
              "desc": "Stage 9 candidate D4 (206b2c9), 4 workers"},
    "D5-w1": {"mods": L5, "phoenium": PHOENIUM_D5, "sysprops": {"phoenium.gameplay.workers": "1"},
              "desc": "Stage 9 candidate D5 (8601b23): D4 + move-plan marks, one-lock tracker refresh; 1 worker"},
    "D5-w4": {"mods": L5, "phoenium": PHOENIUM_D5, "sysprops": {"phoenium.gameplay.workers": "4"},
              "desc": "Stage 9 candidate D5 (8601b23), 4 workers"},
    "D6-w1": {"mods": L5, "phoenium": PHOENIUM_D6, "sysprops": {"phoenium.gameplay.workers": "1"},
              "desc": "Stage 9 candidate D6 (78e9558): D5 + O(1) explicit-route check, FULL-scope fast paths, "
                      "scheduler turn selection; 1 worker"},
    "D6-w4": {"mods": L5, "phoenium": PHOENIUM_D6, "sysprops": {"phoenium.gameplay.workers": "4"},
              "desc": "Stage 9 candidate D6 (78e9558), 4 workers"},
    "D7-w1": {"mods": L5, "phoenium": PHOENIUM_D7, "sysprops": {"phoenium.gameplay.workers": "1"},
              "desc": "Stage 9 candidate D7 (ceb286f): D6 + uniform-world O(1) closures, lazy footprints, replay-ticket "
                      "leak fix, ThreadLocal set(null), non-player refresh skip; 1 worker"},
    "D7-w4": {"mods": L5, "phoenium": PHOENIUM_D7, "sysprops": {"phoenium.gameplay.workers": "4"},
              "desc": "Stage 9 candidate D7 (ceb286f), 4 workers"},
    "D8-w1": {"mods": L5, "phoenium": PHOENIUM_D8, "sysprops": {"phoenium.gameplay.workers": "1"},
              "desc": "Stage 9 candidate D8 (c087802): D7 + vanilla tick readiness (no live wait), incremental tracker "
                      "partitions"},
    "D8-w4": {"mods": L5, "phoenium": PHOENIUM_D8, "sysprops": {"phoenium.gameplay.workers": "4"},
              "desc": "Stage 9 candidate D8 (c087802), 4 workers"},
    "D9-w1": {"mods": L5, "phoenium": PHOENIUM_D9, "sysprops": {"phoenium.gameplay.workers": "1"},
              "desc": "Stage 9 candidate D9 (92efa58): D8 + FastThreadLocal array slots (server thread, gameplay workers)"},
    "D9-w4": {"mods": L5, "phoenium": PHOENIUM_D9, "sysprops": {"phoenium.gameplay.workers": "4"},
              "desc": "Stage 9 candidate D9 (92efa58), 4 workers"},
    "D10-w1": {"mods": L5, "phoenium": PHOENIUM_D10, "sysprops": {"phoenium.gameplay.workers": "1"},
               "desc": "Stage 9 candidate D10 (3c8439b): D9 + direct owner turns, unchanged-seen projections, one "
                       "player cut per entity pass"},
    "D10-w4": {"mods": L5, "phoenium": PHOENIUM_D10, "sysprops": {"phoenium.gameplay.workers": "4"},
               "desc": "Stage 9 candidate D10 (3c8439b), 4 workers"},
    "D11-w1": {"mods": L5, "phoenium": PHOENIUM_D11, "sysprops": {"phoenium.gameplay.workers": "1"},
               "desc": "Stage 9 candidate D11 (217389b): D10 + coalesced owner turns (tracker, spawn state, entity "
                       "ticks), quiet portal coordinators"},
    "D11-w4": {"mods": L5, "phoenium": PHOENIUM_D11, "sysprops": {"phoenium.gameplay.workers": "4"},
               "desc": "Stage 9 candidate D11 (217389b), 4 workers"},
    "D12-w1": {"mods": L5, "phoenium": PHOENIUM_D12, "sysprops": {"phoenium.gameplay.workers": "1"},
               "desc": "Stage 9 candidate D12 (8d91592): D11 + uniform movement preparation, fused ownership "
                       "checks, per-view default routes"},
    "D12-w4": {"mods": L5, "phoenium": PHOENIUM_D12, "sysprops": {"phoenium.gameplay.workers": "4"},
               "desc": "Stage 9 candidate D12 (8d91592), 4 workers"},
    "D13-w1": {"mods": L5, "phoenium": PHOENIUM_D13, "sysprops": {"phoenium.gameplay.workers": "1"},
               "desc": "Stage 9 candidate D13 (5894d58): D12 + shared chunk turns, allocation-free entity getters, "
                       "bound C2ME provider accessors"},
    "D13-w4": {"mods": L5, "phoenium": PHOENIUM_D13, "sysprops": {"phoenium.gameplay.workers": "4"},
               "desc": "Stage 9 candidate D13 (5894d58), 4 workers"},
    "D14-w1": {"mods": L5, "phoenium": PHOENIUM_D14, "sysprops": {"phoenium.gameplay.workers": "1"},
               "desc": "Stage 9 candidate D14 (5a1aee3): D13 + lock-free lease reads, lean move/tracker paths, "
                       "owned natural-spawn structure queries, zero-budget readiness walk"},
    "D14-w4": {"mods": L5, "phoenium": PHOENIUM_D14, "sysprops": {"phoenium.gameplay.workers": "4"},
               "desc": "Stage 9 candidate D14 (5a1aee3), 4 workers"},
    "D15-w1": {"mods": L5, "phoenium": PHOENIUM_D15, "sysprops": {"phoenium.gameplay.workers": "1"},
               "desc": "Stage 9 candidate D15 (cdc08b5): D14 + shared block-entity turns, uniform-first closures, lazy "
                       "block-entity diagnostics, per-scope FULL answer, lock-free forServer, world tables, cheaper "
                       "readiness count, indexed lifecycle release"},
    "D15-w4": {"mods": L5, "phoenium": PHOENIUM_D15, "sysprops": {"phoenium.gameplay.workers": "4"},
               "desc": "Stage 9 candidate D15 (cdc08b5), 4 workers"},
    "D16-w1": {"mods": L5, "phoenium": PHOENIUM_D16, "sysprops": {"phoenium.gameplay.workers": "1"},
               "desc": "Stage 9 candidate D16 (ccaa944): D15 + uncopied move selections, lazy move footprint, refresh "
                       "no-op skip, retained tracker viewer sets, PlayerMap revision, per-turn owner-check memo, queued "
                       "count, live-only ownership index"},
    "D16-w4": {"mods": L5, "phoenium": PHOENIUM_D16, "sysprops": {"phoenium.gameplay.workers": "4"},
               "desc": "Stage 9 candidate D16 (ccaa944), 4 workers"},
    "D17-w1": {"mods": L5, "phoenium": PHOENIUM_D17, "sysprops": {"phoenium.gameplay.workers": "1"},
               "desc": "Stage 9 candidate D17 (3c88fe0): D16 + canonical WorldIds, retained tracker owner answers, "
                       "receipt-bound sessions, checked tracking cuts (session write counter), condition-only cache-store "
                       "wrappers, single-world refresh check"},
    "D17-w4": {"mods": L5, "phoenium": PHOENIUM_D17, "sysprops": {"phoenium.gameplay.workers": "4"},
               "desc": "Stage 9 candidate D17 (3c88fe0), 4 workers"},
    "D18-w1": {"mods": L5, "phoenium": PHOENIUM_D18, "sysprops": {"phoenium.gameplay.workers": "1"},
               "desc": "Stage 9 candidate D18 (849ff78) = D17 + spatial tracker move selection (cell + viewer "
                       "indexes under a verified watermark), block-entity query diagnostic only on request, 1 worker"},
    "D18-w4": {"mods": L5, "phoenium": PHOENIUM_D18, "sysprops": {"phoenium.gameplay.workers": "4"},
               "desc": "Stage 9 candidate D18 (849ff78), 4 workers"},
    "D19-w1": {"mods": L5, "phoenium": PHOENIUM_D19, "sysprops": {"phoenium.gameplay.workers": "1"},
               "desc": "Stage 9 candidate D19 (2270822) = D18 + packet-admitted owner work drained before tickServer "
                       "(vanilla placement, outside MSPT as in L5), tracker move-path counters, 1 worker"},
    "D19-w4": {"mods": L5, "phoenium": PHOENIUM_D19, "sysprops": {"phoenium.gameplay.workers": "4"},
               "desc": "Stage 9 candidate D19 (2270822), 4 workers"},
    "E1-w0": {"mods": L5, "phoenium": PHOENIUM_D20, "sysprops": {**ENGINE, "phoenium.engine.workers": "0"},
              "desc": "Stage 9 engine E1 (D20, 6c03c1e) in engine mode, every tile on the server thread: L5 + engine overhead baseline"},
    "E1-w3": {"mods": L5, "phoenium": PHOENIUM_D20, "sysprops": {**ENGINE, "phoenium.engine.workers": "3"},
              "desc": "Stage 9 engine E1 (D20, 6c03c1e) in engine mode, 3 tile threads + the server thread"},
    "E1-w7": {"mods": L5, "phoenium": PHOENIUM_D20, "sysprops": {**ENGINE, "phoenium.engine.workers": "7"},
              "desc": "Stage 9 engine E1 (D20, 6c03c1e) in engine mode, 7 tile threads + the server thread"},
    "E3-w0": {"mods": L5, "phoenium": PHOENIUM_D21, "sysprops": {**ENGINE, "phoenium.engine.workers": "0"},
              "desc": "Stage 9 engine E3 (D21, ad10e27) in engine mode: entity + chunk phases in tiles, main-thread services, every tile on the server thread (engine overhead vs L5)"},
    "E3-w2": {"mods": L5, "phoenium": PHOENIUM_D21, "sysprops": {**ENGINE, "phoenium.engine.workers": "2"},
              "desc": "Stage 9 engine E3 (D21, ad10e27) in engine mode: entity + chunk phases in tiles, main-thread services, 2 tile threads + the server thread"},
    "E3-w3": {"mods": L5, "phoenium": PHOENIUM_D21, "sysprops": {**ENGINE, "phoenium.engine.workers": "3"},
              "desc": "Stage 9 engine E3 (D21, ad10e27) in engine mode: entity + chunk phases in tiles, main-thread services, 3 tile threads + the server thread"},
    "E3-w5": {"mods": L5, "phoenium": PHOENIUM_D21, "sysprops": {**ENGINE, "phoenium.engine.workers": "5"},
              "desc": "Stage 9 engine E3 (D21, ad10e27) in engine mode: entity + chunk phases in tiles, main-thread services, 5 tile threads + the server thread"},
    "E3-w3-dag": {"mods": L5, "phoenium": PHOENIUM_D21,
                  "sysprops": {**ENGINE, "phoenium.engine.workers": "3", "phoenium.engine.schedule": "dag"},
                  "desc": "Stage 9 engine E3 (D21, ad10e27) in engine mode: entity + chunk phases in tiles, main-thread services, 3 tile threads, entity phase scheduled as a dag (no barrier between colours)"},
    "E3-w3-nochunks": {"mods": L5, "phoenium": PHOENIUM_D21,
                       "sysprops": {**ENGINE, "phoenium.engine.workers": "3", "phoenium.engine.chunks": "false"},
                       "desc": "Stage 9 engine E3 (D21, ad10e27) in engine mode: entity + chunk phases in tiles, main-thread services, 3 tile threads, chunk phase on the server thread (E1 behaviour + services; A/B of the chunk phase)"},
    "E4-w3": {"mods": L5, "phoenium": PHOENIUM_D22, "sysprops": {**ENGINE, "phoenium.engine.workers": "3"},
              "desc": "Stage 9 engine E4 (D22, 48767af) in engine mode: E3 + exact tracker pre-filter, villagers and bees in tiles, 3 tile threads + the server thread"},
    "E4-w5": {"mods": L5, "phoenium": PHOENIUM_D22, "sysprops": {**ENGINE, "phoenium.engine.workers": "5"},
              "desc": "Stage 9 engine E4 (D22, 48767af) in engine mode: E3 + exact tracker pre-filter, villagers and bees in tiles, 5 tile threads + the server thread"},
    "E4-w3-dag": {"mods": L5, "phoenium": PHOENIUM_D22,
                  "sysprops": {**ENGINE, "phoenium.engine.workers": "3", "phoenium.engine.schedule": "dag"},
                  "desc": "Stage 9 engine E4 (D22, 48767af) in engine mode: E3 + exact tracker pre-filter, villagers and bees in tiles, 3 tile threads, entity phase scheduled as a dag (no barrier between colours)"},
    "E4-w5-dag": {"mods": L5, "phoenium": PHOENIUM_D22,
                  "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag"},
                  "desc": "Stage 9 engine E4 (D22, 48767af) in engine mode: E3 + exact tracker pre-filter, villagers and bees in tiles, 5 tile threads, entity phase scheduled as a dag (no barrier between colours)"},
    "E4-w7-dag": {"mods": L5, "phoenium": PHOENIUM_D22,
                  "sysprops": {**ENGINE, "phoenium.engine.workers": "7", "phoenium.engine.schedule": "dag"},
                  "desc": "Stage 9 engine E4 (D22, 48767af) in engine mode: E3 + exact tracker pre-filter, villagers and bees in tiles, 7 tile threads, entity phase scheduled as a dag (no barrier between colours)"},
    "E4-w3-notracker": {"mods": L5, "phoenium": PHOENIUM_D22,
                        "sysprops": {**ENGINE, "phoenium.engine.workers": "3", "phoenium.engine.tracker": "false"},
                        "desc": "Stage 9 engine E4 (D22, 48767af) in engine mode: E3 + exact tracker pre-filter, villagers and bees in tiles, 3 tile threads, tracker pre-filter off (A/B of the filter)"},
    "E6-w3": {"mods": L5, "phoenium": PHOENIUM_D24, "sysprops": {**ENGINE, "phoenium.engine.workers": "3"},
              "desc": "Stage 9 engine E6 (D24, c2b6263) in engine mode: E4/E5 + exact tracker index, 3 tile threads + the server thread"},
    "E6-w3-dag": {"mods": L5, "phoenium": PHOENIUM_D24,
                  "sysprops": {**ENGINE, "phoenium.engine.workers": "3", "phoenium.engine.schedule": "dag"},
                  "desc": "Stage 9 engine E6 (D24, c2b6263) in engine mode: E4/E5 + exact tracker index, 3 tile threads, entity phase scheduled as a dag"},
    "E6-w5-dag": {"mods": L5, "phoenium": PHOENIUM_D24,
                  "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag"},
                  "desc": "Stage 9 engine E6 (D24, c2b6263) in engine mode: E4/E5 + exact tracker index, 5 tile threads, entity phase scheduled as a dag"},
    "E6-w7-dag": {"mods": L5, "phoenium": PHOENIUM_D24,
                  "sysprops": {**ENGINE, "phoenium.engine.workers": "7", "phoenium.engine.schedule": "dag"},
                  "desc": "Stage 9 engine E6 (D24, c2b6263) in engine mode: E4/E5 + exact tracker index, 7 tile threads, entity phase scheduled as a dag"},
    "E6-w3-dag-noindex": {"mods": L5, "phoenium": PHOENIUM_D24,
                          "sysprops": {**ENGINE, "phoenium.engine.workers": "3", "phoenium.engine.schedule": "dag",
                                       "phoenium.engine.trackerIndex": "false"},
                          "desc": "Stage 9 engine E6 (D24, c2b6263) in engine mode: E4/E5 + exact tracker index, 3 tile threads, dag, tracker index off (A/B of the index; = E4-w3-dag behaviour)"},
    "E6-w3-dag-check1": {"mods": L5, "phoenium": PHOENIUM_D24,
                         "sysprops": {**ENGINE, "phoenium.engine.workers": "3", "phoenium.engine.schedule": "dag",
                                      "phoenium.engine.trackerIndex.check": "1", "phoenium.engine.trackerIndex.full": "1"},
                         "desc": "Stage 9 engine E6 (D24, c2b6263) in engine mode: E4/E5 + exact tracker index, 3 tile threads, dag, index compared with the entity map every tick, member by member (correctness evidence, not for timing)"},
    "E7-w3-dag": {"mods": L5, "phoenium": PHOENIUM_D25,
                  "sysprops": {**ENGINE, "phoenium.engine.workers": "3", "phoenium.engine.schedule": "dag"},
                  "desc": "Stage 9 engine E7 (D25, dcacf96) in engine mode: E6 + spawn-state memo, 3 tile threads, entity phase scheduled as a dag"},
    "E7-w5-dag": {"mods": L5, "phoenium": PHOENIUM_D25,
                  "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag"},
                  "desc": "Stage 9 engine E7 (D25, dcacf96) in engine mode: E6 + spawn-state memo, 5 tile threads, entity phase scheduled as a dag"},
    "E7-w7-dag": {"mods": L5, "phoenium": PHOENIUM_D25,
                  "sysprops": {**ENGINE, "phoenium.engine.workers": "7", "phoenium.engine.schedule": "dag"},
                  "desc": "Stage 9 engine E7 (D25, dcacf96) in engine mode: E6 + spawn-state memo, 7 tile threads, entity phase scheduled as a dag"},
    "E7-w5-dag-nomemo": {"mods": L5, "phoenium": PHOENIUM_D25,
                         "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag",
                                      "phoenium.engine.spawnMemo": "false"},
                         "desc": "Stage 9 engine E7 (D25, dcacf96) in engine mode: E6 + spawn-state memo, 5 tile threads, dag, spawn-state memo off (A/B of the memo; = E6-w5-dag behaviour)"},
    "E7-w3-dag-check1": {"mods": L5, "phoenium": PHOENIUM_D25,
                         "sysprops": {**ENGINE, "phoenium.engine.workers": "3", "phoenium.engine.schedule": "dag",
                                      "phoenium.engine.trackerIndex.check": "1", "phoenium.engine.trackerIndex.full": "1", "phoenium.engine.spawnMemo.check": "1"},
                         "desc": "Stage 9 engine E7 (D25, dcacf96) in engine mode: E6 + spawn-state memo, 3 tile threads, dag, tracker index checked every tick member by member and every spawn state rebuilt the vanilla way and compared (correctness evidence, not for timing)"},
    "E8-w1-dag": {"mods": L5, "phoenium": PHOENIUM_D26,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "1", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E8 (D26, a4232f0) in engine mode: E7 + memberships follow at the transition, section timers, 1 tile threads, dag"},
    "E8-w2-dag": {"mods": L5, "phoenium": PHOENIUM_D26,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "2", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E8 (D26, a4232f0) in engine mode: E7 + memberships follow at the transition, section timers, 2 tile threads, dag"},
    "E8-w3-dag": {"mods": L5, "phoenium": PHOENIUM_D26,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "3", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E8 (D26, a4232f0) in engine mode: E7 + memberships follow at the transition, section timers, 3 tile threads, dag"},
    "E8-w5-dag": {"mods": L5, "phoenium": PHOENIUM_D26,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E8 (D26, a4232f0) in engine mode: E7 + memberships follow at the transition, section timers, 5 tile threads, dag"},
    "E8-w7-dag": {"mods": L5, "phoenium": PHOENIUM_D26,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "7", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E8 (D26, a4232f0) in engine mode: E7 + memberships follow at the transition, section timers, 7 tile threads, dag"},
    "E8-w3-dag-check1": {"mods": L5, "phoenium": PHOENIUM_D26,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "3", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", "phoenium.engine.trackerIndex.check": "1", "phoenium.engine.trackerIndex.full": "1", "phoenium.engine.spawnMemo.check": "1", "phoenium.engine.protection.check": "1"},
              "desc": "Stage 9 engine E8 (D26, a4232f0) in engine mode: E7 + memberships follow at the transition, section timers, 3 tile threads, dag, every self-check every tick (correctness evidence, not for timing)"},
    "E8b-w5-dag": {"mods": L5, "phoenium": PHOENIUM_D27,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E8b (D27, 8d808cf) in engine mode: E8 + outermost section timers, pearlOwnerStale, spawnCapOvershoot (measured), 5 tile threads, dag"},
    # S9-E9 (D28): the three parts are separate flags (default on), so one jar gives the A/B of each
    "E9-w5-dag": {"mods": L5, "phoenium": PHOENIUM_D28,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E9 (D28, 583f786) in engine mode: E8b + end-of-tick flush for tile-thread sends, parallel tracker sends, sliced spawn count, 5 tile threads, dag"},
    "E9-w1-dag": {"mods": L5, "phoenium": PHOENIUM_D28,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "1", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E9 (D28, 583f786) in engine mode, 1 tile thread, dag"},
    "E9-w2-dag": {"mods": L5, "phoenium": PHOENIUM_D28,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "2", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E9 (D28, 583f786) in engine mode, 2 tile threads, dag"},
    "E9-w7-dag": {"mods": L5, "phoenium": PHOENIUM_D28,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "7", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E9 (D28, 583f786) in engine mode, 7 tile threads, dag"},
    "E9-w5-dag-off": {"mods": L5, "phoenium": PHOENIUM_D28,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true",
                           "phoenium.engine.sendFlush": "false", "phoenium.engine.trackerSend": "false", "phoenium.engine.spawnSlices": "false"},
              "desc": "Stage 9 engine E9 (D28, 583f786) in engine mode with all three S9-E9 parts off (= E8b behaviour, D28 timers): the same-jar A/B baseline, 5 tile threads, dag"},
    "E9-w5-dag-noSend": {"mods": L5, "phoenium": PHOENIUM_D28,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true",
                           "phoenium.engine.trackerSend": "false"},
              "desc": "Stage 9 engine E9 (D28, 583f786) in engine mode, parallel tracker sends off (A/B), 5 tile threads, dag"},
    "E9-w5-dag-noSlices": {"mods": L5, "phoenium": PHOENIUM_D28,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true",
                           "phoenium.engine.spawnSlices": "false"},
              "desc": "Stage 9 engine E9 (D28, 583f786) in engine mode, sliced spawn count off (A/B), 5 tile threads, dag"},
    "E9-w5-dag-noFlush": {"mods": L5, "phoenium": PHOENIUM_D28,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true",
                           "phoenium.engine.sendFlush": "false"},
              "desc": "Stage 9 engine E9 (D28, 583f786) in engine mode, end-of-tick flush for tile-thread sends off (A/B), 5 tile threads, dag"},
    "E9-w3-dag-check1": {"mods": L5, "phoenium": PHOENIUM_D28,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "3", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true",
                           "phoenium.engine.trackerIndex.check": "1", "phoenium.engine.trackerIndex.full": "1", "phoenium.engine.spawnMemo.check": "1",
                           "phoenium.engine.protection.check": "1", "phoenium.engine.trackerSend.min": "1", "phoenium.engine.spawnSlices.min": "32"},
              "desc": "Stage 9 engine E9 (D28, 583f786) in engine mode, 3 tile threads, dag, every self-check every tick (every spawn state also built the vanilla way and compared), tracker sends always parallel, spawn count sliced from 64 entities (correctness evidence, not for timing)"},
    # S9-E10 (D29): D28 + the players' sub-timers (no behaviour change); the measured and gated candidate from 2026-10-10 14:00
    "E10-w5-dag": {"mods": L5, "phoenium": PHOENIUM_D29,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E10 (D29, a1cd4c3) in engine mode: E9 + player/broadcast sub-timers, 5 tile threads, dag"},
    "E10-w1-dag": {"mods": L5, "phoenium": PHOENIUM_D29,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "1", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E10 (D29, a1cd4c3) in engine mode, 1 tile thread, dag"},
    "E10-w2-dag": {"mods": L5, "phoenium": PHOENIUM_D29,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "2", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E10 (D29, a1cd4c3) in engine mode, 2 tile threads, dag"},
    "E10-w7-dag": {"mods": L5, "phoenium": PHOENIUM_D29,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "7", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E10 (D29, a1cd4c3) in engine mode, 7 tile threads, dag"},
    # S9-E11 (D30): waiting A/B on one jar (defaults = D29's waiting)
    "E11-w5-dag": {"mods": L5, "phoenium": PHOENIUM_D30,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E11 (D30, 117cf2a) in engine mode, D29's waiting (8192 spins, 1 ms idle parks, dag polling), 5 tile threads, dag"},
    "E11-w5-dag-eff1": {"mods": L5, "phoenium": PHOENIUM_D30,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **EFF1},
              "desc": "Stage 9 engine E11 (D30, 117cf2a) in engine mode, waiting eff1 (1024 spins, 50 ms idle parks, dag wake), 5 tile threads, dag"},
    "E11-w5-dag-eff2": {"mods": L5, "phoenium": PHOENIUM_D30,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **EFF2},
              "desc": "Stage 9 engine E11 (D30, 117cf2a) in engine mode, waiting eff2 (128 spins, 50 ms idle parks, dag wake), 5 tile threads, dag"},
    "E11-w5-dag-eff0": {"mods": L5, "phoenium": PHOENIUM_D30,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **EFF0},
              "desc": "Stage 9 engine E11 (D30, 117cf2a) in engine mode, waiting eff0 (D29 spins and dag polling, 50 ms idle parks), 5 tile threads, dag"},
    "E11-w5-dag-eff3": {"mods": L5, "phoenium": PHOENIUM_D30,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **EFF3},
              "desc": "Stage 9 engine E11 (D30, 117cf2a) in engine mode, waiting eff3 (D29 8192 spins, then dag wake; 50 ms idle parks), 5 tile threads, dag"},
    "E11-w1-dag": {"mods": L5, "phoenium": PHOENIUM_D30,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "1", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E11 (D30, 117cf2a) in engine mode, D29's waiting, 1 tile thread, dag"},
    "E11-w7-dag": {"mods": L5, "phoenium": PHOENIUM_D30,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "7", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E11 (D30, 117cf2a) in engine mode, D29's waiting, 7 tile threads, dag"},
    "E11-w2-dag": {"mods": L5, "phoenium": PHOENIUM_D30,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "2", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E11 (D30, 117cf2a) in engine mode, D29's waiting, 2 tile threads, dag"},
    "E11-w2-dag-eff1": {"mods": L5, "phoenium": PHOENIUM_D30,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "2", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **EFF1},
              "desc": "Stage 9 engine E11 (D30, 117cf2a) in engine mode, waiting eff1, 2 tile threads, dag"},
    # S9-E12 (D31): incremental autosave behind a flag (owner decision 2026-10-10)
    "E12-w5-dag-autosave-check1": {"mods": L5, "phoenium": PHOENIUM_D31,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true",
                           **AUTOSAVE, **CHECK1},
              "desc": "Stage 9 engine E12 (D31, 15c27ee) in engine mode, incremental autosave on, 5 tile threads, dag, every self-check every tick (correctness evidence, not for timing)"},
    # S9-E13 (D32): D31 + R-C2 move split behind a flag; same-jar A/B (owner decision 2026-10-10)
    "E13-w5-dag": {"mods": L5, "phoenium": PHOENIUM_D32,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E13 (D32, 3e1889d) in engine mode, D30 behaviour (autosave spread and move split off; the A/B control), 5 tile threads, dag"},
    "E13-w5-dag-autosave": {"mods": L5, "phoenium": PHOENIUM_D32,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **AUTOSAVE},
              "desc": "Stage 9 engine E13 (D32, 3e1889d) in engine mode, incremental autosave on, 5 tile threads, dag"},
    "E13-w5-dag-movesplit": {"mods": L5, "phoenium": PHOENIUM_D32,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **MOVESPLIT},
              "desc": "Stage 9 engine E13 (D32, 3e1889d) in engine mode, R-C2 move split on, 5 tile threads, dag"},
    "E13-w5-dag-movesplit-check1": {"mods": L5, "phoenium": PHOENIUM_D32,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true",
                           **MOVESPLIT, **CHECK1, "phoenium.engine.moveSplit.check": "1"},
              "desc": "Stage 9 engine E13 (D32, 3e1889d) in engine mode, R-C2 move split on, 5 tile threads, dag, every self-check every tick and the move split check every tick (correctness evidence, not for timing)"},
    "E13-w5-dag-autosave-check1": {"mods": L5, "phoenium": PHOENIUM_D32,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true",
                           **AUTOSAVE, **CHECK1},
              "desc": "Stage 9 engine E13 (D32, 3e1889d) in engine mode, incremental autosave on, 5 tile threads, dag, every self-check every tick (correctness evidence, not for timing)"},
    # S9-E14 (D33): same-jar A/B and check stacks
    "E14-w5-dag": {"mods": L5, "phoenium": PHOENIUM_D33,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E14 (D33, 518506c) in engine mode, autosave spread and move split off (D30 behaviour), 5 tile threads, dag (A/B control)"},
    "E14-w5-dag-autosave": {"mods": L5, "phoenium": PHOENIUM_D33,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **AUTOSAVE},
              "desc": "Stage 9 engine E14 (D33, 518506c) in engine mode, incremental autosave on (chunks, entities, players), 5 tile threads, dag"},
    "E14-w5-dag-movesplit": {"mods": L5, "phoenium": PHOENIUM_D33,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **MOVESPLIT},
              "desc": "Stage 9 engine E14 (D33, 518506c) in engine mode, R-C2 move split on, 5 tile threads, dag"},
    "E14-w5-dag-all-check1": {"mods": L5, "phoenium": PHOENIUM_D33,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **AUTOSAVE, **MOVESPLIT, **CHECK1, "phoenium.engine.moveSplit.check": "1"},
              "desc": "Stage 9 engine E14 (D33, 518506c) in engine mode, incremental autosave and R-C2 move split on, every self-check every tick incl. the serial move comparison (correctness evidence, not for timing)"},
    # S9-E15 (D34): same-jar A/B and check stacks
    "E15-w5-dag": {"mods": L5, "phoenium": PHOENIUM_D34,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E15 (D34, d1b03f9) in engine mode, autosave spread and move split off, 5 tile threads, dag (A/B control)"},
    "E15-w5-dag-autosave": {"mods": L5, "phoenium": PHOENIUM_D34,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **AUTOSAVE},
              "desc": "Stage 9 engine E15 (D34, d1b03f9) in engine mode, incremental autosave on with the 2 ms tick budget, 5 tile threads, dag"},
    "E15-w5-dag-autosave-d33": {"mods": L5, "phoenium": PHOENIUM_D34,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **AUTOSAVE, **AUTOSAVE_D33},
              "desc": "Stage 9 engine E15 (D34, d1b03f9) in engine mode, incremental autosave on with D33 per-queue budgets only (tickBudgetUs=0), 5 tile threads, dag"},
    "E15-w5-dag-movesplit": {"mods": L5, "phoenium": PHOENIUM_D34,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **MOVESPLIT},
              "desc": "Stage 9 engine E15 (D34, d1b03f9) in engine mode, R-C2 move split on with the F1 early flush, 5 tile threads, dag"},
    "E15-w5-dag-movesplit-noearly": {"mods": L5, "phoenium": PHOENIUM_D34,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **MOVESPLIT, **NOEARLY},
              "desc": "Stage 9 engine E15 (D34, d1b03f9) in engine mode, R-C2 move split on without the F1 early flush (D33 behaviour), 5 tile threads, dag"},
    "E15-w5-dag-all": {"mods": L5, "phoenium": PHOENIUM_D34,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **AUTOSAVE, **MOVESPLIT},
              "desc": "Stage 9 engine E15 (D34, d1b03f9) in engine mode, incremental autosave (tick budget) and R-C2 move split (early flush) on, 5 tile threads, dag"},
    "E15-w5-dag-all-check1": {"mods": L5, "phoenium": PHOENIUM_D34,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **AUTOSAVE, **MOVESPLIT, **CHECK1, **LIVECHECK, "phoenium.engine.moveSplit.check": "1"},
              "desc": "Stage 9 engine E15 (D34, d1b03f9) in engine mode, autosave and move split on, every self-check every tick incl. the serial move comparison and the live spawn-memo comparison (correctness evidence, not for timing)"},
    # S9-E16 (D35): X1 candidate arms (autosave spread on/off; R-C2 move split stays off = experimental)
    "E16-w5-dag": {"mods": L5, "phoenium": PHOENIUM_D35,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E16 (D35, 496a9cc) in engine mode, autosave spread and move split off, 5 tile threads, dag"},
    "E16-w5-dag-autosave": {"mods": L5, "phoenium": PHOENIUM_D35,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **AUTOSAVE},
              "desc": "Stage 9 engine E16 (D35, 496a9cc) in engine mode, incremental autosave on (2 ms tick budget), 5 tile threads, dag"},
    "E16-w2-dag": {"mods": L5, "phoenium": PHOENIUM_D35,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "2", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E16 (D35, 496a9cc) in engine mode, autosave spread and move split off, 2 tile threads, dag"},
    "E16-w2-dag-autosave": {"mods": L5, "phoenium": PHOENIUM_D35,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "2", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", **AUTOSAVE},
              "desc": "Stage 9 engine E16 (D35, 496a9cc) in engine mode, incremental autosave on (2 ms tick budget), 2 tile threads, dag"},
    "E16-w1-dag": {"mods": L5, "phoenium": PHOENIUM_D35,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "1", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E16 (D35, 496a9cc) in engine mode, autosave spread and move split off, 1 tile thread, dag (X1 W1/W7 scaling)"},
    "E16-w7-dag": {"mods": L5, "phoenium": PHOENIUM_D35,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "7", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "Stage 9 engine E16 (D35, 496a9cc) in engine mode, autosave spread and move split off, 7 tile threads, dag (X1 W1/W7 scaling)"},
    "E16-default-w1": {"mods": L5, "phoenium": PHOENIUM_D35, "sysprops": {"phoenium.gameplay.workers": "1"},
              "desc": "Stage 9 D35 (496a9cc) in DEFAULT mode (no tile engine; the D19 regional path), 1 gameplay worker (S9-F2 release decision: default-mode cap probe)"},
    "E16-default-w4": {"mods": L5, "phoenium": PHOENIUM_D35, "sysprops": {"phoenium.gameplay.workers": "4"},
              "desc": "Stage 9 D35 (496a9cc) in DEFAULT mode (no tile engine; the D19 regional path), 4 gameplay workers (S9-F2 release decision: default-mode cap probe)"},
    # MEM-6 census arms (m6-arms harness only, never X1): the X1 arms plus the measurement-only memprobe v2
    "M6-E16-probe": {"mods": L5 + [PROBE2], "phoenium": PHOENIUM_D35,
              "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "MEM-6 census: E16-w5-dag (D35) + memprobe v2"},
    "M6-L5a-probe": {"mods": L5 + [ASYNC, PROBE2], "phoenium": None,
              "desc": "MEM-6 census: L5-async + memprobe v2"},
    "M6-d39-probe": {"mods": L5 + [PROBE2], "phoenium": PHOENIUM_D39, "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "MEM-6 census: D39 (d374880) as E16-w5-dag + memprobe v2, task release ON"},
    "M6-d39off-probe": {"mods": L5 + [PROBE2], "phoenium": PHOENIUM_D39, "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", "phoenium.c2me.releaseFinishedTasks": "false"},
              "desc": "MEM-6 census: D39 (d374880) as E16-w5-dag + memprobe v2, task release OFF (= D35, A/B control)"},
    # D39 beta gate J2 (clean, never X1): exactly the X1 E16-w5-dag configuration with the D39 jar, no memprobe, no
    # histograms and no profile JFR (run_stage9's default recording only, as in X1); task release on and off.
    "M6-d39-x1": {"mods": L5, "phoenium": PHOENIUM_D39, "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "D39 (d374880, 2743E6B15B27) as X1 E16-w5-dag, task release ON, clean (no probe)"},
    "M6-d39off-x1": {"mods": L5, "phoenium": PHOENIUM_D39, "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", "phoenium.c2me.releaseFinishedTasks": "false"},
              "desc": "D39 (d374880, 2743E6B15B27) as X1 E16-w5-dag, task release OFF (= D35 behaviour), clean (no probe)"},
    # D39 beta gate J3 (never X1): engine OFF (Phoenium default regional mode, 4 gameplay workers, as X1 E16-default-w4),
    # task release on and off.
    "M6-d39-default-w4": {"mods": L5, "phoenium": PHOENIUM_D39, "sysprops": {"phoenium.gameplay.workers": "4"},
              "desc": "D39 (2743E6B15B27) engine off, default mode W4, task release ON"},
    "M6-d39off-default-w4": {"mods": L5, "phoenium": PHOENIUM_D39, "sysprops": {"phoenium.gameplay.workers": "4", "phoenium.c2me.releaseFinishedTasks": "false"},
              "desc": "D39 (2743E6B15B27) engine off, default mode W4, task release OFF (= D35 behaviour)"},
    # D39 ANDROID-HEAP desktop envelope (J6, never X1): as X1 E16-w2-dag (2 tile threads = the phone's 2 big cores),
    # run with --heap 1536M / 1024M like the X1 extension H phone-heap cells.
    "M6-d39-w2": {"mods": L5, "phoenium": PHOENIUM_D39, "sysprops": {**ENGINE, "phoenium.engine.workers": "2", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "D39 (2743E6B15B27) as X1 E16-w2-dag, task release ON (phone-heap envelope)"},
    # J7 (never X1): MEM-6 at phone heaps; fresh-world explore-v2 with --heap 1536M / 1024M, same jar with the release off.
    "M6-d39off-w2": {"mods": L5, "phoenium": PHOENIUM_D39, "sysprops": {**ENGINE, "phoenium.engine.workers": "2", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true", "phoenium.c2me.releaseFinishedTasks": "false"},
              "desc": "D39 (2743E6B15B27) as X1 E16-w2-dag, task release OFF (= D35 behaviour; phone-heap explore control)"},
    # D36 cert Step 4 (never X1): D39+D36 (provisional D42) A/B against M6-d39-x1 / M6-d39-w2, same configuration.
    "M6-d42p-x1": {"mods": L5, "phoenium": PHOENIUM_D42P, "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "D39+D36 (9c37f8c, 3A44EAF6BF90, provisional D42) as X1 E16-w5-dag, clean (no probe)"},
    "M6-d42p-w2": {"mods": L5, "phoenium": PHOENIUM_D42P, "sysprops": {**ENGINE, "phoenium.engine.workers": "2", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "D39+D36 (3A44EAF6BF90, provisional D42) as X1 E16-w2-dag (phone-heap envelope)"},
    # D40 SERVER-RUN (never X1): D40b (FlowSched queue compaction + review fixes) against M6-d39-x1 / M6-d39-w2.
    "M6-d40b-x1": {"mods": L5, "phoenium": PHOENIUM_D40B, "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "D40b (d6da911, 25E170F5B280) as X1 E16-w5-dag, clean (no probe)"},
    "M6-d40b-w2": {"mods": L5, "phoenium": PHOENIUM_D40B, "sysprops": {**ENGINE, "phoenium.engine.workers": "2", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "D40b (d6da911, 25E170F5B280) as X1 E16-w2-dag (phone-heap envelope)"},
    # D43 SERVER-RUN (never X1): D43 against M6-d42p-x1, same configuration (X1 region/groups t16 no-regression A/B).
    "M6-d43-x1": {"mods": L5, "phoenium": PHOENIUM_D43, "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "D43 (fd6abb0, D055242F95CC) as X1 E16-w5-dag, clean (no probe)"},
    "M6-d45-x1": {"mods": L5, "phoenium": PHOENIUM_D45, "sysprops": {**ENGINE, "phoenium.engine.workers": "5", "phoenium.engine.schedule": "dag", "phoenium.test.diagnostics": "true"},
              "desc": "D45 RC1 (0cb648b, F861BE01BF9A = D43 + gate v2.1) as X1 E16-w5-dag, clean (no probe); mirrors M6-d43-x1"},
}


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest().upper()


def _lock() -> dict:
    return {a["slug"]: a for a in json.loads(LOCK.read_text())["artifacts"]}


def mod_source(key: str) -> tuple[pathlib.Path, str]:
    if key == "c2me-patched":
        return PATCHED_C2ME, PATCHED_C2ME_SHA
    if key == "c2me-upstream":                       # upstream base of the patched settlement2 build (Stage 8 reference)
        return JARS / "c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar", "B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B"
    if key.startswith("extra:"):                     # "extra:<file>:<sha256>"
        _, name, digest = key.split(":", 2)
        return EXTRA_JARS / name, digest.upper()
    artifact = _lock()[key]
    if artifact["side"] in ("client-only", "reference"):
        raise SystemExit(f"{key} is {artifact['side']}: never installed on the dedicated server")
    return JARS / artifact["file"], artifact["sha256"].upper()


def build(stack_id: str, server: pathlib.Path, ports: dict, level: str, *, synthetic_bedrock: bool,
          props: dict | None = None) -> dict:
    """Fresh server directory from pinned inputs. Verifies every hash. Returns the install manifest."""
    stack = STACKS[stack_id]
    if server.exists():
        raise SystemExit(f"server dir exists (runs are immutable): {server}")
    pinned = json.loads(PINNED.read_text(encoding="utf-8-sig"))
    for name, digest in pinned["configFilesSha256"].items():
        if sha256(TEMPLATE / "config" / name) != digest.upper():
            raise SystemExit(f"pinned template config changed: {name}")
    server.mkdir(parents=True)
    for name in ("libraries", "versions"):
        _stage_tree(TEMPLATE / name, server / name)
    shutil.copytree(TEMPLATE / "config", server / "config")
    for f in STAGE8_OVERLAY.rglob("*"):
        if f.is_file():
            dest = server / "config" / f.relative_to(STAGE8_OVERLAY)
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, dest)
    for name in ("fabric-server-launch.jar", "eula.txt"):
        _stage_file(TEMPLATE / name, server / name)
    mods_dir = server / "mods"
    mods_dir.mkdir()
    installed: dict[str, str] = {}
    sources = [mod_source(k) for k in stack["mods"]] + [(TELEMETRY, TELEMETRY_SHA)]
    if stack["phoenium"]:
        sources.append(stack["phoenium"])
    for path, digest in sources:
        actual = sha256(path)
        if actual != digest:
            raise SystemExit(f"hash mismatch {path.name}: {actual} != {digest}")
        _stage_file(path, mods_dir / path.name)
        installed[path.name] = actual
    phoenium_jars = [n for n in os.listdir(mods_dir) if n.lower().startswith("phoenium")]
    if len(phoenium_jars) != (1 if stack["phoenium"] else 0):
        raise SystemExit(f"expected exactly {1 if stack['phoenium'] else 0} Phoenium jar, found {phoenium_jars}")

    overrides = {"server-port": str(ports["java"]), "query.port": str(ports["java"]), "level-name": level,
                 "level-seed": "", "online-mode": "false", "enforce-secure-profile": "false", "server-ip": "127.0.0.1",
                 "enable-rcon": "true", "rcon.port": str(ports["rcon"]), "rcon.password": "stage9-local-test",
                 "broadcast-rcon-to-ops": "false", "max-players": "100", "motd": f"Stage9 {stack_id} {level}",
                 "pause-when-empty-seconds": "-1", "player-idle-timeout": "0", "spawn-protection": "0"}
    overrides.update(props or {})
    lines, pending = [], dict(overrides)
    for line in (TEMPLATE / "server.properties").read_text().splitlines():
        key = line.split("=")[0]
        if "=" in line and not line.startswith("#") and key in pending:
            lines.append(f"{key}={pending.pop(key)}")
        else:
            lines.append(line)
    lines += [f"{k}={v}" for k, v in pending.items()]
    (server / "server.properties").write_text("\n".join(lines) + "\n")

    geyser = server / "config" / "Geyser-Fabric" / "config.yml"
    text = geyser.read_text()
    if "port: 19172" not in text:
        raise SystemExit("unexpected Geyser template (no 19172 placeholder)")
    text = text.replace("port: 19172", f"port: {ports['bedrock']}")
    if synthetic_bedrock:  # TEST-ONLY: admits accountless bedrock-protocol clients on loopback. Never production.
        text = re.sub(r"validate-bedrock-login: true", "validate-bedrock-login: false", text)
    geyser.write_text(text)
    voice = server / "config" / "voicechat" / "voicechat-server.properties"
    if voice.exists():
        voice.write_text(re.sub(r"(?m)^port=.*$", f"port={ports['voice']}", voice.read_text()))
    return {"stack": stack_id, "desc": stack["desc"], "mods": installed, "sysprops": stack.get("sysprops", {}),
            "syntheticOfflineBedrock": synthetic_bedrock,
            "serverPropertiesSha256": sha256(server / "server.properties"),
            "config": {str(f.relative_to(server / "config")).replace("\\", "/"): sha256(f)
                       for f in (server / "config").rglob("*") if f.is_file() and f.name != "key.pem"},
            "floodgateKeySha256": sha256(server / "config" / "floodgate" / "key.pem")}
