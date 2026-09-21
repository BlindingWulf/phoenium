package dev.phoenium.chunk;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Mirrors the production ticker HEAD hook and the two native ticker accessors. */
public final class ResidentTickerTestTransform {
    static final String CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
    static boolean supports(String name) {
        return name.equals(CHUNK + "$BoundTickingBlockEntity") || name.equals(CHUNK + "$RebindableTickingBlockEntityWrapper");
    }
    static byte[] transform(String name, byte[] bytes) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); install(node);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    static void install(ClassNode node) {
        if (node.name.equals("net/minecraft/world/level/Level")) {
            var method = node.methods.stream().filter(m -> m.name.equals("addBlockEntityTicker")).findFirst().orElseThrow();
            var head = new InsnList();
            head.add(new VarInsnNode(Opcodes.ALOAD, 0)); head.add(new VarInsnNode(Opcodes.ALOAD, 1));
            head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/ResidentTickerTestHooks", "added",
                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/TickingBlockEntity;)V", false));
            method.instructions.insert(head);
        } else if (supports(node.name)) {
            node.interfaces.add("dev/phoenium/service/LocalNativeTickerAccess");
            boolean bound = node.name.endsWith("$BoundTickingBlockEntity");
            for (String kind : new String[]{"BlockEntity", "Chunk"}) {
                String result = kind.equals("Chunk") ? CHUNK : "net/minecraft/world/level/block/entity/BlockEntity";
                String name = "phoenium$local" + kind;
                var method = new MethodNode(Opcodes.ACC_PUBLIC, name, "()L" + result + ";", null, null);
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                if (bound) method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name,
                    kind.equals("Chunk") ? "this$0" : "blockEntity", "L" + result + ";"));
                else {
                    method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "ticker",
                        "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;"));
                    method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "dev/phoenium/service/LocalNativeTickerAccess"));
                    method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "dev/phoenium/service/LocalNativeTickerAccess", name, "()L" + result + ";", true));
                }
                method.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(method);
            }
        }
    }
}
