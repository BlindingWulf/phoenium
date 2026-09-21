package dev.phoenium.chunk;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/** Locks the Stage 8 FULL adapter to the exact C2ME provider surfaces that were inspected. */
final class C2meFullProviderBinaryTest {
    private static final Path REWRITES = Path.of("build", "stage6-audit-20260909", "research", "c2me-mixins",
            "c2me-fabric-rewrites-chunk-system-mc26.2-0.4.2-alpha.0.43.jar");
    private static final String ROOT = "com/ishland/c2me/rewrites/chunksystem/common/";
    private static final String CTX = "Lcom/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext;";
    private static final String CANCEL = "Lcom/ishland/flowsched/scheduler/Cancellable;";
    private static final String COMPLETABLE = "Lio/reactivex/rxjava3/core/Completable;";
    private static final String FROM_RUNNABLE_OWNER = "io/reactivex/rxjava3/core/Completable";
    private static final String FROM_RUNNABLE_DESC = "(Ljava/lang/Runnable;)Lio/reactivex/rxjava3/core/Completable;";
    private static final String SUBSCRIBE_ON_DESC = "(Lio/reactivex/rxjava3/core/Scheduler;)Lio/reactivex/rxjava3/core/Completable;";

    @Test void providerResidentRemovalHasOneExactHolderReturningSeam() throws Exception {
        var node = read(REWRITES, ROOT + "statuses/ServerAccessible.class");
        var removal = node.methods.stream().filter(m -> m.name.equals("lambda$downgradeFromThis$0")).findFirst().orElseThrow();
        assertEquals(1, invocationCount(removal, "it/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap", "remove", "(J)Ljava/lang/Object;"));
    }

    @Test void serverAccessibleHasExactFullAndDelayedRunnableSeams() throws Exception {
        ClassNode node = read(REWRITES, ROOT + "statuses/ServerAccessible.class");
        String upgrade = "(" + CTX + CANCEL + ")" + COMPLETABLE;
        String post = "(" + CTX + ")" + COMPLETABLE;
        String downgrade = upgrade;
        assertEquals(2, fromRunnableCount(method(node, "upgradeToThis", upgrade)));
        assertEquals(1, fromRunnableCount(method(node, "postUpgradeToThis", post)));
        assertEquals(1, fromRunnableCount(method(node, "downgradeFromThis", downgrade)));
        assertMethod(node, "upgrade0", "(" + CTX
                + "Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk;"
                + "Lnet/minecraft/world/level/chunk/ImposterProtoChunk;)V");
    }

    @Test void providerExposesDistinctAccessibleAndSendingStatuses() throws Exception {
        ClassNode node = read(REWRITES, ROOT + "NewChunkStatus.class");
        String statusDesc = "Lcom/ishland/c2me/rewrites/chunksystem/common/NewChunkStatus;";
        Map<String, FieldNode> fields = node.fields.stream().collect(java.util.stream.Collectors.toMap(field -> field.name, field -> field));
        assertEquals(statusDesc, fields.get("SERVER_ACCESSIBLE").desc);
        assertEquals(statusDesc, fields.get("SERVER_ACCESSIBLE_CHUNK_SENDING").desc);
        assertNotEquals(fields.get("SERVER_ACCESSIBLE").name, fields.get("SERVER_ACCESSIBLE_CHUNK_SENDING").name);
    }

    @Test void providerChunkStateRecordShapeIsPinned() throws Exception {
        ClassNode node = read(REWRITES, ROOT + "ChunkState.class");
        assertTrue((node.access & Opcodes.ACC_RECORD) != 0, "ChunkState is no longer a record");
        assertMethod(node, "chunk", "()Lnet/minecraft/world/level/chunk/ChunkAccess;");
        assertMethod(node, "protoChunk", "()Lnet/minecraft/world/level/chunk/ProtoChunk;");
        assertMethod(node, "reachedStatus", "()Lnet/minecraft/world/level/chunk/status/ChunkStatus;");
        assertMethod(node, "wasFullChunk", "()Z");
    }

    @Test void installedW1CompositionContainsEffectiveNotickvdSendingRunnableWhenEvidenceIsPresent() throws Exception {
        Path transformed = Path.of("run", "stage8", "entry-20260911", "c2me-full-send-observed-body-w1", "server",
                ".mixin.out", "class", "com", "ishland", "c2me", "rewrites", "chunksystem", "common", "statuses",
                "ServerAccessibleChunkSending.class");
        if (!Files.isRegularFile(transformed)) return; // Installed evidence is a local acceptance artifact, not a portable build input.
        ClassNode node = readClass(transformed);
        String upgrade = "(" + CTX + CANCEL + ")" + COMPLETABLE;
        String bridge = "(Ljava/lang/Object;" + CANCEL + ")" + COMPLETABLE;
        MethodNode typed = method(node, "upgradeToThis", upgrade);
        MethodNode generic = method(node, "upgradeToThis", bridge);
        assertEquals(1, fromRunnableCount(typed));
        assertEquals(1, invocationCount(typed, "io/reactivex/rxjava3/core/Completable", "subscribeOn", SUBSCRIBE_ON_DESC),
                "notickvd SEND_READY graph is no longer exactly fromRunnable(...).subscribeOn(...)");
        assertEquals(1, invocationCount(generic, node.name, "wrapOperation$bdl000$phoenium$sendReady",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Ljava/lang/Object;"),
                "generic provider bridge no longer routes exactly once through Phoenium SEND_READY composition");
        MethodNode mixinBridge = node.methods.stream()
                .filter(method -> method.name.startsWith("mixinextras$bridge$upgradeToThis$")
                        && method.desc.equals("([Ljava/lang/Object;)" + COMPLETABLE))
                .findFirst().orElseThrow(() -> new AssertionError("missing MixinExtras typed upgrade bridge"));
        assertEquals(1, invocationCount(mixinBridge, node.name, "upgradeToThis", upgrade),
                "MixinExtras bridge no longer delegates exactly once to the typed SEND_READY upgrade");
        assertTrue(node.methods.stream().anyMatch(method -> method.name.contains("c2me-notickvd")
                        && method.name.contains("upgradeToThis")),
                "installed class no longer contains the notickvd sending overwrite");
    }

    private static int fromRunnableCount(MethodNode method) {
        int count = 0;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals(FROM_RUNNABLE_OWNER) && call.name.equals("fromRunnable")
                && call.desc.equals(FROM_RUNNABLE_DESC)) count++;
        return count;
    }

    private static int invocationCount(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)) count++;
        return count;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst().orElseThrow(() -> new AssertionError("missing provider method " + name + descriptor));
    }

    private static void assertMethod(ClassNode node, String name, String descriptor) { method(node, name, descriptor); }

    private static ClassNode read(Path jar, String entry) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            var jarEntry = file.getJarEntry(entry);
            assertNotNull(jarEntry, "missing provider class " + entry);
            try (InputStream input = file.getInputStream(jarEntry)) { return read(input); }
        }
    }

    private static ClassNode readClass(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) { return read(input); }
    }

    private static ClassNode read(InputStream input) throws Exception {
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(input).accept(node,
                org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        return node;
    }
}
