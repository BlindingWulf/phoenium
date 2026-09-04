# Stage 3 chunk metadata transition decision

## 1. Decision and scope

**DECISION A — CURRENT AUTHORIZED VANILLA OPERATIONS PRESERVE FULL→REPLACEABLE; STALE/UNAUTHORIZED OPERATIONS ARE FENCED.**
Confidence: **HIGH** for this contract correction, not acceptance of an implemented Minecraft binding.
The thenComposeAsync **Decision A is UNCHANGED**; coordinated multi-stage custody is unchanged.
The metadata prerequisite had an over-broad invariant; it does not establish an architecture contradiction.
This decision resolves the contract question in the historical [implementation stop record](STAGE3_PARSED_APPLY_IMPLEMENTATION.md).
It supersedes that record's unresolved-contract disposition, not its accurate evidence or unimplemented gates.
This pass changes documentation only: no production/test edits, PARSED_APPLY, Stage 4, or custody redesign.
Terra is ready for the bounded prerequisite correction below; atomic binding must wait for its regressions.
R-CHUNK-01=PARTIAL; Stage 3=PARTIAL; Stage 4=NOT STARTED; workers=1; parallel gameplay=OFF.

**Corrected invariant:** An old/stale lifecycle or superseded work item may not downgrade a current FULL marker; a legitimate current-lifecycle Minecraft transition may.
The byte is metadata, never authority. Authority is the bound world/lifecycle/work lineage, typed operation and its terminal/admission state.
B cannot preserve both absolute FULL retention and the observed replacement/previous-byte result.
C adds no necessary transition model; the existing two operations suffice. D is not required by the evidence.

## 2. Verified checkpoint and real Minecraft evidence

Start HEAD: `40e999cb4bcf2a539a04faf1a0f4556420ae3d77`; actual Git state matched; starting tree CLEAN.
Read ownership architecture, future decision, service prerequisites, implementation stop record and chunk handoff.
Inspected ChunkTypeMetadataService, LoadApplyPrerequisites, the real contract test and their narrow lineage/test dependencies.
Re-executed `./gradlew.bat clean build --no-daemon`: **121 tests PASS; 0 failures/errors/skips**.
This includes `ChunkMetadataVanillaContractTest.stop07CurrentFullProtectionDiffersFromRealVanillaRecovery`.
Passing that existing witness confirms a mismatch; it does not mean the current façade has been repaired.
No new live-world run or FUT acceptance is claimed. FUT-01 through FUT-18 remain unimplemented/unaccepted.

Exact local Minecraft 26.2 official-named artifact:
`C:/Users/Admin/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`.
SHA-256 rechecked: `A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4`.
Fresh `javap -c -p` inspection of the four requested methods confirms:

| Actual method | Bytecode evidence / consequence |
| --- | --- |
| `markPosition(ChunkPos, ChunkType):byte` | PROTOCHUNK selects -1; otherwise 1; `Long2ByteMap.put` at 20, previous byte returned at 25. |
| `markPositionReplaceable(ChunkPos):void` | Constant -1 at 8; `put` at 9; previous byte discarded at 14; no FULL branch. |
| `lambda$scheduleChunkLoad$4(ChunkPos, Throwable)` | Calls `handleChunkLoadFailure(Throwable, ChunkPos)` at 3. |
| `handleChunkLoadFailure(Throwable, ChunkPos)` | Fatal Error branch calls replaceable at 121, constructs ReportedException at 124, throws at 133. |

The actual-method fixture starts FULL, invokes `$4(pos, CompletionException(exactError))`,
then asserts REPLACEABLE and a thrown ReportedException with `getCause() == exactError`.
The real marker sequence also proves absent previous=0 and subsequent FULL previous=-1 after replacement.
The façade's Update may expose replacement's prior byte; vanilla's void method itself returns nothing.
For FULL→REPLACEABLE the underlying put previous byte is **1**, new byte **-1**.

