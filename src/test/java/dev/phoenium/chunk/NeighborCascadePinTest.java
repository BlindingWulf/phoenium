package dev.phoenium.chunk;

import dev.phoenium.service.NeighborCascadePin;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class NeighborCascadePinTest {
    static Stream<String> seams() { return NeighborCascadePin.PINS.keySet().stream().sorted(); }

    @ParameterizedTest @MethodSource("seams")
    void exact26_2NativeInstructionsAndDescriptors(String name) throws Exception {
        NeighborCascadePin.verify(read(name));
    }

    @ParameterizedTest @MethodSource("seams")
    void changedNativeInstructionFailsDeterministically(String name) throws Exception {
        for (String signature : NeighborCascadePin.PINS.get(name).keySet()) {
            var node = read(name);
            node.methods.stream().filter(m -> signature.equals(m.name + m.desc)).findFirst().orElseThrow()
                .instructions.insert(new InsnNode(Opcodes.NOP));
            assertThrows(IllegalStateException.class, () -> NeighborCascadePin.verify(node), signature);
        }
    }

    @Test void shouldSpreadLiquidHasNoHandlerAndReadsBeforeItsOnlyMutations() throws Exception {
        var method = read(NeighborCascadePin.LIQUID).methods.stream().filter(m -> m.name.equals("shouldSpreadLiquid")).findFirst().orElseThrow();
        assertTrue(method.tryCatchBlocks.isEmpty());
        int mutations = 0;
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call) || !call.name.equals("setBlockAndUpdate")) continue;
            mutations++;
            // after each mutation the branch only fizzes and returns false: no world read, no jump back
            for (var next = insn.getNext(); next.getOpcode() != Opcodes.IRETURN; next = next.getNext()) {
                assertFalse(next instanceof JumpInsnNode, "branch after mutation");
                if (next instanceof MethodInsnNode read) assertFalse(read.owner.endsWith("/Level")
                    && (read.name.equals("getBlockState") || read.name.equals("getFluidState")), "read after mutation: " + read.name);
            }
        }
        assertEquals(2, mutations);
    }

    static ClassNode read(String name) throws Exception {
        try (var input = NeighborCascadePinTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
}
