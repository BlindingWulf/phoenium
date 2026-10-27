# Stage 11: preliminary compatibility-risk assessment (2026-10-16)

Stage 11 is broad Fabric mod compatibility, optional Bukkit/Paper plugin-bridge interoperability, and realistic SMP
functionality testing.

**Status: PLANNING ONLY.** No Stage 11 code exists and none is proposed for implementation now; Stage 9 stays the focus.
The stage definitions are in [ROADMAP.md](ROADMAP.md).

**Source.** A read-only survey of the current experimental candidate D43: worktree
`.candidate-worktrees/stage9-d43-serial`, commit `fd6abb0`, package `src/main/java/dev/phoenium/`. Paths below are
relative to that package. Statements marked *(inferred)* follow from vanilla call paths and were not run. Nothing here
was tested against third-party mods; this is a risk register, not a compatibility result.

## 1. The two runtime modes (they matter differently for Stage 11)

| | **engine mode** (`-Dphoenium.engine.tiles=true`) | **default mode** (no flags) |
|---|---|---|
| role today | the Stage 9 performance mode (D35-D43 benchmarks, phone arms) | Stage 2-8 "serial live" ownership runtime; per `STAGE9_OPEN_ISSUES` item 1, compatibility-only, does not scale |
| mixins applied | 44 engine mixins plus about 5 retained (`PhoeniumMixinConfigPlugin`, `EngineMixinPlugin`) | about 240 of 290 mixin classes |
| off-thread game work | entity ticking and the chunk phase in 4×4-chunk tiles on `Phoenium Tile N` threads; tracker `sendChanges` batches; spawn-state slices | none (one gameplay worker), but packet handlers are cancelled and replayed, and chunk FULL stages are split |
| hard checks against unknown code | few (chunk-request trap; `strict` traps off by default) | **many**: `LiveTickPhaseBoundary` and `FullStageBoundary` reject unaudited injectors in `ServerLevel.tick` and the FULL supplier ("Pinned tick phase drift") |
| shared by both | `SupportedStack.require` refuses to start without the patched C2ME `phoenium-settlement2` unless `-Dphoenium.allowUnsupportedStack=true`; all mixin configs `required: true`, `defaultRequire: 1` | same |

**Consequences for Stage 11:**
- Default mode is *more* hostile to arbitrary mods than engine mode: by design it fails closed on any unaudited
  injector in the tick and FULL paths.
- Engine mode has a smaller mixin footprint, but runs third-party code on worker threads.
- No switch loads Phoenium inert, and no mode keeps vanilla's exact callback thread and ordering while keeping
  Phoenium's other features. Engine mode with `workers=0` still defers effects to the end of each tile colour
  *(inferred)*.

## 2. Risk register (ranked)

