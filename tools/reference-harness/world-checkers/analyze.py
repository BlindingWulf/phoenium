"""PhoneBench analysis: turns a run's raw files into per-phase metrics (summary.json). Pure parsing, stdlib only.
R2 adds worst rolling-10 s TPS, GC pause share, mixed/full-GC live set, join-stall metrics, evidenced crash causes and
classify() (the one verdict function for phone and desktop runs; see TARGETS).

Inputs in <run>/raw/: dev.txt (devsampler.sh), ticks.csv + meta.json + samples.jsonl (Stage 9 telemetry mod),
gc.log (if -Xlog was enabled), server.log (Phoenix evidence log slice), logcat.txt, manifest phases in <run>/manifest.json.
All times are converted to the phone's wall clock (epoch seconds).

Usage: python analyze.py <run_dir> [...]
"""
from __future__ import annotations

import json
import math
import re
import sys
from pathlib import Path

CLK_TCK = 100


def pct(values, q):
    if not values:
        return None
    s = sorted(values)
    i = max(0, min(len(s) - 1, math.ceil(q * len(s)) - 1))
    return s[i]


def r(v, n=2):
    return None if v is None else round(v, n)


# ------------------------------------------------------------------------------------------------ devsampler

def parse_dev(path: Path):
    """List of samples; each a dict with 'wall' (phone epoch s)."""
    wall0 = up0 = None
    samples, cur = [], None
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line:
            continue
        tag, _, rest = line.partition(" ")
        p = rest.split()
        if tag == "H":
            for kv in p:
                k, _, v = kv.partition("=")
                if k == "wall0":
                    wall0 = float(v)
                elif k == "up0":
                    up0 = float(v)
        elif tag == "S":
            cur = {"seq": int(p[0]), "up": float(p[1]), "cpu": {}}
            cur["wall"] = wall0 + (cur["up"] - up0) if wall0 is not None else None
            samples.append(cur)
        elif cur is None:
            continue
        elif tag == "J" and p and p[0] != "-":
            try:
                cur["jvm"] = {"pid": int(p[0]), "utime": int(p[1]), "stime": int(p[2]), "threads": int(p[3]),
                              "rssKb": int(p[4]) if len(p) > 4 and p[4].isdigit() else None,
                              "swapKb": int(p[5]) if len(p) > 5 and p[5].isdigit() else None,
                              "rssAnonKb": int(p[6]) if len(p) > 6 and p[6].isdigit() else None,
                              "rssFileKb": int(p[7]) if len(p) > 7 and p[7].isdigit() else None,
                              "cpus": p[8] if len(p) > 8 else None, "cpuset": p[9] if len(p) > 9 else None}
            except (ValueError, IndexError):
                pass
        elif tag == "I":
            io = dict(zip(p[0::2], p[1::2]))
            cur["io"] = {k.rstrip(":"): int(v) for k, v in io.items() if v.isdigit()}
        elif tag == "A" and len(p) >= 4:
            cur["app"] = {"pid": int(p[0]), "utime": int(p[1]), "stime": int(p[2]), "rssKb": int(p[3]) if p[3].isdigit() else None}
        elif tag == "C" and len(p) >= 8:
            v = list(map(int, p[1:8]))
            cur["cpu"][p[0]] = {"busy": v[0] + v[1] + v[2] + v[5] + v[6], "idle": v[3] + v[4]}
        elif tag == "Q":
            cur["freq"] = [int(x) for x in p if x.isdigit()]
        elif tag == "M" and len(p) >= 4:
            cur["mem"] = {"availKb": int(p[0]), "freeKb": int(p[1]), "swapFreeKb": int(p[2]), "cachedKb": int(p[3])}
        elif tag == "B" and len(p) >= 5:
            cur["batt"] = {"pct": int(p[0]), "tempC": int(p[1]) / 10, "currentUa": int(p[2]), "voltUv": int(p[3]), "status": p[4]}
        elif tag == "T":
            st = re.search(r"Thermal Status: (\d+)", rest)
            temps = {m.group(2): float(m.group(1)) for m in re.finditer(r"mValue=([\d.\-]+), mType=\d+, mName=(\w+)", rest)}
            cur["thermal"] = {"status": int(st.group(1)) if st else None, **temps}
        elif tag == "W":
            cur["awake"] = "Awake" in rest
        elif tag == "P":
            kv = re.findall(r"(\w+): (\d+)", rest)
            cur["pss"] = {k: int(v) for k, v in kv}
        elif tag in ("K", "X") and rest.count('"') >= 2:
            # R4 (devsampler level threads): K tid "name" utime stime cpu [nice run_ns wait_ns slices]; X pid "comm" utime stime cpu
            head, name, tail = rest.split('"', 2)
            t = tail.split()
            if len(t) < 3 or not head.strip():
                continue
            num = [int(x) if x.lstrip("-").isdigit() else None for x in t]
            ticks = (num[0] or 0) + (num[1] or 0)
            if tag == "K":
                cur.setdefault("threads", {})[head.strip()] = {
                    "name": name, "ticks": ticks, "cpu": num[2], "nice": num[3] if len(num) > 3 else None,
                    "runNs": num[4] if len(num) > 4 else None, "waitNs": num[5] if len(num) > 5 else None}
            else:
                cur.setdefault("procs", {})[head.strip()] = {"name": name, "ticks": ticks, "cpu": num[2]}
        elif tag == "KC" and len(p) >= 2:
            cur["serverCpus"] = p[1]
    return samples


BIG_CORES = {6, 7}           # MT6835: cpu6-7 Cortex-A76, cpu0-5 Cortex-A55; screen off leaves the JVM cpus 0-6


def thread_group(name: str) -> str:
    """'c2me-worker-3' -> 'c2me-worker', 'Phoenium Tile 1' -> 'Phoenium Tile', 'GC Thread#2' -> 'GC Thread'."""
    return re.sub(r"[\s#\-]*\d*[\s#\-]*$", "", name or "?") or (name or "?")


