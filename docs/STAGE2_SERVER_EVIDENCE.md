# Stage 2 dedicated-server evidence

## Environment

| Layer | Actual value |
| --- | --- |
| Minecraft | 26.2 |
| Java | Temurin 25.0.4.1 |
| Fabric Loader | 0.19.5 |
| Fabric API modules installed for the server | `fabric-api-base` 2.0.4+ece063239e; `fabric-lifecycle-events-v1` 4.1.4+29b6eb019e |
| Phoenium | 0.2.0-stage2 |

The disposable server is `.stage2-server-live1` (ignored from Git) and has no user
world data. Its `logs/latest.log` contains the original live observations. It uses
local RCON only to send `stop`; no external service or credential is involved.

## Metadata and artifact review

The assembled regular JAR contains `fabric.mod.json`, `phoenium.mixins.json`,
`LICENSE_phoenium`, and only `dev.phoenium` production classes. It has server-only
environment metadata, one dedicated-server entrypoint, no client entrypoint, no test
classes, and no reference source trees. The metadata depends on Fabric Loader 0.19.5+,
Minecraft 26.2, Java 25+, and the exact Fabric lifecycle module used by the entrypoint.

## Lifecycle results

Both normal repetition runs reached Fabric's ready marker, logged the Phoenium
entrypoint once and live runtime start once, performed controlled ticks, accepted RCON
`/stop`, logged `RUNNING -> QUIESCING -> STOPPING` through the scheduler APIs, and
exited without a remaining Java server process. The failure run recorded
`RUNNING -> FAILED`, held the hard barrier, and still accepted controlled shutdown.

No Phoenium background worker is created in Stage 2: the one serial execution lane is
the server-thread lease/context dispatch required to preserve vanilla's thread affinity.
Accordingly there is no Phoenium thread leak, while diagnostics report exactly one
simulation lane and parallel gameplay OFF.

## Reproduction

From the repository root:

```text
./gradlew.bat clean build --no-daemon
```

Install the output JAR with Fabric Loader 0.19.5 and the two Fabric API module JARs
listed above in a disposable Minecraft 26.2 server. Accept the EULA only in that
disposable directory, launch `java -Xmx1G -jar fabric-server-launch.jar nogui`, wait
for `PHOENIUM_STAGE2 started`, allow a bounded observation, then issue `/stop` from the
local console or local RCON. The expected final normal marker has equal nonzero
`intercepted`, `dispatched`, and `executed` values, with zero duplicate, bypass,
fallback, ownership-violation, unleased, and barrier values.

For the development-only barrier test, add:

```text
-Dphoenium.test.failLiveTickAt=10
```

The expected final marker has `scheduler=FAILED`, `dispatched=10`, `executed=9`, one
barrier, and zero fallback/bypass. It must never resume normal vanilla level ticking.
