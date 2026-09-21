package dev.phoenium.chunk;

import dev.phoenium.service.VaultContinuationPin;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Test-only installation of the exact production injector/accessor seams. Both
 * original native tick and original output loop remain executable without a scope. */
public final class VaultContinuationTestTransform {
    private static final String HOOK = "dev/phoenium/service/VaultBlockEntityTickContinuation";
    private static final String DATA = "net/minecraft/world/level/block/entity/vault/";
    static boolean supports(String name) {
        return name.equals(VaultContinuationPin.VAULT) || name.equals(VaultContinuationPin.ENTITY)
            || name.equals(DATA + "VaultServerData") || name.equals(DATA + "VaultSharedData")
            || name.equals("dev/phoenium/mixin/VaultSetChangedAccess");
    }
    static byte[] transform(String name, byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        install(node);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    static void install(ClassNode node) {
        String name = node.name;
        if (name.equals(VaultContinuationPin.VAULT)) {
            VaultContinuationPin.verify(node);
            var method = node.methods.stream().filter(m -> m.name.equals("tick")).findFirst().orElseThrow();
            for (var insn : method.instructions.toArray()) if (insn instanceof MethodInsnNode call && call.name.equals("access$000")) {
                var prefix = new InsnList();
                for (int slot : new int[]{0,1,2,7,4,5}) prefix.add(new VarInsnNode(Opcodes.ALOAD, slot));
                prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "setChanged",
                    "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;L" + DATA + "VaultServerData;L" + DATA + "VaultSharedData;)Z", false));
                LabelNode original = new LabelNode(); prefix.add(new JumpInsnNode(Opcodes.IFEQ, original));
                prefix.add(new InsnNode(Opcodes.RETURN)); prefix.add(original);
                prefix.add(new FrameNode(Opcodes.F_FULL, 8, new Object[]{"net/minecraft/server/level/ServerLevel", "net/minecraft/core/BlockPos", "net/minecraft/world/level/block/state/BlockState", DATA + "VaultConfig", DATA + "VaultServerData", DATA + "VaultSharedData", DATA + "VaultState", "net/minecraft/world/level/block/state/BlockState"}, 0, null));
                method.instructions.insertBefore(call.getPrevious().getPrevious().getPrevious(), prefix);
            }
        } else if (name.equals(VaultContinuationPin.LEVEL)) {
            VaultContinuationPin.verify(node);
            var method = node.methods.stream().filter(m -> m.name.equals("updateNeighbourForOutputSignal")).findFirst().orElseThrow();
            InsnList prefix = new InsnList();
            for (int slot : new int[]{0,1,2}) prefix.add(new VarInsnNode(Opcodes.ALOAD, slot));
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "output", VaultContinuationPin.OUTPUT.replace("(", "(L" + name + ";" ).replace(")V", ")Z"), false));
            LabelNode original = new LabelNode(); prefix.add(new JumpInsnNode(Opcodes.IFEQ, original)); prefix.add(new InsnNode(Opcodes.RETURN)); prefix.add(original);
            prefix.add(new FrameNode(Opcodes.F_NEW, 3, new Object[]{name, "net/minecraft/core/BlockPos", "net/minecraft/world/level/block/Block"}, 0, null));
            method.instructions.insert(prefix);
        } else if (name.equals(VaultContinuationPin.ENTITY)) {
            VaultContinuationPin.verify(node);
            node.methods.stream().filter(m -> m.name.equals("setChanged") && m.desc.equals(VaultContinuationPin.CHANGED))
                .forEach(m -> m.access = (m.access & ~Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC);
        } else if (name.equals("dev/phoenium/mixin/VaultSetChangedAccess")) {
            var method = node.methods.stream().filter(m -> m.name.equals("phoenium$setChanged")).findFirst().orElseThrow();
            method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
            for (int slot : new int[]{0,1,2}) method.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, VaultContinuationPin.ENTITY, "setChanged", VaultContinuationPin.CHANGED, false));
            method.instructions.add(new InsnNode(Opcodes.RETURN));
        } else if (name.equals(DATA + "VaultServerData") || name.equals(DATA + "VaultSharedData")) {
            node.interfaces.add("dev/phoenium/mixin/" + (name.endsWith("VaultSharedData") ? "VaultSharedDataAccess" : "VaultServerDataAccess"));
            var setter = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$isDirty", "(Z)V", null, null);
            setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); setter.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
            setter.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, name, "isDirty", "Z")); setter.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(setter);
            if (name.endsWith("VaultSharedData")) {
                var getter = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$isDirty", "()Z", null, null);
                getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, name, "isDirty", "Z"));
                getter.instructions.add(new InsnNode(Opcodes.IRETURN)); node.methods.add(getter);
            }
        } else if (name.equals("net/minecraft/server/level/ServerChunkCache")) {
            node.interfaces.add("dev/phoenium/mixin/RegionalServerChunkCacheAccess");
            String desc = "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;";
            var method = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$naturalSpawnFuture", desc, null, null);
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1)); method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3)); method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, name, "getChunkFutureMainThread", desc, false));
            method.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(method);
        } else if (name.equals("net/minecraft/server/level/ChunkMap")) {
            node.interfaces.add("dev/phoenium/mixin/ChunkMapLoadAccess");
            var method = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$mainExecutor", "()Lnet/minecraft/util/thread/BlockableEventLoop;", null, null);
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, name, "mainThreadExecutor", "Lnet/minecraft/util/thread/BlockableEventLoop;"));
            method.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(method);
        }
    }
}
