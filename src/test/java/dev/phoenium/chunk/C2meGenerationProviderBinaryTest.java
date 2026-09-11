package dev.phoenium.chunk;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/** Locks the Stage 8 generation adapter to the exact provider binary that was reverse-inspected. */
final class C2meGenerationProviderBinaryTest {
    private static final Path OUTER = Path.of("build", "stage6-audit-20260909", "research", "artifacts", "c2me",
            "c2me-fabric-mc26.2-0.4.2-alpha.0.43.jar");
    private static final Path REWRITES = Path.of("build", "stage6-audit-20260909", "research", "c2me-mixins",
            "c2me-fabric-rewrites-chunk-system-mc26.2-0.4.2-alpha.0.43.jar");
    private static final String SHA256 = "B013229383F51789C57684CBCFC12981C8D791206F3B594866DFEB74AED3770B";
    private static final String DELEGATE = "com/ishland/c2me/rewrites/chunksystem/common/statuses/VanillaWorldGenerationDelegate.class";

    @Test void lockedProviderHasExactlyTwoPreFullChunkStepApplySites() throws Exception {
        assertTrue(Files.isRegularFile(OUTER), "locked C2ME provider artifact is missing");
        assertEquals(SHA256, sha256(OUTER));
        assertTrue(Files.isRegularFile(REWRITES), "locked rewrites module is missing");

        ClassNode node = read(REWRITES, DELEGATE);
        FieldNode status = node.fields.stream().filter(field -> field.name.equals("status")).findFirst().orElseThrow();
        assertEquals("Lnet/minecraft/world/level/chunk/status/ChunkStatus;", status.desc);

        Map<String, Integer> sites = new HashMap<>();
        int total = 0;
        for (MethodNode method : node.methods) {
            int count = 0;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/world/level/chunk/status/ChunkStep")
                        && call.name.equals("apply")
                        && call.desc.equals("(Lnet/minecraft/world/level/chunk/status/WorldGenContext;"
                                + "Lnet/minecraft/util/StaticCache2D;Lnet/minecraft/world/level/chunk/ChunkAccess;)"
                                + "Ljava/util/concurrent/CompletableFuture;")) {
                    count++; total++;
                }
            }
            if (count != 0) sites.put(method.name, count);
        }
        assertEquals(2, total);
        assertEquals(Map.of("lambda$upgradeToThis$1", 1, "lambda$upgradeToThis$5", 1), sites);
    }

    @Test void lockedProviderStillExposesExactGenerationLineageSurface() throws Exception {
        ClassNode context = read(REWRITES, "com/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext.class");
        assertMethod(context, "holder", "()Lcom/ishland/flowsched/scheduler/ItemHolder;");
        assertMethod(context, "tacs", "()Lnet/minecraft/server/level/ChunkMap;");
        assertMethod(context, "theChunkSystem", "()Lcom/ishland/c2me/rewrites/chunksystem/common/TheChunkSystem;");

        ClassNode statuses = read(REWRITES, "com/ishland/c2me/rewrites/chunksystem/common/NewChunkStatus.class");
        assertMethod(statuses, "fromVanillaStatus",
                "(Lnet/minecraft/world/level/chunk/status/ChunkStatus;)Lcom/ishland/c2me/rewrites/chunksystem/common/NewChunkStatus;");
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
