package dev.phoenium.acceptance;

import dev.phoenium.chunk.FullRuntimeAcceptanceProbe;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only observation of the original scoreboard membership calls during saved entity loading. */
@Mixin(Scoreboard.class)
abstract class FullRuntimeAcceptanceScoreboardMixin {
    @Inject(method = "addPlayerToTeam", at = @At("HEAD"))
    private void phoenium$addBefore(String member, PlayerTeam team, CallbackInfoReturnable<Boolean> callback) {
        FullRuntimeAcceptanceProbe.scoreboardAddBefore((Scoreboard) (Object) this, member, team);
    }

    @Inject(method = "addPlayerToTeam", at = @At("RETURN"))
    private void phoenium$addReturn(String member, PlayerTeam team, CallbackInfoReturnable<Boolean> callback) {
        FullRuntimeAcceptanceProbe.scoreboardAddReturn((Scoreboard) (Object) this, member, team,
                callback.getReturnValue());
    }

    @Inject(method = "removePlayerFromTeam(Ljava/lang/String;)Z", at = @At("RETURN"))
    private void phoenium$removeSingleReturn(String member, CallbackInfoReturnable<Boolean> callback) {
        FullRuntimeAcceptanceProbe.scoreboardRemoveSingleReturn((Scoreboard) (Object) this, member,
                callback.getReturnValue());
    }

    @Inject(method = "removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V", at = @At("HEAD"))
    private void phoenium$removeBefore(String member, PlayerTeam team, CallbackInfo callback) {
        FullRuntimeAcceptanceProbe.scoreboardRemoveBefore((Scoreboard) (Object) this, member, team);
    }

    @Inject(method = "removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V", at = @At("RETURN"))
    private void phoenium$removeReturn(String member, PlayerTeam team, CallbackInfo callback) {
        FullRuntimeAcceptanceProbe.scoreboardRemoveReturn((Scoreboard) (Object) this, member, team);
    }
}
