# Stage 3 FULL conversion review and load regression repair

Date: 2026-09-04. Starting checkout: `62b52b4d1f9d0a0f8b3dcd0f2f198f43b937324f`.

**The requested FULL checkpoint is not implemented or accepted.** The existing
PARSED_APPLY and separate STATUS_PUBLISH(EMPTY) binding remains in place. This
change adds a reproducible witness for a concrete FULL ownership question,
repairs a POI bookkeeping race encountered in real-server regression, and repairs
the automated fixture. FULL_CONVERT, STATUS_PUBLISH(FULL), and later
ticking/readiness/send/demotion authority remain unbound. Stage 4 is not started.

## Focused source finding requiring a contract decision

The pinned Minecraft 26.2 synchronous FULL supplier performs construction or
reuse, holder replacement, full-status supplier installation, post-load work,
loaded-flag mutation, block-entity and tick-container registration, and unsaved
listener installation. The accepted architecture treats that body as one stage.
The separately registered holder completion callback is the publication edge.

A legal post-load branch reaches a server-wide mutation and network dispatch:

```
ChunkStatusTasks.lambda$full$0
  LevelChunk.runPostLoad
    ChunkStatusTasks.lambda$full$1 / postLoadProtoChunk
      EntityType.loadEntitiesRecursive (including passenger trees)
        Entity.load -> LivingEntity.readAdditionalSaveData
          LivingEntity.lambda$readAdditionalSaveData$0 (saved Team)
            ServerScoreboard.addPlayerToTeam
              Scoreboard.addPlayerToTeam / previous-team removal
              PlayerList.broadcastAll
                ServerGamePacketListenerImpl.send (players in every dimension)
```

This is synchronous during the original post-load body. A restored UUID can
already belong to another team: the old membership removal and REMOVE broadcasts
precede the new membership addition and ADD broadcasts. Rejecting at packet send
is too late to provide atomic preflight; the scoreboard has already changed.

`FullPostLoadScoreboardContractTest` contains three passing witnesses:

1. The original restoration callback, original ServerScoreboard mutation and
   original PlayerList broadcast loop synchronously reach Overworld and Nether
   recipients from an admitted FULL-shaped pending stage.
2. Previous-team removal and saved-team addition preserve REMOVE-before-ADD
   order across both recipients.
3. An exact exception injected at the final send leaves the earlier membership
   mutation applied, disproving a late-rejection rollback assumption.

These are semantic witnesses, **not FULL adapter acceptance tests**. Constructorless
test environments supply vanilla getters and controlled network sinks. They do
not run a complete LevelChunk conversion or a real network team broadcast.
The exact call path above is supported separately by pinned bytecode.

The region lease is world-bound; extending a chunk's regional footprint cannot
grant authority over a server scoreboard and connections in other dimensions.
Architecture section 9 declares finite metadata services, and section 10 forbids
using a chunk lease to authorize mutable foreign-player access. Section 17 says:

> changing identity, service ownership, future settlement, stage atomicity or lease lifetime requires architecture review

### Proposed review direction (not approved or implemented)

Declare a narrowly scoped server-wide service for saved-entity team restoration
and its ordered outbound team notifications. Specify that it owns scoreboard
membership and this dispatch operation, independently of regional chunk authority.
The serial control lane must preserve the original return/failure behavior and
REMOVE/ADD order. Any service reservation or prerequisite must be ready before
FULL entry; no region may wait for a service or another region while holding its
lease. The service must not become a general arbitrary-world callback executor.

The implementation must demonstrate the complete conversion branch, passenger
restoration, failure ordering, current service evidence, and actual outbound
notification behavior before acceptance. Merely adding a lock, skipping Team,
moving dispatch after FULL returns, or granting the center lease global authority
does not resolve the question. Approval of this direction would not itself prove
that the entire FULL footprint is closed.

A question requesting this specific service-ownership decision was sent during
the task and remained unanswered when this report was prepared. No such change
has been silently added to the architecture.

## Implemented regression repairs

### POI pending-load metadata

A fresh server reproduced `ArrayIndexOutOfBoundsException: Index -1 out of bounds
for length 257` during the adapter's pending-load removal while vanilla worker
prefetch could insert/rehash the same fastutil map. The baseline adapter accessed
the shared pending map and loaded set without vanilla's `SectionStorage.loadLock`.

`PoiLoadBookkeeping` now uses that existing monitor for bounded source-identity
checks, pending removal and loaded-set acknowledgement. Original POI installation
still executes outside the monitor under the stage lease. No callback or future
completion is invoked by the bridge. Three tests require the exact monitor for
every map/set access, prohibit premature acknowledgement and preserve replacement
source identity. The broad-lock source test has one named exception for this
bridge, with the restriction retained for every other production source file.

### Finite external-server fixture

The old explicit A/B fixture covered only a small region near the origin, while
actual vanilla spawn selection and client generation requested chunks outside
it. This caused private cancellations and startup waits. The harness now opts
into an explicit support map around the actual sampler-selected spawn candidate
and the test corridor. The support radius is 22 chunks: spawn search 5, client
view 5 plus its border 1, generation dependencies 11. A deterministic test checks
the pinned FULL-to-EMPTY dependency radius, the declared corners, the A/B boundary
and absence of a world-default fallback. Ordinary fixture callers retain their
original small geometry.

