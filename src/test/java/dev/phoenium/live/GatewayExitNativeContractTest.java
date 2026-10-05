package dev.phoenium.live;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

/** Pins the mapped 26.2 bytecode that EndGatewayBlockMixin and GatewayExitBoundary rely on. */
class GatewayExitNativeContractTest {
    private static final String GATEWAY = "net/minecraft/world/level/block/entity/TheEndGatewayBlockEntity";

    @Test void nativeExitLookupIsTheOnlyChunkReadingStepOfGetPortalDestination() throws Exception {
        MethodNode destination = method("net/minecraft/world/level/block/EndGatewayBlock", "getPortalDestination");
        var calls = new ArrayList<String>();
        for (var i : destination.instructions) if (i instanceof MethodInsnNode c) calls.add(c.owner + "." + c.name);
        assertEquals(1, calls.stream().filter((GATEWAY + ".getPortalPosition")::equals).count(),
            "the wrap assumes exactly one exit lookup: " + calls);
        assertFalse(calls.stream().anyMatch(c -> c.endsWith(".getChunk") || c.endsWith(".getBlockState")),
            "getPortalDestination itself must not read the world");
    }

    @Test void nativeArmingChecksTheSameConditionsTheGateReplicates() throws Exception {
        var calls = new ArrayList<String>();
        for (var i : method("net/minecraft/world/level/block/EndGatewayBlock", "entityInside").instructions)
            if (i instanceof MethodInsnNode c) calls.add(c.name);
        int usable = calls.indexOf("canUsePortal"), cooling = calls.indexOf("isCoolingDown"),
            arm = calls.indexOf("setAsInsidePortal"), cooldown = calls.indexOf("triggerCooldown");
        assertTrue(usable >= 0 && cooling > usable && arm > cooling && cooldown > arm,
            "pinned arming order canUsePortal -> isCoolingDown -> setAsInsidePortal -> triggerCooldown: " + calls);
    }

    @Test void exitSearchReadsChunksOnlyThroughTheLevel() throws Exception {
        ClassNode node = node(GATEWAY);
        assertTrue(node.fields.stream().anyMatch(f -> f.name.equals("exitPortal") && f.desc.equals("Lnet/minecraft/core/BlockPos;")));
        assertTrue(node.fields.stream().anyMatch(f -> f.name.equals("exactTeleport") && f.desc.equals("Z")));
        var calls = new ArrayList<String>();
        for (var i : method(GATEWAY, "findExitPosition").instructions) if (i instanceof MethodInsnNode c) calls.add(c.name);
        assertTrue(calls.contains("findTallestBlock"), calls.toString());
    }

    @Test void productionMixinsBindTheExactNativeSelectorsAndAreRegistered() throws Exception {
        ClassNode mixin = node("dev/phoenium/mixin/EndGatewayBlockMixin");
        var annotationsByMethod = new java.util.HashMap<String, List<AnnotationNode>>();
        for (MethodNode m : mixin.methods) {
            var all = new ArrayList<AnnotationNode>();
            if (m.visibleAnnotations != null) all.addAll(m.visibleAnnotations);
            if (m.invisibleAnnotations != null) all.addAll(m.invisibleAnnotations);
            annotationsByMethod.put(m.name, all);
        }
        AnnotationNode inject = annotationsByMethod.get("phoenium$deferGatewayArming").stream()
            .filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of("entityInside"), inject.values.get(inject.values.indexOf("method") + 1));
        assertTrue(inject.values.contains("cancellable") && inject.values.get(inject.values.indexOf("cancellable") + 1).equals(true));
        AnnotationNode wrap = annotationsByMethod.get("phoenium$preparedGatewayExit").stream()
            .filter(a -> a.desc.endsWith("/WrapOperation;")).findFirst().orElseThrow();
        assertEquals(List.of("getPortalDestination"), wrap.values.get(wrap.values.indexOf("method") + 1));
        Object at = wrap.values.get(wrap.values.indexOf("at") + 1);
        AnnotationNode select = at instanceof AnnotationNode single ? single : (AnnotationNode) ((List<?>) at).getFirst();
        assertTrue(select.values.contains(
            "Lnet/minecraft/world/level/block/entity/TheEndGatewayBlockEntity;getPortalPosition(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"));
        String config;
        try (InputStream stream = getClass().getResourceAsStream("/phoenium.mixins.json")) {
            config = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(config.contains("\"EndGatewayBlockMixin\"") && config.contains("\"TheEndGatewayExitAccess\""));
    }

    private static MethodNode method(String owner, String name) throws Exception {
        var methods = node(owner).methods.stream().filter(m -> m.name.equals(name)).toList();
        assertEquals(1, methods.size(), owner + "." + name);
        return methods.getFirst();
    }

    private static ClassNode node(String owner) throws Exception {
        try (InputStream stream = GatewayExitNativeContractTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(stream, owner);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
