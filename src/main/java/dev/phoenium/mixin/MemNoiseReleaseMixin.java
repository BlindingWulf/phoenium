package dev.phoenium.mixin;

import dev.phoenium.mem.NoiseRelease;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * S9-M1 (MEM-2): CARVERS is the last generation step that reads the proto chunk's noise chunk; release it when the step
 * returns. Vanilla's task and the locked C2ME chunk system both run this task (C2ME through ChunkStep.apply).
 */
@Mixin(ChunkStatusTasks.class)
abstract class MemNoiseReleaseMixin {
    @Inject(method = "generateCarvers", at = @At("RETURN"))
    private static void phoenium$releaseNoiseAfterCarvers(WorldGenContext context, ChunkStep step,
                                                          StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
                                                          CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        NoiseRelease.afterCarvers(chunk, cir.getReturnValue());
    }
}