The real-client target test no longer infers index insertion from the aggregate
entity count rising; unrelated entities can unload/despawn concurrently. It checks
the tagged target position and requires the subsequent real interaction's index
hit and complete admitted execution path. The external test server uses 2 GiB
maximum heap and emits the existing chunk-binding trace.

An intermediate client run with insufficient support retained 68 custody entries
after 34 private cancellations. Widening the declared fixture avoids those missing
routes in the acceptance runs; this is **not** a general repair or acceptance of
missing-route cancellation/retention behavior. That baseline terminal path remains
a separately identified issue.

## Validation and artifact identity

The starting clean build passed **196 tests** with zero failures, errors or skips.
The updated deterministic suite has **203 tests**: the original 196, three FULL
semantic witnesses, three POI bookkeeping tests and one fixture support test.

Final command: `gradlew.bat clean build runStage3ChunkTrace runStage3ClientGameTest --no-daemon`.
It completed successfully. The final XML aggregate is **203 passed, 0 failures,
0 errors, 0 skipped**. These are deterministic tests; the five real-server
lifecycles below are additional evidence, not added to the test count.

| Final-artifact lifecycle | Seed | Exact EMPTY captures / parsed applications / EMPTY publications |
| --- | --- | --- |
| Fresh generation 1 | -7666213150661820950 | 1,058 / 1,058 / 1,058 |
| Fresh generation 2 | -2096787328781571592 | 1,058 / 1,058 / 1,058 |
| Persisted reload of fresh 1 | -7666213150661820950 | 9 / 9 / 9 |
| Real client, lifecycle 1 | 5166915986533674833 | 2,046 / 2,046 / 2,046 |
| Real client, lifecycle 2 | 3203045256913256104 | 1,862 / 1,862 / 1,862 |

All five servers stopped normally. Every shutdown reported zero recovery
applications, private unloads, stale reroutes, duplicate applications, barriers,
and all reported chunk/service retention counters. The total is 6,033 parsed
applications and separately admitted EMPTY publications, not FULL admissions.
All nine persisted applications were marked `reload=true` and registered their
light input batches. This run exercises normal success and shutdown; it does not
newly establish real supersession or exceptional-load correctness.

The client JSON reports real TCP connection and usable world, same-owner
movement and indexed interaction, cross-owner movement and interaction rejection,
target removal, disconnect projection cleanup, second-lifecycle reinitialization,
and normal termination. All corresponding flags are true, with an empty failure
field. It is a usability/packet regression, not proof of FULL/ticking/send authority.
Some older descriptive fields in the existing chunk trace JSON remain observer
metadata; the authoritative EMPTY measurements above come from binding STOPPED
counters in the saved production server log.

Production artifact: `build/libs/phoenium-0.2.0-stage2.jar`.
SHA-256: `CB2BCC215BEA6B318E3B4D0B057A4073051663DEFD14A92FE5C093DA42DC0D69`.
The actual JAR installed in the external server's `mods/` has the same hash.
The starting commit remains HEAD; these source/test/document changes are uncommitted.

Pinned merged-deobfuscated Minecraft JAR SHA-256:
`A9B82EED0C7F32D4EF1A057165D5EEFEA5D5E717138F4C65A13A7035B72C96B4`.

Final evidence is copied to `run/full-conversion-audit/final/`: `server-process.log`
contains the five STOPPED summaries, `STAGE3-CHUNK-RUNTIME.json` and `STAGE3-CLIENT.json`
contain the two harness outcomes, per-run logs and world seeds accompany them,
`test-results/` contains all JUnit XML, and `evidence-hashes.json` identifies the
copied top-level evidence files. Earlier failed and intermediate passing runs are
kept separately and are not counted in the final-artifact totals.

Evidence is preserved under ignored `run/full-conversion-audit/`, including pinned
bytecode extracts, initial failed-run logs and thread dumps, and the successful
regression JSON/logs. The harness uses the offline test identity `PhoeniumTest0`,
localhost, a separate production server, one simulation worker and parallel
gameplay OFF. No personal account, launcher, desktop automation or client-side
production mod is introduced.

## Resume boundary

After the service-ownership decision, extend the existing registrar/custody graph:
retain actual holder lifecycle identity beyond EMPTY settlement; register FULL
work using the actual winning vanilla reservation; preflight the complete
synchronous footprint; claim the original conversion exactly once; capture the
exact returned R and adjacent holder handler; and admit FULL publication separately.
Fresh generation wraps the task future in `ChunkStep.apply` while reload can
return it directly, so do not assume the static FULL task's future is always the
holder's returned R. The Imposter branch and actual replacement hook must be
bound from the pinned code rather than inferred from prose.

These missing adapters and identity extensions are implementation work, not
additional architecture blockers. Tests still needed include actual FULL result
identity, stale-owner publication, wrong-lifecycle and duplicate publication,
supersession and terminal settlement, followed by fresh/reload/client evidence
with authoritative FULL counters. No existing EMPTY counters establish those
properties.
