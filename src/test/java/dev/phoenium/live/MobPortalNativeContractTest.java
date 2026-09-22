package dev.phoenium.live;

import dev.phoenium.service.VaultContinuationPin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

class MobPortalNativeContractTest {
    @Test void implicatedEndermanUsesTheSameReviewedMobAndEntityCallers()throws Exception {
        Class<?> type=net.minecraft.world.entity.monster.EnderMan.class;
        assertEquals(net.minecraft.world.entity.Mob.class,type.getMethod("tick").getDeclaringClass());
        assertEquals(net.minecraft.world.entity.Mob.class,type.getMethod("baseTick").getDeclaringClass());
        assertEquals(net.minecraft.world.entity.Entity.class,type.getMethod("teleport",net.minecraft.world.level.portal.TeleportTransition.class).getDeclaringClass());
        assertEquals(type,type.getMethod("aiStep").getDeclaringClass(),"Enderman's native AI remains dynamically dispatched by the retained living suffix");
    }
    @Test void fourNativeSuffixesBindWithoutRewritingTheirInstructions()throws Exception {
        for(String name:java.util.List.of(MobPortalNativeSuffixes.LIVING,MobPortalNativeSuffixes.MOB)) {
            var node=new ClassNode();try(var in=getClass().getResourceAsStream("/"+name+".class")){
                assertNotNull(in);new ClassReader(in).accept(node,ClassReader.EXPAND_FRAMES);
            }
            MobPortalNativeSuffixes.install(node);
            assertEquals(2,node.methods.stream().filter(m->m.name.startsWith("phoenium$resumeMob")).count());
            for(var method:node.methods) if(method.name.startsWith("phoenium$resumeMob"))
                System.out.println("MOB_SUFFIX_PIN "+method.name+" "+VaultContinuationPin.fingerprint(method));
        }
    }
}
