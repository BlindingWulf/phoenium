# Production initialized-input discovery and operation receipts

## Final serial closure — 2026-09-08

The connected implementation and acceptance status is now
**STAGE3_SERIAL_CORRECTNESS_FROZEN** within the supported scope and explicit deferrals
in [STAGE3_CLOSURE_REPORT.md](STAGE3_CLOSURE_REPORT.md). That report supersedes the
historical PARTIAL/contributor status below and records the current 690-test build,
strict installed probe, actual fresh/disk/reload and saved-vault proof on the same
production SHA. Historical tests and limited evidence below remain honestly labeled.
No Stage 4 or PASS tag was created.

## Saved villager contributor — 2026-09-06

The expanded integrated-FULL assignment supersedes the historical contributor-only
stop instruction below. `FullSavedVillagerInputs` now contributes to the production
`FullInitializedInputs.bind` path using the existing protected, revisioned saved
scalar facts. Its `ENVIRONMENT` purpose records the constructor origin and the
saved, clamped position's section; saved passengers contribute their own positions.
Discovery performs no constructor, saved reader, brain construction/tick, schedule
sample, live-reference lookup or random operation. The normal receipt and retained
target-use machinery still owns admission evidence and invalidation.

The source authority is the pinned official-named Minecraft **26.2** artifact,
Loom mapping identity `043a8b3edf`. Merged binary SHA-256:
`B85B40FF77A813A46A4078F04B1CA4FA291942F01AF146F9F5F391D4D9F5FFAB`;
matching Loom-generated sources SHA-256:
`DDD82534C200D6610309A1EB35C126BED5FBD4D0118CADDC828610914F3988CD`.
Inspected source extracts remain ignored in `run/villager-input-20260906/sources/`;
no upstream source is copied into production. This contributor adopts no Paper,
Folia or Moonrise implementation or cross-version assumption.

The audited original chain is `LivingEntity.<init>` → `Villager.makeBrain` at
`Vec3.ZERO`; then `Entity.load` sets clamped saved `Pos` before the inherited saved
`Brain` reader, `AgeableMob.setAge` and the final `Villager.refreshBrain`.
Negative age crosses the initial adult boundary and adds a baby-brain refresh;
the concrete villager reader always refreshes again. Each new brain's
`registerBrainGoals` calls `Brain.updateActivityFromSchedule`, which may read the
adult/baby schedule through `EnvironmentAttributeSystem`. The actual default biome
layer uses `BiomeManager.getNoiseBiomeAtPosition(double,double,double)`, with
floor/block → quart → section conversion and no spatial interpolation. The
constructor origin must therefore remain in the footprint even for a distant
saved villager and even when saved `NoAI` is true.

These are **BIOMES,false** reads through `LevelReader.getNoiseBiome`, whose null
branch retains original `ServerLevel.getUncachedNoiseBiome` generator behavior.
They are not FULL demand or a permission to replace a present lower-status biome
object with generated biomes. Query status, actual lower-object provenance,
clock/environment-cache writer exclusion and regional enclosure are separate
finishing obligations. A data pack that only changes the sampled values does not
change this base footprint; arbitrary installed callback implementations are not
proved by the base source audit.

Villager attributes, active effects and sleeping-position fields no longer cause
the blanket contextual-dimensions rejection. During this original fresh-entity
reader chain `Entity.firstTick` stays true: dimension refresh skips positional
collision repair, and sleeping restoration skips `setPosToBed`. The sleeping
coordinate and serialized HOME/JOB_SITE/etc. positions are retained entity data,
not inputs queried during initialization. `Brain.stopAll` has no running
behaviors on these newly constructed brains; schedules choose activities without
starting behaviors. Inventory, offers and gossips decode into the new object;
restocking/trade generation, POI lookup and brain ticking occur at later gameplay
edges. Inherited Team writes remain an independent shared-service obligation.

`FullSavedVillagerInputTest` independently executes the original supplier,
constructor, saved reader and original biome-layer installer, verifying adult and
baby refresh counts, saved Brain/no-Brain cases, actual observed positions, and
restored age, sleeping pose, scale, effect, profession, level, XP, inventory,
offers, gossips and cross-dimension HOME memory. Separate production discovery
cases cover prepared proto/reload inputs, saved passengers, stale position aliases,
default/clamped coordinates and invalid positions while asserting zero entity
construction and no gameplay-random consumption. These original-method and
controlled input cases are contributor evidence; final integrated finishing,
disk reload, publication and installed-artifact acceptance are recorded with the
connected implementation rather than inferred from discovery alone.

