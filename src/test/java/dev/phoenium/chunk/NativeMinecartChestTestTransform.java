package dev.phoenium.chunk;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Supplies only the inherited AbstractMinecart tail seam on exact MinecartChest. */
public final class NativeMinecartChestTestTransform {
    public static final String CART = "net/minecraft/world/entity/vehicle/minecart/MinecartChest";
    public static void install(ClassNode node) {
        if (!node.name.equals(CART)) return;
        node.interfaces.add("dev/phoenium/live/AbstractMinecartPortalNativeAccess");
        MethodNode tail = new MethodNode(Opcodes.ACC_PUBLIC,
            "phoenium$resumeAbstractMinecartPortalSuffix", "()V", null, null);
        tail.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        tail.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "dev/phoenium/chunk/NativeMinecartChestTestHooks", "abstractSuffix",
            "(L" + CART + ";)V", false));
        tail.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(tail);
    }
}