| # | risk | mechanism (where) | mods most exposed | severity |
|---|---|---|---|---|
| R1 | **Third-party code runs on tile threads** | `TileEngine.tickEntities` (`engine/TileEngine.java:200-211`) runs the vanilla entity tick per tile on the server thread *or* a tile thread (work stealing). Mod mixins in `Entity.tick` and mob AI, `Block.randomTick` overrides, spawn rules, and damage/death/equipment callbacks fired from entity ticks therefore run off the server thread *(inferred)*. Only a hard-coded serial set stays on the server thread: players, raiders, pearls, dragon, wither, some minecarts, dolphins, portal travellers, player-ridden vehicles (`TileEngine.java:525-533`). | economy (kill rewards), claims and protection (damage cancel), kits and cooldown trackers, custom mobs and AI, anything with non-thread-safe global maps or a database | **High** |
| R2 | **Deferred side effects and early return values** | `TileContext.defer` (`engine/TileContext.java:85`); applied per colour (or per phase in dag) on the server thread (`TileEngine.java:637-789`). The following are deferred: entity add (returns `true` at once), section moves and unlinks, block events, `sendBlockUpdated`, scheduled ticks, tickets, `setChunkUnsaved`, POI, stats, criteria triggers, `PlayerAdvancements.award` (returns `false` when deferred), and scoreboard objective iteration. | mods that add an entity and look it up in the same call, read an award result, rely on immediate ticket effect, or count on `ENTITY_LOAD` timing | **High** |
| R3 | **No entity-access ownership across tiles** | Claims cover blocks and chunks only; trapped are chunk loads, block writes outside the claim, and POI. There is no entity-access trap; the design notes long-range entity effects (TNT cannons) as an open limit. | mods that move entities far, or mutate other players or entities during an entity tick (teleport-on-hit, grappling, mob stacking) | **High / Medium** |
| R4 | **Packet thread and order** | `EngineSendMixin` (HEAD cancellable on `ServerCommonPacketListenerImpl.send`) holds tracker packets in per-thread `EngineOutbox`es. `TileEngine.deliverHeld` redelivers them per connection, possibly from tile threads. `sendFlush` delays tile-thread flushes to the end of `tickChildren`. Per-entity order is kept; order *between* entities on one connection is not vanilla's (`EngineTrackerSends.java:25-27`). A mod hooking `send` may run on a tile thread, and possibly twice per held packet *(unverified)*. | networking and protocol mods (Polymer-style virtual entities, vanish, disguises, anti-xray, packet-based UIs), Via, Geyser | **Medium / High** |
| R5 | **Mixin collisions and fail-closed startup** | Engine hot-path wrappers at priority 2000/3000 (`ServerLevel.tick`, `tickChunk`, `ChunkMap.tick/move`, `ServerChunkCache.getChunk/getChunkNow`, `MinecraftServer.tickChildren`, `PlayerList.tick`, `SectionStorage`) and HEAD-cancellable injectors. A missing target kills startup (`defaultRequire: 1`). Known blocker: VMP's `@Overwrite` of `ChunkMap.tick` (`STAGE6_AUDIT_MATRIX`). In default mode, any unaudited injector in `ServerLevel.tick` or FULL is fatal. | other performance mods (VMP, other tick or chunk optimizers), anything that `@Overwrite`s or `@Redirect`s these methods | **High** (perf mods) |
| R6 | **Pinned stack and bytecode edits to other mods** | `SupportedStack.require` (patched C2ME only). `EngineMixinPlugin.postApply` makes Lithium methods `synchronized` and reroutes `Level.randValue`; C2ME `executeTasksMidTick` gets an early return; `Level.random` is replaced by `EngineRandom`. These are coupled to exact Lithium and C2ME internals. | any C2ME or Lithium version change; mods that read `Level.random` state or `randValue` | **High** (stack lock), **Medium** (edits) |
| R7 | **Chunk access semantics on tile threads** | Tile threads read chunks via `TileContext.chunk/fullChunk`; a load becomes a server-thread "service". A mod's own `ServerChunkCache.getChunk` mixin is bypassed on tile threads (`EngineChunkCacheMixin.java:87-95`). Tickets added from a tile take effect after the colour. | chunk-loader, claim-preview and world-scanning mods | **Medium** |
| R8 | **Entity tracking differences** | `EngineTracker.mayChange` skips far pairs; `EngineTrackerIndex` changes visit order; `TrackedEntityRangeFastPathMixin` cancels `getEffectiveRange` for non-vehicles (in both modes), bypassing other mods' hooks there. Pairing stays serial in vanilla loop order. | vanish, tracking-range, disguise and entity-culling mods | **Medium** |
| R9 | **Plugin bridge** | No Bukkit, Paper or Cardboard code exists. ServerCore declares `"breaks": {"cardboard": "*"}` (`STAGE6_CANDIDATE_MANIFEST.json`). Bukkit plugins assume main-thread events and a synchronous scheduler, which R1/R2 violate in engine mode *(inferred)*. | every Paper plugin | **Blocker** for a bridge on the current pinned stack |
| R10 | **Scheduling semantics** | Engine mode: `server.execute` is vanilla queueing; a task posted from a tile thread runs later on the server thread *(inferred)*. Default mode: `FullFinishingServices.runServerTask` always enqueues under a claim, never runs inline, which changes vanilla's reentrant behaviour. | mods that call `server.execute` and expect inline execution on the server thread | **Low** (engine) / **Medium** (default) |
| R11 | **Persistence timing** | Engine: stats, advancements, `setChunkUnsaved` and POI dirtiness are deferred to the server thread. `autosaveSpread` (default **off**) lost or duplicated entities on hard kill (`STAGE9_OPEN_ISSUES` item 2). No save format changes were found. | economy, home and claim mods that save on these events | **Low** while autosaveSpread stays off |
| R12 | **No opt-out granularity** | No per-mod or per-entity-type allow/deny list and no API; only the hard-coded `TileEngine.serial()` set. An unknown mod cannot be made safe without a code change. | all of the above | **Medium** (it is the main mitigation lever missing) |

