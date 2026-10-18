package dev.phoenix.spawnprep.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's startup wait (prepareLevels): set the next tick time, then run tasks until it passes. */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccess {
    @Accessor("nextTickTimeNanos")
    void phoenix$setNextTickTimeNanos(long nanos);

    @Invoker("waitUntilNextTick")
    void phoenix$waitUntilNextTick();
}
