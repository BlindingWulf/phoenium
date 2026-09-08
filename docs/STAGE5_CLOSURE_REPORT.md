# PHOENIUM Stage 5 closure and freeze

## A. Final status

**STAGE5_PARALLEL_SCALING_FROZEN**. All required final gates pass on the production
artifact below. The local annotated freeze tag identifies this final report and the
validated code. Stage 6 has not started; no further Stage 5 implementation or performance campaign is pending.

## B. Authoritative source

Repository: `C:\Users\Admin\Downloads\Project PHOENIUM\Phoenium`.
Branch: `stage5/bounded-parallelism`. Validated source HEAD:
`8cc210ff2db55835ca21170e3399dc3ac22dd643`, clean before benchmarks.
The complete Java build/test source is `d8cde019abfafa230480c45c9068cb3ee44a210f`;
the subsequent commit only adds native save flushing to the Python adverse driver.
The final documentation commit is identified by the local annotated freeze tag
`STAGE5_PARALLEL_SCALING_FROZEN`; its exact commit/tag IDs are recorded in the
final artifact manifest and final delivery. No production code changed after `5aeb2fe`.

Production JAR: `run/stage5/final-artifacts/phoenium-0.2.0-stage2.jar`.
The historical filename is retained; it does not describe the current implementation stage.

```text
Stage 5 production SHA-256
59BB31DC058351C7F4BC9DB572FEC1DE2B298FF623614FC4CB3EAFAEB5A772D5
Stage 5 acceptance probe SHA-256
DC1FA87FA00CE6EBFF8E25BEF77617BF3FA856B6BD15E0589A24D8AA5C260ED6
Stage 4 immutable reference SHA-256
43AAC30184E5C77784D7A56590AA23A87DC86438684EB2B2F2577A197F4DD81E
Stage 3 older immutable reference SHA-256
017EE8ED612D238894FFA5A1DC019BA8B0E31124B0B5171D08CC0D8993C1B534
```

Stage 4's peeled source/tag remains `1a82b701fce53395481f251347ca9a4deffc12d7`,
annotated tag object `8510dca7094c03caee5e4844434883fe5d249090`. Stage 3's source
remains `c31b3f42a5bcc06c9d3bae7b475337f225e9bd06`. Both frozen JARs, prior evidence
and saved-world fixtures are preserved.

Pinned runtime: Minecraft 26.2, Temurin Java 25.0.4.1, Fabric Loader 0.19.5,
Fabric API 0.159.0+26.2, Loom 1.17.20, Gradle 9.5.1. Gameplay configuration:
`-Dphoenium.gameplay.workers=1|2|4` for installed acceptance; implementation range
1–8, default 1. `-Dphoenium.gameplay.callbacksPerRegion=1..256`, default 64;
benchmark value 256. `-Dphoenium.gameplay.trace=true` only for interval evidence;
benchmark body and callback histories are disabled. Launch JSONs retain every JVM
flag, exact installed modules, heap limit, directory and runtime setting.

## C. What Stage 5 implements

The scheduler admits at most one finite cohort of original gameplay tasks to a fixed
server-lifetime pool. Candidate one-worker mode uses the same admission/preflight/body
path inline. Other modes dispatch to distinct non-daemon workers and join every
accepted body from the coordinator without holding regional ownership while waiting.

Delivery selection reserves a current queue head and fences topology, but grants no
mutation authority. All cohort participants acquire their exact current leases;
every final eligibility check completes under those same retained claims before any
body starts. Claims remain held through the original native body. A common preflight
decline commits no original effect, acknowledges the cohort's ordinary-path obligations
and invokes the native ordered segment once. A selected-body exception cannot fall
back, replay reached effects, or force-release a legitimate peer.

Native ticker scanning preserves original per-owner order and splits at shared or
ineligible callbacks. The admitted branches are original vanilla furnace, blast furnace
and smoker cooking between fuel/completion boundaries, and hopper cooldown/disabled
branches. Eligibility checks exact native class, bound object incarnation, block state,
native readiness, current topology/owner and immutable native recipe membership.
The cooking path invokes the original Minecraft virtual tick; it is not an alternative
smelting algorithm. Installed overlap evidence uses actual furnaces.

Ignition, extinction, fuel consumption, recipe completion, dirty/world writes,
neighbor updates, hopper transfers, other block entities, entity AI, tracking, scheduled
ticks and other shared native phases retain their original ordered paths. Generation
and storage workers remain asynchronous and are not counted as gameplay concurrency.
The shared-service turn coordinates native mutable services; it does not wrap independent
local original bodies in a single execution permit.

