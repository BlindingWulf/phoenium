package dev.phoenium.chunk;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Mirrors only Mixin call-site plumbing; all reservation, generation, registrar and stage bodies are production. */
public final class FullRegistrationTestTransform {
    public static byte[] transform(String name, byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        if (name.equals(FullStageBoundary.TARGET)) {
            FullStageBoundary.install(node); FullStageBoundary.installRegistration(node);
        } else if (name.equals("net/minecraft/world/level/storage/PrimaryLevelData")) {
            node.interfaces.add("dev/phoenium/chunk/FullPreparationClockAccess");
            String descriptor = "Ldev/phoenium/chunk/FullPreparationClock;";
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "phoenium$clock", descriptor, null, null));
            getter(node, "phoenium$preparationClock", "phoenium$clock", descriptor);
            for (MethodNode method : node.methods) {
                if (method.name.equals("<init>")) for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() == Opcodes.RETURN) {
                        InsnList init = new InsnList(); init.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        init.add(new TypeInsnNode(Opcodes.NEW, "dev/phoenium/chunk/FullPreparationClock"));
                        init.add(new InsnNode(Opcodes.DUP));
                        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "dev/phoenium/chunk/FullPreparationClock", "<init>", "()V", false));
                        init.add(new FieldInsnNode(Opcodes.PUTFIELD, name, "phoenium$clock", descriptor));
                        method.instructions.insertBefore(instruction, init);
                    }
                }
                if (method.name.equals("setGameTime") && method.desc.equals("(J)V")) {
                    method.instructions.insert(clockCall(name, "beforeWrite"));
                    for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN)
                        method.instructions.insertBefore(instruction, clockCall(name, "afterWrite"));
                }
            }
        } else {
            node.interfaces.add("dev/phoenium/chunk/FullHolderEvidence");
            getter(node, "phoenium$startedWork", "startedWork", "Ljava/util/concurrent/atomic/AtomicReference;");
            getter(node, "phoenium$futures", "futures", "Ljava/util/concurrent/atomic/AtomicReferenceArray;");
            getter(node, "phoenium$generationRefs", "generationRefCount", "Ljava/util/concurrent/atomic/AtomicInteger;");
            getter(node, "phoenium$generationSaveSync", "generationSaveSyncFuture", "Ljava/util/concurrent/CompletableFuture;");
            MethodNode clear = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$failAndClear", "(ILjava/util/concurrent/CompletableFuture;)V", null, null);
            clear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); clear.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
            clear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            clear.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, name, "failAndClearPendingFuture", clear.desc, false));
            clear.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(clear);
            int winning = 0, adjacent = 0;
            for (MethodNode method : node.methods) if (method.name.equals("applyStep")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals("net/minecraft/server/level/GeneratingChunkMap") && call.name.equals("applyStep")) {
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullRegistrationRuntime",
                                "applyWinning", call.desc.replace("(", "(Lnet/minecraft/server/level/GeneratingChunkMap;"), false)); winning++;
                    } else if (call.owner.equals("java/util/concurrent/CompletableFuture") && call.name.equals("handle")) {
                        InsnList args = new InsnList();
                        for (int local : new int[]{0, 1, 2}) args.add(new VarInsnNode(Opcodes.ALOAD, local));
                        method.instructions.insertBefore(call, args);
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullRegistrationRuntime", "handle",
                                "(Ljava/util/concurrent/CompletableFuture;Ljava/util/function/BiFunction;Lnet/minecraft/server/level/GenerationChunkHolder;"
                                        + "Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/server/level/GeneratingChunkMap;)Ljava/util/concurrent/CompletableFuture;", false)); adjacent++;
                    }
                }
            }
            if (winning != 1 || adjacent != 1) throw new AssertionError("test hook selectors changed");
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private static InsnList clockCall(String owner, String operation) {
        InsnList result = new InsnList(); result.add(new VarInsnNode(Opcodes.ALOAD, 0));
        result.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "phoenium$preparationClock", "()Ldev/phoenium/chunk/FullPreparationClock;", false));
        result.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "dev/phoenium/chunk/FullPreparationClock", operation, "()V", false));
        return result;
    }
    private static void getter(ClassNode node, String methodName, String field, String descriptor) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, methodName, "()" + descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field, descriptor));
        method.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(method);
    }
}
