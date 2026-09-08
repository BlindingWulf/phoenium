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
        FullFinishingServices.runServerTask(server, () -> server.schedule(task), () -> original.call(task));
    }
    @WrapMethod(method = "saveAllChunks(ZZZ)Z")
    private boolean phoenium$saveServices(boolean silent, boolean flush, boolean force, Operation<Boolean> original) {
        try (var turn = FullFinishingServices.forServer((MinecraftServer) (Object) this).serial(FullFinishingServices.Phase.SAVE)) {
            boolean result = original.call(silent, flush, force);
            var saves = dev.phoenium.Phoenium.liveRuntime().regionalSaves();
            if (flush && saves != null)
                dev.phoenium.save.RegionalSaveBinding.flush((MinecraftServer) (Object) this, saves);
            return result;
        }
    }
    @WrapMethod(method = "stopServer()V")
    private void phoenium$stopServices(Operation<Void> original) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        var runtime = dev.phoenium.Phoenium.liveRuntime();
        dev.phoenium.chunk.FullServerShutdown.run(server, runtime::closeGameplayAdmission,
                () -> runtime.drainAdmittedWork(server), () -> original.call(), runtime::stop);
    }
}