The connected reader census adds `FullSavedEntityInputs`, an explicit pinned
built-in ID dispatch for the other constructor/reader families. The 120 concrete
and inherited reader source files were checked along their invoked setters,
initial brain/goal construction and first-tick dimension paths. This is an input
classification, not a shared-service or foreign-entity permission. Raid saved
membership, Wither boss naming, ZombieVillager conversion effects, neutral anger,
legacy tame owners and ender-pearl owner registration remain named service/D1/D2
edges. A new or modded concrete reader does not silently inherit this census.

Contextual tadpoles now use a conservative previous width of zero in the existing
original two-attempt collision envelope. The new frog's scale is unchanged by
`ConversionType.SINGLE`: source attributes are not copied; stored sleeping position
is copied without copying sleeping pose; built-in copied effects have no SCALE
modifier. This includes undersized, enlarged and sleeping saved sources without
running their reader during discovery. `Frog.finalizeSpawn` also creates a
`SpawnContext` before positional repair. Its original `BiomeManager.getBiome`
chooses one of eight quart corners, so discovery retains both horizontal quart
candidates on each axis. These BIOMES inputs can extend beyond the collision
window (for example saved x=z=17.8). Vanilla frog variant conditions use biome
membership; registry-installed structure and other predicate contexts require
their own lower-status/context contributor.

`FullSavedTextInputs` captures a bounded detached text tree and normalized saved
rotation while watching the original nested compound/list and rotation aliases.
Discovery uses the original component codec, then walks decoded sibling,
translation argument, object fallback, separator and hover-text children without
calling the resolver. It records static block coordinates, selector scope facts,
score keys and NBT source/path/interpret continuations. Literal and other
context-free display components now bind through production. Dynamic text requires
the connected protected context producer; in particular block `isLoaded` must
keep original public availability and interpreted NBT can introduce another
bounded discovery pass. This helper never evaluates a selector, serializes a live
entity/block entity, reads command storage, or grants the resulting contexts.

The independent `FullSavedEntityInputTest` and `FullSavedTextInputTest` exercise
the original reader families, contextual conversion, exact original fuzzy biome
selection, scalar/relative/local coordinates, nested component aliases and the
production proto/reload binder. Final test outcomes and remaining context-provider
work belong to the integrated verification record; this census alone is not
whole-registry or whole-FULL acceptance.

## Current checkpoint — published provenance and independent acknowledgement, 2026-09-05

**PUBLISHED INPUT PROVENANCE + INDEPENDENT ACKNOWLEDGEMENT: PASS** within the
connected, controlled scope below. **Overall initialized-input checkpoint: PARTIAL.**
The existing prepared branch remains implemented; published targets now supply
independently retained evidence to the same actual FULL_FINISH input evaluator.
This current section supersedes the published-input limitation and cleanup note
in the historical prepared checkpoint retained below.

Integrated FULL: PARTIAL. General production FULL selection: DISABLED. Vault: OPEN
and unchanged. Stage 3 / R-CHUNK-01: PARTIAL. Stage 4: NOT STARTED. One simulation
worker, parallel gameplay OFF. No Stage 3 PASS tag or private getter is enabled.

Started clean at `cb47a8035a1205bea3bf95364c99f1f0fc69ef6a`, production SHA-256
`2FF035AE64FF64D79158E8023D39794007A388F2987DDCC154460F04D24BED9E`.
Minecraft 26.2 / Java 25 / Loader 0.19.5 / Fabric API 0.159.0+26.2 / Loom 1.17.20 /
Gradle 9.5.1 and server properties are unchanged. One agent wrote this checkout.

### Exact original producer and publication

`GenerationHolderCompletionMixin` still intercepts the map call dominated by the
original winning `GenerationChunkHolder.acquireStatusBump(FULL)` CAS. Selected
maps keep the existing `FullGenerationSource` path. Ordinary ChunkMap takes its
unchanged original supplier; `FullPublishedInputs` records that baseline origin.
The fixture chooses separate baseline and selected maps before either task starts.
No work changes mode, and no selected failure falls back to the original supplier.

