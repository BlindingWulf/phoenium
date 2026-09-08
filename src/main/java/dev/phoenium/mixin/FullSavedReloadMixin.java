package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullSavedInput;
import dev.phoenium.chunk.FullBlockEntityTagInputs;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SerializableChunkData.class)
abstract class FullSavedReloadMixin {
    @WrapMethod(method = "postLoadChunk")
    private static LevelChunk.PostLoadProcessor phoenium$savedReload(ServerLevel level, List<CompoundTag> entities,
            List<CompoundTag> blockEntities, Operation<LevelChunk.PostLoadProcessor> original) {
        var roots = FullSavedInput.reloadRoots(entities);
        var blocks = FullBlockEntityTagInputs.reloadRoots(blockEntities);
        try { return FullSavedInput.reload(original.call(level, roots, blocks), roots, blocks); }
        catch (Throwable failure) {
            FullBlockEntityTagInputs.failedReload(blocks, failure);
            throw new AssertionError("unreachable parsed block-entity reload failure");
        }
    }
}
