package dev.phoenium.mixin;

import dev.phoenium.chunk.FullLiveComponentInputs;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SignBlockEntity.class)
public abstract class FullLiveSignComponentMixin implements FullLiveComponentInputs.SignAccess {
    @Shadow private SignText frontText;
    @Shadow private SignText backText;
    @Override public SignText phoenium$rawFrontText() { return frontText; }
    @Override public SignText phoenium$rawBackText() { return backText; }
}
