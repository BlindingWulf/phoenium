# Vault output-neighbor and tick continuation (Minecraft 26.2)

The branch continues directly from `692a636a621a8ac3899de5fd338fe9d803de2ebb`.
The natural-spawn direct passenger implementation is unchanged. No live acceptance
is asserted by this change.

## Exact native seams

`VaultBlockEntity.Server.tick(ServerLevel, BlockPos, BlockState, VaultConfig,
VaultServerData, VaultSharedData)` retains the original native prefix (bytecodes
0–110). A cancellable injection immediately before `VaultBlockEntity.access$000`
at bytecode 111 captures the original state argument and the computed next state
in local 7. It invokes the original `BlockEntity.setChanged(Level, BlockPos,
BlockState)` once through a typed invoker. A single-use scope at the head of
`Level.updateNeighbourForOutputSignal(BlockPos, Block)` substitutes the small
audited output loop only for this exact call. It is consumed before any callback,
so nested notifications cannot overwrite the outer continuation.

The mirrored loop is precisely `Level.updateNeighbourForOutputSignal` bytecodes
0–130: horizontal iteration, immediate-position `hasChunkAt`, the immediate
`getBlockState` at 47, comparator callback or conductor decision, conditional
second-hop `getBlockState` at 100, optional comparator callback, then next direction.
The observed native unresolved point is the second read at 100. The same mechanism
also protects the immediate read if that reached read lacks public FULL readiness.

The mirrored Vault suffix is precisely bytecodes 114–140. It reads **current**
`VaultSharedData.isDirty` after the entire neighbor loop, conditionally calls
`sendBlockUpdated(position, originalState, computedNextState, 2)`, then writes
`false` to server-data `isDirty`, followed by shared-data `isDirty`. No dirty
boolean is snapshotted before callbacks. There is no RNG instruction in either
mirrored body. Native prefix loot, state transitions, sound/item callbacks and
their random draws, when reached, remain in the original invocation.

There is no safe native entry point into either suffix, so these two small bodies
use explicit state. The whole Vault tick and generic Level behavior are not copied.
`VaultContinuationPin` hashes descriptors, locals, instructions, constants, field
accesses, invoke order/count and jump targets for the three native methods. The
production mixin plugin checks these before applying each seam. JUnit pins the
original 26.2 bytes and deliberately changes instructions to verify rejection.

## Retained state and side-effect ledger

Output state: exact world, immutable source position, source Block identity,
horizontal index, exact query position, and `DIRECTION`, `IMMEDIATE_READ`,
`SECOND_READ` or `DONE`. `SECOND_READ` means the immediate read and conductor
decision are already committed; its block state does not need to survive. Earlier
directions are represented by the monotonic horizontal index.

Vault state: exact ticker, Vault object, source LevelChunk, source holder/lifecycle,
source public FULL slot and chunk result, world, source routing key, topology
generation, tick generation, original BlockState, local-7 next BlockState, and the
exact server/shared data objects needed for the native suffix. No copied mutable
world graph, Java stack frame or retry of the native tick is used.

| Native action | Before suspension? | May repeat? | Retained evidence |
| --- | --- | --- | --- |
| Display-item cycle / loot branch, if reached | Yes | No | Prefix is exited permanently at bci 111 |
| State timing, transition and transition callbacks, if reached | Yes | No | Original and next state; no return to prefix |
| Original dirty-disjunction check at 92–105 | Yes | No | Entered setChanged phase |
| Source chunk `blockEntityChanged` / unsaved mutation | Yes | No | Initial native setChanged invocation only |
| Earlier output directions and their comparator callbacks | Yes | No | Horizontal index and PC |
| Current immediate `hasChunkAt`, read and conductor decision | Yes, for the proven second-hop cut | No | `SECOND_READ` PC and exact query position |
| Conditional second-hop read at 100 | Pending | Resume here | Exact FULL query identity and receipt |
| Current direction's second-hop comparator callback | No | Execute once | PC advances only after callback returns |
| Later directions and conditional second hops | No | Execute once | Live reads at their reached PC; no dependency pre-scan |
| Shared dirty-flag check at 114–119 | No | Execute once | Exact shared-data object; read after neighbors |
| `sendBlockUpdated` at 128 | No | Execute once if current flag is true | Position, original state, local-7 next state, flags 2 |
| Server dirty reset at 134, then shared reset at 140 | No | Execute once | Exact data objects and terminal phase |

