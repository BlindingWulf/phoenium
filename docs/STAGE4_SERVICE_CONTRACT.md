# Regional world-service access contract

Minecraft 26.2, one gameplay worker, parallel gameplay OFF. This contract classifies
the actual services reached by Stage 4; it does not grant additional ownership or
expand the frozen serializer, selector, or compatibility scope. Installed acceptance
is tracked in [STAGE4_CURRENT_WORK.md](STAGE4_CURRENT_WORK.md).

## Authority and operation boundaries

`RegionDirectory`, `OwnerGateway`, `RegionScheduler`, `ObjectOwnershipRegistry`,
`ChunkLifecycleRegistry`, and `ChunkCustodyModel` remain the authority ledgers.
The regional dispatcher retains an exact typed operation identity. Delivery checks
its current route, owner epoch, and active lease before invoking the original body.
A thread name, an earlier directory read, a connection reference, or a global service
turn never grants access to a foreign regional object.

`FullFinishingServices` is the existing shared-service root. Its `SERVER_TICK`,
`SERVER_TASK`, `SAVE`, `STOP`, player/entity lifecycle, and scoreboard phases reserve
the named services before owner admission. Its exact FULL reservations retain their
existing evidence. Reservations are metadata protocols; their monitor is not held
during original callbacks, sends, regional work, future completion, or I/O. This
single-worker protocol is conservative and is not permission to enable parallelism.

## Shared responsibilities

| Existing service | Responsibility and access protocol |
| --- | --- |
| `CONNECTIONS` | Original connection identity, ordered send submission, and lifecycle. Reuse the connection access and scoreboard/recipient service. Block-event nearby broadcasts retain the original loop, distance conditions and sends, consuming exact immutable world/coordinate receipts from `FullEntityFacts` and revalidating the existing live session at the original send. Coordinates publish at insertion and immediately before the original movement callback, after committed position fields, so callback failure cannot hide a moved entity. Recipient identity alone is not a foreign-entity read grant. Network I/O keeps Minecraft's existing asynchronous transport. |
| `SCOREBOARD` | Teams, scores, and synchronization use `FullFinishingScoreboard`, the original lifecycle wrappers and named turn. No regional copy of scoreboard authority is introduced. |
| `CLOCK` | World clocks and the server tick clock remain explicit shared services. Original clock sampling and tick phase order are preserved; a scheduler dispatch does not increment game time or invent a new scheduled-tick deadline. |
| `ENVIRONMENT` | The installed world/environment identity and mutable environment cache use the existing environment service. Generation requests carry a continuation to the original owner-free server event loop before obtaining a service turn. Requests do not retain regional authority while waiting. |
| `ENTITY_REGISTRY` | Original addressability/removal feeds `EntityIndexLifecycleAdapter`, `ObjectOwnershipRegistry`, interaction targets and `FullEntityFacts`. Object incarnation and epoch are revalidated at execution. Tracker state must not create another identity registry. |
| `TRACKER` | Pairing, removal, membership and ordered change submission belong to the original tracked entity's regional service. Shared tracker/recipient access uses the existing named turn and connection service. Topology transfer changes the partition, not the entity, connection, or tracker incarnation. |
| `WAYPOINT` | Existing original waypoint registration/removal stays a named shared service within its exact lifecycle or FULL reservation. It does not authorize mutation of a waypoint's foreign entity. |
| `TICK_LIST` | Actual `LevelChunkTicks` remain the persistence objects and own vanilla deduplication. Regional services own container partitions, due selection and callback cohorts. Shared ordering metadata coordinates one serial cycle; it is not an unchanged call to world-global `LevelTicks.tick`. Save capture also requires this named service. |
| `DEBUG` | Original debug submissions and diagnostic observations use the named operation. Diagnostics never provide readiness, route authority or evidence of durable persistence. |
| `UNSAVED` | Original dirty claims, status/structure eligibility reads, snapshots and failed-write dirty restoration use the exact retained holder/source and current regional owner. Original save selection is retained at its real `getLatestChunk` producer; a later lookup cannot substitute a different holder. |
| `RAID` | Raid management remains a declared world-global service at the original phase and in original order. Regional tick/event extraction must preserve the condition and clock observations surrounding that phase. |
| `TICKET` | Distance/ticket orchestration and holder-map membership are control-plane services at owner-free boundaries. They publish authentic readiness and lifetime transitions; selected chunk/world effects still enter the existing owned FULL/load/save/unload protocols. |
| `COMMAND_STORAGE` | Reuse the original command-storage service and the frozen reached component/selector families. New serializer/predicate families and arbitrary cross-world selectors are outside this assignment. |
| Legacy profile/owner cache | Reuse `FullLegacyOwners` and `FullLegacyOwnerCache`, their producer, capacity, retained outcome, and shutdown protocol. No second resolver or cache is introduced. |
| Chunk POI, light, type metadata | Reuse each world's `ChunkPrerequisiteServices` and genuine holder/work identities. Original I/O/light producers publish immutable outcomes; the authorized consumer applies the reached effect. Retirement and notification failure do not skip independent acknowledgements. |
| Chunk encoding/storage | Original `SerializableChunkData.copyOf` runs once under the selected source owner. Remaining mutable NBT aliases are detached at that handoff. Minecraft's original encoder, write future and flush are preserved. Completions publish outcome metadata; they do not mutate chunks. Blocking storage waits and write initiation release the named serial turn through `ownerFreeIo`. |
| Topology publication | A short metadata section publishes an immutable view and closes/reopens admission. Complete declared service state transfers at an owner-free boundary. Observers and migration callbacks execute outside the metadata gate. A retained save cut delays transition admission; it must not strand the save behind participants that it has already closed. |

