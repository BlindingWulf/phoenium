package dev.phoenium.mixin;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Resolve the actual two native stopOpen recipients, including a chunk-edge double chest. */
@Mixin(CompoundContainer.class)
public interface ContainerCloseCompoundAccess {
    @Accessor("container1") Container phoenium$firstContainer();
    @Accessor("container2") Container phoenium$secondContainer();
}