## 3. Current implementation and exact byte contract

Current service: ABSENT=-1, REPLACEABLE=0, FULL=1; lifecycle-only marker arguments; 64 key stripes.
Its current replaceable operation returns APPLIED/previous=1 but keeps FULL when the lifecycle is current.
Its next FULL write therefore returns 1 instead of Minecraft's -1. **Conflict confirmed: YES.**
Current `markPosition` also accepts arbitrary bytes, which is a model convenience, not a typed vanilla operation.
`LoadApplyPrerequisites.evaluate` checks lifecycle plus WorkId, but metadata writes check lifecycle only.
A READY result is not a reservation and cannot authorize a later mutation after work invalidation.
`orderStorageMutation` currently checks arguments only; it is not proof of storage ordering.

Use Minecraft bytes at the corrected façade boundary: **ABSENT=0, REPLACEABLE=-1, FULL=1**.
Prefer aligning the service constants directly; no encoding translation layer is needed for this unbound façade.
Absence means no entry and a read/previous default of 0, not a stored replacement byte.
Use typed mark-position values FULL/PROTO (or equivalent closed type), mapping PROTO to -1.
Do not permit arbitrary bytes or an ABSENT write through the vanilla marker API.
Update affected byte assertions in PRQ-11..14 while retaining previous-value, ordering and rejection coverage.
Accepted writes atomically read the old byte, store the new byte and return the old byte.
An accepted same-value write remains accepted and returns that value; FULL is not a write veto.

## 4. Required transition table

All numbers below are corrected façade/vanilla bytes; L/W denotes the exact authorized lineage.
For rejected Update results, previous is a diagnostic observation in the key's service order, never a successful vanilla return.
Consumers must branch on rejection before using previous as a vanilla result; no rejected operation writes the backing map.

| Lineage / operation | Previous byte | New byte | Disposition | Reason |
| --- | ---: | ---: | --- | --- |
| Current L/W; ABSENT → markPositionReplaceable | 0 | -1 | ACCEPTED | Valid typed replacement, including missing entry. |
| Current L/W; REPLACEABLE → markPositionReplaceable | -1 | -1 | ACCEPTED | Vanilla same-value put. |
| Current L/W; FULL → markPositionReplaceable | 1 | -1 | ACCEPTED | Vanilla unconditional put. |
| Current L/W; ABSENT → markPosition(FULL) | 0 | 1 | ACCEPTED | Exact missing-key previous byte. |
| Current L/W; REPLACEABLE → markPosition(FULL) | -1 | 1 | ACCEPTED | Observes earlier replacement. |
| Current L/W; FULL → markPosition(FULL) | 1 | 1 | ACCEPTED | Exact existing FULL previous byte. |
| Current L/W; any valid byte b → markPosition(PROTO) | b | -1 | ACCEPTED | Same type mapping as vanilla. |
| Stale L1; current L2 FULL → markPositionReplaceable | 1 | 1 | REJECTED / STALE | Lifecycle mismatch before mutation. |
| Stale L1; current L2 REPLACEABLE → markPosition(FULL) | -1 | -1 | REJECTED / STALE | Old work cannot publish into L2. |
| Current L; superseded W1, valid W2; FULL → markPositionReplaceable | 1 | 1 | REJECTED / STALE | W1's reservation entitlement ended. |
| Wrong world/instance or target; any marker on byte b | b | b | REJECTED / STALE | Operation target and bound lineage differ. |
| Retired lifecycle; any marker on byte b | b | b | REJECTED / STALE | Retirement committed before claim. |
| Shutdown fenced; any marker on byte b | b | b | REJECTED / SHUTDOWN | No new service mutation admitted. |

Here b is the current entry or 0 after authorized cleanup; cleanup itself is distinct from a marker call.
After accepted FULL→REPLACEABLE, the next accepted markPosition(FULL) **must return -1**, never 1.

## 5. Authority, work lineage and atomic fencing

