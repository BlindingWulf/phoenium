package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.monster.creaking.Creaking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stage 11 P1 prototype ({@code phoenium.engine.deathConfinement}): the creaking's own death (its heart broken) is
 * vanilla 26.2's third caller of {@code die}, deferred like the others ({@link EngineDeathConfinementMixin}).
 */
@Mixin(Creaking.class)
abstract class EngineDeathConfinementCreakingMixin {
    @WrapOperation(method = "creakingDeathEffects", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/monster/creaking/Creaking;die(Lnet/minecraft/world/damagesource/DamageSource;)V"))
    private void phoenium$confineDeath(Creaking victim, DamageSource source, Operation<Void> original) {
        TileContext context = TileEngine.current();
        if (context == null) {
            original.call(victim, source);
            return;
        }
        context.defer(() -> original.call(victim, source));
    }
}