def thread_window(samples, t0, t1, big=BIG_CORES):
    """R4: per-thread CPU and run-queue wait from K records in [t0, t1] (first JVM only; a restarted JVM ends the window).
    cpuPct = % of one core; runqWaitPct = % of wall time runnable but not running (schedstat); perCore attributes each
    interval's CPU to the core the thread was last seen on (an approximation: only the last CPU is recorded)."""
    w = [s for s in samples if s.get("wall") is not None and t0 <= s["wall"] <= t1 and s.get("threads")]
    if w:
        pid0 = (w[0].get("jvm") or {}).get("pid")
        for i, s in enumerate(w):
            if (s.get("jvm") or {}).get("pid") != pid0:
                w = w[:i]
                break
    if len(w) < 2:
        return None
    dt = w[-1]["wall"] - w[0]["wall"]
    if dt <= 0:
        return None
    grp, per_core = {}, {}
    srv = {"cpu": 0.0, "wait": 0.0, "waitKnown": False, "iv": 0, "big": 0}
    for a, b in zip(w, w[1:]):
        for tid, tb in b["threads"].items():
            ta = a["threads"].get(tid)
            if not ta or ta["name"] != tb["name"]:
                continue
            cpu_s = max(0, tb["ticks"] - ta["ticks"]) / CLK_TCK
            wait_s = (tb["waitNs"] - ta["waitNs"]) / 1e9 if tb.get("waitNs") is not None and ta.get("waitNs") is not None else None
            g = grp.setdefault(thread_group(tb["name"]), {"cpu": 0.0, "wait": 0.0, "tids": set()})
            g["cpu"] += cpu_s
            g["wait"] += max(0.0, wait_s or 0.0)
            g["tids"].add(tid)
            if tb.get("cpu") is not None:
                c = per_core.setdefault(str(tb["cpu"]), {})
                c[thread_group(tb["name"])] = c.get(thread_group(tb["name"]), 0.0) + cpu_s
            if tb["name"] == "Server thread":
                srv["cpu"] += cpu_s
                srv["iv"] += 1
                srv["big"] += 1 if tb.get("cpu") in big else 0
                if wait_s is not None:
                    srv["wait"] += max(0.0, wait_s)
                    srv["waitKnown"] = True
    pc = lambda x: r(100 * x / dt, 1)  # noqa: E731
    groups = sorted(({"group": k, "cpuPct": pc(v["cpu"]), "runqWaitPct": pc(v["wait"]), "threads": len(v["tids"])}
                     for k, v in grp.items()), key=lambda g: -g["cpuPct"])
    procs = []
    xs = [s for s in w if s.get("procs")]
    xs = xs or [s for s in samples if s.get("wall") is not None and t0 <= s["wall"] <= t1 and s.get("procs")]
    if len(xs) >= 2 and xs[-1]["wall"] > xs[0]["wall"]:
        xdt = xs[-1]["wall"] - xs[0]["wall"]
        jpid = str(pid0) if pid0 is not None else None
        for pid, pb_ in xs[-1]["procs"].items():
            pa = xs[0]["procs"].get(pid)
            if pa and pa["name"] == pb_["name"] and pid != jpid and pb_["ticks"] > pa["ticks"]:
                procs.append({"pid": pid, "name": pb_["name"], "cpuPct": r(100 * (pb_["ticks"] - pa["ticks"]) / CLK_TCK / xdt, 1),
                              "lastCpu": pb_["cpu"]})
        procs.sort(key=lambda p: -p["cpuPct"])
    fq = [s["freq"] for s in w if len(s.get("freq") or []) >= 8]
    big_mhz = r(sum(max(f[6], f[7]) for f in fq) / len(fq) / 1000, 0) if fq else None
    little_mhz = r(sum(f[0] for f in fq) / len(fq) / 1000, 0) if fq else None
    return {"samples": len(w), "seconds": r(dt, 1), "bigFreqMhzAvg": big_mhz, "littleFreqMhzAvg": little_mhz,
            "server": {"cpuPct": pc(srv["cpu"]), "runqWaitPct": pc(srv["wait"]) if srv["waitKnown"] else None,
                       "onBigPct": round(100 * srv["big"] / srv["iv"]) if srv["iv"] else None,
                       "cpusAllowed": sorted({s["serverCpus"] for s in w if s.get("serverCpus")})},
            "schedstat": srv["waitKnown"],
            "groups": groups[:15],
            "perCore": {c: sorted(([k, pc(v)] for k, v in d.items() if pc(v) >= 0.5), key=lambda x: -x[1])[:6]
                        for c, d in sorted(per_core.items())},
            "procs": procs[:10]}


