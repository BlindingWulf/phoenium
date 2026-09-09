# Stage 7 first repair: Chunky asynchronous FULL demand

Finite local pass, 2026-09-09. **Scoped repair: PASS. Chunky compatibility remains PARTIAL.**
Stage 6 remains **AUDIT PARTIAL**; Stage 7 is **IN PROGRESS**; Stage 8+ is **NOT STARTED**.
This closes only the first asynchronous-FULL repair. No next mod, maintained fork,
remote publication, goal tracker, automation or automatic continuation was started.

## Control and preservation

The clean starting checkout was `045834b4bf4208b193523d8d360d2d0288625050` on
`stage6/bounded-compatibility-audit`. Development used `stage7/chunky-async-full-repair`.
The Stage 5 freeze still resolves to `01909eafad8ae95b83f04c7d9bd52ce0facd3cb1`;
its production SHA-256 remains
`59BB31DC058351C7F4BC9DB572FEC1DE2B298FF623614FC4CB3EAFAEB5A772D5`.

Before any build output replacement, the complete audit root was copied and verified:

- Old root: `build/stage6-audit-20260909/`.
- Durable root: `run/stage6/frozen-audit-045834b/`.
- 10,477 files / 9,635,972,942 bytes; all source/copy hashes matched.
- All 604 historical indexed entries matched; no indexed file was missing or altered.
- All 9,873 unindexed files were also copied and individually hashed. Their names,
  relative paths and hashes are recorded in `run/stage7/repair-20260909/preservation.json`.
- The historical manifest and index were copied unchanged beside that receipt.
  Original logs, failed runs, worlds, artifacts and research remain present at both roots.

Minecraft 26.2, Java 25, Loader 0.19.5, Loom 1.17.20, Gradle 9.5.1 and 5/5 distances
were retained. Every matched run uses the full Fabric API bundle, SHA-256
`3F3A5D96E6A8F554A72E71FB507D6A979CA16D9190C305CEB1300F1D01E733EE`.
This is the Stage 6 selection with 43 nested modules, distinct from the frozen
Stage 5 three-module runtime. Chunky 1.5.3 is unchanged, SHA-256
`ECDFC55A0F67F3EC6F40850687615C941AE226BD88F4E06188A7B268FD3DA942`.
The preserved Fabric+Chunky 81/81 save/restart control was reused.

## Initiating failure and remedy

The failure occurred during live FULL finishing, before shutdown. In the fresh
diagnostic reproduction, the first recorded shared-barrier cause was FULL for
chunk `(10,1)`, holder activation 564 / work sequence 1, under fixture owner B,
topology generation 4. Its receipt contained an `ABSENT` `ENVIRONMENT` target at
`(0,0)`, holder present but ticket level 39, routed to fixture owner A.
`phoenium diagnostics` exposed this cause while the server was still running.
The later shutdown exception reused that cause; it did not initiate the stall.
This run reached 63/81 rather than the historical 36/81, reflecting completion
ordering. Neither partial count was used as a timing or correctness result.

The locked Chunky bytecode shows the legitimate producer path: its external
`FabricWorld.getChunkAtAsync` invocation schedules onto Minecraft's server,
adds its ordinary native ticket, runs distance updates, and invokes the original
`getChunkFutureMainThread(..., FULL, ...)` path. The exact winning holder/step
then enters Phoenium's preparation, finishing and separate publication graph.
The bytecode and live failure are retained in `chunky-fabricworld-bytecode.txt`
and `diagnostic-w1/first.log` under the Stage 7 evidence root.

Discovery, context enclosure and admission already excluded absent chunk inputs
from the mutable-data footprint. `requireFinishingAuthority` contradicted them
by checking every non-null target route. It therefore rejected a validated absence
as though the unavailable chunk itself required ownership. `ChunkStageAdmission`
captured the exception in the shared failure barrier; subsequent work stopped.

`Target.requiresRegionalAuthority()` now expresses one rule used by footprint
construction, readiness, missing-binding diagnostics and the final claim check.
Present chunk data and spatial entity context still require the current enclosing
route, including its owner epoch. Absent create=false data does not grant a chunk
capability: its exact holder, ticket, slots, receipt, source work and routing snapshot
remain validated, and the original query still returns its original absent result.
The exception retains detailed source/work/target/route diagnostics.

No preparation, finishing body, publication, acknowledgement, ticket policy,
lease expansion, scheduler or ownership registry was replaced. No exception is
caught and ignored, no chunk or successful future is fabricated, and no permanent
world owner or second ownership map is added. Existing quiescent convergence is
still required when an input becomes present. Chunky production code and JAR
remain unchanged; the solution is a narrow Phoenium core correction.

## Verification

The final production artifact is `run/stage7/repair-20260909/final-artifacts/phoenium-0.2.0-stage2.jar`,
SHA-256 **`51A0DAF540A163C44D18FAE75EA5FCD418DBCC8841144F943DC388864AB167EA`**.
All final installed cases and restarts verify that candidate's identity before
launch. Its server-only JAR contains no client, test or observation helpers.
The original Stage 4/5 gameplay probe remains byte-identical to the frozen probe
(`DC1FA87FA00CE6EBFF8E25BEF77617BF3FA856B6BD15E0589A24D8AA5C260ED6`).
The separate Stage 7 observation probe is
`FE9090B08D1D3A2D7AFCA07E7E6AF09B301ABF6F182F6C167593A9E473D3D5D9`.

- **Red/green:** both generation and reload variants of
  `asynchronousFullCompletesWithAnAbsentInputRoutedToAnotherOwner` fail against
  the exact unmodified old runtime with the enclosure barrier. The same tests
  pass with the correction through original FULL subscriber completion, actual
  result identity, finishing effects and separate publication acknowledgement.
