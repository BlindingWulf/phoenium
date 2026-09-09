package dev.phoenium.c2meprobe.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
@Pseudo @Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerAccessible", priority = 1)
abstract class AccessibleProbeMixin { }
