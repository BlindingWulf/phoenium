# Stage 7 container-close service admission

Parent: `09d4eeb3831aa9c1e25a0043eba6e81e1a9d71cd`, clean branch
`stage7/respawn-lifecycle-repair`. Evidence and candidate directory:
`.audit/container-close-admission-20260922` in the main project root.

## Finding

In mapped Minecraft 26.2, `MinecraftServer.processPacketsAndTick` calls
`PacketProcessor.processQueuedPackets` **before** `tickServer`. The existing
`FullFinishingSerialMixin` reserves `SERVER_TICK` around the latter. Packet draining
is not that turn, nor does it run through the `doRunTask` service wrapper.
The parent listener mixin/invoker has no container-close route (ordinary profile
as well as authoritative FULL). This is an absent route, not an inactive selector.

`handleContainerClose` calls `PacketUtils.ensureRunningOnSameThread`, then
`player.doCloseContainer`. Unlike container **click**, this pinned close handler
has no container-ID, spectator, death or menu-validity predicate. The repair keeps
that exact order and behavior, including an incoming ID different from the current
menu ID. Newly deferred work additionally binds the exact selected menu instance.

The native prefix is significant: `ChestMenu.removed` first calls the superclass,
which returns or drops a carried stack and clears it. `ChestBlockEntity.stopOpen`
then calls `ContainerOpenersCounter.decrementOpeners`: the count changes, the close
sound/game event runs at zero, and `signalOpenCount` calls `ServerLevel.blockEvent`.
Only after menu removal returns does the player transfer menu state and install
`inventoryMenu`. The failure is at `RegionalWorldServices.enqueueBlockEvent`'s
`FullFinishingServices.requireWorld(TICK_LIST)` after those prefix effects.

The block event uses each actual block entity's stored level and position, not the
player's chunk. `CompoundContainer.stopOpen` invokes the first and second containers
in native order. Both are selected through accessors, with identity deduplication
and a `LinkedHashSet` of closure seeds. Ender chest active-block identity and native
menu slot containers also participate. Same-world remote chunks are enclosed by
the existing `TopologyPlanner` and `RegionalServiceDispatcher`; a foreign-world
recipient rejects before any close effect. Native player removal closes the source
menu before dimension installation; no new cross-world transaction is introduced.

## Repair and lifetime

The listener injects immediately before its sole `ServerPlayer.doCloseContainer`
call, after the thread check. It settles earlier accepted FIFO work under the
existing owner-free `referenceAccess` serial reservation, before selecting session,
menu and recipient closure. Enclosure is followed by selection revalidation and
the existing `LiveBlockPacketAdmission` ticket and object-owner gateway.

Execution consumes the retained operation before effects. It revalidates exact
player and listener identities, the existing scoreboard connection-session ID,
menu object, player incarnation/epoch, dimension-transition epoch, level, topology
and recipient footprint. It then re-enters the original handler using the existing
packet execution marker and a narrow invoker. The admitting scheduler supplies its
real service turn; a TICK_LIST precondition is checked before any native effect.
No FULL claim, global packet wrapper, guard relaxation or block-event suppression
is used. Event queue ordering/deduplication and later delivery are unchanged.

A second reproduced race required the shared `doCloseContainer` HEAD hook:
owner-free native cleanup can enqueue a block event and drain an older network
close while the first close's menu prefix is still running. The hook marks only
pending requests for this exact player/menu as superseded, before any native effect.
It does not cancel, defer or replace cleanup. The current replay has already been
removed from retained custody and therefore does not retire itself. This prevents
duplicate cursor/openers effects during replacement or lifecycle cleanup.

Stale requests retire without acting on a replacement player/menu. Native failures
propagate with the original cause and never replay the consumed prefix. Bounded
first-failure diagnostics cannot replace that cause. Shutdown logs append
`close[in,admit,effect,retired,failed]`; retirements are counted separately from
completed closes. A retirement count alone does not establish harmless behavior.

## Direct related callers

| Caller | Admission and impact |
| --- | --- |
| Explicit close packet | Directly repaired: previously outside a service turn; now native replay in an admitted owner/service body. |
| `openMenu`, horse/nautilus menu replacement | Existing admitted block-use/interaction or server task contexts; shared HEAD hook retires competing requests without changing close/send/open order. Other plugin entry contexts are not established. |
| `ServerPlayer.tick` / `doTick` distance closure | Existing `SERVER_TICK` context, native `stillValid` check then `closeContainer`; shared hook applies. The patch does not change distance rules. |
| Death `Player.remove(KILLED)` | Existing entity tick service turn; native removal then inventory/menu cleanup remains synchronous. Shared hook applies. |
| Disconnect `PlayerList.remove` -> `removePlayerImmediately` -> `Player.remove(UNLOADED_WITH_PLAYER)` | Existing `PLAYER_LIFECYCLE` wrapper; cleanup still completes before session retirement. Shared hook applies even after the owner index has been removed. |
| Respawn/source retirement | Existing player lifecycle/transition primitives retained; no changes to respawn, portal preparation or tracking routing. |

## Evidence and verification limits

The preserved parent regression enters the real `handleContainerClose`, then native
player/menu/chest/openers/block-event methods, without a surrounding service turn.
It fails with the same missing TICK_LIST exception. Parent production was unchanged;
the Java test agent supplies only the project's normal call-site plumbing.
The repaired test enters that same boundary and succeeds. Authoritative FULL is
explicitly asserted false in the fixture.

Controlled endpoints: allocated server/world storage; real PacketUtils thread
check and packet processor; network sends collected locally; sound/game-event sinks
recorded; player game-mode/connection facts supplied; waypoint removal is a no-op;
death/disconnect item drops terminate at a counted stack sink. The real inventory
return, menu transfer, opener decrement and block-event queue/delivery run. Menus
start with a seeded native opener count to avoid unrelated scheduled recheck ticks.
The movement-selection regression uses an already accepted dispatcher body with a
controlled movement endpoint, not a network movement/physics or Bedrock claim.

New coverage includes duplicate delivery, cursor counts, sound/notification effects,
existing serial native close, reused menu ID, exact player/session fencing,
superseding cleanup, both native removal reasons, double-chest chunk-edge order,
distinct-owner closure convergence, current tracking-session receipts, wrong-thread
and native ID behavior, original failure preservation, and an unadmitted negative
block-event boundary. Binding tests pin the actual production selectors and native
ordering. Full network disconnect/death gameplay still requires the Luna cards.

The final verification script runs the previous focused selection plus close/packet
coverage, then the byte-identical preserved broader filter. All nine exclusions are
unchanged. Parent broader result: **1,263/1,263**, not an unfiltered pass. Historical
unfiltered **1,264 tests / 73 failures** and unresolved earlier **1,225** reconciliation
remain separate. Exact commands, source/input hashes and XML counts are in the audit
receipts. The startup smoke uses the final committed JAR and pinned eight providers
and 29 configurations; startup does not establish native gameplay acceptance.

The original Nether `PACK_VALIDATION:SETTLED:references=PRESENT`, Nether dimension,
matching preparation generations and `complete=true` evidence in
`nether-natural-spawn-20260922/live-09d4eeb-20260922-024847` remains scoped positive
coverage. The five close failures at 03:00:47–03:00:59 were rejected operations,
not a reproduced crash or natural-spawn failure; normal exit 0 does not clear them.
Historical packet menu ID, session generations, exact chest positions and owner
context were not recorded and remain unavailable.

No provider binary investigation, machine setting change, gameplay campaign,
unfiltered acceptance claim or Stage 7 completion is part of this repair.
