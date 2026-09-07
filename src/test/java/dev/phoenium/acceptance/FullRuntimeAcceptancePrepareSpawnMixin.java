package dev.phoenium.acceptance;

import java.util.concurrent.CompletableFuture;
import dev.phoenium.diagnostics.FullChunkLoadCounterDiagnostics;
import net.minecraft.server.level.ChunkLoadCounter;
import net.minecraft.server.network.config.PrepareSpawnTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Narrow, read-only diagnostics for the vanilla FULL login spawn barrier. */
@Mixin(targets = "net.minecraft.server.network.config.PrepareSpawnTask$Preparing")
abstract class FullRuntimeAcceptancePrepareSpawnMixin {
    @Shadow private CompletableFuture<?> spawnPosition;
    @Shadow private CompletableFuture<?> chunkLoadFuture;
    @Shadow private ChunkLoadCounter chunkLoadCounter;
    @Unique private int phoenium$diagnosticTicks;
    @Unique private String phoenium$lastDiagnostic;

    @Inject(method = "tick", at = @At("HEAD"))
    private void phoenium$traceTick(CallbackInfoReturnable<?> callback) {
        if (++phoenium$diagnosticTicks == 1 || phoenium$diagnosticTicks % 100 == 0) {
            trace("tick-" + phoenium$diagnosticTicks);
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void phoenium$traceReady(CallbackInfoReturnable<?> callback) {
        if (callback.getReturnValue() != null) trace("ready");
    }

    private void trace(String event) {
        String current = "PHOENIUM_FULL_SPAWN_TRACE event=" + event
                + " spawnPosition=" + futureState(spawnPosition)
                + " chunkLoad=" + futureState(chunkLoadFuture)
                + " counter=" + (chunkLoadCounter instanceof FullChunkLoadCounterDiagnostics diagnostics
                        ? diagnostics.phoenium$diagnosticSummary() : "unavailable");
        if (current.equals(phoenium$lastDiagnostic)) return;
        phoenium$lastDiagnostic = current;
        System.out.println(current);
    }

    private static String futureState(CompletableFuture<?> future) {
        if (future == null) return "null";
        if (!future.isDone()) return "pending";
        if (future.isCompletedExceptionally()) return "exceptional";
        return "done";
    }
}
