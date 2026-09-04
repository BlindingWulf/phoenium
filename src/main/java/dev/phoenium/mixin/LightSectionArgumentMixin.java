package dev.phoenium.mixin;

import dev.phoenium.chunk.LightInvocationArgument;
import dev.phoenium.chunk.LightMailboxCommand;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SectionPos.class)
abstract class LightSectionArgumentMixin implements LightInvocationArgument {
    @Unique private LightMailboxCommand.Witness phoenium$light;
    @Override public LightMailboxCommand.Witness phoenium$lightWitness() { return phoenium$light; }
    @Override public void phoenium$lightWitness(LightMailboxCommand.Witness witness) { phoenium$light = witness; }
}
