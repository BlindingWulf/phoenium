package dev.phoenium.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;

/** Fail-fast semantic bytecode pins for every native method the S8-F6 cascade continuation
 * reasons about: the updater loop, the four step shapes, the dispatch path and the audited
 * LiquidBlock read site. Runtime verification covers each class Phoenium mixes into;
 * NeighborUpdater and BlockStateBase are pinned by the unit test against the 26.2 jar. */
public final class NeighborCascadePin {
    public static final String UPDATER = "net/minecraft/world/level/redstone/CollectingNeighborUpdater";
    public static final String MULTI = UPDATER + "$MultiNeighborUpdate", SIMPLE = UPDATER + "$SimpleNeighborUpdate",
        FULL = UPDATER + "$FullNeighborUpdate", SHAPE = UPDATER + "$ShapeUpdate";
    public static final String NEIGHBOR_UPDATER = "net/minecraft/world/level/redstone/NeighborUpdater";
    public static final String STATE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    public static final String LIQUID = "net/minecraft/world/level/block/LiquidBlock";
    private static final String RUN_NEXT = "runNext(Lnet/minecraft/world/level/Level;)Z";
    /** class -> (name+descriptor -> fingerprint) */
    public static final Map<String, Map<String, String>> PINS = Map.of(
        UPDATER, Map.of(
            "runUpdates()V", "fa61cbb33107e7c06ea20512c46b232ea03ebe243c97db9cc298fbc995038e15",
            "addAndRun(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater$NeighborUpdates;)V", "2eb904b369402002e5dfb4b15f64251ed5d1d2eac87875a57e30c941088dcb8e"),
        MULTI, Map.of(RUN_NEXT, "56d83112100a82776024466340ef9803521ef74ba74f2cd8d8a40c7d7eb51de5"),
        SIMPLE, Map.of(RUN_NEXT, "f1c12cf6c999275357bf246086a696d07229561a6a45fea56aeb8bb88f864ed9"),
        FULL, Map.of(RUN_NEXT, "a2565798fdb61dbd7aace6c55456ade5f71895577b3ba3bc41eb28ab3f5fdc07"),
        SHAPE, Map.of(RUN_NEXT, "c054357eadd79af9784f7a09fea36bee58f5e0e49130ace1d04db38f954d0c43"),
        NEIGHBOR_UPDATER, Map.of(
            "executeUpdate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V", "eb3b30eed44544b0c00290860f5ef5fcc0f7acd2f0cd777ef1a7d2b8efdb5d3d",
            "executeShapeUpdate(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)V", "05d78024cb431b6937563b193a2bcadab87febad96d781da3b05cf3f03d4819f"),
        STATE, Map.of(
            "handleNeighborChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V", "41575e4e4a4326ea0921fa56fd040eeb0127e6f6883a8c794e4487765e8b6ba1"),
        LIQUID, Map.of(
            "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V", "12aa0b6e0adac20b19c30be09326d20a0c19287f1fe903862e45d35317adf2a5",
            "shouldSpreadLiquid(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z", "7155765ce794d92c4c29fdacf11a00e07269a03eeb97f8b6816eeb6ed339b06c"));
    private NeighborCascadePin() { }

    public static void verify(ClassNode node) {
        var pins = PINS.get(node.name);
        if (pins == null) throw new IllegalArgumentException("not a neighbor cascade seam: " + node.name);
        pins.forEach((signature, expected) -> {
            var methods = node.methods.stream().filter(m -> signature.equals(m.name + m.desc)).toList();
            if (methods.size() != 1)
                throw new IllegalStateException("Minecraft 26.2 neighbor cascade seam descriptor changed: " + node.name + "." + signature);
            String actual = fingerprint(methods.getFirst());
            if (!expected.equals(actual)) throw new IllegalStateException("Minecraft 26.2 neighbor cascade bytecode changed: "
                + node.name + "." + signature + ": " + actual + " expected " + expected);
        });
    }

    /** VaultContinuationPin's semantic form, extended to switches and invokedynamic. */
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
                case InvokeDynamicInsnNode i -> {
                    text.append(':').append(i.name).append(i.desc).append(':').append(handle(i.bsm));
                    for (Object argument : i.bsmArgs) text.append(':').append(argument instanceof Handle h ? handle(h) : argument);
                }
                case TableSwitchInsnNode i -> {
                    text.append(':').append(i.min).append(':').append(i.max).append(':').append(labels.get(i.dflt));
                    for (var label : i.labels) text.append(':').append(labels.get(label));
                }
                case LookupSwitchInsnNode i -> {
                    text.append(':').append(labels.get(i.dflt));
                    for (int k = 0; k < i.keys.size(); k++) text.append(':').append(i.keys.get(k)).append('=').append(labels.get(i.labels.get(k)));
                }
                case InsnNode ignored -> { }
                default -> throw new IllegalStateException("unreviewed neighbor cascade instruction " + insn.getClass());
            }
            text.append('\n');
        }
        for (var handler : method.tryCatchBlocks) text.append("catch:").append(labels.get(handler.start)).append(':')
            .append(labels.get(handler.end)).append(':').append(labels.get(handler.handler)).append(':').append(handler.type).append('\n');
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String handle(Handle handle) {
        return handle.getTag() + "/" + handle.getOwner() + "." + handle.getName() + handle.getDesc() + "/" + handle.isInterface();
    }
}