Merge/split fences both selected deliveries and active claims. Dynamic chunk authority
resolves the lease manager for the current published route; new runtime registration
occurs on the coordinator before atomic route publication. Stale physical inboxes
remain deliverable after routing changes. Input acknowledgement, object incarnation,
operation identity, owner epoch and topology generation remain separate obligations.

Failure closes new admission, retains the first initiating exception, joins accepted
peers and preserves legitimate save input through acknowledgement. Healthy shutdown
flushes native saves; failed shutdown settles accepted writes without claiming all
world state was durably saved, then closes workers and native resources in order.

For W workers and per-region capacity C: queue capacity W; one registered batch of
at most W tasks; at most W conflicting-region-free claims; at most W*C captured
ticker references (maximum 2,048); at most C callbacks per task. Optional completed
history is capped at 4,096 intervals, plus at most W*C in-flight interval records.
Accepted evidence reaches that 4,096 cap; benchmarks retain zero body intervals.
These bounds do not claim a fixed size for existing accepted-input inboxes, native
world contents, ticker indexes or inherited service state. Save capture separately
applies its existing 128-outstanding-record admission threshold.

## D. Important production defects found and fixed

| Symptom / risk | Root cause → repair | Regression / evidence |
| --- | --- | --- |
| Terminal packet acknowledgement could be blocked or treated as gameplay | Delivery selection and mutation authority were conflated → separate selection reservations and terminal acknowledgement without gameplay authority | Preserved terminal packet suite; selection/terminal cleanup tests; all installed clean stops |
| Shutdown could erase selected delivery or admit a new obligation during settlement | Cleanup checked only active leases; ingress lacked a common stop fence → fence selections and insertion, retain obligations until acknowledgement | `BoundedGameplaySchedulerTest` selection, stop, preflight-failure tests |
| A stale physical inbox could stall legitimate work after routing changed | Scheduling searched only the new route → scan retained physical delivery and resolve current owner | Stale routing tests; 24 installed post-transition native reads |
| A prior eligible native branch could become unsafe before dispatch | Releasing preflight claims allowed intervening queued mutations → common final preflight under all claims retained through bodies | Changed-eligibility and concurrent-ingress regression tests |
| Recipe matching could write shared lazy membership or call unreviewed recipes | Native direct membership cache was lazy; broad recipe iteration invoked unrelated virtual methods → publish immutable membership and inspect immutable exact type buckets | Native-source review and all serial/parallel furnace comparisons |
| Worker exception could be masked by a secondary chunk-thread failure | Native crash reporting reread a chunk through server-thread future access → read the exact bound chunk under the same current claim | Actual installed original failure preserved, peer joined, crash retained |
| Split-created owners failed valid chunk/save demand | Authority retained only a startup lease-manager map → resolve current registered runtime after published route | Dynamic-owner regression; FULL, churn, save/restart campaigns |
| Failure cleanup reached markers but needed watchdog exit | Exception from superclass stop skipped dedicated executor shutdown → finish native executor teardown while preserving the initiating exception | Final 2/4-worker and original Stage 4 adverse runs exit 0 without watchdog |

Test-only failures were kept separate. Completed pending-unload receipt, original sprint
finalization, supported fixture centers, legitimate FULL entity rereads, tracker clock,
pre-publication native IDs and a native non-ticking save cut repaired offered inputs.
They did not alter production output or remove comparator checks. The final adverse
setup failure (`adverse-final-2`) found a normal startup save pending before arming.
`8cc210f` adds `save-all flush` first; the unchanged probe still requires zero prior
custody and exactly one new held native write. Only that affected gate was rerun.

## E. Correctness test results

Complete suite: **834 tests, 88 suites; zero failures, errors or skips**.
Baseline: 818 tests, 87 suites. The last complete build is
`run/stage5/entity-id-input-build.log`; exact suite inventory is in
`run/stage5/prebenchmark-source-freeze-final.json`. Subsequent source changes are
Python adverse setup and closure documentation, with unchanged Java/probe/JAR hashes.

Coverage includes existing ownership, packet acknowledgement, transfer, scheduler,
topology, native chunk classification, FULL inputs/publication, save retention,
regional world-service, tracking and lifecycle suites. New bounded-scheduler coverage
includes actual two-thread model overlap, same-owner/capacity rejection, common
preflight, stale eligibility, failure while a peer/save is held, admission/stop races,
topology contention and independent progress past a busy region. Controlled race
tests are distinct from naturally overlapping installed original gameplay.

Comparator negatives: **17 test methods PASS** (8 preserved Stage 4, 6 native Stage 5,
3 client/input gates), `run/stage5/comparator-id-input-negatives.log`.
Actual copied-JAR prelaunch/restart identity guards and final server-only packaging:
**18/18 copied-file checks PASS**, `run/stage5/identity-final-c/report.json`.
Packaging PASS: 671 archive entries; no client entrypoint/mixin, forbidden test class
or test reference; all version pins intact. An additional check against all 183
Java test-source class names finds zero packaged classes. See `packaging-final.json`,
`packaging-test-source-check.json` and `final-verification.json`.

