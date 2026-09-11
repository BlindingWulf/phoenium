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

### C2ME prerequisite milestone 2 — pre-FULL generation/status completion bridge

Implemented the locked-provider `VanillaWorldGenerationDelegate` bridge at the two exact pre-FULL `ChunkStep.apply(...)` call sites. Provider identity is bound to the backing C2ME `ItemHolder`, its exact status-slot future, the exact center `GenerationChunkHolder`, and target `ChunkStatus`. C2ME remains the asynchronous producer; Phoenium delays only the returned provider branch until owner admission and claim-time provider-lineage revalidation. Topology changes reroute the same queued stage, replacement provider slots supersede the old branch, synchronous producer throws retain their synchronous behavior, provider failures propagate unchanged, and shutdown/supersession cannot replay a late producer.

The production C2ME `breaks` declarations remain intact. Runtime mixin application was proven only with a disposable candidate copy whose embedded metadata removed those declarations. The installed W1 proof reached a real C2ME world, transformed both pseudo-mixin handlers, and observed 2,422 real `ChunkStep.apply` enter/return pairs on C2ME worker threads through `ChunkBindingRuntime.c2meGeneration`. The run then stopped at the separate, still-open `RegionalSaveBinding` `UNSAVED` boundary; that is not treated as installed C2ME acceptance.

Verification after the final lock-order correction: 888 tests / 96 suites / zero failures, errors, or skips; production JAR build PASS; `git diff --check` PASS. Production JAR SHA-256: `8C56B3F4B0E485D50C6447993E6D37C7D33CBFD2A89AB0A5ADA047710F9A18D4`. Disposable post-fix candidate SHA-256: `52F48EFED4D7D406CCCFD6216AFC7E79C3EAB575B204347C2E41245EB5ED5478`. Qwen final review: ACCEPT with no must-fix issue. Evidence: `run/stage8/entry-20260911/C2ME_GENERATION_MILESTONE.md` and `run/stage8/entry-20260911/QWEN_C2ME_GENERATION_FINAL_REVIEW.log`.

### Panda-native prerequisite A1 — authoritative non-player cross-owner relocation

Implemented the bounded physical-observation -> owner-free commit protocol required by the frozen Stage 7 architecture contract. Non-player cross-owner movement now invalidates the derived interaction target immediately, retains only the latest immutable relocation fact, and commits at the owner-free entity-management boundary. Source and destination are fenced with deterministic non-blocking delivery selections; busy or transitioning regions defer without owner-to-owner waiting. The commit revalidates object epoch/incarnation and destination topology, changes authority through the sole `ObjectOwnershipRegistry.transitionOwner()` path, republishes the derived index, and emits exactly one ordered relocation fact.

The implementation also retains an initially unresolved physical destination until topology publishes it, handles topology loss/republication, same-owner supersession, removal/re-addressing, shutdown, and post-commit sink failure. Live diagnostics now expose pending and committed relocation counts. Players remain on the existing classified packet movement protocol.

Verification after final Qwen review: 875 tests / 94 suites / zero failures, errors, or skips; focused A1 + Stage 3 ownership/movement + real-client-readiness regressions PASS; full production JAR build PASS; `git diff --check` PASS. JAR SHA-256: `9DA1A5F006B0291CB77AD17D3C573A633B38DFF886DAC328D50C059B2247C9E6`. Qwen hostile review and exact final-delta review: ACCEPT. Evidence: `run/stage8/entry-20260911/QWEN_A1_RELOCATION_RECEIPT.md` and `run/stage8/entry-20260911/QWEN_A1_RELOCATION_REVIEW.md`.

## Mandatory entry gates still open

1. C2ME `ServerAccessible*` FULL conversion/publication/lifecycle split into preparation, owner-local finishing, and acknowledged publication.
2. C2ME `ReadFromDisk*`, asynchronous serialization, cancellation/failure/stop lifetime and save acknowledgement binding; then co-installation composition and installed W1/W4 acceptance before removing the C2ME gate.
3. Panda-native A2 pearl ledger/version-pinned lifecycle follows completed A1; A3 staggering remains unaccepted pending a semantic-preservation seam.
4. External client facilities: real Bedrock gameplay, legacy Java/Via translation, and real Simple Voice Chat microphone/proximity/reconnect coverage.

VMP remains optional. SLO remains excluded from strict seed-parity. FastNoise strict FULL parity attribution remains bounded by its documented nondeterministic-control limitation.

## Client preparation already established

Windows Minecraft Bedrock package `Microsoft.MinecraftUWP` is installed and healthy (package version observed `1.26.4501.0`) with `gamelaunchhelper.exe` and `Minecraft.Windows.exe` present. Minecraft Java Edition is installed. ADB is available and the currently authorized device is the Moto G 2025 (`[device-serial]`, Android 16); that phone does not currently report a `com.mojang.minecraftpe` package, so it is not a Bedrock gameplay client. Windows Bedrock remains the current actual-client candidate. Evidence: `run/stage8/entry-20260911/CLIENT_ENVIRONMENT_PREP.md`. No credential bypass, account change, purchase, or untrusted launcher is authorized.

## Next executable milestone

Advance the next dependency-ready C2ME prerequisite: split `ServerAccessible` FULL conversion/provider-state mutation from full world publication and bind the relevant `ServerAccessibleChunkSending` provider lineage to Phoenium owner admission. In parallel, A2 may consume the accepted ordered A1 relocation fact stream for the version-pinned projectile/pearl membership ledger, and client preparation may proceed toward actual Bedrock gameplay evidence. Formal Stage 8 acceptance remains gated on all mandatory prerequisites above.
