# Stage 11 entity-hook gate (engine tile threads and third-party mods)

Status: development integration (`stage11/integration`). The owner approved controlled integration of gate v1 (85b9dd0) on 2026-10-18. Not promoted. A passing test is not a release decision.

## What it protects

With `phoenium.engine.workers` > 0, the engine runs non-player entity ticks on tile threads. Those ticks include mob AI and projectiles, and with them every damage and death the ticks cause, including a player's death to a mob. A third-party mod's entity callback can therefore run on several threads at once. Typical SMP mods keep their state in plain maps.

Stage 11 evidence:
- universal-graves crashed in its grave model tick (s11gate-graves on D43);
- OPAC claims were not protected from explosions;
- the deathstorm fixture saw 10 overlapping entries into one plain map.

The gate keeps tile parallelism only when every such hook belongs to a certified mod. Otherwise the tiles run on the server thread alone, exactly as `phoenium.engine.workers=0` would: same phases, same order, and vanilla's thread for every callback.

## Decision (logged at server start)

```
PHOENIUM_COMPAT entityHooks=PARALLEL foreign=[]
PHOENIUM_COMPAT entityHooks=SERIAL foreign=[graves, ...] (tiles run on the server thread: ...)
PHOENIUM_COMPAT entity hook mod=<id> kind=event|mixin <detail>      one line per foreign hook
PHOENIUM_COMPAT entity hook ignored mod=<id> mixin=<class> ... reason=server-thread-only|no-injector
PHOENIUM_COMPAT applied-mixin cross-check entityClasses=N unseenMixins=N judged=N ms=T
PHOENIUM_COMPAT changed entityHooks=SERIAL ... (registered after start; ...)   at most once per run
```

The stack without add-ons stays PARALLEL. Its decision reads no mod files, because certified mods are skipped.

### What counts as a hook

1. **Listeners on the Fabric entity events** fired from tile ticks:
   - `ServerLivingEntityEvents` ALLOW_DAMAGE, AFTER_DAMAGE, ALLOW_DEATH, AFTER_DEATH, MOB_CONVERSION;
   - `ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY`;
   - `ServerEntityEvents.EQUIPMENT_CHANGE`;
   - `ServerPlayerEvents.ALLOW_DEATH`.

   Each listener is attributed to the mod whose jar holds its class.
2. **Mixins on entity classes** (`net.minecraft.world.entity.*` and `ServerPlayer`), read from each uncertified mod's own `fabric.mod.json`, its mixin configs and its class files.
   - Interface mixins (accessors and invokers) add no callbacks and are skipped.
   - **v2:** a mixin counts only if it can run on a tile thread:
     - It counts when it injects into, overwrites or overrides anything other than the server-thread-only player methods. Those methods are login and respawn (`restoreFrom`), permissions, save data, the tab list, menus, game mode, abilities and disconnect, on `ServerPlayer` and `Player`. The list is `EntityHookGate.SERVER_THREAD_ONLY`.
     - A mixin with no injector and no merged public method adds only fields and its own methods, so it does not count.
     - Any selector that cannot be read (wildcard, regex, `@Desc`, or a missing `method`) counts.
3. **v2: mixins Mixin actually applied.** At start, the gate reads Mixin's class metadata (`ClassInfo` applied mixins). This finds entity mixins that the config scan never saw: those added by a config plugin (`IMixinConfigPlugin.getMixins`) and those from programmatic configs. They are judged by the same rule. If Mixin's internals cannot be read, a WARN is logged and the decision uses the config scan alone.
4. **v2: listeners registered after start.** While the tile threads run, the listener arrays are re-checked each server tick, one field read per event. If an uncertified listener appears, the tiles switch to the server thread for the rest of the run, with one `changed` line. The switch never goes back to parallel until the next server start.

### Certified mods

`EntityHookGate.DEFAULT_CERTIFIED`:
- the L5 stack: C2ME, Lithium, ScalableLux, ServerCore, Clumps, Chunky, Via*, Geyser, Floodgate and NoChatReports;
- Phoenix's managed mods (`phoenix-spawnprep`);
- Phoenium and its telemetry;
- Fabric API modules (`fabric-*`), which are dispatchers; the listeners they call are judged by their own mod.

## Operator overrides (not the default)

| Property | Effect |
|---|---|
| `-Dphoenium.engine.foreignHooks=allow` | Keeps the tile threads even with foreign hooks, at the operator's risk. The decision line shows `override=allow`, and every foreign hook is still logged. Use it only for mods you have verified to be thread-safe. It restores plain D43 behaviour, where both the graves crash and the OPAC explosion bypass occurred. |
| `-Dphoenium.engine.foreignHooks.allowMods=a,b` | Treats the listed mod ids as certified. Use it for mods proven safe on tile threads on this exact version. |
| `-Dphoenium.engine.workers=0` | No tile threads at all; same behaviour as SERIAL. |

