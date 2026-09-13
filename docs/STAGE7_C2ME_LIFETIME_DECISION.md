# C2ME lifetime final decision

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

# C2ME lifetime architecture decision — 2026-09-13

**C2ME_LIFETIME BLOCKED — EXACT ARCHITECTURE BLOCKER.** The locked provider has no terminal failure settlement for requested status futures on a broken upgrade. Both production C2ME exclusions remain. The cancellation experiment is withdrawn from production; no complete lifetime acceptance, Stage 7 completion, or Panda continuation is authorized by these results.

## Proven unsupported semantic

After an unrecoverable DISK upgrade failure, C2ME can finish the action and release its busy reference while leaving the requesting DISK and dependent status futures pending. A later request on the same broken holder reuses the pending future. Removing every ticket eventually unloads the holder and completes those futures with the generic `UNLOADED_EXCEPTION` ("Not loaded"), losing the original failure. Thus producer settlement and requester settlement are different boundaries. Phoenium cannot infer the latter from an Rx terminal, zero retained work, or `getOpFuture().isDone()`.

Three independent observations support this conclusion:

1. Exact binary: `TheChunkSystem.handleTransactionException`, offsets 69–105, schedules `MinecraftServer.reportChunkLoadFailure` and returns `MARK_BROKEN`. The native scheduler's failed-upgrade continuation marks the holder broken, clears dependencies and the running action, and settles its operation. It does not complete failed requested status slots. `tickHolder0`, offsets 268–280, returns when an upgrade is requested on a broken holder. Reporting a load failure is not requester-future settlement.
2. Executable native characterization: `C2meNativeFailureSettlementTest` loads the locked scheduler, holder, token and Rx implementation. Only the three-status workload, queued executor and subclass failure handler are test code. With the native `MARK_BROKEN` policy, the exact original error reaches that handler, busy becomes false, the action becomes null and the operation future completes, while DISK/FULL remain pending and item count remains one. A second native request stays pending. Removing both native tickets retires the holder and fails the old future with `UNLOADED_EXCEPTION`, not the original cause. No Phoenium production code executes in this test. A passing characterization records the unsupported behavior; it is not a compatibility pass.
3. Installed candidate v3, literal W1 and W4, each propagates one injected `drainQueue(...).enter` failure as one exact FAILED operation terminal. The synchronous chunk requester remains in the load wait and both processes end through the watchdog with exit 1. Neither failed restart supplies a final Phoenium binding receipt, so zero residual state is not claimed for these runs.

Evidence root: `run/stage8/entry-20260913/astra-c2me-lifecycle/`. See `scheduler-bytecode.txt`, `the-chunk-system-bytecode.txt`, `native-failure-final.xml`, and `failure-v3-w1/` / `failure-v3-w4/`. The binary maps include method names and bytecode offsets; offsets are not Java source lines.

## Authoritative operation and lifecycle

The native identity of one disk action is **the action Cancellable + its exact ChunkLoadingContext + backing ItemHolder + original DISK status slot**, bound to the existing Phoenium holder/status/work/lifecycle lineage. `advanceStatus0` creates distinct outer, dependency and action tokens. Its action continuation creates one context; async retries and sync fallback reuse that context. A later action can reuse a still-requested DISK slot, so the slot alone is insufficient. Thread identity and transient Rx wrappers are not lifetime identities.

| Boundary | Locked provider authority | Consequence for a future adapter |
| --- | --- | --- |
| Cancellation request | `Cancellable.cancel()` atomically changes the token to CANCELLED and invokes an existing callback, if any. `complete()` competes with cancel; only one wins. | A successful cancel is a request, not physical read completion. Avoid late `setup`, whose get-and-set can overwrite cancellation. |
| Cancellation acknowledgement | The scheduler recognizes a cancelled action only when the token is cancelled **and** the operation error, after CompletionException unwrapping, is CancellationException. | Return the exact control through Rx error sources. Normal completion still advances status even if the token was cancelled. |
| Private producer | ReadFromDiskAsync ignores the action token, installs no callback and never completes it. `ItemHolder.subscribeOp` discards the Disposable and decrements busy through terminal subscription. | SerializableChunkData.read may continue. Forced disposal is not a valid cancellation or busy-settlement protocol. |
| First live effect | Deferred POI queue drain precedes `finalizeLoading` publication. Under exact ScalableLux, persisted-light nibbles belong to the unpublished chunk; vanilla retain/queue calls are no-ops. | Private parsing can finish after retirement, but retired work must not drain POI, publish, replay effects or recreate Phoenium work. |
| Success | `finalizeLoading` stores ChunkState and triggers deferred DISK demand; normal action completion then advances the native status. | Commit/cancel arbitration must precede the first live effect, not merely the final Completable callback. |
| Demand change | `tickHandleBusy0` calls `tryCancelAction` when the next desired status changes; outer cancellation reaches the action/dependency tokens. | Revalidate the exact action/holder/slot and Phoenium lineage before any live effect. A cancellation request alone is not yet unload. |
| Slot replacement/unload | `flushUnloadedStatus` replaces retired slots under the holder monitor, then completes old futures exceptionally outside it. Later removal calls onItemRemoval, holder.release and map removal. | Retired slot identity cannot publish. `addTicket0` retries a closed holder; a new action/holder is a new legitimate lifecycle. |
| Terminal failure | Exhausted/error-preserving Rx completion enters native `MARK_BROKEN`; requested futures remain pending. | **Blocked:** there is no native requester-failure settlement boundary to bind safely. |
| Stop | The provider's stop hook and hasWork require the holder-map item count to reach zero. Busy settlement feeds native unloading but is not equivalent to map retirement. | Preserve physical producer settlement; zero Phoenium counters or process exit alone cannot prove provider-wide stop. |

