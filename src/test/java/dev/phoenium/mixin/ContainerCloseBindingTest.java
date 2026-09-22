package dev.phoenium.mixin;

import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.junit.jupiter.api.Assertions.*;

/** Pins the test adapter to the real production injector and mapped native entry/order. */
class ContainerCloseBindingTest {
    @Test void packetCutIsAfterThreadCheckAndBeforeTheOnlyNativeEffect() throws Exception {
        var calls=calls(method(read(ServerGamePacketListenerImpl.class),"handleContainerClose"));
        assertEquals(List.of("level","ensureRunningOnSameThread","doCloseContainer"),calls.stream().map(c->c.name).toList());
        var inject=ServerGamePacketListenerMixin.class.getDeclaredMethod("phoenium$routeContainerClose",ServerboundContainerClosePacket.class,CallbackInfo.class).getAnnotation(Inject.class);
        assertArrayEquals(new String[]{"handleContainerClose(Lnet/minecraft/network/protocol/game/ServerboundContainerClosePacket;)V"},inject.method());
        assertEquals(1,inject.require());assertEquals(1,inject.expect());assertTrue(inject.cancellable());
        assertEquals("Lnet/minecraft/server/level/ServerPlayer;doCloseContainer()V",inject.at()[0].target());
        assertEquals("INVOKE",inject.at()[0].value());
        assertEquals("handleContainerClose",ServerGamePacketListenerInvoker.class.getMethod("phoenium$invokeContainerClose",ServerboundContainerClosePacket.class)
            .getAnnotation(org.spongepowered.asm.mixin.gen.Invoker.class).value());
        var route=calls(method(read(ServerGamePacketListenerMixin.class),"phoenium$routeContainerClose"));
        assertTrue(route.stream().anyMatch(c->c.name.equals("matchesContainerCloseReplay")));
        assertTrue(route.stream().anyMatch(c->c.name.equals("admitContainerClosePacket")));
    }
    @Test void packetDrainPrecedesExistingSerialTickAndNativePrefixRemainsWhole() throws Exception {
        var calls=calls(method(read(MinecraftServer.class),"processPacketsAndTick")).stream().map(c->c.name).toList();
        assertTrue(calls.indexOf("processQueuedPackets")<calls.indexOf("tickServer"));
        assertEquals(List.of("removed","transferState"),calls(method(read(ServerPlayer.class),"doCloseContainer")).stream().map(c->c.name).toList());
        var inject=ContainerCloseLifecycleMixin.class.getDeclaredMethod("phoenium$nativeCloseStarting",CallbackInfo.class).getAnnotation(Inject.class);
        assertArrayEquals(new String[]{"doCloseContainer()V"},inject.method());assertEquals("HEAD",inject.at()[0].value());assertFalse(inject.cancellable());
    }
    @Test void sharedNativeCallersKeepTheirCloseChain() throws Exception {
        var player=read(ServerPlayer.class);
        for(String name:List.of("openMenu","openHorseInventory","openNautilusInventory","tick","doTick"))
            assertTrue(calls(method(player,name)).stream().anyMatch(c->c.name.equals("closeContainer")),name);
        var remove=calls(method(read(net.minecraft.world.entity.player.Player.class),"remove")).stream().map(c->c.name).toList();
        assertTrue(remove.indexOf("remove")<remove.indexOf("doCloseContainer"));assertTrue(remove.contains("removed"));
    }
    private static ClassNode read(Class<?> type) throws Exception {
        try(var stream=type.getResourceAsStream(type.getSimpleName()+".class")) {
            assertNotNull(stream);var node=new ClassNode();new ClassReader(stream).accept(node,0);return node;
        }
    }
    private static MethodNode method(ClassNode node,String name) {return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static List<MethodInsnNode> calls(MethodNode method) {return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();}
}
