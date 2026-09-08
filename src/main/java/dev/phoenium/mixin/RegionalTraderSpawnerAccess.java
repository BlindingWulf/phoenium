package dev.phoenium.mixin;

import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WanderingTraderSpawner.class)
public interface RegionalTraderSpawnerAccess {
    @Accessor("tickDelay") int phoenium$tickDelay();
}
