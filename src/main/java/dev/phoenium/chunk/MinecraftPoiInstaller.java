package dev.phoenium.chunk;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;

/** Exact private packed-data overload, resolved once so signature drift fails before first mutation. */
final class MinecraftPoiInstaller {
    private static final MethodHandle INSTALL = resolve();
    private MinecraftPoiInstaller() { }
    static void install(SectionStorage<?, ?> storage, ChunkPos position, Object packed) throws Throwable {
        INSTALL.invoke(storage, position, packed);
    }
    private static MethodHandle resolve() {
        try {
            Class<?> packed = Class.forName("net.minecraft.world.level.chunk.storage.SectionStorage$PackedChunk");
            return MethodHandles.privateLookupIn(SectionStorage.class, MethodHandles.lookup()).findVirtual(
                    SectionStorage.class, "unpackChunk", MethodType.methodType(void.class, ChunkPos.class, packed));
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
