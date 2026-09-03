# Stage 3 packet ingress contract

State-changing ingress is represented by `OwnedObjectEnvelope`, not a live `ServerPlayer`, Netty callback, or arbitrary runnable. Decode/basic protocol validation remains at the connection boundary. The owner gateway then resolves the player’s current binding, includes the expected player object epoch, and queues a typed owner operation. Immediately before a Minecraft mutation, the owner executor must validate the current object epoch and owner again.

The deterministic stale-packet regression covers decode under epoch N, transfer before delivery, and delivery under epoch N+1. The old envelope is either rejected or rerouted to the new owner; it cannot run at the old owner.

| Family | Stage 3 policy |
| --- | --- |
| Player movement, entity interaction, block/world interaction | Mutating: use current player-owner routing and a typed envelope. |
| Read/query | Must use an immutable snapshot or acquire the appropriate owner; no direct foreign mutable read. |
| Connection/protocol lifecycle | Explicit global/connection policy, never implicit world mutation. |
| Unknown | Fail closed during development. |

## Bound 26.2 paths

Two exact official-named 26.2 handlers are intercepted immediately after their shared `PacketUtils.ensureRunningOnSameThread(Packet, PacketListener, ServerLevel)` call:

| Packet | Handler | State-changing path after replay |
| --- | --- | --- |
| `ServerboundMovePlayerPacket` | `ServerGamePacketListenerImpl.handleMovePlayer` | player movement and chunk-source tracking update |
| `ServerboundInteractPacket` | `ServerGamePacketListenerImpl.handleInteract` | `ServerPlayer.interactOn` entity interaction |

The initial packet invocation is cancelled after owner admission. The raw packet remains in a private runtime pending-record keyed by correlation ID, never in an envelope. The scheduler replays it only inside a correlation-scoped execution marker; the Mixin permits that one re-entry, while every unmarked entry is routed rather than bypassed because it happens to be on the server thread.

Current code resolves the packet target chunk and transitions the player binding *before*
replaying the original handler. The architecture review identifies this as an invalid
ownership inference: the packet supplies a proposal, while vanilla validates, collides,
may reject/correct, and updates live source and destination state synchronously. A proposed
chunk cannot establish destination authority for that complete handler.

The required correction is current-player-owner admission for the whole handler, complete
mutation-closure validation before mutable access, and routing-key reconciliation to the
actual accepted position under the same owner lease. Same-owner chunk movement keeps
object epoch. An escaping operation needs convergence before a proven restartable boundary
or a hard barrier; it is not repaired by target-owner replay. See
[STAGE3_MOVEMENT_ARCHITECTURE_REVIEW.md](STAGE3_MOVEMENT_ARCHITECTURE_REVIEW.md).
This correction is specified, not implemented. Other packet families remain unclaimed;
real packet and player-crossing evidence is still required.