def dev_window(samples, t0, t1):
    w = [s for s in samples if s.get("wall") is not None and t0 <= s["wall"] <= t1]
    if len(w) < 2:
        return {"samples": len(w)}
    a, b = w[0], w[-1]
    dt = b["wall"] - a["wall"]
    out = {"samples": len(w), "seconds": r(dt, 1)}
    js = [s["jvm"] for s in w if "jvm" in s]
    if len(js) >= 2 and js[0]["pid"] == js[-1]["pid"]:
        cpu_s = (js[-1]["utime"] + js[-1]["stime"] - js[0]["utime"] - js[0]["stime"]) / CLK_TCK
        out["jvmCpuPctOneCore"] = r(100 * cpu_s / dt, 1)
        rss = [j["rssKb"] / 1024 for j in js if j.get("rssKb")]
        out["jvmRssMb"] = {"avg": r(sum(rss) / len(rss), 0), "max": r(max(rss), 0), "min": r(min(rss), 0)} if rss else None
        sw = [j["swapKb"] / 1024 for j in js if j.get("swapKb") is not None]
        out["jvmSwapMbMax"] = r(max(sw), 0) if sw else None
        out["jvmThreadsMax"] = max(j["threads"] for j in js)
        out["jvmCpusAllowed"] = sorted({j.get("cpus") for j in js if j.get("cpus")})
        out["jvmCpuset"] = sorted({j.get("cpuset") for j in js if j.get("cpuset")})
    ios = [s["io"] for s in w if s.get("io")]
    if len(ios) >= 2:
        out["jvmIoMb"] = {k: r((ios[-1].get(k, 0) - ios[0].get(k, 0)) / 1048576, 1) for k in ("read_bytes", "write_bytes", "rchar", "wchar")}
        out["jvmIoMbPerS"] = {k: r(v / dt, 2) for k, v in out["jvmIoMb"].items() if v is not None}
    aps = [s["app"] for s in w if "app" in s]
    if len(aps) >= 2 and aps[0]["pid"] == aps[-1]["pid"]:
        out["appCpuPctOneCore"] = r(100 * (aps[-1]["utime"] + aps[-1]["stime"] - aps[0]["utime"] - aps[0]["stime"]) / CLK_TCK / dt, 1)
        out["appRssMbMax"] = r(max(x["rssKb"] for x in aps if x["rssKb"]) / 1024, 0)
    if a["cpu"] and b["cpu"]:
        util = {}
        for c in b["cpu"]:
            if c in a["cpu"]:
                busy = b["cpu"][c]["busy"] - a["cpu"][c]["busy"]
                tot = busy + b["cpu"][c]["idle"] - a["cpu"][c]["idle"]
                util[c] = r(100 * busy / tot, 1) if tot > 0 else None
        out["cpuUtilPct"] = util
    fq = [s["freq"] for s in w if s.get("freq")]
    if fq:
        little = [f[0] / 1000 for f in fq]
        big = [max(f[6], f[7]) / 1000 for f in fq if len(f) >= 8]
        out["freqMhz"] = {"littleAvg": r(sum(little) / len(little), 0), "littleMax": max(little),
                          "bigAvg": r(sum(big) / len(big), 0) if big else None, "bigMax": max(big) if big else None}
    mem = [s["mem"] for s in w if s.get("mem")]
    if mem:
        out["memAvailMb"] = {"min": r(min(m["availKb"] for m in mem) / 1024, 0), "avg": r(sum(m["availKb"] for m in mem) / len(mem) / 1024, 0)}
        out["swapUsedDeltaMb"] = r((mem[0]["swapFreeKb"] - mem[-1]["swapFreeKb"]) / 1024, 0)
    bt = [s["batt"] for s in w if s.get("batt")]
    if bt:
        out["battery"] = {"startPct": bt[0]["pct"], "endPct": bt[-1]["pct"], "tempMaxC": max(x["tempC"] for x in bt),
                          "tempStartC": bt[0]["tempC"], "status": sorted({x["status"] for x in bt})}
    th = [s["thermal"] for s in w if s.get("thermal")]
    if th:
        out["thermal"] = {"statusMax": max((x.get("status") or 0) for x in th),
                          "cpuMaxC": max((x.get("CPU") or 0) for x in th), "skinMaxC": max((x.get("SKIN") or 0) for x in th)}
    ps = [s["pss"] for s in w if s.get("pss")]
    if ps:
        out["jvmPssMb"] = {"avg": r(sum(p.get("Pss", 0) for p in ps) / len(ps) / 1024, 0), "max": r(max(p.get("Pss", 0) for p in ps) / 1024, 0),
                           "swapPssMaxMb": r(max(p.get("SwapPss", 0) for p in ps) / 1024, 0)}
    aw = [s["awake"] for s in w if "awake" in s]
    if aw:
        out["screenAwake"] = sorted(set(aw))
    return out


# ------------------------------------------------------------------------------------------------ telemetry mod

def load_ticks(raw: Path):
    meta = json.loads((raw / "meta.json").read_text()) if (raw / "meta.json").exists() else None
    if not meta or not (raw / "ticks.csv").exists():
        return None
    w0, n0 = meta["startWallMs"] / 1000, meta["startNano"]
    ticks = []
    with open(raw / "ticks.csv", encoding="utf-8") as f:
        next(f, None)
        for line in f:
            p = line.rstrip().split(",")
            if len(p) >= 3:
                try:
                    ticks.append((w0 + (int(p[1]) - n0) / 1e9, int(p[2]) / 1e6))
                except ValueError:
                    pass
    return ticks


def tick_window(ticks, t0, t1):
    if not ticks:
        return None
    w = [(t, d) for t, d in ticks if t0 <= t <= t1]
    if len(w) < 2:
        return {"ticks": len(w)}
    d = [x[1] for x in w]
    starts = [x[0] for x in w]
    gaps = [(b - a) * 1000 for a, b in zip(starts, starts[1:])]
    span = t1 - t0
    return {"ticks": len(w), "tps": r(len(w) / span, 2), "msptMean": r(sum(d) / len(d)), "msptP50": r(pct(d, .5)),
            "msptP95": r(pct(d, .95)), "msptP99": r(pct(d, .99)), "msptMax": r(max(d)),
            "over50msPct": r(100 * sum(1 for x in d if x > 50) / len(d), 2),
            "tickGapMaxMs": r(max(gaps), 1), "gapsOver1s": sum(1 for g in gaps if g > 1000)}


def telemetry_window(raw: Path, t0, t1):
    f = raw / "samples.jsonl"
    if not f.exists():
        return None
    rows = []
    for line in f.read_text(encoding="utf-8").splitlines():
        try:
            j = json.loads(line)
        except ValueError:
            continue
        if t0 <= j["wallMs"] / 1000 <= t1:
            rows.append(j)
    if not rows:
        return None
    mb = 1048576
    return {"playersMax": max(x["players"] for x in rows), "playersMin": min(x["players"] for x in rows),
            "loadedChunksAvg": r(sum(x["loadedChunks"] for x in rows) / len(rows), 0),
            "loadedChunksMax": max(x["loadedChunks"] for x in rows),
            "heapUsedMb": {"avg": r(sum(x["heapUsed"] for x in rows) / len(rows) / mb, 0), "max": r(max(x["heapUsed"] for x in rows) / mb, 0),
                           "min": r(min(x["heapUsed"] for x in rows) / mb, 0)},
            "heapCommittedMbMax": r(max(x["heapCommitted"] for x in rows) / mb, 0)}


# ------------------------------------------------------------------------------------------------ GC

