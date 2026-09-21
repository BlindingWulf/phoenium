# Stage 7 player respawn handoff

Parent: `a0884bae886246f3ace19afe2ca18baa1f52343a`.
This change coordinates one synchronous `PlayerList.respawn` call. It retains one
Phoenium connection session while replacing the exact `ServerPlayer` object. It
does not change dimension-transition architecture or vanilla publication order.

## Lifecycle and ownership

`OPEN -> OLD_RETIRING -> OLD_RETIRED -> PREPARED -> INSERTING -> WORLD_INSERTED
-> PUBLISHED -> COMMITTED`.

A failure before commit enters `ROLLING_BACK -> ROLLED_BACK`; a cleanup invariant
failure enters `FAILED` and preserves the original exception with suppressed
cleanup failures. Terminal cleanup calls do nothing. The session detaches the
handoff at successful commit or rollback. World/server close aborts an attached
handoff before closing its backing services.

The handoff captures the coordinator thread, generation, session ID, retained
Connection/listener, UUID, old object/world/incarnation, and then the exact
replacement object/world/incarnation. In the still-present opening, OPEN leaves
the old player current until vanilla removes its list entry: resolving the respawn
location may load chunks and perform ordinary recipient tracking. A death-retired
old entity has no such ordinary tracking permission.

### Death retirement amendment

Minecraft 26.2 `LivingEntity.tickDeath` calls `remove(KILLED)` at death tick 20.
`Entity.setRemoved` synchronously calls the persistent entity callback. That
callback stops tracking, removes both visible lookup entries, removes the UUID
storage claim and section member, and calls Phoenium's storage retirement hook.
The `PlayerDeathRetirementMixin` wraps only that `tickDeath` removal call and marks
the exact `ServerPlayer` after the original call returns. The mark is never made
from a missing lookup. It is cleared on exact disconnect, handoff commit, or
rollback; a disconnected object is not a live-session candidate.

| Observation | Dead entity still present | Completed native death removal | Disconnect |
| --- | --- | --- | --- |
| Connection and Phoenium session | exact retained connection; LIVE session | same connection and LIVE session | session CLOSED/removed |
| Vanilla `players` and `playersByUUID` | both point to old object | both still point to old object | old entries removed |
| Native world UUID/runtime ID and `world.players` | exact old object | absent after callback | absent |
| Phoenium UUID player directory | exact old object | exact old object until respawn list/UUID publication | retired with disconnect |
| World identity and tracker publication | old entity published | retired by native callback; completion receipt is on exact old object | retired |
| Old object | not removed, no removal reason | removed `KILLED`, death time at least 20, exact callback receipt | old object may remain `KILLED`; receipt invalidated |
| Listener `player` alias | exact old object | exact old object until vanilla post-respawn assignment | not a current live alias |

Opening requires the exact LIVE session, old list and UUID owners, Phoenium UUID
owner, and listener alias. The still-present mode requires exact native world UUID,
runtime ID, and world-player membership. The death-retired mode requires the
completed death callback receipt, `KILLED`, death time at least 20, and absence
from all three native world views. Any different world object, missing receipt,
stale session, changed listener, or terminal object is rejected. OPEN never
authorizes ordinary tracking of an already death-retired player.

In the death-retired mode the native old-world removal invocation site is still
observed in its original position, but its body is skipped because `remove(KILLED)`
already completed. `OLD_RETIRING` and `OLD_RETIRED` still run; their storage and
tracking checks use the completed callback receipt and exact native absence.
The already executed scoreboard list removal is not replayed during rollback.
Replacement preparation, insertion, publication and commit retain their existing
order. No old entity is reinserted and no new ownership record is made for it.

With diagnostics enabled, the bounded `PHOENIUM_PLAYER_RESPAWN_PRECHECK` record
is emitted once per exact old object before session attachment. It reports
independent list, UUID, world UUID, world ID, world-player, listener, session,
native removal, receipt, and active-attempt fields, plus an accepted opening mode
or rejection reason. Identity and ownership internals unavailable at this edge
are explicitly `UNAVAILABLE`; the native callback completion receipt does not
pretend to be a live tracker lookup. A diagnostic exception cannot replace the
admission exception. Handoff phase markers also name the opening mode.

SCOREBOARD scopes cover opening, old list removal, replacement preparation,
new list addition, UUID publication, commit, and directory rollback individually.
The existing `remove` and `registered` operation checks still run. World removal
and insertion use bounded reference scopes. SCOREBOARD authority is not held
across those world operations.

## Vanilla hooks and commit

The preparation hook runs at `ServerPlayer.restoreFrom` entry, immediately after
vanilla assigns the retained listener to the replacement and before
`ServerLevel.addRespawnedPlayer`. Old world removal has completed by then.
Replacement tracking uses the prepared connection identity without requiring
`listener.player` to have changed.

Tracking receives a single exact action receipt: old REMOVE while retiring, new
ADD while inserting, or exact old/new REMOVE during rollback. Ordinary cuts do
not admit these transitional aliases. Cuts, receipts, source facts, routing
revision and topology are validated by the existing tracking admission path.
An action receipt expires when its synchronous operation closes. Repeated removal
after successful retirement cannot execute its native body twice.

The sole commit point is successful return from the entire original respawn
method, after its publication suffix. It requires old world/tracking retirement,
replacement world/tracking insertion, vanilla list membership and UUID mapping,
and the Phoenium session/scoreboard directory to identify the replacement. Commit
retires the old object and detaches the handoff. A separate hook observes each
of vanilla's two post-respawn listener assignments and verifies convergence.
It never assigns that field early.

