# Non-player portal repair implementation note

Baseline: e1f80b7c71a950b27e0e540f35e371395d299bac, clean at inspection.
The preserved Sugar Cane crash is destination tracking under the Overworld owner;
item UUID is UNAVAILABLE. Both dimensions use the string stage2-live-world but
their complete RegionIds differ.

Pinned local 26.2 source and javap of the actual Loom compile JAR agree:
Entity.teleport stops riding, teleportCrossDimension ejects/transfers passengers,
creates the replacement, restoreFrom serializes/loads native state, then retires
the source, applies PositionMoveRotation, inserts the destination, mounts returned
passengers, resets empty time, invokes the post-transition callback, teleports
spectators and returns the replacement. ItemEntity.teleport merges that returned
item, then returns it. handlePortal ignores the value; baseTick and ItemEntity.tick
still finish on the retired source. Item age/delay increments belong to that source
suffix, after the replacement's native serialized snapshot. Do not copy those
increments into the replacement. The source cooldown/portal processor and baseTick
prefix are already changed before the proposed cut.

Retain the item at handlePortal's teleport invocation, after its actual transition
and admission predicates have returned. Unwind baseTick and ItemEntity.tick using
typed suffixes, and coordinate only after the enclosing regional callback returns.
Use the native teleport call itself from the owner-free coordinator, with narrow
operation wrappers for its source and destination effects. This preserves overrides,
the real native result, native state copying and native insertion order. Reuse
CrossDimensionReplacementBoundary and the identity publication at native tracking
start, rather than constructing a registry entry or replaying the tick/portal lookup.
The existing pearl manager retains portal preparation and its one hit continuation;
its replacement operation will share this native boundary. Later player teleport
from the hit remains a separate manager and separate validation obligation.

The demonstrated item cut has a completed native portal destination result, not a
pending portal/POI wait. Readiness and route observations must be revalidated before
admission; no new provider mechanism or artificial readiness will be introduced.
Limit admission to the demonstrated ordinary item and retained pearl without a
passenger graph. Same-dimension native calls remain untouched. Journal exact native
storage/tracker publication prefixes for cleanup; never blindly remove a same-UUID
replacement, replay committed effects, or mark success before insertion and suffixes.

Verification starts with a red native item destination callback through the real
dispatcher. Controlled world endpoints will be disclosed. Preserve the existing
broader filter (nine excluded historical/provider classes) and all earlier evidence.

## Implemented boundary and failure contract

ItemPortalEntityMixin cuts immediately before handlePortal invokes teleport. The
already completed cooldown, native transition and admission predicates are retained.
Entity.baseTick and ItemEntity.tick then return at their typed suffix seams; the
enclosing ServerLevel.tickNonPassenger has only its profiler pop and empty passenger
loop left. ItemPortalTransitionManager runs after the regional phase has returned.
It neither clears OwnershipContext nor drains another owner under the source lease.

NativePortalTransfer invokes the original virtual teleport once. Narrow operation
wrappers admit source stop-riding/ejection, private replacement restore and native
source retirement under the source route. Replacement creation, position, insertion,
reset, post-transition callback and ItemEntity's actual merge run in separately
validated destination operations. Native spectators retain their existing owner-free
coordination point; player travel continues through its separate current machinery.
The returned replacement is the actual native value; there is no pending placeholder.
The original serialized item state and native position/motion reset execute unchanged.
The retained source tick suffix preserves the native call/write order, including
pickup-delay/age changes on the retired source only. The shared baseTick suffix now
uses the native setRemainingFireTicks setter rather than directly decrementing its field.

PearlPortalTransitionManager keeps its one preparation and RetainedPearlTickContinuation.
Its previous copied replacement body is replaced by the same NativePortalTransfer
call. The actual ThrownEnderpearl.teleport return suffix adds its ticket once. Its
existing retained collision suffix receives the original hit result once and retains
the native isAlive guard: a dimension-retired source cannot hit again. A later hit
by the destination pearl and the resulting player teleport remain distinct events.

