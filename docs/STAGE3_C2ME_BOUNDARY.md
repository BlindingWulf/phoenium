# Stage 3 async chunk-completion / C2ME boundary

`ChunkWorkRequest` is the producer-safe identity for a requested completion. It contains world, chunk routing key, request generation, requested topology generation, completion kind, and a correlation ID. A producer only submits this immutable metadata to `ChunkCompletionGateway`. It has no live chunk reference, no mutation callback, no blocking future wait, and no authority to synthesize an EmptyChunk, AIR value, or other fabricated result.

Completion resolves the current directory route and queues `TaskPayload.ChunkCompletion` to the owner. A topology change between request and completion is visible; the gateway routes to the current owner and records a reroute. The owner executor remains responsible for validating the request generation/status immediately before applying a real live-world result.

This is the contract a future C2ME adapter must satisfy. No C2ME source is forked, copied, rewritten, or declared compatible here. A later focused audit must identify the exact 26.2 producer/completion boundaries and demonstrate that its result application can be held behind this gateway.

## Base-server binding

The contract is now observed against the base 26.2 `ServerChunkCache.getChunkFuture(int, int, ChunkStatus, boolean)` return value. Phoenium observes—not replaces—the returned `CompletableFuture<ChunkResult<ChunkAccess>>`, captures only request metadata, and attaches a completion continuation. The continuation has no `ChunkAccess` or `ServerLevel` mutation path; it submits the immutable `ChunkWorkRequest` through `ChunkCompletionGateway`. A leased owner records validated completion admission. Minecraft's own future/result chain remains its existing authority and is not joined, blocked, fabricated, or rewritten.

This proves the Phoenium-side producer boundary in the base server only. It is not a C2ME compatibility result and it does not claim to have taken over vanilla chunk result application.
