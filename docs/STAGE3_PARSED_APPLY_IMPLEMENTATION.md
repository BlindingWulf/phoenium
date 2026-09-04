# Stage 3 metadata correction and Decision A binding

## Part A metadata correction checkpoint

Starting HEAD: `0f955548375f14c06578e2968b193f41e8d1939b`; clean tree.
Baseline clean build: 121 tests, zero failures/errors/skips.

The metadata facade now uses ABSENT=0, REPLACEABLE=-1, FULL=1 and a closed
PROTO/FULL marker type. Accepted replacement always writes -1 and returns the
exact previous byte, including FULL -> -1. There is no bypass API.
Load operations require a registered WorkId and exact bound server session,
world instance, world identity, lifecycle and RoutingKey. Retired/cancelled work
is rejected; another valid status reservation and demand-only cancellation do
not revoke shared work. Pending unload preserves existing metadata entitlement.

Validation and the single backing-map operation execute under the existing
custody metadata fence used by lifecycle/work retirement. This replaces the
independent 64 cache stripes, which could not order retirement. The section
contains only identity checks and cache operations; no vanilla callback, region
lease acquisition, future completion or storage I/O runs there. Different keys
have independent values, but these very short metadata operations serialize on
the custody fence. This is a shared metadata lock, not a gameplay lock.
Cleanup and shutdown use the same fence. Old-lifecycle cleanup cannot delete a
new lifecycle's entry. Cleanup retires the old custody lineage; the lifecycle
registry consults that authoritative state before reuse/rescue/current lookup.
The old no-op orderStorageMutation API has been replaced with an honest ordered
observation; observations and prerequisite READY do not authorize later writes.

PRQ-11..15 retain their logical assertions using real vanilla marker values and
exact target keys. Added PRQ-15A/B/C and identity, shared-work, cancellation,
pending-unload, cleanup, shutdown, and deterministic concurrent fence-order
regressions in ChunkMetadataAuthorizationTest. The package-private write count
proves rejected operations invoke zero backing puts, including same-value puts.

ChunkMetadataVanillaContractTest retains the actual Minecraft 26.2 marker and
fatal recovery methods. It now checks parity and additionally routes the real
$4 -> handleChunkLoadFailure -> map put through the normal typed facade using a
test-only forwarding map with no second cache. FULL changes to -1 exactly once;
ReportedException's cause is the original Error by reference. This is a bound
real-method metadata substitute, not a production Mixin or FUT acceptance.

Minecraft bytecode was re-inspected for markPosition, markPositionReplaceable,
lambda$scheduleChunkLoad$4 and handleChunkLoadFailure in the pinned 26.2 jar.
Part A clean build: 132 tests PASS, zero failures/errors/skips.
No authoritative PARSED_APPLY, recovery or holder Mixin is enabled by Part A.
The separate metadata checkpoint SHA is recorded in the delivery report and in
the subsequent Part B record, avoiding a self-referential commit hash here.

## Part B production binding

Entered only after metadata checkpoint
`4065883746456ce84e550689a438b9a4b64e5801`, successful Part A build and CLEAN tree.

**BLOCKED at reload light prerequisite ordering (B5/B7/B9).** No authoritative
transformation is installed. Decision A flattening and the metadata decision
remain unchanged. This is a service-stage ordering issue, not evidence against
thenComposeAsync or coordinated multi-stage ownership.

B7 requires an accepted LightInputReceipt before PARSED_APPLY is eligible.
B5 defines that receipt as actual retainData/BLOCK/SKY ingress acceptance, not
preparation, a reserved batch, or computation completion. B9 and the locked
future decision preserve the original synchronous parsed-apply body and its
mutation ordering, with no mutation in the main-executor readiness wrapper.

In the exact 26.2 artifact, the relevant ingress occurs INSIDE that body:

| Method | Actual instruction / dependency |
| --- | --- |
| ChunkMap.lambda$scheduleChunkLoad$3 | Calls SerializableChunkData.read at 38. |
| SerializableChunkData.read | In each applicable section, checkConsistencyWithBlocks at 180. |
| Same read | First required retainData at 238. |
| Same read | BLOCK queueSectionData at 261; SKY at 281 when the dimension/payload permits it. |
| LoadApplyPrerequisites.evaluate | Reload stays NOT_READY until the light batch has accepted all required ingress. |

For persisted data with light payloads, binding only those original calls gives:

```
PARSED_APPLY eligibility -> accepted light receipt
accepted light receipt -> original read submits light
original read -> owner-admitted PARSED_APPLY
```

