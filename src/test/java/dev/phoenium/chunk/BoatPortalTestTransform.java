package dev.phoenium.chunk;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Test-only suffix seam; the installed mixin owns the full pinned native statements. */
final class BoatPortalTestTransform {
    static final String TARGET = "net/minecraft/world/entity/vehicle/boat/AbstractBoat";

    static void install(ClassNode node) {
        if (!node.name.equals(TARGET)) return;
        node.interfaces.add("dev/phoenium/live/BoatPortalNativeAccess");
        MethodNode suffix = new MethodNode(Opcodes.ACC_PUBLIC, "phoenium$resumeBoatTickSuffix",
            "()V", null, null);
        suffix.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        suffix.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "dev/phoenium/chunk/BoatPortalTestHooks", "suffix",
            "(Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;)V", false));
        suffix.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(suffix);
    }
}
