package dev.phoenium;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Native-name/handler-seam proof for the three distinct block/item packet handlers. */
final class BlockPacketMixinBindingTest {
    private static final String LISTENER = "net/minecraft/server/network/ServerGamePacketListenerImpl";
    private static final String ACTION = "net/minecraft/network/protocol/game/ServerboundPlayerActionPacket";
    private static final String USE_ITEM_ON = "net/minecraft/network/protocol/game/ServerboundUseItemOnPacket";
    private static final String USE_ITEM = "net/minecraft/network/protocol/game/ServerboundUseItemPacket";

    @Test
    void nativeHandlersExposeOneStablePreMutationAnchorEach() throws Exception {
        ClassNode listener = classNode(LISTENER + ".class");
        assertEquals(1, countInvoke(listener, "handlePlayerAction", "getPos",
            ACTION, "()Lnet/minecraft/core/BlockPos;"));
        assertEquals(1, countInvoke(listener, "handleUseItemOn", "getSequence", USE_ITEM_ON, "()I"));
        assertEquals(1, countInvoke(listener, "handleUseItem", "getSequence", USE_ITEM, "()I"));
        assertEquals(1, countInvoke(listener, "handleUseItem", "useItem",
            "net/minecraft/server/level/ServerPlayerGameMode",
            "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"));
    }

    @Test
    void invokerBindsBothNativeHandlerDeclarations() throws Exception {
        ClassNode invoker = classNode("dev/phoenium/mixin/ServerGamePacketListenerInvoker.class");
        assertNotNull(method(invoker, "phoenium$invokePlayerAction"));
        assertNotNull(method(invoker, "phoenium$invokeUseItemOn"));
        assertNotNull(method(invoker, "phoenium$invokeUseItem"));
        assertEquals(List.of(Type.getType("Lnet/minecraft/server/network/ServerGamePacketListenerImpl;")),
            annotationValue(annotation(invoker, "Lorg/spongepowered/asm/mixin/Mixin;"), "value"));
    }

    @Test
    void useItemRouteCapturesOnceAndHasOneReplayEscape() throws Exception {
        ClassNode mixin = classNode("dev/phoenium/mixin/ServerGamePacketListenerMixin.class");
        MethodNode route = method(mixin, "phoenium$routeUseItem");
        assertNotNull(route);
        assertEquals(1, countInvoke(route, "matchesUseItemReplay"));
        assertEquals(1, countInvoke(route, "admitUseItemPacket"));
        assertEquals(1, countInvoke(route, "cancel"));
        assertEquals(0, countInvoke(route, "serial"),
            "packet routing must not add a global serial authority wrapper");

        ClassNode runtime = classNode("dev/phoenium/live/PhoeniumLiveRuntime.class");
        assertEquals(1, countInvoke(method(runtime, "admitUseItemPacket"), "admitPacket"),
            "UseItem must enter the existing owner gateway exactly once");
        ClassNode retained = classNode("dev/phoenium/live/PhoeniumLiveRuntime$PendingUseItem.class");
        assertEquals(1, countInvoke(method(retained, "execute"), "phoenium$invokeUseItem"),
            "one retained packet must invoke the native handler exactly once");
    }

    private static int countInvoke(ClassNode owner, String methodName, String invokedName,
                                   String invokedOwner, String descriptorSuffix) {
        MethodNode method = owner.methods.stream().filter(candidate -> candidate.name.equals(methodName)).findFirst().orElseThrow();
        int count = 0;
        for (var node : method.instructions) if (node instanceof MethodInsnNode invoke
                && invoke.owner.equals(invokedOwner) && invoke.name.equals(invokedName)
                && invoke.desc.equals(descriptorSuffix)) count++;
        return count;
    }

    private static int countInvoke(MethodNode method, String invokedName) {
        int count = 0;
        for (var node : method.instructions)
            if (node instanceof MethodInsnNode invoke && invoke.name.equals(invokedName)) count++;
        return count;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(candidate -> candidate.name.equals(name)).findFirst().orElse(null);
    }

    private static ClassNode classNode(String resource) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = BlockPacketMixinBindingTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static AnnotationNode annotation(ClassNode node, String descriptor) {
        var annotations = new java.util.ArrayList<AnnotationNode>();
        if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
        return annotations.stream().filter(candidate -> candidate.desc.equals(descriptor)).findFirst().orElse(null);
    }

    private static Object annotationValue(AnnotationNode annotation, String name) {
        assertNotNull(annotation);
        assertNotNull(annotation.values);
        for (int index = 0; index < annotation.values.size(); index += 2)
            if (annotation.values.get(index).equals(name)) return annotation.values.get(index + 1);
        fail("missing annotation value " + name);
        return null;
    }
}
