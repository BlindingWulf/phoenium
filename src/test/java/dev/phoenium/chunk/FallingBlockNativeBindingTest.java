package dev.phoenium.chunk;

import dev.phoenium.live.FallingBlockPortalNativeSuffix;
import net.minecraft.world.entity.item.FallingBlockEntity;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

class FallingBlockNativeBindingTest {
    @Test void pinnedNativeTickHasTheRetainedPortalTail() throws Exception {
        try (var input=FallingBlockEntity.class.getResourceAsStream("FallingBlockEntity.class")) {
            assertNotNull(input);
            var node=new ClassNode();new ClassReader(input).accept(node,ClassReader.EXPAND_FRAMES);
            FallingBlockPortalNativeSuffix.install(node);
            assertTrue(node.methods.stream().anyMatch(m->m.name.equals("phoenium$resumeFallingBlockTickSuffix")));
        }
    }
}