The proposed, experimentally exercised arbiter was `OPEN -> COMMITTING -> APPLIED/FAILED`, or `OPEN -> CANCELLED/SUPERSEDED/SHUTDOWN`. It retained the action record until physical producer settlement and used native token `complete()` immediately before the first owner-routed POI effect (or publication for an empty queue). Before that point cancellation can win; after it cancellation returns false and committing work must finish or fail. This reused existing custody/work/light registration, dispatcher, and completion after lease release, without a second scheduler or owner wait. Cancellation checkpoints and exact control propagation worked in the bounded W1/W4 trials. Replacement/unload and shutdown arbitration are mapped design obligations, **not accepted installed proofs**. The arbiter cannot solve native requester failure settlement and is not retained as production code.

## Smallest required provider change

The provider needs one native failed-upgrade settlement operation, integrated with its own transaction handler. It must validate the exact action, holder and affected status generation; atomically record an unrecoverable failure and prevent success/retry of that failed action; snapshot the failed and dependent requested slots; and complete those promises with the original cause outside the holder monitor. Requests arriving on the broken holder need defined terminal failure behavior rather than a permanently pending slot. Native dependency release, ticket removal, unload, and legitimate re-request after retirement must continue to function. Completing futures must not introduce inline callbacks under a provider lock.

This can be a provider-owned internal fix or a supported API; a particular method name is not required. It needs cancellation/failure races and subsequent-request coverage. `MARK_BROKEN` alone is insufficient. Phoenium's work/custody/dispatcher primitives control its own publication and leases; they do not own all C2ME shared requesters, tickets, or failure policy. Completing an arbitrary returned future wrapper would not settle the backing slot. Directly rewriting the provider's shared slot array, removing other requesters' tickets, fabricating an empty successful chunk or adding a competing scheduler would assume authority this integration does not have.

## Experiment and acceptance ledger

All installed v3 trials used Minecraft 26.2, Java 25, Loader 0.19.5, seed 444444, and the same world within each first/restart pair. The runner's per-run `report.json` records commands, worker property, installed mod hashes, world details, exit codes and health receipts. Its `productionSha256` field names the supplied **disposable candidate**, not the accepted production artifact.

Candidate v3 SHA-256: `88FC0C32A2C24D6F058F9493C884632276C00C7B325846ECF6B85AAB56A5AD9C`. Source baseline: `31fea7d45b6f161fa3897d894cb016d89e1dcd79`. Exact experimental source bytes and hashes are retained in `withdrawn-v3-source/`, `withdrawn-v3-source-manifest.json`, and `withdrawn-v3-tracked.patch`; the candidate JAR is retained alongside them.

| Witness under evidence root | Result and limit |
| --- | --- |
| `cancel-v3-w1/` | First/restart exit 0. Restart 120 read registrations, 119 applications, one CANCELLED, 119 APPLIED, zero read failures and zero final retained/safety counters. Successful cancel receipt context 1044293616/token 1881793608 matches the CANCELLED terminal. |
| `cancel-v3-w4/` | First/restart exit 0. Same 120/119 read counts and one CANCELLED/119 APPLIED; zero read failures and final retained/safety counters. Matching context 1198464935/token 1033207760. |
| Both cancellation trials | 49 captured tokens attempted; one successfully cancelled, zero cancel-call failures. Already committed tokens now reject cancellation. Exact operation counters show zero outstanding actions, superseded/shutdown/failed terminals, graphs/work/custody/light residue, leases, barriers, duplicates, fallbacks and ownership violations. These are bounded cancellation proofs only. |
| `failure-v3-w1/` | First exit 0; restart unresolved, watchdog exit 1. One FAILED, context 1725386224/token 2109797193; no final restart binding receipt. |
| `failure-v3-w4/` | First exit 0; restart unresolved, watchdog exit 1. One FAILED, context 304595350/token 1718984360; no final restart binding receipt. |
| `persistence-v3-w4/` | First exit 0; restart exits 0 but is unresolved because `STAGE7_C2ME_EXACT_W4_RELOADED` was not observed. Restart 119/119 reads and zero retained counters do not make the missing functional marker a pass. |
| Replacement/unload, full stop and co-install matrix | Not completed for changed bytes. Investigation ended on the independently reproduced native blocker. Historical persistence/stop evidence is preserved, not substituted for v3 acceptance. |

