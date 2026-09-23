package dev.phoenium.chunk;

import dev.phoenium.live.FallingBlockPortalNativeSuffix;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Test-only equivalents of the pinned falling-block tick mixin cuts. */
final class FallingBlockPortalTestTransform {
    static void install(ClassNode node) {
        if (!node.name.equals(FallingBlockPortalNativeSuffix.OWNER)) return;
        FallingBlockPortalNativeSuffix.install(node);
        for(var method:node.methods)if(method.name.equals("phoenium$resumeFallingBlockTickSuffix")) {
            var observed=new InsnList();observed.add(new VarInsnNode(Opcodes.ALOAD,0));
            observed.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/PortalDestinationTestHooks",
                "fallingTail","(Lnet/minecraft/world/entity/item/FallingBlockEntity;)V",false));
            method.instructions.insert(observed);
        }
        var methods=node.methods.stream().filter(m->m.name.equals("tick")&&m.desc.equals("()V")).toList();
        if(methods.size()!=1)throw new IllegalStateException("falling-block tick declaration changed");
        var tick=methods.getFirst();
        var begin=new InsnList(); var entry=new LabelNode();
        begin.add(new VarInsnNode(Opcodes.ALOAD,0));
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/PortalDestinationTestHooks",
            "suspended","(Lnet/minecraft/world/entity/Entity;)Z",false));
        begin.add(new JumpInsnNode(Opcodes.IFEQ,entry));begin.add(new InsnNode(Opcodes.RETURN));begin.add(entry);
        begin.add(new FrameNode(Opcodes.F_NEW,1,new Object[]{FallingBlockPortalNativeSuffix.OWNER},0,null));
        begin.add(new VarInsnNode(Opcodes.ALOAD,0));
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/PortalDestinationTestHooks",
            "beginFalling","(Lnet/minecraft/world/entity/item/FallingBlockEntity;)V",false));
        tick.instructions.insert(begin);
        int cuts=0;
        for(var instruction:tick.instructions.toArray())
            if(instruction instanceof MethodInsnNode call && call.name.equals("handlePortal")
                    && call.owner.equals(FallingBlockPortalNativeSuffix.OWNER)) {
                var next=new LabelNode();var code=new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new VarInsnNode(Opcodes.ALOAD,1));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/PortalDestinationTestHooks",
                    "completeFalling","(Lnet/minecraft/world/entity/item/FallingBlockEntity;Lnet/minecraft/world/level/block/Block;)Z",false));
                code.add(new JumpInsnNode(Opcodes.IFEQ,next));code.add(new InsnNode(Opcodes.RETURN));code.add(next);
                code.add(new FrameNode(Opcodes.F_NEW,2,new Object[]{FallingBlockPortalNativeSuffix.OWNER,
                    "net/minecraft/world/level/block/Block"},0,null));
                tick.instructions.insert(call,code);cuts++;
            }
        if(cuts!=1)throw new IllegalStateException("falling-block after-portal cut changed: "+cuts);
        for(var instruction:tick.instructions.toArray()) if(instruction.getOpcode()==Opcodes.RETURN) {
            var done=new InsnList();done.add(new VarInsnNode(Opcodes.ALOAD,0));
            done.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/phoenium/chunk/PortalDestinationTestHooks",
                "finishFalling","(Lnet/minecraft/world/entity/item/FallingBlockEntity;)V",false));
            tick.instructions.insertBefore(instruction,done);
        }
    }
}
