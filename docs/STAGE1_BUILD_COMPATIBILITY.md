# Stage 1 build compatibility correction

## Reproduced toolchain behavior

The locked build tuple was evaluated with Java 25, Gradle 9.5.1, Fabric Loom 1.17.20,
and `com.mojang:minecraft:26.2`. Declaring `mappings loom.officialMojangMappings()`
fails during Gradle configuration with:

```text
Cannot use Mojang mappings in a non-obfuscated environment
```

Loom detects the 26.2 artifact as already official-named/non-obfuscated. This is
consistent with the Stage 0 intent to use official Mojang names, but contradicts the
older template-shaped expression that explicitly applies the mapping layer.

## Narrow correction

`build.gradle` deliberately does not call `officialMojangMappings()`. Loom also does
not create `modImplementation` remap configurations in this mode, so the loader and
Fabric API are declared on Gradle's ordinary `implementation` configuration. The
resolved Minecraft artifact remains official-named; no Yarn, intermediary, or legacy
mapping dependency is introduced. This is a build-tool adaptation, not an architecture
or runtime decision. The `BuildSkeletonTest` checks that the build file retains no Yarn
mapping declaration and that metadata remains server-only.