## F. Authoritative FULL results

Final evidence: `run/full-authoritative/live-1788908474731/full-acceptance.json`.
Production/probe identities match section B. Both real TCP clients and both native
server exits pass at 5/5; exit codes are 0/0. The fixture seed is
`-5846470989536090341` in fresh and persisted reload.

| Native gate | Fresh | Persisted reload |
| --- | --- | --- |
| Original origin | ADMITTED_PROTO | ADMITTED_IMPOSTER |
| Sources / saved readers | 4 / 8 | 4 / 8 |
| Rich villagers / frogs / teams | 4 / 4 / true | 4 / 4 / true |
| Bed / collision / immediate shulker / private-neighbor checks | 4 / 8 / 8 / 4 | 4 / 8 / 8 / 4 |
| Original suffix admissions | 4 | 4 |
| Real TCP chunk receipt | true | true |
| Natural original-body peak from retained timestamps | 4 regions/workers | 2 regions/workers |
| Traced furnaces / retained intervals | 512 / 4,096 | 512 / 4,096 |
| Conflicting same-owner claims/bodies; ownership/fallback/unleased faults | 0 | 0 |

The original eight entity roots are proven in native MCA chunk payloads and read
through native saved FULL branches, including conversion and rich-state facts.
`stage5-full-concurrency.json`, `fresh-observed-peak.json` and `reload-observed-peak.json`
retain exact interval checks. Final stopped tick totals are 246=246=246 fresh and
174=174=174 reload. The earlier fresh live diagnostic was captured inside one native
tick and reported one in progress; final shutdown proves its completion.
All native FULL/chunk/service retained-state receipts are zero as detailed in M.

## G. 5/5 results

`run/stage5/controls-client5-e/report.json`: frozen Stage 4, candidate one, two and
four workers all PASS. Candidate 1/2/4 each matches frozen; candidate 2/4 also matches
candidate one: **five exact comparisons**, **132 ordered events** each.
Per fixture, offered original tracker calls are A=515, B=187 and far=323.
The required native saved button delay is **186 ticks** in every configuration.
Native MCA state, unpack, before/deadline/after callback phases and furnace readback
match. Candidate 2/4 have natural original-body/claim overlap before and after restart;
candidate one has no concurrent original bodies.

Exact comparison digest:
`6AB41117A5099DD52A7E10C358A6E30887EB489B7A038C4E1F1A5078E4385A96`.
Separate normal tracker/ID run: `run/stage5/unrestricted-client-4-5-final/` —
`PASS_UNRESTRICTED_NATIVE_CLIENT`, healthy TCP, original gameplay, natural overlap,
native persistence/readback/deadline, and clean first/restart shutdown.

## H. 8/8 results

`run/stage5/controls-client8-c/report.json`: the same four configurations and all
five exact comparisons PASS at the original 8/8 distances. **138 ordered events**
each; tracker calls A=515/B=187/far=323; saved button delay **186 ticks**.
Original packet kind, recipient, payload, order, native saved arrays and restart
state remain strict. Natural original bodies overlap at workers 2 and 4, before
and after reload; this client fixture has two independent furnace regions.

Exact comparison digest:
`81D6486773E18ABF9DEF82EF6CB181598CCCECC98CD4D345368B971ABC3C154B`.
Separate normal tracker/ID run: `run/stage5/unrestricted-client-4-8-final/` —
`PASS_UNRESTRICTED_NATIVE_CLIENT` with the same native health/lifecycle requirements.
Neither unrestricted run is forced to match the reference's wall-clock packet timing.

## I. Churn / topology results

`run/stage5/controls-native-final/`: frozen Stage 4 and candidate 1/2/4 PASS, as do
all five control comparisons. Per configuration: **800 native ticks, 128 furnaces,
102,400 original callbacks, 204,800 ordered HEAD/RETURN observations, 512 completed
smelts, 12 exact observation cuts**. Three churn rounds perform three merges and
three splits, **12 actual native block-entity replacements** and **24 queued native
reads delivered exactly once after topology publication**. Replacement requires the
old object removed, the new object current, and exact native NBT equality.

Per-object state and callback continuity, current owner, changed generation, save
MCA and zero fault counters are mandatory. Actual natural 2/4-worker overlap passes.
Canonical digest: `6236F03C0743641685D95C8A07C90208EAD2C954F75BBB87ED18B88B861BC120`.
The model suite separately proves a busy region does not stall independent queued
work, capacity rejection precedes body execution, and save/topology gates preserve
current authority. Concentrated benchmark demand exercises finite hot-owner load.

