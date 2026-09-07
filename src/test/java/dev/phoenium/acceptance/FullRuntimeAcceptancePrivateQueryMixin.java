package dev.phoenium.acceptance;

import dev.phoenium.chunk.FullFinishingQueries;
import dev.phoenium.chunk.FullRuntimeAcceptanceProbe;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only observation of a private getter result before its query scope closes. */
@Mixin(FullFinishingQueries.class)
abstract class FullRuntimeAcceptancePrivateQueryMixin {
    @Inject(method = "privateJoin", at = @At("RETURN"))
    private static void phoenium$observePrivateResult(CompletableFuture<?> selected,
            CallbackInfoReturnable<ChunkResult<ChunkAccess>> callback) {
        FullRuntimeAcceptanceProbe.privateQueryReturned(selected, callback.getReturnValue());
    }
}
