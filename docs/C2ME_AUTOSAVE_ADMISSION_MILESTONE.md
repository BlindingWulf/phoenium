# C2ME autosave serial-admission milestone

Date: 2026-09-11

Scope: Stage 8 C2ME lifetime prerequisite only. This accepts provider-driven autosave admission. It does **not** accept ReadFromDisk, cancellation, unload/replacement, restart, W1/W4 coexistence, or removal of the production C2ME safety gate.

## Cause and repair

C2ME enhanced autosave reaches `ChunkMap.saveChunkIfNeeded` from its server-thread idle task. That path does not pass through MinecraftServer's ordinary `saveAllChunks` wrapper, so Phoenium's `RegionalSaveBinding.select` previously reached `FullFinishingServices.requireWorld(..., UNSAVED)` with no service scope and failed closed.

The shared `saveChunkIfNeeded` wrapper now calls `FullFinishingServices.ensureSerial(server, Phase.SAVE, body)` whenever regional save coordination is active. `ensureSerial`:

- rejects any entry after regional ownership has begun;
- validates and reuses an existing same-server service scope;
- rejects a scope belonging to another server;
- otherwise opens exactly one owner-free SAVE scope through the existing serial ledger;
- closes a newly-opened scope on success or exceptional exit.

The integration remains on the stable vanilla save boundary and does not target C2ME's injected autosave method.

## Verification

- Full deterministic suite: **98 suites / 906 tests / 0 failures / 0 errors / 0 skips**.
- `git diff --check`: PASS apart from line-ending warnings.
- Production JAR SHA-256: `4F557203584F9388DBA06AFF3E4A00EF1AABE7D5511502EC33907E09DEAD6051`.
- Disposable candidate SHA-256: `E6DA1052DC1934F0384B49A8EFCCAB44DB79E1635C9F3FD87F514920FBFEE071`.
- Locked C2ME outer artifact remains `0.4.2-alpha.0.43`, SHA-256 `B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B`.
- Local Qwen final hostile review: **ACCEPT** for this milestone only.

## Installed W1 evidence

Run: `run/stage8/entry-20260911/c2me-lifetime-autosave-hardened-w1/`.

The installed server reached `STAGE8_C2ME_GENERATION_LOADED` and stopped with exit code 0. All nine C2ME storage threads stopped. Phoenium shutdown state:

- captures 75 / acknowledgements 75;
- pending 0;
- completed-not-acknowledged 0;
- failed writes 0;
- failed settlements 0;
- retired failed sources 0;
- intercepted / dispatched / executed 276 / 276 / 276;
- duplicate, bypass, fallback, ownership violations, unleased work, and barriers all 0.

No unexpected server exception or `ChunkProtocolException` appears in the installed log. The console wrapper reports `UNRESOLVED` solely because its readiness poll rejects the configured non-read-only loaded-chunk query; this is separate from server runtime acceptance.

## Next lifetime boundary

ReadFromDisk/load, async serialization cancellation, downgrade/unload, status-slot replacement, exceptional completion, stop, and restart/reload remain open. Cancellation must remain cancellation rather than becoming a failed write, and provider producer/slot identities must not be replayed or replaced by Phoenium.
