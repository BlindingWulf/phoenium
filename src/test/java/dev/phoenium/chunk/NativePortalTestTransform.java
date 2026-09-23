package dev.phoenium.chunk;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.Set;

/** Test-only equivalents of the narrow production WrapOperation bindings. Native teleport
 * creation, serialization, state reset, ordering, overrides and return values remain executable. */
public final class NativePortalTestTransform {
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String ITEM = "net/minecraft/world/entity/item/ItemEntity";
    private static final String TNT = "net/minecraft/world/entity/item/PrimedTnt";
    private static final String VEX = "net/minecraft/world/entity/monster/Vex";
    private static final String PEARL = "net/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl";
    private static final String HURTING = "net/minecraft/world/entity/projectile/hurtingprojectile/AbstractHurtingProjectile";
    private static final String WIND_CHARGE = "net/minecraft/world/entity/projectile/hurtingprojectile/windcharge/WindCharge";
    private static final String HOOK = "dev/phoenium/chunk/NativePortalTestHooks";
    public static boolean supports(String name) { return Set.of(ENTITY, ITEM, TNT, VEX, PEARL, "net/minecraft/world/entity/PortalProcessor",
        "net/minecraft/world/entity/item/FallingBlockEntity", "net/minecraft/world/level/block/EndPortalBlock",
        "net/minecraft/world/level/portal/TeleportTransition",
        "net/minecraft/world/entity/projectile/Projectile", HURTING, WIND_CHARGE,
        "net/minecraft/world/entity/projectile/ThrowableProjectile",
        "net/minecraft/server/level/ServerPlayer").contains(name); }
    static void install(ClassNode node) {
        if (!supports(node.name)) return;
        PortalDestinationTestTransform.install(node);
        NativePlayerPortalTestTransform.install(node);
        if(node.name.equals("net/minecraft/world/level/portal/TeleportTransition"))
            for(MethodNode method:node.methods)if(method.name.equals("placePortalTicket"))
                for(var instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode call
                        && call.name.equals("placePortalTicket") && call.owner.equals(ENTITY))
                    method.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"placePortalTicket",
                        "(L"+ENTITY+";Lnet/minecraft/core/BlockPos;)V",false));
        for (MethodNode method : node.methods.toArray(MethodNode[]::new)) {
            if (!method.name.equals("teleport") && !method.name.equals("teleportCrossDimension")) continue;
            for (var at : method.instructions.toArray()) if (at instanceof MethodInsnNode call) {
                String hook = switch (call.name) {
                    case "stopRiding", "ejectPassengers", "restoreFrom", "removeAfterChangingDimensions",
                         "addDuringTeleport", "resetEmptyTime", "onTransition", "mergeWithNeighbours", "placePortalTicket" -> call.name;
                    case "teleportSetPosition" -> method.name.equals("teleportCrossDimension") ? call.name : null;
                    case "create" -> call.owner.equals("net/minecraft/world/entity/EntityType") ? call.name : null;
                    default -> null;
                };
                if (hook == null) continue;
                String descriptor = call.desc.replace("(", "(L" + call.owner + ";");
                if (hook.equals("create")) {
                    method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD,0));
                    descriptor = descriptor.replace(")", "L" + ENTITY + ";)");
                }
                method.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,hook,descriptor,false));
            }
        }
        if (node.name.equals(ENTITY)) {
            node.interfaces.add("dev/phoenium/live/PearlPortalNativeAccess$EntitySuffix");
            for (String name : new String[]{"phoenium$resumePearlPortalBaseTickSuffix"}) {
                MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC,name,"()V",null,null);
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"baseSuffix","(L"+ENTITY+";)V",false));
                method.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(method);
            }
        }
        if (node.name.equals(ITEM)) {
            node.interfaces.add("dev/phoenium/live/ItemPortalTransitionManager$ItemSuffix");
            MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC,"phoenium$resumeItemTickSuffix","()V",null,null);
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"itemSuffix","(L"+ITEM+";)V",false));
            method.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(method);
        }
        if (node.name.equals(ITEM) || node.name.equals(TNT) || node.name.equals(VEX))
            restoreReference(node, node.name.equals(ITEM) ? "thrower" : "owner");
        if (node.name.equals(PEARL)) {
            for (String name:new String[]{"ProjectileSuffix","ThrowableSuffix","PearlSuffix"}) node.interfaces.add("dev/phoenium/live/PearlPortalNativeAccess$"+name);
            suffix(node,"phoenium$resumePearlPortalProjectileTickSuffix","()V","projectileSuffix");
            suffix(node,"phoenium$resumePearlPortalThrowableTickSuffix","(Lnet/minecraft/world/phys/HitResult;)V","throwableSuffix");
            suffix(node,"phoenium$resumePearlPortalPearlTickSuffix","(IIL"+ENTITY+";)V","pearlSuffix");
        }
        if (node.name.equals("net/minecraft/world/entity/projectile/Projectile")) {
            node.interfaces.add("dev/phoenium/live/PearlPortalNativeAccess$ProjectileSuffix");
            suffix(node,"phoenium$resumePearlPortalProjectileTickSuffix","()V","projectileSuffix");
            restoreReference(node, "owner");
        }
        if (node.name.equals("net/minecraft/world/entity/projectile/ThrowableProjectile")) {
            node.interfaces.add("dev/phoenium/live/PearlPortalNativeAccess$ThrowableSuffix");
            suffix(node,"phoenium$resumePearlPortalThrowableTickSuffix",
                "(Lnet/minecraft/world/phys/HitResult;)V","throwableSuffix");
        }
        if (node.name.equals(HURTING)) {
            node.interfaces.add("dev/phoenium/live/HurtingProjectilePortalNativeAccess");
            suffix(node,"phoenium$resumeHurtingProjectileTickSuffix",
                "(Lnet/minecraft/world/phys/HitResult;)V","hurtingProjectileSuffix");
        }
        if (node.name.equals(WIND_CHARGE)) {
            node.interfaces.add("dev/phoenium/live/HurtingProjectilePortalNativeAccess$WindChargeSuffix");
            suffix(node,"phoenium$resumeWindChargeTickSuffix","()V","windChargeSuffix");
        }
    }
    /** Mirrors the four production restore mixins on their original private reference fields. */
    private static void restoreReference(ClassNode node, String field) {
        node.interfaces.add("dev/phoenium/live/NativePortalRestoreAccess");
        String reference = "Lnet/minecraft/world/entity/EntityReference;";
        MethodNode capture = new MethodNode(Opcodes.ACC_PUBLIC,
            "phoenium$capturePortalRestoreReference", "()" + reference, null, null);
        capture.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        capture.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field, reference));
        capture.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(capture);
        MethodNode install = new MethodNode(Opcodes.ACC_PUBLIC,
            "phoenium$installPortalRestoreReference", "(" + reference + ")V", null, null);
        install.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        install.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        install.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, field, reference));
        install.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(install);
    }
    private static void suffix(ClassNode node,String name,String descriptor,String hook) {
        MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC,name,descriptor,null,null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0)); int slot=1;
        for(var type:Type.getArgumentTypes(descriptor)) { method.instructions.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD),slot)); slot+=type.getSize(); }
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,hook,descriptor.replace("(","(L"+ENTITY+";"),false));
        method.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(method);
    }
}
