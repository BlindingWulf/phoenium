# Phoenium 0.2.0-beta.1: known limitations and unverified areas

Beta, not stable. Each item says what was (or was not) tested. Evidence summaries: [VALIDATION.md](VALIDATION.md).

## Correctness / stability
- **Fixed in this beta (P0): server crash when a custom spawner read terrain that was still loading (default mode).**
  Vanilla `CatSpawner` checks `hasChunksAt` (ticket level only), so a chunk ticketed FULL but still loading passed; the
  block read inside Phoenium's owner transaction hit `ChunkSynchronousAccessGuard` and stopped the server ("Exception
  ticking world"). Seen once in real play after a respawn at spawn. The attempt now ends and logs
  `PHOENIUM_SPAWNER_TERRAIN_NOT_READY`; every other failure still fails closed. A desktop reproduction did not hit the
  timing window on a fast PC, so the fix is proven by unit tests and code path, not by a live repro. Other vanilla code
  paths that can reach `getChunk` on an unfinished chunk inside an owner transaction may exist (audit item for reviewers).
- **A failed stop does not save (by design, fail-closed).** If the server crashes, players and the world roll back to the
  last autosave (vanilla interval). The world itself is not corrupted by this.
- **Hard kills** lose whatever vanilla had not yet saved.

## Performance
- **No performance improvement is claimed** for this beta over the previous candidate; the RC1 A/B showed no regression
  (8/8 runs, 16 bots, desktop).
- **GC pauses with 32 players exploring on a 4 GB heap:** about 3 % of time in G1 pauses, p99 pause about 380 ms.
  Accepted as a beta limitation, not resolved.
- **Mods that hook entity damage or death force SERIAL tiles** (Gate v2.1). This is the correct, safe behaviour, but such
  servers get no parallel speed-up in engine mode. The tested SMP stack (Essential Commands, Ledger, OPAC, Polymer,
  VillagerBased PlayerShops, burger-economy) runs SERIAL; EconomyCraft alone keeps PARALLEL.
- **Default mode** (no flags) runs one gameplay worker with `parallel=OFF`: stable, but no speed-up.

## Compatibility
- **Hard requirement: the patched C2ME build `phoenium-settlement2`** (attached to the release). Other C2ME builds are
  refused at startup (`-Dphoenium.allowUnsupportedStack=true` exists but is unsupported).
- Only Minecraft **26.2**, Fabric Loader **0.19.5**, Java **25** were tested. Not all Fabric mods work; see
  `docs/STACK_LOCK.json` (configurations A/B/C) for what was tested.
- **Economy mods are not crash-safe (upstream).** EconomyCraft and burger-economy can duplicate a listing or shop-sale
  money, or lose an auction item, if the server process is killed between the mod's own save and vanilla's next player
  save. Phoenium neither causes nor fixes this. Stop servers cleanly; keep backups.
- Keep `phoenium.engine.autosaveSpread` OFF (default), especially on economy servers.
- No verified Fabric 26.2 `/kit` mod: do not advertise kits.
- Plugin bridges (Cardboard): excluded (incompatible with the pinned ServerCore and with parallel tiles).
- Upstream issues seen in testing: a rare C2ME "Chunk not there" spawn-search race that refuses a join and heals on rejoin;
  OPAC's optional Create-compat probe logs a harmless `ClassNotFoundException`; Universal Graves crashed once on a
  development arm (not re-investigated).

## Not verified (not advertised as supported)
- **Bedrock real-client play:** one real Bedrock session (Geyser/Floodgate) worked until the P0 crash fixed here; the
  formal 11-step Bedrock checklist (login, blocks, inventory, death/respawn, bed, Nether portal, pearls, riding through a
  portal, rejoin, persistence, elytra) has **not** been run. Protocol and startup smoke tests pass.
- **Legacy Java clients through Via**: the mods load; no person-played session. UNVERIFIED.
- **Simple Voice Chat between two real clients**: UNVERIFIED (one client only).
- **The gate chain on the exact beta jar** is being re-run; see VALIDATION.md for status.

## Android (HARDWARE VALIDATION REQUIRED)
- Results are per device (two Moto G phones, MT6835 class). Desktop JVMs and emulators do not prove Android behaviour.
- In a release build of the Phoenix Android app, Phoenium runs in **default mode** (no `-D` flags): stability, lifecycle
  and heap passed on one phone (45 min); no performance claim. Engine-mode phone results came from debug test builds.
- Screen-off joins of many players on a fresh world are the hardest case; world pre-generation (radius 256) is the
  mitigation used by the app.

## Experimental (off by default, NOT part of the beta)
Published only as separate `experimental/*` branches: death confinement, tile idle backoff, heap governor, FlowSched queue
compaction (DO NOT DEPLOY: crashes at boot), Phoenix spawn-preparation helper. The H-BIG big-core placement add-on for
Android and the Java AOT cache are not part of this release.
