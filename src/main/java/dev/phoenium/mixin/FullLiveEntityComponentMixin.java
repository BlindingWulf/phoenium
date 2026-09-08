package dev.phoenium.mixin;

import dev.phoenium.chunk.FullLiveComponentInputs;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;

@Mixin(Entity.class)
public abstract class FullLiveEntityComponentMixin implements FullLiveComponentInputs.EntityAccess {
    @Shadow @Final private static EntityDataAccessor<Optional<Component>> DATA_CUSTOM_NAME;
    @Shadow @Final protected SynchedEntityData entityData;
    @Override public Component phoenium$rawCustomName() { return entityData.get(DATA_CUSTOM_NAME).orElse(null); }
}
