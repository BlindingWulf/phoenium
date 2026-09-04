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

/** Stop-condition witness, not FUT acceptance. The constructorless fixture initializes only the
 * field read by the actual private marker methods and fatal recovery branch. No world or I/O is used. */
final class ChunkMetadataVanillaContractTest {
    @org.junit.jupiter.api.Test void stop07CurrentFullProtectionDiffersFromRealVanillaRecovery() throws Exception {
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
        require(metadata.markPosition(lifecycle, "target", ChunkTypeMetadataService.FULL).previous() == -1, "service missing previous");
        require(metadata.markPositionReplaceable(lifecycle, "target").previous() == 1, "service previous FULL");
        require(metadata.value("target").orElseThrow() == 1, "service retains FULL");
        require(metadata.markPosition(lifecycle, "target", ChunkTypeMetadataService.FULL).previous() == 1, "service subsequent previous FULL");
    }
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
