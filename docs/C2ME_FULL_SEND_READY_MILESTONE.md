# C2ME FULL / SEND_READY milestone

Date: 2026-09-11

Stage status: Stage 8 prerequisite engineering only. Formal combined-stack Stage 8 acceptance has **not** started.

## Baseline and provider lock

- Branch: `stage7/scalablelux-persisted-light-repair`
- Pre-milestone committed HEAD: `d3f65abfa00a3a9df373a96827a6f2f90b5b3d5e`
- Locked C2ME outer artifact: `build/stage6-audit-20260909/research/artifacts/c2me/c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar`
- Locked outer SHA-256: `B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`
- Production `fabric.mod.json` still contains the C2ME safety `breaks` declarations.

## Accepted composition

`ServerAccessible` FULL binds the exact provider state and delayed provider publication path to Phoenium lifecycle admission. `ServerAccessibleChunkSending` remains a distinct SEND_READY lifecycle stage in the installed fat C2ME build.

The final SEND_READY integration wraps the generic provider bridge `upgradeToThis(Object, Cancellable)`. That bridge invokes the installed typed notickvd overwrite, allowing notickvd to build its real `Completable.fromRunnable(...).subscribeOn(...)` graph. `C2meFullAccess` fail-closed reflects the exact installed `CompletableSubscribeOn -> CompletableFromRunnable` shape, extracts the original Runnable and scheduler, and rebuilds the same two-node graph while wrapping only the Runnable body in Phoenium ownership admission. There is no production compile-time RxJava dependency and no replacement scheduler.

The provider-binary regression now pins the final installed composition when evidence is present: one notickvd `fromRunnable`, one `subscribeOn`, the generic bridge's Phoenium SEND_READY wrapper, and the MixinExtras bridge back to the typed provider upgrade.

## Deterministic verification

- Focused C2ME tests: **29 PASS**.
- Full deterministic suite: **98 suites, 901 tests, 0 failures, 0 errors, 0 skips**.
- Production JAR build: PASS.
- `git diff --check`: PASS apart from CRLF conversion warnings.
- Production JAR SHA-256: `7B2310322E1027D900B53EDAB27D531D55C867243C43492F0CE881A7B843ED3B`.
- Final local-Qwen hostile review: **ACCEPT**.

## Installed-path proof

Evidence directory: `run/stage8/entry-20260911/c2me-full-send-observed-body-w1/`.

Disposable candidate SHA-256: `08C2990DE958DA8CE873A4DEAFE835996868266A07288914F377473F00201FCF`.

Observer harness SHA-256: `A92816494CBD9BFDC8B373A9FFD7400E173E8D8A9C343F6405C53052E8BD9CE1`.

Observed provider execution:

- C2ME generation `ChunkStep.apply`: 2,422 enter / 2,422 return.
- `ServerAccessible` typed and generic upgrade paths: 76 / 76.
- `ServerAccessibleChunkSending` typed upgrade: 39 enter / 39 return on `c2me-worker`.
- `ServerAccessibleChunkSending` generic bridge: 39 enter / 39 return on `c2me-worker`.
- Actual merged notickvd SEND_READY synthetic Runnable body: 39 enter / 39 return, `owned=39`, first execution on the Server thread under Phoenium RegionId ownership.
- Shutdown diagnostics: intercepted 99, dispatched 99, executed 99, duplicate 0, bypass 0, fallback 0, ownership violations 0, unleased 0, barriers 0.
- Save state at shutdown: captures 2, acknowledgements 2, failed settlements/writes 0, pending 0.

The installed run advanced past FULL/SEND_READY and then failed closed at the next required boundary:

`ChunkMap.saveChunkIfNeeded -> RegionalSaveCoordinator -> RegionalSaveBinding.select -> FullFinishingServices.requireWorld`

with `ChunkProtocolException: world service access has no admitted serial or FULL turn: UNSAVED`.

That failure is the next C2ME save/lifetime integration gate. It is not classified as a FULL or SEND_READY failure.

## Remaining gate

Trace and bind C2ME disk read/load/save/unload, asynchronous serialization, cancellation, provider-slot replacement, terminal/exceptional completion, retained references, restart/reload, and shutdown lifetime. Only after that boundary is accepted should installed W1/W4 coexistence be attempted and the production C2ME safety gate reconsidered.
