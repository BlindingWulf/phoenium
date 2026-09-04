package dev.phoenium.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionStorage.class)
public interface PoiStorageAccess {
    @Accessor("loadLock") Object phoenium$loadLock();
    @Accessor("pendingLoads") Long2ObjectMap<CompletableFuture<Optional<?>>> phoenium$pendingLoads();
    @Accessor("loadedChunks") LongSet phoenium$loadedChunks();
}
