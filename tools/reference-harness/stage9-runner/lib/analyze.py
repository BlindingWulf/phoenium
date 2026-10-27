"""Turn a run directory's raw evidence into summary metrics and the two Stage 9 verdicts.

Inputs (run_dir): telemetry-raw/{ticks.csv,samples.jsonl}, phases.json, cpu.jsonl, bots.jsonl, jvm/server.jfr.
Gates are fixed constants here (Stage 9 brief) and are never relaxed after looking at results."""
from __future__ import annotations

import datetime as dt
import json
import pathlib
import re
import statistics
import subprocess

JFR = r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\jfr.exe"

DESKTOP_GATES = {"tpsMedianMin": 19.5, "msptP50Max": 40.0, "msptP95Max": 50.0, "msptP99Max": 100.0, "stallMaxS": 5.0,
                 "gcPauseShareMax": 0.02, "gcP99MaxMs": 100.0, "gcMaxMs": 500.0, "liveHeapGrowthMax": 0.10}
# v2 (2026-10-09): one bot connection end = one disconnect incident (the adapters log exactly one 'disconnect' per
# unexpected end; 'error'/'kicked' lines that precede it are details of the same incident, v1 counted them again), and
# join-phase failures are judged by the join gates (first attempt / after retry) instead of also as disconnects.
ANALYZER_VERSION = 2
JOIN_PHASES = ("join", "retry")
EXPECTED_END_PHASES = ("teardown", "reconnect-wave")
JOIN_GATES = {"small": {"tiers": (1, 4, 8), "firstAttempt": 1.0},
              "large": {"tiers": (16, 32, 50), "firstAttempt": 0.98, "afterRetry": 0.99}}


