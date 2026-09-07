package dev.phoenium.mixin;

import dev.phoenium.chunk.FullBlockEntityTagInputs;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

/** Guards the actual pending-tag map, covering put/remove/clear/view writers and Proto-to-Level transfer. */
@Mixin(ChunkAccess.class)
abstract class FullBlockEntityTagInputMixin implements FullBlockEntityTagInputs.Access {
    @Shadow @Final @Mutable protected Map<BlockPos, CompoundTag> pendingBlockEntities;
    @Unique private final FullBlockEntityTagInputs.State phoenium$blockTags = FullBlockEntityTagInputs.create();
    @Inject(method = "<init>", at = @At("RETURN"))
    private void phoenium$guardPendingTags(org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
        pendingBlockEntities = FullBlockEntityTagInputs.guard(phoenium$blockTags, pendingBlockEntities);
    }
    @Override public FullBlockEntityTagInputs.State phoenium$blockEntityTags() { return phoenium$blockTags; }
}
