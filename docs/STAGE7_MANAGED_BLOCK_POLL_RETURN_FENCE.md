# Stage 7 natural-spawn managed-block return fence

This candidate implements all three authorized primitives: the retained natural-spawn
continuation, immutable structure-query preparation, and the native managed-block
poll-turn return fence. It is ready for Luna's isolated real-client retest; no live
server or Bedrock acceptance was run in this implementation task.

## Baseline

Parent: `f9539c0690a00235b56cd47d0e6d51174336ed7d`, the locally verified
default-world/frozen-authority repair. The longer `f9539c0d63be6909437e70bc7407aa255ebf67cf`
identifier in the request does not resolve in this repository; the prior ordering
audit identified the same actual local parent used here.

Work is isolated on `stage7/managed-block-poll-return-fence`. The user's main checkout
and the earlier repaired candidate remain separate. There is no push or provider
deployment. Build identity, exact changed-file list, JAR size/hash, test counts and
packaging results are recorded after the local commit in
`run/managed-block-fence/provenance.json`.

## Native ordering boundary

`ManagedBlockPollTurnFence` belongs to one native chunk event loop. A monotonic
checked-increment `long` identifies each outer poll admitted after a false native
readiness observation. A thread-local active reservation propagates that identity
through the existing save pump and synchronous dispatcher drains. Nested poll
wrappers inherit it; they do not allocate a second turn or hold a monitor while
running their bodies.

`ChunkStagePumpMixin` wraps the entire `MainThreadExecutor.pollTask()Z`, including
the existing HEAD pumps. Completion only records reservation metadata. The full
original poll and all nested owner scopes finish before the wrapper verifies the
owner-free boundary. Immutable preparation can then advance without ordinary
gameplay arbitration. The actual native `BlockableEventLoop.managedBlock` supplier
is the readiness observation: this also preserves native `waitForTasks` after a
false poll return.

The states remain distinct:

```
WAITING_DEPENDENCY -> DEPENDENCY_COMPLETE_IN_POLL
                  -> matching poll returns -> POLL_RETURNED_READY
                  -> RESUME_CLAIMED -> RESUMING -> SETTLED

WAITING_DEPENDENCY -> DEPENDENCY_COMPLETE_OUTSIDE_POLL
                  -> next readiness observation -> RETURN_FENCE_READY
                  -> RESUME_CLAIMED -> RESUMING -> SETTLED
```

There is no forced poll in the already-ready case. After managedBlock returns,
the dispatcher delivers this reservation directly through the scheduler's existing
selection, current-route validation, lease and failure-barrier path. No inbox round
or additional poll is inserted. The ordinary scheduling path is unchanged when no
natural-spawn reservation is active. Direct delivery is restricted to an authentic
active natural-spawn return claim and a CHUNK_GAMEPLAY payload.

The existing native chunk-spawn phase is serial and synchronous. Its outer capture
does not finish until its continuation finishes. Exactly one reservation can be
active on that native thread; overlapping captures/reservations fail closed. There
is no unordered collection of ready reservations or global reservation priority.
Successive queries receive monotonic poll identities. Stale, cancelled, failed and
shutdown reservations cannot be promoted by late completion or return.

Per-reservation monitors protect finite state metadata only. Poll bodies,
preparation, validity/shutdown/owner checks and the resumed owner body run outside
those monitors. The existing source audit explicitly lists this bounded exception,
and a behavioral regression proves those callback boundaries.

## Retained natural-spawn loop and structure facts

`NaturalSpawnContinuation` retains the pinned Minecraft 26.2 category/group/attempt
program counter and locals. It yields only at the two reached `mobsAt` operations:
spawn-list selection and spawn-list membership. The native prefix is executed once;
earlier attempts, entity construction and spawn callbacks are not replayed.
Original private helper methods and SpawnState callbacks use exact mixin invokers.
All draws continue to use the persistent `Level.random` through its native getter.
No RNG copy, rewind, tape, replacement or global RNG lock is introduced.

`NaturalSpawnStructureQuery` requests the reached query-position
STRUCTURE_REFERENCES and only the referenced STRUCTURE_STARTS required by the
fortress/category override path. Its nonblocking invoker selects
`getChunkFutureMainThread`; the public future method would itself managed-block.
Reference iteration and override ordering are retained. Native fortress evaluation
loads all starts before inspecting bounding boxes; override evaluation still visits
every referenced start after a match. PIECE and skipped predicates do not warm the
native aggregate bounding-box cache.

Snapshot facts include immutable reference lists, start validity, copied bounds or
piece bounds, fortress decision and copied weighted spawn lists/biome fallback.
Mutable StructureStart aliases are never stored in the continuation. Preparation
uses the existing structure-mutation read exclusion and a current regional lease
for each exact copy, then releases both. Waiting holds no regional owner context,
claim, selection or scheduler batch.

