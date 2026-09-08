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
        return IDENTITY.sub("@<identity>", TIME.sub(replace, item))

    return value, [normalize(item) for item in events]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    reference_report, reference = load(args.reference)
    candidate_report, candidate = load(args.candidate)
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
        "result": "PASS" if first_mismatch is None and
                  reference_report.get("result") == "PASS" and
                  candidate_report.get("result") == "PASS" else "PARTIAL",
    }
    rendered = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if result["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
