package dev.phoenium.mixin;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value = ServerChunkCache.class, priority = 100)
abstract class LiveChunkTickPhaseMixin { }