The existing addressability callback establishes the replacement's registry identity
before tracking, and records an exact receipt in the transfer. No ownership entry is
fabricated by the transfer. The same UUID has a new native and registry incarnation.
Source/index retirement uses the exact expected publication, world and runtime ID.
Current source incarnation/epoch/route, both exact world objects, the transition,
token and replacement publication are checked at admission and before effects.
Destination preparation consumes the actual public native FULL chunk, owner-free;
its receipt binds both the visible holder object and LevelChunk object. It does not
repeat portal search or manufacture provider futures. The dispatcher re-resolves
and validates each operation's complete world/region route.

The storage journal records successful UUID claim and native callback installation.
Before callback installation it removes only the exact unpublished section/UUID
prefix. After installation it first proves both visible aliases are absent or the
same replacement, retains any partial native tracker insertion through the existing
tracking projection, then invokes native removal under destination authority. It
retires only the recorded ownership publication. A conflicting newer object is
left intact and cleanup rejection is secondary to the original failure. Failed work
is terminal and cannot replay a native tick or transfer. If failure follows committed
source retirement, the source is not resurrected: the failed transfer is explicitly
accounted as failed/cleaned, never successful or silently retried. Cleanup failure
stops the operation with its original exception and secondary suppressed evidence.
Successful native merging can retire the returned item normally; quantity follows
the original ItemEntity merge implementation rather than a second registration.

Diagnostics are enabled only by phoenium.test.diagnostics. Each generation has a
finite phase sequence with exact identities/routes/owners and UNAVAILABLE for unknown
observations. CAPTURED/ADDRESSABLE/TRACKING_PENDING are not success. SETTLED is logged
only after native return and the required retained source suffix complete. Stale
retirement, external removal, world close, shutdown and failure use distinct reasons.

## Deterministic accounting

Evidence: workspace .audit/non-player-portal-repair-20260921/verification.

- Red: native ItemEntity.teleport/Entity.teleportCrossDimension, real dispatcher,
  controlled destination callback reproduced the exact Overworld-versus-Nether
  foreign-owner assertion before implementation.
- Focused: 42/42 across five named classes (focused-classes.txt).
- Related: 224/224 across the exact 28 classes in targeted-classes.txt, including
  player-only dimension contracts, respawn/session, tracking receipts, relocation,
  dispatcher, A2/pearl and projectile ledgers, and current readiness regressions.
- The documented broader filter was run once, unchanged: 1,207 tests, 1 failure,
  0 errors/skips. The failure was CoreAcceptanceTest's source-policy lock guard
  detecting the new synchronized metadata map. The implementation now uses the
  existing pearl manager's concurrent identity-map pattern; the guard was unchanged.
- Final correction/affected regression selection: 276/276, 32 suites, 0 failures,
  errors or skips. This includes every focused and related class and CoreAcceptanceTest.
  The broader suite was not rerun, and its initial failure is retained rather than
  represented as a fully green broader run.
- Exact unchanged exclusions: FullNonselfInputDecisionTest and the eight existing
  C2meDiskReadOperationTest, C2meDiskReadRxTest, C2meFullProviderBinaryTest,
  C2meGenerationProviderBinaryTest, C2meNativeAvailabilityTest,
  C2meNativeFailureSettlementTest, C2meReadProviderBinaryTest, C2meSendReadyInputsTest.
  No transfer, respawn, tracking or core guard is excluded. No provider audit/refresh
  or unfiltered historical audit was run. The baseline's 1,264/73 unfiltered failure
  record remains distinct; it was never a fully green baseline.

The native item tests exercise real factory construction, serialization/restore,
teleport methods, item merge invocation, real dispatcher/leases, and real identity
registry/index retirement. World storage/tracker endpoints and the final physics
suffix endpoints are controlled. The copied production item suffix is independently
pinned against the actual mapped native suffix's call/write sequence. Pearl coverage
executes native replacement and the existing single-consumption continuation with
controlled ticket/collision endpoints. It is not player-hit teleport acceptance.
No artificial provider pending state was introduced; naturally delayed terrain and
real Bedrock item/pearl gameplay remain for Luna. Only ordinary items and the existing
retained pearl path without a passenger graph are in the supported scope here.

Final candidate identity, immutable hashes, exact packaging audit and the FINAL
Fabric bootstrap receipt are recorded outside the commit in the audit handoff.
