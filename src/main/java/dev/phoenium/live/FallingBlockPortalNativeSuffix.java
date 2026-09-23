package dev.phoenium.live;

import java.util.Collections;
import java.util.IdentityHashMap;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

/** Copies only the native FallingBlockEntity.tick tail after handlePortal. Slot one is
 * the original Block local; the prefix and its RNG draws are never replayed. */
public final class FallingBlockPortalNativeSuffix {
    public static final String OWNER = "net/minecraft/world/entity/item/FallingBlockEntity";
    private static final String NAME = "phoenium$resumeFallingBlockTickSuffix";
    private FallingBlockPortalNativeSuffix() { }

    public static void install(ClassNode node) {
        if (!OWNER.equals(node.name)) throw new IllegalArgumentException(node.name);
        var methods = node.methods.stream().filter(m -> m.name.equals("tick") && m.desc.equals("()V")).toList();
        if (methods.size() != 1 || node.methods.stream().anyMatch(m -> m.name.equals(NAME)))
            throw new IllegalStateException("changed falling-block tick declaration");
        var nativeMethod = methods.getFirst();
        var calls = nativeMethod.instructions.toArray();
        MethodInsnNode cut = null;
        for (var instruction : calls) if (instruction instanceof MethodInsnNode call
                && call.name.equals("handlePortal") && call.desc.equals("()V")
                && call.owner.equals(OWNER)) {
            if (cut != null) throw new IllegalStateException("multiple falling-block portal calls");
            cut = call;
        }
        if (cut == null) throw new IllegalStateException("changed falling-block portal call");
        var retained = Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode, Boolean>());
        for (var instruction = cut.getNext(); instruction != null; instruction = instruction.getNext()) retained.add(instruction);
        for (var instruction : retained) {
            if (instruction instanceof JumpInsnNode jump && !retained.contains(jump.label))
                throw new IllegalStateException("falling-block tail jumps into its prefix");
            if (instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode)
                throw new IllegalStateException("unreviewed falling-block tail switch");
        }
        var labels = new IdentityHashMap<LabelNode, LabelNode>();
        for (var instruction : calls) if (instruction instanceof LabelNode label) labels.put(label, new LabelNode());
        var suffix = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, NAME,
            "(Lnet/minecraft/world/level/block/Block;)V", null, null);
        for (var instruction = cut.getNext(); instruction != null; instruction = instruction.getNext()) {
            if (!(instruction instanceof LineNumberNode)) suffix.instructions.add(instruction.clone(labels));
        }
        for (var handler : nativeMethod.tryCatchBlocks) {
            if (!retained.contains(handler.start) || !retained.contains(handler.end) || !retained.contains(handler.handler))
                throw new IllegalStateException("falling-block tail handler crosses portal cut");
            suffix.tryCatchBlocks.add(new TryCatchBlockNode(labels.get(handler.start),labels.get(handler.end),
                labels.get(handler.handler),handler.type));
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
        String expected = "c8f68759701c5167197f20d88c2ca7b29f79403123fbf580d061c623cc3909d6";
        if (!expected.equals(actual)) throw new IllegalStateException("Minecraft 26.2 falling-block tail changed: " + actual);
        node.interfaces.add("dev/phoenium/live/FallingBlockPortalNativeAccess");
        node.methods.add(suffix);
    }
}