GC_LINE = re.compile(r"\[(\d{4}-\d\d-\d\dT[\d:.]+)([+-]\d{4})\].*?GC\((\d+)\) (Pause \w+(?: \([^)]*\))*) (\d+)M->(\d+)M\((\d+)M\) ([\d.]+)ms")


def parse_gc(path: Path):
    import datetime as dt
    out = []
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = GC_LINE.search(line)
        if m:
            ts = dt.datetime.strptime(m.group(1) + m.group(2), "%Y-%m-%dT%H:%M:%S.%f%z").timestamp()
            out.append({"t": ts, "kind": m.group(4).strip(), "beforeMb": int(m.group(5)), "afterMb": int(m.group(6)),
                        "heapMb": int(m.group(7)), "ms": float(m.group(8))})
    return out


def gc_window(gcs, t0, t1):
    w = [g for g in gcs if t0 <= g["t"] <= t1]
    if not w:
        return {"pauses": 0}
    full = [g for g in w if "Full" in g["kind"]]
    young = [g for g in w if "Young" in g["kind"]]
    return {"pauses": len(w), "perMin": r(len(w) / ((t1 - t0) / 60), 2), "totalMs": r(sum(g["ms"] for g in w), 1),
            "maxMs": r(max(g["ms"] for g in w), 1), "p95Ms": r(pct([g["ms"] for g in w], .95), 1),
            "full": len(full), "young": len(young),
            "liveAfterGcMb": {"min": min(g["afterMb"] for g in w), "max": max(g["afterMb"] for g in w),
                              "last": w[-1]["afterMb"]}, "heapCommittedMbMax": max(g["heapMb"] for g in w)}


def metrics_gc_window(server_log: Path, t0, t1):
    """Fallback from Phoenix's metrics agent (@@PHOENIX_METRICS every 2 s): GC count/time deltas, upper-bound max pause."""
    if not server_log.exists():
        return None
    rows = []
    for line in server_log.read_text(encoding="utf-8", errors="replace").splitlines():
        if "@@PHOENIX_METRICS" not in line:
            continue
        try:
            j = json.loads(line[line.index("{"):])
        except ValueError:
            continue
        if t0 <= j["timestampMs"] / 1000 <= t1:
            rows.append(j)
    if len(rows) < 2:
        return None
    steps = [b["gcCollectionTimeMs"] - a["gcCollectionTimeMs"] for a, b in zip(rows, rows[1:])]
    return {"collections": rows[-1]["gcCollections"] - rows[0]["gcCollections"],
            "timeMs": rows[-1]["gcCollectionTimeMs"] - rows[0]["gcCollectionTimeMs"],
            "max2sWindowMs": max(steps) if steps else 0,
            "heapUsedMbMax": r(max(x["jvmHeapUsedBytes"] for x in rows) / 1048576, 0),
            "heapCommittedMbMax": r(max(x["jvmHeapCommittedBytes"] for x in rows) / 1048576, 0)}


# ------------------------------------------------------------------------------------------------ logs

BAD = re.compile(r"(OutOfMemoryError|Exception in server tick loop|watchdog|A single server tick took|Crash report|"
                 r"FATAL|Considering it to be crashed|java\.lang\.\w+Error|Can't keep up)", re.I)


def log_window(server_log: Path, t0, t1):
    if not server_log.exists():
        return None
    counts, examples = {}, []
    s9t = []
    for line in server_log.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.match(r"(\d{13}) ", line)
        if not m:
            continue
        t = int(m.group(1)) / 1000
        if not t0 <= t <= t1 or "@@PHOENIX_METRICS" in line:
            continue
        b = BAD.search(line)
        if b:
            k = b.group(1).lower()
            counts[k] = counts.get(k, 0) + 1
            if len(examples) < 8:
                examples.append(line[14:240])
        if " S9T " in line:
            s9t.append(line[14:])
    return {"badLines": counts, "examples": examples, "s9tLines": len(s9t)}


def logcat_events(path: Path, pkg: str, t0, t1):
    if not path.exists():
        return None
    ev = {"am_kill": 0, "lowmemorykiller": 0, "am_proc_died": 0, "am_anr": 0, "am_crash": 0, "am_freeze": 0, "fatal signal": 0}
    lines = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.match(r"\s*(\d+\.\d+)", line)
        if not m or not t0 <= float(m.group(1)) <= t1:
            continue
        low = line.lower()
        hit = False
        for k in ev:
            if k in low and (pkg in line or k == "lowmemorykiller" or (k == "fatal signal" and "phoenixjvm" in low)):
                ev[k] += 1
                hit = True
        if hit and len(lines) < 20:
            lines.append(line[:220])
    return {"events": ev, "examples": lines}


# ------------------------------------------------------------------------------------------------ R2 metrics + verdict
# R2 (2026-10-14). Everything below is shared by the phone (run.py) and the desktop validator (tests/desktop/pbdesk.py).
# A run is a MEASUREMENT only when classify() says valid; every other outcome keeps its evidence but no performance row.

# R2 targets, fixed before the R2 data exists (TEST_PLAN.md "Success criteria"); never tuned to make a cell pass.
TARGETS = {"tpsAvgMin": 19.0, "tpsWorst10sMin": 15.0, "msptP95Max": 50.0, "msptP99Max": 100.0,
           "actionOkPctMin": 98.0,              # same bar as the Stage 9 desktop correctness gate
           "joinMaxTickGapMsMax": 10000.0,      # a join may not freeze the server for more than 10 s
           "gcPauseSharePctMax": 5.0}
TRIVIAL_ACTIONS = ("chat", "hit-animal", "attack", "shoot")    # return true unconditionally in phone_bots.js: not evidence


def rolling_tps_min(ticks, t0, t1, width=10.0, step=1.0):
    """Worst TPS over any width-second window fully inside [t0, t1] (ticks counted by start time)."""
    if not ticks or t1 - t0 < width:
        return None
    starts = sorted(t for t, _ in ticks if t0 <= t <= t1)
    import bisect
    worst, s = None, t0
    while s + width <= t1 + 1e-9:
        n = bisect.bisect_left(starts, s + width) - bisect.bisect_left(starts, s)
        worst = n / width if worst is None else min(worst, n / width)
        s += step
    return r(worst, 2)