## Preparation, ownership and retirement

`BlockEntityExternalReadPreparation` records the exact ServerLevel, immutable query
position, chunk key, tick/query generations and topology. Status is always native
`ChunkStatus.FULL` with `create=true`. Readiness observation under the source owner
does not select, block on or join an unfinished future. An unresolved read returns
from both retained levels, cancels the native Vault suffix, and releases the owner.

Owner-free preparation calls the existing native `getChunkFutureMainThread`
invoker, preserving native creation/ticket selection. It binds the visible holder,
lifecycle, FULL slot and returned result, and completes only after public native
FULL readiness. No private FULL access or alternate block result is introduced.
The synchronous-read guard and its service coverage are unchanged.

The existing managed-block poll-return fence waits without any regional claim.
Its reserved direct delivery now has a specific Vault entry point carrying
`BLOCK_ENTITY_TICK`; natural spawn retains `CHUNK_GAMEPLAY`. The dispatcher
re-resolves current routing and uses the existing scheduler selection, lease,
validation and settlement path. The source's existing radius-one owner closure
is revalidated; preparation acquires no additional owners.

On resume, world/runtime openness, topology, exact ticker/Vault/chunk identity,
source holder/lifecycle/publication, and queried holder/lifecycle/FULL slot are
rechecked. The actual source chunk's block-entity map is checked under its owner.
A replacement at the same coordinates cannot resume the old object. Topology,
holder, lifecycle and slot replacements invalidate the receipt. Shutdown, world
close, cancellation and late completions are inert. A preparation failure keeps
the original cause, retires pending preparation and does not run the suffix.
Cleanup errors, if encountered, are secondary suppressed details.

Each consumed query is retired before the next reached query can be allocated.
Later directions read current world state after earlier callbacks; a second
suspension keeps the same tick generation and advances the query generation and
PC. Duplicate or terminal resume attempts execute no body. Native future objects
are never cancelled by continuation cleanup.

## Deterministic evidence

- 27 `VaultContinuationNativeTest` cases, including exact native-order differential
  execution, two distinct suspended directions, both mutation-sensitive dependency
  outcomes, all four dirty-flag combinations, distinct old/new suffix states, native
  state-transition prefix effects, no-dirty tick, stale/cancel/close and failure.
- 6 `VaultContinuationPinTest` cases: three exact-method pins and three negative
  instruction mutations.
- Focused regressions: 199 tests in 24 classes, zero failures/errors/skips. This
  includes the preserved five diagnostic tests and 30 passenger-admission cases.
- Broader deterministic suite: 1,169 tests in 131 classes, zero failures/errors/skips.
  The first broader attempt had 24 missing-artifact errors; restoring the three
  existing locked C2ME fixtures (hash-verified copies, no source/provider edits)
  resolved all 24. Both attempts' evidence is preserved locally.
- A separate Fabric prelaunch transformation probe loads the five actual target
  classes with all eight accepted providers, checks exported bytecode and exits
  before world/server/client startup. It is not a gameplay or live acceptance test.

Raw local evidence is retained under `.audit/vault/` and
`run/vault-continuation/`. Generated evidence is excluded from release source and
the production JAR. The candidate receipt records exact commit, files, artifact
size/hash, clean build, packaging/leakage audit and final transformation result.

## Limits and Luna handoff

This continuation covers only the audited Vault.Server.tick setChanged call and
its direct output-neighbor reads. A new unresolved read inside an arbitrary nested
callback or an unrelated Vault prefix remains subject to the existing guard and
bounded first-failure diagnostic. Upstream or provider changes to the pinned
methods fail compatibility checks rather than silently using a stale state machine.

Luna must use a fresh isolated server with the unchanged accepted providers, no
pearls or portals: startup and idle ticks; join; chunk traversal; break/place; mob
interaction; drop/pickup; chat; remain connected through block-entity ticking and
natural spawning; disconnect/reconnect; continue ordinary gameplay and passenger
direct-path observation. Vault PASS requires no unfinished synchronous Vault/output
read, duplicate Vault effects or block-entity first-failure diagnostic, with healthy
ordinary gameplay. Passenger PASS remains separate and requires observed successful
direct-path telemetry. Deterministic and transformation checks do not grant either
live acceptance gate.