## J. Failure / shutdown

Final parallel evidence: `run/stage5/adverse-final-2-b/` and `adverse-final-4/`.
The injected exception is the original object
`IllegalStateException("phoenium expected installed regional callback failure")`.
Another native furnace body holds a distinct legitimate claim when it is thrown;
an original native IOWorker write is also held. The probe observes the barrier,
releases the peer, requires its original return before failed stop, then releases
the native write only after stop is waiting for it.

Both modes record: one callback failure, one peer return, expected failure identity
true, one selected native write, one successful write outcome, zero write failures,
one pending save at failure, one failed-stop settlement acknowledgement, zero pending
saves afterward, and **15/15 cleanup entries** across resource sets **3/3/3/4/1/1**.
`orderedWaitWriteCleanup=true`; natural exit 0; elapsed 6.657 s (two) / 6.578 s (four).
No watchdog, forced process termination or forced claim/state clearing contributes
to PASS. Failed-stop `durablePersistenceClaim=false` is deliberate: settlement does
not promise that every unsaved world effect became durable after an injected failure.

The original one-worker Stage 4 adverse regression also passes on this same JAR:
`run/stage4/adverse-final-20260908-180449/acceptance-manifest.json` and
`run/stage5/original-adverse-final-validation.json`. Its original exact pending-write,
failure-identity, acknowledgement and 15-resource cleanup assertions remain in force.

## K. Benchmark results

**24 runs completed: three fresh-process repeats × two layouts × four configurations.**
Every run has 1,200 warmup ticks followed by five samples of 1,200 ticks: 6,000 measured ticks,
30,720 measured smelts and 6,144,000 offered native furnace ticks per run. Each layout/mode
therefore has 18,000 measured ticks and 92,160 smelts. All 30 exact state comparisons and
all 24 native MCA validations pass. Configuration order rotates between repeats; no build,
other Minecraft server or competing verification campaign runs during measurement.

Host: AMD Ryzen 7 9800X3D (8 physical / 16 logical cores), 33,376,534,528 bytes physical
RAM (31.085 GiB), Samsung SSD 990 PRO with Heatsink 2TB NVMe. OS: `Windows-11-10.0.26200-SP0`.
Runtime pins are in B; every server uses `-Xmx2G`, native G1 defaults and GC logging.
The disposable flat world uses seed 444444, peaceful difficulty, no connected players,
1,024 original furnaces, 36 native forced chunks, 5/5 view/simulation distances and
callbacksPerRegion=256. Four physical cohorts use centers 0/10/20/32; distributed maps
them to four owners, concentrated merges them into one. No artificial body pause,
extra redstone load, world edit or pregeneration campaign is added. Native sprint
runs the offered ticks uncapped by normal 20 TPS. Both body and callback histories are off.

Elapsed is the mean sum of the five measured intervals per run; throughput is completed
smelts divided by total measured elapsed. Startup, warmup, final save and shutdown are
excluded. `speedup(N)=candidate-one elapsed/N-worker elapsed`, within each layout.
RSS is the mean measured working set; exact process samples are collected every 100 ms.

| Mode / layout | Workers | Runs | Measured elapsed s/run | Smelts/s | Elapsed speedup | Mean RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| frozen / distributed | 1 | 3 | 68.921 | 445.7 | — | 628.8 |
| candidate / distributed | 1 | 3 | 49.568 | 619.8 | 1.000× | 622.8 |
| candidate / distributed | 2 | 3 | 49.687 | 618.3 | 0.998× | 641.8 |
| candidate / distributed | 4 | 3 | 44.294 | 693.5 | 1.119× | 647.8 |
| frozen / concentrated | 1 | 3 | 48.953 | 627.5 | — | 628.1 |
| candidate / concentrated | 1 | 3 | 48.930 | 627.8 | 1.000× | 627.5 |
| candidate / concentrated | 2 | 3 | 50.127 | 612.8 | 0.976× | 712.1 |
| candidate / concentrated | 4 | 3 | 62.561 | 491.0 | 0.782× | 597.5 |

Frozen rows are serial references; worker speedup is reported for candidate modes.
Pooled native tick distributions below contain 18,000 ticks each. The run range describes
the three independently measured run means, not a confidence interval.

