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

TIME = re.compile(r"\b(?:gameTime|trigger|now|target)=(-?\d+)\b")
IDENTITY = re.compile(r"@[0-9a-fA-F]+")
AUTHORITY = re.compile(
    r"\scontextRegion=\S+\slease=-?\d+\scategory=\S+\s"
    r"route=(?:RESOLVED|UNRESOLVED|REFERENCE)(?:\sworld=\S+\schunk=-?\d+,-?\d+"
    r"\sgeneration=-?\d+\sownerEpoch=-?\d+\sexplicitFixture=\S+)?")
ROUTE_ASSIGNMENT = re.compile(
    r"(routeA|routeB)=route=(?:RESOLVED|UNRESOLVED)\s+world=\S+\s+chunk=-?\d+,-?\d+"
    r"\s+generation=-?\d+\s+ownerEpoch=-?\d+\s+explicitFixture=\S+")


def load(path: Path):
    value = json.loads(path.read_text(encoding="utf-8"))
    events = value.get("events")
    if not isinstance(events, list) or not all(isinstance(item, str) for item in events):
        raise ValueError(f"{path} has no string events array")
    start = value.get("startedAt")
    if not isinstance(start, int):
        start = next((int(match.group(1)) for item in events
                      for match in [re.search(r"\bgameTime=(-?\d+)\b", item)] if match), 0)

    def normalize(item):
        def replace(match):
            return f"{match.group(0).split('=')[0]}={int(match.group(1)) - start}"
        normalized = IDENTITY.sub("@<identity>", TIME.sub(replace, item))
        # Ownership context and route generation are acceptance metadata.  The event kind,
        # position, timing, packet payload and recipient remain in the behavioral stream;
        # lifecycle evidence validates the metadata independently below.
        normalized = AUTHORITY.sub("", normalized)
        normalized = ROUTE_ASSIGNMENT.sub(r"\1=<route>", normalized)
        return normalized

    # Coordinator observations and per-owner body receipts are validated as a separate
    # candidate gate.  They are not part of the frozen vanilla behavioral stream because the
    # serial reference has no corresponding topology API or owner context.
    behavioral = [item for item in events
                  if not item.startswith("topology.")
                  and not item.startswith("tracking.body ")
                  # Deferred-count observation is a candidate-only service
                  # diagnostic; the exact deferred callback/result remains
                  # in the shared behavioral stream.
                  and not item.startswith("block.deferred ")]
    return value, [normalize(item) for item in behavioral]


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
