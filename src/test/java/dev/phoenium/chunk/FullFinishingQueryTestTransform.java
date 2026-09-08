package dev.phoenium.chunk;

import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Test installation of the production mixins' exact invocation plumbing on pinned classes. */
final class FullFinishingQueryTestTransform {
    private static final Set<String> TARGETS = Set.of("net/minecraft/server/level/ServerChunkCache",
            "net/minecraft/server/level/ServerLevel", "net/minecraft/world/level/Level",
            "net/minecraft/world/level/LevelReader", "net/minecraft/world/level/BlockCollisions",
            "net/minecraft/advancements/predicates/LocationPredicate");
    static boolean supports(String name) { return TARGETS.contains(name); }
    static void install(ClassNode node) {
        if (!supports(node.name)) return;
        if (node.name.equals("net/minecraft/advancements/predicates/LocationPredicate")) {
            wrap(node, "matches", "(Lnet/minecraft/server/level/ServerLevel;DDD)Z", "locationPredicate");
            return;
        }
        int matches = 0;
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            String hook = null;
            if (node.name.equals("net/minecraft/server/level/ServerChunkCache") && method.name.equals("getChunk")) {
                if (call.name.equals("getChunkFutureMainThread")) hook = "selected";
                if (call.name.equals("managedBlock")) hook = "managedBlock";
                if (call.name.equals("join")) hook = "join";
                if (call.name.equals("storeInCache")) hook = "store";
            } else if (node.name.equals("net/minecraft/server/level/ServerLevel")
                    && method.name.equals("getCurrentDifficultyAt") && call.name.equals("getChunk")) hook = "difficulty";
            else if (node.name.equals("net/minecraft/world/level/Level")) {
                if (method.name.equals("getChunkForCollisions") && call.name.equals("getChunk")) hook = "collision";
                if (method.name.equals("getBlockEntity") && call.name.equals("getChunkAt")) hook = "dynamicChunk";
                if (method.name.equals("getBlockEntity") && call.name.equals("getBlockEntity")) hook = "dynamicBlockEntity";
            } else if (node.name.equals("net/minecraft/world/level/LevelReader")
                    && method.name.equals("getNoiseBiome") && call.name.equals("getChunk")) hook = "environment";
            else if (node.name.equals("net/minecraft/world/level/BlockCollisions")
                    && method.name.equals("computeNext") && call.name.equals("getCollisionShape")) hook = "shape";
            if (hook == null) continue;
            String receiver = hook.equals("managedBlock") ? "java/lang/Object" : call.owner;
            method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/phoenium/chunk/FullFinishingQueryTestHooks", hook,
                    call.desc.replace("(", "(L" + receiver + ";"), false));
            matches++;
        }
        int expected = switch (node.name) {
            case "net/minecraft/server/level/ServerChunkCache" -> 5;
            case "net/minecraft/world/level/Level" -> 3;
            default -> 1;
        };
        if (matches != expected) throw new AssertionError("initialized query selectors changed: " + node.name + ": " + matches);
        if (node.name.equals("net/minecraft/server/level/ServerChunkCache"))
            wrap(node, "getChunk", "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", "predicateChunk");
    }
    private static void wrap(ClassNode node, String name, String descriptor, String hook) {
        var matches = node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor)).toList();
        if (matches.size() != 1) throw new AssertionError("predicate query selector changed: " + node.name + "." + name);
        var original = matches.getFirst();
        original.name = "phoenium$originalPredicate$" + name;
        var wrapper = new MethodNode(original.access, name, descriptor, original.signature, original.exceptions.toArray(String[]::new));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int local = 1;
        for (Type type : Type.getArgumentTypes(descriptor)) {
            wrapper.instructions.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local)); local += type.getSize();
        }
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/FullFinishingQueryTestHooks",
                hook, descriptor.replace("(", "(L" + node.name + ";"), false));
        wrapper.instructions.add(new InsnNode(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN)));
        node.methods.add(wrapper);
    }
}
