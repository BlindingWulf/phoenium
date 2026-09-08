package dev.phoenium.chunk;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Test-only call-site equivalent of FullEntityPredicateInputMixin. */
final class FullEntityPredicateTestTransform {
    static final String TARGET = "net/minecraft/advancements/predicates/entity/EntityPredicate";
    static void install(ClassNode node) {
        if (!node.name.equals(TARGET)) return;
        String produced = "Ldev/phoenium/chunk/FullEntityPredicateInputs$Produced;";
        node.interfaces.add("dev/phoenium/chunk/FullEntityPredicateInputs$Access");
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "phoenium$entityInputs", produced, null, null));
        var getter = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$entityPredicateInputs", "()" + produced, null, null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "phoenium$entityInputs", produced));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(getter);
        for (MethodNode method : node.methods) {
            if (method.name.equals("combine")) {
                method.name = "phoenium$originalCombine";
                for (AbstractInsnNode instruction : method.instructions.toArray())
                    if (instruction instanceof MethodInsnNode call && call.owner.equals("java/util/stream/Stream")
                            && call.name.equals("toArray") && call.desc.equals("(Ljava/util/function/IntFunction;)[Ljava/lang/Object;")) {
                        method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "dev/phoenium/chunk/FullEntityPredicateInputs", "selectedArray",
                                "([Ljava/lang/Object;)[Ljava/lang/Object;", false));
                    }
            } else if (method.name.equals("matches") && method.desc.equals(
                    "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/Entity;)Z")) {
                var guard = new InsnList();
                guard.add(new VarInsnNode(Opcodes.ALOAD, 0)); guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
                guard.add(new VarInsnNode(Opcodes.ALOAD, 3));
                guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullSavedContextInputs", "requireEntityPredicate",
                        "(L" + TARGET + ";Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)V", false));
                method.instructions.insert(guard);
            } else if (method.name.equals("<init>")) {
                for (AbstractInsnNode instruction : method.instructions.toArray())
                    if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name) && call.name.equals("combine")) {
                        method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "dev/phoenium/chunk/FullEntityPredicateTestHooks", "capture",
                                "(Ljava/util/Map;L" + node.name + ";)Lnet/minecraft/advancements/predicates/entity/EntitySubPredicate;", false));
                    }
            }
        }
    }
}
