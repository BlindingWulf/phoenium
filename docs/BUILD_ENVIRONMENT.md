# Build environment lock

This is the implementation baseline selected during Stage 0.  No Phoenium build was created or run in this stage.

| Layer | Locked initial value | Evidence and reason |
| --- | --- | --- |
| Minecraft | 26.2 | Official Java Edition version manifest; inspected server inner jar contains the current Mojang-named server classes. |
| Java | 25 | Minecraft 26.2 declares Java runtime major version 25.  The audit host has Temurin OpenJDK 25.0.4.1. |
| Fabric Loader | 0.19.5 | Latest stable 26.2 loader entry in Fabric metadata at audit time. |
| Fabric API | 0.159.0+26.2 | Latest 26.2 artifact in Fabric Maven metadata at audit time. |
| Fabric Loom | 1.17.20 | Released Loom version present in Fabric Maven metadata. |
| Gradle wrapper | 9.5.1 | Conservative initial wrapper: Fabric's 26.2 example template uses 9.5.1 and Loom 1.17's published plugin metadata requires Gradle plugin API 9.5.0.  Gradle 9.7.1 was current but not selected without an integration check. |
| Mapping namespace | Official Mojang mappings | Fabric's 26.1-and-later guidance uses `net.fabricmc.fabric-loom` and official mappings.  Yarn-era names must not be carried forward. |
| Delivery | dedicated server only | The future `fabric.mod.json` must set `id: phoenium` and `environment: server`. |

## Mapping audit

The verified 26.2 server inner jar exposes the following important official names:

| Earlier Ruthenium-era target | 26.2 audit target or redesign boundary |
| --- | --- |
| `ServerWorld` | `net.minecraft.server.level.ServerLevel` |
| `ServerChunkManager` | `ServerChunkCache`, `ChunkMap`, `ChunkHolder` |
| `WorldTickScheduler` | `LevelTicks`, `LevelChunkTicks` |
| `NetworkThreadUtils` | `net.minecraft.network.PacketProcessor`; packet ingress also involves `ServerGamePacketListenerImpl` |
| `PistonHandler` | `PistonStructureResolver` |
| `RedstoneController` | `RedstoneWireEvaluator`, `NeighborUpdater` |
| old entity manager targets | `PersistentEntitySectionManager`, `EntityTickList`, `ServerEntity` |

Other audited classes include `MinecraftServer`, `ServerPlayer`, `Connection`, `GameEventDispatcher`, `Entity`, `TickingBlockEntity`, `ServerClockManager`, `LevelChunk`, and `ChunkTaskDispatcher`.

This table is an audit aid, not a list of approved mixin targets.  Each binding requires an implementation-stage bytecode and dedicated-server boot check against the exact dependency lock.

## Required future build shape

Stage 1 must begin from the current Fabric 26.2 template and use the modern plugin id:

```text
plugins {
  id 'net.fabricmc.fabric-loom'
}
```

It must pin every row above, declare a server environment, and avoid client entrypoints, client mixin configuration, and client datagen unless a later approved feature creates a concrete server-side need.

## Source links

- [Fabric's 26.2 setup guidance](https://docs.fabricmc.net/develop/getting-started/setting-up)
- [Fabric Loom guidance](https://docs.fabricmc.net/develop/loom/)
- [Fabric Maven metadata](https://maven.fabricmc.net/)
- [Minecraft version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)

