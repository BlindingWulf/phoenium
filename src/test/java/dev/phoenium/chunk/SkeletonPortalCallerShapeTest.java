package dev.phoenium.chunk;

import dev.phoenium.live.SkeletonPortalCallerShape;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

class SkeletonPortalCallerShapeTest {
    @Test void pinnedOuterTickReturnsDirectlyAfterItsNativeParent() throws Exception {
        var node=new ClassNode();
        try(var bytes=Skeleton.class.getResourceAsStream("Skeleton.class")) {
            assertNotNull(bytes);new ClassReader(bytes).accept(node,0);
        }
        assertDoesNotThrow(()->SkeletonPortalCallerShape.verify(node));
        var tick=node.methods.stream().filter(method->method.name.equals("tick")&&method.desc.equals("()V"))
            .findFirst().orElseThrow();
        var parent=tick.instructions.iterator();
        MethodInsnNode call=null;
        while(parent.hasNext()) {
            var instruction=parent.next();
            if(instruction instanceof MethodInsnNode method&&method.getOpcode()==Opcodes.INVOKESPECIAL
                    &&method.name.equals("tick")&&method.owner.endsWith("/AbstractSkeleton"))call=method;
        }
        assertNotNull(call);
        tick.instructions.insert(call,new InsnNode(Opcodes.NOP));
        assertThrows(IllegalStateException.class,()->SkeletonPortalCallerShape.verify(node));
    }
}
