package dev.phoenium.stage4;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.live.PhoeniumLiveRuntime;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observe a slow original shutdown predicate on its own server thread. */
@Mixin(PhoeniumLiveRuntime.class)
abstract class Stage4ShutdownDrainMixin {
    @WrapOperation(method = "drainAdmittedWork", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/MinecraftServer;managedBlock(Ljava/util/function/BooleanSupplier;)V"), require = 0)
    private void drain(MinecraftServer server, BooleanSupplier ready, Operation<Void> original) {
        long[] next = {0};
        original.call(server, (BooleanSupplier) () -> {
            boolean done = ready.getAsBoolean();
            if (!done && System.nanoTime() >= next[0]) {
                next[0] = System.nanoTime() + 1_000_000_000L;
                try {
                    StringBuilder state = new StringBuilder("STAGE4_DRAIN_WAIT");
                    for (String name : new String[]{"packets", "chunkRequests", "regionalDispatcher", "regionalSaves", "runtimes"}) {
                        var field = PhoeniumLiveRuntime.class.getDeclaredField(name); field.setAccessible(true);
                        Object value = field.get(this);
                        if (value instanceof Map<?, ?> map && !name.equals("runtimes")) value = map.keySet();
                        else if (name.equals("runtimes")) value = ((Map<?, ?>) value).values().stream()
                            .map(item -> { var runtime = (dev.phoenium.region.RegionRuntime) item;
                                return runtime.id() + ":" + runtime.lifecycle() + ":" + runtime.inboxSize() + ":" + runtime.transitionMailboxSize(); }).toList();
                        else if (name.equals("regionalSaves") && value != null) value = ((dev.phoenium.save.RegionalSaveCoordinator) value).measurements();
                        else if (name.equals("regionalDispatcher") && value != null) value = ((dev.phoenium.service.RegionalServiceDispatcher) value).pendingCount();
                        state.append(' ').append(name).append('=').append(value);
                    }
                    System.out.println(state);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }
            return done;
        });
    }
}
