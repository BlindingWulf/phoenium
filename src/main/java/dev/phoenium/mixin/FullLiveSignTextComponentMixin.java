package dev.phoenium.mixin;

import dev.phoenium.chunk.FullLiveComponentInputs;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SignText.class)
public abstract class FullLiveSignTextComponentMixin implements FullLiveComponentInputs.SignTextAccess {
    @Shadow @Final private Component[] messages;
    @Shadow @Final private Component[] filteredMessages;
    @Override public Component[] phoenium$rawMessages() { return messages; }
    @Override public Component[] phoenium$rawFilteredMessages() { return filteredMessages; }
}
