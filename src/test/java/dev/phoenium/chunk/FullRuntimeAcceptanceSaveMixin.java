package dev.phoenium.acceptance;
import dev.phoenium.chunk.FullRuntimeAcceptanceProbe;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(SerializableChunkData.class)
abstract class FullRuntimeAcceptanceSaveMixin {
    @Shadow public abstract ChunkPos chunkPos();
    @Shadow public abstract ChunkStatus chunkStatus();

    @ModifyReturnValue(method = "write", at = @At("RETURN"))
    private CompoundTag fixture(CompoundTag output) {
        return FullRuntimeAcceptanceProbe.persistedWrite(chunkPos(), chunkStatus(), output);
    }
}
