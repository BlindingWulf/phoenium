package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.phoenium.chunk.*;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** Exact addressability differs from construction and removal state. */
@Mixin(EntityLookup.class)
abstract class FullEntityFactLookupMixin<T extends EntityAccess> {
    @WrapMethod(method = "add")
    private void phoenium$add(T value, Operation<Void> original) {
        if (!(value instanceof Entity entity) || !(entity.level() instanceof ServerLevel level)) { original.call(value); return; }
        try (var turn = FullFinishingServices.forServer(level.getServer()).referenceAccess()) { original.call(value); }
    }
    @WrapMethod(method = "remove")
    private void phoenium$remove(T value, Operation<Void> original) {
        if (!(value instanceof Entity entity) || !(entity.level() instanceof ServerLevel level)) { original.call(value); return; }
        try (var turn = FullFinishingServices.forServer(level.getServer()).referenceAccess()) { original.call(value); }
    }
    @WrapOperation(method = "add", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), require = 1)
    private Object phoenium$inserted(Map<Object, Object> map, Object key, Object value, Operation<Object> original) {
        Object prior = original.call(map, key, value); if (value instanceof Entity entity) FullEntityFacts.inserted(entity); return prior;
    }
    @WrapOperation(method = "remove", at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"), require = 1)
    private Object phoenium$removed(Map<Object, Object> map, Object key, Operation<Object> original) {
        Object removed = original.call(map, key); if (removed instanceof Entity entity) FullEntityFacts.removed(entity, (UUID) key); return removed;
    }
}
