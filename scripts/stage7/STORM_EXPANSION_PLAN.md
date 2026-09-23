# Stage 7 mixed storm expansion plan

This plan is separate from the default 2,240-attempt Nether W4 storm in
`run_storm.py`. Preserve that frozen default mix for the combined boat, minecart,
and TNT candidate's first full rerun. Every added family needs a new disposable
namespace, immutable candidate and probe hashes, a W1 control, an eight-contact
W4 pilot (four contacts in each Nether direction), and then a mixed run. Keep
`stage7FullStormGate=false` until the End routes, player/session cases, remaining
registered families, and required graphs have final-byte evidence.

## Reusable storm contract

Use seed `2704`, the four existing portal lanes, deterministic UUIDs, and
dimension-scoped source/destination selectors. For each attempt, retain the
exact `CAPTURED*` event, one source retirement, one destination admission and
tracking return, one `TRANSFER_SETTLED_AFTER_SUFFIX`, no live pending
transaction, and zero observer violations. Count actual contacts, transfers,
refusals, misses, and semantic effects by family and route. A failed attempt
ends the instance; save/stop when possible and preserve its world, console,
commands, events, and first UUID. A green wave still needs normal server
save/stop and a same-world restart check. Do not classify a missing contact as
a native refusal.

The existing `summoning_nbt` and `planned_attempts` functions provide the
safe extension seam. Add family fixtures only after a small pilot proves that
the projectile actually reaches the intended portal and that the destination
remains loaded long enough for its native suffix/effect. Keep impact targets
and walls outside the portal face and clean them between segments. Portal
integrity is checked and repaired before each four-lane segment.

## Candidate profiles

| Profile | Exact fixture and effect proof | Pilot before mixed credit |
| --- | --- | --- |
| Shulker Bullet | Existing `shulker_bullet` fixture uses deterministic UUID, `NoGravity:1b`, no target, and `Motion:[0.0d,0.0d,-0.8d]`; the direct-tick path is profiled in `shulker_direct_tick_profile.json`. Require the direct capture marker and one settled transfer, with no duplicate inherited capture. | Repeat the eight-contact W4 both-direction card on final bytes, then promote to the default mix only for this **targetless** state. The C7DB candidate / 6546 probe pilot had 4/4 contacts and transfers per direction, zero owner violations, and passed restart. It does not prove owner/target references or non-null hit suffix. Add a separate exact owner-and-target client card before claiming those variants. |
| Arrow and Spectral Arrow | The existing P2 client fixture summons at `0.5 66 3.5` with `NoGravity:1b`, z motion `-0.4d`, the live player UUID in `Owner:[I;...]`, and `pickup:1b`. A glass wall at destination z `-10` witnesses continued movement then `inGround:1b`. | Keep a connected test player in the source and destination fixture; assert preserved `projectileOwnerUuid`, source retirement, one destination tracker, client visibility, movement, and impact for both Nether directions. The earlier CFB5/6546 W4 P2 matrix passed all six P2 cards, but must be rerun on final bytes. An ownerless RCON summon cannot credit this semantic state. |
| Loyalty Trident | Same P2 owner and approach fixture, plus `item:{id:"minecraft:trident",count:1,components:{"minecraft:enchantments":{"minecraft:loyalty":1}}}`. After impact at z `-10`, assert `noPhysics`, positive return motion, and z `>-9` toward the owner. | Use a connected player and destination client tracking. Repeat W1/W4 both directions on final bytes before mixed credit; keep the trident lane/owner association explicit when multiple shots overlap. |
| Egg and Snowball | P3 fixture uses `NoGravity:1b` and z motion `-0.4d`; destination glass wall at z `-10`. Assert destination continuation, projectile removal on impact, and the exact byte-3 native event for the UUID. Snowball additionally targets an isolated Blaze at `0.5 65 -9` and must reduce its health by three. | W1 each direction, then one eight-contact W4 pilot with target IDs isolated per lane. Do not use the ordinary storm's immediate entity-count check after impact as transfer proof; use transaction and impact events together. |
| Splash Potion | P3 fixture adds `Item:{id:"minecraft:splash_potion",count:1,components:{"minecraft:potion_contents":{potion:"minecraft:poison"}}}` to the P3 motion. An isolated pig at `1.5 65 -9` starts healthy and unpoisoned; after impact it must be poisoned. | Isolate the target and effect radius per lane; assert arrival/continued motion, exact removal, and the pig's before/after effect. Pilot both Nether directions before mixed credit. |
| Lingering Potion | Same P3 poison item with `minecraft:lingering_potion`. Require no prior area cloud near the destination, then a native area-effect cloud near z `-9` after exact projectile impact. | Clear clouds between segments; correlate the cloud to the only shot/impact in that lane and keep a pre-impact absence receipt. Pilot both directions. |
| Experience Bottle | P3 motion and wall; require no prior XP orb near z `-9`, then native XP orbs after the exact impact. | Clear orbs between segments, retain pre-impact absence and post-impact presence receipts, pilot both directions. |

The CFB5/6546 P3 W1 client matrix passed all ten throwable cards, with
source, arrival, continuation, impact, and type-specific effect receipts. No
completed P3 W4 matrix was found in the current audit, and neither CFB5 nor
C7DB is the eventual combined candidate. Earlier receipts define the fixture;
they do not certify final bytes.

## Sequencing and gate

1. Finish and preserve the frozen 14-family, 2,240-attempt W4 Nether storm on
   the combined final candidate/probe pair. A failure remains the first cause
   to repair; do not substitute one of these smaller pilots.
2. Repeat the targetless Shulker W1 and eight-contact W4 card on final bytes.
   After that green pilot, append Shulker to a **new** mix/version and increase
   the total planned attempts so every family has balanced Nether entry and
   return counts.
3. Repeat final-byte P2/P3 client matrices to retain native owner, tracking,
   impact, and effect witnesses. Build independent pilot fixtures from those
   exact inputs. The connected-player fixture is required for P2. Run W1 and
   W4 both-direction pilots before adding any P2/P3 profile to a larger mix.
4. Publish the expanded mix's seed, per-family/route contact and semantic
   counts, transaction/owner status, client observations, final accounting,
   save/stop, and same-world restart. The expanded Nether result is still a
   bounded Nether result.
5. Leave `stage7FullStormGate=false` until End entry/exit/gateway applicability,
   player/session and passenger graph forms, the remaining projectile and
   registered entity profiles, and the full frozen-case final-byte census have
   their own passing receipts. Never infer those routes from the Nether storm.

## Existing fixture receipts

- Shulker W4 both directions: `.audit/sol-ultra-final-campaign-20260923/storm/shulker-c7db-w4-both/result.json` (C7DB/6546, eight contacts/transfers).
- P2 W4 six-card matrix: `.audit/sol-ultra-final-campaign-20260923/installed/arrow-matrix-w4-connect-retry-cfb5-6546-arrow-trident-w4/first-client-receipt.json` and sibling `first-result.json` (CFB5/6546, pass).
- P3 W1 ten-card matrix: `.audit/sol-ultra-final-campaign-20260923/installed/projectile-matrix-cfb5-6546-first-throwable-projectiles-w1/first-client-receipt.json` and sibling `first-result.json` (CFB5/6546, pass).
- Exact P2/P3 fixture implementation: `src/clientGameTest/java/dev/phoenium/clienttest/Stage7RepairClientTest.java`, methods `arrowTridentCase` and `throwableProjectileCase`.
