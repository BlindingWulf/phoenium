package dev.phoenium.chunk;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Narrow test-agent mirror of NativePortalTransferMixin's five graph call-site wraps. */
public final class NativePortalGraphTestTransform {
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String TRANSITION = "net/minecraft/world/level/portal/TeleportTransition";
    private static final String POSITION = "net/minecraft/world/entity/PositionMoveRotation";
    private static final String HOOK = "dev/phoenium/chunk/NativePortalGraphTestHooks";

    public static void install(ClassNode node) {
        if (node.name.equals("net/minecraft/server/level/ServerLevel")) {
            int guarded = 0;
            for (var method : node.methods) {
                int entitySlot;
                boolean passenger;
                if (method.name.equals("tickNonPassenger")
                        && method.desc.equals("(L" + ENTITY + ";)V")) {
                    entitySlot = 1; passenger = false;
                } else if (method.name.equals("tickPassenger")
                        && method.desc.equals("(L" + ENTITY + ";L" + ENTITY + ";)V")) {
                    entitySlot = 2; passenger = true;
                } else continue;
                LabelNode proceed = new LabelNode();
                InsnList head = new InsnList();
                head.add(new VarInsnNode(Opcodes.ALOAD, entitySlot));
                head.add(new InsnNode(passenger ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
                head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "pauseGraphTick",
                    "(L" + ENTITY + ";Z)Z", false));
                head.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
                head.add(new InsnNode(Opcodes.RETURN));
                head.add(proceed);
                method.instructions.insert(head);
                guarded++;
            }
            if (guarded != 2)
                throw new IllegalStateException("pinned ServerLevel graph tick entry points drifted: " + guarded);
            return;
        }
        if (!node.name.equals(ENTITY)) return;
        for (var method : node.methods) {
            if (!method.name.equals("teleportCrossDimension")) continue;
            int replaced = 0;
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                String name = null, descriptor = null;
                if (call.owner.equals(ENTITY) && call.name.equals("getPassengers")) {
                    name = "passengers"; descriptor = "(L" + ENTITY + ";)Ljava/util/List;";
                } else if (call.owner.equals(ENTITY) && call.name.equals("calculatePassengerTransition")) {
                    name = "passengerTransition";
                    descriptor = "(L" + ENTITY + ";L" + TRANSITION + ";L" + ENTITY + ";)L" + TRANSITION + ";";
                } else if (call.owner.equals(ENTITY) && call.name.equals("teleport")) {
                    name = "passenger"; descriptor = "(L" + ENTITY + ";L" + TRANSITION + ";)L" + ENTITY + ";";
                } else if (call.owner.equals(ENTITY) && call.name.equals("startRiding")) {
                    name = "attachPassenger"; descriptor = "(L" + ENTITY + ";L" + ENTITY + ";ZZ)Z";
                } else if (call.owner.equals(POSITION) && call.name.equals("of")
                        && call.desc.equals("(L" + ENTITY + ";)L" + POSITION + ";")) {
                    name = "sourcePosition"; descriptor = "(L" + ENTITY + ";)L" + POSITION + ";";
                }
                if (name == null) continue;
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    HOOK, name, descriptor, false));
                replaced++;
            }
            if (replaced != 5)
                throw new IllegalStateException("pinned Entity.teleportCrossDimension graph call sites drifted: " + replaced);
        }
    }
}
