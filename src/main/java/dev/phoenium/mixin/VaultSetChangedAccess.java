package dev.phoenium.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BlockEntity.class)
public interface VaultSetChangedAccess {
    @Invoker("setChanged") static void phoenium$setChanged(Level level, BlockPos pos, BlockState state) { throw new AssertionError(); }
}
