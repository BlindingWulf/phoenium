# Stage 7 Structure Layout Optimizer policy result

Updated: 2026-09-10

## Result

The locked Structure Layout Optimizer (SLO) `1.1.4+26.1-fabric` build is **excluded from the strict vanilla-seed-parity production profile pending an upstream fix or an explicit deployment decision to accept changed jigsaw layouts**.

This is an upstream/provider result, not a Phoenium regression and not a Phoenium repair target.

No Phoenium production code changed for this result.

## Frozen reproduction

- Locked artifact: `structure_layout_optimizer-1.1.4+26.1-fabric.jar`.
- Locked SHA-256: `189A4356CC34B34156EC18DD22B9D63E617F12DDDFE31729C640FCC62B5AD1FC`.
- Release metadata explicitly includes Minecraft `26.2` support.
- Seed `444444`, trial chamber at the frozen audit target:
  - native/no-SLO controls: `338` pieces, NBT SHA-256 `552c736c...759fe`;
  - Phoenium + SLO W1: `273` pieces, NBT SHA-256 `00c9bd5e...7b89c`;
  - Phoenium + SLO W4: the same `273` pieces and SLO NBT hash;
  - Fabric + SLO without Phoenium: the same `273` pieces and SLO NBT hash.
- Every retained SLO configuration has `deduplicateShuffledTemplatePoolElementList=false`.

The standalone Fabric+SLO control is decisive for attribution: the changed layout does not require Phoenium.

## Intended upstream semantics

SLO's generated config says `deduplicateShuffledTemplatePoolElementList=true` intentionally changes the structure layout and breaks vanilla seed parity for more performance. The locked audit used `false`.

Upstream README text describes the default optimizations as attempts to make vanilla jigsaw logic faster while preserving layout. It calls the optional deduplication mode the explicit seed-parity tradeoff.

In GitHub issue #21, `Clarification on vanilla parity`, the maintainer stated that as far as they could tell vanilla structure layout was identical with SLO enabled and asked users to report any missed difference. This is an intended-default-parity statement, although it is not an absolute formal guarantee.

GitHub issue #27 later requested that `1.1.4` be marked compatible with Minecraft `26.2` after a quick fly-around and generation of a few structures. That compatibility marking did not perform or claim strict seed-parity verification.

Therefore the retained `338 -> 273` result with the explicit parity-changing option disabled is **unexpected under SLO's documented default intent**.

## Source-level attribution

The locked JAR has only one exposed SLO behavior option, `deduplicateShuffledTemplatePoolElementList`; there is no supported setting to disable individual always-on jigsaw optimizations while keeping the mod loaded.

The strongest source-level suspect is the always-on `SinglePoolElementMixin` / `GeneralUtils.shuffleAndPrioritize` path:

1. Vanilla's target path shuffles the complete jigsaw-block list and then applies selection-priority ordering.
2. SLO redirects both operations into `shuffleAndPrioritize`.
3. That implementation first buckets entries by `selection_priority`, shuffles each bucket independently, sorts priority keys descending, and concatenates the buckets.
4. SLO's own README specifically notes that Trial Chambers use `selection_priority` and says this optimized sorting path *might* break vanilla seed parity.

Shuffling independent buckets consumes random values differently from shuffling one complete list and then applying a stable priority sort. This makes that redirect a direct mechanism capable of changing the later jigsaw choice sequence for Trial Chambers.

This source observation is not promoted to a one-mixin isolation proof. Other always-on SLO jigsaw optimizations also remain active. A provider-side fix should preserve vanilla random consumption and ordering exactly, then prove the frozen `338`-piece case before release.

## Upstream status as of 2026-09-10

- Modrinth exposes no newer Fabric 26.x release than `1.1.4+26.1-fabric`.
- The `26.1` upstream branch contains no documented release fixing this exact Trial Chamber parity result.
- Existing parity-related issue #28 concerned displayed/saved bounding boxes and was closed after attribution away from SLO; it does not explain the deterministic `338 -> 273` piece-count change here.
- No supported SLO configuration restores the always-on jigsaw shuffle/prioritization path to vanilla while retaining the rest of the provider.

The appropriate compatibility ladder position is therefore **UPSTREAM PATCH REQUIRED / EXCLUDED FROM STRICT-PARITY PROFILE**, not PASS_CONFIG and not a Phoenium adapter.

## Re-entry criteria

Reconsider SLO for the strict-parity profile only when one of these is true:

- upstream publishes a fix for the default parity path;
- a maintained upstream-supported configuration can disable the divergent optimization while retaining useful SLO functionality; or
- deployment policy explicitly accepts changed jigsaw layouts and no longer requires vanilla seed parity for this provider.

Any fix candidate must rerun the same fixed seed/target and prove the native `338` pieces and structure NBT before broader performance work. Do not change the seed, target, comparator, or workload to manufacture parity.

Stage 7 remains `IN PROGRESS`.
