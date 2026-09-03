# Stage 3 chunk runtime map

Status: empirical reconnaissance complete; authoritative chunk ownership is still
unimplemented. This map records the exact symbols in the pinned Minecraft 26.2
artifact and the stages observed by both the RCON-only load trace and the real-
client readiness trace. It is deliberately a mechanical map, not an approval to
redirect any vanilla continuation. The readiness-specific evidence is in
[`STAGE3_CHUNK_READINESS_EVIDENCE.md`](STAGE3_CHUNK_READINESS_EVIDENCE.md).

## Scope and evidence identity

The bytecode inspection used the official-named, non-obfuscated artifact:

| Artifact | Value |
| --- | --- |
| Minecraft mapped artifact | `minecraft-merged-deobf-26.2.jar` |
| Artifact path | `C:\Users\Admin\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar` |
| Artifact size | `37,399,549` bytes |
| Artifact SHA-256 | `A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4` |
| Minecraft | `26.2` |
| Java | `25` |
| Fabric Loader | `0.19.5` |
| Fabric API | `0.159.0+26.2` |
| Loom | `1.17.20` |
| Gradle | `9.5.1` |

The production server artifact used by the final readiness run was
`build/libs/phoenium-0.2.0-stage2.jar`, 239,443 bytes, SHA-256
`0952DE617A8C29718B12DC3C33FB63483AB3503619DDA12DDE63888D4C693E3F`.
The same artifact was copied into the disposable dedicated server's `mods`
directory. A jar-entry scan found zero client-test or `net/minecraft/client`
entries.

The trace is enabled only with `-Dphoenium.stage3.chunkTrace=true` and an
explicit target list. It stores primitive values, strings, object identity
labels, and immutable route snapshots. It does not retain a chunk, holder,
world, or future as a strong field. The future correlation table has weak
future keys, and the event list is bounded by `maxEvents` (10,000 by default).
When the property is absent or false, the mixin calls return after a static
disabled check and no event file is written.

## Selection and lifecycle

The original selection below is retained as historical RCON-only evidence. It
does not cover player tracking, ticking readiness, client receipt, or client
removal. The follow-up real-client selection is recorded immediately after it.

The final run used a test-only RCON command that invoked the genuine
`ServerLevel.getChunkSource().getChunkFuture(32, 4, ChunkStatus.FULL, true)`.
The harness did not call `join`, `get`, or `getNow`, did not replace the future,
and did not wrap the authoritative continuation. The client GameTest process
provided a bounded clock and RCON orchestration; a client TCP connection was
not required for this chunk-only question.

The target was `minecraft:overworld`, chunk `(32,4)`. The gated explicit live
fixture covers A at `x=-4..4, z=-4..4` and B at `x=5..32, z=-4..4` for this
mode, so the route snapshot for the target was consistently
`stage3-fixture-b`, topology generation `1`, owner epoch `0`. The wider B span
is test-only geometry used to put the target outside the startup/spawn area; it
does not change vanilla chunk ownership or make B a Minecraft server.

The run performed three clean dedicated-server lifecycles:

| Run | World | Seed | Purpose | Trace events |
| --- | --- | --- | --- | ---: |
| `fresh-generation-1` | `STAGE3-CHUNK-GEN-1-1788443676413` | `3628321240088420154` | New world, target absent | 231 |
| `fresh-generation-2` | `STAGE3-CHUNK-GEN-2-1788443683711` | `1247676692653712630` | Independent new world, target absent | 231 |
| `existing-chunk-reload` | `STAGE3-CHUNK-GEN-1-1788443676413` | `3628321240088420154` | Restart first evidence world, target persisted | 170 |

The first two worlds were created with an empty `level-seed`, and their seeds
were recorded from the live `/seed` response. The third lifecycle reused the
first world directory after a normal server stop. This is the deliberately
persisted evidence-world exception; it is not used by the movement or
interaction tests.

### Follow-up real-client readiness trace (2026-09-03)

The dedicated task `runStage3ChunkReadinessTrace` used two independent fresh
worlds and a real TCP client connection to the production server jar. It did
not issue a synthetic chunk request. Each lifecycle first verified that the
target had no server `LevelChunk` (`getChunkNow=null`, `hasChunk=false`) and no
exact client `FULL` cache entry, then moved the client into the target chunk and
sampled the exact client cache until receipt. It then moved the client back to
spawn and sampled until exact client eviction and tracking-demand removal.

