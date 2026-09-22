package dev.phoenium.live;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class PlayerDimensionNativeContinuationTest {
    @Test void playerPortalBaseTickBelongsToNetworkDoTickNotRegionalServerPlayerTick() throws Exception {
        List<String> regional=calls(method(net.minecraft.server.level.ServerPlayer.class,"tick"));
        assertFalse(regional.contains("net/minecraft/world/entity/player/Player.tick()V"));
        assertTrue(calls(method(net.minecraft.server.level.ServerPlayer.class,"doTick"))
            .contains("net/minecraft/world/entity/player/Player.tick()V"));
        assertTrue(calls(method(net.minecraft.server.network.ServerGamePacketListenerImpl.class,"tick"))
            .contains("net/minecraft/server/network/ServerGamePacketListenerImpl.tickPlayer()Z"));
        assertTrue(calls(method(net.minecraft.server.network.ServerGamePacketListenerImpl.class,"tickPlayer"))
            .contains("net/minecraft/server/level/ServerPlayer.doTick()V"));
    }
    @Test void retainedPlayerSuffixPreservesPinnedVanillaCallbackOrder() throws Exception {
        List<String> calls = calls(method(Class.forName("dev.phoenium.mixin.ServerPlayerLifecycleMixin"),
            "phoenium$resumePlayerDimensionChange"));
        assertOrdered(calls,
            "dev/phoenium/mixin/PlayerDimensionEntityAccess.phoenium$invokeUnsetRemoved()V",
            "net/minecraft/server/level/ServerPlayer.setServerLevel(Lnet/minecraft/server/level/ServerLevel;)V",
            "net/minecraft/server/network/ServerGamePacketListenerImpl.teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V",
            "net/minecraft/server/level/ServerLevel.addDuringTeleport(Lnet/minecraft/world/entity/Entity;)V",
            "dev/phoenium/mixin/ServerPlayerLifecycleMixin.triggerDimensionChangeTriggers(Lnet/minecraft/server/level/ServerLevel;)V",
            "net/minecraft/world/level/portal/TeleportTransition$PostTeleportTransition.onTransition(Lnet/minecraft/world/entity/Entity;)V",
            "dev/phoenium/mixin/PlayerDimensionEntityAccess.phoenium$invokeTeleportSpectators(Lnet/minecraft/world/level/portal/TeleportTransition;Lnet/minecraft/server/level/ServerLevel;)V");
    }

    @Test void sourceCutUsesRuntimeHandoffInsteadOfCallingDestinationSuffixInline() throws Exception {
        List<String> calls = calls(method(Class.forName("dev.phoenium.mixin.ServerPlayerLifecycleMixin"),
            "phoenium$suspendAfterSourceRetirement"));
        assertTrue(calls.contains(
            "dev/phoenium/live/PhoeniumLiveRuntime.suspendPlayerDimensionTransition(Lnet/minecraft/server/level/ServerPlayer;)Z"));
        assertFalse(calls.stream().anyMatch(call -> call.contains("addDuringTeleport")));
    }

    @Test void productionRegistersBothPlayerAndPearlContinuationMixins() throws Exception {
        String config;
        try (InputStream input = getClass().getResourceAsStream("/phoenium.mixins.json")) {
            assertNotNull(input);
            config = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String name : List.of("ServerPlayerLifecycleMixin", "PlayerDimensionEntityAccess",
                "PearlPortalThrownEnderpearlAccess",
                "PearlPortalThrownEnderpearlMixin")) assertTrue(config.contains('"' + name + '"'), name);
    }

    private static MethodNode method(Class<?> type, String name) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertNotNull(input, type.getName());
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        List<MethodNode> matches = node.methods.stream().filter(method -> method.name.equals(name)).toList();
        assertEquals(1, matches.size(), type.getName() + '.' + name);
        return matches.getFirst();
    }

    private static List<String> calls(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call)
                result.add(call.owner + '.' + call.name + call.desc);
        return result;
    }

    private static void assertOrdered(List<String> calls, String... expected) {
        int cursor = -1;
        for (String call : expected) {
            int found = calls.subList(cursor + 1, calls.size()).indexOf(call);
            assertTrue(found >= 0, "missing ordered call " + call + " in " + calls);
            cursor += found + 1;
        }
    }
}
