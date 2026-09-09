package dev.phoenium.c2meprobe.mixin;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value = net.minecraft.world.level.chunk.status.ChunkStep.class, priority = 1)
abstract class StepProbeMixin { }
