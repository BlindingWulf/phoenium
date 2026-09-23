package dev.phoenium.chunk;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Supplies test-only suffix seams on the exact plain Minecart class. */
public final class NativeMinecartTestTransform {
    public static final String CART = "net/minecraft/world/entity/vehicle/minecart/Minecart";
    private static final String HOOK = "dev/phoenium/chunk/NativeMinecartTestHooks";
    public static void install(ClassNode node) {
        if (!node.name.equals(CART)) return;
        node.interfaces.add("dev/phoenium/live/MinecartPortalNativeAccess");
        MethodNode abstractTail = new MethodNode(Opcodes.ACC_PUBLIC,
            "phoenium$resumeAbstractMinecartPortalSuffix", "()V", null, null);
        abstractTail.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        abstractTail.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "abstractSuffix",
            "(L" + CART + ";)V", false));
        abstractTail.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(abstractTail);
        MethodNode outerTail = new MethodNode(Opcodes.ACC_PUBLIC,
            "phoenium$resumeMinecartPortalSuffix", "(DLnet/minecraft/world/phys/Vec3;)V", null, null);
        outerTail.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        outerTail.instructions.add(new VarInsnNode(Opcodes.DLOAD, 1));
        outerTail.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        outerTail.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "outerSuffix",
            "(L" + CART + ";DLnet/minecraft/world/phys/Vec3;)V", false));
        outerTail.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(outerTail);
    }
}