Before the original async FULL invocation can run inline, the holder carries its
world/server, lifecycle, WorkId, exact holder/cache/step and reservation lineage.
The actual `registerAsync` edge verifies the pinned generation/loading FULL step,
original input and context, then runs the original baseline `supplyAsync` unchanged.
The exact returned R is matched at the original adjacent handler. No new winning
reservation, successful prepared receipt or admitted conversion is fabricated.

`FullPublishedHolderMixin` observes both real publication winners inside
`completeFuture`: an existing subscriber's `complete(result)`, and the null-slot
CAS installing the original completed future. The exact `ChunkResult` wrapper and
slot are installed as candidate evidence **before** those operations can reenter.
Validation succeeds only when that very slot actually contains that very successful
wrapper, original value and LevelChunk reference. Announcement follows the original
winner. Duplicate notices cannot create another publication or retained use.
Proto and in-memory Imposter producers both return their actual LevelChunk here;
the evidence model also preserves wrapper/value identity rather than treating a
nonnull object, coordinates, isDone or an identity hash as lineage.

Exceptional/cancelled/incomplete/unloaded original results do not prove publication.
Original pending-slot invalidation and lower-slot replacement fence old lineage;
adversarial wrong-status, replacement-slot and copied-result tests reject forged
evidence. Those corrupted negative slots are never successful fixture producers.
Discovery uses nonblocking completion-state reads and never joins pending FULL.

### Three lifetimes and actual ordering

| Release | Actual producer / effect |
| --- | --- |
| Consumer input acknowledgement | Removes the source plan, receipt, saved subscription, facts, edges and its own target uses. Each published use owns an original `increaseGenerationRefCount` / `decreaseGenerationRefCount` pair and associated save-sync dependency. It creates no ticket. Receipt-specific acknowledgement ignores an older plan after rediscovery. |
| Producing execution settlement | The baseline adjacent handler settles, then clears R, map/cache and transient value references and reclaims only its WorkId execution records through `settleFinishedExecution`. The historical WorkId remains a value in exact publication evidence; it is not revived or active authority. Successful load/EMPTY now uses the same execution-only cleanup. |
| Holder retirement/replacement | The original pending-unload map removal, actual visible holder replacement, explicit lifecycle retirement or shutdown fences new acquisitions. Old evidence and lifecycle custody remain until outstanding uses acknowledge. Exact holder attachments prevent H1 cleanup from touching H2. |

The two previous `releaseFinishedWork` callers were audited. Load success now
settles only its execution; failed/terminal load and stopped preparation still
perform genuine lifecycle retirement. Terminal reclamation waits for other
registered work and the retained publication lifetime. Registry and custody
metadata share the same short gate, avoiding inverse registry/custody lock order.
No original generation or save pin is removed early to manufacture zero counts.

Actual holder eligibility includes the visible holder identity, ticket entitlement,
original highest allowed status, slot/lower-slot identity and current lifecycle.
The original chunk cache's main-thread reference supplies observation affinity;
ordinary generation registration may start on a different worker. Publication
attachments are visible across those threads. Acquisition reserves metadata,
takes the original save pin outside the gate, then revalidates before installing
the operation receipt. Replacement during pin installation rejects the use and
acknowledges only that pin. Completion and release callbacks run outside the gate.

The original unload test schedules the real save-sync-dependent callback. The
callback remains queued behind the retained input use; its eventual original
pending-map removal retires the holder and then invokes the controlled world
unload endpoint. This proves the permitted holder mechanism, not a new DEMOTE
scheduler or regional-save implementation. The last consumer leaving a still
resident target does not unload it. Publication does not freeze mutable chunk
data or establish that gameplay writers are inactive.

### Connected result, bounds and cleanup

In each connected proto/Imposter and inline/deferred case, Y has one genuine
original successful FULL publication. X has one selected admitted preparation.
X's **actual input evaluator is SATISFIED**, its finishing stage remains QUEUED
with aggregate **NOT_READY**, and its finishing claims, suffix execution and
public FULL success remain zero. No post-load reexecution or preparation of Y
occurs. Mixed windows retain prepared and published targets and record legitimate
absence; existing private X/Y cycles retain their no-public-FULL-wait behavior.
Collision purposes still require DYNAMIC_SHAPE_MUTATION and independent writer,
query, regional-enclosure and service obligations.

