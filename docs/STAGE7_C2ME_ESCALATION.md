# Stage 7 C2ME escalation — resolved

**C2ME_UPSTREAM_SETTLEMENT RESOLVED — C2ME_LIFETIME ACCEPTED — RETURN TO LUNA**

Final acceptance: 2026-09-13. The native settlement patch and explicitly approved
Phoenium DISK lifecycle and SEND_READY dependency repairs passed the complete
literal W1/W4 matrix, including the full selected overlapping Stage 7 stack.
All 12 actual-production first/restart pairs pass, with all 24 server processes
exited. Production C2ME exclusions were removed only after candidate acceptance;
the final matrix uses the actual production JAR directly.

* Phoenium implementation commit: `916904794590433ccac1777c24ffa023b7af4338`.
* Branch: `stage7/scalablelux-persisted-light-repair`.
* Production `build/libs/phoenium-0.2.0-stage2.jar`: **1,433,247 bytes**,
  SHA-256 `CFDF13479E1073E1AB270C1FD447CF1A991A6DFABF3D4F5924D74560F7ADAAC6`.
* Required patched C2ME: **settlement2**, SHA-256 `8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53`.
* Full Phoenium regression: **953 tests / 108 XML suites / 0 failures / 0 errors /
  0 skipped**. Native full suite: **19 tests / 4 suites**, all pass; all **11**
  settlement contract tests also pass against the packaged patched provider.

The [final settlement report](STAGE7_C2ME_SETTLEMENT_REPORT.md) records the exact
composition, per-run positive and terminal counts, regression/build commands,
source provenance, process audit and hypothesis history. The
[provider patch manifest](STAGE7_C2ME_PROVIDER_PATCH_MANIFEST.md) pins native
commits, build inputs and output bytes. This supersedes the original blocked
disposition and old continuation instructions preserved below. The unchanged
characterization test still demonstrates the defect in the original C2ME JAR;
its pass does not accept that original JAR. The production binding requires the
distinct settlement2 base version. No remote push was made.

Current WORK CARD: **PANDA_A2_LIVE**. No Panda work was performed in this session.
Formal combined-stack Stage 8 acceptance has not started. Continue from
[the active work card](STAGE8_ENTRY_CURRENT_WORK.md).

## Historical record — superseded original-provider investigation

Everything below is preserved historical evidence, including its then-current
BLOCKED status, old production hash, retained exclusions and commands. Those
instructions do not override the accepted disposition above.

# Stage 7 C2ME lifetime escalation packet

Updated: 2026-09-13.

**C2ME_LIFETIME BLOCKED — EXACT ARCHITECTURE BLOCKER.** Astra completed the bounded architecture investigation; this is not a lifetime acceptance result. Both production C2ME exclusions remain.

## Astra disposition and required provider change

The authoritative result is [STAGE7_C2ME_LIFETIME_DECISION.md](STAGE7_C2ME_LIFETIME_DECISION.md). The original escalation evidence below is preserved as historical input; its assumed Rx-disposal solution and unanswered review questions are superseded by that decision.

The exact locked native scheduler has no failure settlement for requested status futures on a MARK_BROKEN upgrade. The original failure reaches its handler; action and busy state settle; DISK and dependent FULL futures remain pending. A later native request keeps the same pending slot. Removing all demand retires the holder but completes the old futures with generic UNLOADED_EXCEPTION, losing the original failure. `C2meNativeFailureSettlementTest` executes this behavior using native scheduler/holder/token/Rx classes without the Phoenium production bridge. Exact installed W1 and W4 failure trials both reached one FAILED operation terminal and watchdog exit 1, with no final restart binding receipt.

The provider must own an atomic failed-action/status-generation transition that completes affected requested promises with the original cause outside holder locks, defines terminal behavior for later requests on a broken holder, and preserves native ticket/dependency/unload/re-request semantics. Phoenium's custody and dispatcher can prevent its own late effects; they cannot legitimately settle shared provider requesters or remove another requester's tickets. This is an unsupported provider semantic, not an unresolved first patch.

The stable disk-operation identity is the native action token + exact action context + backing holder + original DISK slot, bound to Phoenium lineage. Token cancellation does not interrupt the private computation. Native acknowledgement requires both a cancelled token and CancellationException in the error channel. A bounded v3 adapter supplied that handshake at W1/W4: one matching successful token cancellation and one CANCELLED terminal, 119 APPLIED, exit 0 and zero final retained/safety counters. The original `c2meControls` field counts generation graphs, not disk-operation terminals. Those bounded cancellation results do not establish the whole lifetime contract.

