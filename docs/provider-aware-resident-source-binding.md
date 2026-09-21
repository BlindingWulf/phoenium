# Provider-aware resident source binding

The Vault's original first-tick predicate required both a live vanilla
`FullRegistrationRuntime` and the inherited `futures[FULL]` slot. Neither is
the resident publication mechanism of the accepted C2ME provider. Ticker
registration alone did not establish either predicate.

`ResidentChunkSourceBinding` is a sealed read-only receipt with separate
Vanilla and C2me proofs. It does not publish a chunk, create a future, establish
ownership, or change provider settlement.

* Vanilla retains the exact visible holder, attached holder lifetime, exact
  inherited FULL slot and its LevelChunk result, and exact open vanilla runtime.
  External preparations also retain a pending vanilla slot's identity.
* C2ME retains the accepted FULL observation after the operation graph retires.
  Its proof references the original provider system/session, backing ItemHolder,
  SERVER_ACCESSIBLE singleton and status-slot identity, exact ChunkState and
  FULL contents, exact published holder, and LevelChunk. The provider slot must
  have completed successfully. Revalidation uses the existing C2ME reflection
  adapter and currentChunkHolders publication check. No vanilla FULL future is
  synthesized or required.
* Both proofs bind the ServerLevel, chunk key, exact visible holder and attached
  Phoenium lifetime (including session/world-instance identity), current lifecycle,
  and exact LevelChunk. The ticker receipt retains topology generation and exact
  route/owner; the continuation retains its original topology and owner checks.
  The accepted provider surface exposes identity epochs through the status slot
  and ChunkState; no numerical publication epoch is invented.

C2ME's resident observation is stored in the existing per-world holder lifetime
service. The FULL graph creates its pending observation before entering native
conversion, acknowledges the existing provider publication, and leaves only the
resident observation behind. Its runtime supplier references the runtime itself,
not the retired graph. Holder retirement, lifecycle release, and shutdown release
the provider context, state, and runtime reference. A narrow observer on C2ME's
actual currentChunkHolders.remove returns the exact removed holder and retires
only its resident source receipt, even if there is no active gameplay consumer.
The provider's backing holder lifetime and settlement remain untouched. Revalidation rejects provider
holder/slot/state replacement. The FULL graph and provider settlement protocol
are unchanged.

## Ticker admission

`Level.addBlockEntityTicker` remains the registration event. The receipt captures
the original ticker, BlockEntity and LevelChunk. During conversion,
`FullFinishingServices.registeringHolder` exposes only the exact holder already
present in the active finishing reservation, so registration can retain its
lifetime before the provider publishes it visibly.

The states are REGISTERED_WAITING_SOURCE, SOURCE_BOUND_READY, ACTIVE and RETIRED.
The original level list retains waiting registrations, in order. No original tick
is scheduled until the exact resident source is ready. The binding is checked
again at execution and local-batch preflight. A pending registration creates no
Vault continuation and has no prefix or side effects to replay.

Replacement of the ticker's bound object, chunk-map BlockEntity, holder,
lifetime, provider holder/slot/state, LevelChunk, topology or route rejects the
old binding. Registration also pins the pending provider publication epoch. Retirement is
terminal, including late provider completion or a new publication on the same holder.
Lithium's known sleeping sentinel keeps its registration without executing;
the bound entity and resident source still have to remain current.

## Vault and external reads

The continuation's open supplier now requires its actual provider runtime as well
as the dispatcher. Its source readiness is the resident receipt. The existing
neighbor program counter, repeated suspension/resume, suffix, ordering, and
side-effect ledger are unchanged.

Each external query obtains its own exact queried-chunk receipt. An already
resident input needs only revalidation; an unavailable input is requested through
the existing native owner-free FULL selector. Its selected result must equal the
provider's published LevelChunk, and the receipt is checked before consumption.
The synchronous read guard is unchanged.

The bounded first-tick diagnostic is retained, with provider/runtime, resident
receipt, holder lifetime and provider identity checks. It does not publish or
repair provider evidence.

## Validation

Deterministic coverage includes native four-argument ServerLevel.setBlock,
LevelChunk.setBlockState, native Vault creation and ticker registration, and the
first original Vault tick reaching its cross-chunk neighbor suspension with no
inherited vanilla FULL slot. It also covers pending activation, replacement,
late completion, shutdown, queried C2ME receipt validation, and the unchanged
two-level Vault continuation tests.

Run evidence and exact artifact identities are preserved separately under
`run/resident-source-binding-20260921`. The installed witness is the existing
unchanged staged-native witness JAR from the supplied audit project, in a fresh
localhost Java-only server. Geyser/Floodgate account bridges are not part of that
Java-only validation; accepted chunk-provider JAR bytes remain unchanged.
Passenger implementation and its pending live acceptance gate are outside this
change.
