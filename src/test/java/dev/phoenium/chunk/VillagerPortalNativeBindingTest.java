package dev.phoenium.chunk;

import dev.phoenium.live.MobPortalNativeSuffixes;
import net.minecraft.world.entity.npc.villager.Villager;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

class VillagerPortalNativeBindingTest {
    @Test void pinnedNativeVillagerTailMatchesMappedSource() throws Exception {
        var node=new ClassNode();
        try(var bytes=Villager.class.getResourceAsStream("Villager.class")) {
            assertNotNull(bytes);new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);
        }
        assertDoesNotThrow(()->MobPortalNativeSuffixes.install(node));
    }
}
