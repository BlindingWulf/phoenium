# Stage 8 entry current work

Updated: 2026-09-11

Status: **Stage 8 prerequisite engineering in progress. Formal combined-stack Stage 8 acceptance has NOT started.**

Baseline branch: `stage7/scalablelux-persisted-light-repair`.

## Completed in current entry campaign

### Local Qwen routing/tool acceptance

The required local route through `C:\AI\Models\qwen-subagent.ps1` is verified with provider `qwen-local`, model `Qwen3.8-27B-NVFP4-MTP-MID-HIGH`, local llama.cpp model identity, real repository tool calls, and successful returned reports. The parent retains architecture and final-review authority. Receipt: `run/stage8/entry-20260911/QWEN_C2ME_READINESS_RECEIPT.md`.

### C2ME prerequisite milestone 1 — stable readiness status-slot identity

Implemented a locked-provider adapter for C2ME `BLOCK_TICKING` status-slot identity so `LiveTickReadiness` does not treat C2ME's freshly-created public wrapper as a new producer on every observation. One wrapper is retained per stable slot incarnation; slot replacement re-observes; old producers are not cancelled; reflection fails closed on provider drift.

Verification: 865 tests / 93 suites / zero failures, errors, or skips; full production JAR build PASS; `git diff --check` PASS. Current JAR SHA-256: `37A8B73DFC22D56B7FEC6E7C99644CBCF7E86468A188E0837A6B3A31735A1124`. Qwen final review: ACCEPT. This does not establish installed C2ME compatibility.

## Mandatory entry gates still open

1. C2ME direct `VanillaWorldGenerationDelegate` / `ChunkStep.apply` generation and status-completion adapter into Phoenium admission/retained-input/completion semantics.
2. C2ME `ServerAccessible*` FULL conversion/publication/lifecycle split into preparation, owner-local finishing, and acknowledged publication.
3. C2ME `ReadFromDisk*`, asynchronous serialization, cancellation/failure/stop lifetime and save acknowledgement binding; then co-installation composition and installed W1/W4 acceptance before removing the C2ME gate.
4. Panda-native prerequisite A1: authoritative non-player cross-owner relocation with bounded pending facts, quiescent commit, exactly one object-epoch bump, stale/incarnation invalidation, no owner-to-owner blocking, derived-index republish, and exactly-once ordered relocation/accounting emission.
5. Panda-native A2 pearl ledger/version-pinned lifecycle follows A1; A3 staggering remains unaccepted pending a semantic-preservation seam.
6. External client facilities: real Bedrock gameplay, legacy Java/Via translation, and real Simple Voice Chat microphone/proximity/reconnect coverage.

VMP remains optional. SLO remains excluded from strict seed-parity. FastNoise strict FULL parity attribution remains bounded by its documented nondeterministic-control limitation.

## Client preparation already established

Windows Minecraft Bedrock package `Microsoft.MinecraftUWP` is installed (package version observed `1.26.4501.0`) with a launch entry through `GameLaunchHelper.exe`. Minecraft Java Edition is installed. ADB is available and an authorized Samsung S20 FE 5G is connected, but its queried Minecraft package version was stale-looking and is not accepted as Bedrock gameplay evidence. Windows Bedrock is the stronger current actual-client candidate. No credential bypass, account change, purchase, or untrusted launcher is authorized.

## Next executable milestone

Implement and test A1 pending non-player cross-owner relocation while continuing independent client-facility preparation. Do not call `ObjectOwnershipRegistry.transitionOwner()` directly from `Entity.setPosRaw`; the existing contract requires a bounded pending fact and quiescent publication with exactly-once derived accounting.
