# Current FULL / Stage 3 closure — 2026-09-08

The latest user direction is the Stage 3 closure request in attachment
C:/Users/Admin/.codex/attachments/7c97b080-1373-4f6c-84c0-07f0ea145144/pasted-text.txt.
Finish the bounded active work, fix demonstrated correctness failures, prove all gates,
produce the A–L report and stop. No speculative expansion, Stage 4, push, release or PASS tag.
Historical contributor-only stop and approval-pending passages are superseded.

## Authoritative source and owners

Authoritative checkout: C:/Users/Admin/Downloads/Project PHOENIUM/Phoenium, main.
Current integrated executable source: c31b3f4 (production fix 328b95c plus current client parser).
Main was clean before the final verification dispatch. This note is documentation only.

| Owner | Checkout / responsibility |
| --- | --- |
| Root | Phoenium/main: narrow blocker diagnosis, integration, evidence review and closure report |
| /root/sol_services | Phoenium-full-sol: final pinned-source read-only review; accepted implementation integrated |
| /root/luna_harness | Phoenium-full-luna: sole clean build, installed, actual fresh/disk/reload and saved-vault campaign owner |

Supported spawn controls explicitly selected gpt-5.6-sol/high and gpt-5.6-luna/high,
fork_turns=none; their minimal useful assignments verified the controls were accepted.
Runtime model identity metadata is unavailable. Lead retains the user-selected Astra effort.
No recursive delegation, additional Astra, tier override, separately billed API or account change.
At the latest integration boundary account-wide Codex weekly use was 34%, reserve 7%;
per-task/per-worker tokens are unknown. Two reset credits remain unused.

Pinned MC26.2 / Java25 / Loader0.19.5 / Fabric0.159.0+26.2 / Loom1.17.20 /
Gradle9.5.1. One gameplay worker, parallel OFF, view/simulation5, offline PhoeniumTest0.
Disposable local worlds only. Root/Sol do not run competing full builds or servers.

## Latest demonstrated failure and narrow fix

Current failed real-client evidence (old production SHA D27633AEB68D5086061491820555B698B81553AD9113FEBF4B253895B23ABE82):
Phoenium-full-luna/run/full-authoritative/live-1788843598570/full-acceptance.json.
Fresh FULL and real TCP chunk receipt passed, then runtime diagnostics reported
ChunkProtocolException: obsolete admitted publication before save-all flush timed out.
The earlier 4-second save timeout run is preserved at live-1788843351880.
Neither run proves disk/reload or clean shutdown.

328b95c reproduces the exact exception deterministically for fresh and Imposter inputs:
original ticket demotion fails and clears a pending FULL subscriber while the admitted
producer still owns its work. FullPublishedInputs conflated invalid consumer evidence
with cancelled producer authority. admittedPublishing now retains the exact stage claim,
owner/settled/retired guards and original completion; it does not clear invalid or permit
invalid evidence in proposing/matches. Original withdrawn subscriber remains UNLOADED.
Before-fix XML has two exact obsolete-publication failures; after-fix six suites/72 cases
pass (authoritative8, published27, registration28, acknowledgements6, generation shutdown2,
shutdown failure ordering1), zero failures/errors/skips. Evidence is under
run/full-resume/session-20260907/closure-withdrawn-publication-{before,after}.* in main.
Both new cases are connected to installed finishing67/publication4 markers.

1375b59 gives only normal synchronous save-all flush a bounded 30-second response,
and correctly parses final end-of-line teardown fields. c31b3f4 captures and rejects
nonempty runtime failure diagnostics before either lifecycle save. These are harness-only.

## Final gate state and next action

Luna is cherry-picking 328b95c onto its current equivalent parser/probe tree, then running:

1. One clean build and all current probes; expected 690 tests/59 suites. Archive exact XML.
2. Strict installed transformed production probe; finishing67/publication4 plus all prior markers.
3. The exact same production artifact through actual fresh FULL, normal flush and MCA payload
   proof before restart, normal stop, actual disk reload/reconnect, final clean stop.
4. The existing saved-vault reproduction on that same artifact (preserved source world untouched).

Classify any failure before a retry. Do not repeat unchanged live runs or extend timeouts
again without evidence. A production change invalidates installed and complete live gates.

Latest prior installed PASS is transform-20260907-235421, old production D27633...;
registration SHA449B96AABDBA3BA98169ADEA56E5B8FEB8EF92749655FFE81DBA3B5C3CFB50A9.
It is superseded by the current production fix and is not the final gate.
Previous clean result was 688/59; final current 690/59 remains pending verification.

Accepted scope includes direct live component/context refinement, pending command/sign,
same-world original predicates, connected structure references/starts and service/lifetime work.
Cached DataComponents context encoding is explicitly deferred: independent global encoders
can make original getUnchecked wait under R after final validation. No production cache hooks
remain. Cross-world selectors and unknown serializers/predicates remain explicit unresolved
boundaries under the latest closure authorization. See STAGE3_CACHED_COMPONENT_CONTEXT_BOUNDARY.md.

Assembly note: run/full-resume/session-20260907/closure-narrative.md (ignored, not final PASS).
After gates, write docs/STAGE3_CLOSURE_REPORT.md sections A–L with exact artifacts/evidence,
refresh this note and the acceptance ledger, commit documentation and STOP if all criteria pass.
