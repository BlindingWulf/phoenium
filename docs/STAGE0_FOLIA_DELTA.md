# Current Folia delta over Ruthenium's era

## Why this matters

Ruthenium is archived. The current Folia 26.2 branch is the first source of truth for regional semantics. The audit identified 31 commits after the baseline immediately before Ruthenium's final development window: Folia baseline eee7128bc810195ad758ea5ace1b72c600896d3b, current audited revision 14b7fee5c866fca9a40ede4a58998fb928140f65.

The list below isolates behavior that changes Phoenium design or regression testing. The other commits include upstream Paper synchronization and must be reviewed again when a binding is chosen.

| Date | Folia revision | Current behavior relevant to Phoenium |
| --- | --- | --- |
| 2026-01-20 | aa37f05 | DimensionDataStorage uses concurrent/atomic loading refinements; global persistence cannot be assumed to have the older access pattern. |
| 2026-04-20 | de00a948 | Server clock reads direct level data game time, avoiding cross-world access. |
| 2026-04-24 | 5b5f8dc | Missing tick-thread checks for entity-interaction packets were fixed. Packet ownership is an active correctness boundary. |
| 2026-07-17 | 602048cb | Teleport acceptance is denied and disconnected when the accepted position lies outside the owning region. |
| 2026-07-17 | 65598524 | Passenger desynchronization detection reconnects the ticket corridor and teleports to a root vehicle. Transfer groups require reconciliation. |
| 2026-08-19 | 95c3f27 | Eigencraft redstone updates and game events receive ownership checks; foreign work is not made safe by fake state. |
| 2026-07-04 onward | multiple | Minecraft 26.2 update and related upstream synchronization require current Mojang mappings and bytecode verification. |

## Design consequences

1. Packet ingress, teleport acceptance, vehicle passengers, game clock reads, redstone, and game events are mandatory first-class ownership boundaries.
2. Any Ruthenium path that predates these fixes is a regression candidate, not a reusable solution.
3. The Folia task scheduler's lease around queued task execution is essential. Ruthenium's queued task path does not take its comparable region ticking lease.
4. Current Folia still uses carefully scoped locking for topology and diagnostics. Phoenium may retain only the narrow semantic role after measuring it; it must not turn that into a world-wide gameplay lock.

