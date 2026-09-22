package dev.phoenium.chunk;

import dev.phoenium.live.PhoeniumLiveRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;

public final class ContainerCloseTestHooks {
    public static PhoeniumLiveRuntime live;
    public static java.util.function.Consumer<net.minecraft.world.inventory.ChestMenu> removal = menu -> { };
    public static java.util.function.Consumer<ServerLevel> event = level -> { };
    public static void removed(net.minecraft.world.inventory.ChestMenu menu) { removal.accept(menu); }
    public static void starting(ServerPlayer player) { if (live != null) live.containerCloseStarting(player); }
    /** The parent has no close injector. Only bind the repaired route when it exists in production. */
    public static void close(ServerPlayer player, ServerGamePacketListenerImpl listener, ServerboundContainerClosePacket packet) {
        try {
            var method = PhoeniumLiveRuntime.class.getMethod("admitContainerClosePacket", ServerGamePacketListenerImpl.class, ServerboundContainerClosePacket.class);
            boolean replay = (boolean) PhoeniumLiveRuntime.class.getMethod("matchesContainerCloseReplay", ServerGamePacketListenerImpl.class, ServerboundContainerClosePacket.class).invoke(live, listener, packet);
            if (!replay) { method.invoke(live, listener, packet); return; }
        } catch (NoSuchMethodException parent) {
            // Unchanged parent production: enter its actual, unadmitted native close.
        } catch (java.lang.reflect.InvocationTargetException failure) { rethrow(failure.getCause()); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        player.doCloseContainer();
    }
    public static void blockEvent(ServerLevel level, BlockPos pos, Block block, int a, int b) {
        event.accept(level);
        if (live != null && live.enqueueRegionalBlockEvent(level, new BlockEventData(pos, block, a, b))) return;
        try { ServerLevel.class.getMethod("phoenium$testOriginalBlockEvent", BlockPos.class, Block.class, int.class, int.class).invoke(level, pos, block, a, b); }
        catch (java.lang.reflect.InvocationTargetException failure) { rethrow(failure.getCause()); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new AssertionError(failure);
    }
}
