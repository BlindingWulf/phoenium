# Stage 3 reload light ingress implementation

## Part A checkpoint

Starting commit: `54fe36e0f3cead4517055fd7a0ed3c038c83fda9`, clean tree.
Baseline clean build: 134 tests PASS. Part A clean build: **177 tests PASS**, zero
failures, errors or skips: original 134, LIO-01..16, and 27 additional adversarial
cases. Production binding is deliberately absent from this independent checkpoint.
The accepted decision documents are unchanged.

`LightInputService.BatchId` contains LifecycleId, WorkId, exact PARSED_APPLY
StageId and batch sequence. LifecycleId carries server session, world instance,
WorldId, RoutingKey/ChunkPos and holder activation sequence. An OccurrenceId adds
RETAIN/BLOCK/SKY, section key and occurrence sequence. Ordered repeated section
coordinates remain separate. The service stores scalar declarations and states,
not serialized input, DataLayer, engine, holder, callback or lease references.

The pre-claim query requires this exact registration; it does not require an
actual receipt. Admission pins the specific service registration object in an
explicit, synchronous ChunkStageClaim. The same scalar IDs in a different ledger
cannot authenticate this claim. INVOKING is recorded before the original call;
the custody fence is released before crossing that boundary. Insertion must be
witnessed in the real mailbox and the call must return normally before ACCEPTED.
A lost insertion acknowledgment or swallowed dispatch rejection is a protocol
failure. No attempted operation returns to REGISTERED and nothing retries it.

The batch seals at **normal SerializableChunkData.read return**, including an
empty batch. Completion authenticates the service-issued receipt by reference
and full lineage before returning APPLIED. A normal body return without sealing
is FAILED_BARRIER. A separate STATUS_PUBLISH(EMPTY) prerequisite authenticates
the original parsed stage's APPLIED state and actual receipt. This checkpoint
provides that prerequisite; it does not bind the real holder publication yet.

Ordinary original exceptions become FAILED_VANILLA with the identical Throwable,
including checked exceptions, Errors and nested CompletionExceptions. Only
ChunkProtocolException and failures outside the correctly admitted original body
become FAILED_BARRIER. The executor completes outcomes after releasing the
ownership scope and lease. Inputs and continuations are released on every
terminal path. Blanket conversion of an ordinary vanilla exception into a
barrier and the public arbitrary-Runnable admission overload have been removed.

A claimed transaction finishes synchronous ingress after shutdown, work
retirement or lifecycle replacement. An active claim also excludes another
claim for the same world-instance target until synchronous exit, even if the
directory has moved that target to another owner. This orders old/new submissions
without a wait or a transfer of the old claim. Following stages resolve the
directory again. Closing services defers disposal of a claimed light batch.

`forget` acknowledges a batch after its consumers settle. Final
`ChunkPrerequisiteServices.releaseRetiredLifecycle` requires a retired lifecycle
with no active claim, then releases POI/light/metadata records, work registrations,
holder evidence, demands, stages and dispositions. Lifecycle activation uses one
monotonic scalar rather than permanent per-coordinate tombstones. An old token
cannot be recreated after cleanup. Lifecycle cleanup never deletes replacement
data. The production registrar must call these acknowledgments at the real
terminal boundary; Part A alone installs no live cleanup hooks.

## Evidence and scope

`MinecraftLightReadFixture` executes the unmodified 26.2 read, real POI consistency,
real ThreadedLevelLightEngine retain/queue, ChunkTaskDispatcher.submit,
AbstractConsecutiveExecutor.schedule and StrictQueue.push. Only world environment
accessors and asynchronous executor progression are controlled. The known-empty
POI fixture uses actual packed-storage installation; the installed-entry variant
supplies actual PoiSection objects. The test adapter asserts original DataLayer
reference, section, per-section order, active lease and INVOKING at each call.
The fresh fixture calls the actual `$3` empty branch and verifies the original
ProtoChunk and FULL-to-replaceable metadata operation.

LIO-01..16 cover registration without receipt; fresh prerequisites; retain/BLOCK/
SKY and zero-input behavior; genuine receipt; pending propagation; duplicate
wakeups; old lifecycle/work fences; frozen claims; partial and enqueue-before-throw
failures; shutdown/rejection; supersession; cleanup; and both completion gates.
Additional cases cover every identity component, foreign service registrations,
unbound claims, duplicate physical kinds, incomplete sealing, invalid read suffix
after all ingress, throwable identity/nesting, repeated coordinates, replacement
claim ordering, and full retired-lifecycle reclamation. Duplicate RETAIN/BLOCK/SKY
and partial replay are zero in these deterministic tests. Real engine processing
can remain pending while the receipt and stage complete with no retained lease.

The pinned Minecraft jar has SHA-256
`A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4`.
The original read order remains section reconstruction, POI consistency, first
retain, BLOCK, SKY, next section, remaining read. Real consecutive-executor
rejection is exercised: its queue push can succeed and both execute attempts can
be rejected and logged without the original caller receiving an exception.
Method-return counting alone therefore does not satisfy the insertion contract.

These are deterministic bound-method and protocol tests, **not live Fabric
acceptance, production ingress Mixins, FUT tests or an enabled Decision A graph**.
The recovery assertion in LIO-12 verifies exact throwable transport into one
future recovery callback; the actual adjacent `$4` and holder filter remain Part B.
No live reload or fresh acceptance run is claimed by this checkpoint.

## Synchronization and safety audit

The existing custody ReentrantLock orders bounded identity, readiness, cursor,
claim and terminal-map updates. Registry metadata locks protect only their maps;
retired-lifecycle reclamation follows registry-to-custody order, with no outer
custody lock held while calling a registry. The light service has no separate
lock. No Minecraft callback, physical light call, future completion or asynchronous
computation runs under any new metadata critical section. The original callback
is no longer executed under PendingVanillaStage's object monitor.

Production changes contain zero blocking owner/producer/peer waits, arbitrary
Runnable dispatch/retention, main-thread fallback, ThreadLocal correlation,
identity-hash authority, or lease retention across asynchronous lighting. Search
hits for `.get` are bounded map/list/Optional observations. The existing
OwnershipContext foundation still validates its lease; it is not used to discover
a holder, work token or light batch. New authority is passed explicitly by claim.
Test-only executor Runnables model the original light service queue, and test-only
constructorless world stubs are excluded from the production jar. Generated
Minecraft `logs/` are ignored like the existing disposable server/run outputs.

Workers remain 1, parallel gameplay OFF, Stage 3/R-CHUNK-01 PARTIAL, Stage 4 and
C2ME NOT STARTED. Build versions, public vanilla futures and later stages are unchanged.
