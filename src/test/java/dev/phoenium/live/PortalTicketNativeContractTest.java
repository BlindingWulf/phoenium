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

/** Pins the mapped 26.2 bytecode that PortalTicketEntityMixin and PortalTicketBoundary rely on. */
class PortalTicketNativeContractTest {
    @Test void nativeTicketIsOneNonBlockingChunkDemandRequest() throws Exception {
        MethodNode ticket = method("net/minecraft/world/entity/Entity", "placePortalTicket");
        assertEquals(List.of("level", "getChunkSource", "containing", "addTicketWithRadius"), calls(ticket),
            "the cut assumes placePortalTicket only registers PORTAL chunk demand");
    }

    @Test void sameDimensionTeleportMovesBeforeTheCallbackAndNeverRetiresTheSource() throws Exception {
        List<String> calls = calls(method("net/minecraft/world/entity/Entity", "teleportSameDimension"));
        int move = calls.indexOf("teleportSetPosition"), callback = calls.indexOf("onTransition");
        assertTrue(move >= 0 && callback > move, "position change precedes the post-teleport callback: " + calls);
        for (String forbidden : List.of("removeAfterChangingDimensions", "addDuringTeleport", "create", "restoreFrom"))
            assertFalse(calls.contains(forbidden), "same-dimension teleport must keep the native object: " + forbidden);
    }

    @Test void portalTicketConstantResolvesThroughTheNativeEntityMethod() throws Exception {
        assertTrue(calls(method("net/minecraft/world/level/portal/TeleportTransition", "placePortalTicket"))
            .contains("placePortalTicket"));
    }

    @Test void productionMixinCutsTheHeadOfTheExactNativeMethodAndIsRegistered() throws Exception {
        ClassNode mixin = node("dev/phoenium/mixin/PortalTicketEntityMixin");
        MethodNode cut = mixin.methods.stream().filter(m -> m.name.equals("phoenium$ownerCorrectPortalTicket"))
            .findFirst().orElseThrow();
        List<AnnotationNode> annotations = new ArrayList<>();
        if (cut.visibleAnnotations != null) annotations.addAll(cut.visibleAnnotations);
        if (cut.invisibleAnnotations != null) annotations.addAll(cut.invisibleAnnotations);
        AnnotationNode inject = annotations.stream()
            .filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        List<Object> values = inject.values;
        assertEquals("method", values.get(0));
        assertEquals(List.of("placePortalTicket(Lnet/minecraft/core/BlockPos;)V"), values.get(1));
        assertTrue(values.contains("cancellable") && values.get(values.indexOf("cancellable") + 1).equals(true));
        Object atValue = values.get(values.indexOf("at") + 1);
        AnnotationNode at = atValue instanceof AnnotationNode single ? single
            : (AnnotationNode) ((List<?>) atValue).getFirst();
        assertEquals("value", at.values.get(0));
        assertEquals("HEAD", at.values.get(1));
        assertEquals(List.of("deferForeignPortalTicket"), calls(cut).stream()
            .filter("deferForeignPortalTicket"::equals).toList());
        String config;
        try (InputStream stream = getClass().getResourceAsStream("/phoenium.mixins.json")) {
            config = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(config.contains("\"PortalTicketEntityMixin\""));
    }

    private static List<String> calls(MethodNode method) {
        List<String> names = new ArrayList<>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call) names.add(call.name);
        return names;
    }

    private static MethodNode method(String owner, String name) throws Exception {
        var methods = node(owner).methods.stream().filter(m -> m.name.equals(name)).toList();
        assertEquals(1, methods.size(), owner + "." + name);
        return methods.getFirst();
    }

    private static ClassNode node(String owner) throws Exception {
        try (InputStream stream = PortalTicketNativeContractTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(stream, owner);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