| Run | World | Seed | Target | Route | Trace events | Result |
| --- | --- | --- | --- | --- | ---: | --- |
| `fresh-generation-1` | `STAGE3-CHUNK-READINESS-1-1788472908795` | `-4966688072561929527` | `(4,-4)` | `stage3-fixture-a`, generation 1, owner epoch 0 | 1,456 | receipt/removal PASS |
| `fresh-generation-2` | `STAGE3-CHUNK-READINESS-2-1788472934995` | `-1217979177269415904` | `(4,-4)` | `stage3-fixture-a`, generation 1, owner epoch 0 | 1,487 | receipt/removal PASS |

The target is at the edge of the explicit A fixture and outside the readiness
server's startup/spawn view. A partial holder may appear before the target is
loaded; the precondition is specifically no loaded server `LevelChunk` and no
exact client cache entry. Both raw traces stored zero dropped events and zero
selected-event exceptions.

The client-receipt path observed the exact 26.2 method
`ClientChunkCache.getChunk(int,int,ChunkStatus,boolean)` with `FULL` and
`create=false`; the returned `LevelChunk` was checked for exact `(4,-4)`
position. Removal was the same non-forcing lookup returning `null` after the
client left the tracking view. `addedLoadedChunks()` and
`removedLoadedChunks()` were sampled as transient auxiliary sets and were not
required because their per-tick windows were legitimately missed.

The raw server sequence reached `FULL` accessibility, ticking preparation,
`BLOCK_TICKING`/`ENTITY_TICKING` promotion, ticking conversion, post-processing,
`ServerLevel.startTickingChunk`, `ChunkMap.onChunkReadyToSend`, and
`ServerChunkCache.onChunkReadyToSend` in both worlds. The ordinary fresh-world
path took the immediate send-sync branch. The delayed send callback branch was
not observed.

The same-owner target `(4,-4)` was selected after a preliminary cross-owner
probe of `(32,4)` closed itself with
`routing update crosses object owner; use transitionOwner after a quiescent
publication boundary`. No topology transition, model change, ownership
architecture change, or implementation workaround was introduced in response.

## Exact current symbols

Descriptors below are JVM descriptors from the artifact above. Synthetic
`lambda$...` names are included where the current bytecode exposes the actual
continuation or callback used by the trace.

### Request, holder, generation, and disk path

| Trace stage / status | Exact class and method | Descriptor | Important target / effect | Thread observed | Runtime / Phoenium control |
| --- | --- | --- | --- | --- | --- |
| `CHUNK_FUTURE_REQUEST`, `CHUNK_FUTURE_RETURN` | `ServerChunkCache.getChunkFuture` | `(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;` | Public request boundary; returns `CompletableFuture<ChunkResult<ChunkAccess>>` | `Server thread` | **CONFIRMED**; trace plus pre-existing diagnostic observer only |
| candidate | `ServerChunkCache.getChunkFutureMainThread` | `(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;` | Creates `ChunkPos`, adds an `UNKNOWN` ticket when `create=true`, finds/creates holder, starts the request | `Server thread` in the selected request | **CONFIRMED by bytecode**, direct method boundary not separately traced |
| `CHUNK_MAP_PREPARE_ACCESSIBLE_CHUNK_ENTER` | `ChunkMap.prepareAccessibleChunk` | `(Lnet/minecraft/server/level/ChunkHolder;)Ljava/util/concurrent/CompletableFuture;` | Gets the status-range future and applies an accessibility result transformation | `Server thread` | **CONFIRMED**; observational hook only |
| `CHUNK_MAP_RUN_GENERATION_TASK_ENTER` | `ChunkMap.runGenerationTask` | `(Lnet/minecraft/server/level/ChunkGenerationTask;)V` | Submits the task to `ChunkTaskDispatcher` | `Server thread` | **CONFIRMED**; observational hook only |
| `CHUNK_MAP_GENERATION_WORKER_ENTER` | `ChunkMap.lambda$runGenerationTask$0` | `(Lnet/minecraft/server/level/ChunkGenerationTask;)V` | Worker-side generation task body | `Worker-Main-*` | **CONFIRMED**; role is generation worker, name classifier says background |
| `GENERATION_HOLDER_APPLY_STEP_ENTER/RETURN` | `GenerationChunkHolder.applyStep` | `(Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/server/level/GeneratingChunkMap;Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;` | Calls `GeneratingChunkMap.applyStep`, handles result, and advances holder futures | `Worker-Main-*` and `Server thread` for some completions | **CONFIRMED**; no owner admission |
| `CHUNK_MAP_GENERATION_STEP_ENTER/RETURN` | `ChunkMap.applyStep` | `(Lnet/minecraft/server/level/GenerationChunkHolder;Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;` | For `EMPTY`, calls `scheduleChunkLoad`; otherwise calls `ChunkStep.apply` | `Worker-Main-*` | **CONFIRMED**; orchestration, not sufficient ownership control |
| `CHUNK_MAP_SCHEDULE_CHUNK_LOAD_ENTER/RETURN` | `ChunkMap.scheduleChunkLoad` | `(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;` | Composes disk read, parse, POI prefetch, main-thread application, and exceptional recovery | `Worker-Main-*` | **CONFIRMED**; no Phoenium control |
| `CHUNK_MAP_READ_CHUNK_ENTER/RETURN` | `ChunkMap.readChunk` | `(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;` | Asynchronous region-file read returning `Optional<CompoundTag>` | `Worker-Main-*` | **CONFIRMED**; no Phoenium control |
| `SERIALIZABLE_DATA_PARSE_ENTER/RETURN` | `ChunkMap.lambda$scheduleChunkLoad$1` | `(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;` | Parses NBT into detached `SerializableChunkData` on the parse executor | `Worker-Main-*` | **CONFIRMED on reload only**; no Phoenium control |
| `POI_PREFETCH_ENTER/RETURN` | `SectionStorage.prefetch` | `(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;` | Starts the POI/section-storage prefetch branch | `Worker-Main-*` | **CONFIRMED**; no Phoenium control |
| `CHUNK_MAP_APPLICATION_ENTER/RETURN` | `ChunkMap.lambda$scheduleChunkLoad$3` | `(Lnet/minecraft/world/level/ChunkPos;Ljava/util/Optional;)Lnet/minecraft/world/level/chunk/ChunkAccess;` | Main-thread application continuation; selects `SerializableChunkData.read` or `createEmptyChunk` | `Server thread` | **CONFIRMED**; best initial-application seam, insufficient alone |
| exceptional candidate | `ChunkMap.lambda$scheduleChunkLoad$4` | `(Lnet/minecraft/world/level/ChunkPos;Ljava/lang/Throwable;)Lnet/minecraft/world/level/chunk/ChunkAccess;` | Delegates to `handleChunkLoadFailure` | Not observed | **NOT OBSERVED**; no unsafe corruption was attempted |
| exceptional candidate | `ChunkMap.handleChunkLoadFailure` | `(Ljava/lang/Throwable;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ChunkAccess;` | Vanilla recovery/reporting path for load failures | Not observed | **NOT OBSERVED**; no Phoenium control |