def recovery_seconds(ticks, t0, t1, tps=15.0, width=10.0):
    """End of the join stall: seconds from t0 to the end of the LAST width-second window in [t0, t1] below `tps`
    (0 = never below). With t0 = first bot join this is how long joining players degraded the server."""
    if not ticks or t1 - t0 < width:
        return None
    import bisect
    starts = sorted(t for t, _ in ticks if t0 <= t <= t1)
    last_bad, s = None, t0
    while s + width <= t1 + 1e-9:
        n = bisect.bisect_left(starts, s + width) - bisect.bisect_left(starts, s)
        if n / width < tps:
            last_bad = s + width
        s += 1.0
    return 0.0 if last_bad is None else r(last_bad - t0, 1)


TICK_S = 0.05


def lag_replay(starts, max_tick_s=60.0, end=None, seed=None):
    """R4: replays vanilla 26.2's schedule over tick start times (MinecraftServer.runServer + ServerWatchdog, checked
    against the 26.2 sources). lag = how far the server is behind nextTickTime. A catch-up ("Can't keep up!") skips
    floor(lag / 50 ms) ticks when lag > 1 s + 20 ticks AND nextTickTime - lastWarning >= 10 s + 100 ticks (at most one
    per 300 executed ticks); the watchdog kills the server when lag > max-tick-time (also checked at `end`, for a server
    killed inside its last tick). seed = the logged start-up catch-up (time, lag s, ticks): it sets nextTickTime and the
    cooldown phase, which decide when the later catch-ups may fire. Returns peakLagS/peakAt, catchUps [(time, ticks)],
    deathAt (predicted kill time, None = survives). A model: lag_track() is the measured lag when the log has catch-ups."""
    if len(starts) < 2:
        return {"peakLagS": None, "peakAt": None, "catchUps": [], "deathAt": None}
    if seed:
        nxt = seed[0] - seed[1] + seed[2] * TICK_S
        last_warn = nxt
    else:
        nxt, last_warn = starts[0], float("-inf")
    peak, peak_at, died, ups = 0.0, None, None, []
    for i, s in enumerate(starts):
        behind = s - nxt
        if behind > 1.0 + 20 * TICK_S and nxt - last_warn >= 10.0 + 100 * TICK_S:
            skip = int(behind / TICK_S)
            nxt += skip * TICK_S
            last_warn = nxt
            ups.append((s, skip))
        nxt += TICK_S
        nt = starts[i + 1] if i + 1 < len(starts) else end
        if nt is not None:
            d = nt - nxt
            if d > peak:
                peak, peak_at = d, nt
            if d > max_tick_s and died is None:
                died = nxt + max_tick_s
    return {"peakLagS": r(peak, 2), "peakAt": peak_at, "catchUps": ups, "deathAt": died}


def lag_track(starts, logged, max_tick_s=60.0, end=None):
    """R4: the measured lag. Vanilla logs every catch-up with its exact lag ("Running <ms>ms or <n> ticks behind"), so
    nextTickTime right after it is (log time - lag + n x 50 ms); between catch-ups it advances 50 ms per executed tick.
    Lag is evaluated at every tick start and at `end`. None without a logged catch-up (lag before the first is unknown)."""
    if not logged or len(starts) < 2:
        return None
    logged = sorted(logged)
    nxt, k, peak, peak_at, died = None, 0, 0.0, None, None
    for i, s in enumerate(starts):
        while k < len(logged) and logged[k][0] <= s + TICK_S:      # the check runs just before this tick starts
            t, ms, n = logged[k]
            nxt = t - ms / 1000 + n * TICK_S
            if ms / 1000 > peak:                                    # the logged lag is itself a measurement
                peak, peak_at = ms / 1000, t
            k += 1
        if nxt is None:
            continue
        nxt += TICK_S
        nt = starts[i + 1] if i + 1 < len(starts) else end
        if nt is not None:
            d = nt - nxt
            if d > peak:
                peak, peak_at = d, nt
            if d > max_tick_s and died is None:
                died = nxt + max_tick_s
    return {"peakLagS": r(peak, 2), "peakAt": peak_at, "deathAt": died, "anchors": k}


CANT_KEEP_UP = re.compile(r"^(\d{13}) .*Can't keep up! Is the server overloaded\? Running (\d+)ms or (\d+) ticks behind")


def cant_keep_up(server_log: Path, t0=0.0, t1=1e18):
    """R4: vanilla's own catch-up lines [(time, ms behind, ticks skipped)] in [t0, t1] (ground truth for lag_replay)."""
    if not server_log.exists():
        return []
    out = []
    for line in server_log.read_text(encoding="utf-8", errors="replace").splitlines():
        m = CANT_KEEP_UP.match(line)
        if m and t0 <= int(m.group(1)) / 1000 <= t1:
            out.append((int(m.group(1)) / 1000, int(m.group(2)), int(m.group(3))))
    return out


def watchdog_time(server_log: Path, t0=0.0):
    """R4: time of the first "A single server tick took" line at or after t0 (the moment the watchdog declared the server
    dead; Phoenix's restart line comes seconds later, after the crash report)."""
    if not server_log.exists():
        return None
    for line in server_log.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.match(r"(\d{13}) ", line)
        if m and "A single server tick took" in line and int(m.group(1)) / 1000 >= t0:
            return int(m.group(1)) / 1000
    return None


THREAD_HEAD = re.compile(r'^\d{13} "(.+?)" (?:daemon )?prio=\d+ Id=\d+ (\w+)')