| Layout / mode / workers | Mean ms | p50 ms | p95 ms | p99 ms | Run-mean range ms | CPU cores | Allocation KiB/tick |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| distributed / frozen / 1 | 11.439 | 12.014 | 14.711 | 16.063 | 8.998–14.106 | 1.25 | 4754.4 |
| distributed / candidate / 1 | 8.200 | 7.848 | 9.501 | 10.709 | 7.801–8.903 | 1.46 | 6209.9 |
| distributed / candidate / 2 | 8.219 | 8.006 | 9.488 | 10.615 | 7.891–8.752 | 1.47 | 6166.7 |
| distributed / candidate / 4 | 7.317 | 7.113 | 8.534 | 9.712 | 6.900–7.916 | 1.71 | 6164.4 |
| concentrated / frozen / 1 | 8.102 | 7.981 | 9.358 | 10.631 | 7.049–9.175 | 1.33 | 4659.4 |
| concentrated / candidate / 1 | 8.095 | 8.119 | 9.155 | 10.484 | 7.948–8.205 | 1.47 | 6177.9 |
| concentrated / candidate / 2 | 8.291 | 8.216 | 9.221 | 10.433 | 8.226–8.406 | 1.28 | 6160.9 |
| concentrated / candidate / 4 | 10.368 | 9.222 | 15.200 | 16.313 | 8.443–13.430 | 1.42 | 5919.6 |

CPU cores are process CPU seconds / measured wall seconds, including native/GC/JIT
threads; they are not individual gameplay-worker utilization. Allocation comes from
the child JVM total-thread allocation counter. Mean queue delay includes enqueue to
worker entry and common admission/preflight; maximum queue delay is since startup,
including warmup, not a measured percentile. Shared-service waiting duration and
per-worker CPU utilization are unavailable.

| Layout / workers (candidate) | Mean queue µs | Max queue µs since startup | Original-body counter peak | Original-body time / total tick time |
| --- | ---: | ---: | ---: | ---: |
| distributed / 1 | 276.4 | 4317.1 | 1 | 1.35% |
| distributed / 2 | 650.7 | 6305.4 | 2 | 1.47% |
| distributed / 4 | 1824.8 | 7374.9 | 4 | 2.15% |
| concentrated / 1 | 435.7 | 5148.5 | 1 | 1.50% |
| concentrated / 2 | 451.0 | 4338.0 | 1 | 1.52% |
| concentrated / 4 | 587.2 | 5181.9 | 1 | 1.14% |

The body-time sum is work duration, and can overlap in parallel modes; it is not
a share of elapsed wall time there. Counter peaks are supporting measurement metadata.
Actual natural body/claim interval overlap is proven separately in the same-size
`benchmark-smoke/smoke-validation.json` and installed correctness campaigns.

| Layout / mode / workers | Peak measured RSS MiB | Heap-at-cut range MiB | G1 young count/ms | G1 concurrent count/ms |
| --- | ---: | ---: | ---: | ---: |
| distributed / frozen / 1 | 686.8 | 167.7–325.9 | 549/523 | 516/506 |
| distributed / candidate / 1 | 691.0 | 167.9–333.0 | 710/672 | 691/674 |
| distributed / candidate / 2 | 694.5 | 177.9–319.5 | 657/610 | 644/612 |
| distributed / candidate / 4 | 690.3 | 167.0–317.7 | 641/593 | 618/594 |
| concentrated / frozen / 1 | 677.1 | 211.6–305.6 | 510/484 | 478/463 |
| concentrated / candidate / 1 | 672.6 | 166.9–315.5 | 706/673 | 686/630 |
| concentrated / candidate / 2 | 863.7 | 163.2–439.8 | 517/465 | 394/384 |
| concentrated / candidate / 4 | 668.9 | 169.5–267.9 | 797/730 | 780/720 |

GC totals cover the three measured runs per row; concurrent collector time is not
stop-the-world pause time. Every old-generation count/time is zero. GC logs, heap
committed/nonheap snapshots, total process CPU and raw 100 ms process samples are retained.
Working set samples include brief frozen command gaps between measured samples.

Distributed elapsed speedup is **0.998× at two workers** and **1.119× at four**
against candidate one; efficiencies are 49.9% and 28.0%. Four workers improve aggregate
elapsed by 10.6% and throughput by 11.9%, and improve over two by 12.2% in throughput.
This is modest scaling; no configuration approaches linear speedup. Candidate one
takes 28.1% less distributed elapsed than frozen Stage 4, but is essentially equal
on concentrated work. The reference itself has 18.3% distributed run-mean variation
(population standard deviation / mean), so the oracle comparison is not a universal
performance guarantee and its improvement cannot be attributed to extra workers.

Concentrated elapsed speedup is **0.976× at two** and **0.782× at four**: elapsed
regressions of 2.4% and 27.9% against candidate one. Four-worker run means span
8.443–13.430 ms (21.1% variation). The full slow repeat remains included. With one
current owner, original-body concurrency is exactly 1 regardless of pool capacity.

