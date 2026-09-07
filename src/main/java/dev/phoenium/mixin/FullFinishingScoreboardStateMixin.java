package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.phoenium.chunk.*;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import java.util.*;
import net.minecraft.world.scores.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Each committed map/set prefix is observed before the next original notification edge. */
@Mixin(Scoreboard.class)
abstract class FullFinishingScoreboardStateMixin {
    @WrapOperation(method = "addPlayerToTeam", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), require = 1)
    private Object phoenium$mappingPut(Object2ObjectMap<Object, Object> map, Object key, Object value, Operation<Object> original) {
        Object old = original.call(map, key, value); phoenium$primitive(); return old;
    }
    @WrapOperation(method = "addPlayerToTeam", at = @At(value = "INVOKE", target = "Ljava/util/Collection;add(Ljava/lang/Object;)Z"), require = 1)
    private boolean phoenium$memberAdd(Collection<Object> members, Object member, Operation<Boolean> original) {
        boolean added = original.call(members, member); phoenium$primitive(); return added;
    }
    @WrapOperation(method = "removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;remove(Ljava/lang/Object;)Ljava/lang/Object;"), require = 1)
    private Object phoenium$mappingRemove(Object2ObjectMap<Object, Object> map, Object key, Operation<Object> original) {
        Object old = original.call(map, key); phoenium$primitive(); return old;
    }
    @WrapOperation(method = "removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Collection;remove(Ljava/lang/Object;)Z"), require = 1)
    private boolean phoenium$memberRemove(Collection<Object> members, Object member, Operation<Boolean> original) {
        boolean removed = original.call(members, member); phoenium$primitive(); return removed;
    }
    @org.spongepowered.asm.mixin.Unique private void phoenium$primitive() {
        if (this instanceof FullScoreboardAccess access) FullFinishingServices.forServer(access.phoenium$scoreboardServer()).scoreboard.primitive();
    }
    @WrapMethod(method = "addPlayerTeam")
    private PlayerTeam phoenium$team(String name, Operation<PlayerTeam> original) {
        if (!(this instanceof FullScoreboardAccess access)) return original.call(name);
        try (var guard = FullFinishingServices.forServer(access.phoenium$scoreboardServer()).scoreboardAccess()) { return original.call(name); }
    }
    @WrapMethod(method = "removePlayerTeam")
    private void phoenium$removeTeam(PlayerTeam team, Operation<Void> original) {
        if (!(this instanceof FullScoreboardAccess access)) { original.call(team); return; }
        try (var guard = FullFinishingServices.forServer(access.phoenium$scoreboardServer()).scoreboardAccess()) { original.call(team); }
    }
    @WrapMethod(method = {"getPlayerTeam", "getPlayersTeam"})
    private PlayerTeam phoenium$teamRead(String name, Operation<PlayerTeam> original) {
        if (!(this instanceof FullScoreboardAccess access)) return original.call(name);
        try (var guard = FullFinishingServices.forServer(access.phoenium$scoreboardServer()).scoreboardAccess()) { return original.call(name); }
    }
}
