package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.phoenium.live.CommandOwnerConfinement;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * In-owner selectors see only entities owned by the current region. The predicate confines before sort/limit;
 * the result filters also cover the player-name and UUID paths, which bypass the predicate.
 */
@Mixin(EntitySelector.class)
abstract class CommandSelectorConfinementMixin {
    @ModifyReturnValue(method = "getPredicate", at = @At("RETURN"), require = 1)
    private Predicate<Entity> phoenium$confinePredicate(Predicate<Entity> predicate) {
        return CommandOwnerConfinement.confining() ? predicate.and(CommandOwnerConfinement::admitsEntity) : predicate;
    }

    @ModifyReturnValue(method = "findEntities", at = @At("RETURN"), require = 1)
    private List<? extends Entity> phoenium$confineEntities(List<? extends Entity> entities) {
        return CommandOwnerConfinement.filter(entities);
    }

    @ModifyReturnValue(method = "findPlayers", at = @At("RETURN"), require = 1)
    private List<ServerPlayer> phoenium$confinePlayers(List<ServerPlayer> players) {
        return CommandOwnerConfinement.filter(players);
    }
}
