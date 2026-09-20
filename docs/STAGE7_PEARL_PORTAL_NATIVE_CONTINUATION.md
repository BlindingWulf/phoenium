# Stage 7 pearl portal native continuation

Status: implementation candidate; deterministic verification only. Real Bedrock acceptance remains Luna-owned.

## Native 26.2 continuation decision

At `PortalProcessor.getPortalDestination`, the native pearl tick has already run the
`ThrownEnderpearl.tick` server prefix, bubble/gravity/inertia, collision selection, movement,
rotation, block effects, projectile shoot/left-owner checks, `Entity.baseTick`'s prefix,
`processPortalTeleportation`, and `setPortalCooldown`. None of those effects may be replayed.

The exact retained locals are the `ThrowableProjectile.tick` `HitResult` and the
`ThrownEnderpearl.tick` pre-tick section X/Z plus its resolved owner alias. The transition also
retains the exact source alias/native incarnation, object-registry incarnation, source level,
`PortalProcessor`, portal instance, entry position, and a monotonic transition generation.

The remaining native suffix is:

1. the rest of the portal decision and teleport;
2. `Entity.baseTick` after `handlePortal` (particles, fluid/swimming, fire, lava, below-world,
   shared fire flag, first-tick and leash work);
3. `Projectile.tick`'s `leftOwnerChecked = false`;
4. `ThrowableProjectile.tick`'s retained-hit collision, only if the source is still alive; and
5. `ThrownEnderpearl.tick`'s ticket update, only if the source is still alive.

Five pinned mixin cuts return normally from each enclosing native method after portal capture.
They do not throw across the owner scheduler and do not run a native suffix early. The typed
`RetainedPearlTickContinuation` invokes only four exact suffix seams; it has no arbitrary callback
and cannot call `tick` or destination preparation. Pending pearls are excluded at the next tick
head until that continuation settles or retires.

## Checked replacement protocol

`CrossDimensionReplacementBoundary` is an identity/state fence, not another ownership registry.
It records exact native and object-registry incarnations plus transition generation and permits
only this serial state sequence:

`CAPTURED → PREPARED → SOURCE_RETIRING → SOURCE_RETIRED → DESTINATION_ADMITTING → DESTINATION_ADMITTED → CONTINUATION_RUNNING → SETTLED`

Each source and destination stage re-resolves its `RoutingKey` through the existing regional
dispatcher. No source and destination lease is held simultaneously and no owner waits for the
other owner.

- Source retirement runs under the current source route. Only the exact token in
  `SOURCE_RETIRING` recognizes `CHANGED_DIMENSION` as expected removal. Native tracker/storage
  callbacks run before Phoenium retires the source membership and object binding.
- The destination entity is vanilla-created and restored before source retirement. After source
  retirement, destination positioning, `addDuringTeleport`, identity publication, tracking,
  `resetEmptyTime` and the native post-transition callback run under the currently resolved
  destination route. After the native owner-free spectator callback, the pearl portal ticket is
  placed in a second currently resolved destination turn, preserving vanilla call order.
- Destination admission requires the same UUID and different native incarnation, and requires the
  object-registry incarnation to advance. Tracking completion is rejected unless the destination
  binding already exists in the destination world.
- Native spectator transition runs owner-free after destination admission. The retained old-source
  suffix then runs once under the currently resolved source route; the removed source makes the
  collision and ticket suffixes native no-ops after successful replacement.

The coordinator first runs only at an owner-free live-tick cut after pending physical relocation
facts have been reconciled. A topology change therefore re-routes the same transition generation;
it does not replay preparation. A stale alias, native incarnation, registry incarnation, processor,
portal, entry position, generation, source world, or destination world retires the transition
without gameplay mutation.

## Cancellation and lifetime

External source removal cancels the exact pending generation before normal pearl/projectile
cleanup. Expected committed source removal does not. Source or destination level close, runtime
shutdown, or explicit cancellation retires the continuation; later completion is inert. Destination
preparation executes on the server thread without `OwnershipContext`. A preparation exception
retires the pending transition before following the existing fail-closed live-tick failure path; a
null destination resumes the native suffix without replaying the prefix or fabricating a portal.

The generic synchronous owner-wait guard is unchanged. Ordinary players and non-pearl projectiles
never enter these seams. The interception is restricted to `ThrownEnderpearl` with a vanilla
`NetherPortalBlock`. Command-created ridden pearls and an impossible same-dimension result from that
vanilla block fail closed rather than entering a partially modeled replacement path.

## Verification and Luna handoff

Deterministic protocol and bytecode-shape tests cover single admission, monotonic/stale generation,
single continuation, expected versus external removal, same-UUID incarnation safety, serial
source/destination staging, shutdown retirement, late completion, pinned native prefix/suffix order,
typed suffix-only resumption, and production mixin registration. The established focused cleanup,
relocation, dispatcher, chunk-failure, live-tick boundary, and real-client-readiness suites are also
required before packaging.

The real-client acceptance sequence remains exactly the sequence in the user authorization: fresh
isolated `REAL_CLIENT_GATES`; candidate SHA verification; exact C2ME settlement2, ScalableLux and
accepted Geyser/Floodgate; server `Done` and Geyser UDP; real Bedrock join; Nether entry; real pearl
through the Nether portal toward Overworld; confirm pearl completion and responsiveness; player
portal return; chunk loading, break/place, mob interaction, chat, reconnect; log review and clean
shutdown. No Bedrock acceptance is claimed by this document.
