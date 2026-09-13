# Locked C2ME native settlement patch manifest

2026-09-13. Native requester settlement is resolved by the pinned artifact below.
Phoenium lifetime acceptance is a separate result in
[STAGE7_C2ME_SETTLEMENT_REPORT.md](STAGE7_C2ME_SETTLEMENT_REPORT.md).

## Source and artifact identity

Source root: `C:\Users\Admin\Downloads\Project PHOENIUM\C2ME-locked-settlement`.
Original root revision `e5f9ed26349b2f9504f73cac48aa09a490d137a3`, describe
`0.4.2+alpha.0-43-ge5f9ed26`. Original FlowSched submodule
`fae2126839607b68d4059e3933a492886fe8b8f5`. Original compiled source was compared
with locked base bytecode after normalization for constant-pool numbering and
remapper ldc width/branch offsets; all four scheduler/holder/ticket/token
instruction and control-flow comparisons matched. No newer C2ME release was used.

Local commits, no push:

* FlowSched `f5634c0a64b952da9e2961fa724da136b51585cf` —
  `fix: settle failed chunk-status requesters`.
* C2ME `ec85f1081e6826a536e01f668dc7d0ae2e68daaf` —
  `fix: settle locked chunk requesters and preserve nullable availability`.

The root commit points at the local FlowSched commit. Both repositories must be
retained together; the local submodule commit is not available from a remote.
Reviewable exact diffs are [FlowSched patch](patches/c2me-flowsched-settlement.patch)
and [C2ME root patch](patches/c2me-root-settlement.patch). These are exact
commit diffs with zero context so patch context blank lines do not become
trailing whitespace in the containing documentation commit. Use
`git apply --unidiff-zero` at the recorded original revision; reverse check
against each patched checkout passes. Full-context diffs remain in the evidence
root as `native-flowsched.patch` and `native-root.patch`.

| Artifact | SHA-256 |
| --- | --- |
| Original C2ME outer | `B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B` |
| Original nested base | `734D62AAD5440ACFFD3BBCB6A8C76904261AC250C223CBF90B90396D0FE377EB` |
| Original nested rewrites | `76CEDA178849978682473C285B0A354BF611021EB59DD5FAB7ED18AC327D1F48` |
| Patched settlement2 outer | `8AE4F2BB5FB15A28E555816DE89F0E400EA6A55A11213673E60068BC43C13D53` |
| Patched nested base | `DBEA700B7675C4A6CA7E8F48D0D6554CDDA5B9C36F01B6588FDEBE5302E5732D` |
| Patched nested rewrites | `065C2A842FFB5DE66CC35E9F627AD6D79A84B0918BC7CEF8DE1C1D297A004625` |

Output under the C2ME source root:
`build/patched/c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar`,
**3,834,439 bytes**, metadata version
`0.4.2-alpha.0.43-phoenium-settlement2+26.2`. The adjacent machine manifest records
compiled class hashes and touched entry payloads. The original and settlement1
JARs remain intact. All other nested and outer entry payloads are unchanged.

## Native semantics and scope

FlowSched owns the status futures in `ItemHolder.futures`. The failed-upgrade
continuation validates its native action token, target status and original slot.
It retains the original Throwable on that holder, sets MARK_BROKEN, snapshots
unfulfilled affected futures and tickets, and publishes prefailed slots while
holding only the holder monitor. It completes the original futures and dependency
callbacks after releasing the monitor. Publishing replacements first prevents a
concurrent unload from substituting generic Not loaded before signalling.

Later demand on that broken holder receives its retained failure. Successful
lower statuses remain successful. A real new holder after native map removal may
work normally. Native dependency ItemTicket callbacks gain an optional failure
channel; error delivery goes through the dependent holder's existing critical
executor after dependency registration. No failed prerequisite invokes a success
callback. Native demand, dependency release, busy retirement and unload remain.

The only additional native method change is the rewrites module's nullable
`NewChunkHolderVanillaInterface.getWorldChunk` (runtime `getTickingChunk`). It now
returns a chunk only when its future is successful, instead of throwing from
getNow on a settled exceptional future. Explicit requester futures still expose
the original cause. This was required by the installed ticking/shutdown failures
that became visible once requesters actually settled.

The architecture was recorded before provider edits in
[the settlement decision](STAGE7_C2ME_PROVIDER_SETTLEMENT_ARCHITECTURE.md).
This is a reasonable small upstream proposal: FlowSched failure settlement and
the C2ME nullable availability contract belong in their respective native
projects. Local packaging/version metadata is integration-specific. No upstream
submission has been made.

## Tests and build

The original Phoenium `C2meNativeFailureSettlementTest` remains unchanged and
targets the original binary. Its pass characterizes the old defect. The separate
provider test began red (7 tests, 6 failed), then passed and expanded to **11
contract tests** covering original DISK/FULL cause, cross-holder dependency
failure, later demand, real holder replacement, unload, both cancellation/error
orderings, rejected late success, multiple requesters and callback/lock safety.
All 11 pass with the actual settlement2 nested base ahead of compiled source on
the test classpath. The complete FlowSched suite passes: **19 tests / 4 suites /
0 failures / 0 errors / 0 skipped**.

`C2meNativeAvailabilityTest` executes the original and patched packaged native
getter, supplying only its future through a test subclass. It proves the original
throws for failure, while the patch returns null for pending/failed/cancelled
availability, preserves the request Throwable, and returns the same successful
chunk. It is part of the full Phoenium regression.

Exact commands used in the native root, Java Temurin **25.0.4.1+1-LTS**:

```powershell
.\gradlew.bat :c2me-rewrites-chunk-system:remapJar --no-daemon
python build-settlement.py ../Phoenium/build/stage6-audit-20260909/research/artifacts/c2me/c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar build/patched/c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar --rewrites c2me-rewrites-chunk-system/build/libs/c2me-fabric-rewrites-chunk-system-mc26.2-0.4.2-alpha.0.43-dirty.jar
```

The builder compiles FlowSched and refuses to overwrite an existing output.
The remapped input filename above is the one emitted before the local root
commit; a subsequent rebuild must use the filename its checkout emits and verify
the class payloads. Acceptance pins these existing bytes, not an assumed hash
from a later build with different archive timestamps.

Evidence root relative to Phoenium:
`run/stage8/entry-20260913/astra-c2me-provider-settlement/`. It preserves source
provenance, normalized bytecode comparisons, `provider-red.log`, green/expanded
logs, `patched2-binary-contract.log`, `patched2-native-contract.xml`,
`native-full-final.log`, `native-full-final-totals.json`, native availability
red/green evidence, exact source diffs, and all installed trials.
