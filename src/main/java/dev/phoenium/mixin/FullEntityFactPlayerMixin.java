package dev.phoenium.mixin;

import com.mojang.authlib.GameProfile;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class FullEntityFactPlayerMixin {
    @Shadow @Final private Abilities abilities;
    @WrapMethod(method = "getScoreboardName()Ljava/lang/String;")
    private String phoenium$scoreboardName(Operation<String> original) { return FullEntityFacts.scoreboardName((Player) (Object) this, original::call); }
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void phoenium$abilities(Level level, GameProfile profile, CallbackInfo ci) {
        ((FullPlayerAbilitiesAccess) abilities).phoenium$abilitiesOwner((Player) (Object) this);
    }
}