The existing independent POI packed-data prefetch/install path does not resolve
this light dependency. A zero-input reload batch cannot stand in for persisted
BLOCK/SKY inputs; registering a batch alone does not create its receipt.

Two changes could break that cycle, but neither is silently chosen here:
(1) make pre-apply eligibility require prepared/reserved light ingress and
record the accepted receipt during read, or (2) explicitly authorize extraction
of physical light ingress into a preceding service stage, with a defined
POI/light ordering and suppression of duplicate nested submissions. The first
changes B7's receipt prerequisite; the second changes the retained body's
side-effect timing and needs a precise contract for the split. Merely submitting
inputs early is not evidence that vanilla mutation ordering was preserved.
The current task locks these semantics against redesign. The architecture's
section 17 treats an unresolved narrow POI/light service contract as a blocker.

ChunkReloadPrerequisiteOrderTest provides two passing stop witnesses:
- Exact method/class bytecode checks place POI consistency and retain/BLOCK/SKY
  calls inside read, and read inside $3; immediate parse/registration methods
  have no direct light-ingress calls. Exact method match counts are checked.
- The real prerequisite ledger, with POI satisfied and a required BLOCK batch
  declared, remains NOT_READY until retain and section acceptance are recorded.
  Those test acknowledgments are explicitly not physical ingress acceptance.

These witnesses establish the dependency for a direct binding, not a proof
that no reviewed extraction design can exist. No alternative is implemented,
no fake receipt is issued, and no production body is partially enabled.

Final clean build: 134 tests PASS, zero failures/errors/skips, including all 132
metadata-checkpoint tests and both ordering witnesses. FUT acceptance remains
0/18; actual Fabric transformation, fresh generation and persisted-reload
acceptance are NOT RUN. Runtime safety counters and terminal leaks are NOT
MEASURED, not inferred zero. Existing diagnostic Mixins remain diagnostic.

Next: resolve the accepted-versus-prepared light prerequisite and original
read ordering, then resume the atomic application/recovery/holder binding.
Do not advance to FULL_CONVERT/FULL publication from this checkpoint.

---

# Historical implementation stop record (before metadata correction)

The following record documents the earlier mismatch; its unresolved disposition
is superseded by the metadata decision and the Part A correction above.

# Stage 3 Decision A implementation stop record

## Disposition

**BLOCKED: user stop condition 7, exact controlled metadata semantics.**

Decision A's future-composition choice remains accepted.
This pass does not install or partially enable authoritative PARSED_APPLY.
The conflict is between the accepted prerequisite service's current-FULL
protection and Minecraft's genuine load-recovery metadata mutation.
It is not a contradiction of JDK thenComposeAsync flattening.

Start HEAD: `070a7200481fc971ca5dce1b20cb09ac5cc82286`.
The working tree was clean and `git diff --check` passed at the start.
The expected fifteen-commit history was verified.
Baseline `./gradlew.bat clean build --no-daemon`: PASS, 120 tests.
Final clean build: PASS, 121 tests, no failures/errors/skips.
All original 120 tests and their assertions remain unchanged.
PRQ-01 through PRQ-18 remain PASS; the prerequisite class has 21 tests total.
FUT acceptance: **0/18 implemented/accepted in this pass**, not 18 failed tests.

## Pinned artifact

Minecraft: 26.2, official-named Loom artifact.
Java: Temurin 25.0.4.1+1-LTS.
Artifact: `minecraft-merged-deobf-26.2.jar`.
Location: `C:/Users/Admin/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`.
Rechecked SHA-256:
`A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4`.

The bytecode anchors below describe this artifact.
They are evidence anchors, not production Mixin selectors.
The additional inspection was restricted to the binding and its immediate
metadata/recovery dependencies; historical live reconnaissance was not rerun.

## Exact metadata mismatch

Minecraft constructs `chunkTypeCache` as `Long2ByteOpenHashMap`.
Its default missing-key byte is zero.
The relevant byte encodings differ:

| Meaning | Minecraft | Existing prerequisite service |
| --- | ---: | ---: |
| Missing | 0 | ABSENT = -1 |
| Replaceable/proto | -1 | REPLACEABLE = 0 |
| FULL | 1 | FULL = 1 |

An explicit encoding conversion could handle this table.
The byte encoding difference alone is **not** the stop condition.

`ChunkMap.markPositionReplaceable(ChunkPos):void` unconditionally writes -1.
Its method-local bytecode is:

