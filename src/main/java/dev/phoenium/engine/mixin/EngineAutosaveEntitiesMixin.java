package dev.phoenium.engine.mixin;

import dev.phoenium.engine.EngineAutosave;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.function.Consumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S9-E12 (engine mode only): a spreading autosave's PersistentEntitySectionManager.autoSave() records the keys of the
 * chunks it would store; after each tick() (where vanilla unloads) of a level that is not noSave (save-off), ServerLevel
 * runs vanilla's per-chunk step for the next keys: unload a hidden chunk, else store its entities ({@link EngineAutosave}). saveAll() (shutdown, flush) and a
 * non-spreading autoSave run as vanilla and drop the queue.
 */
@Mixin(PersistentEntitySectionManager.class)
abstract class EngineAutosaveEntitiesMixin<T extends EntityAccess> implements EngineAutosave.EntityStores {
    @Shadow @Final private Long2ObjectMap<Visibility> chunkVisibility;

    @Unique private final EngineAutosave.Spread phoenium$stores = new EngineAutosave.Spread();

    @Shadow private LongSet getAllChunksToSave() { throw new AssertionError(); }

    @Shadow private boolean processChunkUnload(long chunkKey) { throw new AssertionError(); }

    @Shadow private boolean storeChunkSections(long chunkKey, Consumer<T> savedEntityVisitor) { throw new AssertionError(); }

    @Inject(method = "autoSave", at = @At("HEAD"), cancellable = true)
    private void phoenium$recordEntityStores(CallbackInfo ci) {
        if (EngineAutosave.recordEntities()) {
            EngineAutosave.recordedEntities(this.phoenium$stores, this.getAllChunksToSave());
            ci.cancel();
            return;
        }
        EngineAutosave.fullSaveEntities(this.phoenium$stores);
    }

    @Inject(method = "saveAll", at = @At("HEAD"))
    private void phoenium$fullEntitySave(CallbackInfo ci) {
        EngineAutosave.fullSaveEntities(this.phoenium$stores);
    }

    @Override
    public void phoenium$drainEntityStores() {
        if (!this.phoenium$stores.busy()) return;
        // S9-E15: each key's outcome is counted (stored, hidden chunk stored and unloaded, or pending: load in flight)
        EngineAutosave.drainEntities(this.phoenium$stores, key -> this.chunkVisibility.get(key) == Visibility.HIDDEN
            ? EngineAutosave.entityOutcome(this.processChunkUnload(key) ? EngineAutosave.ENTITY_UNLOADED : EngineAutosave.ENTITY_PENDING)
            : EngineAutosave.entityOutcome(this.storeChunkSections(key, entity -> { }) ? EngineAutosave.ENTITY_STORED : EngineAutosave.ENTITY_PENDING));
    }
}
