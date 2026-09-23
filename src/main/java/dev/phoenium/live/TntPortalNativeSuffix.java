package dev.phoenium.live;

import java.util.Collections;
import java.util.IdentityHashMap;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Copies the exact pinned PrimedTnt.tick tail after its first-instruction portal call. */
public final class TntPortalNativeSuffix {
    public static final String OWNER = "net/minecraft/world/entity/item/PrimedTnt";
    private static final String NAME = "phoenium$resumeTntTickSuffix";
    private TntPortalNativeSuffix() { }

    public static void install(ClassNode node) {
        if (!OWNER.equals(node.name)) throw new IllegalArgumentException(node.name);
        var methods = node.methods.stream().filter(m -> m.name.equals("tick") && m.desc.equals("()V")).toList();
        if (methods.size() != 1 || node.methods.stream().anyMatch(m -> m.name.equals(NAME)))
            throw new IllegalStateException("changed TNT tick declaration");
        var nativeMethod = methods.getFirst();
        var calls = nativeMethod.instructions.toArray();
        MethodInsnNode cut = null;
        int semanticPrefix = 0;
        for (var instruction : calls) {
            if (instruction instanceof MethodInsnNode call
                    && call.name.equals("handlePortal") && call.desc.equals("()V")
                    && call.owner.equals(OWNER)) {
                if (cut != null) throw new IllegalStateException("multiple TNT portal calls");
                cut = call;
            }
            if (cut == null && instruction.getOpcode() >= 0) {
                if (++semanticPrefix != 1 || instruction.getOpcode() != Opcodes.ALOAD
                        || !(instruction instanceof VarInsnNode load) || load.var != 0)
                    throw new IllegalStateException("TNT portal call is no longer first effect");
            }
        }
        if (cut == null || semanticPrefix != 1) throw new IllegalStateException("changed TNT portal call");
        var retained = Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode, Boolean>());
        for (var instruction = cut.getNext(); instruction != null; instruction = instruction.getNext()) retained.add(instruction);
        for (var instruction : retained) {
            if (instruction instanceof JumpInsnNode jump && !retained.contains(jump.label))
                throw new IllegalStateException("TNT tail jumps into its prefix");
            if (instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode)
                throw new IllegalStateException("unreviewed TNT tail switch");
        }
        var labels = new IdentityHashMap<LabelNode, LabelNode>();
        for (var instruction : calls) if (instruction instanceof LabelNode label) labels.put(label, new LabelNode());
        var suffix = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, NAME, "()V", null, null);
        for (var instruction = cut.getNext(); instruction != null; instruction = instruction.getNext())
            if (!(instruction instanceof LineNumberNode)) suffix.instructions.add(instruction.clone(labels));
        for (var handler : nativeMethod.tryCatchBlocks) {
            if (!retained.contains(handler.start) || !retained.contains(handler.end) || !retained.contains(handler.handler))
                throw new IllegalStateException("TNT tail handler crosses portal cut");
            suffix.tryCatchBlocks.add(new TryCatchBlockNode(labels.get(handler.start), labels.get(handler.end),
                labels.get(handler.handler), handler.type));
        }
        suffix.maxLocals = nativeMethod.maxLocals;
        suffix.maxStack = nativeMethod.maxStack;
        var pin = new ClassNode();
        pin.version = node.version; pin.access = Opcodes.ACC_PUBLIC;
        pin.name = OWNER + "$PortalTailPin"; pin.superName = "java/lang/Object";
        pin.methods.add(suffix);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); pin.accept(writer);
        String actual;
        try {
            actual = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(writer.toByteArray()));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        String expected = "4a6481c82fa4164611b669e0c507b59f5c277309a888e505b801cbb03e7f6151";
        if (!expected.equals(actual)) throw new IllegalStateException("Minecraft 26.2 TNT tail changed: " + actual);
        node.interfaces.add("dev/phoenium/live/TntPortalNativeAccess");
        node.methods.add(suffix);
    }
}
