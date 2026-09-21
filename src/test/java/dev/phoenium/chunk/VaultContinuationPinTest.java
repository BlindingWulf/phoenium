package dev.phoenium.chunk;

import dev.phoenium.service.VaultContinuationPin;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class VaultContinuationPinTest {
    @ParameterizedTest @ValueSource(strings = {VaultContinuationPin.LEVEL, VaultContinuationPin.ENTITY, VaultContinuationPin.VAULT})
    void exact26_2NativeInstructionsAndDescriptors(String name) throws Exception {
        VaultContinuationPin.verify(read(name));
    }
    @ParameterizedTest @ValueSource(strings = {VaultContinuationPin.LEVEL, VaultContinuationPin.ENTITY, VaultContinuationPin.VAULT})
    void changedNativeInstructionFailsDeterministically(String name) throws Exception {
        var node = read(name);
        var method = node.methods.stream().filter(m -> m.name.equals(name.equals(VaultContinuationPin.LEVEL)
            ? "updateNeighbourForOutputSignal" : name.equals(VaultContinuationPin.VAULT) ? "tick" : "setChanged")
            && (!name.equals(VaultContinuationPin.ENTITY) || m.desc.equals(VaultContinuationPin.CHANGED))).findFirst().orElseThrow();
        method.instructions.insert(new InsnNode(Opcodes.NOP));
        assertThrows(IllegalStateException.class, () -> VaultContinuationPin.verify(node));
    }
    static ClassNode read(String name) throws Exception {
        try (var input = VaultContinuationPinTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
}