## Failure and exact cleanup

Rollback removes the failed replacement and normalizes the old/dead connection
for reconnect; it does not resurrect the old player. Native storage UUID claims
and section insertion before callback installation have a dedicated exact
prefix receipt. After callback installation, cleanup uses native world removal.
Before native UUID/runtime-ID removal, both lookup entries must be absent or
identify the exact object. Directory cleanup uses `==`, not `Entity.equals`
(vanilla reuses the runtime ID and equality compares that ID).

If native tracker insertion or reverse recipient pairing throws, the respawn
path retains the exact partial tracker/recipient projection. Its callback failure
returns to the respawn coordinator after the owner lease releases, permitting
strict tracking rollback without tripping the scheduler's common failure barrier.
Ordinary service failures retain their existing behavior.

A reentrant disconnect requests abort; rollback waits until the admitted tracking
body unwinds. Session retirement and a terminal bit on each exact failed/old
player object fence later disconnect, storage-add, and entity-removal callbacks.
No UUID-only search selects a cleanup target. A terminal failed-incarnation
rollback cannot touch a later reconnect, including one reusing the runtime ID.

Diagnostics are enabled only by `phoenium.test.diagnostics`: one
`PHOENIUM_PLAYER_RESPAWN_HANDOFF` marker per state transition records UUID,
generation/session, old/new incarnation/object identity and observed routes.
`PHOENIUM_PLAYER_RESPAWN_LISTENER` records final convergence. No tick logging is
added by this primitive.

## 9063014 deterministic verification

Final focused run: **34 tests, zero failures/errors/skips**. The production
handoff lifecycle matrix covers success, truthful scopes, preparation before
tracking, one-time retirement, eleven failure boundaries, disconnect during
tracking, incomplete UUID publication, impostors, sequential replacements,
world/server close, listener convergence, and stale cleanup after same-UUID/ID
reconnect. Tracking tests exercise the actual RegionalTrackingService and
dispatcher, including native tracker and reverse-pairing partial failure. Storage
tests exercise the production prefix hook with native entity-section storage.
Vanilla-only ASM contract tests verify ordering and exact hook sites.

Targeted existing regression run: **138 tests, zero failures/errors/skips**,
covering tracking/route handoff, scheduler/dispatcher, connection/scoreboard,
entity identity, player dimension transitions and entity removal hooks. Accepted
tests were not modified.

Final scoped broader deterministic run: **1,171 tests, zero failures/errors/skips**.
The user explicitly authorized this scoped suite as the commit/live gate.
The unfiltered exploratory run had 1,253 tests and 73 failures before one final
focused test was added. Forty-eight failures in the unchanged historical
`FullNonselfInputDecisionTest` reproduce on parent a0884ba (same test names and
exception types). Twenty-five failures were missing historical provider-audit
fixtures. Provider inspection is prohibited for this task; no missing binary was
inspected. The scoped run excludes that 48-test prototype and these eight
provider-audit classes (35 cases in the current inventory):

- C2meDiskReadOperationTest
- C2meDiskReadRxTest
- C2meFullProviderBinaryTest
- C2meGenerationProviderBinaryTest
- C2meNativeAvailabilityTest
- C2meNativeFailureSettlementTest
- C2meReadProviderBinaryTest
- C2meSendReadyInputsTest

The scoped filter is an external Gradle init script under ignored
`build/respawn-verification/bounded-suite.gradle`; repository test configuration
and accepted tests remain unchanged. Parent reproduction uses baseline production
sources compiled under ignored build output, not a new worktree or a source reset.
XML results, commands/filter and logs are preserved with the candidate evidence.

## Death retirement amendment verification

The new witness ran the mapped 26.2 `LivingEntity.tickDeath` body for 20 calls,
including its real `Entity.setRemoved` call. The fixture controlled the world
broadcast, `LivingEntity.remove` endpoint, and `EntityInLevelCallback.onRemove`
projection; the production `PlayerDeathRetirementMixin` receipt call was invoked
at that completed boundary. It asserted actual native removal and both absent
world lookups before requesting respawn. On the unamended 9063014 guard it failed
with `old publication is not current`. The ASM contract verifies the native
`tickDeath` removal and persistent callback order; it does not claim that the
controlled endpoint is a complete Bedrock server.

Final amendment tests: **44 focused**, **138 targeted regressions**, and **1,181
scoped broader**, each with zero failures/errors/skips. The same nine excluded
classes remain in the scoped filter: the historical `FullNonselfInputDecisionTest`
and the eight provider audits listed above. The unfiltered run had **1,264 tests,
73 failures**: the same 48 parent-reproduced prototype failures and 25 unavailable
provider-fixture failures. The unfiltered suite is not green. The XML, exact
commands and filter are in the death-retirement evidence directory. Real Bedrock
death, respawn, reconnect, and second-cycle acceptance remains for Luna.

## Scope and live evidence

The accepted tracking tick retirement and initial addEntity drain/receipt order
remain intact. No provider, portal, passenger, packet-admission, natural-spawn,
Vault, Via or voice-chat implementation changes are included. The world-close
hook also aborts respawn state before existing passenger close; passenger behavior
is unchanged.

The candidate manifest and one fresh disposable Bedrock validation record are
stored outside the worktree in the dedicated respawn audit directory. They record
the committed revision, JAR identity, unchanged provider/config hashes, first live
failure if any, gameplay observations, death/respawn/reconnect chronology and
shutdown status. Deterministic success alone is not a live pass.