The main measured limit is the small cost of the admitted branch: original local
bodies consume only 1.35% of candidate-one distributed tick time and 1.50% concentrated.
Admission/preflight, ownership checks and ordered native world work dominate. Additional
threads add queue/offload and synchronization costs. This is consistent with the
concentrated regression and limited distributed scaling; no new correctness defect
or hidden single execution permit is shown. JVM/native phase variation is also visible
in later samples. Detailed attribution beyond the measured counters is not claimed.
The stage closes without a scheduler redesign or a new gameplay parallelization class.

Allocation is a regression worth retaining: candidate one allocates about 30.6% more
bytes per tick than the frozen distributed reference and 32.6% more concentrated.
Mean working sets remain about 598–712 MiB across candidate modes, with a maximum
measured 863.7 MiB in concentrated two-worker runs. These are observed process values
under a 2 GiB heap ceiling, not a minimum-memory or leak-free claim.

Each three-run row records 18,606 shared serial-service entries, except distributed
four records 18,604; every sample cut has 0 held service reservations and 0 new FULL
finishing entries. Entry counts are not contention waiting durations. Initial topology
publication averages 92.3–99.3 ms; final native save receipts 88.3–99.3 ms; native stop
994.7–1,031.3 ms across row means. Exact per-run values are retained in `summary.json`.
Those bounded operations complete; controlled correctness probes separately prove
contention defers progress safely and preserves native save input.

Measurement limits: this is one host and a narrow finite native furnace workload.
Whole-tick time includes ordered native work and admission; elapsed also includes
the initial snapshot/arming overhead. A fixed 1,200-tick warmup does not prove every
JIT or native runtime phase is stationary: several runs become faster in their last
sample. All samples and repeat variance remain included. No favorable subset was
selected. Queue maxima include warmup; service entry counts do not measure service
wait time. Tracing is off but production counters/timestamps and normal test input
checks remain and their cost is included equally where applicable.

Raw evidence: `run/stage5/benchmark-final/report.json`, `samples.json`, `summary.json`,
each run’s native samples/process metrics/GC log/MCA validation, and all 30 comparison
receipts. `prebenchmark-source-freeze-final.json` records the clean source and flags;
`benchmark-process-isolation.json` records the benchmark as the sole Java process.

## L. Comparison methodology

The comparison contract was established before candidate results: exact required
ordered semantics, with only inherited Stage 4 normalization of absolute clock
origin and diagnostic identity/interleaving fields. The Stage 4 comparator is
unchanged. Furnace state order, each object's callback count/clock/phase/causal state,
inventories, timers, output, packet recipients/payload/order and saved delays remain
strict. Independent regional wall-clock body interleaving is evaluated separately,
never sorted into apparent semantic equivalence.

For deterministic client controls, the test harness offers one unchanged original
`ServerEntity.sendChanges` invocation per native world game tick to the three fixture
entities. It records the native private counter before/after without rewriting it,
requires increasing world ticks, exact UUID coverage and identical offered sequences.
It assigns native IDs 340573321, 681146642 and 1021719963 after original NBT load and
before native publication. Their hash inputs are stable; original native tracker
iteration is not sorted. Occupied or published IDs fail, and every actual tracking
event must carry the exact UUID/ID mapping before the original packet validator runs.

The fixture proves completed native unload including pending-unload removal. It uses
native sprint completion at exact world-tick cuts. The far FULL chunk is retained
with a loading-only level-33 native ticket; actual block-ticking eligibility must be
false after the viewport moves. The same original chunk is marked unsaved with the
other fixture chunks at the same final frozen cut, followed by native save/flush.
No saved delay or packet is removed to obtain equality. All these input controls are
test-only; their classes are excluded from production.

Unrestricted 5/5 and 8/8 clients explicitly set `-Pstage5TrackerClock=false`, preserving
normal native tracker cadence and ID allocation while retaining the workload's actual
save/restart and clock-cut assertions. FULL and benchmarks omit the comparison clock.
Independent Sol High static reviews found no actionable issue in the final production
claims/lifecycle implementation or these test input changes; installed gates provide
the separate runtime evidence.

Negative controls reject changed priority, sub-order/ties, payload, recipient/identity,
packet order, callback order, duplicate/missing effects, saved delay, native state,
unknown events, missing FULL/unpack/lifecycle coverage, invalid overlap/claims,
changed tracker clocks and changed native ID/hash-table inputs. Actual copied-JAR
guards test first launch and restart with Stage 3, frozen Stage 4 and candidate files;
Stage 5 candidate mode rejects both frozen artifacts.

Failed raw evidence remains in its original disposable directory. In particular
`controls-client5-b` (delay 186 vs 369), `controls-client5-c` (uncontrolled tracker call),
`controls-client8-a` (different native save cuts), `controls-client8-b` (different
native IDs/packet order), `full-4-e` (test-side legitimate reader rejection) and
`adverse-final-2` (ordinary save pending before arm) remain failed evidence. Input
repairs are documented above; none was accepted by loosening output equality.

