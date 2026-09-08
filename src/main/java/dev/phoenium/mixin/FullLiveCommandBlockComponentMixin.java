package dev.phoenium.mixin;

import dev.phoenium.chunk.FullLiveComponentInputs;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(CommandBlockEntity.class)
public abstract class FullLiveCommandBlockComponentMixin implements FullLiveComponentInputs.CommandBlockAccess {
    @Shadow @Final private BaseCommandBlock commandBlock;
    @Override public BaseCommandBlock phoenium$rawCommandBlock() { return commandBlock; }
}
