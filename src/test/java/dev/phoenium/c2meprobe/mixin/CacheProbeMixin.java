package dev.phoenium.c2meprobe.mixin;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value = net.minecraft.server.level.ServerChunkCache.class, priority = 1)
abstract class CacheProbeMixin { }