## M. Zero-residue health

`run/stage5/final-health.json` verifies **50 positive native lifecycles**: 16
controlled-client first/restarts, 4 unrestricted first/restarts, 4 native controls,
24 benchmark servers and 2 authoritative FULL lifecycles. Every stopped receipt has
equal intercepted/dispatched/executed tick totals and zero inappropriate retained
state. The evidence records every checked field and original log line.

| Relevant state | Final observed value / scope |
| --- | --- |
| Regional leases; duplicate, bypass, fallback, ownership, unleased faults | 0 in all 50 |
| Positive failure barriers; packet recursion; duplicate/producer chunk mutation | 0 in all 50 |
| Queued named dispatcher services; pending saves; completed-but-unacknowledged saves | 0 in all 50 |
| Failed writes, retired failed save sources and failed settlements in positive runs | 0 in all 50 |
| Gameplay admission | closed at all 50 shutdowns |
| Queued envelopes, transitioning runtimes, pending topology transition/convergence | 0 / false in all 50 emitted terminal receipts |
| FULL graphs, returned indexes, generation/structure holders/readers/writers, demand tickets, waiting writers, deliveries | 0 in all 50 |
| FULL pending notifications, facts bytes, consumers, requested preparations, plans, edges, expanded operations | 0 in all 50 |
| Published FULL live records, charged record bytes and uses | 0 in all 50 |
| Entity/player ownership indexes | 0 in all 50 |
| Named service reservations, active services, worlds and environment requests | 0 in both authoritative FULL stopped receipts |
| Addressable entity facts, scoreboard sessions/catchup/uncertain work | 0 in both authoritative FULL stopped receipts |
| Legacy plans/queue/running/cache roots; native producer termination | 0 / terminated in both authoritative FULL stopped receipts |
| Chunk retained graphs/indexes, light batches/occurrences/receipts, POI/metadata/lifecycle/work/custody entries | 0 in both authoritative FULL stopped receipts |
| Active original-body counter and retained history during benchmark observation cuts | 0 active / 0 history entries |
| Worker queue/batch/thread counts after stop | No separate installed counter; scheduler tests prove zero queue/threads and joined batches; all accepted processes exit naturally |
| Post-stop dynamic runtime-map count and separate stale-inbox/mailbox count | Not independently exported; terminal queued count covers accepted runtime envelopes; dynamic owner resolution is tested, no numerical count is invented |

Configured worker count 4, controlled worlds 3, completed captures/publications/producer
releases, admitted/executed packet totals and other cumulative counters remain nonzero
by design. They are historical work or configuration, not live custody. The legacy
diagnostic scheduler label is STOPPING after completed healthy stop; runtime lifecycle
is STOPPED and native processes have exited. No forced clearing turns unfinished work
into a zero counter. Adverse runs are separate: exactly one intentional failure and
one failed-stop acknowledgement are expected, with live saves/services/leases 0 and
15/15 resources closed. Optional body history retains 4,096 completed intervals when
enabled; it is bounded evidence, not admitted work.

## N. Deferred performance work

The parallel scope is intentionally narrow. Additional original callback classes,
broader block-entity/entity gameplay, automatic topology partitioning, hot-region
balancing, work stealing, lower-overhead eligibility/queue instrumentation, deeper
allocation reduction and platform tuning are not supplied by Stage 5. The roadmap
assigns required-stack audit to Stage 6, proven remedies to Stage 7, complete stack
validation to Stage 8, broader native optimization to Stage 9 and Android tuning to
Stage 10. Benchmark limitations are evidence for later work, not an authorization
to redesign the scheduler at closure.

No claim follows about Folia superiority, required-mod compatibility, Android player
capacity, low-RAM deployment or eight-worker scaling. Default one-worker behavior is
preserved; users must explicitly configure additional workers.

## O. Stage 6 handoff

Stage 6 may rely on the exact frozen JAR/source, bounded original gameplay execution,
one-worker control path, accepted scope and this report's evidence. It must preserve
ownership and claim lifetime, no same-owner conflicting execution, Stage 4 required
observable semantics, native persistence/deadlines and failure/shutdown guarantees.
Its work starts with the required-stack compatibility audit under the existing roadmap;
boot alone is never compatibility proof. This session stops after Stage 5 closure:
no compatibility mods, forks, Panda adaptation or Android work were begun.