### Application, POI, lighting, and holder publication

| Trace stage / status | Exact class and method | Descriptor | Important target / effect | Thread observed | Runtime / Phoenium control |
| --- | --- | --- | --- | --- | --- |
| `CHUNK_MAP_CREATE_EMPTY_CHUNK_ENTER/RETURN` | `ChunkMap.createEmptyChunk` | `(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ChunkAccess;` | Fresh branch creates a `ProtoChunk` | `Server thread` | **CONFIRMED on fresh runs**; vanilla-only |
| `CHUNK_MAP_MARK_POSITION_REPLACEABLE` | `ChunkMap.markPositionReplaceable` | `(Lnet/minecraft/world/level/ChunkPos;)V` | Bytecode writes `chunkTypeCache[pos] = -1` | `Server thread` | **CONFIRMED on fresh runs**; bookkeeping only, not Phoenium authority |
| `CHUNK_MAP_MARK_POSITION_ENTER/RETURN` | `ChunkMap.markPosition` | `(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/status/ChunkType;)B` | Bytecode writes `chunkTypeCache[pos]` to `-1` or `1` based on `ChunkType` | `Server thread` | **CONFIRMED on reload and after future**; bookkeeping only |
| `SERIALIZABLE_DATA_READ_ENTER/RETURN` | `SerializableChunkData.read` | `(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/ai/village/poi/PoiManager;Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ProtoChunk;` | Converts parsed data to a `ProtoChunk`; the observed call contains POI/lighting side effects before return | `Server thread` | **CONFIRMED on reload only**; insufficient alone |
| `POI_CHECK_CONSISTENCY_ENTER/RETURN` | `PoiManager.checkConsistencyWithBlocks` | `(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/chunk/LevelChunkSection;)V` | Checks/reconciles POI state against loaded sections | `Server thread` | **CONFIRMED on reload**; exact per-record mutation footprint not decomposed |
| `POI_UNPACK_PENDING_ENTER/RETURN` | `SectionStorage.unpackChunk(ChunkPos)` | `(Lnet/minecraft/world/level/ChunkPos;)V` | Drains pending section loads for the target | `Server thread` | **CONFIRMED on reload** |
| `POI_UNPACK_DATA_ENTER/RETURN` | `SectionStorage.unpackChunk(ChunkPos,PackedChunk)` | `(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/storage/SectionStorage$PackedChunk;)V` | Installs one packed POI section payload | `Server thread` in the reload read; also a later callback on fresh | **CONFIRMED**; object identity only |
| `LIGHT_ENGINE_RETAIN_DATA_ENTER/RETURN` | `ThreadedLevelLightEngine.retainData` | `(Lnet/minecraft/world/level/ChunkPos;Z)V` | Retains light data while a persisted chunk is unpacked | `Server thread` | **CONFIRMED on reload** |
| `LIGHT_ENGINE_QUEUE_SECTION_DATA_ENTER/RETURN` | `ThreadedLevelLightEngine.queueSectionData` | `(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/chunk/DataLayer;)V` | Queues block/sky light section data | `Server thread` | **CONFIRMED on reload** |
| `LIGHT_ENGINE_LIGHT_CHUNK_ENTER/RETURN` | `ThreadedLevelLightEngine.lightChunk` | `(Lnet/minecraft/world/level/chunk/ChunkAccess;Z)Ljava/util/concurrent/CompletableFuture;` | Schedules or completes lighting for the chunk object | `Worker-Main-*` | **CONFIRMED**; name was classified background, not a distinct lighting worker |
| `GENERATION_HOLDER_COMPLETE_FUTURE_ENTER/RETURN` | `GenerationChunkHolder.completeFuture` | `(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Lnet/minecraft/world/level/chunk/ChunkAccess;)V` | Completes or installs a holder status future | `Server thread` and `Worker-Main-*` | **CONFIRMED**; holder publication seam, insufficient alone |
| `GENERATION_HOLDER_REPLACE_PROTO_CHUNK_ENTER/RETURN` | `GenerationChunkHolder.replaceProtoChunk` | `(Lnet/minecraft/world/level/chunk/ImposterProtoChunk;)V` | CAS-replaces proto results in the holder future array | `Server thread` | **CONFIRMED on fresh runs**; holder publication substage |
| `CHUNK_STATUS_FULL_ENTER/RETURN` | `ChunkStatusTasks.full` | `(Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/util/StaticCache2D;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;` | Schedules FULL conversion using `WorldGenContext.mainThreadExecutor()` | `Worker-Main-*` at method boundary | **CONFIRMED**; no Phoenium control |
| `CHUNK_STATUS_FULL_CONVERSION_ENTER/RETURN` | `ChunkStatusTasks.lambda$full$0` | `(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/server/level/GenerationChunkHolder;)Lnet/minecraft/world/level/chunk/ChunkAccess;` | Constructs/reuses `LevelChunk`, replaces proto, sets full status, runs post-load, marks loaded, registers block entities/tick container, installs unsaved listener | `Server thread` | **CONFIRMED**; strongest observed live-chunk mutation seam, not implemented as custody |
| `CHUNK_STATUS_FULL_POST_LOAD_ENTER/RETURN` | `ChunkStatusTasks.lambda$full$1` | `(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk;)V` | Post-load entity work in the FULL continuation | `Server thread` | **CONFIRMED on fresh runs; not observed on reload** |

