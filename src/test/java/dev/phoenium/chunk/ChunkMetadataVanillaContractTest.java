package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import dev.phoenium.topology.RoutingKey;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkType;

/** Real vanilla parity regression, not FUT acceptance. The constructorless fixture initializes only the
 * field read by the actual private marker methods and fatal recovery branch. No world or I/O is used. */
final class ChunkMetadataVanillaContractTest {
    @org.junit.jupiter.api.Test void currentMetadataMatchesRealVanillaRecovery() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        ChunkMap map = (ChunkMap) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, ChunkMap.class);
        var cache = new Long2ByteOpenHashMap();
        Field cacheField = ChunkMap.class.getDeclaredField("chunkTypeCache");
        cacheField.setAccessible(true);
        cacheField.set(map, cache);
        var pos = new ChunkPos(32, 4);
        Method mark = ChunkMap.class.getDeclaredMethod("markPosition", ChunkPos.class, ChunkType.class);
        Method replace = ChunkMap.class.getDeclaredMethod("markPositionReplaceable", ChunkPos.class);
        Method recovery = ChunkMap.class.getDeclaredMethod("lambda$scheduleChunkLoad$4", ChunkPos.class, Throwable.class);
        mark.setAccessible(true); replace.setAccessible(true); recovery.setAccessible(true);
        byte missingPrevious = (byte) mark.invoke(map, pos, ChunkType.LEVELCHUNK);
        require(missingPrevious == 0, "vanilla missing previous");
        replace.invoke(map, pos);
        require(cache.get(pos.pack()) == -1, "vanilla FULL then replaceable");
        byte replacePrevious = (byte) mark.invoke(map, pos, ChunkType.LEVELCHUNK);
        require(replacePrevious == -1, "vanilla replacement previous");
        Error exactError = new AssertionError("disposable metadata contract probe");
        var transportedError = new java.util.concurrent.CompletionException(exactError);
        try {
            recovery.invoke(map, pos, transportedError);
            throw new AssertionError("fatal recovery must throw");
        } catch (InvocationTargetException result) {
            require(result.getCause() instanceof net.minecraft.ReportedException, "real fatal recovery classification");
            // Vanilla CrashReport.forThrowable removes CompletionException transport itself.
            require(result.getCause().getCause() == exactError, "real fatal recovery cause");
        }
        require(cache.get(pos.pack()) == -1, "real fatal recovery invalidates FULL");
        var world = new WorldId("probe");
        var services = new ChunkPrerequisiteServices(world, "metadata-contract-probe");
        var lifecycle = services.lifecycles().activateHolder(0, new RoutingKey(world, 32, 4), new Object());
        var metadata = services.metadata();
        var work = services.works().beginOrResolveSharedWork(lifecycle, new ChunkWorkRegistry.WorkReservation("load", "parsed", 0));
        require(metadata.markPosition(work, lifecycle.chunkKey(), ChunkTypeMetadataService.Marker.FULL).previous() == 0, "service missing previous");
        require(metadata.markPositionReplaceable(work, lifecycle.chunkKey()).previous() == 1, "service previous FULL");
        require(metadata.value(lifecycle.chunkKey()).orElseThrow() == -1, "service replaces FULL");
        require(metadata.markPosition(work, lifecycle.chunkKey(), ChunkTypeMetadataService.Marker.FULL).previous() == -1, "service subsequent previous replacement");
        // Bind the real private recovery method's map operation to the normal typed facade.
        // This map has no second backing cache and cannot bypass authorization.
        cacheField.set(map, new Long2ByteOpenHashMap() {
            @Override public byte put(long key, byte value) {
                require(key == pos.pack(), "exact bound recovery target");
                require(value == -1 || value == 1, "closed vanilla marker values");
                var update = value == -1 ? metadata.markPositionReplaceable(work, lifecycle.chunkKey())
                    : metadata.markPosition(work, lifecycle.chunkKey(), ChunkTypeMetadataService.Marker.FULL);
                require(update.result() == ChunkTypeMetadataService.Result.APPLIED, "bound recovery entitlement");
                return update.previous();
            }
        });
        long beforeRecovery = metadata.backingWrites();
        try {
            recovery.invoke(map, pos, transportedError);
            throw new AssertionError("bound fatal recovery must throw");
        } catch (InvocationTargetException result) {
            require(result.getCause() instanceof net.minecraft.ReportedException, "bound fatal recovery classification");
            require(result.getCause().getCause() == exactError, "bound fatal recovery exact cause");
        }
        require(metadata.value(lifecycle.chunkKey()).orElseThrow() == -1, "bound fatal recovery replaces FULL");
        require(metadata.backingWrites() == beforeRecovery + 1, "one bound recovery write");
        require((byte) mark.invoke(map, pos, ChunkType.LEVELCHUNK) == -1, "bound subsequent previous byte");
    }
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
