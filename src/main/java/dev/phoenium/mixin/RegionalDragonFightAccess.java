package dev.phoenium.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EnderDragonFight.class)
public interface RegionalDragonFightAccess {
    @Accessor("origin") BlockPos phoenium$origin();
    @Accessor("dragonEvent") ServerBossEvent phoenium$dragonEvent();
    @Accessor("needsStateScanning") boolean phoenium$needsStateScanning();
}
