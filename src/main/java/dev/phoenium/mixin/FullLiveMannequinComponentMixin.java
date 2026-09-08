package dev.phoenium.mixin;

import dev.phoenium.chunk.FullLiveComponentInputs;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Mannequin.class)
public abstract class FullLiveMannequinComponentMixin implements FullLiveComponentInputs.MannequinAccess {
    @Shadow @Final private static EntityDataAccessor<Optional<Component>> DATA_DESCRIPTION;
    @Shadow public abstract SynchedEntityData getEntityData();
    @Override public Component phoenium$rawDescription() { return getEntityData().get(DATA_DESCRIPTION).orElse(null); }
}
