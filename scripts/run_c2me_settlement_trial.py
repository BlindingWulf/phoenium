"""Pinned patched-provider trials using the existing disposable console runner."""
from pathlib import Path
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "run/stage8/entry-20260913/astra-c2me-provider-settlement"
PATCHED = ROOT.parent / "C2ME-locked-settlement/build/patched/c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar"
PATCHED_SHA = "8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53"

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()

def main():
    label, worker, plan_name, *candidate_args = sys.argv[1:]
    assert worker in ("1", "4")
    assert sha(PATCHED) == PATCHED_SHA
    candidate_name, expected_production = candidate_args or ["accepted-source-no-break-candidate.jar",
        "8A1C0C4852150C8D36E9EDB8DDEFC9F9B6E9A3E82AE71123D798416DD39A21FC"]
    candidate = EVIDENCE / candidate_name
    production = ROOT / "build/libs/phoenium-0.2.0-beta.1.jar"
    assert sha(production) == expected_production
    if candidate_name == "@production":
        candidate = production
        with zipfile.ZipFile(production) as jar:
            assert not json.loads(jar.read("fabric.mod.json")).get("breaks")
    else:
        content = io.BytesIO()
        with zipfile.ZipFile(production) as original, zipfile.ZipFile(content, "w") as target:
            for entry in original.infolist():
                data = original.read(entry)
                if entry.filename == "fabric.mod.json":
                    meta = json.loads(data)
                    assert meta.pop("breaks") == {"c2me": "*", "c2me-rewrites-chunk-system": "*"}
                    data = json.dumps(meta, indent=2).encode()
                target.writestr(entry, data)
        expected_candidate = content.getvalue()
        if not candidate.exists():
            # Publish a complete immutable candidate atomically when W1/W4 start together.
            with tempfile.NamedTemporaryFile(dir=EVIDENCE, suffix=".candidate-pending", delete=False) as pending:
                pending.write(expected_candidate)
                pending_path = Path(pending.name)
            try:
                try: os.link(pending_path, candidate)
                except FileExistsError: pass
            finally: pending_path.unlink()
        assert candidate.read_bytes() == expected_candidate, "Existing candidate differs from exact gated production conversion"
    output = EVIDENCE / f"{label}-w{worker}"
    plan = json.loads(Path(plan_name).read_text())
    plan["jvm"] = [p for p in plan.get("jvm", []) if not p.startswith("-Dphoenium.c2me.observations=")]
    plan["jvm"] += ["-Dphoenium.c2me.nativeSettlementWitness=true", "-Dphoenium.c2me.observations=" + str(output / "observations.json")]
    plan_path = EVIDENCE / f"{label}-w{worker}-plan.json"
    plan_path.write_text(json.dumps(plan, indent=2))
    command = [sys.executable, "scripts/run_stage6_console.py", "--output", str(output), "--plan", str(plan_path),
        "--api", "build/stage6-audit-20260909/research/artifacts/fabric-api/fabric-api-0.159.0+26.2.jar",
        "--mod", str(PATCHED), "--mod", "build/stage6-audit-20260909/research/artifacts/scalablelux/ScalableLux-fabric-0.3.0-alpha.0.3-all.jar",
        "--mod", "build/test-harness/c2me-path-observer.jar", "--production", str(candidate),
        "--production-sha256", sha(candidate), "--workers", worker, "--port", str(25680 + int(worker))]
    for extra in plan.get("pinnedMods", []):
        mod = ROOT / extra["path"]
        assert sha(mod) == extra["sha256"]
        command += ["--mod", str(mod)]
    provenance = dict(patchedC2meSha256=PATCHED_SHA, candidateSha256=sha(candidate),
        sourceHead=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        command=command, worker=int(worker))
    source_files = {str(path.relative_to(ROOT)): sha(path) for path in sorted((ROOT / "src/main").rglob("*")) if path.is_file()}
    source_manifest = json.dumps(source_files, sort_keys=True, indent=2).encode()
    source_path = EVIDENCE / f"{label}-w{worker}-source.json"
    source_path.write_bytes(source_manifest)
    provenance["sourceManifest"] = str(source_path)
    provenance["sourceManifestSha256"] = hashlib.sha256(source_manifest).hexdigest().upper()
    (EVIDENCE / f"{label}-w{worker}-command.json").write_text(json.dumps(provenance, indent=2))
    result = subprocess.call(command, cwd=ROOT)
    provenance["runnerExitCode"] = result
    if output.exists(): (output / "patched-provider-provenance.json").write_text(json.dumps(provenance, indent=2))
    raise SystemExit(result)

if __name__ == "__main__":
    main()
