#!/usr/bin/env bash
# RC1 (D45 = D43 + gate v2.1, jar F861BE01BF9A) gate chain, 2026-10-19, main Opus.
# Same scripts and arguments as the D43 / S11DC receipts (candidate jar and output names changed only).
# Every server job goes through s11q.sh -> testsched run (x1guard waits, never bypassed). Validators are read-only.
export MSYS_NO_PATHCONV=1
export PATH="${PATH//\"/}"
A='C:/Users/Admin/Downloads/Project PHOENIUM/.audit/stage9-20261009'
S="$A/gates/scripts"
Q='C:/Users/Admin/Downloads/Project PHOENIUM/.audit/stage11-20261017/harness/s11q.sh'
JAR="$A/candidates/phoenium-stage9-d45-F861BE01BF9A.jar"
PROBE="$A/candidates/test-harness/a3-live-probe-d23-8781AFB5C5D6.jar"
SW="$S"
q() { local id=$1 cpu=$2 mem=$3 to=$4; shift 4; echo "== $(date -u +%FT%TZ) $id"; bash "$Q" --id "$id" --cwd "$SW" --class functional --cpu "$cpu" --mem "$mem" --timeout "$to" --note "RC1-GATES $id" -- "$@" | tail -1; echo "rc=$? $id"; }
cd "$S" || exit 2
sha256sum "$JAR" | grep -qi '^f861be01bf9ad313' || { echo "JAR HASH MISMATCH"; exit 2; }

# 1. Step B / engine off (default mode W1/W4, default + minWidth=0 arm), as d43-stepb
q rc1-stepb 30 16384 10800 python stepbsw9m.py "$JAR" s9d45-F861 --port 25760 --out stepB-d45 --switch=-Dphoenium.engine.minWidth=0
# 2. A3 pair (shipping config w5 dag) + strict validator
q rc1-a3-on 25 6144 3600 python a3live9e8.py --output "$A/gates/a3/rc1-d45-w5-dag-on" --candidate "$JAR" --probe "$PROBE" --engine-workers 5 --enabled true --port 25720 --source-head 0cb648b --extra=-Dphoenium.engine.schedule=dag --extra=-Dphoenium.engine.protection.check=1
q rc1-a3-off 25 6144 3600 python a3live9e8.py --output "$A/gates/a3/rc1-d45-w5-dag-off" --candidate "$JAR" --probe "$PROBE" --engine-workers 5 --enabled false --port 25721 --source-head 0cb648b --extra=-Dphoenium.engine.schedule=dag --extra=-Dphoenium.engine.protection.check=1
python -I validate_a3_9e8.py --enabled "$A/gates/a3/rc1-d45-w5-dag-on" --control "$A/gates/a3/rc1-d45-w5-dag-off" | tail -3
# 3. DET-1 w1/w2/w5 + compare
q rc1-det-w1 25 6144 1200 python envrun.py "S8_CANDIDATE=$JAR" -- python determinism9e8b.py rc1-d45-det2-w1 1 25730
q rc1-det-w2 25 6144 1200 python envrun.py "S8_CANDIDATE=$JAR" -- python determinism9e8b.py rc1-d45-det2-w2 2 25735
q rc1-det-w5 25 6144 1200 python envrun.py "S8_CANDIDATE=$JAR" -- python determinism9e8b.py rc1-d45-det2-w5 5 25745
python -I determinism9e8b.py --compare rc1-d45-det2-w1 rc1-d45-det2-w2 rc1-d45-det2-w5 | tail -6
# 4. protections, transitions r7
q rc1-prot 25 6144 1800 python envrun.py "S8_CANDIDATE=$JAR" -- python protections9e8.py rc1-d45-prot 5 25780 -Dphoenium.engine.schedule=dag -Dphoenium.engine.protection.check=1
q rc1-trans 25 6144 2400 python envrun.py "S8_CANDIDATE=$JAR" -- python transitions9e8r7.py rc1-d45-t7 5 dag 25790
# 5. save/restart + unload/reload integrity (as J4a / d42p-wgeq), then the memory cycle (as J4b)
q rc1-wgeq5 40 32768 7200 python wgeq9m.py wgeq5-d45-E16-w5-dag E16-w5-dag --c2me-all globalExecutorParallelism=1 --arm base --arm base2 "--arm" "d45|jar=$JAR" "--arm" "d45b|jar=$JAR" --arm base3
q rc1-memcycle 40 32768 7200 python memcycle9m.py mc4-E16-w5-dag-tp-4G-d45 E16-w5-dag --heap 4G --phoenium-jar "$JAR"
echo "== $(date -u +%FT%TZ) CHAIN END"
