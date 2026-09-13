package dev.phoenium.chunk;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** Locks the DISK read bridge to the exact C2ME binary that was reverse-inspected. */
final class C2meReadProviderBinaryTest {
    private static final Path OUTER = Path.of("build", "stage6-audit-20260909", "research", "artifacts", "c2me",
            "c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar");
    private static final Path REWRITES = Path.of("build", "stage6-audit-20260909", "research", "c2me-mixins",
            "c2me-fabric-rewrites-chunk-system-mc26.2-0.4.2-alpha.0.43.jar");
    private static final String SHA256 = "B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B";
    private static final String REWRITES_SHA256 = "76CEDA178849978682473C285B0A354BF611021EB59DD5FAB7ED18AC327D1F48";
    private static final String READ = "(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;"
            + "Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;"
            + "Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ProtoChunk;";

    @Test void lockedProviderHasExactlyTheTwoDiskParseSitesPhoeniumWraps() throws Exception {
        assertTrue(Files.isRegularFile(OUTER));
        assertEquals(SHA256, sha256(OUTER));
        assertTrue(Files.isRegularFile(REWRITES));
        assertEquals(REWRITES_SHA256, sha256(REWRITES));

        assertEquals(Map.of("lambda$invokeVanillaLoad$0", 1), readSites(read(REWRITES,
                "com/ishland/c2me/rewrites/chunksystem/common/statuses/ReadFromDisk.class")));
        assertEquals(Map.of("lambda$invokeAsyncLoad$0", 1), readSites(read(REWRITES,
                "com/ishland/c2me/rewrites/chunksystem/common/statuses/ReadFromDiskAsync.class")));
    }

    @Test void lockedProviderStillExposesTheExactDiskLineageSurface() throws Exception {
        ClassNode context = read(REWRITES, "com/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext.class");
        assertMethod(context, "holder", "()Lcom/ishland/flowsched/scheduler/ItemHolder;");
        assertMethod(context, "tacs", "()Lnet/minecraft/server/level/ChunkMap;");
        assertMethod(context, "theChunkSystem", "()Lcom/ishland/c2me/rewrites/chunksystem/common/TheChunkSystem;");
        ClassNode status = read(REWRITES, "com/ishland/c2me/rewrites/chunksystem/common/NewChunkStatus.class");
        assertMethod(status, "fromVanillaStatus",
                "(Lnet/minecraft/world/level/chunk/status/ChunkStatus;)Lcom/ishland/c2me/rewrites/chunksystem/common/NewChunkStatus;");
    }

    @Test void lockedAsyncGraphStillHasTheAuditedDeferredDrainShape() throws Exception {
        ClassNode async = read(REWRITES,
                "com/ishland/c2me/rewrites/chunksystem/common/statuses/ReadFromDiskAsync.class");
        MethodNode invoke = method(async, "invokeAsyncLoad");
        var calls = methodCalls(invoke);
        assertEquals(1, count(calls, "io/reactivex/rxjava3/core/Single", "observeOn"));
        assertEquals(2, count(calls, "io/reactivex/rxjava3/core/Single", "map"));
        int observe = indexOf(calls, "io/reactivex/rxjava3/core/Single", "observeOn", 0);
        int firstMap = indexOf(calls, "io/reactivex/rxjava3/core/Single", "map", 0);
        int finalMap = indexOf(calls, "io/reactivex/rxjava3/core/Single", "map", firstMap + 1);
        assertTrue(firstMap < observe && observe < finalMap,
                "locked final map must remain the sole map after observeOn(mainThreadExecutor)");

        MethodNode finalMapper = method(async, "lambda$invokeAsyncLoad$2");
        assertEquals(1, count(methodCalls(finalMapper),
                "com/ishland/c2me/rewrites/chunksystem/common/async_chunkio/ChunkIoMainThreadTaskUtils", "drainQueue"));

        MethodNode parse = method(async, "lambda$invokeAsyncLoad$0");
        var parseCalls = methodCalls(parse);
        assertEquals(1, count(parseCalls,
                "com/ishland/c2me/rewrites/chunksystem/common/async_chunkio/ChunkIoMainThreadTaskUtils", "push"));
        assertEquals(1, count(parseCalls, "net/minecraft/world/level/chunk/storage/SerializableChunkData", "read"));
        // One normal-path pop plus the exact finally/exception-path pop that protects the same
        // captured queue.  Both must remain after the single parse site.
        assertEquals(2, count(parseCalls,
                "com/ishland/c2me/rewrites/chunksystem/common/async_chunkio/ChunkIoMainThreadTaskUtils", "pop"));
        int push = indexOf(parseCalls,
                "com/ishland/c2me/rewrites/chunksystem/common/async_chunkio/ChunkIoMainThreadTaskUtils", "push", 0);
        int read = indexOf(parseCalls, "net/minecraft/world/level/chunk/storage/SerializableChunkData", "read", 0);
        int pop = indexOf(parseCalls,
                "com/ishland/c2me/rewrites/chunksystem/common/async_chunkio/ChunkIoMainThreadTaskUtils", "pop", 0);
        assertTrue(push < read && read < pop, "provider queue capture no longer brackets the single parse");
    }