### Readiness and send path candidates

| Candidate | Exact class and method | Descriptor | Result |
| --- | --- | --- | --- |
| ticking preparation | `ChunkMap.prepareTickingChunk` | `(Lnet/minecraft/server/level/ChunkHolder;)Ljava/util/concurrent/CompletableFuture;` | **OBSERVED** in both real-client runs; holder ticket moved from the FULL threshold toward ticking |
| ticking result mapping | `ChunkMap.lambda$prepareTickingChunk$1` | `(Lnet/minecraft/server/level/ChunkHolder;Lnet/minecraft/server/level/ChunkResult;)Lnet/minecraft/server/level/ChunkResult;` | **OBSERVED** with successful `ChunkResult` inputs/outputs; internal future identity only |
| ticking conversion | `ChunkMap.lambda$prepareTickingChunk$2` | `(Lnet/minecraft/server/level/ChunkHolder;Ljava/util/List;)Lnet/minecraft/world/level/chunk/LevelChunk;` | **OBSERVED** on `Server thread`; both runs reported `listSize=9` and `sendBranch=IMMEDIATE` |
| delayed ready-to-send callback | `ChunkMap.lambda$prepareTickingChunk$3` | `(Lnet/minecraft/server/level/ChunkHolder;Lnet/minecraft/world/level/chunk/LevelChunk;Ljava/lang/Object;)V` | **NOT OBSERVED**; ordinary fresh demand completed through the immediate send-sync branch |
| ready-to-send map callback | `ChunkMap.onChunkReadyToSend` | `(Lnet/minecraft/server/level/ChunkHolder;Lnet/minecraft/world/level/chunk/LevelChunk;)V` | **OBSERVED** after ticking conversion and `startTickingChunk`; later than storage application |
| ready-to-send cache callback | `ServerChunkCache.onChunkReadyToSend` | `(Lnet/minecraft/server/level/ChunkHolder;)V` | **OBSERVED** after the map callback; holder send-sync was done |
| world ticking | `ServerLevel.startTickingChunk` | `(Lnet/minecraft/world/level/chunk/LevelChunk;)V` | **OBSERVED** on `Server thread`; called with `LevelChunk` full status `ENTITY_TICKING` |

