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

For movement, the packet target chunk is resolved and the player binding transitions *before* the original handler is replayed. This prevents post-mutation reconciliation from being the authority boundary for this packet path. Other packet families remain deliberately unimplemented and unclaimed.
