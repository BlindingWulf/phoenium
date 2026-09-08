package dev.phoenium.mixin;

import dev.phoenium.chunk.FullLiveComponentInputs;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.BaseCommandBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BaseCommandBlock.class)
public abstract class FullLiveBaseCommandComponentMixin implements FullLiveComponentInputs.CommandAccess {
    @Shadow private Component customName;
    @Shadow private Component lastOutput;
    @Shadow private boolean trackOutput;
    @Override public Component phoenium$rawCommandName() { return customName; }
    @Override public Component phoenium$rawLastOutput() { return lastOutput; }
    @Override public boolean phoenium$rawTrackOutput() { return trackOutput; }
}
