#!/usr/bin/env python3
"""Render the strict Stage 7 coverage ledger without changing its evidence."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path


DISPOSITIONS = (
    "TRANSFER_VERIFIED",
    "NATIVE_REFUSAL_VERIFIED",
    "NO_NATIVE_ENTRY_PATH_VERIFIED",
    "ROUTE_NOT_APPLICABLE_VERIFIED",
    "SAME_DIMENSION_TRANSFER_VERIFIED",
    "UNRESOLVED",
)


def render(coverage: dict) -> str:
    rows = coverage["rows"]
    by_id = {row["id"]: row for row in rows}
    if len(by_id) != len(rows):
        raise ValueError("duplicate v2 row ID")
    frozen = coverage["frozenIdMapping"]
    if len(frozen) != coverage["frozenCaseCount"] or len(set(frozen.values())) != len(frozen):
        raise ValueError("frozen ID mapping is not one to one")
    if any(v2 not in by_id for v2 in frozen.values()):
        raise ValueError("frozen ID points outside coverage rows")
    counts = Counter(row["disposition"] or "UNRESOLVED" for row in rows)
    if any(name not in DISPOSITIONS for name in counts):
        raise ValueError("unexpected final disposition")
    if any(counts[name] != coverage["dispositionCounts"][name] for name in DISPOSITIONS):
        raise ValueError("reported disposition counts differ from rows")
    if counts["UNRESOLVED"] != coverage["unresolvedCount"]:
        raise ValueError("reported unresolved count differs from rows")

    lines = [
        "# PHOENIUM Stage 7 portal coverage ledger",
        "",
        f"Candidate SHA-256: `{coverage['candidateSha256']}`. Pinned native source SHA-256: "
        f"`{coverage['sourceJarSha256']}`; mapped binary SHA-256: "
        f"`{coverage['mappedBinarySha256']}`.",
        "",
        f"Status: **{'COMPLETE' if coverage['complete'] else 'PARTIAL'}**. "
        f"Frozen IDs: {coverage['frozenCaseCount']}; generated obligations: "
        f"{coverage['generatedCaseCount']}; unresolved: {coverage['unresolvedCount']}.",
        "",
        "## Final dispositions",
        "",
        "| Disposition | Rows |",
        "| --- | ---: |",
    ]
    lines.extend(f"| {name} | {counts[name]} |" for name in DISPOSITIONS)
    lines.extend([
        "",
        "A blank disposition below means unresolved. Each row's source proof, required "
        "assertions, installed receipts, rejected runs, and failure reasons are in "
        "`PORTAL_COVERAGE_V2.json`.",
        "",
        "## Original 843 ID mapping",
        "",
        "| Frozen ID | V2 row | Expected native outcome | Final disposition |",
        "| --- | --- | --- | --- |",
    ])
    for old_id, v2_id in frozen.items():
        row = by_id[v2_id]
        lines.append(f"| `{old_id}` | `{v2_id}` | {row['expectedNativeOutcome'] or 'UNRESOLVED'} "
                     f"| {row['disposition'] or 'UNRESOLVED'} |")
    lines.extend([
        "",
        "## Added obligations",
        "",
        "| V2 row | Entity / variant | Route | Expected native outcome | Final disposition |",
        "| --- | --- | --- | --- | --- |",
    ])
    for row in rows:
        if row["legacyCaseId"] is not None:
            continue
        entity = (row["entityType"] or row["graph"] or "unknown")
        if row["variant"]:
            entity += " / " + row["variant"]
        lines.append(f"| `{row['id']}` | {entity} | {row['route']} | "
                     f"{row['expectedNativeOutcome'] or 'UNRESOLVED'} | "
                     f"{row['disposition'] or 'UNRESOLVED'} |")
    lines.extend([
        "",
        "## Gate summary",
        "",
        f"Credited installed cards: {coverage['creditedCardCount']}. "
        f"Rejected runs: {len(coverage['rejectedRuns'])}. "
        f"Metadata errors: {len(coverage['metadataErrors'])}.",
        "",
        "This report mirrors the JSON ledger and awards no additional coverage.",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--coverage", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = render(json.loads(args.coverage.read_text(encoding="utf-8")))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(result, encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
