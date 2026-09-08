package dev.phoenium.mixin;

import dev.phoenium.chunk.FullLiveComponentInputs;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.*;
import org.spongepowered.asm.mixin.*;

@Mixin({BeaconBlockEntity.class, BaseContainerBlockEntity.class, EnchantingTableBlockEntity.class, BannerBlockEntity.class})
public abstract class FullLiveNamedBlockComponentMixin implements FullLiveComponentInputs.NamedBlockAccess {
    // All four pinned, unobfuscated 26.2 targets declare this exact field. Mixin
    // rejects a remappable shadow on a mixin with more than one target.
    @Shadow(remap = false) private Component name;
    @Override public Component phoenium$rawBlockName() { return name; }
}