## Saves, migration, and terminal acknowledgement

Save-all preflights the original selected holders' save readiness before retaining
its topology view. Autosave and final unload take an operation cut when there is no
enclosing save cut or active exact owner. These reservations contain no gameplay
lease and no monitor across storage. The retained source is the actual originally
selected holder/chunk; an original unload retains that lineage before conditional
pending-map removal. Original serialization and dirty claims are not replayed.

When a pending FULL subscriber is withdrawn, its slot and evidence remain revoked.
The still-winning original producer may complete a distinct slot. Only that exact
native completion, its original result, and the earlier actual UNLOADED outcome mint
a new subscriber receipt tied to the existing producer reservation. Re-promotion
does not repeat conversion. Current holder, lower-input slot, lifecycle, and ticket
eligibility still gate use; later input replacement invalidates the new receipt too.

Each outstanding write retains one source and detached snapshot until its original
future completes and the current owner acknowledges it. A changed topology may
route that acknowledgement to a new owner without changing the historical capture
cut. A replacement holder cannot inherit the older source's dirty restoration.
Admission of another snapshot waits at the owner-free event loop at 128 outstanding
records. Completion and release remove protocol state; no completed-save history is
kept. This bound is not a claim about total heap use or platform capacity.

Normal flush requires zero pending writes/acknowledgements and preserves original
storage errors. Disk inspection and restart are required evidence of saved state.
The protocol makes no stronger durability claim than Minecraft's original storage
flush, and does not claim a transaction across multiple worlds or power-loss safety.

Shutdown closes new gameplay/packet admission first, while accepted generation,
packet and service continuations retain their original lifecycle. The stop phase
drives the existing one-worker scheduler and owner-free pumps before original world
saving/closing. Independent cleanup steps retain the first failure and suppress later
ones in order. Reentrant stop cannot execute the original stop twice or terminate
services while the outer stop is active.

A shared hard barrier prohibits further regional mutation. A write already in flight
still retains its source/snapshot until real completion. Terminal failure settlement
then releases only protocol custody and records failure; it neither restores dirty
state from an unowned caller nor reports successful flush. Remaining rejected work
is acknowledged as unexecuted. Persistent scheduled ticks belong to actual chunk
records; transient block-event/tracker metadata ends with the original world lifetime.
An ended service binding retains its terminal marker. Later producer entrypoints
reject before original mutation; clearing its maps cannot reopen the bootstrap path.

## Provenance and acceptance boundary

Semantics were checked against the locally pinned Minecraft 26.2 sources archive,
SHA-256 `DDD82534C200D6610309A1EB35C126BED5FBD4D0118CADDC828610914F3988CD`.
No Folia, Paper, Moonrise or other third-party implementation was imported. Test-only
instrumentation remains outside the production JAR. Unit/model evidence alone does
not establish installed regional migration, correct client behavior, saved records,
reload effects, or final shutdown health. Those gates remain explicit in the current
execution note until the stable installed artifact has passed them.
