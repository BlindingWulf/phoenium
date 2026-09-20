package dev.phoenium.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Declaring-class invokers for the two protected Entity methods in the player suffix. */
@Mixin(Entity.class)
public interface PlayerDimensionEntityAccess {
    @Invoker("unsetRemoved") void phoenium$invokeUnsetRemoved();
    @Invoker("teleportSpectators") void phoenium$invokeTeleportSpectators(
        TeleportTransition transition, ServerLevel oldLevel);
}
