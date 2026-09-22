package dev.phoenium.chunk;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Test-only plumbing at the native packet and block-event boundaries. No close body is replaced. */
public final class ContainerCloseTestTransform {
    static final String LISTENER = "net/minecraft/server/network/ServerGamePacketListenerImpl";
    static final String PLAYER = "net/minecraft/server/level/ServerPlayer";
    static final String HOOK = "dev/phoenium/chunk/ContainerCloseTestHooks";
    public static boolean supports(String name) { return name.equals(LISTENER)
        || name.equals("net/minecraft/world/CompoundContainer") || name.equals("net/minecraft/world/inventory/PlayerEnderChestContainer")
        || name.equals("net/minecraft/world/inventory/ChestMenu"); }
    public static byte[] transform(String name, byte[] bytes) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        install(node); var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    static void install(ClassNode node) {
        if (node.name.equals(PLAYER)) {
            var method = node.methods.stream().filter(m -> m.name.equals("doCloseContainer")).findFirst().orElseThrow();
            var probe = new InsnList(); probe.add(new VarInsnNode(Opcodes.ALOAD, 0));
            probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "starting", "(L" + PLAYER + ";)V", false));
            method.instructions.insert(probe);
        }
        if (node.name.equals("net/minecraft/world/CompoundContainer")) {
            node.interfaces.add("dev/phoenium/mixin/ContainerCloseCompoundAccess");
            getter(node, "phoenium$firstContainer", "container1", "Lnet/minecraft/world/Container;");
            getter(node, "phoenium$secondContainer", "container2", "Lnet/minecraft/world/Container;");
        }
        if (node.name.equals("net/minecraft/world/inventory/PlayerEnderChestContainer")) {
            node.interfaces.add("dev/phoenium/mixin/ContainerCloseEnderAccess");
            getter(node, "phoenium$activeChest", "activeChest", "Lnet/minecraft/world/level/block/entity/EnderChestBlockEntity;");
        }
        if (node.name.equals("net/minecraft/world/inventory/ChestMenu")) {
            var removed = node.methods.stream().filter(m -> m.name.equals("removed")).findFirst().orElseThrow();
            var probe = new InsnList(); probe.add(new VarInsnNode(Opcodes.ALOAD, 0));
            probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "removed", "(Lnet/minecraft/world/inventory/ChestMenu;)V", false));
            removed.instructions.insert(probe);
        }
        if (node.name.equals(LISTENER)) {
            for (var method : node.methods) if (method.name.equals("handleContainerClose")) {
                for (var insn : method.instructions.toArray()) if (insn instanceof MethodInsnNode call && call.name.equals("doCloseContainer")) {
                    var args = new InsnList(); args.add(new VarInsnNode(Opcodes.ALOAD, 0)); args.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    method.instructions.insertBefore(call, args);
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "close",
                        "(L" + PLAYER + ";L" + LISTENER + ";Lnet/minecraft/network/protocol/game/ServerboundContainerClosePacket;)V", false));
                }
            }
            node.interfaces.add("dev/phoenium/mixin/ServerGamePacketListenerInvoker");
            var invoke = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$invokeContainerClose",
                "(Lnet/minecraft/network/protocol/game/ServerboundContainerClosePacket;)V", null, null);
            invoke.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); invoke.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            invoke.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LISTENER, "handleContainerClose", invoke.desc, false));
            invoke.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(invoke);
        }
        if (node.name.equals("net/minecraft/server/level/ServerLevel")) {
            var original = node.methods.stream().filter(m -> m.name.equals("blockEvent")).findFirst().orElseThrow();
            original.name = "phoenium$testOriginalBlockEvent";
            var wrapper = new MethodNode(Opcodes.ACC_PUBLIC, "blockEvent", original.desc, null, null);
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2)); wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
            wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
            wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "blockEvent",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;II)V", false));
            wrapper.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(wrapper);
        }
    }
    private static void getter(ClassNode node, String name, String field, String desc) {
        var method = new MethodNode(Opcodes.ACC_PUBLIC, name, "()" + desc, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field, desc));
        method.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(method);
    }
}