Two consumers retain two independent uses of Y; acknowledging the first leaves
one, and cancelling the second leaves zero. Y's exact publication and resident
lifecycle remain valid, including unrelated demand/work in that lifecycle.
Acknowledgement reentry can acquire a newer use; closing the old use again leaves
it intact. Actual target retirement retains one old record until its use closes.

The two-consumer capacity test preserves pending source demand and progresses on
real acknowledgement. An operation that cannot fit the configured consumer/edge
limit is UNRESOLVED rather than promised a nonexistent release-to-progress event.
Across 34 release/rebind cycles, the active plan count stays one and its consumers
stay two; after acknowledgement plans/consumers are zero, published records one,
published uses zero and transient producer executions zero. True teardown reclaims
the remaining publication, holder, lifecycle, work and custody roots. External
test receipt/future/actual-object aliases are explicitly outside these roots.

Existing plan/edge/consumer/fact limits remain unchanged. The publication index
caps 8192 resident records, charging 256 logical bytes per record; acquisition
uses count against the shared input-consumer capacity. Allocation is at original
winning FULL registration; transient references leave at original handler
settlement; the compact record leaves at acknowledged true retirement. If tracking
capacity refuses an original reservation, vanilla demand still runs, but its later
publication is explicitly UNRESOLVED for this binder: no hot attachment or
reconstructed provenance is promised. One coalesced input notification and one
publication observer per map are reused, with a bounded index pass only on actual
visible-map publication. There are no per-target polling listeners or no-progress
resubmission loops. The 16 MiB fact charge and record charges are logical accounting,
**not measured Java heap bounds, low-RAM proof or Android optimization evidence**.

### Verification and evidence

Evidence: `run/full-published-input/session-20260905/`. Prior XML/JAR were saved
once. Fresh baseline: exit 0, **392 tests / 28 suites**. Final clean build,
client-test compilation and server preparation: **419 / 29**, all 11 tasks
executed, exit 0, zero failures/errors/skips. All 392 baseline classname/name
occurrences remain; the new published class executes 27 cases. The existing
34-cancellation and eight-times-34 cleanup regressions remain executed and pass.

The selected disposable installed probe runs **18 published + 19 prepared-input
+ 8 registration cases**, with the actual production Mixins and no JUnit agent.
A separate baseline smoke covers normal load/EMPTY and ordinary server operation.
Both final runs must have launcher/child exits 0, no server crash or shutdown
exception, an observed original seed command, and zero terminal registry roots.
World endpoints, lower-generation outputs and scheduling remain controlled;
in-memory reload is not disk reload or integrated saved-world/client acceptance.
The installed Fabric lifecycle callback runs with its ordinary loaded-chunk
collection initialized in the controlled endpoint. No callback is suppressed.

Preserved failures include the initial fixture interface compile error; the genuine
connected UNRESOLVED red test; test-accessor corrections; a forbidden broad-monitor
regression and its removal; stale-artifact launcher refusal; Fabric's missing
controlled-world loadedChunks field; and the baseline observation-thread defect.
The old launcher incorrectly accepted the latter's exit-0 crash. A log audit
corrected that result to FAIL and added crash-report/shutdown/seed assertions.
Its original reported acceptance and corrected diagnosis are both retained.

Final artifact identity, matching prepared/external copies, module/log hashes,
world seeds, executed XML totals and wrapper/child exits are in
`final-manifest.json`. The server-only production JAR excludes JUnit, test agents,
hooks, probes, generated prototype fragments, Byte Buddy and client code.
Generated evidence, worlds, caches and artifacts remain ignored.

Final production SHA-256:
`A4B57EAEC74D0072A82A4035111A3B44D2ECB928700FA9728475EF8171C7FAC5`.
It matches the built JAR, prepared server and both final disposable copies. The
315-entry ZIP inventory contains no prohibited test/client payloads and declares
Fabric environment `server`. Final commands were
`gradlew.bat clean build compileClientGameTestJava prepareStage3ClientServer --no-daemon`
(all 11 tasks executed), the separate harness build, both installed probes and
`git diff --check`, all exit 0.

