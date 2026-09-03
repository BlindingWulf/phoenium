# Stage 3 async chunk-completion / C2ME boundary

## Architecture review disposition

Read [STAGE3_CHUNK_APPLICATION_REVIEW.md](STAGE3_CHUNK_APPLICATION_REVIEW.md) for the
exact 26.2 application review. The existing observer is **DIAGNOSTIC ONLY**. The normal
parsed-chunk application seam is the second `thenApplyAsync` in
`ChunkMap.scheduleChunkLoad`, before `lambda$scheduleChunkLoad$3` invokes
`SerializableChunkData.read`. Its exceptional continuation, holder publication via
non-async `handle`, independent POI unpacking and FULL conversion also need owner
admission. A single load-executor wrapper does not prove the complete pipeline safe.
R-CHUNK-01 remains PARTIAL; no live application hook or C2ME integration was added.

`ChunkWorkRequest` is the producer-safe identity for a requested completion. It contains world, chunk routing key, request generation, requested topology generation, completion kind, and a correlation ID. A producer only submits this immutable metadata to `ChunkCompletionGateway`. It has no live chunk reference, no mutation callback, no blocking future wait, and no authority to synthesize an EmptyChunk, AIR value, or other fabricated result.

Completion resolves the current directory route and queues `TaskPayload.ChunkCompletion` to the owner. A topology change between request and completion is visible; the gateway routes to the current owner and records a reroute. The owner executor remains responsible for validating the request generation/status immediately before applying a real live-world result.

This is the contract a future C2ME adapter must satisfy. No C2ME source is forked, copied, rewritten, or declared compatible here. A later focused audit must identify the exact 26.2 producer/completion boundaries and demonstrate that its result application can be held behind this gateway.

## Base-server binding

The contract is now observed against the base 26.2 `ServerChunkCache.getChunkFuture(int, int, ChunkStatus, boolean)` return value. Phoenium observes—not replaces—the returned `CompletableFuture<ChunkResult<ChunkAccess>>`, captures only request metadata, and attaches a completion continuation. The continuation has no `ChunkAccess` or `ServerLevel` mutation path; it submits the immutable `ChunkWorkRequest` through `ChunkCompletionGateway`. A leased owner records validated completion admission. Minecraft's own future/result chain remains its existing authority and is not joined, blocked, fabricated, or rewritten.

This proves the Phoenium-side producer boundary in the base server only. It is not a C2ME compatibility result and it does not claim to have taken over vanilla chunk result application.

## 2026-09-02 base 26.2 first live-mutation trace (historical)

The trace below identified a candidate, not a complete application cut. The later review
above distinguishes first parsed-chunk application from earlier scheduling metadata and
independent POI application; it also establishes additional holder/FULL application paths.

The observer is not sufficient proof of chunk ownership by itself. The required bytecode trace
of the shipped official-named 26.2 server is:

```text
ServerChunkCache.getChunkFuture
  -> ServerChunkCache.getChunkFutureMainThread
  -> ChunkHolder.scheduleChunkGenerationTask
  -> ChunkMap.runGenerationTasks
  -> ChunkGenerationTask.runUntilWait / scheduleChunkInLayer
  -> GenerationChunkHolder.applyStep
  -> ChunkMap.scheduleChunkLoad (for loading)
     -> readChunk
     -> thenApplyAsync(SerializableChunkData.parse, Util.backgroundExecutor("parseChunk"))
     -> PoiManager.prefetch
     -> thenCombine
     -> thenApplyAsync(..., ChunkMap.mainThreadExecutor)
```

The `scheduleChunkLoad` producer stage parses persisted data on the background executor. It
does not receive Phoenium owner authority. The next continuation is explicitly scheduled on
`ChunkMap.mainThreadExecutor`; this is the first identified base-server application boundary
at which the loaded `ChunkAccess` may be installed into `ChunkMap`/holder state and become live
server state. Generation similarly returns `ChunkResult<ChunkAccess>` through
`GenerationChunkHolder.applyStep`, with task progress driven by `ChunkMap.runGenerationTasks`.

Therefore the answer is **A, conditional**: the current base 26.2 pipeline has a
main-thread-owned application continuation after immutable/background load parsing, but
Phoenium's present `ServerChunkCache.getChunkFuture` return observer runs too late to control
that continuation. It can prove producer-side Phoenium non-authority and queue a metadata-only
owner admission; it cannot claim that it owns vanilla installation. A future adapter must bind
the main-thread application continuation (or its exact `ChunkMap`/holder installation call),
capture the route generation before producer work, and re-resolve/validate it immediately
before installation. It must not block a region owner or manufacture a chunk result.

This trace does **not** establish an irreducible producer-thread mutation that requires a P1
redesign, so it is not the chunk architectural escalation condition. It does establish that
`R-CHUNK-01` cannot yet be PASS: there is no real forced request/completion evidence and no
Phoenium-controlled application binding. Minecraft's own load-failure `createEmptyChunk` path
was observed in `ChunkMap`; Phoenium neither invokes nor substitutes that path, and the
Phoenium contract remains prohibited from fabricating semantic state.
