"""Compare two Stage 4 ordered traces while ignoring run-specific clock values.

The comparator deliberately compares the callback sequence and event payloads.  It
does not reduce a trace to counters and it never upgrades a partial input report
to PASS.  Absolute game times are normalized to the trace's first observed time;
positions, block/fluid identities, priorities, sub-order values, and recipients
remain strict comparison fields.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

TIME = re.compile(r"\b(?:gameTime|trigger|now|target|currentTick)=(-?\d+)\b")
IDENTITY = re.compile(r"@[0-9a-fA-F]+")
AUTHORITY = re.compile(
    r"\scontextRegion=\S+(?:\slease=-?\d+\scategory=\S+)?\s"
    r"route=(?:RESOLVED|UNRESOLVED|REFERENCE)(?:\sworld=\S+\schunk=-?\d+,-?\d+"
    r"\sregion=\S+\sgeneration=-?\d+\sownerEpoch=-?\d+\sexplicitFixture=\S+)?")
ROUTE_ASSIGNMENT = re.compile(
    r"(routeA|routeB)=route=(?:RESOLVED|UNRESOLVED)\s+world=\S+\s+chunk=-?\d+,-?\d+"
    r"\s+region=\S+\s+generation=-?\d+\s+ownerEpoch=-?\d+\s+explicitFixture=\S+")

OBSERVABLE = {"tick.schedule", "tick.block.vanilla", "tick.fluid", "block.enqueue",
              "block.dispatch", "block.dispatch.result", "block.nested.enqueue",
              "block.recipient.packet", "tracking.pair", "tracking.remove", "tracking.delivered"}
CONTRACT = {"fixture.player.pads", "trace.armed", "tick.pending.input", "block.recipient.required",
            "fixture.deferred.released", "fixture.temporary.unloaded",
            "fixture.temporary.released", "block.deferred.eligibility", "block.probe", "block.probe.input",
            "block.probe.result", "block.probe.support", "tick.migrated.result", "block.deferred.result",
            "save.server.begin", "save.server.end", "save.enter", "save.exit", "server.stop.begin", "server.stop"}
CLOCKS = {"clock.step", "clock.step.reached", "clock.far.deadline", "tick.native.unpack"}
# These are retained in raw evidence but do not describe a delivered effect. Native
# ServerEntity.sendChanges also advances its own counter while gameTime is frozen.
# Compare every actual delivery and primitive payload in order, without assigning
# its network delivery to that unrelated simulation-clock coordinate.
INTERNAL = {"tracking.recipient", "tracking.recipient.remove", "tracking.packet", "tracking.changes",
            "tracking.body", "tracking.lifecycle.required", "block.deferred", "trace.begin", "clock.freeze",
            "teleport.accept", "trace.barrier"}


def load(path: Path):
    value = json.loads(path.read_text(encoding="utf-8"))
    events = value.get("events")
    if not isinstance(events, list) or not all(isinstance(item, str) for item in events):
        raise ValueError(f"{path} has no string events array")
    start = value.get("startedAt")
    if not isinstance(start, int):
        start = next((int(match.group(1)) for item in events
                      for match in [re.search(r"\bgameTime=(-?\d+)\b", item)] if match), 0)

    native_orders = {}
    for item in events:
        if item.startswith("tick.schedule ") and "WaterFluid$" in item and "priority=NORMAL" in item:
            trigger = re.search(r"\btrigger=(-?\d+)", item).group(1)
            order = int(re.search(r"\border=(-?\d+)", item).group(1))
            native_orders.setdefault(trigger, set()).add(order)
    identities = {}

    def normalize(item):
        if item.startswith("tick.schedule ") and "WaterFluid$" in item and "priority=NORMAL" in item:
            trigger = re.search(r"\btrigger=(-?\d+)", item).group(1)
            order = int(re.search(r"\border=(-?\d+)", item).group(1))
            # Only native auto-assigned values use the world's unrelated global counter.
            # Preserve every difference/tie within the exact trigger/priority bucket.
            item = re.sub(r"\border=-?\d+", f"order=native+{order - min(native_orders[trigger])}", item)
        if item.startswith(("tracking.pair ", "tracking.remove ", "tracking.delivered ")):
            identity = re.search(r"\bentityId=(\d+)", item)
            uuid = re.search(r"\buuid=([0-9a-f-]{36})", item)
            if not identity or not uuid:
                raise ValueError("actual tracking effects require stable UUID and native runtime identity")
            runtime = identity.group(1)
            if identities.setdefault(runtime, uuid.group(1)) != uuid.group(1):
                raise ValueError("native entity id changed fixture UUID")
            for payload_id in re.findall(r"\b(?:entityId|id)=(\d+)", item):
                if payload_id != runtime:
                    raise ValueError("delivered packet names a foreign native entity id")
            item = re.sub(r"\b(entityId|id)=\d+", r"\1=<runtime-id>", item)
            item = re.sub(r"\s+gameTime=-?\d+", "", item)
        def replace(match):
            return f"{match.group(0).split('=')[0]}={int(match.group(1)) - start}"
        normalized = IDENTITY.sub("@<identity>", TIME.sub(replace, item))
        # Ownership context and route generation are acceptance metadata.  The event kind,
        # position, timing, packet payload and recipient remain in the behavioral stream;
        # lifecycle evidence validates the metadata independently below.
        normalized = AUTHORITY.sub("", normalized)
        normalized = ROUTE_ASSIGNMENT.sub(r"\1=<route>", normalized)
        return normalized

    ordered, tracking, contract, clocks, readiness, saves = [], [], [], [], [], {}
    for item in events:
        kind = item.split(" ", 1)[0]
        if kind in OBSERVABLE:
            (tracking if kind.startswith("tracking.") else ordered).append(normalize(item))
        elif kind in CONTRACT:
            contract.append(normalize(item))
        elif kind in CLOCKS:
            clocks.append(normalize(item))
        elif kind == "save.serialization.ticks":
            chunk = re.search(r"chunk=\[[^]]+\]", item).group(0)
            # Intermediate native eager saves are wall-clock driven. Compare the final
            # exact packed arrays for every observed fixture chunk, including delay and
            # priority. A clean empty chunk need not be serialized again at final flush.
            saves[chunk] = re.sub(r"\s+gameTime=-?\d+", "", normalize(item))
        elif kind == "full.ready":
            radius = int(re.search(r"radius=(\d+)", item).group(1))
            target = int(re.search(r"target=(\d+)", item).group(1))
            if target < (2 * (radius - 1) + 1) ** 2:
                raise ValueError("FULL receipt lacks its complete configured simulation cohort")
            readiness.append(re.sub(r" target=\d+ candidates=\d+", "", item))
        elif kind not in INTERNAL and not kind.startswith("topology."):
            raise ValueError(f"unclassified trace event: {kind}")
    if not any(item.startswith("tracking.delivered ") for item in tracking):
        raise ValueError("trace lacks actual tracker connection.send evidence")
    if not any(item.startswith("tick.native.unpack ") for item in clocks):
        raise ValueError("trace lacks native fixture unpack evidence")
    behavioral = (["observable:" + item for item in ordered]
        + ["tracking:" + item for item in tracking]
        + ["contract:" + item for item in contract]
        + ["clock:" + item for item in clocks]
        + ["readiness:" + item for item in readiness]
        + ["save:" + saves[key] for key in sorted(saves)])
    return value, behavioral


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--require-lifecycle", action="store_true",
                        help="require coordinator merge/split, deferred-event, and body evidence")
    args = parser.parse_args()
    reference_report, reference = load(args.reference)
    candidate_report, candidate = load(args.candidate)
    lifecycle_markers = ("topology.merge", "topology.split", "topology.body", "block.deferred",
                         "tracking.body")
    lifecycle_evidence = None
    if args.require_lifecycle:
        def marker_present(events, marker):
            return any(item == marker or item.startswith(marker + " ") for item in events)
        lifecycle_evidence = {
            "candidate": {marker: marker_present(candidate_report.get("events", []), marker)
                          for marker in lifecycle_markers},
        }
        # The frozen serial reference intentionally has no coordinator or owner-body
        # observations.  Lifecycle is therefore a candidate-only installed gate; requiring
        # these markers on the reference would make a valid behavioral comparison impossible.
        for report_name, report in (("candidate", candidate_report),):
            events = report.get("events", [])
            lifecycle_evidence[report_name]["topology.body.owner.a"] = any(
                item.startswith("topology.body ") and "contextRegion=stage3-fixture-a" in item
                for item in events if isinstance(item, str))
            lifecycle_evidence[report_name]["topology.body.owner.b"] = any(
                item.startswith("topology.body ") and "contextRegion=stage3-fixture-b" in item
                for item in events if isinstance(item, str))
            lifecycle_evidence[report_name]["tracking.body.owner.a"] = any(
                item.startswith("tracking.body ") and "contextRegion=stage3-fixture-a" in item
                for item in events if isinstance(item, str))
            lifecycle_evidence[report_name]["tracking.body.owner.b"] = any(
                item.startswith("tracking.body ") and "contextRegion=stage3-fixture-b" in item
                for item in events if isinstance(item, str))
    first_mismatch = None
    for index, (expected, actual) in enumerate(zip(reference, candidate)):
        if expected != actual:
            first_mismatch = {"index": index, "reference": expected, "candidate": actual}
            break
    if first_mismatch is None and len(reference) != len(candidate):
        index = min(len(reference), len(candidate))
        first_mismatch = {
            "index": index,
            "reference": reference[index] if index < len(reference) else None,
            "candidate": candidate[index] if index < len(candidate) else None,
        }
    reference_digest = hashlib.sha256("\n".join(reference).encode()).hexdigest().upper()
    candidate_digest = hashlib.sha256("\n".join(candidate).encode()).hexdigest().upper()
    result = {
        "kind": "stage4-ordered-trace-comparison",
        "reference": str(args.reference),
        "candidate": str(args.candidate),
        "referenceResult": reference_report.get("result"),
        "candidateResult": candidate_report.get("result"),
        "referenceEvents": len(reference),
        "candidateEvents": len(candidate),
        "referenceDigest": reference_digest,
        "candidateDigest": candidate_digest,
        "firstMismatch": first_mismatch,
        "lifecycleEvidence": lifecycle_evidence,
        "result": "PASS" if first_mismatch is None and
                  reference_report.get("result") == "PASS" and
                  candidate_report.get("result") == "PASS" and
                  (not args.require_lifecycle or all(lifecycle_evidence["candidate"].values())) else "PARTIAL",
    }
    rendered = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if result["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
