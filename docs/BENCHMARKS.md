# Benchmark plan

There are **no Phoenium benchmark results** in Stage 0. No build or server was run.

## Required measurement protocol

Every later benchmark report must record:

| Category | Required data |
| --- | --- |
| Environment | Minecraft/Fabric/Java/mod versions, CPU, memory, JVM flags, storage, operating system, world seed, simulation distance, view distance, and test duration. |
| Scheduler | Worker count, topology radii, tick budget policy, queue policy, active-region count, and failure-barrier events. |
| Workload | Player count, entity count/types, chunk activity, redstone/block-event load, world-edit/pregeneration use, and exact scripts or replay traces. |
| Outcomes | MSPT distribution, TPS, p50/p95/p99 tick time, throughput, queue latency, topology transition latency, GC, CPU, allocation, and save latency. |
| Correctness | Ownership violations, stale-envelope reroutes, lease duration, duplicated/missing tick counters, transfer failures, and comparison to serial oracle. |

## Comparison sequence

1. Vanilla/Fabric 26.2 baseline, with the stated target set only.
2. Phoenium integration disabled through explicit startup configuration, if this becomes a supported diagnostic mode.
3. Phoenium serial-first mode with one worker.
4. Phoenium bounded parallel mode at each tested worker count.
5. Target compatibility combinations, beginning with C2ME only after its adapter gate.

The same world, workload, warmup, duration, JVM, and collection method must be used across a comparison. A throughput improvement does not count as success if deterministic state comparison, ownership diagnostics, save, or shutdown checks fail.

## Guardrail metrics

The benchmark harness must make synchronization regressions visible:

- topology gate hold time and contention;
- time waiting for a lease boundary;
- owner-gateway queue age and stale-route count;
- task execution outside a lease, expected to remain zero;
- number and duration of any global service operation;
- blocking calls attempted from an owner context, expected to remain zero;
- hard failure barriers and any attempted fallback, expected to remain zero.

