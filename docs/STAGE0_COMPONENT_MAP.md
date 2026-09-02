# Stage 0 component map

This map records what was audited and the only acceptable disposition for the future Fabric 26.2 design. It is not a source import plan.

| Concern / Ruthenium area | Current Folia semantic reference | 26.2 binding or subsystem | Phoenium disposition | Why |
| --- | --- | --- | --- | --- |
| ThreadedRegionizer | ThreadedRegionizer | ServerLevel ownership directory | Rewrite | Retain Folia lifecycle and buffer semantics; re-prove all radii and topology logic. |
| TickRegionScheduler and region handles | TickRegionScheduler | Server tick integration, no approved target yet | Rewrite | Ruthenium task handles lack a ticking lease; scheduler needs serial-first design. |
| TickRegions and tick data | Folia TickRegions | ServerLevel services | Rewrite | Must define data ownership before routing ticks. |
| RegionizedWorldData | Folia RegionizedWorldData | ServerLevel, LevelTicks, block events | Rewrite | Ruthenium broad world locks and peer lock order are unacceptable. |
| RegionizedData migration API | Folia RegionizedData | Phoenium extension SPI | Independently design | Ruthenium migration coverage is incomplete. |
| RegionizedServer and tick thread context | Folia RegionizedServer and TickThread | MinecraftServer / worker contexts | Rewrite | Context must prove ownership rather than bless main-thread access. |
| Shutdown coordinator | Folia RegionShutdownThread | lifecycle integration | Rewrite | Raw queue execution from a shutdown thread is forbidden. |
| Teleport utilities | Folia TeleportUtils | Entity and ServerPlayer | Rewrite as transfer state machine | Source object queuing and missing passenger protocol are unsafe. |
| Player transfer and network manager | Folia packet scheduling and connection migration | PacketProcessor, ServerGamePacketListenerImpl, Connection | Rewrite | Ruthenium production call paths are absent or incomplete. |
| Chunk integration | Folia/Moonrise chunk ownership concepts | ServerChunkCache, ChunkMap, ChunkHolder, future C2ME adapter | Isolate and defer | No synthetic chunk fallback and no blocking future calls. |
| Entity tracking and tick list | Folia regional entity state | PersistentEntitySectionManager, EntityTickList, ServerEntity | Rewrite and defer | Must replace global scans and blanket collection synchronization. |
| Scheduled tick routing | Folia regional LevelTicks | LevelTicks and LevelChunkTicks | Reimplement per region | Ruthenium monitor-serializes scheduler operations. |
| Block events | Folia regional block-event storage | ServerLevel block event machinery | Rewrite and defer | Ownership, ordering, and migration need explicit records. |
| Redstone, pistons, neighbor updates, game events | Current Folia redstone/game-event ownership patches | RedstoneWireEvaluator, PistonStructureResolver, NeighborUpdater, GameEventDispatcher | Redesign boundary protocol | Air, bedrock, cancellation, and live foreign reads are prohibited. |
| Raids and world-global services | Folia global region concepts | explicitly classified global services | Decide per service | A synchronized shared map is not an ownership design. |
| Watchdog and metrics | Folia diagnostics | diagnostics only | Adapt semantics independently | May observe ownership; may not coordinate simulation. |
| Failure guard / fallback validator | No approved fallback model | runtime lifecycle | Replace | Default fallback and disabled assertions violate Phoenium policy. |
| Test scaffolding | current Fabric and new deterministic harness | unit/integration/dedicated server | Replace | Ruthenium's tests are mostly reflection tests with a test-source ServerWorld stub. |

## Old mixin targets are not mapping authority

Ruthenium's old config targets names such as ServerWorld, ServerChunkManager, WorldTickScheduler, NetworkThreadUtils, PistonHandler, and RedstoneController. Fabric 26.2 uses official Mojang mappings. The audited names in [BUILD_ENVIRONMENT.md](BUILD_ENVIRONMENT.md) are starting points for a new target review, not permission to translate descriptors mechanically.

Two Ruthenium mixins that were presented as safety coverage require no porting: ComparatorBlockMixin is empty, and ImposterProtoChunkMixin is a placeholder targeting WorldChunk. Neither demonstrates a 26.2 correctness boundary.

