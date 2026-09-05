package dev.phoenium.mixin;

import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import org.spongepowered.asm.mixin.Mixin;

/** Priority keeps the split after the pinned Fabric and Phoenium lifecycle injections. */
@Mixin(value = ChunkStatusTasks.class, priority = 100)
abstract class FullStageBoundaryMixin { }
