package dev.phoenium.live;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Generalization of {@link MobPortalNativeSuffixes} to every pinned Mob subclass that declares its own
 * {@code tick()} or {@code baseTick()}. For each reviewed (class, method) the instructions after the single
 * {@code super.method()} call are copied, unchanged, into a resume method of the same declaring class, and the
 * original gains one early return while the mob's portal transfer is retained. The reviewed pin file records
 * the exact fingerprint of every tail; a changed Minecraft body fails binding instead of silently diverging.
 *
 * <p>Rules (all checked mechanically): exactly one super call, no handlers, no branch or switch crossing the
 * cut, and at most one live caller local: the {@code int airSupply = getAirSupply()} that the four
 * water-animal {@code baseTick} overrides read after their super call (captured at suspension, see
 * {@link Capture#AIR_SUPPLY}; no intervening native code writes it).
 */
public final class MobPortalOuterTails {
    public enum Capture { NONE, AIR_SUPPLY }

    /** One reviewed pin. {@code emptyTail} means nothing executes after the super call. */
    public record Pin(String owner, String method, Capture capture, boolean emptyTail, String fingerprint) { }

    /** Result of lifting one tail from a pinned native method. */
    public record Lifted(MethodInsnNode cut, MethodNode method, Capture capture, boolean emptyTail, String fingerprint) { }

    public static final String RESOURCE = "/mob-portal-tail-pins.txt";
    private static final String GUARD = "dev/phoenium/live/MobPortalGuard";

    private MobPortalOuterTails() { }

    public static String resumeName(String owner, String method) {
        return "phoenium$resumeTail$" + method + "$" + owner.substring(owner.lastIndexOf('/') + 1);
    }

    // ---------------------------------------------------------------------------------------------- binding

    /** Declaring classes that carry reviewed pins. */
    public static java.util.Set<String> targets() { return pins().keySet(); }

    public static Map<String, List<Pin>> pins() { return Pinned.PINS; }

    /** Lock-free lazy load: class initialization is the only synchronization (no monitors in production code). */
    private static final class Pinned {
        static final Map<String, List<Pin>> PINS = Collections.unmodifiableMap(read(RESOURCE));
    }

    static Map<String, List<Pin>> read(String resource) {
        Map<String, List<Pin>> byOwner = new LinkedHashMap<>();
        try (InputStream in = MobPortalOuterTails.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing pin resource " + resource);
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] f = line.split("\\|");
                    if (f.length != 5) throw new IllegalStateException("bad pin line: " + line);
                    byOwner.computeIfAbsent(f[0], k -> new ArrayList<>())
                        .add(new Pin(f[0], f[1], Capture.valueOf(f[2]), Boolean.parseBoolean(f[3]), f[4]));
                }
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException("unreadable pin resource", failure); }
        return byOwner;
    }

    /** Verifies every pin for this declaring class, then adds resume methods and the suspended early return. */
    public static void install(ClassNode node) {
        List<Pin> reviewed = pins().get(node.name);
        if (reviewed == null) throw new IllegalArgumentException("unreviewed mob tail owner " + node.name);
        for (Pin pin : reviewed) {
            Lifted lifted = lift(node, pin.method());
            if (!pin.fingerprint().equals(lifted.fingerprint()) || pin.capture() != lifted.capture()
                    || pin.emptyTail() != lifted.emptyTail())
                throw new IllegalStateException("Minecraft 26.2 mob tail changed: " + node.name + "." + pin.method()
                    + " " + lifted.capture() + " empty=" + lifted.emptyTail() + " " + lifted.fingerprint());
            if (lifted.emptyTail()) continue;
            String resume = resumeName(node.name, pin.method());
            if (node.methods.stream().anyMatch(m -> m.name.equals(resume)))
                throw new IllegalStateException("mob tail already installed: " + node.name + "." + pin.method());
            MethodNode nativeMethod = node.methods.stream()
                .filter(m -> m.name.equals(pin.method()) && m.desc.equals("()V")).findFirst().orElseThrow();
            lifted.method().name = resume;
            node.methods.add(lifted.method());
            InsnList guard = new InsnList();
            LabelNode proceed = new LabelNode();
            guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "suspended", "(Ljava/lang/Object;)Z", false));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(proceed);
            nativeMethod.instructions.insert(lifted.cut(), guard);
        }
    }

    // ---------------------------------------------------------------------------------------------- lifting

    /** Extracts the suffix after the single super call; throws when a reviewed rule does not hold. */
    public static Lifted lift(ClassNode node, String name) {
        var methods = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals("()V")).toList();
        if (methods.size() != 1) throw new IllegalStateException("changed mob tail method: " + node.name + "." + name);
        MethodNode nativeMethod = methods.getFirst();
        var cuts = new ArrayList<MethodInsnNode>();
        for (var i : nativeMethod.instructions)
            if (i instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals(node.superName) && call.name.equals(name) && call.desc.equals("()V")) cuts.add(call);
        if (cuts.size() != 1 || !nativeMethod.tryCatchBlocks.isEmpty())
            throw new IllegalStateException("changed mob super boundary: " + node.name + "." + name);
        MethodInsnNode cut = cuts.getFirst();
        var tail = Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode, Boolean>());
        for (var i = cut.getNext(); i != null; i = i.getNext()) tail.add(i);
        for (var i : nativeMethod.instructions) {
            boolean inside = tail.contains(i);
            if (i instanceof JumpInsnNode jump && inside != tail.contains(jump.label))
                throw new IllegalStateException("mob boundary gained a crossing branch: " + node.name + "." + name);
            if (inside && i instanceof TableSwitchInsnNode ts && (!tail.contains(ts.dflt) || ts.labels.stream().anyMatch(l -> !tail.contains(l))))
                throw new IllegalStateException("mob tail switch leaves the tail: " + node.name + "." + name);
            if (inside && i instanceof LookupSwitchInsnNode ls && (!tail.contains(ls.dflt) || ls.labels.stream().anyMatch(l -> !tail.contains(l))))
                throw new IllegalStateException("mob tail switch leaves the tail: " + node.name + "." + name);
            if (!inside && i instanceof TableSwitchInsnNode ts && (tail.contains(ts.dflt) || ts.labels.stream().anyMatch(tail::contains))
                    || !inside && i instanceof LookupSwitchInsnNode ls && (tail.contains(ls.dflt) || ls.labels.stream().anyMatch(tail::contains)))
                throw new IllegalStateException("mob prefix switch enters the tail: " + node.name + "." + name);
        }
        // Prefix locals read in the tail before any tail store are live across the cut.
        BitSet stored = new BitSet(), live = new BitSet();
        for (var i = cut.getNext(); i != null; i = i.getNext()) {
            if (!(i instanceof VarInsnNode v)) {
                if (i instanceof IincInsnNode inc && inc.var != 0 && !stored.get(inc.var)) live.set(inc.var);
                continue;
            }
            boolean store = v.getOpcode() >= Opcodes.ISTORE && v.getOpcode() <= Opcodes.ASTORE;
            boolean load = v.getOpcode() >= Opcodes.ILOAD && v.getOpcode() <= Opcodes.ALOAD;
            if (store) stored.set(v.var);
            else if (load && v.var != 0 && !stored.get(v.var)) live.set(v.var);
        }
        Capture capture = Capture.NONE;
        if (!live.isEmpty()) {
            if (!live.equals(bits(1)) || !airSupplyPrefix(nativeMethod))
                throw new IllegalStateException("mob tail has unreviewed live caller locals " + live + ": " + node.name + "." + name);
            capture = Capture.AIR_SUPPLY;
        }
        boolean empty = true;
        for (var i = cut.getNext(); i != null; i = i.getNext())
            if (i.getOpcode() >= 0 && i.getOpcode() != Opcodes.RETURN) { empty = false; break; }
        var labels = new IdentityHashMap<LabelNode, LabelNode>();
        for (var i : nativeMethod.instructions) if (i instanceof LabelNode l) labels.put(l, new LabelNode());
        var lifted = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "phoenium$lifted",
            capture == Capture.AIR_SUPPLY ? "(I)V" : "()V", null, null);
        for (var i = cut.getNext(); i != null; i = i.getNext()) {
            if (i instanceof LineNumberNode || i instanceof FrameNode) continue;
            lifted.instructions.add(i.clone(labels));
        }
        lifted.maxLocals = nativeMethod.maxLocals;
        lifted.maxStack = nativeMethod.maxStack;
        return new Lifted(cut, lifted, capture, empty, fingerprint(lifted));
    }

    private static BitSet bits(int... values) { BitSet b = new BitSet(); for (int v : values) b.set(v); return b; }

    /** The single reviewed caller local: first statement is {@code int airSupply = this.getAirSupply();}. */
    private static boolean airSupplyPrefix(MethodNode method) {
        var real = new ArrayList<AbstractInsnNode>();
        for (var i : method.instructions) if (i.getOpcode() >= 0) { real.add(i); if (real.size() == 3) break; }
        return real.size() == 3
            && real.get(0) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0
            && real.get(1) instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && call.name.equals("getAirSupply") && call.desc.equals("()I")
            && real.get(2) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE && store.var == 1;
    }

    /** Complete, order-stable text fingerprint of a lifted body (labels as ordinals; all node kinds). */
    public static String fingerprint(MethodNode method) {
        StringBuilder text = new StringBuilder(method.desc).append('\n');
        Map<LabelNode, Integer> labels = new IdentityHashMap<>();
        int pc = 0;
        for (var insn : method.instructions) { if (insn instanceof LabelNode l) labels.put(l, pc); if (insn.getOpcode() >= 0) pc++; }
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
                case LdcInsnNode i -> text.append(':').append(i.cst.getClass().getSimpleName()).append(':').append(i.cst);
                case IincInsnNode i -> text.append(':').append(i.var).append(':').append(i.incr);
                case MultiANewArrayInsnNode i -> text.append(':').append(i.desc).append(':').append(i.dims);
                case TableSwitchInsnNode i -> {
                    text.append(':').append(i.min).append(':').append(i.max).append(':').append(labels.get(i.dflt));
                    for (var l : i.labels) text.append(',').append(labels.get(l));
                }
                case LookupSwitchInsnNode i -> {
                    text.append(':').append(labels.get(i.dflt));
                    for (int k = 0; k < i.keys.size(); k++) text.append(',').append(i.keys.get(k)).append('>').append(labels.get(i.labels.get(k)));
                }
                case InvokeDynamicInsnNode i -> {
                    text.append(':').append(i.name).append(i.desc).append(':').append(i.bsm.getOwner()).append('.').append(i.bsm.getName());
                    for (Object arg : i.bsmArgs) text.append(',').append(arg);
                }
                case InsnNode ignored -> { }
                default -> throw new IllegalStateException("unreviewed mob tail instruction " + insn.getClass());
            }
            text.append('\n');
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

}
