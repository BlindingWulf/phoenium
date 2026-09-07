package dev.phoenium.chunk;

import java.util.Set;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Exact saved-context Mixin entry points for JVM tests; reader and storage bodies remain original. */
public final class FullSavedContextTestTransform {
    private static final String HOOKS = "dev/phoenium/chunk/FullSavedContextTestHooks";
    private static final Set<String> TARGETS = Set.of("net/minecraft/world/entity/Display$TextDisplay",
            "net/minecraft/world/level/storage/CommandStorage", "net/minecraft/network/chat/contents/data/BlockDataSource");
    public static boolean supports(String name) { return TARGETS.contains(name); }
    public static void install(ClassNode node) {
        if (!supports(node.name)) return;
        if (node.name.endsWith("$TextDisplay")) {
            wrap(node, "readAdditionalSaveData", "(Lnet/minecraft/world/level/storage/ValueInput;)V", "textReader");
        } else if (node.name.endsWith("/CommandStorage")) {
            wrap(node, "get", "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/nbt/CompoundTag;", "get");
            wrap(node, "set", "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/nbt/CompoundTag;)V", "set");
        } else {
            var method = node.methods.stream().filter(body -> body.name.equals("getData")
                    && body.desc.equals("(Lnet/minecraft/commands/CommandSourceStack;)Ljava/util/stream/Stream;")).findFirst()
                    .orElseThrow(() -> new AssertionError("saved-context selector changed: " + node.name + ".getData"));
            for (AbstractInsnNode at : method.instructions.toArray()) if (at instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/server/level/ServerLevel")) {
                if (call.name.equals("isLoaded") && call.desc.equals("(Lnet/minecraft/core/BlockPos;)Z"))
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "blockLoaded",
                            "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Z", false));
                if (call.name.equals("getBlockEntity") && call.desc.equals("(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "blockEntity",
                            "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;", false));
            }
        }
    }
    private static void wrap(ClassNode node, String name, String descriptor, String hook) {
        var matches = node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor)).toList();
        if (matches.size() != 1) throw new AssertionError("saved-context selector changed: " + node.name + "." + name);
        var original = matches.getFirst();
        original.name = "phoenium$originalContext$" + name;
        original.access = (original.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        var wrapper = new MethodNode(original.access, name, descriptor, original.signature, original.exceptions.toArray(String[]::new));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int local = 1;
        for (Type type : Type.getArgumentTypes(descriptor)) {
            wrapper.instructions.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local)); local += type.getSize();
        }
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, hook,
                descriptor.replace("(", "(L" + node.name + ";"), false));
        wrapper.instructions.add(new InsnNode(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN)));
        node.methods.add(wrapper);
    }
}
