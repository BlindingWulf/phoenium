"""Step B startup gate per kill switch (2026-10-14, lead; generalises j3stepb9m.py, which stays as the D39 J3 evidence).

The Stage 8 Step B startup gate (stepb_gate8f7.py, unchanged) on a candidate jar in Phoenium's default (engine-off) mode
at W1 and W4: once with every switch at its default (ON), then once per named kill switch turned OFF.

    python stepbsw9m.py <candidate jar> <name prefix> [--port 25760] [--out stepB-d42]
                        --switch=-Dphoenium.c2me.releaseFinishedTasks=false --switch=-Dphoenium.mem.releaseNoise=false
    (use --switch=VALUE: argparse reads a bare "-D..." value as an option)

Each OFF arm passes its switch through JAVA_TOOL_OPTIONS (the gate builds its own JVM command line). The arm's console
must show the JVM picking up exactly that value (OFF arms) or no JAVA_TOOL_OPTIONS at all (ON arms), so a switch that
never reached the server cannot pass. Arms run one after another on one port set (java P, rcon P+1, bedrock P-7000,
voice P-1500). Writes gates/<out>/<prefix>-summary.json. Exit 0 = every arm PASS, 1 = any FAIL.
TEST ONLY: offline, loopback, no players.
"""
import argparse
import json
import os
import pathlib
import subprocess
import sys

GATE = pathlib.Path(r"C:\Users\Admin\Downloads\Project PHOENIUM\.audit\stage8-f7-fix-20261008\scriptsF7\stepb_gate8f7.py")
STEPB = GATE.parent.parent / "stepB"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jar")
    ap.add_argument("prefix")
    ap.add_argument("--port", type=int, default=25760)
    ap.add_argument("--out", default="stepB-d42")
    ap.add_argument("--switch", action="append", required=True)
    a = ap.parse_args()
    out = pathlib.Path(__file__).resolve().parent.parent / a.out
    out.mkdir(parents=True, exist_ok=True)
    configs = [("on", None)] + [(s.split("=")[0].rsplit(".", 1)[-1] + "-off", s) for s in a.switch]
    results = []
    for workers in (1, 4):
        for label, switch in configs:
            name = f"{a.prefix}-w{workers}-{label}"
            env = dict(os.environ, S8_CANDIDATE=a.jar)
            env.pop("JAVA_TOOL_OPTIONS", None)
            if switch:
                env["JAVA_TOOL_OPTIONS"] = switch
            proc = subprocess.run([sys.executable, str(GATE), name, str(workers), str(a.port)], env=env,
                                  capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=1800)
            run = STEPB / name
            gate = json.loads((run / "gate-result.json").read_text(encoding="utf-8")) if (run / "gate-result.json").exists() else {}
            console = run / "console.log"
            text = console.read_text(encoding="utf-8", errors="replace") if console.exists() else ""
            picked_any = "Picked up JAVA_TOOL_OPTIONS:" in text
            switch_ok = (f"Picked up JAVA_TOOL_OPTIONS: {switch}" in text) if switch else not picked_any
            ok = bool(gate.get("pass")) and switch_ok and proc.returncode == 0
            results.append({"name": name, "workers": workers, "config": label, "switch": switch, "pass": ok,
                            "gatePass": gate.get("pass"), "switchOk": switch_ok, "rc": proc.returncode,
                            "candidateSha256": gate.get("candidateSha256"), "running": gate.get("checks", {}).get("running"),
                            "bad": gate.get("bad"), "stopRc": gate.get("stopRc"), "result": str(run / "gate-result.json"),
                            "stderrTail": proc.stderr.splitlines()[-5:]})
            print(json.dumps(results[-1]), flush=True)
    summary = {"schema": "stage9-stepb-switch-v1", "jar": a.jar, "port": a.port, "switches": a.switch, "arms": results,
               "pass": bool(results) and all(r["pass"] for r in results)}
    (out / f"{a.prefix}-summary.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    print("PASS" if summary["pass"] else "FAIL")
    return 0 if summary["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
