# Stage 7 C2ME settlement and reviewed send-ready acceptance

**C2ME_UPSTREAM_SETTLEMENT RESOLVED — C2ME_LIFETIME ACCEPTED — RETURN TO LUNA**

Final acceptance: 2026-09-13. The native settlement patch and explicitly approved
Phoenium DISK lifecycle and SEND_READY dependency repairs passed the complete
literal W1/W4 matrix, including the full selected overlapping Stage 7 stack.
All 12 actual-production first/restart pairs pass, with all 24 server processes
exited. Production C2ME exclusions were removed only after candidate acceptance;
the final matrix uses the actual production JAR directly.

* Phoenium implementation commit: `916904794590433ccac1777c24ffa023b7af4338`.
* Branch: `stage7/scalablelux-persisted-light-repair`.
* Production `build/libs/phoenium-0.2.0-stage2.jar`: **1,433,247 bytes**,
  SHA-256 `CFDF13479E1073E1AB270C1FD447CF1A991A6DFABF3D4F5924D74560F7ADAAC6`.
* Required patched C2ME: **settlement2**, SHA-256 `8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53`.
* Full Phoenium regression: **953 tests / 108 XML suites / 0 failures / 0 errors /
  0 skipped**. Native full suite: **19 tests / 4 suites**, all pass; all **11**
  settlement contract tests also pass against the packaged patched provider.

The [final settlement report](STAGE7_C2ME_SETTLEMENT_REPORT.md) records the exact
composition, per-run positive and terminal counts, regression/build commands,
source provenance, process audit and hypothesis history. The
[provider patch manifest](STAGE7_C2ME_PROVIDER_PATCH_MANIFEST.md) pins native
commits, build inputs and output bytes. This supersedes the original blocked
disposition and old continuation instructions preserved below. The unchanged
characterization test still demonstrates the defect in the original C2ME JAR;
its pass does not accept that original JAR. The production binding requires the
distinct settlement2 base version. No remote push was made.


## Scope and authority

The mission started at Phoenium `31fea7d45b6f161fa3897d894cb016d89e1dcd79`
with intentionally dirty diagnostics. Recovery HEAD, branch, staged/tracked diffs,
untracked inventory, process state and original production bytes are preserved.
The original production SHA was
`8A1C0C4852150C8D36E9EDB8DDEFC9F9B6E9A3E82AE71123D798416DD39A21FC`
(1,403,887 bytes). No reset, clean, stash, branch switch or remote push occurred.

The user explicitly approved the reviewed Phoenium lifecycle binding after the
native tests passed, then superseded the native-only handoff choice with
“Extend to reviewed send-ready repai”. This authorizes the additional finite
SEND_READY dependency repair demonstrated necessary by default Lithium.
The native provider still owns shared requester settlement. The withdrawn v3
candidate and its failed receipts remain historical; they were never substituted
for acceptance of the new reviewed bytes.

Native source root:
`C:/Users/Admin/Downloads/Project PHOENIUM/C2ME-locked-settlement`.
FlowSched commit `f5634c0a64b952da9e2961fa724da136b51585cf` and C2ME root commit
`ec85f1081e6826a536e01f668dc7d0ae2e68daaf` are local. Phoenium implementation
commit `916904794590433ccac1777c24ffa023b7af4338` was created only after actual-production acceptance. The
following documentation commit records this handoff; no binary/run directory is
committed. Both native roots and all ignored evidence remain on disk.

## Exact installed composition

Minecraft **26.2**, Fabric Loader **0.19.5**, Temurin **25.0.4.1+1-LTS**.
All 12 final pairs used normal terrain, seed **444444**, world
`stage6-disposable`, literal worker count **1** or **4**, and port 25681 or 25684.
Each pair saves, normally stops, and restarts the same existing world.

