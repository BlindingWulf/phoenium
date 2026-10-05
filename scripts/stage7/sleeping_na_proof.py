#!/usr/bin/env python3
"""Generate the ROUTE_NOT_APPLICABLE proof of the `sleeping` player rows.

A sleeping player has no portal contact: LivingEntity.canUsePortal returns `super.canUsePortal(..) && !isSleeping()`, the
sleeping position is the bed cell (setPosToBed), and LivingEntity.tick wakes a sleeper (stopSleeping) before aiStep whenever
checkBedExists() fails - and a portal block placed into the bed cell is exactly what makes the bed disappear. So the state
"sleeping inside a portal block" is not a steady state any route can observe. The tool binds the pinned source references
and three native observations of that transient (explore-output.json of a Phoenium-free control and of W1 and W4): the player
really sleeps in the cell, the cell is replaced by the portal block, and the player is awake on the next read-back and
ends in the dimension the route's block selects, identically in all three modes.

Usage: sleeping_na_proof.py --sources <sources.jar> --binary <mapped.jar> --vanilla <explore-output.json>
                            --w1 <explore-output.json> --w4 <explore-output.json> --output <json>
The observation files are copied to <output dir>/sleeping-observations/ and referenced by that relative path; the classifier
copies both next to the census and the importer re-reads and re-verifies them (build_portal_coverage.sleeping_observation_verdict).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import zipfile

import build_portal_coverage as coverage
import no_entry_source_proofs as source_proofs

LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity"
METHODS = (
    ("canUsePortal", r"\tpublic boolean canUsePortal\(final boolean ignorePassenger\)"),
    ("setPosToBed", r"\tprivate void setPosToBed\(final BlockPos bedPosition\)"),
    ("checkBedExists", r"\tprivate boolean checkBedExists\(\)"),
    ("tick", r"\tpublic void tick\(\) \{"),
)
PREDICATE = ("LivingEntity.canUsePortal requires !isSleeping(); a sleeper is positioned in its bed cell (setPosToBed) and "
             "LivingEntity.tick calls stopSleeping() before aiStep() as soon as checkBedExists() fails, which is what "
             "placing a portal block into that cell does, so no tick observes a sleeping entity in portal contact")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=pathlib.Path, required=True)
    parser.add_argument("--binary", type=pathlib.Path, required=True)
    for mode in ("vanilla", "w1", "w4"):
        parser.add_argument(f"--{mode}", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args(argv)
    sources, binary = zipfile.ZipFile(args.sources), zipfile.ZipFile(args.binary)
    source_sha, binary_sha = source_proofs.sha(args.sources.read_bytes()), source_proofs.sha(args.binary.read_bytes())
    lines = sources.read(LIVING_ENTITY + ".java").decode("utf-8").replace("\r\n", "\n").split("\n")
    class_sha = source_proofs.sha(binary.read(LIVING_ENTITY + ".class"))
    references = {}
    for name, pattern in METHODS:
        line = source_proofs.find_method(lines, pattern)
        text = source_proofs.method_at(lines, line)
        references[name] = {"file": LIVING_ENTITY + ".java", "method": name, "line": line,
                            "sourceMethodSha256": source_proofs.sha(text.encode("utf-8")), "classBytecodeSha256": class_sha}
    if "!this.isSleeping()" not in source_proofs.method_at(lines, references["canUsePortal"]["line"]):
        raise SystemExit("LivingEntity.canUsePortal no longer refuses a sleeping entity")
    tick = source_proofs.method_at(lines, references["tick"]["line"])
    if "this.isSleeping() && (!this.canInteractWithLevel() || !this.checkBedExists())" not in tick or "this.stopSleeping();" not in tick:
        raise SystemExit("LivingEntity.tick no longer wakes a sleeper whose bed is gone")
    observations = []
    for mode, path in (("vanilla", args.vanilla), ("W1", args.w1), ("W4", args.w4)):
        verdicts = coverage.sleeping_observation_verdict(json.loads(path.read_text(encoding="utf-8")))
        if verdicts is None or not all(v["asleepBefore"] and v["positionInCell"] and v["wokeAtOnce"] for v in verdicts):
            raise SystemExit(f"{mode} exploration does not show the constructed sleeping state and the wake-up: {path}")
        target = args.output.parent / "sleeping-observations" / f"{mode}-explore-output.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(path.read_bytes())
        observations.append({"mode": mode, "path": f"sleeping-observations/{target.name}",
                             "sha256": hashlib.sha256(path.read_bytes()).hexdigest().upper(), "verdicts": verdicts})
    if any(item["verdicts"] != observations[0]["verdicts"] for item in observations):
        raise SystemExit("vanilla, W1 and W4 disagree about the sleeping transient")
    proof = {
        "kind": coverage.SLEEPING_NA_KIND, "sourceJarSha256": source_sha, "mappedBinarySha256": binary_sha,
        "nativeOutcome": "ROUTE_NOT_APPLICABLE",
        "reference": references["canUsePortal"],
        "supportingReferences": [references[name] for name in ("setPosToBed", "checkBedExists", "tick")],
        "eligibilityPredicate": PREDICATE, "applicabilityDemonstrated": True,
        "artifact": args.output.name,
    }
    args.output.write_text(json.dumps({"schemaVersion": 1, "sourceJarSha256": source_sha, "mappedBinarySha256": binary_sha,
                                       "proof": proof, "observations": observations}, indent=2) + "\n", encoding="utf-8")
    print(f"wrote sleeping proof to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
