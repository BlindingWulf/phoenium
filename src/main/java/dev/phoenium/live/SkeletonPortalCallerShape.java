package dev.phoenium.live;

import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Skeleton's 26.2 outer tick does its conversion prefix, then returns directly
 * after AbstractSkeleton.tick. Mob's retained continuation therefore owns the
 * complete remaining native stack only while this shape stays true. */
public final class SkeletonPortalCallerShape {
    private SkeletonPortalCallerShape() { }
    public static void verify(ClassNode node) {
        if(!node.name.equals("net/minecraft/world/entity/monster/skeleton/Skeleton"))
            throw new IllegalArgumentException(node.name);
        List<MethodNode> ticks=node.methods.stream().filter(method->method.name.equals("tick")
            &&method.desc.equals("()V")).toList();
        if(ticks.size()!=1||!ticks.getFirst().tryCatchBlocks.isEmpty())
            throw new IllegalStateException("Skeleton outer portal caller changed");
        MethodNode tick=ticks.getFirst();
        MethodInsnNode parent=null;
        for(var instruction:tick.instructions)
            if(instruction instanceof MethodInsnNode call &&call.getOpcode()==Opcodes.INVOKESPECIAL
                &&call.owner.equals("net/minecraft/world/entity/monster/skeleton/AbstractSkeleton")
                &&call.name.equals("tick")&&call.desc.equals("()V")) {
                if(parent!=null)throw new IllegalStateException("Skeleton outer tick gained another parent call");
                parent=call;
            }
        if(parent==null)throw new IllegalStateException("Skeleton outer tick lost native Mob path");
        int returns=0;
        for(var instruction=parent.getNext();instruction!=null;instruction=instruction.getNext()) {
            int opcode=instruction.getOpcode();
            if(opcode<0)continue;
            if(opcode==Opcodes.RETURN){returns++;continue;}
            throw new IllegalStateException("Skeleton outer tick gained a post-super effect: "+opcode);
        }
        if(returns!=1)throw new IllegalStateException("Skeleton outer tick return changed");
    }
}
