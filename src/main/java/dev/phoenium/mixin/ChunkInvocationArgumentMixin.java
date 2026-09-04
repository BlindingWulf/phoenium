package dev.phoenium.mixin;

import dev.phoenium.chunk.ChunkInvocationArgument;
import dev.phoenium.chunk.LightInvocationArgument;
import dev.phoenium.chunk.LightMailboxCommand;
import dev.phoenium.chunk.SynchronousChunkInvocation;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ChunkPos.class)
abstract class ChunkInvocationArgumentMixin implements ChunkInvocationArgument,LightInvocationArgument {
    @Unique private volatile SynchronousChunkInvocation.Key phoenium$invocation;
    @Unique private LightMailboxCommand.Witness phoenium$light;
    @Override public SynchronousChunkInvocation.Key phoenium$invocation() { return phoenium$invocation; }
    @Override public void phoenium$invocation(SynchronousChunkInvocation.Key key) { phoenium$invocation = key; }
    @Override public LightMailboxCommand.Witness phoenium$lightWitness() { return phoenium$light; }
    @Override public void phoenium$lightWitness(LightMailboxCommand.Witness witness) { phoenium$light = witness; }
}
