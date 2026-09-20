package dev.phoenium.mixin;

import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exact invoker for the private native sound callback used by the retained pearl-hit suffix. */
@Mixin(ThrownEnderpearl.class)
public interface PearlPortalThrownEnderpearlAccess {
    @Invoker("playSound") void phoenium$invokePlaySound(Level level, Vec3 position);
}
