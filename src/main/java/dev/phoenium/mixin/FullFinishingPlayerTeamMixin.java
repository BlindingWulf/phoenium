package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.phoenium.chunk.*;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** Existing properties are written inside their serial service operation, before callbacks. */
@Mixin(PlayerTeam.class)
abstract class FullFinishingPlayerTeamMixin {
    @Shadow @Final private Scoreboard scoreboard;
    @Unique private FullFinishingServices.Access phoenium$turn() {
        return scoreboard instanceof FullScoreboardAccess access
                ? FullFinishingServices.forServer(access.phoenium$scoreboardServer()).scoreboardAccess() : null;
    }
    @WrapMethod(method = {"setDisplayName", "setPlayerPrefix", "setPlayerSuffix"})
    private void phoenium$component(Component value, Operation<Void> original) { try (var turn = phoenium$turn()) { original.call(value); } }
    @WrapMethod(method = {"setAllowFriendlyFire", "setSeeFriendlyInvisibles"})
    private void phoenium$flag(boolean value, Operation<Void> original) { try (var turn = phoenium$turn()) { original.call(value); } }
    @WrapMethod(method = {"setNameTagVisibility", "setDeathMessageVisibility"})
    private void phoenium$visibility(Team.Visibility value, Operation<Void> original) { try (var turn = phoenium$turn()) { original.call(value); } }
    @WrapMethod(method = "setCollisionRule")
    private void phoenium$collision(Team.CollisionRule value, Operation<Void> original) { try (var turn = phoenium$turn()) { original.call(value); } }
    @WrapMethod(method = "setColor")
    private void phoenium$color(Optional<TeamColor> value, Operation<Void> original) { try (var turn = phoenium$turn()) { original.call(value); } }
    @WrapOperation(method = {"setDisplayName", "setPlayerPrefix", "setPlayerSuffix", "setAllowFriendlyFire", "setSeeFriendlyInvisibles",
            "setNameTagVisibility", "setDeathMessageVisibility", "setCollisionRule", "setColor"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/scores/Scoreboard;onTeamChanged(Lnet/minecraft/world/scores/PlayerTeam;)V"), require = 9)
    private void phoenium$propertyPublished(Scoreboard target, PlayerTeam team, Operation<Void> original) {
        if (target instanceof FullScoreboardAccess access) FullFinishingServices.forServer(access.phoenium$scoreboardServer()).scoreboard.primitive();
        original.call(target, team);
    }
}
