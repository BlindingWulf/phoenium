package dev.phoenium.mixin;

import dev.phoenium.live.PearlPortalNativeAccess;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.block.Portal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PortalProcessor.class)
public interface PearlPortalProcessorAccess {
    @Accessor("portal") Portal phoenium$portal();
}
