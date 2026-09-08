"""Strict native furnace snapshots plus independent original body/claim interval checks.

Only absolute clock origin and diagnostic/timing metadata are excluded from semantic
equality. Furnace order, coordinates, inventories, fuel and native timers stay exact.
"""
import argparse
import hashlib
import json
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def canonical(snapshot, origin):
    health = snapshot["health"]
    for name in ("duplicates", "unexpectedBypasses", "fallbacks", "ownershipViolations",
                 "unleasedExecutions", "hardBarriers", "producerThreadChunkMutations"):
        require(health[name] == 0, f"unhealthy {name}={health[name]}")
    require(not health["barrierTripped"] and health["lifecycle"] == "RUNNING", "native health not running")
    require(health["intercepted"] == health["dispatched"] == health["executed"], "unfinished original world ticks")
    require(health["activeLeases"] == 0, "snapshot retained a gameplay lease")
    rows = snapshot["furnaces"]
    require(rows and len({(row["x"], row["y"], row["z"]) for row in rows}) == len(rows), "empty/duplicate native furnace coverage")
    expected = {"x", "y", "z", "ingredient", "fuel", "output", "lit", "cooking", "total"}
    for row in rows:
        require(set(row) == expected, "unknown or missing native furnace state field")
        require(all(type(value) is int for value in row.values()), "nonintegral native furnace state")
    result = {"label": snapshot["label"], "layout": snapshot["layout"],
              "gameTime": snapshot["gameTime"] - origin, "furnaces": rows}
    if snapshot["label"].startswith("churn-"):
        require(snapshot["replacements"] > 0 and snapshot["staleReads"] == 2 * snapshot["replacements"], "missing native churn coverage")
        result.update(replacements=snapshot["replacements"], staleReads=snapshot["staleReads"])
    if "ticks" in snapshot:
        require(len(snapshot["tickNanos"]) == snapshot["ticks"], "missing measured native ticks")
        require(snapshot["gameTime"] - snapshot["startGameTime"] == snapshot["ticks"], "changed native clock input")
        result.update({"ticks": snapshot["ticks"], "startGameTime": snapshot["startGameTime"] - origin})
    if isinstance(snapshot.get("nativeCallbacks"), list):
        result["nativeCallbacks"] = canonical_callbacks(snapshot["nativeCallbacks"], rows, origin, result["gameTime"])
    return result


