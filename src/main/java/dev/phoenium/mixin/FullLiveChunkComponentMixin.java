package dev.phoenium.mixin;

import dev.phoenium.chunk.FullLiveComponentInputs;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkAccess.class)
public interface FullLiveChunkComponentMixin extends FullLiveComponentInputs.ChunkAccessView {
    @Override @Accessor("blockEntities") Map<BlockPos, BlockEntity> phoenium$rawBlockEntities();
}
