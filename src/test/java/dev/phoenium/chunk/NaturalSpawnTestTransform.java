package dev.phoenium.chunk;

import java.util.Map;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** JUnit-only equivalents of invoker/accessor plumbing. Native loop bodies are unchanged. */
public final class NaturalSpawnTestTransform {
    public static boolean supports(String name) {
        return name.equals("net/minecraft/world/level/NaturalSpawner")
            || name.equals("net/minecraft/world/level/NaturalSpawner$SpawnState")
            || name.equals("dev/phoenium/mixin/NaturalSpawnContinuationAccess");
    }
    public static byte[] transform(String name, byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        if (name.equals("dev/phoenium/mixin/NaturalSpawnContinuationAccess")) {
            var targets = Map.of("phoenium$randomPos", "getRandomPosWithin", "phoenium$rightDistance", "isRightDistanceToPlayerAndSpawnPoint",
                "phoenium$createMob", "getMobForSpawn", "phoenium$validMob", "isValidPositionForMob");
            for (MethodNode method : node.methods) if (targets.containsKey(method.name)) {
                method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
                int slot = 0;
                for (Type arg : Type.getArgumentTypes(method.desc)) {
                    method.instructions.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot)); slot += arg.getSize();
                }
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/world/level/NaturalSpawner", targets.get(method.name), method.desc, false));
                method.instructions.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
            }
        } else if (name.endsWith("$SpawnState")) {
            node.interfaces.add("dev/phoenium/mixin/RegionalSpawnStateAccess");
            var targets = Map.of("canSpawnForCategoryLocal", "phoenium$canSpawnLocal", "canSpawn", "phoenium$canSpawn", "afterSpawn", "phoenium$afterSpawn");
            for (MethodNode original : java.util.List.copyOf(node.methods)) if (targets.containsKey(original.name)) {
                var method = new MethodNode(Opcodes.ACC_PUBLIC, targets.get(original.name), original.desc, null, null);
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); int slot = 1;
                for (Type arg : Type.getArgumentTypes(method.desc)) { method.instructions.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot)); slot += arg.getSize(); }
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, name, original.name, original.desc, false));
                method.instructions.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN))); node.methods.add(method);
            }
        } else {
            for (MethodNode method : node.methods) if (java.util.Set.of("getRandomPosWithin", "isRightDistanceToPlayerAndSpawnPoint", "getMobForSpawn", "isValidPositionForMob").contains(method.name))
                method.access = (method.access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC;
        }
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
}