## Bytecode-confirmed relationships

`ServerChunkCache.getChunkFuture` has two materially different branches. On the
server thread it calls `getChunkFutureMainThread`, invokes the main-thread
processor's `managedBlock`, and calls `CompletableFuture.join` before caching
the result. Off the server thread it schedules the main-thread supplier and
composes the returned future. That behavior belongs to the pinned vanilla
artifact. Phoenium's trace command did not add a wait; the diagnostic observer
does not alter that contract.

`ChunkMap.scheduleChunkLoad` composes the following exact edges:

1. `readChunk(pos)`;
2. `thenApplyAsync(lambda$scheduleChunkLoad$1, Util.backgroundExecutor().forName("parseChunk"))`;
3. `PoiManager.prefetch(pos)`;
4. `thenCombine` of the parsed-data and POI branches;
5. `thenApplyAsync(lambda$scheduleChunkLoad$3, mainThreadExecutor)`;
6. `exceptionallyAsync(lambda$scheduleChunkLoad$4, mainThreadExecutor)`.

The application lambda's bytecode calls `SerializableChunkData.read` and then
`markPosition` when its `Optional` is present. For an empty optional it calls
`createEmptyChunk`, which calls `markPositionReplaceable`. This is why the
reload trace contains POI/lighting work before `CHUNK_MAP_APPLICATION_RETURN`,
while the fresh trace contains empty-chunk creation instead.

`ChunkStatusTasks.full` uses `WorldGenContext.mainThreadExecutor()` to run
`lambda$full$0`. The current bytecode makes this lambda a much stronger live
chunk mutation seam than a public future return: it has the input chunk, the
`GenerationChunkHolder`, and the `ServerLevel` through the context, and it
performs the LevelChunk/holder/post-load registrations. It is still too late
to control the preceding parsed-data, POI, lighting, and map-cache footprint.

## Observed order and identity facts

The event sequence is a monotonic trace sequence within one server JVM. The
`stageOrder` in the generated summary is the first-occurrence order of unique
stage names; repeated holder statuses are retained in the raw JSON and counted
separately. The two fresh runs had the same unique semantic order. Numeric
sequence positions move when background generation tasks complete at a slightly
different time, so they are evidence anchors, not a scheduler contract.

Fresh unique order, stable in both runs:

```text
CHUNK_FUTURE_REQUEST -> CHUNK_MAP_PREPARE_ACCESSIBLE_CHUNK_ENTER -> CHUNK_MAP_RUN_GENERATION_TASK_ENTER -> CHUNK_MAP_GENERATION_WORKER_ENTER -> GENERATION_HOLDER_APPLY_STEP_ENTER -> CHUNK_MAP_GENERATION_STEP_ENTER -> CHUNK_MAP_SCHEDULE_CHUNK_LOAD_ENTER -> CHUNK_MAP_READ_CHUNK_ENTER -> CHUNK_MAP_READ_CHUNK_RETURN -> POI_PREFETCH_ENTER -> POI_PREFETCH_RETURN -> CHUNK_MAP_SCHEDULE_CHUNK_LOAD_RETURN -> CHUNK_MAP_GENERATION_STEP_RETURN -> GENERATION_HOLDER_APPLY_STEP_RETURN -> CHUNK_MAP_APPLICATION_ENTER -> CHUNK_MAP_CREATE_EMPTY_CHUNK_ENTER -> CHUNK_MAP_MARK_POSITION_REPLACEABLE -> CHUNK_MAP_CREATE_EMPTY_CHUNK_RETURN -> CHUNK_MAP_APPLICATION_RETURN -> GENERATION_HOLDER_COMPLETE_FUTURE_ENTER -> GENERATION_HOLDER_COMPLETE_FUTURE_RETURN -> LIGHT_ENGINE_LIGHT_CHUNK_ENTER -> LIGHT_ENGINE_LIGHT_CHUNK_RETURN -> CHUNK_STATUS_FULL_ENTER -> CHUNK_STATUS_FULL_RETURN -> CHUNK_STATUS_FULL_CONVERSION_ENTER -> GENERATION_HOLDER_REPLACE_PROTO_CHUNK_ENTER -> GENERATION_HOLDER_REPLACE_PROTO_CHUNK_RETURN -> CHUNK_STATUS_FULL_POST_LOAD_ENTER -> CHUNK_STATUS_FULL_POST_LOAD_RETURN -> CHUNK_STATUS_FULL_CONVERSION_RETURN -> CHUNK_FUTURE_RETURN -> CHUNK_FUTURE_COMPLETION -> POI_UNPACK_DATA_ENTER -> POI_UNPACK_DATA_RETURN -> CHUNK_MAP_MARK_POSITION_ENTER -> CHUNK_MAP_MARK_POSITION_RETURN
```

