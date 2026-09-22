package dev.phoenium.live;

import dev.phoenium.service.VaultContinuationPin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

class MobPortalNativeContractTest {
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
