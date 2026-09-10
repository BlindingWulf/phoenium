# Stage 7 Geyser/Floodgate lifecycle repair

Updated: 2026-09-10

## Scoped result

- Geyser-Fabric: `2.11.2-b1234`, SHA-256 `1C3AC7B8C62FA7CBC6B82AF57681BD8AB0ECD693B1F06AB971ED79976CE65EF5`
- Floodgate Fabric: `2.2.6-b67`, SHA-256 `9F736BA3EE86B8F8613868FEAFA35F9A3218C3C45EF4B7E4992C34EA1B044DC4`
- Result: **PASS_ADAPTER for the reproduced post-stop WebSocket lifecycle defect**.
- Bedrock login, identity, movement, inventory and persistence remain **PARTIAL / NOT TESTED** because no supported Bedrock client was exercised.

## Historical failure

The frozen Stage 6 W1 restart completed Minecraft save and shutdown, including a clean Phoenium
STOPPED receipt, but the Java process did not exit. Its post-stop thread dump contained no
Minecraft `Server thread` and retained three non-daemon Java-WebSocket threads:

- `WebSocketConnectReadThread-235`
- `WebSocketWriteThread-236`
- `connectionLostChecker-1`

The Geyser log reported `Geyser shutdown successfully` at 22:45:50. The leaked WebSocket threads
had elapsed times showing they were created at approximately 22:46:04, about 14 seconds after
that shutdown message. The process was therefore held by an external-service reconnect, not a
Phoenium world-write or server-thread deadlock.

Historical evidence:

- `run/stage6/frozen-audit-045834b/runs/geyser-floodgate-w1/restart.log`
- `run/stage6/frozen-audit-045834b/runs/geyser-floodgate-w1/restart-post-stop-threads.txt`
- `run/stage6/frozen-audit-045834b/runs/geyser-floodgate-w1/forced-post-stop-termination.json`

## Exact provider race

The locked Geyser JAR contains `org.geysermc.geyser.skin.FloodgateSkinUploader`, whose client is a
Java-WebSocket `WebSocketClient`. Its close handler always calls `reconnectLater(GeyserImpl)`.

`reconnectLater` checks `scheduledThread.isShutdown()` and the uploader's existing volatile
`closed` flag **when scheduling**, then submits a direct `client.reconnect()` runnable with an
8-14 second randomized delay. That delayed runnable does not recheck `closed` when it executes.

The locked `GeyserImpl.disable()` order is materially important:

1. shut down the scheduled executor with `ExecutorService.shutdown()`;
2. later call `FloodgateSkinUploader.close()`, which sets `closed=true` and closes the WebSocket.

A reconnect queued just before shutdown remains eligible to execute after `shutdown()` under the
executor's delayed-task policy. Because the queued runnable calls `client.reconnect()` directly,
it can recreate non-daemon WebSocket threads after the uploader has been closed. The frozen
failure's approximately 14-second delay matches the provider's 8-14 second reconnect window.

Floodgate itself also contains a WebSocket skin upload client, but its connection is created from
the Floodgate handshake path. The failing frozen lifecycle had zero players. The reproduced timing
and Geyser bytecode identify Geyser's `FloodgateSkinUploader` delayed reconnect as the relevant
path.

## Repair

Phoenium now applies `GeyserFloodgateSkinUploaderMixin` only when both `geyser-fabric` and
`floodgate` are loaded. The mixin wraps the exact scheduled reconnect submission in
`FloodgateSkinUploader.reconnectLater` and replaces the delayed runnable with a guard that checks
Geyser's own existing `closed` flag at execution time.

While the provider is open, reconnect behavior is unchanged. Once the provider has closed, an
already-queued reconnect becomes inert. No provider feature is disabled, no extra lifecycle model
is introduced, and no owner-to-owner blocking or global serialization is added.

The injection is fail-closed against provider shape drift: the external mixin is optional by mod
presence, while its exact wrap site requires one matching scheduling call when the locked provider
combination is present.

## Verification

Focused regression:

- open provider state -> guarded delayed reconnect executes;
- closed provider state -> the same queued action does not execute;
- production mixin registration is present.

Locked Geyser+Floodgate quick-stop lifecycle, the same timing shape that failed historically:

- W1 fresh: PASS, native exit 0;
- W1 restart: PASS, native exit 0;
- W4 fresh: PASS, native exit 0;
- W4 restart: PASS, native exit 0;
- Phoenium stopped health remains clean.

Evidence:

- `run/stage7/geyser-floodgate-reconnect-repair-w1/`
- `run/stage7/geyser-floodgate-reconnect-repair-w4/`

Geyser-only control with offline Java auth and no Floodgate:

- W1 fresh: PASS, native exit 0;
- W1 restart: PASS, native exit 0.

Evidence: `run/stage7/geyser-alone-control-w1/`.

Full deterministic suite after the repair: `847` tests across `91` suites, zero failures, errors
or skips. Candidate production JAR SHA-256:

`D095C007C1B81698440F3BF0145E38A23DC6078F7033D713324F61F15F2C3CC9`

## Remaining provider acceptance

This repair closes only the demonstrated shutdown-lifecycle defect. Overall Geyser/Floodgate
compatibility stays PARTIAL until a supported Bedrock client exercises login, Floodgate identity,
movement, inventory/state persistence and restart under the accepted provider pair. A UDP bind or
Java-only lifecycle sample is not a substitute for those feature checks.
