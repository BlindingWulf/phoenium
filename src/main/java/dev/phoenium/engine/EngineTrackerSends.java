package dev.phoenium.engine;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.mixin.EngineServerEntityAccess;
import io.netty.channel.ChannelFutureListener;
import java.util.ArrayList;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.world.entity.decoration.ItemFrame;

/**
 * S9-E9 (engine mode; {@link EngineConfig#TRACKER_SEND}): one ChunkMap's ServerEntity.sendChanges calls of a tick()
 * loop. The loop still pairs (section change: updatePlayers) in vanilla order and collects the calls; after the loop,
 * before the moved-players pass, they run on the tile threads and the server thread, outside any tile.
 *
 * <p>sendChanges reads and writes only its own entity and ServerEntity (LivingEntity's updateDataBeforeSync refreshes
 * its own effect flags) and reads its tracker's seenBy and the entity's passengers. Only pairing changes seenBy, and
 * pairing is done (the loop's) or not started (the moved pass), so each call sees the seenBy it would see in vanilla,
 * where it ran right after its own entity's pairing. An item frame holding a map ticks the map's shared saved data every
 * 10th call: item frames run in place. No other mod of the stack hooks ServerEntity (L5 mixin export).
 *
 * <p>The packets are held per thread ({@link EngineOutbox}) and delivered per connection after the job, one thread per
 * connection: no two threads send to one connection at once, and each entity's packets keep their order. Only the order
 * of different entities' packets on a connection differs from vanilla's; the client applies each to its own entity, and
 * pairing data covers either order of a vehicle's and its passenger's packets. Fewer collected calls than
 * {@link EngineConfig#TRACKER_SEND_MIN} run on the server thread after the loop.
 */
public final class EngineTrackerSends {
    /** Calls per block a thread takes. */
    private static final int BLOCK = 32;
    private static final EngineOutbox.Sender SENDER = (connection, packet, listener) ->
        ((ServerCommonPacketListenerImpl) connection).send((Packet<?>) packet, (ChannelFutureListener) listener);
    // server thread
    private static long calls;
    private static long inPlace;
    private static long parallelRuns;
    private static long serialRuns;
    private static long workNanos;
    /** Held packets, connections delivered to, summed delivery time (ns). */
    private static final long[] DELIVERED = new long[3];

    private final ArrayList<ServerEntity> pending = new ArrayList<>();
    private Operation<Void> operation;
    private long movedStart = -1L;

    /** Head of ChunkMap.tick(): what a tick that threw left behind is dropped. */
    public void begin() {
        pending.clear();
        operation = null;
        movedStart = -1L;
    }

    /** ChunkMap.tick()'s loop: true if the call was collected to run after the loop; false to run it now. */
    public boolean collect(ServerEntity serverEntity, Operation<Void> original) {
        if (!EngineConfig.TRACKER_SEND || !TileEngine.canRunParallel()) return false;
        if (((EngineServerEntityAccess) serverEntity).phoenium$entity() instanceof ItemFrame) {
            inPlace++;
            return false;
        }
        if (operation == null) operation = original;
        pending.add(serverEntity);
        return true;
    }

    /** After the loop, before the moved-players pass (server thread): runs the collected calls. */
    public void flush() {
        int count = pending.size();
        if (count == 0) {
            movedStart = EngineTimers.start();
            return;
        }
        long start = EngineTimers.start();
        ArrayList<ServerEntity> collected = pending;
        Operation<Void> send = operation;
        try {
            calls += count;
            long work = count >= EngineConfig.TRACKER_SEND_MIN
                ? TileEngine.runParallel(count, BLOCK, i -> send.call(collected.get(i)), true) : -1L;
            if (work < 0L) {
                for (int i = 0; i < count; i++) send.call(collected.get(i));
                serialRuns++;
            } else {
                parallelRuns++;
                workNanos += work;
                TileEngine.deliverHeld(SENDER, DELIVERED);
            }
        } catch (Throwable failure) {
            TileEngine.discardHeld();
            throw failure;
        } finally {
            pending.clear();
            operation = null;
            EngineTimers.add(EngineTimers.TRACKER_SEND, start);
            movedStart = EngineTimers.start();
        }
    }

    /** Return of ChunkMap.tick(): the moved-players pass's time (from the flush on). */
    public void end() {
        if (movedStart >= 0L) EngineTimers.add(EngineTimers.TRACKER_MOVED, movedStart);
        movedStart = -1L;
    }

    static String counters() {
        return "trackerSendCalls=" + calls + " trackerSendInPlace=" + inPlace + " trackerSendParallel=" + parallelRuns
            + " trackerSendSerial=" + serialRuns + " trackerSendWorkUs=" + workNanos / 1000 + " trackerSendPackets=" + DELIVERED[0]
            + " trackerSendConnections=" + DELIVERED[1] + " trackerSendDeliverUs=" + DELIVERED[2] / 1000;
    }
}
