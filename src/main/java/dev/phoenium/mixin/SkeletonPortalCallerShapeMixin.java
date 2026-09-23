package dev.phoenium.mixin;

import net.minecraft.world.entity.monster.skeleton.Skeleton;
import org.spongepowered.asm.mixin.Mixin;

/** Loads the pinned outer-caller verifier before this exact class can portal tick. */
@Mixin(Skeleton.class)
abstract class SkeletonPortalCallerShapeMixin { }