Reload unique order:

```text
CHUNK_FUTURE_REQUEST -> CHUNK_MAP_PREPARE_ACCESSIBLE_CHUNK_ENTER -> CHUNK_MAP_RUN_GENERATION_TASK_ENTER -> CHUNK_MAP_GENERATION_WORKER_ENTER -> GENERATION_HOLDER_APPLY_STEP_ENTER -> CHUNK_MAP_GENERATION_STEP_ENTER -> CHUNK_MAP_SCHEDULE_CHUNK_LOAD_ENTER -> CHUNK_MAP_READ_CHUNK_ENTER -> CHUNK_MAP_READ_CHUNK_RETURN -> POI_PREFETCH_ENTER -> POI_PREFETCH_RETURN -> CHUNK_MAP_SCHEDULE_CHUNK_LOAD_RETURN -> CHUNK_MAP_GENERATION_STEP_RETURN -> GENERATION_HOLDER_APPLY_STEP_RETURN -> SERIALIZABLE_DATA_PARSE_ENTER -> SERIALIZABLE_DATA_PARSE_RETURN -> CHUNK_MAP_APPLICATION_ENTER -> SERIALIZABLE_DATA_READ_ENTER -> POI_CHECK_CONSISTENCY_ENTER -> POI_UNPACK_PENDING_ENTER -> POI_UNPACK_DATA_ENTER -> POI_UNPACK_DATA_RETURN -> POI_UNPACK_PENDING_RETURN -> POI_CHECK_CONSISTENCY_RETURN -> LIGHT_ENGINE_RETAIN_DATA_ENTER -> LIGHT_ENGINE_RETAIN_DATA_RETURN -> LIGHT_ENGINE_QUEUE_SECTION_DATA_ENTER -> LIGHT_ENGINE_QUEUE_SECTION_DATA_RETURN -> SERIALIZABLE_DATA_READ_RETURN -> CHUNK_MAP_MARK_POSITION_ENTER -> CHUNK_MAP_MARK_POSITION_RETURN -> CHUNK_MAP_APPLICATION_RETURN -> GENERATION_HOLDER_COMPLETE_FUTURE_ENTER -> GENERATION_HOLDER_COMPLETE_FUTURE_RETURN -> LIGHT_ENGINE_LIGHT_CHUNK_ENTER -> LIGHT_ENGINE_LIGHT_CHUNK_RETURN -> CHUNK_STATUS_FULL_ENTER -> CHUNK_STATUS_FULL_RETURN -> CHUNK_STATUS_FULL_CONVERSION_ENTER -> CHUNK_STATUS_FULL_CONVERSION_RETURN -> CHUNK_FUTURE_RETURN -> CHUNK_FUTURE_COMPLETION
```

Useful final-run anchors are:

| Run | Application / first map-cache write | FULL conversion | Future return / completion | Later map marker |
| --- | --- | --- | --- | --- |
| fresh 1 | application 15; `markPositionReplaceable` 17 | enter 216, return 221 | 226 / 227 | 230 / 231 |
| fresh 2 | application 17; `markPositionReplaceable` 19 | enter 216, return 221 | 226 / 227 | 230 / 231 |
| reload | application 17; `markPosition` 82 inside read | enter 160, return 162 | 167 / 168 | 169 / 170 |

The fresh application returned a `ProtoChunk` identity. The reload application
returned an `ImposterProtoChunk` identity after a `SerializableChunkData` read.
The FULL conversion returned a `LevelChunk` identity and carried a stable
holder identity within each lifecycle. Identity labels are diagnostic strings
of the form `class#identityHash`; they are not retained objects and are not
cross-process identities.

## Thread and context evidence

The trace records the raw thread name, thread ID, and a conservative
name-based classification. The classifier intentionally does not infer a
generation or lighting executor from a method name:

| Observed role | Raw names in final run | Trace class | Stages |
| --- | --- | --- | --- |
| server/main | `Server thread` | `server-thread` | request, application, FULL conversion, holder publication, future return/completion, map bookkeeping |
| generation / parse / background | `Worker-Main-1` through `Worker-Main-15` | `background-worker` | generation worker body, schedule/read, parse, holder transitions, FULL boundary, lighting API |
| distinct generation-worker name | none | `generation-worker` **not observed by name** | the `CHUNK_MAP_GENERATION_WORKER_ENTER` method role is nevertheless confirmed by the exact method and dispatcher bytecode |
| distinct lighting-worker name | none | `lighting-worker` **not observed** | `lightChunk` ran on a `Worker-Main-*` thread |
| distinct IO-worker name | none | `io-worker` **not observed** | disk/read and parse ran on `Worker-Main-*` |

