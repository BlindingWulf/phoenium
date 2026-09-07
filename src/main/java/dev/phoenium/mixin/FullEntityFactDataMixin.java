package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.phoenium.chunk.FullEntityFacts;
import java.util.List;
import net.minecraft.network.syncher.*;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** Publish health/flags/pose data at each original value write, before its virtual callback. */
@Mixin(SynchedEntityData.class)
abstract class FullEntityFactDataMixin {
    @Shadow @Final private SyncedDataHolder entity;
    @WrapMethod(method = "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V")
    private <T> void phoenium$dataWriter(EntityDataAccessor<T> key, T value, boolean force, Operation<Void> original) {
        try (var turn = entity instanceof Entity current ? FullEntityFacts.writer(current) : null) { original.call(key, value, force); }
    }
    @WrapMethod(method = "assignValues")
    private void phoenium$assigned(List<SynchedEntityData.DataValue<?>> values, Operation<Void> original) {
        try (var turn = entity instanceof Entity current ? FullEntityFacts.writer(current) : null) { original.call(values); }
    }
    @WrapOperation(method = {"set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V", "assignValues"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/syncher/SyncedDataHolder;onSyncedDataUpdated(Lnet/minecraft/network/syncher/EntityDataAccessor;)V"), require = 2)
    private void phoenium$publishedBeforeCallback(SyncedDataHolder holder, EntityDataAccessor<?> key, Operation<Void> original) {
        if (holder instanceof Entity current) FullEntityFacts.publish(current);
        try { original.call(holder, key); }
        finally { if (holder instanceof Entity current) FullEntityFacts.publish(current); }
    }
}