def watchdog_dump(server_log: Path, frames=12):
    """R4: the thread dump the watchdog prints with its crash report: the Server thread's state and top frames (what it
    was doing when it was declared dead) and every thread group's states. None without a watchdog line."""
    if not server_log.exists():
        return None
    lines = server_log.read_text(encoding="utf-8", errors="replace").splitlines()
    start = next((i for i, l in enumerate(lines) if "A single server tick took" in l), None)
    if start is None:
        return None
    states, server, cur = {}, None, None
    for line in lines[start:start + 6000]:
        if "This crash report has been saved to" in line:
            break
        m = THREAD_HEAD.match(line)
        if m:
            name, st = m.group(1), m.group(2)
            g = states.setdefault(thread_group(name), {})
            g[st] = g.get(st, 0) + 1
            cur = {"state": st, "frames": []} if name == "Server thread" and server is None else None
            if cur is not None:
                server = cur
            continue
        body = line[14:] if re.match(r"\d{13} ", line) else line
        if cur is not None and body.startswith("\tat ") and len(cur["frames"]) < frames:
            cur["frames"].append(body[4:].replace("knot//", ""))
        elif not body.strip():
            cur = None
    return {"serverThread": server, "states": states} if states else None


def screen_state(samples, screen_off_at, ref_t, end_t):
    """R4: when the screen-off CPU configuration actually reached the server JVM. restrictedAt = first sample after the
    screen-off request whose Cpus_allowed_list is not 0-7; restrictedBeforeJoinS = ref_t (first join, or warmup start
    without bots) minus restrictedAt; fullCoreSamplesAfterSwitch = samples back on all 8 cores before end_t."""
    if screen_off_at is None:
        return None
    js = [(s["wall"], s["jvm"]["cpus"]) for s in samples
          if s.get("wall") is not None and (s.get("jvm") or {}).get("cpus") and s["wall"] <= end_t]
    restricted = next((t for t, c in js if t >= screen_off_at and c != "0-7"), None)
    after = [c for t, c in js if restricted is not None and t >= restricted]
    return {"screenOffAt": screen_off_at, "refAt": ref_t, "restrictedAt": restricted,
            "restrictedAfterS": r(restricted - screen_off_at, 1) if restricted is not None else None,
            "restrictedBeforeJoinS": r(ref_t - restricted, 1) if restricted is not None and ref_t is not None else None,
            "fullCoreSamplesAfterSwitch": sum(1 for c in after if c == "0-7"), "cpusAfterSwitch": sorted(set(after))}


def lag_summary(ticks, j0, t_end, max_tick_s, server_log: Path, source: str):
    """R4: peak accumulated lag (log-anchored when vanilla logged catch-ups, else the replay), the watchdog margin and
    the time the lag crossed max-tick-time, catch-ups (replay, seeded with the logged start-up catch-up) next to the
    logged ones, from the JVM's first tick to t_end, times relative to the first join (j0); executed ticks per 10 s from j0."""
    st = [t for t, _ in ticks if t <= t_end]
    logged = cant_keep_up(server_log, st[0] - 30, t_end) if st else []
    seed = logged[0][:1] + (logged[0][1] / 1000, logged[0][2]) if logged and st and abs(logged[0][0] - st[0]) < 5 else None
    rp = lag_replay(st, max_tick_s, end=t_end, seed=seed)
    trk = lag_track(st, logged, max_tick_s, end=t_end)
    prim = trk or rp
    rel = lambda t: r(t - j0, 1) if t is not None else None  # noqa: E731
    bins = []
    t = j0
    while t + 10 <= t_end + 1e-9 and len(bins) < 36:
        bins.append(sum(1 for x in st if t <= x < t + 10))
        t += 10
    return {"source": source, "maxTickS": max_tick_s, "peakSource": "log-anchored" if trk else "replay",
            "peakLagS": prim["peakLagS"], "peakAtSFromJoin": rel(prim["peakAt"]),
            "watchdogMarginS": r(max_tick_s - prim["peakLagS"], 2) if prim["peakLagS"] is not None else None,
            "predictedDeathSFromJoin": rel(prim["deathAt"]),
            "replayPeakLagS": rp["peakLagS"], "replayDeathSFromJoin": rel(rp["deathAt"]), "replaySeeded": seed is not None,
            "catchUps": [{"sFromJoin": rel(t), "ticks": n} for t, n in rp["catchUps"]],
            "logCatchUps": [{"sFromJoin": rel(t), "ms": ms, "ticks": n} for t, ms, n in logged],
            "tEndSFromJoin": rel(t_end), "ticksPer10sFromJoin": bins}


def extra_window(ticks, gcs, t0, t1):
    out = {}
    w = [(t, d) for t, d in (ticks or []) if t0 <= t <= t1]
    if len(w) >= 2:
        out["tpsWorst10s"] = rolling_tps_min(ticks, t0, t1)
        out["lagBehindS"] = r(max(0.0, (t1 - t0) - len(w) * 0.05), 1)     # how far the server fell behind schedule
    win = [g for g in (gcs or []) if t0 <= g["t"] <= t1]
    if win:
        out["gcPauseSharePct"] = r(100 * sum(g["ms"] for g in win) / ((t1 - t0) * 1000), 2)
        mf = [g for g in win if "Full" in g["kind"] or "Mixed" in g["kind"]]
        # occupancy after Young pauses still contains uncollected old-gen garbage; mixed/full pauses are the live-set proxy
        out["liveAfterMixedOrFullMb"] = {"min": min(g["afterMb"] for g in mf), "max": max(g["afterMb"] for g in mf),
                                         "last": mf[-1]["afterMb"], "count": len(mf)} if mf else None
        out["fullGcs"] = sum(1 for g in win if "Full" in g["kind"])
    return out


RESTART = re.compile(r"\[Phoenix\] The server stopped unexpectedly \((?:it )?(?:crashed with )?exit code (-?\d+)\)|"
                     r"\[Phoenix\] The server stopped unexpectedly(.*)")
CAUSES = (("OOM", re.compile(r"OutOfMemoryError")), ("WATCHDOG", re.compile(r"A single server tick took|Watchdog \(Watching Server\)|ServerWatchdog\.createWatchdogCrashReport")),
          ("NATIVE", re.compile(r"Fatal signal|SIGSEGV|SIGBUS|SIGABRT|hs_err_pid|A fatal error has been detected")))


