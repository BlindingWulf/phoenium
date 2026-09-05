# Concrete non-self FULL query conflict — 2026-09-05

**Integrated FULL entry remains BLOCKED by a demonstrated D3 boundary conflict.**
The [approved D2/D3 amendments](STAGE3_FULL_D2_D3_APPROVAL.md) remain accepted.
This record does not request either approval again. It resolves the previously
unmeasured query obligations into concrete evidence; it does not claim that a
cycle detector, production private-object capability or FULL binding is installed.

## Exact reproducer

`FullNestedQueryBoundaryTest` executes the original pinned Minecraft 26.2
FULL supplier and saved entity readers. In one controlled ServerLevel, X at
chunk (0,0) contains a saved mature tadpole at (15.8,8,8), and Y at (1,0)
contains one at (16.2,8,8). Both have Age=24000 and AgeLocked=true, and both
holders allow FULL (ticket level 33) with distinct, incomplete FULL slots.
Lower status inputs are complete. The fixture covers both proto conversion
and the original reload postLoadChunk/Imposter branch. In the latter, both
actual LevelChunks already exist, but neither is publicly FULL.

Each original reader converts the tadpole and reaches frog finalization and
`fudgePositionAfterSizeChange`. Original `findFreePosition` inflates the
search area, and `BlockCollisions` rounds and visits chunks BEFORE filtering
individual blocks. Thus even all-air sections cause the foreign query:

```text
X original FULL supplier active
  Entity.load -> Tadpole.readAdditionalSaveData -> setAge -> ageUp
  Mob.convertTo -> original frog callback
  getCurrentDifficultyAt: FULL,false, X -> approved direct-self access
  fudgePositionAfterSizeChange -> CollisionGetter.findFreePosition
  BlockCollisions.computeNext/getChunk -> Level.getChunkForCollisions
  FULL,false, Y -> original getChunkFutureMainThread selects Y's incomplete slot

Y original supplier requires the corresponding callback:
  difficulty: FULL,false, Y -> approved direct-self access
  collision: FULL,false, X -> original selector selects X's incomplete slot

publish Y requires Y supplier return
  requires ready X -> publish X requires X supplier return
  requires ready Y
```

The witness invokes Y's original queued supplier while X is still on the
stack, verifies both selected futures by object equality, then throws bounded
diagnostic stops BEFORE managedBlock. It never supplies a neighbor object,
completes a FULL slot, waits for a timeout, or claims a live deadlock. The
controlled getter boundary replaces only exact direct-self results and exposes
the original private selector for the other queries. The cycle assumes the
ordinary last-result cache miss branch, as specified by D3; no general claim
about every vanilla schedule is made. Original visible-holder lookup and
scheduleChunkGenerationTask/getOrCreateFuture select the witnessed slots.

Both injected RuntimeExceptions are caught by Entity.load/EntityType. The
outer suppliers return their actual chunks, with conversion team prefixes
surviving; no grow-up sound or entity insertion was reached. Those diagnostic
returns are **not unmodified successful progress**. They are why a guard that
only throws cannot safely stand in for preflight closure.

## Query obligations now measured

| Original caller / requested result | Actual target and data | Validity / effects | Authority and evidence |
| --- | --- | --- | --- |
| getCurrentDifficultyAt, FULL,false | Current conversion; inhabited time plus original world-context/moon reads | Chunk fields initialized before postLoad; no ticket for false | Approved exact-self exception only; earlier nine D3 witnesses remain |
| fudgePositionAfterSizeChange -> getChunkForCollisions, FULL,false | Rounded collision envelope, including X/Y neighbor; actual block sections | Blocks initialized, but query still selects the actual FULL slot before block filtering; false adds no ticket | Neighbor requires ordinary readiness under D3 §4.6; proto and reload pair prove a mutual dependency |
| Same collision call with genuinely ready neighbor | Neighbor's actual published LevelChunk | Original selector returns completed actual result | New ready-neighbor witness preserves original result by equality |
| Same call with absent neighbor | Legitimate original unloaded result | No fabricated absence for a present target; no false-query ticket | New absent-holder witness returns the original completed unloaded result |
| ShulkerBoxBlock.getShape -> Level.getBlockEntity/getChunkAt, FULL,true | Exact self block entity and its dynamic bounding box | Original UNKNOWN ticket insertion precedes selection even when holder already entitled; actual box progress affects shape | New non-air witness runs original TicketStorage and shape; the named ticket turn and production local capability still need binding |
| Same shape with no block entity installed | Exact self initialized map | Original IMMEDIATE creation during collision, before later block-entity registration; the second position-search attempt reuses it | New original-method witness observes absent then present; no preinstallation of reload suffix |
| Same FULL,true query encounters a guard breach | Exact self, original ticket already committed | Ticket/team prefix remains; failure precedes IMMEDIATE creation, sound and entity insertion | New existing-admission test fences success despite original EntityType catch; no rollback |