- **Changing evidence:** `newlyEligibleAbsentInputInvalidatesItsReceiptAndConvergesBeforeCompletion`
  proves that an eligibility change invalidates the absence receipt and requires
  real topology convergence before successful finishing. Existing route change,
  stale holder/delivery, shared dependency, cancellation, reentrant stop and
  retention tests also pass.
- **Consolidated build:** `gradlew.bat build stage4AcceptanceHarness stage7ObservationHarness --rerun-tasks --no-daemon`
  passed once on the final candidate: **837 tests / 88 suites / 0 failures, errors or skips**.
  This freshly reruns the previous 834-test baseline plus three added cases.
- **Original workload:** `final-original-w1` and `final-original-w4` both complete
  the unchanged square centered at `(160,0)`, radius 64: **81/81**. These cases
  install only the candidate, locked full API and locked Chunky, without probes.
- **Persistence:** all 81 requested native records are decoded and checked for
  their exact chunk positions, FULL status, light flag and nonempty sections
  **before restart**. After native restart, all 81 are actually loaded and checked
  again after normal save/stop. The original lamp-on/persisted/lamp-off checks also
  pass. No whole-world byte-equality assertion is substituted for those checks.
- **Ordinary simultaneous activity:** `final-concurrent-w1` records **54** and
  `final-concurrent-w4` records **57** original furnace bodies with increasing
  cooking/output state while the same real Chunky request is outstanding at both
  body entry and return. Both record **16** owned native redstone writes from a
  scheduled repeater across the circuit's chunk boundary during that interval.
  The comparator checks timestamps against unchanged upstream future lifetimes;
  it supplies no chunks, tickets, generation completion or gameplay effects.
- **Actual parallelism limits:** W4 really configures four gameplay workers, but
  these normal-terrain sample domains converge to one current owner. The evidence
  demonstrates gameplay progress during outstanding generation, not independent
  owner-body overlap or a four-worker speedup. The historical Stage 6 sequential
  redstone phase is not relabeled as simultaneous evidence.
- **Lifetime:** W1/W4 cancellation and W1/W4 stop-request samples each observe
  **50 real requests outstanding** at the actual cancel/stop call. All 50 settle;
  ordinary native save/shutdown completes with actual child exit 0 and zero retained
  protocol work. The stop cases omit a preceding flush specifically so the stop
  request happens while generation remains outstanding; Minecraft performs its
  own ordinary save. These cases are separate from 81/81 completion acceptance.
- **Native demand without Chunky:** final W1/W4 matched full-API native-generation,
  signal and save/restart controls use the same candidate; their final receipts
  are retained under `final-native-w1` / `final-native-w4`.

Final healthy runs require unique STOPPED receipts and zero leases, bypass,
fallback, ownership violations, unleased work and barriers. FULL graphs,
generation/structure holders, returned-future indexes, input plans/edges/pins,
notifications, published records and save acknowledgements settle to zero.
Legitimate resident holders before shutdown are not classified as leaks.

## Harness corrections and preserved failures

The concurrency fixture places existing furnaces and a circuit west of the
pregeneration area. Its native FULL/POI dependency halo originally escaped the
old origin-centered test declarations at `(-24,-12)`. A diagnostic cut identified
the precise `POI_INSTALL` unroutable work. The test-only startup declaration now
derives radius **14** from the actual FULL-to-EMPTY generation radius, forced-ticket
level and one-chunk workload halo. It adds 29 previously missing explicit routes
before startup; it neither generates terrain nor expands any active lease.
An initial RETURN injection was bypassed by the existing fixture's cancellable
RETURN injection; an outer wrapper now preserves both startup declarations.

The failed boots, watchdog exits from malformed fixture declarations, thread
dumps, and two rejected redstone observation attempts remain in the evidence root.
Direct console block changes did not demonstrate owned regional tick bodies, so
the final fixture uses an actual scheduled repeater. The comparator's ownership
and outstanding-demand requirements were retained. No active server was forcibly
killed by the agent; watchdog exits are failed runs, not successful shutdowns.

The simultaneous samples process the full 81-position selection. The upstream
adapter issues 78/79 asynchronous requests because ordinary existing-world work
has already produced some records; those are distinguished in the observation
files. The separate original-workload runs establish the repair without that
gameplay preparation. No target terrain is filled or generated by the observer.

## Reproduction and remaining scope

Run `scripts/run_stage7_chunky.py` with a **new** `--output`, `--workers 1` or `4`,
the final `--production` and `--production-sha256`, and `--mode original`,
`concurrent`, `cancel` or `stop`. Concurrent/lifetime modes also accept explicit
`--probe` and `--gameplay-probe` paths. Each run saves its exact argv, plan,
installed inventory, raw logs, native snapshots and independent verification.
The final batch command arrays are in `run/stage7/repair-20260909/final-batch.json`.
The [Stage 7 evidence index](STAGE7_CHUNKY_EVIDENCE_INDEX.json) pins retained receipts.

Start was **2026-09-09 04:29:19 UTC**. Current session metadata was checked once
and explicitly reported **gpt-6-astra / high**; no helper was used. Backend model
fingerprints are not independently exposed. Initial account-wide used allowance
was 69% (reserve 7%); the requested early-finalization threshold was 79%.
Final time/allowance and commit identity are in the final verification receipt
and delivery message. No per-task token accounting is claimed.

Broader Chunky feature acceptance, sustained independent gameplay parallelism,
larger pregeneration/lifecycle stress, other mods, old Java clients, Bedrock and
audio remain outside this repair's evidence. The next action, if separately
requested, is broader Chunky acceptance with independently active gameplay domains.
This pass stops here and does not promote Chunky to a full `PASS_*` compatibility
category or complete any other Stage 7 backlog row.
