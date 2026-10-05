package dev.phoenium.live;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/** Binding contract of the reviewed mob outer tails against the pinned mapped 26.2 bytecode. */
class MobPortalOuterTailsTest {
    @Test void mixinTargetsAndPinnedOwnersAreIdentical() throws Exception {
        ClassNode mixin = node("dev/phoenium/mixin/MobPortalOuterTailMixin");
        var annotations = new ArrayList<AnnotationNode>();
        if (mixin.visibleAnnotations != null) annotations.addAll(mixin.visibleAnnotations);
        if (mixin.invisibleAnnotations != null) annotations.addAll(mixin.invisibleAnnotations);
        AnnotationNode declared = annotations.stream().filter(a -> a.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
        var targets = new TreeSet<String>();
        for (int i = 0; i < declared.values.size(); i += 2)
            if (declared.values.get(i).equals("targets"))
                for (Object target : (List<?>) declared.values.get(i + 1)) targets.add(((String) target).replace('.', '/'));
        assertEquals(new TreeSet<>(MobPortalOuterTails.targets()), targets,
            "the mixin target list must mirror mob-portal-tail-pins.txt exactly");
        try (InputStream stream = getClass().getResourceAsStream("/phoenium.mixins.json")) {
            assertTrue(new String(stream.readAllBytes()).contains("\"MobPortalOuterTailMixin\""));
        }
    }

    @Test void everyReviewedTailBindsAgainstTheMappedNativeBytecode() throws Exception {
        int installed = 0;
        for (var owner : MobPortalOuterTails.pins().entrySet()) {
            ClassNode target = node(owner.getKey());
            MobPortalOuterTails.install(target);
            for (var pin : owner.getValue()) {
                boolean resume = target.methods.stream().anyMatch(m -> m.name.equals(
                    MobPortalOuterTails.resumeName(owner.getKey(), pin.method())));
                assertEquals(!pin.emptyTail(), resume, owner.getKey() + "." + pin.method());
                if (!pin.emptyTail()) {
                    MethodNode lifted = target.methods.stream().filter(m -> m.name.equals(
                        MobPortalOuterTails.resumeName(owner.getKey(), pin.method()))).findFirst().orElseThrow();
                    assertEquals(pin.capture() == MobPortalOuterTails.Capture.AIR_SUPPLY ? "(I)V" : "()V", lifted.desc);
                    installed++;
                }
            }
        }
        assertTrue(installed >= 25, "unexpectedly few lifted tails: " + installed);
    }

    @Test void guardIsInsertedImmediatelyAfterTheSingleSuperCall() throws Exception {
        ClassNode wolf = node("net/minecraft/world/entity/animal/wolf/Wolf");
        MobPortalOuterTails.install(wolf);
        MethodNode tick = wolf.methods.stream().filter(m -> m.name.equals("tick") && m.desc.equals("()V")).findFirst().orElseThrow();
        var calls = new ArrayList<String>();
        for (var i : tick.instructions) if (i instanceof MethodInsnNode call) calls.add(call.getOpcode() + ":" + call.owner + "." + call.name);
        int cut = calls.indexOf(Opcodes.INVOKESPECIAL + ":" + wolf.superName + ".tick");
        assertTrue(cut >= 0);
        assertEquals(Opcodes.INVOKESTATIC + ":dev/phoenium/live/MobPortalGuard.suspended", calls.get(cut + 1));
        assertEquals(1, calls.stream().filter(c -> c.endsWith("MobPortalGuard.suspended")).count());
    }

    @Test void aChangedNativeTailFailsBindingInsteadOfDiverging() throws Exception {
        ClassNode wolf = node("net/minecraft/world/entity/animal/wolf/Wolf");
        MethodNode tick = wolf.methods.stream().filter(m -> m.name.equals("tick") && m.desc.equals("()V")).findFirst().orElseThrow();
        var cut = (MethodInsnNode) java.util.stream.StreamSupport.stream(tick.instructions.spliterator(), false)
            .filter(i -> i instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKESPECIAL && c.name.equals("tick")).findFirst().orElseThrow();
        tick.instructions.insert(cut, new InsnNode(Opcodes.NOP));
        var failure = assertThrows(IllegalStateException.class, () -> MobPortalOuterTails.install(wolf));
        assertTrue(failure.getMessage().contains("mob tail changed"), failure.getMessage());
    }

    @Test void unreviewedShapesAreRejected() {
        assertThrows(IllegalStateException.class, () -> MobPortalOuterTails.lift(synthetic(m -> {
            call(m, "tick"); call(m, "tick");
            m.instructions.add(new InsnNode(Opcodes.RETURN));
        }), "tick"), "two super calls");
        assertThrows(IllegalStateException.class, () -> MobPortalOuterTails.lift(synthetic(m -> {
            LabelNode a = new LabelNode(), b = new LabelNode(), h = new LabelNode();
            m.instructions.add(a); call(m, "tick"); m.instructions.add(b); m.instructions.add(new InsnNode(Opcodes.RETURN));
            m.instructions.add(h); m.instructions.add(new InsnNode(Opcodes.RETURN));
            m.tryCatchBlocks.add(new TryCatchBlockNode(a, b, h, null));
        }), "tick"), "handler");
        assertThrows(IllegalStateException.class, () -> MobPortalOuterTails.lift(synthetic(m -> {
            LabelNode inside = new LabelNode();
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new JumpInsnNode(Opcodes.IFNULL, inside));   // prefix jumps into the tail
            call(m, "tick"); m.instructions.add(inside); m.instructions.add(new InsnNode(Opcodes.RETURN));
        }), "tick"), "branch crossing the cut");
        assertThrows(IllegalStateException.class, () -> MobPortalOuterTails.lift(synthetic(m -> {
            m.instructions.add(new InsnNode(Opcodes.ICONST_1)); m.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2));
            call(m, "tick");
            m.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2)); m.instructions.add(new InsnNode(Opcodes.POP));
            m.instructions.add(new InsnNode(Opcodes.RETURN));
        }), "tick"), "an unreviewed live caller local");
        // The reviewed shape is accepted: nothing after the call.
        var ok = MobPortalOuterTails.lift(synthetic(m -> { call(m, "tick"); m.instructions.add(new InsnNode(Opcodes.RETURN)); }), "tick");
        assertTrue(ok.emptyTail());
        assertEquals(MobPortalOuterTails.Capture.NONE, ok.capture());
    }

    @Test void theFourWaterAnimalBaseTickPinsCaptureOnlyTheAirSupplyLocal() {
        var airSupply = new TreeSet<String>();
        for (var pins : MobPortalOuterTails.pins().values())
            for (var pin : pins) if (pin.capture() == MobPortalOuterTails.Capture.AIR_SUPPLY) {
                assertEquals("baseTick", pin.method());
                airSupply.add(pin.owner().substring(pin.owner().lastIndexOf('/') + 1));
            }
        assertEquals(new TreeSet<>(List.of("AgeableWaterCreature", "Axolotl", "Nautilus", "WaterAnimal")), airSupply);
    }

    private interface Body { void build(MethodNode method); }

    private static ClassNode synthetic(Body body) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21; node.name = "test/Child"; node.superName = "test/Parent";
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.build(method);
        method.maxLocals = 4; method.maxStack = 4;
        node.methods.add(method);
        return node;
    }

    private static void call(MethodNode method, String name) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "test/Parent", name, "()V", false));
    }

    private static ClassNode node(String owner) throws Exception {
        try (InputStream stream = MobPortalOuterTailsTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(stream, owner);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
