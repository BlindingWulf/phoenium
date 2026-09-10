package dev.phoenium.chunk;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Narrow bridge for the locked ScalableLux persisted-light implementation.
 *
 * ScalableLux replaces the vanilla threaded light engine with the exact provider class below.
 * Its persisted-light read hook owns the saved nibble arrays, so its overrides of retainData and
 * queueSectionData intentionally return without inserting a vanilla task. The normal light
 * ingress protocol still remains strict for every other engine; this adapter acknowledges only
 * that exact provider and then verifies the provider-owned chunk state after SerializableChunkData
 * has returned.
 */
public final class ScalableLuxLightBridge {
    private static final String PROVIDER = "ca.spottedleaf.starlight.common.light.StarLightLightingProvider";
    private static final String ENGINE = "ca.spottedleaf.starlight.common.light.vanillainterface.ThreadedLevelLightEngineVanillaInterface";
    private static final String CHUNK = "ca.spottedleaf.starlight.common.chunk.ExtendedChunk";

    private ScalableLuxLightBridge() { }

    @FunctionalInterface
    public interface PhysicalCall { void call() throws Throwable; }

    /** True only for ScalableLux's concrete vanilla-compatible engine, never for a generic subclass. */
    public static boolean ownsPersistedReload(LevelLightEngine engine) {
        if (engine == null || !ENGINE.equals(engine.getClass().getName())) return false;
        try {
            Class<?> provider = Class.forName(PROVIDER, false, engine.getClass().getClassLoader());
            return provider.isInstance(engine);
        } catch (ClassNotFoundException | LinkageError missingProvider) {
            return false;
        }
    }

    /** Runs the original call and records provider delegation only after a normal return. */
    public static void submit(LevelLightEngine engine, LightInputService.Insertion insertion,
                              PhysicalCall original) throws Throwable {
        Objects.requireNonNull(insertion); Objects.requireNonNull(original);
        if (!ownsPersistedReload(engine)) {
            original.call();
            return;
        }
        original.call();
        insertion.delegated();
    }

    /**
     * ScalableLux's SerializableChunkData return hook must have installed its own nibble arrays
     * before the Phoenium read receipt is sealed. Reflection keeps ScalableLux optional at compile
     * time while still making a recognized provider failure closed and observable.
     */
    public static void requireReloadState(ServerLevel level, ProtoChunk result) {
        Objects.requireNonNull(level);
        LevelLightEngine engine = level.getLightEngine();
        if (!ownsPersistedReload(engine)) return;
        Objects.requireNonNull(result);

        Class<?> extended = namedInterface(CHUNK, result.getClass().getClassLoader());
        if (!extended.isInstance(result))
            throw new ChunkProtocolException("ScalableLux persisted reload returned a non-extended chunk");
        Object using = invoke(extended, result, "scalablelux$usingStarlight");
        if (!Boolean.TRUE.equals(using))
            throw new ChunkProtocolException("ScalableLux persisted reload did not retain provider ownership");
        requireArray(extended, result, "scalablelux$getBlockNibbles", "block");
        if (level.dimensionType().hasSkyLight())
            requireArray(extended, result, "scalablelux$getSkyNibbles", "sky");
    }

    private static Class<?> namedInterface(String name, ClassLoader loader) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException | LinkageError failure) {
            throw new ChunkProtocolException("recognized ScalableLux provider is missing " + name, failure);
        }
    }

    private static void requireArray(Class<?> owner, ChunkAccess chunk, String method, String layer) {
        Object value = invoke(owner, chunk, method);
        if (value == null || !value.getClass().isArray() || Array.getLength(value) == 0)
            throw new ChunkProtocolException("ScalableLux persisted reload has no " + layer + " nibble array");
    }

    private static Object invoke(Class<?> owner, Object receiver, String name) {
        try {
            Method method = owner.getMethod(name);
            return method.invoke(receiver);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new ChunkProtocolException("ScalableLux persisted reload state query failed: " + name, cause);
        } catch (ReflectiveOperationException failure) {
            throw new ChunkProtocolException("ScalableLux persisted reload state query is unavailable: " + name, failure);
        }
    }
}
