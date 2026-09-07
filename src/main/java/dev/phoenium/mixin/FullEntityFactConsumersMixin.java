package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.phoenium.chunk.FullEntityFacts;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Original caller virtual methods keep their exact order and side effects. */
@Mixin(Mob.class)
abstract class FullEntityFactConsumersMixin {
    @WrapOperation(method = "asValidTarget", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;isCreative()Z"), require = 1)
    private boolean phoenium$creative(Player target, Operation<Boolean> original) { return FullEntityFacts.creative(target, () -> original.call(target)); }
    @WrapOperation(method = "asValidTarget", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;isSpectator()Z"), require = 1)
    private boolean phoenium$spectator(Player target, Operation<Boolean> original) { return FullEntityFacts.spectator(target, () -> original.call(target)); }
}
