package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.service.RegionalEntityStorageWorld;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PersistentEntitySectionManager.class)
abstract class RegionalEntityStorageMixin<T extends EntityAccess> {
    @Shadow @Final private LevelCallback<T> callbacks;
    @Shadow @Final private EntitySectionStorage<T> sectionStorage;

    @WrapOperation(method = {"processPendingLoads", "addLegacyChunkEntities"},
        at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
            target = "Ljava/util/stream/Stream;forEach(Ljava/util/function/Consumer;)V"), require = 2, allow = 2)
    private void phoenium$loadedInputs(java.util.stream.Stream<T> stream, java.util.function.Consumer<T> consumer,
            Operation<Void> original) {
        if (!(callbacks instanceof RegionalEntityStorageWorld source))
            throw new IllegalStateException("native loaded batch lost its world callback identity");
        var values = stream.toList();
        var entities = values.stream().map(value -> {
            if (!(value instanceof Entity entity)) throw new IllegalStateException("native loaded entity type changed");
            return entity;
        }).toList();
        var level = source.phoenium$entityStorageWorld();
        try (var turn = FullFinishingServices.forServer(level.getServer()).referenceAccess();
             var batch = dev.phoenium.chunk.FullEntityFacts.loadedBatch(level, entities)) {
            original.call(values.stream(), consumer);
        }
    }

    @WrapOperation(method = "addEntity(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z",
        at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntitySection;add(Lnet/minecraft/world/level/entity/EntityAccess;)V"))
    private void phoenium$stored(net.minecraft.world.level.entity.EntitySection<T> section,
            T value, Operation<Void> original) {
        original.call(section, value);
        if (value instanceof Entity entity) dev.phoenium.chunk.FullEntityFacts.stored(entity, this);
    }

    @WrapMethod(method = "storeChunkSections(JLjava/util/function/Consumer;)Z")
    private boolean phoenium$store(long chunkKey, java.util.function.Consumer<T> visitor, Operation<Boolean> original) {
        if (!(callbacks instanceof RegionalEntityStorageWorld source))
            throw new IllegalStateException("native entity storage lost its world callback identity");
        var level = source.phoenium$entityStorageWorld();
        try (var turn = FullFinishingServices.forServer(level.getServer()).referenceAccess()) {
            Boolean applied = Phoenium.liveRuntime().regionalEntityStorageSave(level, chunkKey,
                () -> sectionStorage.getExistingSectionsInChunk(chunkKey).flatMap(section -> section.getEntities())
                    .map(value -> {
                        if (!(value instanceof Entity entity)) throw new IllegalStateException("native entity section source changed");
                        return entity;
                    }).toList(),
                () -> original.call(chunkKey, visitor));
            return applied != null ? applied : original.call(chunkKey, visitor);
        }
    }
}
