package dev.phoenium.chunk;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Test-only invocation plumbing. The cut is selected from the compiled production
 * mixin annotation, so a late production cut cannot pass the early-path regression. */
final class PortalDestinationTestTransform {
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String ITEM = "net/minecraft/world/entity/item/ItemEntity";
    private static final String PROCESSOR = "net/minecraft/world/entity/PortalProcessor";
    private static final String PROJECTILE = "net/minecraft/world/entity/projectile/Projectile";
    private static final String THROWABLE = "net/minecraft/world/entity/projectile/ThrowableProjectile";
    private static final String PEARL = "net/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl";
    private static final String PROFILER = "net/minecraft/util/profiling/ProfilerFiller";
    private static final String HOOK = "dev/phoenium/chunk/PortalDestinationTestHooks";
    static void install(ClassNode node) {
        if (node.name.equals(PROCESSOR)) {
            node.interfaces.add("dev/phoenium/mixin/PearlPortalProcessorAccess");
            var getter = new MethodNode(Opcodes.ACC_PUBLIC,"phoenium$portal","()Lnet/minecraft/world/level/block/Portal;",null,null);
            getter.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));
            getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,PROCESSOR,"portal","Lnet/minecraft/world/level/block/Portal;"));
            getter.instructions.add(new InsnNode(Opcodes.ARETURN)); node.methods.add(getter);
        }
        for (var method : node.methods) {
          int superOrdinal=0;
          for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (node.name.equals(ENTITY) && method.name.equals("handlePortal") && call.name.equals("setPortalCooldown"))
                method.instructions.insert(call,cut("capture",new Object[]{ENTITY,"net/minecraft/server/level/ServerLevel",PROFILER},true));
            if (node.name.equals(ENTITY) && method.name.equals("baseTick") && call.name.equals("handlePortal"))
                method.instructions.insert(call,cut("returnBase",new Object[]{ENTITY,PROFILER},true));
            if (node.name.equals(ITEM) && method.name.equals("tick") && call.name.equals("tick") && call.owner.equals(ENTITY))
                method.instructions.insert(call,cut("complete",new Object[]{ITEM},false));
            if (node.name.equals(PROJECTILE) && method.name.equals("tick") && call.name.equals("tick") && call.owner.equals(ENTITY))
                method.instructions.insert(call,cut("suspended",new Object[]{PROJECTILE},false));
            if (node.name.equals(THROWABLE) && method.name.equals("tick")) {
                if (call.name.equals("getHitResultOnMoveVector"))
                    method.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"hitResult",call.desc,false));
                if (call.name.equals("applyEffectsFromBlocks"))
                    method.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"blockEffects","(L"+ENTITY+";)V",false));
                if (call.name.equals("tick") && call.owner.equals(PROJECTILE))
                    method.instructions.insert(call,cut("suspended",new Object[]{THROWABLE,"net/minecraft/world/phys/HitResult","net/minecraft/world/phys/Vec3"},false));
            }
            if (node.name.equals(PEARL) && method.name.equals("tick") && call.name.equals("tick")) {
                if (superOrdinal++ == 1) {
                    var begin=new InsnList(); begin.add(new VarInsnNode(Opcodes.ALOAD,0));
                    begin.add(new VarInsnNode(Opcodes.ILOAD,2)); begin.add(new VarInsnNode(Opcodes.ILOAD,3)); begin.add(new VarInsnNode(Opcodes.ALOAD,4));
                    begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"beginPearl","(L"+ENTITY+";IIL"+ENTITY+";)V",false));
                    method.instructions.insertBefore(call,begin);
                    method.instructions.insert(call,cut("complete",new Object[]{PEARL,"net/minecraft/server/level/ServerLevel",Opcodes.INTEGER,Opcodes.INTEGER,ENTITY},false));
                }
            }
          }
        }
    }
    private static InsnList cut(String hook,Object[] locals,boolean popProfiler) {
        InsnList code = new InsnList(); LabelNode next = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD,0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,hook,"(L"+ENTITY+";)Z",false));
        code.add(new JumpInsnNode(Opcodes.IFEQ,next));
        if (popProfiler) {
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"net/minecraft/util/profiling/Profiler","get","()L"+PROFILER+";",false));
            code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,PROFILER,"pop","()V",true));
        }
        code.add(new InsnNode(Opcodes.RETURN)); code.add(next);
        code.add(new FrameNode(Opcodes.F_NEW,locals.length,locals,0,null));
        code.add(new InsnNode(Opcodes.NOP));
        return code;
    }
}
