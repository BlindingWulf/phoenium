package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.EngineAutosave;
import dev.phoenium.engine.EngineTimers;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * S9-E8 (engine mode only): a level tick's server-thread sections for {@link EngineTimers}. S9-E8b: priority 3000, applied
 * after the engine's own ServerLevel wrappers (2000), so each timer is the outermost wrapper of its call and times the
 * whole section; at the default priority the entities timer sat inside the tile-phase wrapper and timed only the
 * classification pass.
 */
@Mixin(value = ServerLevel.class, priority = 3000)
abstract class EngineLevelTimersMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V"))
    private void phoenium$timeLevelTicks(LevelTicks<?> ticks, long gameTime, int max, BiConsumer<?, ?> runner, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(ticks, gameTime, max, runner); } finally { EngineTimers.add(EngineTimers.LEVEL_TICKS, start); }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/raid/Raids;tick(Lnet/minecraft/server/level/ServerLevel;)V"))
    private void phoenium$timeRaids(Raids raids, ServerLevel level, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(raids, level); } finally { EngineTimers.add(EngineTimers.RAIDS, start); }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"))
    private void phoenium$timeChunkSource(ServerChunkCache cache, BooleanSupplier haveTime, boolean tickChunks, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(cache, haveTime, tickChunks); } finally { EngineTimers.add(EngineTimers.CHUNK_SOURCE, start); }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;runBlockEvents()V"))
    private void phoenium$timeBlockEvents(ServerLevel level, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(level); } finally { EngineTimers.add(EngineTimers.BLOCK_EVENTS, start); }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void phoenium$timeEntities(EntityTickList list, Consumer<Entity> body, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(list, body); } finally { EngineTimers.add(EngineTimers.ENTITIES, start); }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickBlockEntities()V"))
    private void phoenium$timeBlockEntities(ServerLevel level, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(level); } finally { EngineTimers.add(EngineTimers.BLOCK_ENTITIES, start); }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;tick()V"))
    private void phoenium$timeEntityManager(PersistentEntitySectionManager<?> manager, Operation<Void> original) {
        long start = EngineTimers.start();
        try {
            original.call(manager);
            // S9-E12: a spreading autosave's next entity stores, unless the level is noSave (save-off), as vanilla skips saving then
            if (!((ServerLevel) (Object) this).noSave()) ((EngineAutosave.EntityStores) manager).phoenium$drainEntityStores();
        } finally { EngineTimers.add(EngineTimers.ENTITY_MANAGER, start); }
    }
}
