package dev.phoenium.chunk;

import java.util.Map;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** JUnit-only equivalents of invoker/accessor plumbing. Native loop bodies are unchanged. */
public final class NaturalSpawnTestTransform {
    public static boolean supports(String name) {
        return name.equals("net/minecraft/world/level/NaturalSpawner")
            || name.equals("dev/phoenium/service/NaturalSpawnContinuation")
            || name.equals("net/minecraft/world/level/NaturalSpawner$SpawnState")
            || name.equals("dev/phoenium/mixin/NaturalSpawnContinuationAccess");
    }
    public static byte[] transform(String name, byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        if (name.equals("dev/phoenium/service/NaturalSpawnContinuation")) {
            for (MethodNode method : node.methods) for (var instruction : method.instructions.toArray())
                if (instruction instanceof MethodInsnNode call && call.name.equals("naturalSpawnPassengerScope"))
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/chunk/NaturalSpawnSceneHooks", "scope",
                        "(Ldev/phoenium/live/PhoeniumLiveRuntime;Lnet/minecraft/server/level/ServerLevel;)Ldev/phoenium/chunk/FreshPassengerAdmission$NaturalSpawnScope;", false));
        } else if (name.equals("dev/phoenium/mixin/NaturalSpawnContinuationAccess")) {
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
            // Same entry seam as RegionalNaturalSpawnerMixin. If no capture is active,
            // execute the unmodified native selector/validation/generator bodies.
            for (MethodNode method : node.methods) if (method.name.equals("spawnForChunk")) {
                var entry = new InsnList(); var nativeBody = new LabelNode();
                for (int slot = 0; slot < 4; slot++) entry.add(new VarInsnNode(Opcodes.ALOAD, slot));
                entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/phoenium/service/NaturalSpawnContinuation", "capture",
                    method.desc.substring(0, method.desc.length() - 1) + "Z", false));
                entry.add(new JumpInsnNode(Opcodes.IFEQ, nativeBody));
                entry.add(new InsnNode(Opcodes.RETURN)); entry.add(nativeBody);
                method.instructions.insert(entry);
            }
            for (MethodNode method : node.methods) if (java.util.Set.of("getRandomPosWithin", "isRightDistanceToPlayerAndSpawnPoint", "getMobForSpawn", "isValidPositionForMob").contains(method.name))
                method.access = (method.access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC;
        }
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES); node.accept(writer); return writer.toByteArray();
    }
}
