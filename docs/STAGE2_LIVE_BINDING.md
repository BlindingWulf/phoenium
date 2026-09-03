# Stage 2 live binding

## Accepted binding

| Item | Value |
| --- | --- |
| Target class | `net.minecraft.server.MinecraftServer` |
| Target method | `protected void tickChildren(java.util.function.BooleanSupplier)` |
| JVM descriptor | `(Ljava/util/function/BooleanSupplier;)V` |
| Intercepted invoke | `net.minecraft.server.level.ServerLevel.tick(java.util.function.BooleanSupplier)` |
| Invoke descriptor | `(Ljava/util/function/BooleanSupplier;)V` |
| Mechanism | Required Mixin `@Redirect`, `defaultRequire: 1` |

`MinecraftServer.tickChildren` has the one direct `invokevirtual ServerLevel.tick` in
its iteration over loaded server levels. The redirect is therefore reached once for
each vanilla level-tick dispatch, before the original receiver call can run. It invokes
`PhoeniumLiveRuntime.dispatchLevelTick`, which submits a typed `LiveLevelTick` envelope
to the P1 `OwnerGateway`; `RegionScheduler.runOne` acquires a `RegionLease`, enters
`OwnershipContext`, and calls the dedicated typed live executor. Only that executor
invokes the original `ServerLevel.tick` receiver call. A failed scheduler consumes the
redirected call, so there is no vanilla fallback execution.

## Candidates considered

| Candidate | Decision | Reason |
| --- | --- | --- |
| `ServerTickEvents.START_SERVER_TICK` / `END_SERVER_TICK` | Rejected | Server-wide observational events cannot replace or suppress the original level invocation, so they would create an extra path rather than exactly-once control. |
| `ServerTickEvents.START_LEVEL_TICK` / `END_LEVEL_TICK` | Rejected | Also observational around the existing level tick; they cannot run the original operation inside the P1 lease. |
| `MinecraftServer.tickServer(BooleanSupplier)` | Rejected | Exactly once per server cycle, but too broad: it includes connections, player list, clocks, functions, tickables, and all levels. |
| `ServerLevel.tick(BooleanSupplier)` head injection | Rejected | It provides no narrow way to make a scheduler failure suppress the original call without cancellation logic at a less explicit call site. |
| `MinecraftServer.tickChildren` redirect | Accepted | Exact one direct 26.2 call to the controlled operation; replaces rather than observes it. |

## Bytecode and runtime evidence

`javap -c -p` against the exact Loom-resolved 26.2 official-named artifact shows
`MinecraftServer.tickServer(BooleanSupplier)` invokes `tickChildren(BooleanSupplier)`,
and its `tickChildren` bytecode contains the direct call at the level loop:

```text
ServerLevel.tick:(Ljava/util/function/BooleanSupplier;)V
```

Fresh dedicated-server runs used a disposable Fabric Loader 0.19.5 / Minecraft 26.2 /
Java 25 server with the Stage 2 JAR and server-side Fabric lifecycle modules.

| Run | Intercepted | Dispatched | Executed | Duplicate | Bypass | Fallback |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Normal repetition A | 180 | 180 | 180 | 0 | 0 | 0 |
| Normal repetition B | 177 | 177 | 177 | 0 | 0 | 0 |
| Earlier normal observation | 1173 | 1173 | 1173 | 0 | 0 | 0 |
| Injected failure at dispatch 10 | 1131 | 10 | 9 | 0 | 0 | 0 |

The failure observation additionally recorded one hard barrier and a `FAILED`
scheduler. It deliberately kept later level invocations suppressed, then shut down
through an actual RCON `/stop` in the disposable server.

## Scope and risks

The live model creates one real Phoenium region per `ServerLevel` at server start, with
routing key `(world, 0, 0)`. It controls the top-level level invocation only. It does
not independently regionalize any inner tick category or establish that such categories
are safe for parallel execution. Runtime-added levels are fail-closed rather than
allowed through vanilla; Stage 3 must make their ownership lifecycle explicit.

Minecraft's server thread performs the serial operation in Stage 2, but it has no
implicit authority: the direct mutation regression fails without a lease and succeeds
only under scheduler-installed context. No physical Phoenium worker thread is created
at this stage, avoiding an unsafe transfer of vanilla `ServerLevel.tick` away from its
required server thread. The diagnostics' worker count means the one serial scheduler
execution lane, not a main-thread bypass.
