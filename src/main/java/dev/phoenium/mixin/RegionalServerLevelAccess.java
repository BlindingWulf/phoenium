package dev.phoenium.mixin;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exact original callback invokers used only from a current regional service lease. */
@Mixin(ServerLevel.class)
public interface RegionalServerLevelAccess {
    @Accessor("blockEvents") ObjectLinkedOpenHashSet<BlockEventData> phoenium$getBlockEvents();
    @Invoker("tickBlock") void phoenium$invokeTickBlock(BlockPos pos, Block block);
    @Invoker("tickFluid") void phoenium$invokeTickFluid(BlockPos pos, Fluid fluid);
    @Invoker("doBlockEvent") boolean phoenium$invokeDoBlockEvent(BlockEventData event);
}
