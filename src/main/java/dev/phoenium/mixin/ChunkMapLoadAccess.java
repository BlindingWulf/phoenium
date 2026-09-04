package dev.phoenium.mixin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapLoadAccess {
    @Accessor("level") ServerLevel phoenium$level();
    @Accessor("mainThreadExecutor") BlockableEventLoop<Runnable> phoenium$mainExecutor();
    @Accessor("poiManager") PoiManager phoenium$poi();
    @Invoker("readChunk") CompletableFuture<Optional<CompoundTag>> phoenium$read(ChunkPos position);
    @Invoker("lambda$scheduleChunkLoad$0") Optional<SerializableChunkData> phoenium$parse(ChunkPos position,Optional<CompoundTag> data);
    @Invoker("lambda$scheduleChunkLoad$2") static Optional<SerializableChunkData> phoenium$combine(Optional<SerializableChunkData> data,Object ignored) { throw new AssertionError(); }
    @Invoker("lambda$scheduleChunkLoad$3") ChunkAccess phoenium$apply(ChunkPos position,Optional<SerializableChunkData> data);
    @Invoker("lambda$scheduleChunkLoad$4") ChunkAccess phoenium$recover(ChunkPos position,Throwable failure);
}
