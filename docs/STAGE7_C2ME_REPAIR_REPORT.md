# Stage 7 C2ME tick-phase integration

Finite local pass, 2026-09-09. **Tick-phase transformation repair: PASS within the
reviewed instruction semantics. Installed C2ME integration: BLOCKED. Overall C2ME
compatibility: BLOCKED.** The final artifact deliberately rejects C2ME before world
creation. It must not be presented as a working C2ME integration.

Stage 6 is **AUDIT PARTIAL**; Stage 7 is **IN PROGRESS**; Stage 8+ is **NOT STARTED**.
The substantially larger replacement chunk-system integration identified below is
unfinished. No upstream fork, release, push, external issue, persistent objective,
automatic continuation, additional optimizer or independent-domain campaign was used.

## Baseline and artifacts

The clean baseline was `f12fd22faf31460952eccf7d9268fb0db278acb6` on
`stage7/chunky-async-full-repair`. Work branched to
`stage7/c2me-tick-phase-integration`. The repaired baseline JAR hash verified as
`51A0DAF540A163C44D18FAE75EA5FCD418DBCC8841144F943DC388864AB167EA`.
The immutable Stage 5 freeze still resolves to
`01909eafad8ae95b83f04c7d9bd52ce0facd3cb1`, with its unchanged JAR hash
`59BB31DC058351C7F4BC9DB572FEC1DE2B298FF623614FC4CB3EAFAEB5A772D5`.

The prior preservation receipt maps `build/stage6-audit-20260909/` to
`run/stage6/frozen-audit-045834b/` (10,477 previously verified files). That durable
root was reused without recopying the archive. New raw evidence is under
`run/stage7/c2me-20260909/`, outside clean-build outputs. Neither historical control
nor the Stage 7 Chunky artifacts were overwritten.

The final server-only JAR is
`run/stage7/c2me-20260909/final-artifacts-v2/phoenium-0.2.0-stage2.jar`, SHA-256
**`6E87986E8BAD90C0F8E7F4636392C55BA38CFFA7CCE45B4EA5BB30C576F547B2`**.
The manifest beside it records all observation/probe hashes and deterministic totals.
The original gameplay probe remains `DC1FA87FA00CE6EBFF8E25BEF77617BF3FA856B6BD15E0589A24D8AA5C260ED6`;
the existing Chunky observer remains `FE9090B08D1D3A2D7AFCA07E7E6AF09B301ABF6F182F6C167593A9E473D3D5D9`.
Client/probe/test classes are excluded from the production JAR.

Minecraft 26.2, Java 25.0.4.1, Loader 0.19.5, Fabric API 0.159.0+26.2, Loom
1.17.20 and Gradle 9.5.1 are unchanged. Installed runs use the full audited API
bundle (`3F3A5D96E6A8F554A72E71FB507D6A979CA16D9190C305CEB1300F1D01E733EE`),
with 43 nested modules and Loader's ordinary server-side module filtering recorded
in the raw logs, and 5/5 distances. C2ME is the unchanged
`c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar`, version `0.4.2-alpha.0.43+26.2`, hash
`B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`.
Release-to-source revision mapping remains **UNKNOWN**. Locked binary behavior is
the authority; no newer release or guessed upstream revision was substituted.
The diagnostic effective configuration keeps chunk-system replacement, no-tick view
distance, asynchronous serialization and replacement I/O enabled at their generated
defaults. Delayed FULL events and the experimental reduced-allocation serializer
remain off. Generated `c2me.toml` hashes, complete loaded-module lists and the two
actual C2ME worker threads are retained; no substantive module was disabled in the
W1/W4 failure reproductions.

## Reproduced phase failure and narrow remedy

`baseline-w1` installs the exact repaired baseline, full API and locked C2ME. It
reproduces `Pinned tick phase drift: distance-update result consumption changed`.
The actual failed target class, its disassembly, final installed class exports and
the locked nested C2ME disassemblies are retained in the evidence root.

The transformed cache tick contains:

1. The original thread-safety callback and conditional ticket purge.
2. One call to `runDistanceManagerUpdates()Z`.
3. `ALOAD 0`, `ACONST_NULL`, and C2ME's `updateHolderMapAfterTick(CallbackInfo)V`.
4. The original `POP` consuming the distance-update boolean.
5. The original gameplay/tracking/maintenance progression.

The callback does **not** consume the producer's result. That boolean remains on
the operand stack beneath the callback arguments. The callback obtains `chunkMap`,
casts to `IThreadedAnvilChunkStorage`, invokes `invokeUpdateHolderMap()Z`, and
discards that separate result. The holder-map effect must occur before readiness.

