# Stage 5 execution and acceptance

Status: **IN PROGRESS; no Stage 5 PASS claimed.** Branch `stage5/bounded-parallelism`.
Stage 6 and compatibility work remain out of scope.

## Immutable starting point

Verified clean starting HEAD and peeled Stage 4 tag:
`1a82b701fce53395481f251347ca9a4deffc12d7`. The annotated tag object is
`8510dca7094c03caee5e4844434883fe5d249090`.
`run/stage4/final-artifacts/phoenium-0.2.0-stage2.jar` hashes to
`43AAC30184E5C77784D7A56590AA23A87DC86438684EB2B2F2577A197F4DD81E`.
This is the one-worker Stage 4 reference. Stage 3 is a distinct older reference.
Neither freeze, artifact, evidence tree nor saved fixture is to be rewritten.

Read the complete Stage 4 closure, current roadmap, testing/benchmark requirements,
threading invariants and Stage 4 service contract. The user authorizes Stage 5
parallel development/acceptance; historical one-worker restrictions are superseded
only for this work. Account-free TCP clients, disposable worlds and all dependency
pins remain mandatory.

## Execution policy

The selected lead configuration is preserved. Astra owns production engineering;
the optional substantial implementation worker defaults to explicit Astra Medium,
in isolated edits. At most one subagent may be active, with no recursive delegation.
Explicit Luna at the lowest adequate effort handles bounded read-only evidence or
existing commands; it may not repair production/harness/comparators or weaken tests.
Optional Sol High review replaces that worker. The lead owns integrated builds and
installed campaigns. Tool-selected routing, not worker names, records configuration.
If supported model selection is unavailable, work single-agent. No paid API use,
credits, account changes, external publishing or automatic extra agents.

Initial account telemetry: main Codex window 31% used / 69% remaining; reserve
bucket 7% used. Per-worker token attribution is unavailable. One helper was
explicitly selected with `gpt-5.6-luna`, `low`, for the unchanged baseline only.
It completed once: 818 tests / 87 suites, zero failures/errors/skips, Java
Temurin 25.0.4.1, 18.793 seconds. Log:
`run/stage5/baseline-20260908/test.log`. No live campaign has run yet.

## Comparison contract (before candidate results)

Use frozen Stage 4 plus the candidate's one-worker implementation as controls.
Preserve the Stage 4 comparator's exact ordered scheduled-tick, block-event,
tracking/recipient/payload, callback/result and saved-delay channels and all negative
controls. Keep original MCA bytes and raw traces. Do not sort these channels or
normalize saved delays, priority, explicit sub-order/ties or duplicate effects.

New independently admitted regional callback cohorts may interleave across distinct
owners. Their original order within an owner, exact callback identity/count, input
clock, outputs and before/after phase relationships remain strict. Shared or
interacting callbacks create ordered boundaries. Worker/lease identity and timing
are evidence, not semantic equality fields. One seed alone is not deterministic
input. Candidate/reference artifact swaps must fail before launch/restart.

## Implementation direction and pending gates

Audit found that the scheduler is serial and that native chunk/entity phases and
the shared finishing-service turn retain serial mutable state. Pooling arbitrary
callbacks would be unsafe. Introduce bounded, explicitly admitted regional cohorts
with current claims and publication/lifecycle fencing; keep native shared phases
ordered. Original callbacks must run once, with no fallback after selection fails.
Audit each admitted callback's native shared accesses before enabling its cohort.

Current implementation: 1..8 fixed gameplay workers (default 1), at most one finite
batch of one task per worker, 1..256 original callbacks per regional task (default
64), and optional 4096-entry original-body interval history. One-worker execution
uses the same selection/body implementation inline. Original furnace cooking
between completion/fuel boundaries and hopper cooldown/disabled branches are the
first locally admitted bodies. All other original callbacks still execute in their
ordered phases; no supported demand is refused or replayed. Installed verification
of these branches is pending. Native source was read from the pinned local archive;
no external implementation was imported.

Delivery selection is distinct from a gameplay lease: topology fences both, but
terminal packet rejection acknowledges without mutation authority. Accepted batch
cleanup joins every original body. Metadata ingress is fenced against stop; stale
physical inboxes remain deliverable; generic service submission cannot mint worker
eligibility. A bounded explicit `gpt-5.6-sol`/`high` review identified these races;
the lead repaired them. Both helpers are finished. The 829-test complete suite
passed (88 suites); focused tests include actual two-thread model overlap,
failure with a held peer claim, merge admission, stop joining, capacity, conflicting
owners, generic eligibility rejection and topology contention. These model probes
do not substitute for installed original gameplay evidence.

Next: run `scripts/run_stage5_gameplay.py` against the built installed candidate in
a new `run/stage5/` directory, repair the first captured failure, then continue the
serial/reference and installed client/persistence/adverse/benchmark gates below.

Required remaining evidence: deterministic races and progress/capacity; original
gameplay body/claim intervals with natural overlap at 2 and another bounded worker
count; complete final suite; installed 5/5 and 8/8 TCP client and native FULL branches;
strict frozen Stage 4/candidate serial comparisons; merge/split/stale/replacement;
save/MCA/restart/native scheduled deadlines; healthy/adverse shutdown with legitimate
claims and writes retained to acknowledgement; server-only final packaging; identical
artifact identity throughout final acceptance; repeatable distributed/concentrated
benchmarks with completed work, latency, CPU/memory/GC and explicit limitations.