Final baseline: `run/full-production-integration/transform-20260905-231534/`,
seed `2311601226506048603`. Final selected:
`run/full-production-integration/transform-20260905-231543/`, seed
`5491335090803380787`. Each has launcher/child exit 0 and no crash report or
shutdown exception. Both separately report 529 ordinary load/EMPTY applications
and publications; these are not X's selected FULL counters. Terminal load graph,
R/POI indexes, custody, work and lifecycle counts are zero. Selected assertions
add the publication/use/transient/holder teardown checks described above.

Installed modules: Minecraft 26.2, Java 25, Loader 0.19.5 / MixinExtras 0.5.5,
Fabric base 2.0.4+ece063239e, command API 3.1.0+00cb03469e, lifecycle events
4.1.4+29b6eb019e, production Phoenium 0.2.0-stage2; the selected run additionally
loads the separate phoenium-full-registration-probe 1.0.0. No all-module Fabric
compatibility claim follows from this scope. No client/vault campaign, account,
GUI, network profile experiment, Android change or compatibility mod was added.

**One exact next implementation dependency:** add a concrete saved-reader/context
contributor for the currently UNRESOLVED saved villager path, deriving its actual
initialization query-purpose footprint and feeding this existing operation receipt
without executing the reader during discovery. Further dimension contexts,
target-demand routing gaps, private-query validation, full regional enclosure,
D1/D2/C/P/S/W, original finishing and separate STATUS_PUBLISH(FULL) remain visible
and unimplemented. This published-input checkpoint is not their authorization or
completion and is not a repair for ordinary vault ticking.

## Historical privately prepared checkpoint — 2026-09-05

**INITIALIZED-INPUT DISCOVERY + RECEIPT BINDING: PARTIAL.** A functioning production
component connects original asynchronous registration, actual admitted preparation,
saved-input discovery, target preparation demand, independent consumer retention,
and the real FULL_FINISH prerequisite evaluator. The positive privately prepared
scope below is implemented and tested. Already-published input provenance and
additional concrete reader/context contributors remain unbound; their rejection is
not completion of those branches. No approval or semantic-method-split work was repeated.

Integrated FULL and Stage 3 / R-CHUNK-01 remain PARTIAL; general production FULL
selection DISABLED; vault OPEN and unchanged; Stage 4 NOT STARTED. One simulation
worker, parallel gameplay OFF. No Stage 3 PASS tag. Ordinary ChunkMap implements
only the read/notification adapter, **not** FullGenerationSource.

Start: clean `31c83fd0f62d27f7f79f1fef16695fa2a2e746e5`, production SHA-256
`BCD87DE959F1189594330ABB83488A5433B8287DCC3CC59AF62016CE05CBB97D`.
Pinned build configuration is unchanged: Minecraft 26.2, Java 25, Loader 0.19.5,
Fabric API 0.159.0+26.2, Loom 1.17.20, Gradle 9.5.1. Build parallelism is distinct
from gameplay parallelism. Only this writing session modified the checkout.

## Connected mechanism and protected observations

