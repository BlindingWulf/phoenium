package dev.phoenium.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$DistanceManager")
public interface RegionalDistanceManagerAccess {
    @Invoker("inEntityTickingRange") boolean phoenium$invokeInEntityTickingRange(long chunkKey);
}