```text
 0 aload_0
 1 getfield chunkTypeCache
 4 aload_1
 5 invokevirtual ChunkPos.pack:()J
 8 iconst_m1
 9 invokeinterface Long2ByteMap.put:(JB)B
14 pop
15 return
```

There is no branch protecting an existing FULL marker.

`ChunkTypeMetadataService.markPositionReplaceable(lifecycle,key)` instead
reads the previous entry and writes only when that entry is absent or not FULL.
It returns APPLIED while retaining FULL for a valid current lifecycle.
This behavior is explicitly documented by the prerequisite checkpoint.

Consequently, for one current lifecycle and one key:

```text
mark FULL
mark replaceable
mark FULL, observing previous value

Minecraft: final mark returns -1
Service:   final mark returns  1
```

Both values already have distinct meanings under any valid encoding conversion.
No encoding translation can turn retained FULL into the original replaceable
state while also preserving the requirement to retain that FULL state.

## Why the mismatch is inside the requested path

`ChunkMap.lambda$scheduleChunkLoad$3(ChunkPos,Optional):ChunkAccess`
calls `createEmptyChunk` at offset 59 for the absent-data branch.
`createEmptyChunk` calls `markPositionReplaceable` at offset 2.
The reload branch invokes `SerializableChunkData.read` at offset 38 and
`markPosition` at offset 51.

`ChunkMap.lambda$scheduleChunkLoad$4(ChunkPos,Throwable):ChunkAccess`
calls `handleChunkLoadFailure(Throwable,ChunkPos)` at offset 3.
In that real recovery body, a classified Error reaches:

```text
119 aload_0
120 aload_2
121 invokevirtual markPositionReplaceable:(ChunkPos)V
124 new ReportedException
...
133 athrow
```

Thus the disputed marker write is part of genuine vanilla fatal recovery,
which the requested atomic binding must preserve.
It is not a private Phoenium terminal signal or stale-lifecycle write.
Nonfatal recovery also calls `createEmptyChunk` at offset 153.

The test supplies CompletionException(exactError), matching the normal
dependent-future transport shape, to the actual `$4` method.
Vanilla classifies the Error, changes the cache to -1, and throws
ReportedException whose cause is the exact Error.
`CrashReport.forThrowable` removes CompletionException transport before
constructing that report; its offsets 0 through 19 perform that unwrap loop.
The test preserves this real behavior rather than assuming the report retains
the incoming CompletionException as its direct cause.

No precondition in the supplied service contract excludes a current FULL entry.
The service deliberately supports that case and explicitly protects it.
Assuming it is unreachable would require a new proof/constraint, not a
position-based shortcut or a fabricated load result.

## Reproducible real-method witness

Source:
`src/test/java/dev/phoenium/chunk/ChunkMetadataVanillaContractTest.java`.

Test:
`stop07CurrentFullProtectionDiffersFromRealVanillaRecovery`.

Run from the repository root:

```text
./gradlew.bat test --tests dev.phoenium.chunk.ChunkMetadataVanillaContractTest --no-daemon
./gradlew.bat clean build --no-daemon
```

The test uses the actual Minecraft marker and `$4` recovery methods.
It does not copy their implementations, replace them with counters, or
install a Mixin.
The fixture allocates a constructorless ChunkMap and initializes only
chunkTypeCache, the instance field accessed by these narrow method paths.
Constructorless allocation uses reflected sun.misc.Unsafe only in test code.
It avoids creating server/world/storage services that the tested paths do not use.

The witness checks missing-key previous value, FULL-to-replaceable behavior,
the following marker's exact previous byte, and real fatal recovery.
It separately runs the existing service with a current lifecycle and checks
that its FULL value remains 1 after replaceable marking.
The assertions characterize the conflict; they do not claim the conflict is fixed.

No world was created, no region file was changed, and no dedicated server was
started by this fixture.
Minecraft bootstrap and crash-report system diagnostics run inside the test JVM.
Host Perflib/native-library warnings are captured in the JUnit output; they do
not prevent the method assertions from passing.
This is a bound metadata/recovery witness, not FUT-02, FUT-11, or a live gate.

## Why the binding is stopped

The requested contract simultaneously requires:

1. Exact controlled vanilla metadata and genuine recovery semantics.
2. The existing façade's current-FULL protection.
3. No direct uncontrolled backing-cache writes alongside the façade.

For the demonstrated input, those requirements demand different final states.
Calling generic markPosition to force a downgrade would evade FULL protection.
Keeping two divergent caches would violate the coordinated façade contract.
Skipping the marker would change the genuine recovery body’s effect.
Rejecting the valid current work would change vanilla failure handling.