All experimental production changes were archived and withdrawn. Full replacement/unload, stop and co-install acceptance is incomplete; v3 W4 persistence missed its required marker and remains unresolved. Production source is unchanged from recovery, and the rebuilt 1,403,887-byte production JAR again has SHA-256 `8A1C0C4852150C8D36E9EDB8DDEFC9F9B6E9A3E82AE71123D798416DD39A21FC`, with both breaks retained and no test/experimental classes. Final regression: **930 tests / 104 suites / 0 failures / 0 errors / 0 skipped**, exit 0; build exit 0; diff check passes. No project server remains running.

New evidence root: `run/stage8/entry-20260913/astra-c2me-lifecycle/`, including exact bytecode, native-failure-final.xml, candidate/source manifests, cancel-v3-w1/, cancel-v3-w4/, failure-v3-w1/, failure-v3-w4/, final-full-suite.log and final-production-audit.json. The decision records all locked hashes and continuation instructions. HEAD remains `31fea7d45b6f161fa3897d894cb016d89e1dcd79`; Luna's dirty diagnostics are preserved; no completion commit or push was made. Keep C2ME_LIFETIME blocked and do not advance to Panda.

## Historical Luna escalation input

The remainder records the initial evidence that triggered Astra review. It is not the current continuation instruction.

## Active card and safety posture

- Active milestone: `C2ME_LIFETIME`.
- Branch: `stage7/scalablelux-persisted-light-repair`.
- Source HEAD: `31fea7d45b6f161fa3897d894cb016d89e1dcd79` (`docs: finalize stage8 entry provenance`).
- Production JAR: `build/libs/phoenium-0.2.0-stage2.jar`.
- Production SHA-256: `8A1C0C4852150C8D36E9EDB8DDEFC9F9B6E9A3E82AE71123D798416DD39A21FC`.
- Production `fabric.mod.json` still contains exactly:

```json
"breaks": {"c2me": "*", "c2me-rewrites-chunk-system": "*"}
```

- No final Stage 7 commit was created; formal combined-stack Stage 8 acceptance has not started.

## Exact runtime composition

- Minecraft `26.2`, Java `25`, Fabric Loader `0.19.5`.
- Fabric API `0.159.0+26.2`, SHA-256 `3F3A5D96E6A8F554A72E71FB507D6A979CA16D9190C305CEB1300F1D01E733EE`.
- C2ME `0.4.2-alpha.0.43+26.2`, SHA-256 `B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`.
- ScalableLux `0.3.0-alpha.0.3+26.2`, SHA-256 `8D76CF27589DCC8E16C34BA0709A9EE2134CFCD60B90862F8CBD204EA9B6C7C8`.
- ScalableLux provides `starlight` through its own `fabric.mod.json`; no separate Starlight JAR is present.
- Disposable no-break Phoenium candidate used for these runtime witnesses: `run/stage8/entry-20260912/candidates/a2-final-current/phoenium-0.2.0-stage2-a2-final.jar`, SHA-256 `4F25BAE543C5D8D79FFAEEE20D50643BB136D79E6A62F1DFE1D995E558367590`.

## What passed

Literal W1 and W4 exact-pair redstone fixtures passed the following bounded lifecycle:

`fresh/start → generate/use known redstone data → save-all flush → normal stop → restart the same existing world → real ReadFromDisk → verify lamp persisted and then off → stop`.

The restart phases reached `ReadFromDiskAsync`, used the exact ScalableLux-owned persisted-light provider, and reported `c2meReadRegistrations=119`, `c2meReadApplications=119`, and `c2meReadProviderFailures=0`. The final binding receipts for both workers had zero retained graphs, work entries, custody entries, leases, barriers, duplicates, fallbacks, ownership violations and other health counters. The evidence directories are:

- `run/stage8/entry-20260913/c2me-exact-lifetime-redstone-w1/`
- `run/stage8/entry-20260913/c2me-exact-lifetime-redstone-w4/`

