package dev.phoenium.mixin;

import dev.phoenium.chunk.FullLiveComponentInputs;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import org.spongepowered.asm.mixin.*;

@Mixin(SkullBlockEntity.class)
public abstract class FullLiveSkullComponentMixin implements FullLiveComponentInputs.NamedBlockAccess {
    @Shadow private Component customName;
    @Override public Component phoenium$rawBlockName() { return customName; }
}
