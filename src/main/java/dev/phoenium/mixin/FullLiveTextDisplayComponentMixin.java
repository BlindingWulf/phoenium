package dev.phoenium.mixin;

import dev.phoenium.chunk.FullLiveComponentInputs;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.*;

@Mixin(Display.TextDisplay.class)
public abstract class FullLiveTextDisplayComponentMixin implements FullLiveComponentInputs.TextAccess {
    @Shadow @Final private static EntityDataAccessor<Component> DATA_TEXT_ID;
    @Shadow public abstract SynchedEntityData getEntityData();
    @Override public Component phoenium$rawDisplayText() { return getEntityData().get(DATA_TEXT_ID); }
}
