package dev.phoenium.chunk;

import dev.phoenium.live.MobPortalNativeSuffixes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

class HorsePortalNativeBindingTest {
    @Test void pinnedNativeHorseTailMatchesMappedSource() throws Exception {
        var node=new ClassNode();
        try(var bytes=AbstractHorse.class.getResourceAsStream("AbstractHorse.class")) {
            assertNotNull(bytes);new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);
        }
        assertDoesNotThrow(()->MobPortalNativeSuffixes.install(node));
    }
}