Any other value of `foreignHooks` is treated as `auto`, so foreign hooks still mean SERIAL.

## Known limitations (owner review 2026-10-18)

- **L-a (narrowed in v2):** the gate is per mod, not per callback. One tile-reachable hook makes the whole run SERIAL, even when the mod's other hooks are harmless.
  - The method rule removes the common harmless cases, confirmed live on a2cde95:
    - LuckPerms (`permissions`, `restoreFrom`): PARALLEL alone;
    - polymer-core's `initMenu` mixin and the injector-free mixins of polymer-networking, polymer-virtual-entity and server_translations_api.
  - Mods with real tile-reachable hooks still serialize by design. Examples: Essential Commands, Styled Chat and SGUI hook `ServerPlayer.die`; Vanish, GOML, Ledger, OPAC, Graves and Polymer hook damage or ticks.
  - Player Roles stays SERIAL, correctly. Its `name_decoration/ServerPlayerEntityMixin` merges a public `getDisplayName()` override into `ServerPlayer`, and death messages call it on the tile that kills the player. The offline census prediction missed this, because the census does not record merged methods; the gate reads the class bytes.
- **L-e (documented, not addressed; conservative):** public methods a mixin merges to implement its own mod interface count as possible overrides, because the rule matches merged methods by name only.
  - Example: PlayerAbilityLib's `ServerPlayerMixin` merges `listPalAbilities`, `get` and `refreshAllPalAbilities`. Its injectors touch only server-thread-only methods. Vanilla cannot call these methods, yet the mod counts as foreign (s11gv21-goml-D43G-1).
  - No measured stack changed decision because of it: PlayerAbilityLib arrives with GOML, SGUI or Polymer, which serialize anyway.
  - Possible fix: skip merged methods that implement a non-`net.minecraft` interface the mixin itself adds. Not done in v2.1.
- **L-b (addressed in v2):** listeners registered after start are watched. Events outside the list above are not watched or scanned at all; see L-d.
- **L-c (addressed in v2):**
  - The applied-mixin cross-check covers config plugins and programmatic configs for entity classes loaded by server start. Entity classes are loaded at bootstrap through the `EntityType` registry.
  - An entity mixin applied to a class first loaded after start would be missed.
- **L-d (documented, not addressed):** the target scope is entity classes only. Code a mob tick reaches outside them is not judged: `ServerExplosion`, block behaviour, loot tables, scoreboard, advancements and level events.
  - The OPAC explosion case was caught only because OPAC also hooks entity classes.
  - Widening the scope to those classes would serialize nearly every SMP stack, since most mods hook blocks or explosions.
  - Closing L-d properly needs per-callback thread confinement, not a wider gate. That is a Stage 11 design item, not a v2 change.

## Evidence

- v1 (85b9dd0):
  - graves 40/40;
  - OPAC 3/3;
  - deathstorm 0 overlaps;
  - inert PARALLEL foreign=[] without add-ons;
  - benchmarks with no regression; start time unchanged; liveAfterGC 374-385 vs 372-382 MB.
- v2.1 (a2cde95, 2026-10-18), all runs PASS, no fatal errors or crash reports. The applied-mixin cross-check took 13-19 ms with no WARN.
  - t0 without add-ons: 23/23, PARALLEL foreign=[], appliedMixins 793-809.
  - LuckPerms: 29/29, PARALLEL.
  - Graves: SERIAL (polymer-*, sgui, universal-graves).
  - Deathstorm dds2: 7/7, SERIAL.
  - Styled Chat: 9/9, SERIAL.
  - Player Roles: 13/13, SERIAL (see L-a).
  - GOML: 32/32, SERIAL (see L-e).
  - still8 benchmark: mean 1.06 ms, against D43 1.01 and v1 0.99.
  - explore8 benchmark, 5 interleaved pairs: gate mean 2.728 ± 0.117 ms vs plain D43 2.678 ± 0.048 ms (+0.05 ms, Welch p = 0.41, not significant). p95 5.31 vs 5.19; p99 7.78 vs 7.95.
    - entityParallelRuns 4614-4750 vs 4664-4752. Start 16.1-18.2 s vs 16.1-23.1 s.
    - The per-tick watch is about eight reflective field reads, well under a microsecond.
    - Runs: s11gv21-bench-D43G-explore8[-2..-5] and s11t0-bench-D43-explore8[-2..-5]; details in `.audit/stage11-20261017/GATE_V2_REVALIDATION.md`.
- Matrix: `.audit/stage11-20261017/STAGE11_MATRIX.md`.
- Per-mod census: `.audit/stage11-20261017/GATE_CENSUS.md`.
