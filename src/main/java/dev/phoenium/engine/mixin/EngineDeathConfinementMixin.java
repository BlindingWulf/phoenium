package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stage 11 P1 prototype ({@code -Dphoenium.engine.deathConfinement=true}, engine mode, default off; applied only then, see
 * EngineMixinPlugin): a death caused on a tile runs after the tile's colour, on the server thread, in tile order. So does
 * everything inside {@code die}: drops, experience, the death message, kill credit, Fabric's AFTER_DEATH and
 * AFTER_KILLED_OTHER_ENTITY, and other mods' hooks there ({@link dev.phoenium.engine.EntityHookGate#DEATH_PATH}).
 *
 * <p>The wrap is on the {@code die} call, not on {@code die} itself, so every override (ServerPlayer, Player, the
 * villagers, golems, Raider, Sniffer, TamableAnimal, Wolf) is deferred whole. These are all of vanilla 26.2's callers that
 * are not an override's {@code super.die}; Creaking and ArmorStand have their own mixins. Until the apply step the victim
 * has no health left: {@code hurtServer} refuses further damage to a dead or dying entity, and {@code die} itself returns
 * when the entity is already dead or removed, so a second deferred death does nothing.
 */
@Mixin(LivingEntity.class)
abstract class EngineDeathConfinementMixin {
    @WrapOperation(method = {"hurtServer", "handleEntityEvent"}, require = 2, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;die(Lnet/minecraft/world/damagesource/DamageSource;)V"))
    private void phoenium$confineDeath(LivingEntity victim, DamageSource source, Operation<Void> original) {
        TileContext context = TileEngine.current();
        if (context == null) {
            original.call(victim, source);
            return;
        }
        context.defer(() -> original.call(victim, source));
    }
}
