# Boat and pig Nether portal graph: bounded native continuation

The first full storm on immutable product `9794C531...` stopped at wave 13,
attempt 104, when `/summon oak_boat{Passengers:[pig]}` inserted the boat before
PHOENIUM had reserved the pig's identity. The command admission change wraps
the native `ServerLevel.addFreshEntityWithPassengers` call after the native
duplicate UUID check. Its installed result must be preserved before extending
boat transfer. The present `BoatPortalTransitionManager` deliberately throws
for `boat.isVehicle()`; the next red may therefore be this exact boundary.

## Exact first bound

- Root: exact `Boat` with `EntityTypes.OAK_BOAT`, eligible Nether portal root,
  with exactly one exact pig passenger. Reject player passengers, nested
  passengers, foreign worlds, changed edges, and cross-region owner keys.
  Other boat and passenger graphs retain their existing strict boundary until
  independently profiled and tested.
- Capture `PortalPassengerGraphSnapshot` under the source owner before native
  `ejectPassengers`. Reserve one `CrossDimensionReplacementBoundary` token per
  member and emit a retained graph child capture. Hold a graph pending entry
  for both objects so the root and pig cannot tick while destination work is
  pending. Keep the standalone boat path for passenger-free boats.
- Prepare the root transition outside the source owner through
  `PortalDestinationObservation.prepare`. Give the captured graph and tokens
  to `NativePortalGraphTransferPlan`; it computes the pig's native passenger
  transition, demands visibility for both destination chunks, and rejects
  differing destination owner regions before mutation. Revalidate the exact
  source graph under its owner immediately before invocation.
- Invoke the plan once. `NativePortalTransfer` binds the recursive child call
  while the pinned 26.2 `Entity.teleportCrossDimension` still ejects passengers,
  teleports the pig, creates/adopts the boat replacement, and reattaches the
  successful child in native order. Do not synthesize a pig teleport or attach
  an edge outside that native call.
- Run only the boat's captured source tick suffix under its source owner.
  Then settle child tokens in postorder and the root token through the graph
  plan. Check destination owner publication, replacement incarnations,
  source retirements, and exact boat/pig edges before reporting success.
  Cancel unspent tokens and remove graph pending entries on all exits. A
  parent-null or child-null native result needs its own explicit partial
  outcome; it cannot be credited as an intact transferred graph.

## Required installed proof

1. Preserve the first post-admission `boat_pig_graph` W1 result in a fresh
   disposable world. Any admission or transfer red remains a red; inspect the
   first marker before changing the boat adapter.
2. After a focused adapter unit pass, run one Nether entry and one Nether
   return W1, then W4 with four spatial lanes. The storm script derives the
   pig UUID from its boat UUID and requires separate native child settlement,
   child source retirement and destination admission before the root, native
   passenger attachment after root admission, both source UUIDs absent, one
   live destination row per UUID, fresh object identities, and the ordered
   boat-to-pig and pig-to-boat edges.
3. Add a duplicate UUID refusal and partial parent-null control that cannot
   pass as an intact graph. Only then repeat the original seed 2704, 2,240
   attempt W4 mixed storm on one immutable product/probe pair and preserve the
   first failure. Its Nether stress gate does not imply the Stage 7 full gate;
   End routes, a real player, and other family cards remain separate work.