The final installed producer also contains C2ME's schedule-consolidation wrapper
around distance updates and its 50 ms holder-map promotion throttle. Consolidation
drains its queued work on both normal and exceptional return. The original result
variables still control cache invalidation and the producer's returned boolean.
The inserted unconditional per-tick holder-map callback compensates for throttled
task-time promotion. None of these producers, wrappers, callbacks or return values
are replaced by the Phoenium repair.

`LiveTickPhaseBoundary` now recognizes the complete reviewed sequence and puts it
entirely in the readiness prefix. It checks the callback invocation, receiver,
descriptor, uniqueness, complete six-instruction body, both result consumers and
absence of added synchronization/exception control. An unstable generated Mixin
prefix or mod presence alone is insufficient. The no-C2ME immediate-POP path and
existing jump/local/phase-order guards remain intact.

Executable regression tests run the real pinned Minecraft producer instruction
body and both original/copied result-consumption slices against observable
collaborators. They cover all eight combinations of distance-update, ordinary
promotion and callback-promotion results; verify the producer's boolean and
conditional cache-clear behavior; and require ordered, once-only effects. A
publication exception escapes with original identity after the preceding effects,
without replay. Unknown callbacks in this result-consumption sequence, extra effects, added locking, changed publication
targets and altered consumers are rejected. These fixture executions are phase
semantics tests, not proof of installed chunk ownership.

The positive test fails against the exact old boundary class compiled separately
from `f12fd22` (`red-results`), at the original immediate-POP assertion, and passes
with the repair. The complete final deterministic suite is **840 tests / 89 suites /
0 failures, errors or skips**. The full build and all three observation/probe
packaging tasks passed. A subsequent metadata gate correction required another
build; its complete 840-test run also passed. Failed/earlier build receipts remain.

## What beyond-startup validation exposed

The phase-only candidate reaches actual installed tick execution. W1
`phase-native-w1` and W4 `early-native-w4-v3` then fail on the unchanged native
terrain workload after the first 11-chunk forceload command. Both are failures,
despite child exit zero. Neither completed the requested workload or actual disk
restart; normal terminal save/shutdown acknowledgement was absent after heap
exhaustion. No server was forcibly killed. The driver requested stop, recorded the
original process exits and retained worlds, logs and thread dumps.

An early W4 sample identifies the initiating defect. `LiveTickReadiness` repeatedly
compares lists containing `getTickingChunkFuture()` by future identity. C2ME's
`NewChunkHolderVanillaInterface` creates a new wrapper on every call. Thus the
managed-block predicate sees a change immediately, retries without pumping the
required server tasks and accumulates wrappers attached to outstanding native
status futures. The early heap histogram contains **12,037,983 `UniHandle`
callbacks**, matching wrapper lambdas, and about 12 million futures. Two early
thread dumps show the server inside this readiness loop. A later C2ME worker waits
at Phoenium's guarded world-generation moon/environment request; that wait is
downstream evidence, not permission for a generation thread to own live state.

Read-only instrumentation proves that the replacement system executes. The retained
W4 snapshot records 3,148 typed generation-delegate entries and 2,205 `ChunkStep.apply`
entries (2,204 returns at that snapshot), without any recorded entry through the
instrumented vanilla holder/ChunkMap `applyStep` paths. Both distance-result branches
execute (23 true / 5,602 false), and 10 holder-map callback promotions enter and
return. Counts include bootstrap and the failed demand interval; generic bridge
overloads are recorded separately and are not added to typed method counts.
Generation bodies run without gameplay ownership, as expected for computation.
Their execution does not establish owned live application.

The locked binary reveals additional replacements:

- `VanillaWorldGenerationDelegate` calls `ChunkStep.apply` directly and publishes
  its `ChunkState`, bypassing the vanilla holder/ChunkMap admission interception.
- `ServerAccessible` creates/converts FULL chunks itself, changes the holder's
  published state and invokes post-load, block-entity/tick-container and Fabric
  lifecycle events through its own methods. The old FULL supplier cut is not its
  semantic application boundary.
- `ReadFromDisk`/`ReadFromDiskAsync` provide loading and unload/save paths, including
  direct `SerializableChunkData.copyOf` and storage completion, outside the reviewed
  `ChunkMap.save` selection/snapshot/acknowledgement path.

Therefore fixing wrapper churn alone would expose a substantially larger ownership,
retained-input, publication and save-lifetime integration. Empty Phoenium counters
would not prove those replacement paths safe. This pass preserves the phase repair
and rejects the unsupported installation instead of advertising it as usable.

## Safe installed boundary and unfinished dependency

