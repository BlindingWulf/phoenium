package dev.stage9.telemetry.mixin;

import dev.stage9.telemetry.TickProbe;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Brackets the whole vanilla server tick; under Phoenium the regional fan-out runs inside it. Since 1.1.0 it also
 * brackets the tick frame (processPacketsAndTick: the queued packets handled before tickServer, then tickServer), so
 * work an arm does at packet time and work it does inside the tick are measured over the same window. */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerTickMixin {
    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void stage9$tickStart(BooleanSupplier keepTicking, CallbackInfo ci) {
        TickProbe.start();
    }

    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
    private void stage9$tickEnd(BooleanSupplier keepTicking, CallbackInfo ci) {
        TickProbe.end((MinecraftServer) (Object) this);
    }

    @Inject(method = "processPacketsAndTick(Z)V", at = @At("HEAD"))
    private void stage9$frameStart(boolean sprinting, CallbackInfo ci) {
        TickProbe.frameStart();
    }

    @Inject(method = "processPacketsAndTick(Z)V", at = @At("RETURN"))
    private void stage9$frameEnd(boolean sprinting, CallbackInfo ci) {
        TickProbe.frameEnd();
    }
}
