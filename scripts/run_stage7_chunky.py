"""Finite single-run Stage 7 campaign. Exact locked mods, unchanged 81-chunk selection.

Use original for W1/W4 save/reload; concurrent for observed ordinary activity;
cancel/stop are distinct outstanding-work lifetime runs, never completion evidence.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
from check_stage7_chunky import concurrent_activity


def action(command, expect, **kwargs):
    return dict(command=command, expect=expect, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--archive", type=Path, default=Path("run/stage6/frozen-audit-045834b"))
    parser.add_argument("--production", type=Path, required=True)
    parser.add_argument("--production-sha256", required=True)
    parser.add_argument("--workers", type=int, choices=[1, 4], required=True)
    parser.add_argument("--mode", choices=["original", "concurrent", "cancel", "stop"], required=True)
    parser.add_argument("--probe", type=Path, default=Path("build/test-harness/stage7-observations.jar"))
    parser.add_argument("--gameplay-probe", type=Path, default=Path("build/test-harness/stage4-acceptance-probe.jar"))
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        raise SystemExit("Refusing existing output")
    output.mkdir(parents=True)
    plan = json.loads((args.archive / "runs/chunky-w1/plan.json").read_text())
    chunks = [dict(x=x, z=z) for x in range(6, 15) for z in range(-4, 5)]
    mods = [args.archive / "research/artifacts/chunky/Chunky-Fabric-1.5.3.jar"]
    if args.mode == "original":
        plan["requiredFullChunks"] = chunks
        plan["restart"] += [action("forceload add 96 -64 224 64", "Marked", settleSeconds=2)]
        plan["restart"] += [action(f"execute if loaded {p['x'] * 16} 65 {p['z'] * 16} run say RELOAD_{p['x']}_{p['z']}",
                                    f"RELOAD_{p['x']}_{p['z']}") for p in chunks]
    else:
        mods += [args.probe]
        plan["jvm"] = ["-Dphoenium.stage7.observations=" + str(output / "observations.json")]
        selection = plan["first"][:5]
        selection[-1] = action("chunky start", "Task started")
        plan.pop("restart")
        if args.mode == "concurrent":
            mods += [args.gameplay_probe]
            plan["jvm"] += ["-Dphoenium.stage5.probe=true", "-Dphoenium.stage5.centers=-10,-4,2",
                            "-Dphoenium.stage7.fixtureSupport=true",
                            "-Dphoenium.stage4.viewDistance=5", "-Dphoenium.stage4.simulationDistance=5",
                            "-Dphoenium.stage5.evidence=" + str(output / "gameplay")]
            plan["first"] = [action("stage5 demand", "STAGE5_READY"), action("stage5 prepare 1", "STAGE5_PREPARED"),
                action("forceload add -112 0 -81 15", "Marked", settleSeconds=2),
                action("fill -105 64 7 -87 64 9 minecraft:stone", "Successfully filled"),
                action("fill -105 65 7 -87 66 9 minecraft:air", "Successfully filled|No blocks were filled", regex=True),
                action("setblock -88 65 8 minecraft:redstone_lamp", "Changed the block"),
                action("fill -103 65 8 -89 65 8 minecraft:redstone_wire", "Successfully filled"),
                action("setblock -104 65 8 minecraft:repeater[facing=west,delay=1]", "Changed the block"),
                action("stage5 split", "STAGE5_TOPOLOGY"),
                action('phoenium route "minecraft:overworld" -7 0', "region="),
                action('phoenium route "minecraft:overworld" -6 0', "region="),
                action("tick unfreeze", "The game is running"), action("stage5 snapshot before", "STAGE5_SNAPSHOT")]
            plan["first"] += selection + [action("setblock -105 65 8 minecraft:redstone_block", "Changed the block", settleSeconds=0.3),
                action("execute if block -88 65 8 minecraft:redstone_lamp[lit=true] run say CONCURRENT_LAMP_ON", "CONCURRENT_LAMP_ON"),
                {"await": "Task finished", "after": "Task started", "timeout": 180},
                action("chunky progress", "No tasks running"), action("stage5 snapshot after", "STAGE5_SNAPSHOT")]
            plan["requiredFullChunks"] = chunks
            plan["positions"] = [dict(x=-88, y=65, z=8)]
        elif args.mode == "cancel":
            plan["first"] = selection + [action("chunky cancel", "If you are sure"),
                action("chunky confirm", "Cancelling all tasks"), action("chunky progress", "No tasks running")]
        else:
            # Native stop performs its ordinary save itself. No preceding flush/dump
            # delays the stop request until the pending generation has already settled.
            plan["first"] = selection
            plan["stopWithOutstanding"] = True
    plan["description"] = f"Stage 7 {args.mode}: audited square center 160,0 radius 64; workers {args.workers}"
    plan_path = output / "plan.json"
    plan_path.write_text(json.dumps(plan, indent=2))
    cmd = [sys.executable, str(Path(__file__).with_name("run_stage6_console.py")), "--output", str(output / "run"),
           "--plan", str(plan_path), "--workers", str(args.workers), "--production", str(args.production),
           "--production-sha256", args.production_sha256,
           "--api", str(args.archive / "research/artifacts/fabric-api/fabric-api-0.159.0+26.2.jar")]
    for mod in mods:
        cmd += ["--mod", str(mod)]
    (output / "command.json").write_text(json.dumps(cmd, indent=2))
    with (output / "driver.log").open("w") as log:
        result = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit(result.returncode)
    if args.mode == "concurrent":
        (output / "concurrent-verification.json").write_text(json.dumps(concurrent_activity(output / "observations.json"), indent=2))
    elif args.mode in ("cancel", "stop"):
        observations = json.loads((output / "observations.json").read_text())
        kind = "chunky-cancel" if args.mode == "cancel" else "server-stop-request"
        marks = [m for m in observations["lifetime"] if m["kind"] == kind]
        outstanding = [r for r in observations["requests"] if any(
            r["startNanos"] < m["nanos"] < r.get("endNanos", 0) for m in marks)]
        if not outstanding or observations["overflow"] or any(not r.get("endNanos") for r in observations["requests"]):
            raise AssertionError("Lifetime observation lacks outstanding-and-acknowledged native requests")
        (output / "lifetime-verification.json").write_text(json.dumps(dict(
            status="PASS_OUTSTANDING_LIFETIME_SAMPLE", kind=kind, outstanding=len(outstanding),
            issued=len(observations["requests"]), completed=len(observations["requests"])), indent=2))
    print(f"Stage 7 {args.mode} W{args.workers}: driver completed; see {output}")


if __name__ == "__main__":
    main()
