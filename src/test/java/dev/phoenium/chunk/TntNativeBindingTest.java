package dev.phoenium.chunk;

import dev.phoenium.live.TntPortalNativeSuffix;
import net.minecraft.world.entity.item.PrimedTnt;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

class TntNativeBindingTest {
    @Test void pinnedNativeTickHasExactNoLocalPortalTail() throws Exception {
        try (var input = PrimedTnt.class.getResourceAsStream("PrimedTnt.class")) {
            assertNotNull(input);
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.EXPAND_FRAMES);
            TntPortalNativeSuffix.install(node);
            var suffixes = node.methods.stream()
                .filter(method -> method.name.equals("phoenium$resumeTntTickSuffix")
                    && method.desc.equals("()V")).toList();
            assertEquals(1, suffixes.size());
            assertTrue(node.interfaces.contains("dev/phoenium/live/TntPortalNativeAccess"));
            assertFalse(java.util.Arrays.stream(suffixes.getFirst().instructions.toArray())
                .filter(org.objectweb.asm.tree.MethodInsnNode.class::isInstance)
                .map(org.objectweb.asm.tree.MethodInsnNode.class::cast)
                .anyMatch(call -> call.name.equals("handlePortal")));
        }
    }
}
