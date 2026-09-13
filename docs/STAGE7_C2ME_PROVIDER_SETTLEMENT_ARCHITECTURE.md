# Locked C2ME native settlement decision — 2026-09-13

Written before provider source edits. Acceptance is pending.

## Recovery and exact source

Phoenium is at `31fea7d45b6f161fa3897d894cb016d89e1dcd79`, branch
`stage7/scalablelux-persisted-light-repair`. No staged changes. Existing dirty
diagnostics are preserved in place and inventoried with a binary tracked patch
under `run/stage8/entry-20260913/astra-c2me-provider-settlement/recovery-*`.
No project Java/Python process was running at recovery. No applicable AGENTS.md
was found in either repository or their ancestors.

Production remains 1,403,887 bytes, SHA-256
`8A1C0C4852150C8D36E9EDB8DDEFC9F9B6E9A3E82AE71123D798416DD39A21FC`, with both C2ME breaks.
Original C2ME SHA-256 is verified as
`B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`.
Source root: `C:\Users\Admin\Downloads\Project PHOENIUM\C2ME-locked-settlement`.
C2ME revision `e5f9ed26349b2f9504f73cac48aa09a490d137a3` describes as
`0.4.2+alpha.0-43-ge5f9ed26`; its FlowSched submodule is
`fae2126839607b68d4059e3933a492886fe8b8f5`. Both were initially clean.
FlowSched is shadowed into `c2me-base`; no Minecraft-facing module needs editing
for requester settlement. Original characterization rerun passed (exit 0),
reproducing the defect without changing its expectations.

## Ownership and terminal transition

`ItemHolder.futures[status.ordinal()]` owns the shared requested slot. DISK and
later FULL/status slots are separate CompletableFutures on the same holder.
Ticket demand creates slots from the UNLOADED sentinel. Slot replacement plus
holder identity defines the native generation; there is no independent numeric
generation counter. The running action is a pair of outer Cancellable and target
status. `advanceStatus0` binds that outer action to one inner action token and
one context through its closure. Dependency failure can occur before a context
exists, so requiring a non-null context would incorrectly strand that failure.

After retry policy returns MARK_BROKEN, the original Throwable is still the
onErrorResumeNext argument. This continuation runs while the action is busy,
outside the holder monitor; the per-holder critical executor serializes ticks,
and the busy reference prevents another action/unload. Validate the outer token,
target status, and originally captured target slot under the holder monitor.
Store the original Throwable on that holder and set FLAG_BROKEN atomically with
replacing unfulfilled requested slots with already-failed futures. Snapshot the
old futures and affected tickets there, then signal those old objects outside
the monitor. Newly created failed futures have no attached callbacks.

An upgrade on a holder with retained terminal failure must be rejected by
setStatus as well as the scheduler's existing FLAG_BROKEN check. The Rx action
has one cached terminal; a late source success cannot legitimately enter its
success continuation. Both properties need tests. CompletableFuture's terminal
CAS preserves a cancellation that already won and prevents duplicate failure
or unload from changing an old result. A cancelled action acknowledged through
CancellationException takes the existing cancellation branch before MARK_BROKEN;
token cancellation alone does not disguise a real provider error.

## Later requests, retirement, and lock safety

The same broken holder cannot upgrade again. Its subsequent demanded unavailable
slots must be born exceptionally completed with the retained original cause,
including after demand shrink or downgrade. Previously achieved prerequisites
remain successful until normally downgraded. Failure is holder-local; only real
map removal followed by construction of a new holder permits fresh successful
work. Do not clear failure on a ticket fluctuation or slot recreation.

Replacing the visible affected slots with terminal futures before signalling
old slots prevents concurrent unload from substituting Not loaded during the
post-monitor signalling gap. Old requesters retain the original Throwable.
Native demand accounting, dependency removal, busy release and map retirement
remain intact. Existing flushUnloadedStatus uses snapshot/post-monitor signalling,
but tickHolder0 calls it under an outer monitor: this is NOT a general proof of
lock safety. The new failure operation must never be called under that monitor;
assert this invariant and test callbacks with Thread.holdsLock(holder). Its
already-failed replacement slots make later flush completion a no-op.

## Cross-holder dependency propagation

FULL also waits on other holders via shared ItemTicket success callbacks in
getDependencyFuture0. Completing only local status futures would leave these
native Rx dependency waits busy forever. Extend ItemTicket with an optional
failure callback, preserving existing constructors and success-only callers.
Use the existing atomic callback removal as the single success/failure terminal.
Failure must signal the native dependency emitter on its holder critical
executor, after dependency registration has returned; normal error policy then
fails that dependent holder and releases its own dependencies. Never invoke a
success callback for a failed prerequisite. Cached dependency reuse must inspect
the retained holder failure before consuming success. Failure callbacks do not
remove another requester's ticket.

Smallest intended patch surface: ItemHolder (terminal generation/slots),
StatusAdvancingScheduler (native MARK_BROKEN and dependency error handling),
ItemTicket (optional dependency error terminal), plus independent provider tests.
No global scheduler, worker waits, synchronous disk replacement, or Phoenium-owned
requester completion. Red/green tests must include local and cross-holder
dependencies, later demand, replacement, cancellation ordering, late success,
unload and callback locks. A pinned artifact and installed W1/W4 acceptance are
separate later gates; this note does not clear either.

## Installed lifecycle re-check before the second provider patch surface

The first shape (77 added lines across three FlowSched classes) passed 11 focused
contracts and the existing native suite. W1/W4 installed task-loop failures then
proved original DISK/FULL requester failure and later same-cause access. They also
exposed a downstream native API assumption: `NewChunkHolderVanillaInterface`'s
nullable `getWorldChunk` (runtime name `getTickingChunk`) calls `getNow` on a failed
ticking future. It throws into the world tick and again during shutdown, leaving
other in-flight actions unretired. This is not a reason to weaken exceptional
requester futures or skip the failure matrix.

The getter is an availability peek, separate from `load` and the future-returning
APIs, which explicitly propagate original failures. Its correct failure result
is null: a failed generation is not a ticking chunk. Snapshot the future's state;
return its value only after SUCCESS, using terminal state immutability to avoid a
check/getNow race. Preserve exceptional future-returning requests unchanged.
This adds one method in the native rewrites module. It does not change tickets,
success prerequisites, original causes, or error reporting. This distinction is
required by the installed failure evidence, not a blanket swallow of errors.
