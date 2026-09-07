# Saved-selector cross-world authority decision — 2026-09-07

Status: proposal; no cross-world gameplay authority enabled.

The requested saved-text completion includes selectors that can reach another
dimension. The approved D1-F grant in `STAGE3_FULL_D1_D3_DECISIONS.md` permits
enumerated owner-published identity facts, forbids arbitrary foreign virtual calls,
and explicitly says same-world convergence cannot span dimensions. The current
FULL claim and `FullRegionalEnclosure` therefore cannot authorize these calls.

Pinned Minecraft 26.2 original source establishes the affected behavior:

* `EntitySelector.findEntities` visits worlds in the server's existing order;
  unrestricted player selectors use the actual player-list order. UUID/name and
  limited selectors retain their original first-match and short-circuit behavior.
* `EntitySelectorOptions` evaluates `nbt=` by calling the selected entity's real
  `saveWithoutId`, including player inventory, with the original reporting scope.
* A `predicate=` option constructs loot context from the selected entity's world.
  `LootContext.Builder.create(Optional.empty())` defaults to that world's gameplay
  random source. This can advance foreign-world RNG at the original selector call.
* `EntityDataSource` invokes the real selected entity's NBT serializer; display
  names, selectors and interpreted text can observe earlier effects in the same
  original FULL body. Pre-resolving them would change protected observation points.

The proposed bounded amendment is a dedicated FULL saved-selector operation with
an explicit owner bundle, acquired before the original finishing body:

1. Retain actual world membership and ordered entity/player index evidence, exact
   object incarnations, routes, the source saved occurrence and required context
   and chunk inputs. Discovery does not evaluate predicates, serialize live
   entities, resolve text, advance RNG or perform gameplay effects.
2. Add nonblocking all-or-release admission for the exact participating owners
   across those worlds. A failed acquisition releases every unused entitlement
   and retries owner-free. No owner waits for another; objects keep their actual
   owners. No global gameplay mutex or additional gameplay worker is introduced.
3. At the original selector/NBT call, require the matching source FULL claim and
   its exact candidate/context bundle. Execute original enumeration, predicates,
   sorting, name/NBT reads, reporting and reached world-RNG effects once, in their
   original order. Same-body insertions/removals update the observed index at their
   original points. A grant is not transferable to later ticks or arbitrary code.
4. Retain existing original ready/absent/error semantics for every reached world
   query. Any required asynchronous dependency must finish before owner admission;
   the bundle grants no synthetic ready chunk, public FULL, or general getter.
5. Release the bundle after synchronous exit, including partial failure and
   shutdown, before dependent completion. No replay, rollback, skipped predicate,
   cached final selector result or speculative RNG/serialization is permitted.

This changes the approved one-world ownership boundary. It does not approve a
different selector result, RNG sequence, exception point, public visibility or
side-effect order. Implementation still requires positive cross-world original
method tests, wrong/stale capability and partial-failure tests, installed adapters,
and affected actual client/reload acceptance. An approval is not evidence of PASS.

Recommendation: authorize this bounded owner-bundle extension. If the current
one-world boundary is retained, global saved selectors remain an explicit missing
binding and the FULL assignment cannot be reported complete. Existing block NBT,
same-world contexts, service fixes and installed/client diagnosis continue
independently while this decision is pending.