All core application and FULL events recorded `ownershipContext=false` and
`regionLease=false`. The route snapshot was still B. A later fresh POI callback
and the final `CHUNK_MAP_MARK_POSITION` callback recorded
`contextRegion=stage3-fixture-a`, `ownershipContext=true`, and
`regionLease=true`; the reload's final marker did the same. In this trace,
`regionLease=true` means only that the current `OwnershipContext` carried a
`RegionLease` on that thread. It is not a chunk-specific lease, and no vanilla
chunk was admitted through it. The mismatch between route B and context A is
evidence that thread-local Phoenium context is not a safe ownership decision
for vanilla chunk application.

## Request and holder identity

| Question | Evidence | Result |
| --- | --- | --- |
| Is `ChunkPos` available? | Direct argument at `getChunkFuture`, `ChunkMap` load/application methods, and holder `getPos()` | **YES** |
| Is a current route available? | `PhoeniumLiveRuntime.chunkTraceRoute` resolves `(minecraft:overworld,32,4)` to B, generation 1, owner epoch 0 | **YES, diagnostic route only** |
| Is a holder identity available? | `GenerationChunkHolder`/`ChunkHolder` identity is present at holder and FULL conversion stages | **YES** |
| Is a vanilla holder incarnation available? | No exposed incarnation field or lifecycle token was found at the observed seams | **NO** |
| Is a vanilla request incarnation available? | No request-generation/supersession token is exposed by the public request or observed continuations | **NO** |
| Is the original public future available? | `CompletableFuture<ChunkResult<ChunkAccess>>` is visible at `getChunkFuture` return and completion | **YES at the diagnostic boundary** |
| Is the original future available at FULL conversion? | The FULL lambda has an internal stage/result, not the public request future as an argument | **NO, not directly** |
| Can the current code invoke the vanilla continuation exactly once? | The trace observed one normal invocation per run but never invokes or replaces it | **UNRESOLVED for an ownership implementation** |
| Was real supersession observed? | No safe natural request/unload race was reproduced | **NOT OBSERVED** |

The raw trace assigns `correlationId=1` and the same numeric
`requestIncarnation=1` in each server process. The JSON labels this field
`requestIncarnationSource="trace-assigned correlation; vanilla incarnation not exposed"`.
It is useful for correlating one test request to its events, but must not be
treated as Minecraft's request or holder incarnation fence.

## First mutation and remaining footprint

There are two different answers depending on what “live mutation” means:

1. The first observed write to a live Minecraft-owned map structure is
   `ChunkMap.markPositionReplaceable(ChunkPos)` on the fresh path (sequence 17
   or 19). The bytecode writes `chunkTypeCache`. On reload, the corresponding
   first observed map-cache write is `ChunkMap.markPosition` inside
   `SerializableChunkData.read` (sequence 82). This is live vanilla bookkeeping,
   not a Phoenium region mutation and not proof of chunk installation.
2. The first confirmed mutation of the usable live chunk/holder footprint is
   `ChunkStatusTasks.lambda$full$0` on `Server thread` (fresh sequence 216;
   reload sequence 160). Its bytecode constructs or reuses the `LevelChunk`,
   calls `GenerationChunkHolder.replaceProtoChunk`, sets full status, performs
   post-load work, marks the chunk loaded, registers block entities and tick
   containers, and installs the unsaved listener. The trace sees the
   `ProtoChunk` and holder on entry and the `LevelChunk` on return.

That FULL seam is not the complete application footprint. The reload proves
that `SerializableChunkData.read` can run POI consistency/unpack and queue
lighting data before the application lambda returns. The holder's status
futures, the later FULL future completion, post-future map bookkeeping, and the
client-demand ticking/readiness/send paths all have independent effects. The
follow-up trace reached those later paths, but it did not turn them into a
Phoenium custody implementation.

`CHUNK_FUTURE_RETURN` is downstream of the initial application and FULL
conversion in both observed paths. It occurred at sequence 226 on fresh and
167 on reload; its completion callback followed at 227 and 168. The existing
`ServerChunkCache.getChunkFuture` Phoenium observer fires at this same public
return boundary and remains **DIAGNOSTIC ONLY**. It does not own the
application continuation, and its existing diagnostic counters must not be
read as proof that R-CHUNK-01 was implemented.

## Candidate admission seams (analysis only)

