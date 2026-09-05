# Approved FULL amendments — 2026-09-05

The user explicitly approved the bounded D2-P and D3-X contracts in
[the D1–D3 decision](STAGE3_FULL_D1_D3_DECISIONS.md), §§3–5, in the request
continuing commit `f09fe2ffb737ff9019c01cd01b7198b609fe9d38`. This is an
implementation authorization, not a request to decide D2/D3 again.

D2 permits the audited legacy-owner name/cache lookup, required profile
resolution, cache mutation, serialization and completed file-write attempt
before service reservations and regional admission. Identity/expiry can be
observed earlier; profile/cache/MRU/file-attempt effects can survive stale work
or an unreached Owner callback; committed preparation is not rolled back on
staleness. These are approved observable differences, not exact vanilla
equivalence. Identity resolution, attempted persistence and successful
persistence remain distinct. Preserve caught physical IOException behavior,
original runtime propagation and committed memory prefixes. Pending work and
retained results must be bounded; no regional or C/S/W reservation spans I/O.

D3 permits only the audited direct-self query to use the actual in-progress
LevelChunk during its exact active conversion, bound to world, chunk,
lifecycle, work, stage, claim and holder. Revoke on every synchronous exit.
This explicitly permits progress and subsequent original effects where the
getter otherwise selects that supplier's unfinished FULL slot. Preserve
ordinary ready/null/error branches and original query observation points.
No public early FULL, future/cache publication, synthetic state, new ready
flag, neighboring object access or general getter replacement is approved.
Dynamic non-air shapes, FULL/true ticket prefixes and non-self cycles still
require concrete closure within these limits; this approval does not extend
the private exception to another conversion.

D1-F, the scoreboard/session contract, M1/M2, separate conversion/publication,
failure-prefix rules and the cleanup checkpoint remain unchanged. The precise
combined order is readiness/context/identity plus settled D2 preparation;
callback-dependency/cycle check; C; short P if needed; S; named W in fixed
order; full revalidation; one regional claim; original supplier once; record
outcomes/revoke private access; release authority/turns; separately admit FULL
publication; terminal acknowledgement and cleanup. No lock spans callbacks,
sends, submissions, completion or regional work.

The session targets the integrated FULL checkpoint and its acceptance, with
coherent buildable checkpoints and FULL disabled if a demonstrated unresolved
semantic conflict prevents safe entry. It does not authorize Stage 4, general
ticking/readiness/send/demotion implementation, multiple simulation workers,
parallel gameplay, compatibility work, accounts or production-world changes.
Stage 3 remains PARTIAL pending the whole-stage audit; no PASS tag is authorized.

## Scope reconciliation

The movement-only prohibitions and zero-chunk-counter interpretation in
[the testing policy](AGENT_MINECRAFT_TESTING.md) and older HANDOFF sections are
historical scope, superseded for this request by the user's explicit FULL
implementation and disposable generation/reload/client authorization. Its
account-free command-line workflow, offline identity, one worker, isolated
client source set and evidence preservation rules still apply.

Architecture §§10/15/16 assigns ticking/readiness/send/demotion binding and
post-processing evidence to the complete Stage 3 chunk contract. The roadmap
assigns general regional ticks/tracking/save services to Stage 4. This session
closes only the supporting FULL callbacks, conversion and separate publication;
it does not erase the later Stage 3 chunk gates or expand general Stage 4 work.
See [the gate ledger](STAGE3_FULL_APPROVED_IMPLEMENTATION.md).