def pct(values: list[float], q: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(-(-q * len(ordered) // 1)) - 1))
    return ordered[index]


def _iso_seconds(value: str) -> float:
    match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?", value)
    if not match:
        return 0.0
    h, m, s = match.groups()
    return int(h or 0) * 3600 + int(m or 0) * 60 + float(s or 0)


def _iso_ms(value: str) -> float:
    return dt.datetime.fromisoformat(value).timestamp() * 1000.0


def tick_metrics(run_dir: pathlib.Path, window: tuple[float, float]) -> dict:
    raw = run_dir / "telemetry-raw"
    offsets = []
    for line in (raw / "samples.jsonl").read_text().splitlines():
        sample = json.loads(line)
        offsets.append(sample["wallMs"] - sample["nano"] / 1e6)
    offset = statistics.median(offsets)
    starts, durations, frames = [], [], []
    for line in (raw / "ticks.csv").read_text().splitlines()[1:]:
        fields = line.split(",")
        start, dur = fields[1], fields[2]
        wall = int(start) / 1e6 + offset
        if window[0] <= wall < window[1]:
            starts.append(wall)
            durations.append(int(dur) / 1e6)
            # telemetry 1.1.0: the tick's frame (the packets handled just before tickServer, then tickServer)
            if len(fields) > 3 and int(fields[3]) > 0:
                frames.append(int(fields[3]) / 1e6)
    seconds = (window[1] - window[0]) / 1000.0
    if not starts:
        return {"ticks": 0, "windowS": seconds, "error": "no ticks in window"}
    tps_windows = []
    edge = window[0]
    while edge + 10_000 <= window[1]:
        tps_windows.append(sum(1 for s in starts if edge <= s < edge + 10_000) / 10.0)
        edge += 10_000
    gaps = [b - a for a, b in zip(starts, starts[1:])]
    return {"ticks": len(starts), "windowS": round(seconds, 1), "tpsMean": round(len(starts) / seconds, 3),
            "tpsMedian10s": statistics.median(tps_windows) if tps_windows else None,
            "tpsMin10s": min(tps_windows) if tps_windows else None,
            "msptMean": round(statistics.fmean(durations), 3), "msptP50": round(pct(durations, .50), 3),
            "msptP95": round(pct(durations, .95), 3), "msptP99": round(pct(durations, .99), 3),
            "msptMax": round(max(durations), 3), "ticksOver50ms": sum(1 for d in durations if d > 50),
            # tail (owner directive 2026-10-10): p99.9 and the count of ticks over 100 ms beside the max
            "msptP999": round(pct(durations, .999), 3), "ticksOver100ms": sum(1 for d in durations if d > 100),
            # start-to-start interval: includes main-thread work outside tickServer (chunk tasks, packets between ticks)
            "intervalP50": round(pct(gaps, .50), 2) if gaps else None, "intervalP95": round(pct(gaps, .95), 2) if gaps else None,
            "intervalP99": round(pct(gaps, .99), 2) if gaps else None,
            "maxTickGapMs": round(max(gaps), 1) if gaps else None,
            "stallS": round(max([max(durations)] + gaps) / 1000.0, 2),
            # frame = processPacketsAndTick: one window for an arm that handles movement at packet time (vanilla) and
            # one that handles it inside tickServer (Phoenium's owner turns); None before telemetry 1.1.0
            "frameTicks": len(frames),
            "frameP50": round(pct(frames, .50), 3) if frames else None,
            "frameP95": round(pct(frames, .95), 3) if frames else None,
            "frameP99": round(pct(frames, .99), 3) if frames else None,
            "frameP999": round(pct(frames, .999), 3) if frames else None,
            "frameMax": round(max(frames), 3) if frames else None,
            "framesOver50ms": sum(1 for f in frames if f > 50) if frames else None,
            "framesOver100ms": sum(1 for f in frames if f > 100) if frames else None,
            "frameMean": round(statistics.fmean(frames), 3) if frames else None}


def gc_metrics(run_dir: pathlib.Path, window: tuple[float, float]) -> dict:
    jfr = run_dir / "jvm" / "server.jfr"
    if not jfr.exists():
        return {"available": False, "reason": "no server.jfr"}
    out = subprocess.run([JFR, "print", "--json", "--events", "jdk.GarbageCollection,jdk.GCHeapSummary", str(jfr)],
                         capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600)
    events = json.loads(out.stdout)["recording"]["events"]
    pauses, after_gc = [], []
    for event in events:
        values = event["values"]
        when = _iso_ms(values["startTime"])
        if not (window[0] <= when < window[1]):
            continue
        if event["type"] == "jdk.GarbageCollection":
            pauses.append((values["name"], _iso_seconds(values["sumOfPauses"]) * 1000, _iso_seconds(values["longestPause"]) * 1000))
        elif values.get("when") == "After GC":
            after_gc.append((when, values["heapUsed"]))
    seconds = (window[1] - window[0]) / 1000.0
    longest = [p[2] for p in pauses]
    total_ms = sum(p[1] for p in pauses)
    result = {"available": True, "gcCount": len(pauses), "gcByName": {}, "pauseTotalMs": round(total_ms, 1),
              "pauseShare": round(total_ms / 1000.0 / seconds, 5) if seconds else None,
              "pauseP50Ms": pct(longest, .5), "pauseP95Ms": pct(longest, .95), "pauseP99Ms": pct(longest, .99),
              "pauseMaxMs": max(longest) if longest else 0.0}
    for name, _, _ in pauses:
        result["gcByName"][name] = result["gcByName"].get(name, 0) + 1
    # Live-set proxy: minimum heap-after-GC in the first vs last third of the window (G1 young GCs keep old garbage).
    if len(after_gc) >= 6:
        third = (window[1] - window[0]) / 3
        first = [h for t, h in after_gc if t < window[0] + third]
        last = [h for t, h in after_gc if t >= window[1] - third]
        if first and last:
            result["liveSetFirstMB"] = round(min(first) / 2**20, 1)
            result["liveSetLastMB"] = round(min(last) / 2**20, 1)
            result["liveSetGrowth"] = round(min(last) / min(first) - 1, 4)
    return result


CENSUS = (("all", "@e[type=!minecraft:player]"), ("items", "@e[type=minecraft:item]"),
          ("xp", "@e[type=minecraft:experience_orb]"), ("players", "@a"))


def census(command) -> dict:
    """Entity census through a command callable (RCON): vanilla `execute if entity <selector>` answers 'Test passed.
    Count: N' (26.2; older versions 'Test passed, count: N') or 'Test failed' (0); None when the reply is neither. Same
    command on every stack."""
    out: dict = {}
    for key, selector in CENSUS:
        try:
            reply = command(f"execute if entity {selector}")
        except Exception:  # noqa: BLE001  (diagnostic only)
            out[key] = None
            continue
        m = re.search(r"[Cc]ount: (\d+)", reply)
        out[key] = int(m.group(1)) if m else (0 if "Test failed" in reply else None)
    return out


def census_columns(meta: dict) -> dict:
    """summary.csv columns of the census at the window edges; mobsEnd = all - items - xp (mobs, projectiles, vehicles)."""
    out: dict = {}
    for edge, key in (("Start", "censusStart"), ("End", "censusEnd")):
        values = meta.get(key) if isinstance(meta.get(key), dict) else {}
        for name, _ in CENSUS:
            out[f"census.{name}{edge}"] = values.get(name)
        parts = [values.get(k) for k in ("all", "items", "xp")]
        out[f"census.mobs{edge}"] = parts[0] - parts[1] - parts[2] if all(isinstance(v, int) for v in parts) else None
    return out


def efficiency(tier: int, proc: dict, gc: dict) -> dict:
    """Per-player cost (owner directive 2026-10-10 item 6): server process cores (mean) and RSS (max), and the live set
    after the measure window, each divided by the tier; flat process columns beside them."""
    server = proc.get("server") or {}
    out: dict = {"proc.serverCoresMean": server.get("coresMean"), "proc.serverCoresP95": server.get("coresP95"),
                 "proc.serverRssMBMax": server.get("rssMBMax"), "proc.hostCpuPctMean": proc.get("hostCpuPctMean")}
    players = max(1, int(tier or 0))
    for key, value in (("eff.serverCoresPerPlayer", server.get("coresMean")), ("eff.rssMBPerPlayer", server.get("rssMBMax")),
                       ("eff.liveSetMBPerPlayer", gc.get("liveSetLastMB") if isinstance(gc, dict) else None)):
        out[key] = round(value / players, 4) if isinstance(value, (int, float)) else None
    return out


def process_metrics(run_dir: pathlib.Path, window: tuple[float, float]) -> dict:
    path = run_dir / "cpu.jsonl"
    rows = [json.loads(l) for l in path.read_text().splitlines()] if path.exists() else []
    rows = [r for r in rows if window[0] <= r["wallMs"] < window[1]]
    result: dict = {"samples": len(rows)}
    for name in ("server", "bots", "bedrockBots", "proxy"):
        series = [r[name] for r in rows if r.get(name)]
        if series:
            cores = [s["cores"] for s in series]
            result[name] = {"coresMean": round(statistics.fmean(cores), 2), "coresP95": pct(cores, .95),
                            "rssMBMax": max(s["rssMB"] for s in series), "threadsMax": max(s["threads"] for s in series)}
    if rows:
        result["hostCpuPctMean"] = round(statistics.fmean(r["hostCpuPct"] for r in rows), 1)
        result["hostCpuPctP95"] = pct([r["hostCpuPct"] for r in rows], .95)
    return result


def bot_metrics(run_dir: pathlib.Path, expected: int) -> dict:
    path = run_dir / "bots.jsonl"
    events = [json.loads(l) for l in path.read_text().splitlines() if l.strip()] if path.exists() else []
    first_spawn, attempts, spawned_any, joins, disconnects, actions_ok, actions_fail, pings = {}, {}, set(), [], [], 0, 0, []
    by_name: dict = {}
    portal_defects: list = []
    deaths = 0
    for e in events:
        bot = e.get("bot")
        kind = e.get("event")
        if kind == "connect":
            attempts[bot] = attempts.get(bot, 0) + 1
        elif kind == "spawn":
            if bot not in first_spawn:
                first_spawn[bot] = attempts.get(bot, 1)
                if e.get("joinMs") is not None:
                    joins.append(e["joinMs"])
            spawned_any.add(bot)
        elif kind in ("disconnect", "kicked", "error"):
            disconnects.append({"bot": bot, "event": kind, "phase": e.get("phase"), "detail": str(e.get("detail"))[:200],
                                "ts": e.get("ts")})
        elif kind == "action":
            if e.get("ok"):
                actions_ok += 1
            else:
                actions_fail += 1
            by_name.setdefault(e.get("name"), [0, 0])[0 if e.get("ok") else 1] += 1
            defects = (e.get("detail") or {}).get("defects") if isinstance(e.get("detail"), dict) else None
            if defects:
                portal_defects.append({"bot": bot, "defects": defects})
        elif kind == "ping" and e.get("ms") is not None:
            pings.append(e["ms"])
        elif kind == "death":
            deaths += 1
    first_ok = sum(1 for a in first_spawn.values() if a == 1)
    # incidents: each unexpected connection end once; errors/kicks are kept as details, not extra incidents
    incidents = [d for d in disconnects if d["event"] == "disconnect"]
    unintended = [d for d in incidents if d["phase"] not in EXPECTED_END_PHASES + JOIN_PHASES]
    join_failures = [d for d in disconnects if d["phase"] in JOIN_PHASES and d["event"] in ("disconnect", "error")]
    steady_errors = [d for d in disconnects if d["event"] == "error" and d["phase"] not in EXPECTED_END_PHASES + JOIN_PHASES]
    kicks = [d for d in disconnects if d["event"] == "kicked" and d["phase"] not in EXPECTED_END_PHASES]
    total_actions = actions_ok + actions_fail
    return {"expected": expected, "joinedAny": len(spawned_any), "joinedFirstAttempt": first_ok,
            "firstAttemptRate": round(first_ok / expected, 4) if expected else None,
            "afterRetryRate": round(len(spawned_any) / expected, 4) if expected else None,
            "joinMsP50": pct(joins, .5), "joinMsP95": pct(joins, .95), "joinMsMax": max(joins) if joins else None,
            "unintendedDisconnects": len(unintended), "disconnectSamples": unintended[:20],
            "joinFailureEvents": len(join_failures), "joinFailureSamples": join_failures[:20],
            "steadyErrors": len(steady_errors), "steadyErrorSamples": steady_errors[:20],
            "kicks": len(kicks), "kickSamples": kicks[:20], "analyzerVersion": ANALYZER_VERSION,
            "actionsOk": actions_ok, "actionsFailed": actions_fail,
            "actionSuccessRate": round(actions_ok / total_actions, 4) if total_actions else None,
            "actionsByName": {k: {"ok": v[0], "failed": v[1]} for k, v in by_name.items()},
            "portalDefects": portal_defects[:50], "portalDefectCount": len(portal_defects), "deaths": deaths,
            "pingMsP50": pct(pings, .5), "pingMsP95": pct(pings, .95),
            "pingNote": "server-reported keepalive latency from the tab list (includes server loop delay)"}


ENGINE_TRAPS = ("loadTraps", "writeTraps", "storageTraps")
# S9-E3: what replaced the traps (a tile pauses, the server thread does it); printed beside the traps
ENGINE_SERVICES = ("loadServices", "storageServices", "writeServices", "villageServices", "structureServices", "services",
                   "serviceUs")
# S9-E4: the tracker pre-filter (checks seen, checks skipped as unable to change anything); absent before D22
# S9-E6 (D24): the tracker index (move calls and moved-player passes it answered, trackers visited, visits avoided, full
# loops it left to vanilla, its self-checks and rebuilds, and its faults); absent before D24
ENGINE_TRACKER = ("trackerChecks", "trackerSkipped", "trackerIndexMoves", "trackerIndexPasses", "trackerIndexVisited",
                  "trackerIndexAvoided", "trackerIndexFallbacks", "trackerIndexChecks", "trackerIndexRebuilds",
                  "trackerIndexStale", "trackerReverseStale", "trackerIndexOffThread")
# S9-E7 (D25): the spawn-state memo (states built, entity chunk queries, memo hits, chunks looked up, mobs counted through
# remembered count maps, states rebuilt the vanilla way and compared, and differences found); absent before D25
ENGINE_MEMO = ("spawnMemoCalls", "spawnMemoQueries", "spawnMemoHits", "spawnMemoChunks", "spawnMemoFastMobs",
               "spawnMemoChecks", "spawnMemoStale")
# must stay 0 like the traps: an index that differed from the entity map, a tracker change off the server thread, or a
# spawn state that differed from the vanilla one (indexFaults keeps its name: the engine self-check faults)
ENGINE_INDEX_FAULTS = ("trackerIndexStale", "trackerReverseStale", "trackerIndexOffThread", "spawnMemoStale",
                       # S9-E8 (D26): protection memberships against their objects (stale tile/world, orphan, late)
                       "mobDecisionStale", "mobDecisionOrphans", "projectileStale", "projectileOrphans", "pearlStale",
                       "pearlOrphans", "projectileLateRelocations", "protectionOffThread",
                       # S9-E8b (D27): a pearl's owning player other than its membership's
                       "pearlOwnerStale",
                       # S9-E12/E13 (D31/D32): autosave spread overlap / off-thread, move split mismatch / stray changes
                       "autosaveOverlap", "autosaveOffThread", "moveSplitMismatch", "moveSplitStray", "moveSplitFailures",
                       # S9-E15 (D34): a spawn-memo check query outside its pinned chunk set; spread keys with no outcome
                       "spawnMemoPinMisses", "autosaveUnaccounted")
# S9-E8 (D26): server-thread section timers (EngineTimers, cumulative us) and the phase critical path / largest tile
ENGINE_TIMERS = ("Packets", "ServerTick", "Autosave", "Children", "Levels", "LevelTicks", "Raids", "ChunkSource", "Distance",
                 "TickChunks", "SpawnState", "Tracker", "ChunkUnload", "BlockEvents", "Entities", "BlockEntities",
                 "EntityManager", "Connections", "PlayerList", "ChunkSend",
                 # S9-E9 (D28): the tracker's chunk tracking, its collected sendChanges calls (with delivery), the moved pass
                 "TrackerChunks", "TrackerSend", "TrackerMoved",
                 # S9-E10 (D29): move packets (in Packets), ChunkMap.move(player), player doTick (in Connections),
                 # the changed chunks' broadcast (in TickChunks)
                 "MovePackets", "PlayerMove", "PlayerTicks", "ChunkBroadcast",
                 # S9-E12/E13 (D31/D32): the spread autosave slice, the move split (deferred pass, apply)
                 "AutosaveSpread", "MoveSplit", "MoveSplitApply")
# S9-E9 (D28): parallel-for work outside the phases, per frame (summed thread time in ms, or counts)
ENGINE_PARALLEL_FOR = {"trackerSendWorkUs": "trackerSendWorkMsPerFrame", "trackerSendDeliverUs": "trackerSendDeliverMsPerFrame",
                       "spawnSliceWorkUs": "spawnSliceWorkMsPerFrame",
                       # S9-E13 (D32): the move split's parallel work
                       "moveSplitWorkUs": "moveSplitWorkMsPerFrame"}
ENGINE_PARALLEL_FOR_COUNTS = ("trackerSendCalls", "trackerSendPackets", "trackerSendConnections", "trackerSendParallel",
                              "trackerSendSerial", "trackerSendInPlace", "spawnSliceStates", "spawnSlices", "parallelFors",
                              # S9-E12/E13 (D31/D32): move split and incremental autosave counters
                              "moveSplitDeferred", "moveSplitFlushes", "moveSplitParallel", "moveSplitSerial",
                              "moveSplitChanges", "moveSplitRechecked", "moveSplitChecks", "autosaveSpreads",
                              "autosaveChunkSaves", "autosaveEntityStores", "autosavePlayerSaves", "autosaveDrains", "autosaveMaxDrainUs",
                              # S9-E15 (D34): early flushes, key outcomes, deferred drains, spawn-memo smear (informational)
                              "moveSplitEarly", "autosaveChunkGone", "autosaveChunkIdle", "autosaveChunkNotReady",
                              "autosaveChunkClean", "autosaveChunkCooldown", "autosaveChunkNotWritten", "autosaveEntityPending",
                              "autosaveEntityUnloads", "autosavePlayerGone", "autosaveDeferredDrains", "spawnMemoSmeared",
                              "spawnMemoLiveDiffs")


def engine_window(run_dir: pathlib.Path, workers: int | None) -> dict | None:
    """The measure window's engine counters: the difference of the server log's two `PHOENIUM_ENGINE mark measureStart`
    / `measureEnd` lines (D26+), with per-frame section times (ms), the entity and chunk phases' wall per frame, tile
    utilisation (tile time over (phase - apply - serial) x threads), the critical path and the largest tile as shares of
    that parallel wall, and the window's traps and self-check faults. None when the marks are absent."""
    path = run_dir / "server.log"
    if not path.exists():
        return None
    marks: dict = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = re.search(r"PHOENIUM_ENGINE mark (measureStart|measureEnd) (.*)", line)
            if m:
                marks[m.group(1)] = {k: int(v) for k, v in re.findall(r"(\w+)=(-?\d+)\b(?![.\w])", m.group(2))}
    if "measureStart" not in marks or "measureEnd" not in marks:
        return None
    a, b = marks["measureStart"], marks["measureEnd"]
    d = {k: b[k] - a.get(k, 0) for k in b if isinstance(b[k], int)}
    frames = d.get("timeFrames") or 0
    out: dict = {"frames": frames}
    if frames > 0:
        for name in ENGINE_TIMERS:
            if f"time{name}Us" in d:
                out[f"t{name}MsPerFrame"] = round(d[f"time{name}Us"] / frames / 1000.0, 4)
    threads = (workers or 0) + 1
    for kind in ("entity", "chunk"):
        phase = d.get(f"{kind}PhaseUs")
        if phase is None:
            continue
        parallel = phase - d.get(f"{kind}ApplyUs", 0) - d.get(f"{kind}SerialUs", 0)
        if frames > 0:
            out[f"{kind}PhaseMsPerFrame"] = round(phase / frames / 1000.0, 4)
        if parallel > 0:
            out[f"{kind}TileUtil"] = round(d.get(f"{kind}TileUs", 0) / (parallel * threads), 4)
            if f"{kind}CriticalUs" in d:
                out[f"{kind}CriticalShare"] = round(d[f"{kind}CriticalUs"] / parallel, 4)
            if f"{kind}LargestUs" in d:
                out[f"{kind}LargestShare"] = round(d[f"{kind}LargestUs"] / parallel, 4)
    out["traps"] = sum(d.get(k, 0) for k in ENGINE_TRAPS)
    out["faults"] = sum(d.get(k, 0) for k in ENGINE_INDEX_FAULTS)
    if "spawnCapOvershoot" in d:   # S9-E8b (D27): merged spawns the shared per-player caps would already refuse (STATE-1 R2)
        out["spawnCapOvershoot"] = d["spawnCapOvershoot"]
    if frames > 0:   # S9-E9 (D28)
        for key, column in ENGINE_PARALLEL_FOR.items():
            if key in d:
                out[column] = round(d[key] / frames / 1000.0, 4)
        for key in ENGINE_PARALLEL_FOR_COUNTS:
            if key in d:
                out[f"{key}PerFrame"] = round(d[key] / frames, 3)
    return out


def engine_stop(line: str) -> dict | None:
    """Counters of a Phoenium engine-mode stop line (PHOENIUM_STAGE2 stopped ... mode=engine-tiles ...), else None.

    Every key=value pair after 'stopped' is kept (integers as int); 'traps' sums the load/write/storage trap counters,
    which must stay 0 (a trap is a tile access the engine could not keep inside its claim)."""
    if "mode=engine-tiles" not in line:
        return None
    tail = line.split("PHOENIUM_STAGE2 stopped", 1)[-1]
    out: dict = {}
    for key, value in re.findall(r"(\w+)=(\S+)", tail):
        out[key] = int(value) if re.fullmatch(r"-?\d+", value) else value
    out["traps"] = sum(int(out.get(k, 0) or 0) for k in ENGINE_TRAPS)
    gap = autosave_unaccounted(out)
    if gap is not None:
        out["autosaveUnaccounted"] = gap
    out["indexFaults"] = sum(int(out.get(k, 0) or 0) for k in ENGINE_INDEX_FAULTS)
    return out


# S9-E15 (D34): every recorded spread key ends with exactly one outcome or is still in the backlog
AUTOSAVE_OUTCOMES = ("autosaveChunkSaves", "autosaveChunkGone", "autosaveChunkIdle", "autosaveChunkNotReady", "autosaveChunkClean",
                     "autosaveChunkCooldown", "autosaveChunkNotWritten", "autosaveEntityStored", "autosaveEntityUnloads",
                     "autosaveEntityPending", "autosavePlayerSaves", "autosavePlayerGone")


def autosave_unaccounted(d: dict) -> int | None:
    """(chunk + entity + player keys) - (every key outcome) - the live backlog, for a D34+ line with no dropped queue (a
    full save drops keys without an outcome); 0 means every key is explained. None when not applicable."""
    if "autosaveChunkClean" not in d or "autosaveBacklog" not in d or int(d.get("autosaveDropped", 0) or 0) != 0:
        return None
    keys = sum(int(d.get(k, 0) or 0) for k in ("autosaveChunkKeys", "autosaveEntityKeys", "autosavePlayerKeys"))
    return keys - sum(int(d.get(k, 0) or 0) for k in AUTOSAVE_OUTCOMES) - int(d["autosaveBacklog"])


def verdicts(tier: int, ticks: dict, gc: dict, proc: dict, bots: dict, server_bad: list[str], crashed: bool,
             controller_cores_limit: float) -> dict:
    reasons_c, reasons_p = [], []
    if crashed:
        reasons_c.append("server crashed or exited unexpectedly")
    if server_bad:
        reasons_c.append(f"{len(server_bad)} bad log lines (mixin/ownership/OOM/watchdog patterns)")
    gate = JOIN_GATES["small"] if tier in JOIN_GATES["small"]["tiers"] else JOIN_GATES["large"]
    if bots["expected"]:
        if (bots["firstAttemptRate"] or 0) < gate["firstAttempt"]:
            reasons_c.append(f"first-attempt join {bots['firstAttemptRate']} < {gate['firstAttempt']}")
        if "afterRetry" in gate and (bots["afterRetryRate"] or 0) < gate["afterRetry"]:
            reasons_c.append(f"join after retry {bots['afterRetryRate']} < {gate['afterRetry']}")
        if bots["unintendedDisconnects"] > max(0, int(0.02 * tier)):
            reasons_c.append(f"{bots['unintendedDisconnects']} unintended disconnects (> 2% of {tier})")
        if bots["actionSuccessRate"] is not None and bots["actionSuccessRate"] < 0.99:
            reasons_c.append(f"scripted action success {bots['actionSuccessRate']} < 0.99")
        if bots.get("portalDefectCount"):
            reasons_c.append(f"{bots['portalDefectCount']} mounted-portal correctness defects (dup/lost/wrong-dimension/separated)")
    g = DESKTOP_GATES
    if ticks.get("ticks", 0) == 0:
        reasons_p.append("no tick data")
    else:
        if (ticks["tpsMedian10s"] or 0) < g["tpsMedianMin"]:
            reasons_p.append(f"TPS median {ticks['tpsMedian10s']} < {g['tpsMedianMin']}")
        if ticks["msptP50"] >= g["msptP50Max"]:
            reasons_p.append(f"MSPT p50 {ticks['msptP50']} >= {g['msptP50Max']}")
        if ticks["msptP95"] > g["msptP95Max"]:
            reasons_p.append(f"MSPT p95 {ticks['msptP95']} > {g['msptP95Max']}")
        if ticks["msptP99"] > g["msptP99Max"]:
            reasons_p.append(f"MSPT p99 {ticks['msptP99']} > {g['msptP99Max']}")
        if ticks["stallS"] >= g["stallMaxS"]:
            reasons_p.append(f"stall {ticks['stallS']} s >= {g['stallMaxS']}")
    if gc.get("available"):
        if gc["pauseShare"] is not None and gc["pauseShare"] >= g["gcPauseShareMax"]:
            reasons_p.append(f"GC pause share {gc['pauseShare']} >= {g['gcPauseShareMax']}")
        if (gc["pauseP99Ms"] or 0) > g["gcP99MaxMs"]:
            reasons_p.append(f"GC p99 {gc['pauseP99Ms']} ms > {g['gcP99MaxMs']}")
        if gc["pauseMaxMs"] >= g["gcMaxMs"]:
            reasons_p.append(f"GC max {gc['pauseMaxMs']} ms >= {g['gcMaxMs']}")
        if gc.get("liveSetGrowth") is not None and gc["liveSetGrowth"] > g["liveHeapGrowthMax"]:
            reasons_p.append(f"live-set growth {gc['liveSetGrowth']} > {g['liveHeapGrowthMax']}")
    harness = []
    proxy_cpu = (proc.get("proxy") or {}).get("coresP95")
    if proxy_cpu is not None and proxy_cpu >= controller_cores_limit:
        harness.append(f"ViaProxy near saturation ({proxy_cpu} cores p95)")
    bots_cpu = (proc.get("bots") or {}).get("coresP95")
    if bots_cpu is not None and bots_cpu >= controller_cores_limit:
        harness.append(f"bot controller near saturation ({bots_cpu} cores p95 >= {controller_cores_limit})")
    if bots.get("steadyErrors", 0) > max(0, int(0.02 * tier)):
        harness.append(f"{bots['steadyErrors']} bot errors outside join/teardown (> 2% of {tier}); see steadyErrorSamples")
    if (proc.get("hostCpuPctP95") or 0) >= 90:
        harness.append(f"host CPU p95 {proc['hostCpuPctP95']}% >= 90% (measurement contaminated)")
    return {"CORRECTNESS": "PASS" if not reasons_c else "FAIL", "correctnessReasons": reasons_c,
            "PERFORMANCE": "PASS" if not reasons_p else "FAIL", "performanceReasons": reasons_p,
            "HARNESS_HEALTH": "OK" if not harness else "SUSPECT", "harnessReasons": harness}