## 3. Stage 9 decisions that keep Stage 11 feasible (guidance, not implementation)

**Owner requirement (2026-10-16).** Every future Stage 9 optimization must account for these five properties. A change
that alters any of them needs an explicit note and a test, not only a benchmark:

| property | what to check | risks |
|---|---|---|
| **event-thread requirements** | which thread mod, Fabric API and plugin callbacks reached from the changed path run on | R1, R9, R10 |
| **cross-region entity ownership** | whether the change lets code on one tile or region read or mutate entities owned by another | R3 |
| **callback ordering** | deferred effects, early return values, `ENTITY_LOAD` and award timing | R2 |
| **packet correctness** | sending thread, per-connection order, no duplicate or lost packets | R4, R8 |
| **persistence** | save timing, dirtiness flags, hard-kill safety | R11 |

1. **Keep every engine behaviour behind its existing switch, with documented defaults.** D43's width-serial phases
   (`minWidth`) are also the natural compatibility fallback, because serial phases keep work on the server thread. Do not
   remove that path for benchmark gains.
2. **Do not widen the parallel scope** (block entities in tiles, players in tiles, more deferred operations) without first
   defining which thread mod callbacks run on, and testing it with at least one real third-party mod per affected
   category.
3. **Packet path.**
   - Keep send deferral limited to the tracker batch, as it is.
   - Before Stage 11, add a test with a mod-style `send` hook to settle the double-fire question in R4.
   - The tracker-send deferral (sends after the loop) is kept because the 2026-10-16 desktop A/B showed it cheaper
     (0.76 vs 0.80-0.85 ms/tick). Revisit if a protocol mod depends on vanilla order.
4. **Mixins.** Avoid `@Overwrite` and `@Redirect`; prefer `WrapOperation` and `WrapMethod` (already the norm: one
   `@Redirect` in total, none in engine mode). Keep the engine footprint near 50 classes.
5. **Plan an opt-out registry for Stage 11** (design item): entity types and mod IDs that must tick serially, plus an
   optional "vanilla-ordered" profile (engine on, `workers=0`, no deferral) as the certification baseline. Measure its cost
   in Stage 9 terms before relying on it.
6. **Stack lock.** The C2ME `phoenium-settlement2` pin and the Lithium bytecode edits mean every provider update is a
   compatibility event. Track provider versions in the certification matrix, and keep `allowUnsupportedStack` a
   diagnostic switch, not a user-facing one.
7. **autosaveSpread stays off** for any SMP use until its hard-kill loss is fixed.

## 4. Outline of a Stage 11 certification campaign (for later)

1. **Inventory.** Pick 2-3 representative Fabric mods per category: economy, kits, homes and teleports, permissions,
   claims, inventories, networking, performance. Check each one's mixin targets against the Phoenium footprint
   (static scan; flags R5/R7/R8 conflicts before any run).
2. **Harness.** Reuse the Stage 9 gate infrastructure (`gates/scripts`, testsched, offline bots). For each mod and
   combination run these, in default mode and engine mode (w0 and the shipping width):
   - start and stop;
   - category scenarios (buy/sell with balance persistence across restart, kit cooldown, `/home` across dimensions,
     claim-protected container, permission-denied command);
   - a save/kill/restart check;
   - a duplication probe (inventory transactions under concurrent load).
3. **Thread and order probes.** A test mod that records the thread and order of Fabric callbacks fired from entity
   ticks and packet sends, to measure R1, R2 and R4 directly instead of inferring them.
4. **Bridge study (B).** Confirm whether any bridge (Cardboard or other) exists for 26.2/26.3 and what it patches.
   - It must coexist with C2ME, Lithium and Phoenium; ServerCore currently `breaks` Cardboard.
   - It should run as an optional, separately certified component, never inside Phoenium's core.
5. **Performance (E).** Same workload with the L5 stack without Phoenium against Phoenium plus mods (plus a bridge, plus
   a realistic SMP pack), on the Stage 9 metrics, including joins and stalls.

## 5. Could not be determined from source alone
- whether Fabric API's own events fire from tile threads in 26.2 (Fabric API source not read);
- injector order between Phoenium's HEAD cancel on `send` and other mods' hooks (the double-fire question);
- what Cardboard or another bridge patches for 26.2, and whether any version exists;
- runtime behaviour of any specific third-party mod (nothing was run).