| Fact / producer | Protected observation, invalidation, and lifetime |
| --- | --- |
| Proto saved root list | The actual retained ProtoChunk from the existing generation graph. Tracking activates only for selected admitted preparation; ordinary proto readers/writers keep their inactive baseline behavior. Acquisition of its discovery handle occurs during admitted preparation; scalar allocation/discovery follows preparation under retained input custody, after all older raw generation writers release. The original generation pins remain live. Original getEntities exposes a guarded list and original addEntity participates in the same nonblocking input turn. List mutations invalidate the source revision. |
| Reload saved roots | The real SerializableChunkData.postLoadChunk factory retains a guarded view of its original parsed entity list and passes that same view to the original processor. The original processor is invoked only at its later original edge. No reader, constructor, post-load, random value, ID allocation or shape call is run to discover inputs. In tests this is explicitly an in-memory reload processor, not disk reload. |
| Saved scalar facts | Root/passenger path, id, clamped Pos, Age and the presence of attributes/effects/sleeping context. Facts are immutable and versioned. Discovery owns a nonblocking read turn, not a post-read version guess. The original input transfer excludes earlier raw aliases/writers during first attachment. Attached CompoundTag maps and position/passenger lists route put/remove, iterator/entry mutations, set/add/clear through that input turn. Actual public mutation methods are exercised on both paths. New inserted data still requires the ordinary exclusive input handoff; arbitrary pre-transfer backing-map aliases/reflection are not granted authority. |
| Concrete dimensions and threshold | The pinned built-in TADPOLE/FROG dimensions and Tadpole threshold, initialized by the pinned game. No installed contributing runtime writer changes these defaults. Saved dimension-affecting context is explicitly unresolved. This is not a general mod/dynamic-registry context validator. |
| Visible holder and absence | Original ChunkMap visible lookup on the runtime's serial publication thread; original promoteChunkMap publishes the cloned volatile map before its notice. The plan freezes exact holder identity and ticket level, including null/disallowed FULL,false results. Original holder/cache queries are never used to load an entity or to fetch a private chunk. Lookup/publication endpoints and generation outputs are controlled in the harness; publication itself is original. |
| Target preparation | Existing FullRegistrationRuntime graph and successful Prepared receipt, exact world/lifecycle/work/holder/attempt/actual, actual SPAWN and optional FULL slot references, generation-save retention and current route. Old preparation failure supplies no receipt. Public conversion/FULL futures are not prerequisite subscriptions. |
| Changes and capacity | Atomic coalesced notification, registration/preparation/actual raw release, saved writer completion, original visible-map publication, RegionDirectory proposal/publication/completion and exact consumer acknowledgement. Subscribe before observation; no per-neighbor future listeners, lost flag-clear wakeup, owner wait or resubmission loop. Plan invalidation is rechecked in the real admission fence. |

The discovery rule is the pinned Entity.fudgePositionAfterSizeChange horizontal
width-delta envelope, CollisionGetter.findFreePosition inflation, and
BlockCollisions' floor/epsilon/one-block cursor margins. Both searches share this
horizontal enclosure. Concrete saved positions determine the chunks; there is
no fixed chunk radius, fixture-coordinate branch or empty-air assumption.
Root and passenger tadpoles are considered in their saved reader order; mature
Age (including AgeLocked=true) adds difficulty and collision requirements.
Underage roots add no conversion queries. The reference witnesses in
FullNonselfInputDecisionTest and the original-method query conflict independently
justify the X/Y and corner expectations. Tests do not call the planner to derive
expected sets.

Every collision target conservatively carries DYNAMIC_SHAPE_MUTATION in addition
to COLLISION. This includes possible audited shulker FULL,true / IMMEDIATE work;
no shape is evaluated and no block entity is created during discovery. Block
sections, shape progress, world border/biome/service state and live entities are
not read to prune this input set. Their later execution, writer exclusion and
full callback enclosure remain independent obligations. A route for each target
identifies required regions; it does not converge them or grant a multi-region lease.

## Resolution, exact consumers, and admission

For a present entitled holder without registered preparation, the component uses
original scheduleChunkGenerationTask(FULL, map) and runGenerationTasks. The
original holder decides the reservation and creates a public subscriber slot only
where its request policy does so. No new ticket is inserted. Earlier generation
prerequisites may still be pending; their original producer must advance. The
binder listens to registration/preparation/release events, never neighboring
conversion success or public FULL completion. It does not recursively execute
finishing or wait under an owner lease.

Typed target observations distinguish PUBLISHED, PREPARED, PREPARATION_PENDING,
ABSENT, STALE, TOPOLOGY_PENDING, TERMINAL, UNROUTABLE and CAPACITY. No-receipt is not
absence. A genuinely absent/disallowed FULL,false collision target is recorded as
absence with its exact visible-holder observation; a newly visible target forces
rediscovery. Already-published original FULL is recognized, but without its actual
publication lifecycle/work/retention producer it remains UNRESOLVED. It is not
assigned a fabricated preparation or successful private-input receipt.
Permanently unroutable active target demand is an explicit unresolved routing
obligation, distinct from a real pending topology event or cancellation. Existing
source-work unroutable barrier behavior remains unchanged and tested.

