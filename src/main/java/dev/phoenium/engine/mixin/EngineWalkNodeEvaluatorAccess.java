package dev.phoenium.engine.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** S9-E1: the uncached path-type function behind a tile thread's own path-type cache. */
@Mixin(WalkNodeEvaluator.class)
public interface EngineWalkNodeEvaluatorAccess {
    @Invoker("getPathTypeFromState")
    static PathType phoenium$pathTypeFromState(BlockGetter level, BlockPos pos) { throw new AssertionError(); }
}
