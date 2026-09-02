# Project Phoenium

Phoenium is a planned Fabric 26.2 dedicated-server mod for regionized multithreaded Minecraft simulation.

This repository is currently at **Stage 0: source truth and architecture lock**.  It deliberately contains documentation only.  It contains no runtime source, mixins, access wideners, Gradle build, generated mappings, or copied third-party implementation.

The implementation starts only after the checkpoint recorded in [docs/STAGE0_AUDIT.md](docs/STAGE0_AUDIT.md):

`PHOENIUM_P0_SOURCE_TRUTH_LOCKED`

Read [docs/HANDOFF.md](docs/HANDOFF.md) first when resuming work.  The documents define the source hierarchy, legal plan, design boundaries, and test gates that subsequent stages must satisfy.

## Stage 0 decisions

- Target: Minecraft Java Edition 26.2, Fabric Loader 0.19.5, Fabric API 0.159.0+26.2, Java 25, Fabric Loom 1.17.20, and Gradle 9.5.1.
- Delivery: Fabric server-only mod id `phoenium`; client code is not part of the plan.
- Execution model: serial-first regional execution through Phoenium-owned worker contexts, followed by bounded parallelism only after ownership and topology tests pass.
- Failure model: an ownership or scheduler failure stops Phoenium scheduling behind a hard barrier.  It never silently resumes vanilla ticking on the server thread.
- Source use: Stage 0 copied no implementation.  Future direct adaptation of Folia, Ruthenium, Moonrise, or ConcurrentUtil code requires GPL-3.0-only licensing, preserved notices, a provenance entry, and a review before the source enters this repository.

## Document map

| Document | Purpose |
| --- | --- |
| [Architecture](docs/ARCHITECTURE.md) | Proposed component boundaries and message flows |
| [Threading invariants](docs/THREADING_INVARIANTS.md) | Non-negotiable ownership, migration, and failure rules |
| [Source provenance](docs/SOURCE_PROVENANCE.md) | Audited source hierarchy, revisions, licensing, and reuse decisions |
| [Compatibility matrix](docs/COMPATIBILITY_MATRIX.md) | Required target-mod and subsystem compatibility plan |
| [Test matrix](docs/TEST_MATRIX.md) | Evidence recorded in Stage 0 and gates for later stages |
| [Benchmarks](docs/BENCHMARKS.md) | Measurement protocol; no results are claimed yet |
| [Roadmap](docs/ROADMAP.md) | Stage gates and exit criteria |
| [Stage 0 audit](docs/STAGE0_AUDIT.md) | Evidence-backed audit conclusion and checkpoint |
| [Handoff](docs/HANDOFF.md) | Resume instructions, known risks, and repository state |