The optional C2ME generation delegate's two existing native apply call sites join
the same structure-writer exclusion. This covers referenced starts that FEATURES
can mutate across its finite dependency footprint. The original producer executes
once and its exact future/synchronous exception is preserved. No C2ME provider JAR,
settlement2 code or settlement policy changes.

Resume revalidates the world/runtime, active cycle, compatible closure, query
generation, source ticking chunk/holder and dependency holders, then resolves the
current owner. Replacement, incompatible topology, retired operations, stop and
dependency failure prevent the suffix; original failure causes are retained.
ChunkSynchronousAccessGuard is unchanged.

## Verification

Final focused run: **225 tests, 34 suites, zero failures/errors/skips**.

New coverage comprises 41 tests:

* 14 fence tests using the actual Minecraft event loop and regional dispatcher.
* 10 immutable structure-query tests, including writer exclusion, replacement,
  fortress/override order, lazy-box timing and failure/shutdown cleanup.
* 3 optional-provider structure-writer tests, preserving native future and failures.
* 10 differential native-spawner comparisons over five seeds, with delayed and
  already-ready dependencies. Every raw RNG draw, reached query and poll event is
  compared with the original pinned NaturalSpawner implementation.
* 4 native descriptor/binding tests, including the complete poll wrapper, no hidden
  managed wait in future selection, persistent RNG getter and no native mutation
  suffix after the captured spawnForChunk call.

The remaining 184 tests cover existing services/scheduler, topology, authority,
movement, interaction, block admission, owner release, structure inputs/protection,
FULL context, chunk-query failure binding, and C2ME generation/provider contracts.
The authority repair and item/player interaction code are unchanged at source.
These tests are regression evidence, not a live item drop/pickup demonstration.

The explicit shared-RNG witness uses seed 812:

```
prefix draw 0 = -1270909246
poll 1: dependency completion after lease release
poll 1: permitted ordinary gameplay draw 1 = -1970933588
poll 1: nested save acknowledgement, then complete poll return
native readiness observation
reserved suffix draw 2 = 446055132
queued later ordinary gameplay remains pending until after the suffix
```

Immediate completion resumption would take draw 1; another ordinary arbitration
before resumption would consume draw 2. Both are excluded by the assertions. The
already-ready case takes draw 1 with zero polls. Nested polls retain the same ID,
and an asynchronous completion thread can finish while the native poll is running.

Command (PowerShell, repository root):

```powershell
.\gradlew.bat test --tests 'dev.phoenium.service.*' --tests 'dev.phoenium.topology.*' --tests 'dev.phoenium.CoreAcceptanceTest' --tests 'dev.phoenium.RealClientReadinessTest' --tests 'dev.phoenium.LiveMovementAdmissionTest' --tests 'dev.phoenium.Stage3MovementTest' --tests 'dev.phoenium.BlockPacketAdmissionTest' --tests 'dev.phoenium.InteractionAdmissionTest' --tests 'dev.phoenium.RegionLeaseReleaseTest' --tests '*NaturalSpawn*Test' --tests '*FullStructure*Test' --tests '*FullContextRefinementTest' --tests '*ChunkQueryFailureBindingTest' --tests '*C2meGeneration*Test' --offline --console=plain
```

The existing provider-binary tests require the pinned research fixtures at their
existing ignored build paths. Original fixtures were copied there without changing
them. The outer fixture SHA-256 is
`B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`.
This fixture is not a deployment choice or replacement for Luna's accepted providers.
The successful XML reports are preserved outside build before the clean JAR build.
Earlier failed test reports remain under `run/managed-block-fence/failure-*`.

No full repository test suite or live campaign was run. The differential scenes
use controlled world endpoints and AIR terrain that rejects final mob placement;
they prove query/RNG/control-flow equivalence on those paths, not successful live
mob insertion. JUnit-only native access adapters are excluded from production.
The retained loop is pinned to the audited 26.2 vanilla spawning/structure contract;
custom generator overrides or other mods replacing that loop require a separate
compatibility review. Activation uses the existing authoritative FULL runtime.

## Luna retest

Use a fresh isolated server and the explicit candidate JAR, verifying its recorded
hash. Keep the accepted provider set and existing authoritative configuration.
Portal/pearl acceptance remains paused.

1. Join, walk 20–30 blocks and cross chunk boundaries.
2. Remain connected through repeated natural spawning. Verify the server stays
   alive and chunks keep rendering.
3. Break one block, place one block, interact with/damage a mob, drop one item,
   pick it up, and chat.
4. Disconnect and reconnect. Remain connected through natural spawning again;
   repeat movement and one block interaction.

PASS requires continued natural spawning, authoritative movement, healthy chunk
delivery and item/mob interaction, with no unfinished synchronous chunk dependency
inside an owner transaction, crash, ownership violation, barrier, fallback or
unleased regression. This candidate does not claim Bedrock acceptance.
