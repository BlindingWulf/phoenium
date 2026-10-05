package dev.phoenium.chunk;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Test-only equivalents of EndGatewayBlockMixin's two cuts, with the native bodies untouched. */
public final class GatewayExitTestTransform {
    static final String BLOCK = "net/minecraft/world/level/block/EndGatewayBlock";
    private static final String HOOKS = "dev/phoenium/chunk/GatewayExitTestHooks";
    private GatewayExitTestTransform() { }
    static final String ENTITY = "net/minecraft/world/level/block/entity/TheEndGatewayBlockEntity";
    public static boolean supports(String name) { return name.equals(BLOCK) || name.equals(ENTITY); }
    public static void install(ClassNode node) {
        if (!supports(node.name)) return;
        if (node.name.equals(ENTITY)) {
            // Mirrors the production accessor mixin on the two private fields.
            node.interfaces.add("dev/phoenium/mixin/TheEndGatewayExitAccess");
            var exit = new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "phoenium$exitPortal", "()Lnet/minecraft/core/BlockPos;", null, null);
            exit.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            exit.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, ENTITY, "exitPortal", "Lnet/minecraft/core/BlockPos;"));
            exit.instructions.add(new InsnNode(Opcodes.ARETURN));
            var exact = new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "phoenium$exactTeleport", "()Z", null, null);
            exact.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            exact.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, ENTITY, "exactTeleport", "Z"));
            exact.instructions.add(new InsnNode(Opcodes.IRETURN));
            node.methods.add(exit); node.methods.add(exact);
            return;
        }
        for (var method : node.methods) {
            if (method.name.equals("entityInside")) {
                var guard = new InsnList();
                var proceed = new LabelNode();
                guard.add(new VarInsnNode(Opcodes.ALOAD, 2));   // level
                guard.add(new VarInsnNode(Opcodes.ALOAD, 3));   // pos
                guard.add(new VarInsnNode(Opcodes.ALOAD, 4));   // entity
                guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "mayArm",
                    "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)Z", false));
                guard.add(new JumpInsnNode(Opcodes.IFNE, proceed));
                guard.add(new InsnNode(Opcodes.RETURN));
                guard.add(proceed);
                method.instructions.insert(guard);
            }
            if (method.name.equals("getPortalDestination"))
                for (var at : method.instructions.toArray())
                    if (at instanceof MethodInsnNode call && call.name.equals("getPortalPosition"))
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "portalPosition",
                            "(Lnet/minecraft/world/level/block/entity/TheEndGatewayBlockEntity;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;", false));
        }
    }
}
