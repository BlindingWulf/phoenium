package dev.phoenium.chunk;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.*;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ChunkStatusTasks.class)
abstract class FullRuntimeAcceptanceGenerationMixin {
    @Inject(method = "generateSpawn", at = @At("RETURN"))
    private static void fixture(WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks,
                                ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
        FullRuntimeAcceptanceProbe.generated(context.level(), chunk);
    }
}