The historical W1/W4 difference in `c2meControls` does not measure disk terminal authority: that counter covers generation graphs. The v3 experiment introduced separate disk-action counters; both failure trials now show one FAILED. Counter agreement still does not repair native future settlement.

Hypothesis history: v1 failed startup with a VerifyError because an optional-type ModifyReturnValue coercion did not supply the provider return cast. The existing WrapOperation surface corrected that injector issue. V2 then exposed Rx wrapping a thrown recovery-callback control into CompositeException. Actual locked-Rx tests reproduced that error (2/2 RED); returning `Single.error(control)` corrected it (GREEN). The lifecycle model was revisited before further production changes. V3 preserved cancellation but disproved the hypothesis that normal error-channel settlement suffices for provider-wide failure/stop: native MARK_BROKEN leaves requesters pending. No timeout increases or configuration changes were used to reinterpret these failures as passes.

## Final checkout, verification and continuation

Branch remains `stage7/scalablelux-persisted-light-repair`; HEAD remains `31fea7d45b6f161fa3897d894cb016d89e1dcd79`. The five production files changed experimentally were clean at recovery and have been restored byte-for-byte from that HEAD. All experimental new production files/tests were archived before withdrawal. Luna's original observer/runner/plans and documentation are preserved; the observer retains only an added successful-cancel identity receipt. The native failure characterization test and this decision remain as new source/evidence documentation. There is no Stage 7 completion commit or push.

Final full suite: `.\gradlew.bat test --rerun-tasks --no-daemon`, exit 0; **930 tests / 104 XML suites / 0 failures / 0 errors / 0 skipped**. The locked provider/mixin selector tests are included. Final build: `.\gradlew.bat jar --rerun-tasks --no-daemon`, exit 0. `git diff --check` passes, and `git diff -- src/main` is empty.

Production `build/libs/phoenium-0.2.0-stage2.jar` reproduces the recovery bytes: **1,403,887 bytes**, SHA-256 `8A1C0C4852150C8D36E9EDB8DDEFC9F9B6E9A3E82AE71123D798416DD39A21FC`. Both `breaks.c2me="*"` and `breaks.c2me-rewrites-chunk-system="*"` remain. JAR audit finds no observer/probe or experimental lifecycle classes. See `final-production-audit.json`, `final-full-suite.log`, and `final-production-build.log`. No project server remains running.

Locked artifact hashes:

| Artifact | SHA-256 |
| --- | --- |
| C2ME 0.4.2-alpha.0.43+26.2 outer | `B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B` |
| C2ME rewrites | `76CEDA178849978682473C285B0A354BF611021EB59DD5FAB7ED18AC327D1F48` |
| C2ME base | `734D62AAD5440ACFFD3BBCB6A8C76904261AC250C223CBF90B90396D0FE377EB` |
| ScalableLux 0.3.0-alpha.0.3+26.2 | `8D76CF27589DCC8E16C34BA0709A9EE2134CFCD60B90862F8CBD204EA9B6C7C8` |
| Fabric API 0.159.0+26.2 | `3F3A5D96E6A8F554A72E71FB507D6A979CA16D9190C305CEB1300F1D01E733EE` |

ScalableLux supplies Starlight; no separate Starlight JAR was installed. No broader Stage 7/8 gate was worked.

Luna continuation: retain `C2ME_LIFETIME` as BLOCKED and both production exclusions. Read this decision before any adapter change. The first reproducible command from the repository is:

```powershell
.\gradlew.bat test --rerun-tasks --no-daemon --tests "dev.phoenium.chunk.C2meNativeFailureSettlementTest"
```

That command reproduces the blocker characterization; it does not clear the gate. Resume implementation only against a provider failure-settlement contract that addresses the requested-future gap, then rerun the complete deterministic and exact W1/W4 lifetime matrix, including final production bytes. Do not restore v3 as an accepted fix or move the work card to `PANDA_A2_LIVE`.
