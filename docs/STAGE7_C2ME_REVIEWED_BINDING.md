# Reviewed binding to native settlement — 2026-09-13

The user explicitly authorized a narrowly reviewed Phoenium lifecycle binding
change after the native settlement patch passed. The original request's ban on
blindly reintroducing an experimental adapter remains relevant: the archived v3
source was read and compared to accepted source before reusing its bounded token
and Rx handshake. Its old installed receipts do not accept these new bytes.

The final native patch is settlement2, pinned by SHA-256
`8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53`. The initial settlement1 pin was
`C5DF53C8A11EA79FFC50B4657108136C6B25842457D673FCB5D15F66CC9E39A3`;
it is historical and is not the accepted provider.
Eleven native contract tests pass both against compiled source and the exact
rebuilt nested base JAR. A full FlowSched regression also passes. The original
characterization remains unchanged and continues to target the original provider.

The binding retains one action/context/holder/status-slot record through physical
provider completion. Only the native token CAS grants commit immediately before
the first owner-routed POI effect, or before publication for an empty queue.
Cancellation acknowledges through CancellationException, never forced Rx disposal.
Pre-commit private failures retain bounded native retries; a failure after live
effects is retained and bypasses retry, fallback and success recovery. Native
FlowSched alone settles shared requester slots. No Phoenium code completes them.

Review found two further issues in the archived prototype and corrects them:

* External stop/replacement retirement must cancel the real action token before
  emitting its control error, so native cancellation is acknowledged. Cancellation
  can call provider code and is therefore invoked outside the operation monitor.
* A commit that already won must finish or fail. Stop/replacement cannot relabel
  it after live effects begin. Retirement competes with commit through the native
  token CAS; the operation monitor protects only its own terminal metadata.

Focused tests cover those orderings, including a callback that checks it does not
hold the operation monitor. The terminal registry is released in a finally block
even when a terminal invariant is violated. A runtime provider version guard
requires the distinct native settlement base, preventing use with the original
unsupported C2ME provider after any future metadata gate removal.

At the initial review, acceptance was pending and both breaks stayed in place. The W1 trial
using accepted Phoenium source was not a decisive DISK-failure proof: fallback
completed DISK requesters and later shutdown failed higher statuses. Its exit 0
does not clear the gate. Two initial persistence plans were rejected before the
fixture because their polling marker contained digits; the existing runner only
allows uppercase letters/underscores in readiness markers. That harness error
does not imply a provider regression. Correct the marker and use new evidence
roots, preserving the rejected runs and unchanged timeout policy.

## Final review and acceptance

The installed native task-drain error is captured inside the leased provider
body, then propagated with its original cause after release; an ordinary I/O
error does not trip the shared regional scheduler. True protocol violations
remain barriers. A reserved FULL turn encountered while polling the server
queue re-enqueues the same original task through the existing scheduler; it
does not wait on another owner or replay effects.

The final production binding includes the separately authorized
[SEND_READY repair](STAGE7_C2ME_SEND_READY_REVIEW.md). Its finite dependency pins
and asynchronous readiness preserve the original provider body and scheduler.
The original archived adapter was reviewed and corrected, not restored as an
unverified workaround; Phoenium never settles shared native requester futures.

Implementation commit `916904794590433ccac1777c24ffa023b7af4338` passes 953 tests and all 12 candidate and all
12 actual-production lifetime pairs. Both exclusions were removed after candidate
acceptance. Final production SHA-256 is `CFDF13479E1073E1AB270C1FD447CF1A991A6DFABF3D4F5924D74560F7ADAAC6`. See the
[settlement report](STAGE7_C2ME_SETTLEMENT_REPORT.md) for exact acceptance scope.