def crashes(server_log: Path, logcat: Path | None = None, t0=0.0, t1=1e18):
    """Every unexpected server end in [t0, t1] with its cause from the evidence before it (never assumed)."""
    if not server_log.exists():
        return []
    lines = []
    for line in server_log.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.match(r"(\d{13}) ", line)
        if m:
            lines.append((int(m.group(1)) / 1000, line[14:]))
    lc = logcat.read_text(encoding="utf-8", errors="replace").splitlines() if logcat and logcat.exists() else []
    out = []
    for i, (t, text) in enumerate(lines):
        if not t0 <= t <= t1:
            continue
        m = RESTART.search(text)
        if not m:
            continue
        code = int(m.group(1)) if m.group(1) else None
        before = [x for tt, x in lines[max(0, i - 4000):i] if t - 180 <= tt <= t]
        cause = next((c for c, rx in CAUSES if any(rx.search(x) for x in before)), None)
        if cause is None:
            near = [x for x in lc if (lambda mm: mm and t - 60 <= float(mm.group(1)) <= t + 5)(re.match(r"\s*(\d+\.\d+)", x))]
            if any(re.search(r"Fatal signal|tombstone", x) and "phoenixjvm" in x.lower() for x in near):
                cause = "NATIVE"
            elif code == 137 or any(re.search(r"lowmemorykiller|am_kill", x) and "dev.phoenix" in x for x in near):
                cause = "ANDROID_KILL"
            else:
                cause = "OTHER"
        out.append({"t": t, "exitCode": code, "cause": cause, "line": text[:200],
                    "evidence": [x[:200] for x in before if any(rx.search(x) for _, rx in CAUSES)][:3]})
    return out


def first_line_time(server_log: Path):
    if not server_log.exists():
        return None
    for line in server_log.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.match(r"(\d{13}) ", line)
        if m:
            return int(m.group(1)) / 1000
    return None


def action_success(bots_window: dict | None):
    """Non-trivial action success in one bot window: ok/(ok+fail) over actions that can actually fail."""
    if not bots_window:
        return None
    ok = fail = 0
    per = {}
    for name, v in (bots_window.get("actions") or {}).items():
        if name in TRIVIAL_ACTIONS:
            continue
        ok += v["ok"]
        fail += v["fail"]
        per[name] = v["okPct"]
    return {"okPct": r(100 * ok / (ok + fail), 2) if ok + fail else None, "attempts": ok + fail, "perAction": per}


def classify(rec: dict) -> dict:
    """rec keys (phone and desktop fill the same ones):
       tier, configProblems[list], harnessProblems[list], crashes[list of crashes()], fatalLines[list], joined, joinedAlive,
       playersMinMeasure, stopClean, measure{ticks, gc, extras}, join{ticks, extras}, botsMeasure (summarize_bots window),
       botsJoinWarmupDisconnects"""
    reasons = []
    tier = rec.get("tier") or 0
    cls = None
    if rec.get("configProblems"):
        cls = "CONFIG"; reasons += rec["configProblems"]
    elif rec.get("harnessProblems"):
        cls = "HARNESS"; reasons += rec["harnessProblems"]
    else:
        cr = rec.get("crashes") or []
        causes = [c["cause"] for c in cr]
        fl = " ".join(rec.get("fatalLines") or [])
        # R5: INJECTED = a crash the harness caused on purpose (run.py --fault-kill-after, smoke phase only); real evidence
        # (OOM / NATIVE / ANDROID_KILL / WATCHDOG) is checked first and wins
        for c in ("OOM", "NATIVE", "ANDROID_KILL", "WATCHDOG", "INJECTED", "OTHER"):
            if c in causes or (c == "OOM" and "OutOfMemoryError" in fl) or (c == "WATCHDOG" and "A single server tick took" in fl):
                cls = {"OTHER": "CRASH_OTHER", "INJECTED": "FAULT_INJECTED"}.get(c, c)
                reasons.append(f"server ended unexpectedly: {c} ({len(cr)} restart(s))")
                break
    if cls is None and tier:
        if rec.get("joined") is None:
            cls = "JOIN_INCOMPLETE"; reasons.append("bot controller never reported its join phase")
        elif rec["joined"] < tier or (rec.get("joinedAlive") is not None and rec["joinedAlive"] < tier):
            cls = "JOIN_INCOMPLETE"; reasons.append(f"joined {rec['joined']}/{tier}, alive at join end {rec.get('joinedAlive')}")
        elif rec.get("playersMinMeasure") is not None and rec["playersMinMeasure"] < tier:
            cls = "DROPOUT"; reasons.append(f"server saw only {rec['playersMinMeasure']}/{tier} players during measure")
        elif rec.get("playersMinMeasure") is None:
            cls = "HARNESS"; reasons.append("no telemetry player count in the measure window")
    m = rec.get("measure") or {}
    t = m.get("ticks") or {}
    if cls is None and not t.get("tps"):
        cls = "HARNESS"; reasons.append("no tick telemetry in the measure window")
    if cls is None and rec.get("stopClean") is False:
        reasons.append("stop was not clean (recorded; measurement kept)")
    cls = cls or "MEASURED"
    out = {"class": cls, "valid": cls == "MEASURED", "reasons": reasons}
    if out["valid"]:
        ex = m.get("extras") or {}
        acts = action_success(rec.get("botsMeasure"))
        j = (rec.get("join") or {}).get("ticks") or {}
        T = TARGETS
        crit = {"tpsAvg": (t.get("tps"), t.get("tps") is not None and t["tps"] >= T["tpsAvgMin"]),
                "tpsWorst10s": (ex.get("tpsWorst10s"), ex.get("tpsWorst10s") is not None and ex["tpsWorst10s"] >= T["tpsWorst10sMin"]),
                "msptP95": (t.get("msptP95"), t.get("msptP95") is not None and t["msptP95"] <= T["msptP95Max"]),
                "msptP99": (t.get("msptP99"), t.get("msptP99") is not None and t["msptP99"] <= T["msptP99Max"]),
                "gcPauseSharePct": (ex.get("gcPauseSharePct"), ex.get("gcPauseSharePct") is None or ex["gcPauseSharePct"] <= T["gcPauseSharePctMax"]),
                "actionOkPct": ((acts or {}).get("okPct"), not acts or acts["okPct"] is None or acts["okPct"] >= T["actionOkPctMin"]),
                "disconnects": (rec.get("botsJoinWarmupDisconnects", 0) + ((rec.get("botsMeasure") or {}).get("disconnects") or 0),
                                rec.get("botsJoinWarmupDisconnects", 0) + ((rec.get("botsMeasure") or {}).get("disconnects") or 0) == 0),
                "joinMaxTickGapMs": (j.get("tickGapMaxMs"), j.get("tickGapMaxMs") is None or j["tickGapMaxMs"] <= T["joinMaxTickGapMsMax"])}
        out["criteria"] = {k: {"value": v, "ok": ok} for k, (v, ok) in crit.items()}
        out["steadyStatePlayable"] = all(crit[k][1] for k in ("tpsAvg", "tpsWorst10s", "msptP95", "msptP99", "gcPauseSharePct", "actionOkPct", "disconnects"))
        out["joinStable"] = crit["joinMaxTickGapMs"][1]
        out["stablePlayable"] = out["steadyStatePlayable"] and out["joinStable"]
        out["actions"] = acts
    return out


