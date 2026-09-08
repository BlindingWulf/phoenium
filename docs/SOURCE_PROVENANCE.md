# Source provenance and licensing

## Decision

Phoenium selects **GPL-3.0-only** as its planned project license before any direct implementation reuse from Folia, Ruthenium, Paper, Moonrise, or ConcurrentUtil. A complete project LICENSE file must be added before the first source file derived from any of those projects is committed.

Stage 0 copied **no implementation code** into Phoenium. This repository currently contains independently written audit and design documentation only. The decision therefore imposes no present code-notice obligation, but it is binding on later stages.

Any future source-derived file requires:

1. a row in the table below naming upstream path, pinned revision, license, and specific use;
2. preservation of applicable copyright and license notices;
3. a review that confirms GPL-3.0-only compatibility and corresponding-source obligations;
4. a commit message that names the provenance row; and
5. a source-level comment where a substantial adaptation needs reviewer context.

Behavioral lessons can be independently reimplemented without copying code. That is the planned approach for all Ruthenium material and for the primary Folia algorithms until a future review approves a narrower direct adaptation.

## Audited source hierarchy

| Priority | Source | Audited revision | Status | License evidence | Phoenium decision |
| --- | --- | --- | --- | --- | --- |
| 1 | [PaperMC/Folia, ver/26.2.x](https://github.com/PaperMC/Folia/tree/14b7fee5c866fca9a40ede4a58998fb928140f65) | 14b7fee5c866fca9a40ede4a58998fb928140f65 | Current primary semantic source | GPL-3.0; repository and PATCHES-LICENSE | Study current behavior first. No code copied in Stage 0. |
| 2 | Local supplied Folia mirror | Content matches the revision above except Windows line endings in three batch files | Read-only forensic reference | Same upstream license | Do not modify. |
| 3 | [BaconCat1/Ruthenium, main](https://github.com/BaconCat1/Ruthenium/tree/aebbbad87498c0718b1287a226789212e6103bcf) | aebbbad87498c0718b1287a226789212e6103bcf | Archived secondary reference | Root LICENSE and GitHub metadata: GPL-3.0 | Audit defects and intent; do not copy runtime code. |
| 4 | Local supplied Ruthenium mirror | Content matches the revision above except Windows line endings in five batch files | Read-only forensic reference | Same upstream license | Do not modify. |
| 5 | [TreeOfSelf/PandaAntiLag, main](https://github.com/TreeOfSelf/PandaAntiLag/tree/b8de06e7e04159b6753c3d94d7d8362a68e80002) | b8de06e7e04159b6753c3d94d7d8362a68e80002 | Current behavioral reference for later Stage 6 | CC0-1.0 root license | Redesign target behavior; no code copied in Stage 0. |
| 6 | Local supplied PandaAntiLag mirror | Substantively matches upstream pre-26.2 revision b74156f661250812ccb108ee6354a2eac3e2c7fa; one batch file has CRLF-only difference | Older read-only reference | CC0-1.0 | Do not treat as current 26.2 source. |
| 7 | Current Fabric 26.2 template/docs/Maven | Metadata inspected 2026-09-02 | Primary build and mapping source | Terms per upstream artifacts | Use as the initial build basis in Stage 1. |
| 8 | Minecraft 26.2 official server artifact | Version manifest SHA-1 verified during audit | Mapping/bytecode verification source | Mojang distribution terms | No redistribution or source extraction planned. |

## Important license finding

Ruthenium has a material metadata conflict: its root LICENSE and GitHub repository metadata state GPL-3.0, while its fabric.mod.json declares Apache-2.0. The root license is treated as controlling for the audit. The conflict makes direct reuse inappropriate until an upstream clarification exists; Phoenium will instead independently reimplement any adopted design.

Folia's patch license, Paper's supplied source license, Moonrise, and ConcurrentUtil are GPL-3.0. A permissive declaration in a consumer metadata file cannot convert that provenance. PandaAntiLag is CC0-1.0, but later feature work still needs its own correctness review.

## Current-source delta rule

Ruthenium ended active development before current Folia's 26.2 branch accumulated its later fixes. The audit found 31 Folia commits after the pre-Ruthenium-final baseline, including packet ownership, teleport acceptance, passenger reconciliation, direct world clock reads, redstone/game-event ownership checks, and 26.2 remapping. Future design review must read the current Folia implementation first and may use Ruthenium only to identify an implementation candidate or regression test.

Details are in [STAGE0_FOLIA_DELTA.md](STAGE0_FOLIA_DELTA.md).

## Stage 1 implementation provenance

All Stage 1 Java, Gradle, metadata, and test files are independently authored from
the Stage 0 behavioral contract. No Folia, Ruthenium, Paper, Moonrise, ConcurrentUtil,
PandaAntiLag, Minecraft, or Fabric implementation source was copied or adapted. The
GPL-3.0-only project LICENSE was added before implementation, so no source-derived
implementation row or upstream copyright notice is currently required.

## Stage 2 implementation provenance

Stage 2's lifecycle, typed `LiveLevelTick` payload, diagnostics, Mixin configuration,
and server harness guidance are independently authored. The exact 26.2 class and
descriptor names were verified from the locally resolved official Minecraft 26.2
artifact with bytecode inspection; no Minecraft, Fabric, Folia, Ruthenium, Paper,
Moonrise, ConcurrentUtil, or Panda implementation source was copied or adapted.
Fabric API is used only through its published lifecycle-event API. This remains a
source-independent implementation under the existing GPL-3.0-only project license.

## Stage 3 implementation provenance

Stage 3's object epoch ledger, envelope routing, async chunk completion identity, explicit
world-default directory route, and live entity reconciliation are independently authored from the
existing Phoenium contracts and exact Minecraft public API signatures verified from the locally
resolved official 26.2 artifact. No Folia, Ruthenium, Paper, C2ME, Minecraft, or Fabric source
was copied or adapted. C2ME is documented only as a future contract boundary.

The Stage 3 packet and chunk observer Mixins are likewise independently authored. The exact
official-named 26.2 method descriptors were verified with local `javap` inspection of the Loom
resolved Minecraft artifact. No handler implementation or C2ME source was copied or adapted.

## Stage 3 architecture review provenance

The movement and chunk application reviews are independently written analysis of the
same pinned Folia 26.2 reference, its pinned Paper dependency and the official Minecraft
26.2 bytecode. Exact paths, revisions, method descriptors, offsets and artifact digest
are recorded in `STAGE3_MOVEMENT_ARCHITECTURE_REVIEW.md` and
`STAGE3_CHUNK_APPLICATION_REVIEW.md`. The two pinned Paper patches were inspected only
to establish Folia's actual entity movement/index behavior. Local disassemblies and
reference patches remain in the parent workspace's `.audit` directory, outside this
repository and its packaged artifacts. No runtime implementation is copied, adapted,
or changed by this review; no compatibility-mod source was inspected.

## Stage 5 implementation provenance

The bounded worker pool, retained two-phase claims, native branch eligibility and
test harnesses are independently authored. The pinned Minecraft 26.2 Loom sources
were inspected locally for furnace/hopper tick effects, native wrapper identity,
recipe matching/publication, crash reporting and dedicated-server executor shutdown.
Original native bodies remain invoked through production Mixins. The direct holder
membership publication and crash lookup/failed-stop hooks use the exact native data
and existing APIs; no upstream gameplay implementation was imported. Local extracted
reference sources and disposable evidence remain under ignored `run/stage5`, outside
production and source artifacts. No Folia/Paper/Moonrise/Ruthenium/C2ME or compatibility
implementation is copied or added in this stage.