ChunkTypeMetadataService is a **COORDINATED GLOBAL METADATA SERVICE**, scoped to the actual world instance.
It is not region gameplay ownership. A region lease alone does not authorize a metadata update.
Bind the service's world/session/instance and target chunk to LifecycleId; do not trust an unrelated string key.
For load-associated operations, require WorkId belonging to that lifecycle and the exact registered vanilla reservation.
Validate current lifecycle, valid work/reservation, typed operation and terminal state at mutation admission.
Capture this lineage when registering the callback; never resolve an old callback to the newest work by coordinate.
Do not expose a lifecycle-only overload as a load adapter escape hatch; any later non-work service producer needs its own validated service lineage.

**Same lifecycle is insufficient:** retire/invalidate W1's actual reservation, make W2 valid for its successor,
then reject W1's late marker even while L1 is current. Existing `isCurrentWork` tests ACTIVE, not largest sequence.
Creating W2 alone does not supersede W1: different statuses can be concurrent valid dependencies.
Demand cancellation, task rescheduling and topology refresh do not invalidate still-valid shared startedWork.
Failure of PARSED_APPLY is a stage outcome, not automatic retirement of its work; genuine recovery can retain that work.
Pending unload closes new work but permits already-reserved entitled stages; rescue retains the same lifecycle/work.
Actual retirement, shutdown or a winning work-supersession fence prevents any subsequent new marker admission.

One coordinated metadata order must cover validation/reservation, per-key put, storage observations and invalidation/cleanup.
A stripe protects the table key; separate `isCurrent` / `isCurrentWork` calls alone leave a check-to-write race.
Use the existing custody/admission reservation protocol to pin an admitted synchronous unit through its metadata subaction.
For standalone metadata operations, validation and the small update must share the fence's linearization protocol.
Do not execute vanilla bodies or complete futures under that metadata gate, and do not add a gameplay lock.
If a stage claim wins, its authorized synchronous unit finishes before retirement/shutdown commits against it.
If the fence wins, no mutation is invoked; table rejection rows describe that order, not rollback of an earlier winner.
Coordinate retire/shutdown cleanup too: late old-lifecycle removal cannot erase an L2 entry or reinsert after shutdown.
Keep reads and storage observations in this service order; no direct backing-cache write or divergent second cache.
No owner waits, peer locks, or locks spanning asynchronous work are introduced by this requirement.

## 6. Operation types and genuine recovery

**MARK_POSITION and MARK_REPLACEABLE suffice.** No NORMAL_LOAD / GENUINE_LOAD_FAILURE / RETIREMENT reason enum is needed.
Both fresh-load replacement and genuine recovery may legitimately replace FULL when their lineage is entitled.
Retirement is an existing lifecycle fence/cleanup operation, not a new right to call a marker.
The normal typed façade implements the transition; no forceReplaceableIgnoringSafety or recovery bypass exists.
The closed original callback binding supplies operation entitlement; a caller-provided reason cannot mint authority.

For eligible L1/W1 with FULL and a genuine fatal error, invoke the original `$4` with its exact retained throwable.
Its nested MARK_REPLACEABLE uses the same validated lineage, stores -1 and precedes ReportedException.
Preserve the real throwable/report construction and exact Error cause; do not copy the recovery body or add wrapping.
The witness proves this real-method behavior; parity through a corrected façade remains a required implementation test.
Recovery is a separately admitted stage; if its lifecycle/work fence wins first, it must not invoke `$4`.

Private controls follow the existing future decision before the genuine recovery callback can be invoked:

- STALE_ROUTE: keep the same stage pending and reroute; zero recovery/marker invocations for that disposition.
- SUPERSEDED: authenticate the bound terminal, settle via private control; zero recovery/marker invocations.
- SHUTDOWN: authenticate the bound terminal and disarm; zero recovery/marker invocations.

Binding/barrier failures also bypass vanilla recovery. Merely possessing `$4` is not authorization.
An ordinary genuine CancellationException retains vanilla meaning; do not classify by throwable name/message alone.

