# Stage 3 server evidence

## Automated evidence

During the 2026-09-02 architecture review, `gradlew.bat clean build --no-daemon`
executed **25 tests (19 CoreAcceptanceTest, 6 Stage3OwnershipTest), zero failures,
errors or skips**. The subsequent `gradlew.bat test --no-daemon` succeeded with tasks
up to date. Documentation was the only repository change. No new dedicated server,
client/bot, real movement or real chunk-application run was performed in this review.
See [the movement review](STAGE3_MOVEMENT_ARCHITECTURE_REVIEW.md) and
[the chunk review](STAGE3_CHUNK_APPLICATION_REVIEW.md) for architectural findings and
the limits of existing test coverage. Future M-01–M-06 designs are not live evidence.
Four separate deterministic core inspection witnesses reproduced the existing epoch,
buffer-validation and pre-transition-inbox defects recorded in the movement review.
They are negative evidence outside the acceptance suite, not additional passing gates.

`gradlew.bat test --no-daemon` exercises deterministic Stage 3 entity, player, stale-envelope, outer-lease, and asynchronous chunk-routing regressions alongside all prior Stage 1/2 tests. The Stage 3 tests use the one-worker scheduler and distinct A/B regions; no caller thread, outer lease, broad gameplay lock, peer wait, or vanilla fallback is accepted as authority.

## Live status

The controlled Stage 2 level-tick route remains unchanged. A fresh disposable Fabric 26.2 server launched the final current Stage 3 artifact and stopped through local RCON. Its final normal marker recorded `1266 intercepted = 1266 dispatched = 1266 executed`, with `duplicate=0`, `bypass=0`, `fallback=0`, `ownershipViolations=0`, `unleased=0`, and `barriers=0`. Packet and chunk observer counters were also zero because no client or explicit chunk producer was exercised. It reported the expected single serial lane and parallel gameplay OFF.

The final artifact was also launched with `-Dphoenium.test.failLiveTickAt=10`. Its clean RCON shutdown marker recorded `1293 intercepted`, `10 dispatched`, `9 executed`, `duplicate=0`, `bypass=0`, `fallback=0`, `ownershipViolations=0`, `unleased=0`, and `barriers=1`, with the scheduler correctly remaining `FAILED`. The deliberate failure was consumed by Phoenium; it did not crash through an outer vanilla fallback.

Its live executor reconciles real Minecraft entities and players into the Stage 3 ownership ledger after the original level tick, while still under the leased controlled operation. The observed server had no player/entity activity (`entities=0 players=0`). Dedicated-server exercise of a real client packet hook, entity boundary movement, and a real asynchronous chunk producer therefore remains **NOT TESTED**: the core envelope contracts exist, but this repository intentionally does not claim an unimplemented Minecraft packet Mixin or C2ME integration as live evidence.

Before a Stage 3 acceptance checkpoint, run a fresh disposable Fabric 26.2 server with a client/bot movement and entity-interaction trace, an actual player crossing a configured multi-region boundary, and a forced base chunk-future completion whose owner changes before delivery. Record ledger epochs/routing keys and the packet/chunk diagnostics, then repeat the same clean shutdown proof.