The held-read stop witnesses also passed process settlement at W1 and W4. An observer held the exact `SerializableChunkData.read` return boundary for 15 seconds on a C2ME worker; the external halt request occurred after 2 seconds; each server exited `0` and shut down with zero retained Phoenium state. These prove a meaningful in-flight stop boundary, but the hold occurs after Phoenium's private graph has completed and therefore does not prove Phoenium cancellation control. Evidence is in:

- `run/stage8/entry-20260913/c2me-exact-lifetime-stop-restart7-w1/`
- `run/stage8/entry-20260913/c2me-exact-lifetime-stop-restart7-w4/`

## Discriminating failure

The disposable observer injected one bounded failure at the exact C2ME provider drain entry:

`com/ishland/c2me/rewrites/chunksystem/common/async_chunkio/ChunkIoMainThreadTaskUtils.drainQueue(...).enter`.

At both workers, the receipt was emitted once, `c2meReadProviderFailures=1`, and all Phoenium retained counters reached zero. The terminal outcome is not accepted as a clean failure contract: W1 ended with `c2meControls=2`, W4 with `c2meControls=0`, both reported `scheduler=FAILED`, and the intentionally injected exception was logged as part of server shutdown. The complete preserved reports/logs are:

- `run/stage8/entry-20260913/c2me-exact-lifetime-failure-stop-w1/`
- `run/stage8/entry-20260913/c2me-exact-lifetime-failure-stop-w4/`

This result is evidence that provider failure is not silently swallowed and that Phoenium residue retires, but it is not evidence that all provider-wide failure/stop semantics are accepted.

## Exact unresolved cancellation boundary

The disposable observer captured the actual C2ME `Cancellable` passed to `ReadFromDiskAsync.upgradeToThis`. During a held persisted read at both W1 and W4 it called `cancel()` on 49 captured action tokens and recorded `attempted=49 cancelled=49 failures=0`. The read nevertheless continued to its normal return, and the final Phoenium receipts remained:

`c2meControls=0 c2meProducerFailures=0 c2meReadProviderFailures=0 c2meRetained=0 ... lifecycleEntries=0 workEntries=0 custodyEntries=0`.

The current production mixin wraps the final `Completable` with `doOnDispose` and calls `c2meAsyncDisposed(context)` from that disposal callback. Runtime bytecode/source inspection shows:

1. C2ME creates an action `Cancellable` and passes it into `ReadFromDiskAsync.upgradeToThis`.
2. The action token's `cancel()` marks the token cancelled but has no bound `onCancel` callback in this path.
3. C2ME's `ItemHolder.subscribeOp(Completable)` subscribes to the Rx operation and discards the Rx `Disposable`.
4. Consequently, cancelling the action token does not dispose the Rx `Completable`, so Phoenium's `CANCELLED` graph-control callback does not run.

The preserved runtime witnesses are:

- `run/stage8/entry-20260913/c2me-exact-lifetime-cancel-cancellable3-w1/`
- `run/stage8/entry-20260913/c2me-exact-lifetime-cancel-cancellable3-w4/`

## Why this is ASTRA-HARD

Closing this gap requires deciding which object is authoritative for cancellation, how action-token cancellation is bound to the Rx subscription, and how that choice interacts with holder/status replacement, stale lineage, provider continuation, lease release, replay prevention and server stop. Those are explicitly classified as ownership/lease/cancellation/unload/stop authority questions by the Stage 7 campaign. The current session has no selectable Astra route, so no production semantics were changed by trial and error.

## Required review/acceptance questions

The next architecture review must answer these questions before any production patch or `breaks` removal:

- Which C2ME cancellation token is authoritative for the Phoenium graph: the outer holder token, the `ReadFromDiskAsync` action token, or the Rx subscription disposable?
- Where is the authoritative one-way binding installed so cancellation disposes the exact Rx operation exactly once without replay or double control?
- How do `preDowngradeFromThis`/`downgradeFromThis`, holder replacement and stale lineage interact with that binding?
- What terminal outcome is documented for provider failure and server stop, including whether `scheduler=FAILED` is an accepted bounded result or a defect?
- How is continuation prevented after cancellation/failure while preserving provider-visible cancellation behavior and zero lease/work residue at W1 and W4?

Until those answers are implemented and demonstrated with provider-wide W1/W4 evidence, keep both production `breaks` declarations, keep A3 unwired, do not start combined Stage 8 acceptance, and do not claim Stage 7 completion.
