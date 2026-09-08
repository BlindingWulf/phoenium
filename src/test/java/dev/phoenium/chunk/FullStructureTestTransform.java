package dev.phoenium.chunk;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Test-only equivalents of the two raw-field production access mixins. */
final class FullStructureTestTransform {
    static void install(ClassNode node) {
        if (node.name.equals("net/minecraft/world/level/chunk/ChunkAccess")) {
            node.interfaces.add("dev/phoenium/chunk/FullStructureInputs$ChunkView");
            getter(node, "phoenium$structureReferences", "structuresRefences", "Ljava/util/Map;");
            getter(node, "phoenium$structureStarts", "structureStarts", "Ljava/util/Map;");
        } else if (node.name.equals("net/minecraft/server/level/ServerChunkCache")) {
            node.interfaces.add("dev/phoenium/chunk/FullStructureInputs$CacheView");
            getter(node, "phoenium$structureCachePositions", "lastChunkPos", "[J");
            getter(node, "phoenium$structureCacheStatuses", "lastChunkStatus", "[Lnet/minecraft/world/level/chunk/status/ChunkStatus;");
            getter(node, "phoenium$structureCacheChunks", "lastChunk", "[Lnet/minecraft/world/level/chunk/ChunkAccess;");
            node.interfaces.add("dev/phoenium/chunk/FullStructureDemand$CacheAccess");
            getter(node, "phoenium$structureDemandTickets", "ticketStorage", "Lnet/minecraft/world/level/TicketStorage;");
            var update = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$updateStructureDemand", "()Z", null, null);
            update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            update.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "runDistanceManagerUpdates", "()Z", false));
            update.instructions.add(new InsnNode(Opcodes.IRETURN)); node.methods.add(update);
        }
    }
    private static void getter(ClassNode node, String name, String field, String descriptor) {
        var method = new MethodNode(Opcodes.ACC_PUBLIC, name, "()" + descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field, descriptor));
        method.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(method);
    }
}
