package dev.phoenium.chunk;

import dev.phoenium.live.MobPortalNativeSuffixes;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Install the production native tails and the two exact after-super cuts per class. */
public final class MobPortalTestTransform {
    public static boolean supports(String name) { return name.equals(MobPortalNativeSuffixes.LIVING)||name.equals(MobPortalNativeSuffixes.MOB); }
    public static byte[] transform(String name,byte[] bytes) {
        var node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);
        install(node);
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();
    }
    public static void install(ClassNode node) {
        String name=node.name;if(!supports(name))return;
        MobPortalNativeSuffixes.install(node);
        if(name.equals(MobPortalNativeSuffixes.LIVING))for(var method:node.methods)if(method.name.equals("tickDeath"))
            for(var i:method.instructions.toArray())if(i instanceof MethodInsnNode call&&call.name.equals("remove")&&call.owner.equals(name))
                method.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/NativePlayerDeathTestHooks","remove","(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/Entity$RemovalReason;)V",false));
        for(var method:node.methods) if((method.name.equals("tick")||method.name.equals("baseTick"))&&method.desc.equals("()V"))
            for(var i:method.instructions.toArray()) if(i instanceof MethodInsnNode call && call.getOpcode()==Opcodes.INVOKESPECIAL
                    && call.owner.equals(node.superName)&&call.name.equals(method.name)&&call.desc.equals("()V")) {
                var code=new InsnList();var next=new LabelNode();
                code.add(new VarInsnNode(Opcodes.ALOAD,0));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/PortalDestinationTestHooks","suspended","(Lnet/minecraft/world/entity/Entity;)Z",false));
                code.add(new JumpInsnNode(Opcodes.IFEQ,next));code.add(new InsnNode(Opcodes.RETURN));code.add(next);
                code.add(new FrameNode(Opcodes.F_NEW,1,new Object[]{name},0,null));code.add(new InsnNode(Opcodes.NOP));
                method.instructions.insert(call,code);
            }
    }
}