# ------------------------------------------------------------------------------------------------ run

def analyze(run_dir: Path):
    man = json.loads((run_dir / "manifest.json").read_text(encoding="utf-8"))
    raw = run_dir / "raw"
    samples = parse_dev(raw / "dev.txt") if (raw / "dev.txt").exists() else []
    ticks = load_ticks(raw)
    gcs = parse_gc(raw / "gc.log")
    pkg = man.get("testPackage", "dev.phoenix.server.uicheck")
    phases = {}
    for name, (t0, t1) in man.get("phases", {}).items():
        if t0 is None or t1 is None:
            continue
        phases[name] = {"start": t0, "end": t1, "device": dev_window(samples, t0, t1), "ticks": tick_window(ticks, t0, t1),
                        "extras": extra_window(ticks, gcs, t0, t1),
                        "telemetry": telemetry_window(raw, t0, t1),
                        "gc": gc_window(gcs, t0, t1) if gcs else None,
                        "gcAgent": metrics_gc_window(raw / "server.log", t0, t1),
                        "log": log_window(raw / "server.log", t0, t1),
                        "logcat": logcat_events(raw / "logcat.txt", pkg, t0, t1)}
    summary = {"runId": man.get("runId"), "arm": man.get("arm", {}).get("arm"), "scenario": man.get("scenario"),
               "heapMb": man.get("heapMb"), "screen": man.get("screen"), "tier": man.get("tier"),
               "result": man.get("result"), "phases": phases, "events": man.get("events", []),
               # R2: unexpected server ends with their evidenced cause, and where the saved server.log slice begins
               "crashes": crashes(raw / "server.log", raw / "logcat.txt"), "serverLogStartsAt": first_line_time(raw / "server.log")}
    st = man.get("phases", {}).get("startup")
    # R4: a crashed run's ticks.csv belongs to Phoenix's auto-restarted JVM; the killed (original) JVM's ticks are the
    # ones run.py saved in crash1/telemetry, so join and lag metrics of a crashed run come from there
    orig = raw / "crash1" / "telemetry"
    orig_ticks = load_ticks(orig) if (orig / "ticks.csv").exists() else None
    jticks, jsource = (orig_ticks, "crash1/telemetry") if orig_ticks else (ticks, "ticks.csv")
    if st and phases.get("join") and jticks:
        j0 = phases["join"]["start"]
        w1 = phases.get("warmup", phases["join"])["end"]
        summary["joinStall"] = {"maxTickGapMs": (tick_window(jticks, j0, w1) or {}).get("tickGapMaxMs"),
                                "lagBehindS": extra_window(jticks, [], j0, w1).get("lagBehindS"),
                                "recoverySFromJoinStart": recovery_seconds(jticks, j0, phases.get("measure", phases["join"])["end"]),
                                "source": jsource}
    # R4: peak accumulated lag / catch-ups / watchdog margin, screen-off cpuset timing, per-thread CPU, watchdog dump
    ref = (phases.get("join") or phases.get("warmup") or {}).get("start")
    ends = [phases[n]["end"] for n in ("join", "warmup", "measure") if n in phases]
    crash_t = min((c["t"] for c in summary["crashes"] if not st or c["t"] >= st[0] - 5), default=None)
    if crash_t is not None:                             # a crash ends the run early: at the watchdog line if there is one
        wd_t = watchdog_time(raw / "server.log", st[0] - 5 if st else 0.0)
        t_end = min(crash_t, wd_t) if wd_t is not None and wd_t <= crash_t else crash_t
    else:
        t_end = max(ends) if ends else None
    wd_ms = re.match(r"\d+", str(man.get("maxTickTimeMs") or "60000"))
    if ref is not None and t_end is not None and jticks:
        summary["lag"] = lag_summary(jticks, ref, t_end, int(wd_ms.group(0)) / 1000 if wd_ms else 60.0, raw / "server.log", jsource)
    if ref is not None and t_end is not None:
        summary["screenState"] = screen_state(samples, man.get("screenOffAt"), ref, t_end)
    if any(s.get("threads") for s in samples):
        summary["threadsCaptured"] = any(t["name"] == "Server thread" for s in samples for t in (s.get("threads") or {}).values())
        tw = {}
        if ref is not None:
            for name, a, b in (("join0-30", 0, 30), ("join30-60", 30, 60), ("join60-120", 60, 120), ("join0-60", 0, 60)):
                tw[name] = thread_window(samples, ref + a, min(ref + b, t_end if t_end is not None else ref + b))
        if "measure" in phases:
            tw["measure"] = thread_window(samples, phases["measure"]["start"], phases["measure"]["end"])
        summary["threadWindows"] = tw
    summary["watchdogDump"] = watchdog_dump(raw / "server.log")
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    return summary


if __name__ == "__main__":
    for d in sys.argv[1:]:
        s = analyze(Path(d))
        print(json.dumps({k: v for k, v in s.items() if k != "phases"}, indent=1))
        for n, p in s["phases"].items():
            print(n, json.dumps({k: p[k] for k in ("ticks", "gc", "gcAgent")}, indent=None))
            print("   device", json.dumps(p["device"]))
