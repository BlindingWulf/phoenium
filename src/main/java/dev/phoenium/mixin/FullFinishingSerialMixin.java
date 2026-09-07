package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullFinishingServices;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import org.spongepowered.asm.mixin.Mixin;

/** Real serial scheduling phases reserve services before their enclosed owner claims. */
@Mixin(MinecraftServer.class)
abstract class FullFinishingSerialMixin {
    @WrapMethod(method = "tickServer(Ljava/util/function/BooleanSupplier;)V")
    private void phoenium$tickServices(BooleanSupplier haveTime, Operation<Void> original) {
        var services = FullFinishingServices.forServer((MinecraftServer) (Object) this);
        try (var turn = services.serial(FullFinishingServices.Phase.SERVER_TICK)) { original.call(haveTime); }
    }
    @WrapMethod(method = "doRunTask(Lnet/minecraft/server/TickTask;)V")
    private void phoenium$taskServices(TickTask task, Operation<Void> original) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        // Pinned scheduleExecutables deliberately runs terminal executor acknowledgements
        // inline on their producer thread after stopped=true. They inherit no service turn;
        // each actual shared mutation still requires its installed writer entry.
        if (server.isStopped() && !server.isSameThread()) { original.call(task); return; }
        try (var turn = FullFinishingServices.forServer(server).serial(FullFinishingServices.Phase.SERVER_TASK)) {
            original.call(task);
        }
    }
    @WrapMethod(method = "saveAllChunks(ZZZ)Z")
    private boolean phoenium$saveServices(boolean silent, boolean flush, boolean force, Operation<Boolean> original) {
        try (var turn = FullFinishingServices.forServer((MinecraftServer) (Object) this).serial(FullFinishingServices.Phase.SAVE)) {
            return original.call(silent, flush, force);
        }
    }
    @WrapMethod(method = "stopServer()V")
    private void phoenium$stopServices(Operation<Void> original) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        try (var turn = FullFinishingServices.forServer(server).serial(FullFinishingServices.Phase.STOP)) { original.call(); }
        finally { FullFinishingServices.stopped(server); }
    }
}
