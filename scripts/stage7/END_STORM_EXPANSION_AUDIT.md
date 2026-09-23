# Stage 7 opt-in End storm expansion audit

## Scope at source freeze

`run_storm.py` now accepts `--routes END_ENTRY,END_EXIT` as a separate,
deterministic W4 cross-dimension run. It retains the default 2,240-contact
Nether route order and family plan. For End routes, four Overworld or End source
lanes are placed at x=32,64,96,128; each has one exact End portal block at
y=65,z=8. Every source portal is rechecked and repaired before each segment.
An entity enters at x+0.5,65.25,8.5, matching the positive I0 item End-entry
contact height. The planner balances families, routes, and spatial lanes.

The runner requires one `CAPTURED*` observer event for each deterministic UUID,
one source retirement, one destination admission, one tracking return, one
suffix settlement, the exact expected source/destination world and route, zero
owner violations, and no pending transaction. Missing contacts and native
refusals fail the instance. End and Nether entity cleanup uses dimension-scoped
selectors and a global segment-tag absence check. A transferred Nether sentinel
still proves save/normal stop and same-world restart. An End pilot can set
`endCrossPilotPass`; `stage7FullStormGate` remains false.

## Disposable seed lineage

The original Nether storm seed has no End region files. The End mode instead
copies this completed installed audit world into a fresh storm namespace:

`C:\Users\Admin\Downloads\Project PHOENIUM\.audit\sol-ultra-final-campaign-20260923\installed\no-entry-full-scoped-75d6-no-native-entry-w4\server\STAGE7-REPAIR-W4`

Its `first-result.json` reports `pass=true`, `txPass=true`, `normalSave=true`,
and `serverExitCode=0`; SHA-256 of that receipt is
`6039822E48A137412BDD8675B2FEEF3B5FDC83C8B626C78FB00A5D88B8F7DC90`.
The SHA-256 of the canonical 61-file source-world hash manifest, excluding
`session.lock`, is
`0BB7B5DEE19A40AFEADF997B895DC15E33F6C134A818D1F3F51B532F4F951FFC`.
Both digests are pinned in the runner and checked before server launch.
The source contains 1,849 pre-generated End chunk coordinates, including the
four source lanes and the native End entry platform neighborhood. Before any
server launch, the runner checks the End and Nether lane chunk headers, hashes
all copied source-world files, verifies the fresh copy, and records the receipt
and seed hashes in `inputs.json`. The prior installed world is only read. Every
recursive copy destination is resolved under the fresh storm namespace before
the copy begins. Preflight rejects inherited `stage7_storm` tagged entities.

## Pilot command after product/probe freeze

Use a fresh name and the then-current immutable product/probe paths. The first
run should exercise only two already fixtured entity types and both End routes:

```powershell
python -u scripts/stage7/run_storm.py --name end-cross-pilot-w4-1 --candidate <immutable-product.jar> --probe <immutable-probe.jar> --routes END_ENTRY,END_EXIT --families item,chicken --attempts 16 --minimum-contacts 16 --wave-size 8 --workers 4 --java-port 25734 --bedrock-port 19234 --rcon-port 25834
```

This command is prepared and covered by Python tests; it has **not** been run
against an installed server yet. A successful 16-contact pilot supports only
item/chicken End entry/exit in these four lanes. More families, larger counts,
and final-byte reruns need their own receipts. A red pilot preserves its first
failed namespace and copied world.

## Gateway and connected-player boundary

End gateway is a same-dimension, same-native-object relocation. Its proof is
different from the cross-dimension replacement contract. The existing G1 card
uses a connected client and exact source/arrival UUID, object, native ID,
cooldown, source vacancy, portal ticket, and tracking witnesses. The global
`stage7-probe snapshot` response can exceed RCON's 4,096-character bound in a
storm, so this RCON runner does not count gateway contacts.

Proposed read-only probe command for a later coordinated Java change:

`stage7-probe gateway <uuid>`

Proposed bounded JSON schema: `stage7-gateway-snapshot-v1` with the requested
UUID, at most one live `entity(e)` row, exact-UUID `gatewayContactEvents`,
`gatewayTicketEvents`, and `gatewayTrackingEvents`, plus a `truncated` boolean.
The command must fail if its reply would exceed 4,096 characters or any event
needed for the current UUID has rolled out of the buffer. The runner would
capture source object/native ID while ticks are frozen, then require the same
object/native ID after relocation, destination chunk/owner/cooldown, exact
source vacancy, ticket and later tracking under End owner, and at least 20
post-arrival native ticks. No gateway result is credited from transaction
status alone.

A connected player cannot be summoned by RCON. The existing client GameTest
`playerPortals()` drives a real Java client for Nether entry/return, but does
not attach that session to this independent storm server or drive End player
routes. Player W4 storm credit requires a coordinated client fixture that joins
this exact server, performs native contacts during the same wave, and correlates
session UUID, visible destination, owner observations, save/stop, and restart.
The current End expansion makes no player claim.
