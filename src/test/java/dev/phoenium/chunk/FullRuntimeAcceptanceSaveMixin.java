package dev.phoenium.chunk;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(SerializableChunkData.class)
abstract class FullRuntimeAcceptanceSaveMixin {
    @ModifyReturnValue(method = "copyOf", at = @At("RETURN"))
    private static SerializableChunkData fixture(SerializableChunkData data, ServerLevel level, ChunkAccess chunk) {
        return FullRuntimeAcceptanceProbe.persisted(data, level);
    }
}
