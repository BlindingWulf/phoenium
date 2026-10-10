package dev.phoenium.engine.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** S9-E9: the entity of a ServerEntity (the tracker's collected sendChanges calls run item frames in place). */
@Mixin(ServerEntity.class)
public interface EngineServerEntityAccess {
    @Accessor("entity")
    Entity phoenium$entity();
}
