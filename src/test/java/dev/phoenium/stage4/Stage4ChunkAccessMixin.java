package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe detached packed ticks at the real serialization boundary. */
@Mixin({LevelChunk.class, ProtoChunk.class})
abstract class Stage4ChunkAccessMixin {
    @Inject(method = "getTicksForSerialization(J)Lnet/minecraft/world/level/chunk/ChunkAccess$PackedTicks;",
            at = @At("RETURN"), require = 1)
    private void phoenium$packedTicks(long gameTime, CallbackInfoReturnable<ChunkAccess.PackedTicks> callback) {
        ChunkAccess chunk = (ChunkAccess) (Object) this;
        if (chunk.getPos().equals(new ChunkPos(7, 0)))
            Stage4Trace.packedTicks(chunk, gameTime, callback.getReturnValue());
    }
}
