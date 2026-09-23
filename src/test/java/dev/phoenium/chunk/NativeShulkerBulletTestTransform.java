package dev.phoenium.chunk;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Supplies test-only suffix seams for the exact ShulkerBullet class. */
public final class NativeShulkerBulletTestTransform {
    private static final String BULLET = "net/minecraft/world/entity/projectile/ShulkerBullet";
    private static final String HOOK = "dev/phoenium/chunk/NativeShulkerBulletTestHooks";
    public static void install(ClassNode node) {
        if (!node.name.equals(BULLET)) return;
        node.interfaces.add("dev/phoenium/live/ShulkerBulletPortalNativeAccess");
        MethodNode inherited = new MethodNode(Opcodes.ACC_PUBLIC,
            "phoenium$resumeShulkerAfterProjectileTick", "()V", null, null);
        inherited.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        inherited.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "afterProjectile",
            "(L" + BULLET + ";)V", false));
        inherited.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(inherited);
        MethodNode direct = new MethodNode(Opcodes.ACC_PUBLIC,
            "phoenium$resumeShulkerDirectPortalSuffix",
            "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/HitResult;Lnet/minecraft/world/phys/Vec3;)V",
            null, null);
        direct.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        direct.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        direct.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        direct.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        direct.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "directSuffix",
            "(L" + BULLET + ";Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/HitResult;"
                + "Lnet/minecraft/world/phys/Vec3;)V", false));
        direct.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(direct);
    }
}
