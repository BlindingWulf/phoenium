package dev.phoenium.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact Level.getBlockEntity thread-prefix input for retained private chunk selection. */
@Mixin(Level.class)
public interface FullLevelThreadAccessMixin {
    @Accessor("thread") Thread phoenium$levelThread();
}
