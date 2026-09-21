package dev.phoenium.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.objectweb.asm.tree.*;

/** Fail-fast semantic bytecode pins for every mirrored instruction and local slot.
 * Debug tables/constant-pool numbering are excluded; descriptors, order, constants,
 * field writes, call counts and jump destinations are all included. */
public final class VaultContinuationPin {
    public static final String LEVEL = "net/minecraft/world/level/Level";
    public static final String ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    public static final String VAULT = "net/minecraft/world/level/block/entity/vault/VaultBlockEntity$Server";
    public static final String CHANGED = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V";
    public static final String TICK = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/vault/VaultConfig;Lnet/minecraft/world/level/block/entity/vault/VaultServerData;Lnet/minecraft/world/level/block/entity/vault/VaultSharedData;)V";
    public static final String OUTPUT = "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V";
    private VaultContinuationPin() { }
    public static void verify(ClassNode node) {
        String name, descriptor, expected;
        switch (node.name) {
            case LEVEL -> { name = "updateNeighbourForOutputSignal"; descriptor = OUTPUT; expected = "ea216e0f798cfaab805642ff11d507db4efa934bacd291da8f70296077f99c28"; }
            case ENTITY -> { name = "setChanged"; descriptor = CHANGED; expected = "0bdc96c32dd8df69671dd840e4346574b262c38da490ad73ec5b9c1894231ea6"; }
            case VAULT -> { name = "tick"; descriptor = TICK; expected = "eff42a13917e8155d188e51f4c7db6a2cead7cca8a8f1ea428ae02a242141132"; }
            default -> throw new IllegalArgumentException("not a Vault continuation seam: " + node.name);
        }
        var methods = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).toList();
        if (methods.size() != 1) throw new IllegalStateException("Minecraft 26.2 Vault seam descriptor changed: " + node.name + "." + name);
        String actual = fingerprint(methods.getFirst());
        if (!expected.equals(actual)) throw new IllegalStateException("Minecraft 26.2 Vault continuation bytecode changed: "
            + node.name + "." + name + ": " + actual + " expected " + expected);
    }
    public static String fingerprint(MethodNode method) {
        StringBuilder text = new StringBuilder(method.name).append(method.desc).append(':').append(method.access).append('\n');
        Map<LabelNode, Integer> labels = new IdentityHashMap<>(); int pc = 0;
        for (var insn : method.instructions) { if (insn instanceof LabelNode label) labels.put(label, pc); if (insn.getOpcode() >= 0) pc++; }
        for (var insn : method.instructions) {
            if (insn.getOpcode() < 0) continue;
            text.append(insn.getOpcode());
            switch (insn) {
                case VarInsnNode i -> text.append(':').append(i.var);
                case MethodInsnNode i -> text.append(':').append(i.owner).append('.').append(i.name).append(i.desc).append(':').append(i.itf);
                case FieldInsnNode i -> text.append(':').append(i.owner).append('.').append(i.name).append(i.desc);
                case JumpInsnNode i -> text.append(':').append(labels.get(i.label));
                case TypeInsnNode i -> text.append(':').append(i.desc);
                case IntInsnNode i -> text.append(':').append(i.operand);
                case LdcInsnNode i -> text.append(':').append(i.cst);
                case IincInsnNode i -> text.append(':').append(i.var).append(':').append(i.incr);
                case InsnNode ignored -> { }
                default -> throw new IllegalStateException("unreviewed Vault instruction " + insn.getClass());
            }
            text.append('\n');
        }
        for (var handler : method.tryCatchBlocks) text.append("catch:").append(labels.get(handler.start)).append(':')
            .append(labels.get(handler.end)).append(':').append(labels.get(handler.handler)).append(':').append(handler.type).append('\n');
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
