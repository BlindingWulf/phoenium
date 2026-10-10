package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import java.util.Optional;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * S9-E1 (engine mode only): a tile reads POI sections without writing the storage. A section of a loaded column is
 * answered from the map, as vanilla does. S9-E3: a column that is not loaded, or a section to create, is a service:
 * the server thread runs the vanilla method while no tile runs (services off: a trap, answered empty / created in place).
 * POI changes from a tile are deferred (ServerLevel.updatePOIOnBlockStateChange, PoiManager.release; S9-E4 dirty marks).
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
        return TileEngine.storage(context, "load", sectionPos, () -> original.call(sectionPos), Optional::empty);
    }

    /**
     * S9-E4: a POI column a tile loads without getOrLoad (Lithium's column lookups call unpackChunk directly) is loaded
     * by the server thread too: the load writes the storage map and queues village-distance tracker updates. Services off:
     * loaded in place, counted as a trap.
     */
    @WrapMethod(method = "unpackChunk(Lnet/minecraft/world/level/ChunkPos;)V")
    private void phoenium$tileUnpack(ChunkPos pos, Operation<Void> original) {
        TileContext context = TileEngine.current();
        if (context == null) {
            original.call(pos);
            return;
        }
        TileEngine.storage(context, "unpack", SectionPos.asLong(pos.x(), 0, pos.z()), () -> {
            original.call(pos);
            return null;
        }, () -> {
            original.call(pos);
            return null;
        });
    }

    /** S9-E4: a section marked dirty from a tile joins the shared dirty set after the colour, on the server thread. */
    @WrapMethod(method = "setDirty")
    private void phoenium$deferDirty(long sectionPos, Operation<Void> original) {
        TileContext context = TileEngine.current();
        if (context == null) {
            original.call(sectionPos);
            return;
        }
        context.defer(() -> original.call(sectionPos));
    }

    @WrapMethod(method = "getOrCreate")
    private R phoenium$tileCreate(long sectionPos, Operation<R> original) {
        TileContext context = TileEngine.current();
        if (context == null) return original.call(sectionPos);
        return TileEngine.storage(context, "create", sectionPos, () -> original.call(sectionPos), () -> original.call(sectionPos));
    }
}
