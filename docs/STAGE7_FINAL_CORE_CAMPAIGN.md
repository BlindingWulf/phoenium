# Stage 7 core repair campaign

The campaign starts at `692a21aec8b033db67f70bff4a80e9bef8fbe887`, preserving
the `e1f80b7` respawn lineage and its subsequent shared item transfer, early native
portal preparation, pearl/player composition, natural-spawn query and container
close repairs. No provider or accepted gameplay configuration is changed.

## Exact destination readiness

The runtime portal authority previously retained only a visible holder and the
native public chunk identity after FULL preparation. Those identities can remain
unchanged while the provider's status slot, FULL state or holder lifetime changes.
`PortalResidentReadinessTest` exercises the actual runtime authority with existing
controlled C2ME fixtures. Six mutations failed before this repair: slot, state,
lifetime, publication retirement, provider runtime close and world close. The
owner-held preparation rejection is a separate control. These are deterministic
defensive-path witnesses, not claims that an installed provider entered those states.

The authority now captures the existing `ResidentChunkSourceBinding`, requires
that its chunk is the exact result of native FULL preparation, and retains its
publication/lifetime checks through destination admission. Native chunk identity,
active world, source incarnation, ownership route and closure checks remain.
No new provider receipt mechanism or synchronous-wait exemption is introduced.
Diagnostic descriptions are read-only and fail safely to UNAVAILABLE. The first
actually unfinished native destination future, if one occurs with diagnostics
enabled, records its phase and owner; this never changes the future or provider.

Pinned native order remains source `removeAfterChangingDimensions` before
replacement `teleportSetPosition`, `addDuringTeleport`, post-transition callback
and return. Destination tracking uses the newly admitted destination service;
source retirement and the retained original caller suffix use source authority.
Partial insertion failure retires only the failed exact incarnation and cannot
promise rollback of already committed native source removal. The distinct later
pearl hit uses the player owner's operation and existing dimension continuation.

## Installed verification facility

`Stage7RepairClientTest` uses the existing account-free Fabric Client GameTest
client over normal TCP to a separate dedicated server. Setup commands supply
items, position the actor and create native lit portals. Real movement, drop and
use packets cause the portal/item/pearl witnesses; commands never stand in for an
item or pearl portal transfer. Two normal client respawns follow observed native
death removal, and each is followed by a normal disconnect/reconnect. Restart
checks use the same saved world and player data. Position and inventory setup
wait for client convergence; post-respawn/restart movement must actually progress.

The separate observer JAR logs native tracking, removal and pearl-hit endpoints
and exposes a read-only snapshot command. It never calls a Phoenium repair helper.
It is only for disposable test servers. Build it explicitly:

```powershell
.\gradlew.bat -I scripts/stage7-repair-client.gradle stage7RepairHarness compileClientGameTestJava jar --console=plain
```

The client task takes `-PrepairAddress`, `-PrepairRconPort`, `-PrepairEvidence`,
`-PrepairSha256` and `-PrepairRestart`. The isolated orchestration script and exact
locked provider/config inputs are preserved in the campaign below. It selects
the existing `phoenium.gameplay.workers` profile at 1 or 4, leaves caps/A3 at their
accepted defaults, stops its own server at a first failure and preserves evidence.

## Evidence and acceptance boundary

Authoritative final records live outside mutable build output at
`C:\Users\Admin\Downloads\Project PHOENIUM\.audit\astra-max-stage7-20260922`:
`ASTRA_MAX_STAGE7_REPAIR_REPORT.md`, `FINAL_CANDIDATE_MANIFEST.json`,
`REGRESSION_ACCOUNTING.md`, `LUNA_FINAL_VALIDATION.md`, the native execution ledger,
archived XML, installed logs and immutable candidate. Their final artifact/test
ledger determines acceptance; this source note does not predict test results.

The original unfiltered entry baseline had 73 failures. Restoring three exact
local provider fixtures resolved 25; 48 historical prototype failures still
require explicit disposition. The existing broad filter must remain unchanged;
the new readiness tests are included in focused and scoped selections.

This campaign certifies only the supported exact ItemEntity and thrown-pearl
continuations to the extent recorded in final evidence. The preserved incidental
ZombifiedPiglin portal crash and representative unsupported mob continuation test
remain disclosed limitations. Basic chicken ticking is not mob portal acceptance.
No Bedrock human action, Legacy Via gameplay, two-client voice, exact Drowned /
Chicken pair, arbitrary modded entity or formal Stage 7 completion is inferred.