Physical-thread check is preserved in these fixtures: Level.thread is the
actual test thread so Level.getBlockEntity reaches the server-thread branch.
This is an original-method behavior condition, not Phoenium authority.
The shulker fixture uses a controlled progress value and real initialized
non-air sections; it is not a live gameplay/opening test or external network test.

## Why the approved mechanisms do not resolve this pair

Preflight can discover both dependencies, but ordinary readiness cannot choose
either as a first producer: each requires the other's publication. Reordering
X/Y, one-worker execution, service turn order or quiescent region convergence
does not remove either future edge. No service/region is held while preflight
waits, but an owner-free parked cycle still has no producer that can finish.
The existing continuation mechanism parks before the complete supplier; it
cannot resume Java's nested saved-reader stack after a partially committed
conversion, or publish a supplier result that does not exist yet.

D3-X explicitly forbids neighboring private access and requires neighbors'
ordinary ready results, even inside the same converged region. A dynamic-shape
ticket is also not permission to publish a neighbor early. Returning null,
skipping collision/tadpole loading, manufacturing a ready chunk, pumping under
R or replaying the conversion would violate the current contract. This is a
concrete semantic boundary, not a missing helper or ordinary implementation bug.

The smallest candidate amendment for the **reload reproducer** is a narrow,
versioned pre-publication borrow of the other work's already-constructed actual
LevelChunk **only for this collision query's initialized block data**, after
generation borrows drain and before either work claims conversion. Its exact
world/holder/work/input/field versions and entire footprint must be reserved;
the borrowed work cannot mutate those fields concurrently. This would observe
neighbor data before that neighbor's postLoad, which is a NEW observable cut
and NEW neighboring access absent from the current approval. It must preserve
ready/null/error and ticket prefixes and cannot imply public FULL readiness.
Dynamic block-entity operations need an explicitly enumerated mutation/callback
borrow as well; the read-only amendment alone does not cover those operations.
Proto inputs without an already-constructed actual neighbor require a further
input/construction boundary decision; this candidate alone does not solve them.
No such amendment is implemented or inferred as approved here.

## Implemented safety correction

The existing `ChunkSynchronousAccessGuard` now records an unfinished-query
breach in a finite synchronous fault scope bound to the existing exact
ChunkStageClaim. `ChunkStageAdmission` checks that record after the original
body, before APPLIED, and on an exceptional exit. A swallowed protocol marker
therefore becomes FAILED_BARRIER, never successful conversion evidence. A
later genuine failure remains attached to the original breach for diagnosis.
A genuine caught Minecraft exception without a protocol breach still permits
the original supplier's success. Original committed effects are retained.

This extends the real admission implementation used by the existing load
path. It adds no lifecycle, scheduler, service coordinator, foreign-reference
authority or FULL production registrar. The lexical fault slot locates only
the already-running claim; it grants no query access and infers no asynchronous
lineage. It retains one claim and the first failure for the finite invocation,
drops both at exit and removes the thread-local before future completion.
It holds no state lock across the body and introduces no new serialization.
Reentrant settlement, duplicate delivery and later independent claims are tested.
This is an escape backstop, not acceptance of an unsupported saved entity path.

## Verification and retained failed attempts

- Baseline: 253 passing tests at f09fe2f.
- Attempt 01: 14 focused tests, one fixture assertion failed. It assumed a
  single dynamic-shape query; the original vertical fallback repeats it. The
  corrected witness requires the actual absent-then-present sequence.
- Attempt 02 before the production fix: 9 focused tests, three failures prove
  caught query breaches incorrectly become APPLIED or FAILED_VANILLA.
- Attempt 03: 262-test full run, 79 failures from one new test class missing
  explicit Minecraft bootstrap, poisoning shared registry initialization. The
  root XML records `Not bootstrapped`; this was a fixture initialization error.
- Attempt 04 with explicit bootstrap: clean build and client compilation,
  262 tests pass. Attempt 05 adds reload-cycle and ticket-prefix binding cases:
  clean build and client compilation, **264 tests, zero failures/errors/skips**.

Logs and root XML for unsuccessful runs remain under `run/full-query-attempt-*`.
The eleven additions are six query/semantic closure witnesses and five tests
of the actual query-guard/admission binding, including original FULL callers.
The original 253 assertions and cleanup checkpoint a99d647 remain passing.
Final artifact runtime checks and exact hashes are recorded in the
[implementation ledger](STAGE3_FULL_APPROVED_IMPLEMENTATION.md).