The final metadata declares incompatibility with both top-level `c2me` and the
standalone `c2me-rewrites-chunk-system` module. A first attempted gate against only
the nested module was **rejected by verification**: Loader silently omitted that
optional module (and no-tick view distance) while loading the reduced C2ME set.
That run is retained under `final-blocked-c2me-w1`; it is not C2ME acceptance.
The corrected gate rejects the top-level installation before world creation.
No production bypass property or disabled-substantive-module profile is provided.

The smallest next integration proposal is a direct adapter for this locked
replacement system, leaving upstream unchanged:

1. Retain one public wrapper per stable C2ME status slot and validate the underlying
   holder/status-future incarnation under C2ME's actual status-transition contract.
   Mere holder-list equality or dropping future validation is insufficient.
2. Bridge C2ME's direct generation/status completion to Phoenium's existing
   admission, retained-input and completion protocol while preserving asynchronous
   computation. Bind the actual C2ME holder/status transition, not a fabricated
   vanilla application event.
3. Split its real FULL conversion/publication/lifecycle effects into preparation,
   owned finishing and acknowledged publication, preserving its original event
   order and completion frequency. Do not replay already committed effects.
4. Bind its read/POI/post-load roots, downgrade/cancel paths and asynchronous
   snapshot/write completions to existing retention and save acknowledgements.
   Validate normal stop and failure with outstanding work before removing the gate.

This is an explicit unfinished provider integration, not a request to redesign the
general compatibility framework or create a maintained fork. C2ME-enabled real
client, completed native generation/reload, cancellation and healthy outstanding-stop
acceptance remain unexecuted/blocked. ScalableLux remains independently blocked;
full Chunky+C2ME acceptance and independent-domain speedup remain later work.

## Final regression receipts

Final installed outcomes and exact commands are indexed in
`STAGE7_C2ME_EVIDENCE_INDEX.json`. All final cases use the single recorded final JAR.

| Final case | W1 | W4 | Scope and positive evidence |
| --- | --- | --- | --- |
| C2ME safety gate | PASS explicit rejection | PASS explicit rejection | Loader rejects the conflicting root installation; child exit 1; no world created. This is not runtime compatibility. |
| No-C2ME native generation/reload | PASS | PASS | All 70 selected native FULL records checked before and after disk restart: exact coordinates, status, light and nonempty sections. Original lamp on/persisted/off effects pass. |
| Unchanged Chunky selection | PASS | PASS | 81/81 completed, saved and actually loaded after restart. Chunky and the original workload are unchanged. |
| Chunky cancellation | PASS | PASS | 50 requests outstanding at cancellation, all 50 settled; normal save/stop. |
| Stop with Chunky demand outstanding | PASS | PASS | 50 requests outstanding at stop, all 50 settled; native shutdown saves, without an earlier flush delaying the stop. |
| No-C2ME offline TCP client | PASS | PASS | Both actual dedicated-server lifecycles pass. Unchanged comparator matches all 132 ordered events to the archived full-API control, including native saved delay 186 and its real restart deadline. |

The client comparator positively requires both current fixture owners in original
topology/tracking bodies. Original offered tracker calls remain 515/187/323 for the
three fixed fixture entities in each W1/W4 sample. Configured gameplay workers are
one/four, independently of C2ME's two diagnostic generation workers. No independent
owner overlap or performance gain is claimed. No C2ME-enabled client acceptance was
run; these client cases preserve the no-C2ME behavior affected by the phase code.

Healthy final runs contain unique stopped receipts with zero ownership violations,
bypass, fallback, barriers and leases. FULL graphs, retained inputs/publications,
notifications and save/write acknowledgements settle normally. The complete suite
retains the repaired Chunky ABSENT-input tests, including newly eligible absent
inputs invalidating receipts before convergence.

Failed harness attempts remain visible: two observation-probe installation defects,
the nested-only gate, a read-only query label rejected by the existing strict
readiness-command validator, and an initial comparator invocation pointed one
directory too deep. These were corrected without changing offered work or comparator
rules. No server rerun was needed for the comparator path correction. A small derived
JSON view maps the historical absolute client paths to the durable archive; its
original/mapped hashes are retained, and the archived logs/worlds remain unchanged.

Session settings were checked once through exposed tool metadata. The active
model/reasoning setting was not exposed; GPT-6 Astra/high was not falsely claimed
as verified. Work was performed alone. Initial UTC time was 05:57:33 and account-wide
Codex usage was 76% (reserve 7%). The original +10-point finalization boundary is
86%; the subsequent 90%-used stop guard does not relax it. Final readings and time
are retained with the final verification receipt. These are account-wide telemetry,
not task-specific billing. No credits were consumed or separate API invoked.
