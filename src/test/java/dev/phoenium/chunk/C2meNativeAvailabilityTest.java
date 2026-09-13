package dev.phoenium.chunk;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.jar.JarFile;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;

/** Execute the packaged native getter; only its input future is supplied by the fixture. */
class C2meNativeAvailabilityTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    @TempDir Path temporary;
    private static final Path ORIGINAL = Path.of("build/stage6-audit-20260909/research/artifacts/c2me/c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar");
    private static final Path PATCHED = Path.of("../C2ME-locked-settlement/build/patched/c2me-0.4.2-alpha.0.43+26.2-phoenium-settlement2.jar");

    @Test void originalNullableGetterThrowsWhenExplicitRequestFailed() throws Exception {
        try (var fixture = fixture(ORIGINAL)) {
            var cause = new RuntimeException("exact native failure");
            fixture.future(CompletableFuture.failedFuture(cause));
            var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class, fixture::get);
            assertInstanceOf(CompletionException.class, thrown.getCause());
            assertSame(cause, thrown.getCause().getCause());
        }
    }

    @Test void patchedNullableGetterPreservesRequestErrorAndReturnsOnlyAvailableChunk() throws Exception {
        try (var fixture = fixture(PATCHED)) {
            var pending = new CompletableFuture<>();
            fixture.future(pending);
            assertNull(fixture.get());
            assertFalse(pending.isDone());
            var cause = new RuntimeException("exact native failure");
            var failed = CompletableFuture.failedFuture(cause);
            fixture.future(failed);
            assertNull(fixture.get());
            assertSame(cause, failed.exceptionNow());
            var cancelled = new CompletableFuture<>();
            cancelled.cancel(false);
            fixture.future(cancelled);
            assertNull(fixture.get());
            assertTrue(cancelled.isCancelled());
            LevelChunk chunk = (LevelChunk) unsafe().allocateInstance(LevelChunk.class);
            fixture.future(CompletableFuture.completedFuture(ChunkResult.of(chunk)));
            assertSame(chunk, fixture.get());
        }
    }

    private Fixture fixture(Path artifact) throws Exception {
        var urls = new ArrayList<URL>();
        try (var outer = new JarFile(artifact.toFile())) {
            for (var entry : Collections.list(outer.entries())) {
                if (!entry.getName().startsWith("META-INF/jars/") || !entry.getName().endsWith(".jar")) continue;
                Path target = temporary.resolve(artifact.getFileName() + "-" + Path.of(entry.getName()).getFileName());
                try (var input = outer.getInputStream(entry)) { Files.copy(input, target); }
                urls.add(target.toUri().toURL());
            }
        }
        var loader = new ProviderLoader(urls.toArray(URL[]::new));
        try {
            Class<?> type = loader.define(subclass());
            return new Fixture(loader, type, unsafe().allocateInstance(type));
        } catch (Throwable failure) { loader.close(); throw failure; }
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private record Fixture(ProviderLoader loader, Class<?> type, Object holder) implements AutoCloseable {
        void future(CompletableFuture<?> value) throws Exception { type.getField("future").set(null, value); }
        Object get() throws Exception { return type.getMethod("getTickingChunk").invoke(holder); }
        public void close() throws Exception { loader.close(); }
    }

    private static final class ProviderLoader extends URLClassLoader {
        ProviderLoader(URL[] urls) { super(urls, C2meNativeAvailabilityTest.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = name.startsWith("com.ishland.") ? findClass(name) : super.loadClass(name, false);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
        Class<?> define(byte[] bytes) { return defineClass("phoeniumtest.NativeAvailabilityHolder", bytes, 0, bytes.length); }
    }

    private static byte[] subclass() {
        String self = "phoeniumtest/NativeAvailabilityHolder";
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V25, ACC_PUBLIC | ACC_SUPER, self, null,
                "com/ishland/c2me/rewrites/chunksystem/common/NewChunkHolderVanillaInterface", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "future", "Ljava/util/concurrent/CompletableFuture;", null, null).visitEnd();
        var method = writer.visitMethod(ACC_PUBLIC, "getTickingChunkFuture", "()Ljava/util/concurrent/CompletableFuture;", null, null);
        method.visitCode();
        method.visitFieldInsn(GETSTATIC, self, "future", "Ljava/util/concurrent/CompletableFuture;");
        method.visitInsn(ARETURN); method.visitMaxs(0, 0); method.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
}
