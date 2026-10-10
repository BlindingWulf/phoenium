package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import java.util.Optional;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * S9-E1 (engine mode only): a tile reads POI sections without writing the storage. A section of a loaded column is
 * answered from the map, as vanilla does; a column that is not loaded is a trap (counted, answered empty) instead of a
 * load from a tile thread. POI changes from a tile are deferred (ServerLevel.updatePOIOnBlockStateChange,
 * PoiManager.release), so no tile creates a section; one that does is counted as a trap.
 */
@Mixin(value = SectionStorage.class, priority = 2000)
abstract class EngineSectionStorageMixin<R, P> {
    @Shadow protected abstract @Nullable Optional<R> get(long sectionPos);

    @Shadow protected abstract boolean outsideStoredRange(long sectionPos);

    @WrapMethod(method = "getOrLoad")
    private Optional<R> phoenium$tileRead(long sectionPos, Operation<Optional<R>> original) {
        TileContext context = TileEngine.current();
        if (context == null) return original.call(sectionPos);
        if (this.outsideStoredRange(sectionPos)) return Optional.empty();
        Optional<R> loaded = this.get(sectionPos);
        if (loaded != null) return loaded;
        TileEngine.storageTrap(context, "load", sectionPos);
        return Optional.empty();
    }

    @WrapMethod(method = "getOrCreate")
    private R phoenium$tileCreate(long sectionPos, Operation<R> original) {
        TileContext context = TileEngine.current();
        if (context != null) TileEngine.storageTrap(context, "create", sectionPos);
        return original.call(sectionPos);
    }
}