The lead retained the selected configuration and owned integration and every live
campaign. Tool-selected helper settings were Luna Low for bounded read-only tasks
and Sol High for bounded independent review, at most one optional active helper,
with no recursive delegation. No worker performed an independent competing build or
live campaign. Per-worker token accounting is unavailable. Actual main-window telemetry
was 31% used / 69% remaining initially; 57% used / 43% remaining at final verification; reserve 7% used, unchanged. These are
shared account readings, not a claim of per-task token consumption. No extra credits,
usage resets, paid API calls, account changes, remote pushes or external publication.

## P. Recovery / reproduction

All commands run from the repository above using the pinned Java/runtime and Python
3.12 with psutil. Read `docs/AGENT_MINECRAFT_TESTING.md` before live work. Use fresh
output names; the scripts refuse existing evidence directories. Restore the local
freeze in a separate checkout if needed; do not reset or rewrite prior freezes.

Key commits: `6cbb404` bounded claims; `c8960f202e10028c5a2b624af5911ba43b9dc85e`
native access/publication; `0aab8e2` retained preflight/crash/failed stop;
`5de466b` recipe type buckets; `5aeb2fe` dynamic current-owner authority;
`0c1bb43` exact native sprint finalizer; `36c7270` legitimate FULL reread;
`cc6d0ba` completed native pending unload; `fde62ff` tracker input clock;
`a92bf12` native non-ticking save cut; `d8cde01` pre-publication fixture IDs;
`8cc210f` adverse setup flush. Final freeze: `STAGE5_PARALLEL_SCALING_FROZEN`.

```powershell
# Complete build/test and harness preparation; build includes the complete test suite.
.\gradlew.bat build fullRegistrationHarness fullAcceptanceHarness adverseStopHarness stage4AcceptanceHarness prepareStage3ClientServer compileClientGameTestJava --no-daemon --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
python -m unittest discover -s scripts -p 'test_compare_stage*.py' -v

# Authoritative native FULL, original fresh + persisted reload.
$env:PHOENIUM_DIAGNOSTIC_SEED='-5846470989536090341'
.\gradlew.bat runStage3FullAcceptance -Pstage5=true -Pstage5Workers=4 --no-daemon --console=plain

# Four configurations and five strict comparisons per client distance.
python scripts/run_stage5_controls.py --mode clients --distance 5 --output run/stage5/reproduce-client5
python scripts/run_stage5_controls.py --mode clients --distance 8 --output run/stage5/reproduce-client8

# Unrestricted client acceptance: use a new run ID/output per distance; set both to 8 for 8/8.
.\gradlew.bat runStage4ClientGameTest -Pstage5=true -Pstage5Workers=4 -Pstage5TrackerClock=false -Pstage4RunId=reproduce-normal5 -Pstage4ServerDirectory=run/stage5/reproduce-normal5 -Pstage4EvidenceDir=run/stage5/reproduce-normal5/evidence -Pstage4ViewDistance=5 -Pstage4SimulationDistance=5 -Pstage4PacketTrace=true --no-daemon --console=plain

# Native four-configuration churn/state/causal callback comparisons and MCA verification.
python scripts/run_stage5_controls.py --mode native --output run/stage5/reproduce-native
python scripts/run_stage5_gameplay.py --output run/stage5/reproduce-adverse2 --workers 2 --trace --adverse --furnaces-per-region 32
python scripts/run_stage5_gameplay.py --output run/stage5/reproduce-adverse4 --workers 4 --trace --adverse --furnaces-per-region 32
python scripts/run_stage4_adverse_stop.py

# The control runner invokes these strict comparators and native persistence validators.
python scripts/compare_stage5_client.py --reference run/stage5/controls-client8-c/clients-frozen-1-8 --candidate run/stage5/controls-client8-c/clients-candidate-4-8 --output run/stage5/reproduce-client-comparison.json
python scripts/compare_stage5_gameplay.py --reference run/stage5/controls-native-final/native-frozen-1-5 --candidate run/stage5/controls-native-final/native-candidate-4-5 --require-overlap --output run/stage5/reproduce-native-comparison.json

# Run alone; 3 repeats, two layouts, four modes, 1200 warmup + 5*1200 measured ticks.
python scripts/benchmark_stage5.py --output run/stage5/reproduce-benchmark
.\gradlew.bat stage5ArtifactIdentityChecks -Pstage5IdentityOutput=run/stage5/reproduce-identities --no-daemon --console=plain
python scripts/audit_stage4_packaging.py --jar build/libs/phoenium-0.2.0-stage2.jar --output run/stage5/reproduce-packaging.json
```

Final receipt/index: `run/stage5/final-artifacts/manifest.json`.
Original logs, installed identity receipts, exact comparison results, body intervals,
MCA copies, benchmark per-tick/resource samples and failed attempts remain under the
paths above. Local native-source inspection informed this independently authored
implementation; no upstream gameplay code was imported. See `SOURCE_PROVENANCE.md`.
