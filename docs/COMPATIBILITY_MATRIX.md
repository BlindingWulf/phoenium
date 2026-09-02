# Compatibility matrix

This is a target and test plan. No target mod has been executed with Phoenium because Stage 0 has no Phoenium runtime.

## Core environment

| Component | Required posture | Stage 0 status | Future gate |
| --- | --- | --- | --- |
| Minecraft 26.2 | Exact runtime target | Toolchain identified | Dedicated boot on the exact locked artifact |
| Fabric Loader 0.19.5 | Required | Version selected | Dedicated boot |
| Fabric API 0.159.0+26.2 | Required | Version selected | API and dedicated boot |
| Java 25 | Required | Host version inspected | CI and dedicated boot |
| Server-only metadata | Required | Design locked | Metadata inspection and launch |

## Required ecosystem targets

| Target | Intended relationship | Stage 0 status | Required compatibility proof | Known integration risk |
| --- | --- | --- | --- | --- |
| C2ME | Required compatibility target; no direct dependency selected yet | Not tested | Current 26.2 module audit, chunk completion routing, generation-reroute test, dedicated boot | Chunk work may complete off-owner; no blocking or fake chunk fallback allowed. |
| Lithium | Compatibility target | Not tested | Boot and gameplay smoke tests | Vanilla optimization changes may alter tick call paths. |
| FerriteCore | Compatibility target | Not tested | Boot and memory smoke test | Memory-layout changes must not affect ownership assumptions. |
| VMP | Compatibility target | Not tested | Boot, entity tracking, player movement test | Tracker and networking paths are ownership-sensitive. |
| Krypton | Compatibility target | Not tested | Boot and packet ingress test | Connection and packet scheduling boundaries need proof. |
| Carpet | Compatibility target | Not tested | Command, tick manipulation, and diagnostic smoke tests | Commands may access world state from global context. |
| Spark | Compatibility target | Not tested | Profiling session and shutdown test | Sampling must observe without locking gameplay. |
| Chunky | Compatibility target | Not tested | Pregeneration and chunk pipeline test | High-volume chunk work requires adapter/rerouting. |
| Servux | Compatibility target | Not tested | Network/reporting smoke test | Packet and world snapshot access. |
| WorldEdit | Compatibility target | Not tested | Large edit and undo smoke test | Bulk mutations must enter owner gateway, not bypass ownership. |
| LuckPerms | Compatibility target | Not tested | Login, command, async callback tests | Permission callbacks must not mutate foreign region state. |
| BlueMap | Compatibility target | Not tested | Render/snapshot and save test | Snapshot reads must be immutable or owner-routed. |
| PandaAntiLag behavioral subset | Later feature target, no runtime dependency | Not tested | Local throttling correctness tests and dedicated smoke test | Current approach uses global scans, dimension-unsafe cache key, and partial entity compensation. |

## Compatibility policy

Phoenium will not declare hard dependencies on the listed optimization or utility mods merely to load. Each adapter or compatibility branch must announce its capability result at startup. A target with an unproven boundary remains disabled, rather than widening a lock or relaxing ownership.

The first compatibility implementation is not scheduled until the core serial-first scheduler, topology model, owner gateway, and dedicated server baseline pass their gates.

