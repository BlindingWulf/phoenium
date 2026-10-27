# Changelog

## 0.2.0-beta.1 (2026-10-27) - first public beta, Minecraft 26.2 / Fabric
Based on release candidate RC1 ("D45" = Stage 9 engine "D43" + entity-hook Gate v2.1).

### Fixed
- **Server crash when a custom spawner read terrain that was still loading (default mode).** A custom spawner attempt
  that reaches an unfinished chunk inside an owner transaction now ends that attempt only and logs
  `PHOENIUM_SPAWNER_TERRAIN_NOT_READY spawner=<name> world=<dim> attempts=<n>`; the spawner tries again next cycle.
  Every other failure still stops the server (fail-closed). (`RegionalChunkGameplayService.runSpawnerAttempt`,
  `ChunkSynchronousAccessGuard.unfinishedWait`, tests in `RegionalCustomSpawnerBoundaryTest`.)

### Included from RC1
- Engine mode (`TileEngine`): colored tiles on worker threads + server thread, `dag` schedule, deferred effects applied on
  the server thread in a fixed order, serial fallback below `minWidth`.
- Gate v2.1: entity-damage/death hooks (Fabric events and foreign mixins, including late listeners) switch tiles to SERIAL.
- MEM-6 fix: C2ME worldgen tasks release finished work (no orphaned proto chunks); noise-chunk release after CARVERS.
- Tracker, spawn and packet-send fast paths; A3 mob-decision protection; projectile/pearl caps.

### Changed
- Version `0.2.0-stage2` -> `0.2.0-beta.1`; mod description updated. Built jar: `build/libs/phoenium-0.2.0-beta.1.jar`.
- Added `scripts/fetch_test_fixtures.py`, public docs (`README.md`, `docs/MYTHOS_*`, `docs/KNOWN_LIMITATIONS.md`,
  `docs/VALIDATION.md`, `docs/STACK_LOCK.json`), and the reference test harness (`tools/reference-harness/`).

### Known limitations
See [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).
