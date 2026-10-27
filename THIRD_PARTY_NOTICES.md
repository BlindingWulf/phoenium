# Third-party notices

Phoenium itself is GPL-3.0-only (see `LICENSE`). The Phoenium jar contains no bundled third-party code.

## Redistributed with the release
- **C2ME-fabric** (patched build `c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar`), Copyright (c) RelativityMC /
  ishland and contributors, **MIT License** - https://github.com/RelativityMC/C2ME-fabric . Upstream base: C2ME
  0.4.2-alpha.0.43 for 26.2 (Modrinth `VSNURh3q` / `jSMMstCy`, SHA-256
  `B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`). Phoenium's changes (a settled chunk-request path
  and three recompiled FlowSched classes) are in `docs/patches/c2me-root-settlement.patch` and
  `docs/patches/c2me-flowsched-settlement.patch` (FlowSched: https://github.com/RelativityMC/FlowSched , MIT). The jar
  keeps upstream's nested libraries and their own license files; check upstream for their terms.

  License texts: `third_party/C2ME-LICENSE-MIT.txt` and `third_party/FlowSched-LICENSE-MIT.txt`. The C2ME monorepo
  also has an All-Rights-Reserved module (`c2me-opts-accel-opencl`); it is **not** in this jar (verified by listing the
  jar's nested modules). Nested libraries shipped by upstream C2ME (asyncutil, caffeine, jctools, rxjava, reactive-streams,
  night-config core/toml, exp4j, mixinsquared) keep their own licenses (Apache-2.0 / MIT / LGPL-3.0 per upstream).

## Build-time dependencies (downloaded by Gradle, not redistributed)
Fabric Loom, Fabric Loader, Fabric API (Apache-2.0), JUnit (EPL-2.0), Gradle wrapper (Apache-2.0,
`gradle/wrapper/gradle-wrapper.jar`). Minecraft 26.2 is downloaded from Mojang by Loom under the Minecraft EULA; no
Minecraft code is in this repository.

## Tested mods (NOT redistributed; download from the official URLs in `docs/STACK_LOCK.json`)
Lithium (LGPL-3.0), ScalableLux (LGPL-3.0), ServerCore (MIT), Clumps (MIT), Chunky (GPL-3.0), Geyser / Floodgate (MIT),
ViaVersion / ViaBackwards / ViaFabric (GPL-3.0), No Chat Reports (WTFPL), EconomyCraft (GPL-3.0), LuckPerms (MIT),
Essential Commands (MIT), Open Parties and Claims (LGPL-3.0), Forge Config API Port (MPL-2.0), Universal Graves and
Polymer (LGPL-3.0), Ledger (LGPL-3.0), Fabric Language Kotlin (Apache-2.0).
**All Rights Reserved - link only, never re-upload:** Simple Voice Chat, VillagerBased PlayerShops, burger-economy.

## Reference harness
`tools/reference-harness/` is Phoenium project code (GPL-3.0-only). It downloads nothing by itself and contains no
third-party binaries; ViaProxy, mineflayer and bedrock-protocol must be obtained from their own projects.