A Receipt names source server/world, source WorkId, the actual FULL_FINISH StageId,
monotonic plan attempt, saved revision, route view and exact target evidence.
An acknowledged operation input consumer is TERMINAL, even if its source work
remains active; a late notice cannot turn it into a capacity waiter or recreate a
plan. Only the installed receipt with independently retained target consumers validates;
an equal reconstructed record, another work's receipt, different stage/attempt,
or edited target/purpose is stale. Input evidence is never an execution claim.
Ownership and receipt are installed before publishing the real finishing stage.
Its real readiness method consumes and records SATISFIED for supported inputs,
then returns NOT_REGISTERED / aggregate NOT_READY because the other bindings
are absent. The stage stays QUEUED; its throwing unbound continuation is never
invoked. No empty successful callback or APPLIED finishing counter is used.

D1, D2/P, C, S, W, private query-operation validation, complete regional enclosure,
original finishing and separate STATUS_PUBLISH(FULL) remain unimplemented.
Naming them is not implementing their validators. Existing synchronous fault
recording/no-expansion/no-replay behavior is unchanged. No receipt enables a
private getter, neighbor mutation, ticket, ready-cache entry, tick or publication.

## Bounds and lifetimes

Production defaults are 256 plans, 256 positions per plan, 8192 edges, 8192
prepared consumers, and 16 MiB charged fact capacity. Each admitted source plan
reserves one 64 KiB fact allocation; facts also cap 4096 entity occurrences,
32 passenger levels and type-id length. Limits are constructor configuration,
not the prototype 4/8 limits or a view-distance setting. Refusal retains demand
and has a named capacity-release event. Source facts/listeners are not allocated
for plans waiting for capacity, and pending plans retain no partial target pins.
Preparations can proceed without source-plan or consumer capacity, which the
one-plan and two-consumer tests demonstrate before any FULL publication.

One atomic pending notification is shared per runtime. Directory listeners are
one per actual observed map plus one route listener; saved-data listeners belong
to admitted plans. Guarded saved collections are intrinsic input data views, use
weak references to the discovery tracker, except the reload root list, which
retains its small custody tracker across the original processor factory's
allocation window. Neither retains a plan, graph, chunk or callback after its
subscription is acknowledged. Acknowledgement removes source subscriptions,
facts and installed receipts before closing target pins. External graph/receipt/
future aliases and original deferred processors can keep their ordinary data
alive; these are reported separately from protocol roots. This is finite logical
record/payload accounting, not a measured Java heap-byte bound or a bound on all
pre-existing vanilla demand/generation graphs.

Two pair consumers retain four target uses. Acknowledging one leaves two and the
other operation's evidence remains valid; acknowledging the second leaves zero.
Replacement temporarily retains three graphs. The old explicit prepared consumer
reaches zero on acknowledgement, but X's original generation cache still pins old
Y while X's original result is pending. Those legitimate generation pins must not
be forcibly released to manufacture cleanup. Authentic shutdown and actual task
release then yield zero graphs, input plans/edges/consumers/fact reservations,
notifications, original-demand indexes, generation holders and custody records.
The new visible holder is preserved. Original APPLIED-before-claim-release,
SPAWN producer reentry, cancellation and no-resurrection assertions remain intact.

## Evidence and exact remaining scope

Evidence root: `run/full-input-binding/session-20260905/`. Baseline prior XML and
JAR were preserved; each executed iteration has a distinct log/exit and copied
XML. `connected-red-02` fails on the missing component after genuine preparation;
`connected-red` is the preceding fixture bootstrap failure. Later compile/fixture
failures, the broad-monitor regression, and the correction to the replacement
retention expectation are preserved, not counted as passing runs.

The focused class covers original wrapper/preparation/real evaluator, proto and
in-memory Imposter X/Y in both orders, corner/passenger windows, six-target
replanning, actual permitted demand, delayed raw release, inline and duplicate
notices, saved mutation producers, absence/new presence, real topology completion,
object/holder replacement, source-plan/payload and consumer capacity, preparation
failure, exact receipt rejection, non-air mutation requirements and acknowledged
retention. Unsupported concrete readers and dimension contexts have explicit
unmet requirements. Already-published target binding is not positively validated.