| Candidate | Classification | Why |
| --- | --- | --- |
| `ChunkMap.lambda$scheduleChunkLoad$3` | **BEST INITIAL SEAM; INSUFFICIENT ALONE** | Narrow `ChunkPos` + parsed optional + `ChunkAccess` result on `mainThreadExecutor`; it is the first application continuation, but it lacks a vanilla request/holder incarnation and encloses POI/lighting branches that must be accounted for. |
| `SerializableChunkData.read` | **INSUFFICIENT ALONE** | Has `ServerLevel`, POI manager, storage info, and `ChunkPos`; reload evidence shows side effects before map bookkeeping, but it is too early and has no public future/holder admission identity. |
| `ChunkMap.markPositionReplaceable` / `markPosition` | **BOOKKEEPING ONLY** | The bytecode writes `chunkTypeCache`; it is a real map mutation but not the complete chunk data/holder installation seam. |
| `GenerationChunkHolder.completeFuture` | **GOOD HOLDER-PUBLICATION SUBSEAM; INSUFFICIENT ALONE** | Exact status, chunk, and holder are present, but POI/lighting/application work already happened and later FULL/readiness work remains. |
| `GenerationChunkHolder.replaceProtoChunk` | **GOOD PUBLICATION SUBSEAM; INSUFFICIENT ALONE** | Exact holder and replacement proto are present, but it is nested inside FULL conversion and cannot cover earlier load effects. |
| `ChunkStatusTasks.lambda$full$0` | **GOOD FULL-CONVERSION SEAM; INSUFFICIENT ALONE** | Main-thread continuation has chunk, holder, and access to level; bytecode performs the strongest live `LevelChunk` footprint, but it is too late for parsed data, POI, light input, and map-cache writes. |
| `ServerChunkCache.getChunkFuture` return / existing observer | **TOO LATE / DIAGNOSTIC ONLY** | Public future/result is visible, but observed after application and FULL conversion; it cannot prevent or admit earlier side effects. |
| `ChunkMap.prepareAccessibleChunk` | **INSUFFICIENT ALONE** | Readiness/range orchestration has holder but not the complete application result or mutable footprint. |
| `ChunkMap.prepareTickingChunk`, `lambda$prepareTickingChunk$2`, `startTickingChunk` | **OBSERVED; INSUFFICIENT ALONE** | The real-client trace confirms the later usable/ticking path on `Server thread`; it is downstream of FULL conversion and still has no validated Phoenium request/holder custody. |
| `ChunkMap.onChunkReadyToSend` and send callbacks | **OBSERVED; TOO LATE for storage custody** | The real-client trace confirms the send/readiness callbacks after usable chunk construction. They are useful ordering anchors, not storage-ownership seams. |
| load-failure callbacks | **UNKNOWN / NOT OBSERVED** | Exact symbols are mapped, but no safe controlled parse/load failure was induced. |

No candidate is approved for implementation by this document. In particular,
thread identity, `mainThreadExecutor`, route resolution, or an existing
`OwnershipContext` is not itself regional authority.

## Gaps carried forward

- Exceptional load recovery and terminal error/fallback behavior were not safely
  reproduced. The precise result contract remains **NOT OBSERVED**.
- A legitimate real request/holder supersession or stale late-arrival race was
  not reproduced. Phoenium's deterministic `ChunkCustody` tests remain model
  evidence, not live Minecraft evidence.
- The real-client readiness trace reached ticking/readiness/send and exact client
  receipt/removal, but did not observe the delayed send branch. The final target
  holder remained present at `FULL` with ticket level `31` after client tracking
  removal during the 300-tick leave bound; direct target-holder demotion/unload
  therefore remains **NOT OBSERVED**.
- The trace does not expose a vanilla holder incarnation, request generation,
  ticket identity, cancellation token, or direct holder-to-public-future mapping.
- The trace records method boundaries and object identities; it does not replace
  a field-level audit of every POI, lighting, entity, block-entity, tick,
  save/unload, or cross-chunk side effect.
- The existing diagnostic observer still increments metadata-only chunk
  admission/application counters. Those counters are not authoritative vanilla
  installation and do not change the `ChunkCustody` model.

## Safety boundary

The implementation is dormant by default and observation-only when enabled. It
adds no owner routing, future replacement, authoritative callback wrapper,
blocking owner wait, broad gameplay lock, peer lock, fake chunk, C2ME path,
second worker, or parallel gameplay. The readiness run reported one worker,
`parallel=OFF`, zero selected-event exceptions, zero dropped trace events, and
no ownership implementation. The existing Phoenium diagnostics also reported
zero ownership violations, zero unleased executions, zero fallback, zero bypass,
zero barriers, and zero producer-thread chunk mutations. Therefore:

`R-CHUNK-01 = PARTIAL`

`Stage 3 = PARTIAL`

`Stage 4 = NOT_STARTED`