def canonical_callbacks(callbacks, furnaces, origin, ticks):
    require(len(callbacks) == len(furnaces), "missing native callback object")
    normalized = []
    for body, furnace in zip(callbacks, furnaces):
        require(set(body) == {"x", "y", "z", "events"}, "unknown callback field")
        require(all(body[key] == furnace[key] for key in ("x", "y", "z")), "changed native callback object order")
        events = body["events"]
        require(len(events) == 2 * ticks, "missing/duplicate original callback")
        prior = None
        for index in range(0, len(events), 2):
            before, after = events[index:index+2]
            require(len(before) == len(after) == 8, "missing native callback state")
            require(before[0] == after[0] == origin + index // 2 + 1, "changed callback clock/order")
            require(before[1] == "before" and after[1] == "after", "original callback phase order changed")
            if prior is not None:
                require(prior == before[2:], "effect outside original callback")
            prior = after[2:]
        require(prior == [furnace[k] for k in ("ingredient", "fuel", "output", "lit", "cooking", "total")],
                "native callback final effect differs from owner snapshot")
        normalized.append({**body, "events": [[event[0] - origin, *event[1:]] for event in events]})
    return normalized


def validate_intervals(gameplay, require_overlap=False):
    require(isinstance(gameplay, dict), "current engine has no original-body measurements")
    require(gameplay["failedBodies"] == 0 and gameplay["activeBodies"] == 0, "incomplete or failed original bodies")
    intervals = gameplay["intervals"]
    require(len(intervals) <= gameplay["intervalCapacity"], "retained interval bound exceeded")
    claims = {}
    body_ids = set()
    overlap = []
    for interval in intervals:
        require(interval["completed"], "unfinished original callback")
        require(interval["selectedAt"] <= interval["claimStart"] <= interval["bodyStart"]
                <= interval["bodyEnd"] <= interval["claimEnd"], "original body escaped claim interval")
        require(interval["identity"] not in body_ids, "duplicate original body identity")
        body_ids.add(interval["identity"])
        key = (interval["region"], interval["lease"])
        value = (interval["claimStart"], interval["claimEnd"], interval["operation"], interval["thread"], interval["generation"])
        require(key not in claims or claims[key] == value, "one claim changed identity/generation/lifetime")
        claims[key] = value
    # Sorting by observed timestamps is only an interval sweep, never semantic normalization.
    active = []
    for interval in sorted(intervals, key=lambda row: row["bodyStart"]):
        active = [prior for prior in active if prior["bodyEnd"] > interval["bodyStart"]]
        for prior in active:
            require(prior["region"] != interval["region"], "conflicting original bodies in one region")
            require(prior["thread"] != interval["thread"], "one worker reported simultaneous original bodies")
            if len(overlap) < 4:
                overlap.append([prior, interval])
        active.append(interval)
    per_region = {}
    for (region, lease), value in claims.items():
        per_region.setdefault(region, []).append((lease, value))
    for region, values in per_region.items():
        values.sort(key=lambda pair: pair[1][0])
        for previous, current in zip(values, values[1:]):
            require(previous[1][1] <= current[1][0], "conflicting claims in one region")
    if require_overlap:
        require(overlap, "no overlapping original gameplay bodies on distinct workers")
    return {"retainedIntervals": len(intervals), "retainedClaims": len(claims),
            "regions": len(per_region), "threads": sorted({row["thread"] for row in intervals}),
            "naturalOverlapExamples": overlap}


def compare(reference_dir, candidate_dir, require_overlap=False):
    reference_dir, candidate_dir = Path(reference_dir), Path(candidate_dir)
    reports = [json.loads((root / "report.json").read_text()) for root in (reference_dir, candidate_dir)]
    for report in reports:
        require(report["status"] == "PASS_NATIVE_WORKLOAD_ONLY" and report["exitCode"] == 0, "failed installed input campaign")
        require(report["identity"]["pass"], "invalid installed artifact identity")
        require(report["identity"]["expectedSha256"] == report["identity"]["installedSha256"], "artifact receipt mismatch")
    evidence = [root / "evidence" for root in (reference_dir, candidate_dir)]
    labels = [[path.name for path in root.glob("*.json") if path.name != "production-identity-first.json"] for root in evidence]
    require(set(labels[0]) == set(labels[1]), "different native observation coverage")
    origins = [json.loads((root / "warmup-before.json").read_text())["gameTime"] for root in evidence]
    canonical_rows = []
    for label in sorted(labels[0]):
        snapshots = [json.loads((root / label).read_text()) for root in evidence]
        left, right = [canonical(snapshot, origin) for snapshot, origin in zip(snapshots, origins)]
        require(left == right, f"changed native state or clock at {label}")
        canonical_rows.append(left)
    final = json.loads((evidence[1] / "final.json").read_text())
    intervals = validate_intervals(final["gameplay"], require_overlap)
    digest = hashlib.sha256(json.dumps(canonical_rows, sort_keys=True).encode()).hexdigest().upper()
    return {"status": "PASS", "reference": str(reference_dir), "candidate": str(candidate_dir),
            "canonicalSha256": digest, "observations": len(canonical_rows),
            "furnaces": len(final["furnaces"]), "completedSmelts": sum(row["output"] for row in final["furnaces"]),
            "intervalEvidence": intervals}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--require-overlap", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = compare(args.reference, args.candidate, args.require_overlap)
    except Exception as failure:
        result = {"status": "FAIL", "failure": str(failure)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2))
    print(json.dumps({key: result.get(key) for key in ("status", "failure", "observations", "completedSmelts")}))
    if result["status"] != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