The installed harness extends the existing disposable server probe. Its 19 input
cases and 8 retained registration cases run through actual production Mixins
without the JUnit agent. Target lists come from production discovery over saved
data. Controlled world endpoints, lower-generation results and the in-memory
reload processor are explicit. A separate baseline smoke retains normal vanilla
FULL behavior and load/EMPTY evidence; its counters are not selected FULL inputs.
Neither run is integrated saved-world/client acceptance. No vault/client campaign
or profile-provider experiment was run.

**Exact next implementation dependency:** bind the already-published FULL target
branch to its actual holder/lifecycle/work and acknowledged retention producer,
so recognizing a successful public slot can supply a valid operation input without
inventing a FULL reservation or retiring another holder's demand. Additional
concrete reader/context contributors remain visible unmet requirements. Private
query execution and full service participation are still unimplemented.

## Final verification

| Fresh execution | Result |
| --- | --- |
| Baseline `build compileClientGameTestJava --rerun-tasks --no-daemon` | Exit 0; 365 tests / 27 suites; zero failures, errors or skips |
| Final `clean build compileClientGameTestJava prepareStage3ClientServer --no-daemon` | Exit 0; all 11 tasks executed; 392 tests / 28 suites; zero failures, errors or skips |
| Baseline occurrence preservation | All 365 original classname/name occurrences remain; 48 FullNonselfInputDecisionTest reference cases and 28 FullProductionRegistrationTest cases retained |
| New connected input class | 27 executed occurrences, including four proto/Imposter/order combinations, both real saved-collection writer paths and shared-position custody exclusion |
| Final disposable probe harness build | Exit 0; kept outside the production JAR |
| Final actual-Mixin baseline smoke | Launcher 0 / server child 0; PASS; separate ordinary load/EMPTY 529 applications and 529 publications; no selected FULL input probe |
| Final actual-Mixin selected run | Launcher 0 / server child 0; 19 input cases + 8 registration cases PASS; input SATISFIED / aggregate NOT_READY; selected post-load 0 / public FULL 0; no JUnit agent |
| Runtime terminal accounting | Both servers report zero retained graphs, returned index, POI index, custody, work and lifecycle entries; selected assertions additionally check zero input plans/edges/consumers/payload/notifications/demand index |
| `git diff --check` | Exit 0 |

Final production SHA-256:
`2FF035AE64FF64D79158E8023D39794007A388F2987DDCC154460F04D24BED9E`.
It matches `build/libs/phoenium-0.2.0-stage2.jar`, the prepared server copy and
both final disposable server copies. ZIP inventory has no tests, agents, hooks,
probes, generated prototype fragments, JUnit, Byte Buddy or client code. The
packaged Fabric environment is `server`.

Final baseline: `run/full-production-integration/transform-20260905-191408/`,
seed `-837859423505384468`. Final selected run:
`run/full-production-integration/transform-20260905-191425/`, seed
`-4599659048645338987`. Each retains its process log, actual transformed class
exports/disassembly and `acceptance.json`. Their ordinary 529 load/EMPTY
applications/publications are independent of the selected input assertions.

Installed modules: Minecraft 26.2, Java 25, Loader 0.19.5 / MixinExtras 0.5.5,
Fabric base 2.0.4+ece063239e, command API 3.1.0+00cb03469e, lifecycle events
4.1.4+29b6eb019e and production Phoenium 0.2.0-stage2. Only the selected run adds
the separate phoenium-full-registration-probe 1.0.0. This is not evidence for
all modules of the development Fabric API bundle.

The retained `run/full-input-binding/session-20260905/final-manifest.json`
contains totals, exact matching copies, module/log hashes and launcher/child
results. This document records the stable acceptance facts in Git; generated
worlds, caches, logs, probe JARs and production artifacts remain ignored.

The next published-input branch is unfinished implementation, not a new approval
request. The specific missing connection is a successful original public FULL
slot/reservation to target WorkId and independent acknowledged retention.
`ChunkPrerequisiteServices.releaseFinishedWork` retires the holder lifecycle;
reusing that cleanup for ordinary published input would retire unrelated work.
The next change needs the real published producer and a lifetime-preserving
consumer acknowledgement before this branch can return SATISFIED. No published
receipt, private-query implementation or service participation is inferred from
the positive privately prepared cases above.
