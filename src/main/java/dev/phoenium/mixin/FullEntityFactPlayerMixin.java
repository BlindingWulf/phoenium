package dev.phoenium.mixin;

import com.mojang.authlib.GameProfile;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.phoenium.chunk.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class FullEntityFactPlayerMixin {
    @Shadow @Final private Abilities abilities;
    @ModifyReturnValue(method = "getScoreboardName()Ljava/lang/String;", at = @At("RETURN"), require = 1)
    private String phoenium$scoreboardName(String own) { return FullEntityFacts.scoreboardName((Player) (Object) this, own); }
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void phoenium$abilities(Level level, GameProfile profile, CallbackInfo ci) {
        ((FullPlayerAbilitiesAccess) abilities).phoenium$abilitiesOwner((Player) (Object) this);
    }
}
