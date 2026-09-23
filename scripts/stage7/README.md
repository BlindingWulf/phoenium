# Stage 7 installed evidence intake

`build_portal_coverage.py` reads the pinned v2 census, the frozen 843-ID list,
the final candidate JAR, the pinned mapped source/binary JARs, and one or more
installed-run directories. It writes a JSON report and a readable Markdown
report. `--gate` exits nonzero while any row lacks its earned disposition.

For each installed phase it checks the candidate hash in the inputs, server
result, client receipt and installed JAR copy; successful client/server exit;
normal save and stop; unchanged source world; and every recorded test-source
file against the current worktree. A passing card from an earlier test source
revision is rejected even if the product JAR hash is unchanged.

The legacy card contracts in `portal_legacy_card_contracts.json` bind reviewed
Java method bodies to their card names. M0, A0, F1, I1, I2, I3, I6, P0, P1,
P2, P3, N0, G0, and V0 can
contribute direct row observations. The old I5 pearl card is supporting
evidence only: it observes the later player transition and pearl hit, but not
the projectile identity in both worlds. R1–R5, I4, I7 and I8 are recorded as
ancillary respawn, local pearl and restart evidence. A changed reviewed method
body revokes its legacy card contract until re-reviewed. The importer never
uses a family name to apply one card to an unrelated entity or route.

New installed cards need a reviewed exact-card contract in the importer. A
card's own `coverage` array is rejected as unreviewed metadata because its
assertion strings cannot certify themselves. The census row's
`requiredAssertions` lists additional family assertions.
Every cross-dimension transfer requires at least `source_retired`, `destination_unique`,
`destination_owner`, and `tracking_unique`, independently observed under W1
and W4 on the final bytes. Same-dimension gateway transfer also requires
`same_native_object`, old-location vacancy, gateway cooldown/ticket, and
`same_dimension`; it does not retire or replace the entity. Native refusal requires negative state assertions and an
applicable pinned native source proof. Proof-only outcomes are accepted only
when contact is explicitly impossible and there are no unobserved required
assertions. The five leash-knot rows are source-only: a stable fence occupies
the same block a portal would need, and the strict importer verifies the
pinned geometry proof by exact artifact hash. The other 55 no-entry rows still
need W1 and W4 installed observations.

Owner assertions are imported only from `STAGE7_PORTAL_TX` event lines with
the same card UUID, entity type, route and transaction generation, and with
source retirement, destination insertion and ChunkMap tracking in order under
their respective owners. The phase status must have zero pending transactions
and violations, and both product and probe hashes must match the installed
inputs. A card's own metadata cannot award an owner assertion. Family-specific
requirements cover projectiles, falling states, item count/pickup, player
session, vehicles, graphs and TNT effects.

P1 checks each hurting projectile's later native effect: explosion, fire,
dragon breath cloud, or a wind-triggered lever. P2 checks arrow/trident owner
UUID across native replacement, in-ground arrow impact, and enchanted trident
loyalty return. P3 checks egg/snowball's exact UUID byte-3 impact broadcast,
Blaze damage for snowballs, splash poison, lingering cloud, and XP orbs. The
byte-3 observer is bounded and included only in the installed test probe JAR.

The copied audit-root `run-sweep.py` and `run-installed.py` preserve the old
command shape while using this campaign's worktree and isolated installed
namespace. They discover explicit client groups and check exact card selection
branches before launching a server. A selected card must be the only card in
the resulting client receipt. Failed instances remain in place; use a new
`--name` after a repair.

`render_portal_coverage.py` expands the strict JSON ledger into a complete
human-readable table of all 843 frozen IDs and all generated obligations. It
does not award evidence; rerun it after the final importer pass.