## 7. Required replacement regressions

Actual PRQ-15 is `prq15OldReplaceableCannotDowngradeCurrentFull`: it already uses an **old lifecycle**.
It does not itself assert current-lifecycle FULL immutability; the real contract witness asserts that current defect.
Preserve PRQ-15's assertions as PRQ-15A, add 15B/15C, and replace the witness's service-divergence assertions with parity when fixing production.

| Gate | Exact required design |
| --- | --- |
| PRQ-15A | Capture L1/W1, replace with L2/W2 at the same target, set FULL under L2, deliver L1 replaceable: STALE, previous=1, FULL unchanged, zero backing writes by L1. Also stale FULL marking cannot change L2 REPLACEABLE. |
| PRQ-15B | Current authorized L/W: parameterize initial absent/replaceable/FULL; replaceable returns 0/-1/1 and stores -1; subsequent FULL returns -1. Assert APPLIED and actual entry, not status alone; retain typed PROTO and FULL cases. |
| PRQ-15C | Same L: evaluate READY for W1, actually retire/supersede W1's reservation, register W2 successor, set FULL via W2, deliver W1 replacement: STALE, previous=1, FULL unchanged, zero writes. W2 replacement succeeds. |

Add variants for wrong world/instance/target, retired lifecycle, shutdown, and both fence-before-write and claim-before-fence orders.
Observe the private backing write via a test-only counting seam or equivalent write observation; unchanged bytes alone cannot prove zero writes.
Control ordering with deterministic test hooks/acknowledgments, not sleeps; test invalidation between readiness and mutation.
Keep a complementary shared-work case: demand-only cancellation / another valid status W2 leaves W1 entitled.
Keep PRQ-11..14 same-key previous/order and distinct-key coverage, plus cleanup/no-reinsertion checks.
No production bypass or exposed mutable map is justified by test instrumentation.

**ChunkMetadataVanillaContractTest is a REQUIRED REGRESSION**, not a disposable stop probe.
Retain its real marker and fatal `$4` invocations and exact Error identity assertions.
On implementation, compare the corrected façade against the real methods for the complete marker sequence and byte encodings.
Exercise the same real fatal recovery through the bounded façade interception in the eventual atomic binding test;
assert FULL→-1 before ReportedException and exact cause identity, without reimplementing recovery in a fake callback.
Until that interception exists, separate façade parity plus the real recovery witness are necessary but not FUT-11 acceptance.
Keep the current witness passing unchanged in this docs-only commit; its divergence assertions must change with the actual fix.

## 8. Terra's bounded implementation sequence

1. Adjust ChunkTypeMetadataService to unconditional accepted replacement, exact vanilla constants/previous bytes and closed mark-position values.
2. Add load WorkId and bound world/target validation; integrate mutation/cleanup with existing lifecycle/work/terminal reservation ordering; revalidate after readiness.
3. Preserve PRQ-15 as 15A; add 15B/15C and the targeted authority/order variants; retain all earlier logical coverage with valid typed bytes.
4. Retain ChunkMetadataVanillaContractTest, convert service mismatch assertions to parity, keep actual recovery/cause proof, and require it in the build gate.
5. Rerun the full clean build; report exact counts and new regression evidence. Do not equate these tests with live/FUT acceptance.
6. Only then resume Decision A's already-specified atomic application/recovery/holder binding and FUT-01..18; no partial PARSED_APPLY enablement.

The original full-save protection requirement means rejecting stale lifecycle/work downgrades, not making FULL immutable.
Current legitimate Minecraft invalidation remains ordered against save/load observations; this decision adds no new save algorithm.
No changes to future composition, custody identity, leases, FULL/readiness scope, C2ME, or Stage 4 are authorized here.
**STOP after this docs-only decision. R-CHUNK-01 PARTIAL; Stage 3 PARTIAL; Stage 4 NOT STARTED.**
