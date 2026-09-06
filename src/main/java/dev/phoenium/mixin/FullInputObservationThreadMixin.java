package dev.phoenium.mixin;

import dev.phoenium.chunk.FullInputObservationThread;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerChunkCache.class)
public interface FullInputObservationThreadMixin extends FullInputObservationThread {
    @Accessor("mainThread") Thread phoenium$inputThread();
}
