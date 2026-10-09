package dev.phoenium.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stage 9 D1: vanilla {@code TrackedEntity.getEffectiveRange} builds a passenger stream on every call, and
 * {@code ChunkMap.move} calls it for every tracked entity on every player move packet (34% of the server thread with
 * 50 spread players on the generic stack). With no passengers the vanilla loop body never runs, so the result is
 * {@code scaledRange(range)}; return that directly. Vehicles still take the vanilla path.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityRangeFastPathMixin {
    @Shadow @Final Entity entity;
    @Shadow @Final private int range;

    @Shadow private int scaledRange(int range) { throw new AssertionError(); }

    @Inject(method = "getEffectiveRange", at = @At("HEAD"), cancellable = true)
    private void phoenium$rangeWithoutPassengers(CallbackInfoReturnable<Integer> cir) {
        if (this.entity.getPassengers().isEmpty()) cir.setReturnValue(this.scaledRange(this.range));
    }
}
