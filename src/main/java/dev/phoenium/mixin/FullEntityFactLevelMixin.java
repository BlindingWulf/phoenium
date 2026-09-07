package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.*;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerLevel.class)
abstract class FullEntityFactLevelMixin {
    @WrapMethod(method = "getEntityInAnyDimension")
    private Entity phoenium$lookup(UUID id, Operation<Entity> original) {
        if (!FullFinishingServices.finishing()) return original.call(id);
        ServerLevel level = (ServerLevel) (Object) this;
        return FullFinishingServices.forServer(level.getServer()).entityFacts.lookup(level, id);
    }
    @WrapMethod(method = "getPlayerInAnyDimension")
    private Player phoenium$player(UUID id, Operation<Player> original) {
        if (!FullFinishingServices.finishing()) return original.call(id);
        ServerLevel level = (ServerLevel) (Object) this;
        return FullFinishingServices.forServer(level.getServer()).scoreboard.lookupPlayer(id);
    }
}
