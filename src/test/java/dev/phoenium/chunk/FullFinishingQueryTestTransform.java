package dev.phoenium.chunk;

import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Test installation of the production mixins' exact invocation plumbing on pinned classes. */
final class FullFinishingQueryTestTransform {
    private static final Set<String> TARGETS = Set.of("net/minecraft/server/level/ServerChunkCache",
            "net/minecraft/server/level/ServerLevel", "net/minecraft/world/level/Level",
            "net/minecraft/world/level/LevelReader", "net/minecraft/world/level/BlockCollisions");
    static boolean supports(String name) { return TARGETS.contains(name); }
    static void install(ClassNode node) {
        if (!supports(node.name)) return;
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
    }
}
