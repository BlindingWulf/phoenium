package dev.phoenium.mixin;

import dev.phoenium.chunk.FullLiveComponentInputs;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.*;
import org.spongepowered.asm.mixin.*;

@Mixin({BeaconBlockEntity.class, BaseContainerBlockEntity.class, EnchantingTableBlockEntity.class, BannerBlockEntity.class})
public abstract class FullLiveNamedBlockComponentMixin implements FullLiveComponentInputs.NamedBlockAccess {
    @Shadow private Component name;
    @Override public Component phoenium$rawBlockName() { return name; }
}
