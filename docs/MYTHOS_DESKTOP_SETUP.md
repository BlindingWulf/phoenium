# Mythos desktop setup (no phone, no ADB)

Status labels: **VERIFIED** = run on 2026-10-27 from a fresh clone; **CI** = verified by the repository's GitHub
Actions on Linux and Windows; **DOC** = from the repository docs, not re-run here.

## Phoenium (https://github.com/BlindingWulf/phoenium)

Requirements: Git, Temurin JDK 25 (25.0.4.1 tested), Python 3.12+, internet access to Maven, Fabric, Mojang and
Modrinth. No global Gradle (wrapper 9.5.1). First build downloads several GB.

```bash
git clone https://github.com/BlindingWulf/phoenium.git
cd phoenium
python3 scripts/fetch_test_fixtures.py      # writes into build/ AND ../C2ME-locked-settlement/ (outside the checkout)
./gradlew build --no-daemon                 # Windows: .\gradlew.bat build --no-daemon
sha256sum build/libs/phoenium-0.2.0-beta.1.jar
```

- **VERIFIED (Windows 11, `main` at `d03441c`):** 2060 tests, 0 failures, 2 skipped; 1 min 12 s with a warm Gradle
  cache; `phoenium-0.2.0-beta.1.jar` 2,500,473 bytes, SHA-256 `09530B8B…037B`, identical to the release asset.
- **CI:** Linux builds and passes too, but its jar hash differs (likely line endings in resources).
- Run the fixture script again after `gradlew clean`.

### Local test server (DOC; see `docs/MYTHOS_START_HERE.md` sections 6-7)

1. Fabric server launcher: `https://meta.fabricmc.net/v2/versions/loader/26.2/0.19.5/1.0.1/server/jar`, saved as
   `fabric-server-launch.jar`. First run downloads the Mojang server; accept the EULA yourself. Never commit it.
2. `mods/`: Fabric API 0.159.0+26.2, the settlement2 C2ME jar from the Phoenium release, your Phoenium jar. Optional
   L5 stack: `docs/STACK_LOCK.json` (official URLs and SHA-256).
3. `server.properties`: `online-mode=false`, `enforce-secure-profile=false`, `server-ip=127.0.0.1`, a new `level-name`
   per run, blank seed.
4. Start with **all three** Mojang host flags (with two, 26.2 ignores them and uses the real hosts):

```bash
java -Xmx4G -Dphoenium.test.diagnostics=true -Djava.net.useSystemProxies=false \
  -Dminecraft.api.session.host=http://127.0.0.1:9 -Dminecraft.api.services.host=http://127.0.0.1:9 \
  -Dminecraft.api.profiles.host=http://127.0.0.1:9 -jar fabric-server-launch.jar nogui
# engine mode: add -Dphoenium.engine.tiles=true -Dphoenium.engine.workers=5 -Dphoenium.engine.schedule=dag
```

PowerShell: quote every `-D` argument. Stop with `stop`, never by killing the process.

5. Before connecting anything, check `logs/latest.log` for `Environment[...name=properties]` (not `PROD`),
   `PHOENIUM_STACK supported`, `Done`, and the `PHOENIUM_STAGE2 started ...` line (and `PHOENIUM_COMPAT` in engine mode).

Metrics: `/tick query` (TPS, P50/P95/P99 MSPT), `-Xlog:gc*:file=gc.log`, `-XX:StartFlightRecording=...`, `jcmd`.

## Phoenix (https://github.com/BlindingWulf/phoenix-android)

Requirements: Temurin 25, Android SDK platform 36 + build-tools 36.0.0 + platform-tools, `gh` CLI (for the AARs).
Windows: clone into a short path with `core.longpaths=true` and `core.autocrlf=false`.

```bash
git -c core.longpaths=true -c core.autocrlf=false clone https://github.com/BlindingWulf/phoenix-android.git
cd phoenix-android/android-stage10a
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"   # or python tools/setup_sdk.py
echo "sdk.dir=$ANDROID_HOME" > local.properties
gh release download v0.5.0-beta.1 -R BlindingWulf/phoenix-android -p 'netbird-android-v0.80.0-*.aar' -D app/libs
grep '\.aar$' tools/netbird/SHA256SUMS | sha256sum -c -
./gradlew :core:acceptance :core:provisionAcceptance :core:worldAcceptance \
          :core:networkingAcceptance :core:netbirdAcceptance :core:playitAcceptance --no-daemon
./gradlew :app:testDebugUnitTest --no-daemon
```

- **VERIFIED (Windows, no SDK installed):** the six `:core` harnesses pass (45 s). `:app:testDebugUnitTest` needs the
  SDK and the AARs. `:core:test` fails ("no tests discovered"): `:core` has only `main()` harnesses.
- **CI:** the full recipe above is green on Linux and Windows at the current `main` (342 app unit tests).
- APK packaging needs the runtime, playit and telemetry inputs (`docs/DEVELOPMENT_SETUP.md` section 2); not needed for
  backend work.

## What needs the maintainers' phones

Android memory/LMK, foreground-service survival, Doze, screen-off behaviour, process-group kills, big-core scheduling,
real VPN/TUN, thermal throttling, engine mode on phones, the Bedrock real-client checklist, two-client voice and legacy
Java via Via. Put a short checklist in the PR for the maintainers' agents instead.
