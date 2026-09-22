package dev.phoenium.chunk;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only proposed atomicity amendment. Copies the actual loaded Minecraft bytecode, with
 * exactly one cut BEFORE runPostLoad. Neither fragment is handwritten vanilla logic. The
 * constructor, replacement, status supplier and entire callback/registration suffix execute.
 * No class replacement, agent, production mixin, or original FULL-future completion occurs. */
final class FullConstructionFragments {
    private static final Method PREPARE;
    private static final Method FINISH;
    static {
        try {
            String original = Type.getInternalName(ChunkStatusTasks.class);
            String generated = original + "PhoeniumTestFragments";
            ClassNode node = new ClassNode();
            try (var input = ChunkStatusTasks.class.getResourceAsStream("ChunkStatusTasks.class")) {
                byte[] bytes = input.readAllBytes();
                // Independent accepted reference: STAGE3_FULL_NONSELF_INPUT_DECISION.md,
                // adopted prototype 542b352; descriptor/call/control-flow checks below
                // still validate the original mapped class before any fragment executes.
                assertEquals("ab9a75ec31f57df5502ee65ffe413d8099097417c2ed1c928da3890b523e3b05",
                    java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),
                    "only the audited Minecraft 26.2 ChunkStatusTasks class may be split");
                new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            MethodNode supplier = node.methods.stream().filter(m -> m.name.equals("lambda$full$0")).findFirst().orElseThrow();
            assertEquals("(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/server/level/GenerationChunkHolder;)Lnet/minecraft/world/level/chunk/ChunkAccess;", supplier.desc);
            assertTrue(supplier.tryCatchBlocks.isEmpty(), "cut must not cross a catch/finally");
            var calls = Arrays.stream(supplier.instructions.toArray()).filter(n -> n instanceof MethodInsnNode)
                .map(n -> ((MethodInsnNode)n).name).toList();
            assertEquals(java.util.List.of("level", "getWrapped", "<init>", "<init>", "replaceProtoChunk",
                "requireNonNull", "setFullStatus", "runPostLoad", "setLoaded", "registerAllBlockEntitiesAfterLevelLoad",
                "registerTickContainerInLevel", "unsavedListener", "setUnsavedListener"), calls);
            MethodInsnNode call = (MethodInsnNode)Arrays.stream(supplier.instructions.toArray())
                .filter(n -> n instanceof MethodInsnNode m && m.name.equals("runPostLoad")).findFirst().orElseThrow();
            AbstractInsnNode cut = call.getPrevious();
            assertEquals(Opcodes.ALOAD, cut.getOpcode());
            assertEquals(4, ((VarInsnNode)cut).var, "pinned actual LevelChunk local");
            int boundary = supplier.instructions.indexOf(cut);
            for (AbstractInsnNode instruction : supplier.instructions) {
                if (instruction instanceof JumpInsnNode jump) {
                    assertTrue(supplier.instructions.indexOf(instruction) < boundary);
                    assertTrue(supplier.instructions.indexOf(jump.label) < boundary,
                        "both Imposter reuse and proto construction branches must converge before the cut");
                }
                assertFalse(instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode);
            }
            MethodNode postLoad = node.methods.stream().filter(m -> m.name.equals("lambda$full$1")).findFirst().orElseThrow();
            assertEquals(2, postLoad.tryCatchBlocks.size(), "original scoped collector close/suppression handlers remain whole");
            assertTrue(postLoad.tryCatchBlocks.stream().allMatch(handler -> handler.type.equals("java/lang/Throwable")));
            MethodNode finish = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "finish",
                "(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/chunk/status/WorldGenContext;)Lnet/minecraft/world/level/chunk/ChunkAccess;", null, null);
            // Restore only the actual two locals consumed by the original suffix.
            finish.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            finish.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
            finish.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            finish.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, Type.getInternalName(WorldGenContext.class),
                "level", "()Lnet/minecraft/server/level/ServerLevel;", false));
            finish.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));
            Map<LabelNode, LabelNode> labels = new HashMap<>();
            for (AbstractInsnNode instruction : supplier.instructions) if (instruction instanceof LabelNode label) labels.put(label, new LabelNode());
            for (AbstractInsnNode instruction = cut; instruction != null; instruction = instruction.getNext())
                finish.instructions.add(instruction.clone(labels));
            while (cut != null) { var next = cut.getNext(); supplier.instructions.remove(cut); cut = next; }
            supplier.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
            supplier.instructions.add(new InsnNode(Opcodes.ARETURN));
            supplier.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
            node.methods.add(finish);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(new ClassRemapper(writer, new SimpleRemapper(original, generated)));
            Class<?> type = MethodHandles.privateLookupIn(ChunkStatusTasks.class, MethodHandles.lookup()).defineClass(writer.toByteArray());
            PREPARE = type.getMethod("lambda$full$0", ChunkAccess.class, WorldGenContext.class, GenerationChunkHolder.class);
            FINISH = type.getMethod("finish", LevelChunk.class, WorldGenContext.class);
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    static LevelChunk prepare(ChunkAccess input, WorldGenContext context, GenerationChunkHolder holder) throws Throwable {
        return invoke(PREPARE, input, context, holder);
    }
    static LevelChunk finish(LevelChunk actual, WorldGenContext context) throws Throwable { return invoke(FINISH, actual, context); }
    private static LevelChunk invoke(Method method, Object... args) throws Throwable {
        try { return (LevelChunk)method.invoke(null, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