| Provider | Version/profile | SHA-256 |
| --- | --- | --- |
| C2ME patched | 0.4.2-alpha.0.43-phoenium-settlement2+26.2 | `8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53` |
| ScalableLux | 0.3.0-alpha.0.3+26.2 | `8D76CF27589DCC8E16C34BA0709A9EE2134CFCD60B90862F8CBD204EA9B6C7C8` |
| Fabric API | 0.159.0+26.2 | `3F3A5D96E6A8F554A72E71FB507D6A979CA16D9190C305CEB1300F1D01E733EE` |
| Chunky | 1.5.3 | `ECDFC55A0F67F3EC6F40850687615C941AE226BD88F4E06188A7B268FD3DA942` |
| Lithium | default 0.25.3+mc26.2 | `FDDE92E238E8075F89AD7F701F2A3D5854AF88BA9A67657184A4407B104AC563` |
| ServerCore | 1.5.19+26.2, accepted preservation profile | `4F7C9FB3B35B364D8F6500D04F0D5481656F617A83BC69742B47F39ADFF59666` |
| Clumps | 26.2.1 | `DC9544190ABFB65A3C9DDC5DAD1523C9DFC5A732344F528D908B950894398E8C` |

ScalableLux supplies `starlight`; there is no separate Starlight JAR. ServerCore
uses its existing preservation profile, with optional activation/dynamic/breeding
optimizations disabled. Every final mode includes this full selected overlapping
stack plus the separate test observer JAR. The observer captures native action
and requester identities; it never completes provider futures or invents demand.
Production contains no observer/probe/test classes.

## Repair and contract verification

The [pre-edit native architecture](STAGE7_C2ME_PROVIDER_SETTLEMENT_ARCHITECTURE.md)
identifies ItemHolder's actual requested slots and MARK_BROKEN continuation.
The provider validates action/target/slot, retains the original failure, publishes
prefailed affected slots under the holder monitor, then signals old requesters
and dependency callbacks outside that monitor. Later requests receive the same
cause. Unload cannot overwrite it, and a legitimate replacement holder may work.
The nullable ticking-chunk getter now returns null for unavailable futures while
explicit requesters retain the error. See the exact patches and native tests in
[the manifest](STAGE7_C2ME_PROVIDER_PATCH_MANIFEST.md).

The [reviewed DISK binding](STAGE7_C2ME_REVIEWED_BINDING.md) holds native operation
identity through physical completion, uses token CAS before live effects, and
acknowledges cancellation through the native error channel. Post-effect failures
cannot retry or fall back into success. Provider errors propagate after lease
release without falsely failing the shared regional scheduler.

The [reviewed SEND_READY binding](STAGE7_C2ME_SEND_READY_REVIEW.md) discovers the
finite six-neighbor chunk footprint of the original native removal list before
owner admission. It pins only existing FULL demand, awaits native readiness
asynchronously on the original provider scheduler, converges that finite owner
closure, revalidates identities and cancellation, then executes the retained body
once. All pins close on terminal paths; committed work retains them until exit.
It does not promote absent/LIGHT-only demand, wait across owners, broaden locks,
disable default Lithium, replay effects or seize native future authority.

The unchanged original characterization still passes against the original locked
binary, proving its old unsupported behavior. Separate provider contract tests
started red (7 tests, 6 failed), then expanded to 11 and passed both source and
the actual packaged settlement2 base. Native full regression: **19 / 4 / 0 / 0 /
0** (tests/suites/failures/errors/skipped). The full Phoenium regression includes
native getter behavior, DISK token/Rx ordering, concurrent FULL reservation and
eight SEND_READY cases: **953 / 108 / 0 / 0 / 0**.

## Final production matrix

Evidence root, relative to Phoenium:
`run/stage8/entry-20260913/astra-c2me-provider-settlement/`.
Each row is `production-final-<mode>-w<workers>/`, with first/restart logs and
reports, installed hashes, plan, command/source manifest, MCA persistence snapshots,
native observations and settlement summary. All **12 pairs / 24 phases** pass
with exit **0**, positive installed read execution and clean process exit.