No such workaround is installed.
The user explicitly required STOP rather than redesign for this condition.
The Decision A and ownership architecture documents remain untouched.

## Registration and graph inspection

The real holder source remains `ChunkMap.applyStep(holder,step,cache)`.
Its EMPTY branch calls `scheduleChunkLoad(ChunkPos)` at offset 19.
The preceding winning work source is
`GenerationChunkHolder.acquireStatusBump(ChunkStatus)`.
Its `startedWork.compareAndExchange` occurs at offset 22.
`GenerationChunkHolder.applyStep` calls it at offset 20, calls the map at
offset 30 on success, and attaches the original handle edge at offset 42.

The verified graph retains thenApplyAsync at scheduleChunkLoad offset 55
and exceptionallyAsync at offset 69.
No production lexical registrar or exact-returned-future index was added.
Reentrant A/B identity isolation and index cleanup remain unimplemented gates.

The actual holder `lambda$applyStep$0` invokes relayDelayCrash at offset 15
on failure and completeFuture at offset 27 on success.
No holder private-control filter or separate EMPTY STATUS_PUBLISH was installed.

## Remaining acceptance matrix

| Requested companion | Result in this pass |
| --- | --- |
| Holder/work tokens and arming | NOT IMPLEMENTED |
| Exact future index and cleanup | NOT IMPLEMENTED |
| POI read/background shared installation | NOT BOUND |
| Light retain/BLOCK/SKY ingress | NOT BOUND; existing receipt means ingress only |
| Metadata markPosition / replaceable | BLOCKED by the demonstrated contract conflict |
| Fresh/reload branch and prerequisite wakeups | NOT BOUND |
| PARSED_APPLY thenComposeAsync | NOT INSTALLED |
| FAILED_VANILLA invocation classification | NOT CHANGED |
| exceptionallyComposeAsync owner recovery | NOT INSTALLED |
| Authenticated private control and holder filter | NOT INSTALLED |
| Independent EMPTY status publication | NOT INSTALLED |
| Parsed-input retention proof | NOT ACCEPTED in this stopped pass |
| FUT-01 through FUT-18 | 0/18 accepted; not run as binding acceptance |
| Existing PRQ-01 through PRQ-18 | 18/18 PASS |
| New actual-method conflict witness | 1/1 PASS |
| Fabric authoritative transformation boot | NOT RUN; no authoritative hooks exist |
| Fresh generation worlds / seeds | 0 new runs; no acceptance claim |
| Persisted reload | 0 new runs; no acceptance claim |
| Admitted genuine failure / private control | NOT TESTED |
| Stale route / supersession / shutdown binding | NOT TESTED |
| Terminal binding resource accounting | NOT MEASURED |

No zero-valued runtime safety counters are inferred from the absence of a run.
Existing model tests are not relabeled as production binding proof.
Normal FailureBarrier, duplicate execution, private-control recovery and
terminal leak counts therefore remain unmeasured for the requested adapter.

## Source safety and lifetime

Production source/configuration changes: zero.
Added test code retains no server, world, future graph, owner callback or lease.
Its objects are scoped to one JUnit invocation and released with that test JVM.
The requested search found reflected Field.get and fastutil cache.get calls;
these read the fixture's Unsafe singleton or marker bytes and are not future waits.
No added join, managedBlock, wait, notify, latch, ThreadLocal, thread identity,
server-thread identity, Runnable, gameplay lock, identityHashCode or fallback.
Test-only reflection/constructorless allocation is not a production identity mechanism.

No FULL_CONVERT, FULL publication, BLOCK_TICKING, ENTITY_TICKING,
TICK_READY, SEND_READY or DEMOTE binding was added.
No administrator view/simulation-distance setting was changed.
Production remains server-only; simulation workers remain 1; parallel gameplay OFF.
C2ME and all listed optimization/compatibility integrations remain NOT STARTED.

## Resume boundary

Resolve the narrow metadata contract before retrying atomic Decision A binding.
The required clarification is how an authoritative current-lifecycle vanilla
load/recovery invalidation interacts with FULL protection.
Keep stale-lifecycle rejection, exact previous-value behavior and storage
ordering explicit in that decision.
This record does not choose a replacement contract or expand implementation scope.

R-CHUNK-01: PARTIAL. Stage 3: PARTIAL. Stage 4: NOT STARTED.
The next task is resolving stop condition 7, not expanding into FULL binding.