    @Test void lockedDeferredQueueHasOnlyTheAuditedPoiProducer() throws Exception {
        String utility = "com/ishland/c2me/rewrites/chunksystem/common/async_chunkio/ChunkIoMainThreadTaskUtils";
        java.util.ArrayList<String> executeMainCallers = new java.util.ArrayList<>();
        try (JarFile file = new JarFile(REWRITES.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                ClassNode node = new ClassNode();
                try (InputStream input = file.getInputStream(entry)) {
                    new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                for (MethodNode method : node.methods) {
                    long calls = count(methodCalls(method), utility, "executeMain");
                    for (long i = 0; i < calls; i++) executeMainCallers.add(node.name + "." + method.name);
                }
            }
        }
        assertEquals(java.util.List.of(
                "com/ishland/c2me/rewrites/chunksystem/mixin/async_serialization/MixinChunkSerializer.onPoiStorageInitForPalette"),
                executeMainCallers,
                "locked async deserialize queue gained another deferred-task producer");

        ClassNode serializer = read(REWRITES,
                "com/ishland/c2me/rewrites/chunksystem/mixin/async_serialization/MixinChunkSerializer.class");
        MethodNode producer = method(serializer, "onPoiStorageInitForPalette");
        assertEquals(1, count(methodCalls(producer), utility, "executeMain"));
        MethodNode deferred = method(serializer, "lambda$onPoiStorageInitForPalette$0");
        assertEquals(1, count(methodCalls(deferred),
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call"),
                "locked POI deferred task no longer delegates exactly once to the wrapped vanilla call");
    }

    @Test void lockedAsyncRetryFallbackAndSyncReadRemainPinned() throws Exception {
        ClassNode async = read(REWRITES,
                "com/ishland/c2me/rewrites/chunksystem/common/statuses/ReadFromDiskAsync.class");
        MethodNode upgrade = async.methods.stream().filter(method -> method.name.equals("upgradeToThis")
                && method.desc.startsWith("(Lcom/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext;"))
                .findFirst().orElseThrow();
        var upgradeCalls = methodCalls(upgrade);
        assertEquals(1, count(upgradeCalls,
                "com/ishland/c2me/rewrites/chunksystem/common/statuses/ReadFromDiskAsync", "invokeAsyncLoad"));
        assertEquals(1, count(upgradeCalls, "io/reactivex/rxjava3/core/Single", "retryWhen"));
        assertEquals(1, count(upgradeCalls, "io/reactivex/rxjava3/core/Single", "onErrorResumeNext"));
        assertEquals(1, count(methodCalls(method(async, "lambda$upgradeToThis$0")),
                "com/ishland/c2me/rewrites/chunksystem/common/statuses/ReadFromDiskAsync", "invokeVanillaLoad"));

        ClassNode sync = read(REWRITES,
                "com/ishland/c2me/rewrites/chunksystem/common/statuses/ReadFromDisk.class");
        var syncCalls = methodCalls(method(sync, "lambda$invokeVanillaLoad$0"));
        assertEquals(1, count(syncCalls, "net/minecraft/world/level/chunk/storage/SerializableChunkData", "read"));
        assertEquals(0, count(syncCalls,
                "com/ishland/c2me/rewrites/chunksystem/common/async_chunkio/ChunkIoMainThreadTaskUtils", "push"));
        assertEquals(0, count(syncCalls,
                "com/ishland/c2me/rewrites/chunksystem/common/async_chunkio/ChunkIoMainThreadTaskUtils", "drainQueue"));
    }

    private static Map<String, Integer> readSites(ClassNode node) {
        java.util.HashMap<String, Integer> result = new java.util.HashMap<>();
        for (var method : node.methods) {
            int count = 0;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/world/level/chunk/storage/SerializableChunkData")
                        && call.name.equals("read") && call.desc.equals(READ)) count++;
            }
            if (count != 0) result.put(method.name, count);
        }
        return Map.copyOf(result);
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("missing pinned provider method " + name));
    }

    private static java.util.List<MethodInsnNode> methodCalls(MethodNode method) {
        java.util.ArrayList<MethodInsnNode> result = new java.util.ArrayList<>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call) result.add(call);
        return result;
    }

    private static long count(java.util.List<MethodInsnNode> calls, String owner, String name) {
        return calls.stream().filter(call -> call.owner.equals(owner) && call.name.equals(name)).count();
    }

    private static int indexOf(java.util.List<MethodInsnNode> calls, String owner, String name, int start) {
        for (int i = Math.max(0, start); i < calls.size(); i++) {
            MethodInsnNode call = calls.get(i);
            if (call.owner.equals(owner) && call.name.equals(name)) return i;
        }
        fail("missing pinned provider call " + owner + "." + name);
        return -1;
    }

    private static ClassNode read(Path jar, String entry) throws Exception {
        try (JarFile file = new JarFile(jar.toFile()); InputStream input = file.getInputStream(file.getJarEntry(entry))) {
            assertNotNull(input, "missing provider class " + entry);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static void assertMethod(ClassNode node, String name, String descriptor) {
        assertTrue(node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor)),
                () -> "missing pinned provider method " + name + descriptor);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) if (read != 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }
}