| Mode | Workers | Read registrations / applications | DISK applied / cancelled / failed | Native DISK success / error | Native FULL success / error | Restart seconds |
| --- | --- | --- | --- | --- | --- | --- |
| coinstall | W1 | 1015 / 1015 | 1225 / 0 / 0 | 1225 / 0 | 95 / 0 | 19.157 |
| failure | W1 | 25 / 10 | 10 / 14 / 1 | 10 / 15 | 0 / 25 | 8.047 |
| cancel | W1 | 120 / 119 | 119 / 1 / 0 | 120 / 0 | 38 / 0 | 22.906 |
| replacement | W1 | 144 / 143 | 143 / 0 / 1 | 143 / 1 | 36 / 25 | 8.765 |
| stop | W1 | 119 / 119 | 119 / 0 / 0 | 119 / 0 | 38 / 3 | 22.375 |
| cancel-stop | W1 | 120 / 119 | 119 / 1 / 0 | 120 / 0 | 38 / 0 | 22.437 |
| coinstall | W4 | 1015 / 1015 | 1225 / 0 / 0 | 1225 / 0 | 95 / 0 | 19.797 |
| failure | W4 | 25 / 10 | 10 / 14 / 1 | 10 / 15 | 0 / 25 | 8.032 |
| cancel | W4 | 120 / 119 | 119 / 1 / 0 | 120 / 0 | 44 / 0 | 23.313 |
| replacement | W4 | 144 / 141 | 141 / 2 / 1 | 141 / 3 | 34 / 25 | 8.703 |
| stop | W4 | 119 / 119 | 119 / 0 / 0 | 119 / 0 | 36 / 0 | 22.563 |
| cancel-stop | W4 | 120 / 119 | 119 / 1 / 0 | 120 / 0 | 46 / 0 | 22.625 |

Counts above are final restart receipts. Native requester counts are observations
attached at each actual DISK action, not counts of distinct promises. A cancelled
action can share a requested slot later satisfied by a fresh action. The verifier
checks exact action context/token starts and terminals, no duplicates or missing
terminal, and no successful terminal for a successfully cancelled action. Every
observed requested DISK/FULL slot has a terminal receipt. Native ERROR also includes
legitimate cancellation/unload of other demand; it is not an extra injected failure.

Every failure/replacement run injects exactly one failure *inside* the native task
drain. DISK and dependent FULL retain the native aggregate containing the original
injected Throwable as suppressed, with `holderLock=false` callbacks. Later requests
log `done=true exceptional=true sameCause=true`. Replacement additionally proves
`oldRetired=true oldCausePreserved=true` and successful new-holder loading. Failure
runs finish in about eight seconds including startup, without a watchdog hang.

Each co-install run saves/reloads all **81** Chunky chunks (x=6..14, z=-4..4),
verifies lamp persisted ON and then OFF, and executes nontrivial two/three-chunk
SEND_READY footprints. Cancellation/cancel-stop show a matching native cancelled
token and Phoenium CANCELLED. In-flight stop includes a positive held-read receipt
and external halt request. Native formal stop may wait for an already admitted
read to apply; that is bounded settlement before formal stop, not a claim of a
positive SHUTDOWN terminal. Explicit shutdown arbitration is separately unit tested.

At both phase ends: zero leases, barriers, duplicates, fallbacks, ownership
violations, unleased execution, retained graphs, DISK actions, private reads,
light/POI/work/custody entries, SEND_READY retained inputs and native pins.
SEND_READY prepared inputs equal closed inputs. The machine matrix records all
generation registration/application/control/failure counters as well as the
DISK counters above; zero residue is backed by the positive workloads.

`final-process-state.json` checks all **24** recorded server PIDs: none alive.
`final-project-processes.json` records an empty matching project Java/Python process
inventory. `final-artifact-source-audit.json` verifies installed production hashes
in all 12 roots and exact current src/main bytes against every final run manifest.
Build-time HEAD was the recovery commit plus the recorded source manifest;
commit `916904794590433ccac1777c24ffa023b7af4338` preserves those tested source bytes.

## Gate ordering, build and reproduction

The latest gated source JAR was SHA-256
`A2506F5BA80CF4DB9D2031F219BDB4F8F4662F4DC1530D2EF7020097FA5305C2`.
Its disposable no-break candidate was
`8C7A665156F331781387D212A9F35190DC6F2C7040992335538059447D5761D9`;
`stack-send3-verified-matrix.json` accepts all 12 candidate pairs. Then:

```powershell
.\gradlew.bat test --rerun-tasks --no-daemon
```

passed with the fresh totals above (`post-acceptance-regression.log`,
`post-acceptance-test-totals.json`). Only then were both production exclusions
removed, followed by:

```powershell
.\gradlew.bat jar --rerun-tasks --no-daemon
```

`final-production-build.log` records success. The final production SHA is `CFDF13479E1073E1AB270C1FD447CF1A991A6DFABF3D4F5924D74560F7ADAAC6`.
`production-final-verified-matrix.json` accepts all 12 runs of these actual bytes.
The runner's quoted `'@production'` argument directly installs build/libs output
and performs no candidate conversion. Example for a fresh, nonexisting evidence
label from the repository (do not overwrite accepted roots):

```powershell
python scripts/run_c2me_settlement_trial.py review-coinstall 1 scripts/stage8-plans/c2me-settlement-coinstall-w1.json '@production' CFDF13479E1073E1AB270C1FD447CF1A991A6DFABF3D4F5924D74560F7ADAAC6
python scripts/verify_c2me_settlement_matrix.py production-final
```

All six accepted plans at W1/W4 are retained under
`scripts/stage8-plans/c2me-settlement-*.json`. Older `c2me-exact-*` plans preserve
historical diagnostics and must not be mistaken for the final accepted matrix.
The runner fixes the patched-provider hash, asserts extra mod hashes, records the
exact command/worker/source manifest, and rejects unexpected production bytes.
No test/probe class is packaged; the version guard requires settlement2. Original
C2ME and arbitrary newer releases are not accepted by this result.

## Failed hypotheses and review consequences

All named roots/logs remain under the evidence root; none was converted to a pass.

| Trial / hypothesis | Result and architectural consequence |
| --- | --- |
| Original native contract | MARK_BROKEN retires action/busy but leaves requesters pending. Red contract tests required native settlement, retaining the original characterization. |
| `failure-current-w1`, settlement1 with baseline binding | Native retries/fallback completed DISK and later shutdown failed higher statuses. Exit 0 was not decisive failure acceptance; review the explicit lifecycle binding. |
| Initial `persistence-w1/w4` | Readiness marker contained digits rejected by runner polling syntax. Corrected only the fixture marker, preserving timeouts and failed evidence. |
| `failure-bound-w1` | Reserved FULL turn collided with server queue polling. Re-enqueue the same task through the existing scheduler; no blocking or replay. |
| `failure-bound-w4` | Native requester settlement worked but provider error tripped the regional scheduler. Capture error inside leased native body and propagate after release. |
| `failure-task-w1/w4` | True task failure settled requesters but native nullable getter threw during ticking/stop. Rechecked native availability semantics and added the single settlement2 getter fix plus binary tests. |
| Native availability test fixture | Initial fixture lacked Minecraft bootstrap. Fixed bootstrap/classloader lifecycle; this was not a provider change. |
| `coinstall-native2-w1/w4` | Default Lithium requested neighboring FULL in source-only SEND_READY ownership. User approved separate review; derive the finite native removal footprint and await outside owner admission. |
| `coinstall-send1-w1/w4` and send2 review | Initial co-install passed, but review found shutdown could release committed pins. Retain pins until physical body exit; later refined preflight/no-Lithium cancellation acknowledgement into send3. |
| `stack-send2-coinstall-w1` | Parallel candidate creation raced before server launch. Publish complete immutable candidate atomically; preserved failure and used a new `stack-send2b-coinstall-w1` root. |
| `production-coinstall-w1/w4` command attempt | Unquoted @production was PowerShell splatting, causing missing argument before server launch. Quoted it and used new `production-final-*` roots. |

Native2 DISK witnesses, send1/send2 preliminary passes and all earlier v3 evidence
are historical, not substitutes for final send3 and production-byte acceptance.
After failures the affected lifecycle/availability/ownership model was reviewed;
no timeout inflation, disabled provider or reduced-stack acceptance was used.

## Handoff

WORK CARD: **PANDA_A2_LIVE**. No Panda work began. Formal combined-stack Stage 8
acceptance, separate projectile caps, A3 and external gameplay facilities remain
outside this result. Local commits and clean source roots preserve the repair;
large worlds, logs and observer binaries remain local ignored evidence.

Exact short continuation for Luna:

> Continue PANDA_A2_LIVE from docs/STAGE8_ENTRY_CURRENT_WORK.md. Preserve the
> accepted C2ME settlement2 composition and evidence; do not reopen the resolved
> C2ME gate or start A3 or formal Stage 8 acceptance.
