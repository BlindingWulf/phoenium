package dev.phoenium.testmixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.ChunkBindingRuntime;
import dev.phoenium.chunk.FullRegistrationFixtureSaveBridge;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.live.PhoeniumLiveRuntime;
import dev.phoenium.save.RegionalSaveBinding;
import dev.phoenium.save.RegionalSaveCoordinator;
import dev.phoenium.topology.RoutingKey;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Test artifact only: adapts one exact controlled world to its own real regional save service. */
@Mixin(value = RegionalSaveBinding.class, remap = false)
abstract class FullRegistrationSaveBindingMixin {
    @WrapMethod(method = "unload")
    private static void phoenium$controlledUnload(RegionalSaveCoordinator coordinator, ChunkMap map,
            ServerLevel level, ChunkHolder holder, CompletableFuture<?> expectedSave, long position,
            Runnable body, Operation<Void> original) {
        var binding = FullRegistrationFixtureSaveBridge.find(level, map);
        if (binding == null) {
            original.call(coordinator, map, level, holder, expectedSave, position, body);
            return;
        }
        var finishing = FullFinishingServices.forServer(level.getServer());
        binding.finishing(finishing);
        finishing.registerWorld(level);
        try (var turn = finishing.serial(FullFinishingServices.Phase.SERVER_TASK);
             var scope = FullRegistrationFixtureSaveBridge.enter(binding)) {
            original.call(coordinator, map, level, holder, expectedSave, position, body);
        }
    }

    @WrapMethod(method = "save")
    private static boolean phoenium$controlledSave(RegionalSaveCoordinator coordinator, ChunkMap map,
            ServerLevel level, ChunkAccess chunk, Supplier<Boolean> body, Operation<Boolean> original) {
        var binding = FullRegistrationFixtureSaveBridge.currentOrNull();
        if (binding == null) return original.call(coordinator, map, level, chunk, body);
        if (binding.level() != level || binding.map() != map)
            throw new IllegalStateException("controlled save escaped its exact world or map");
        return original.call(binding.coordinator(), map, level, chunk, body);
    }

    @WrapOperation(method = "unload", at = @At(value = "INVOKE",
            target = "Ldev/phoenium/live/PhoeniumLiveRuntime;requireChunkWorld(Lnet/minecraft/server/level/ServerLevel;)Ldev/phoenium/chunk/ChunkBindingRuntime$WorldAuthority;"),
            require = 1, expect = 1)
    private static ChunkBindingRuntime.WorldAuthority phoenium$controlledAuthority(
            PhoeniumLiveRuntime runtime, ServerLevel level,
            Operation<ChunkBindingRuntime.WorldAuthority> original) {
        var binding = FullRegistrationFixtureSaveBridge.currentOrNull();
        return binding == null ? original.call(runtime, level)
                : FullRegistrationFixtureSaveBridge.current(level).authority();
    }

    @WrapOperation(method = "unload", at = @At(value = "INVOKE",
            target = "Ldev/phoenium/save/RegionalSaveBinding;key(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)Ldev/phoenium/topology/RoutingKey;"),
            require = 1, expect = 1)
    private static RoutingKey phoenium$controlledUnloadKey(ServerLevel level, ChunkPos position,
                                                            Operation<RoutingKey> original) {
        var binding = FullRegistrationFixtureSaveBridge.currentOrNull();
        return binding == null ? original.call(level, position)
                : FullRegistrationFixtureSaveBridge.current(level).key(position);
    }

    @WrapOperation(method = "unload", at = @At(value = "INVOKE",
            target = "Ldev/phoenium/save/RegionalSaveCoordinator;prepareSource(Ldev/phoenium/topology/RoutingKey;Ljava/util/function/Supplier;)Ljava/lang/Object;"),
            require = 1, expect = 1)
    private static Object phoenium$controlledPrepare(RegionalSaveCoordinator coordinator, RoutingKey key,
                                                      Supplier<?> source, Operation<Object> original) {
        var binding = FullRegistrationFixtureSaveBridge.currentOrNull();
        return binding == null ? original.call(coordinator, key, source)
                : binding.coordinator().prepareSource(key, source::get);
    }

    @WrapOperation(method = "save", at = @At(value = "INVOKE",
            target = "Ldev/phoenium/save/RegionalSaveBinding;key(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)Ldev/phoenium/topology/RoutingKey;"),
            require = 1, expect = 1)
    private static RoutingKey phoenium$controlledSaveKey(ServerLevel level, ChunkPos position,
                                                          Operation<RoutingKey> original) {
        var binding = FullRegistrationFixtureSaveBridge.currentOrNull();
        return binding == null ? original.call(level, position)
                : FullRegistrationFixtureSaveBridge.current(level).key(position);
    }

    @WrapOperation(method = "mutateUnload", at = @At(value = "INVOKE",
            target = "Ldev/phoenium/save/RegionalSaveBinding;key(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)Ldev/phoenium/topology/RoutingKey;"),
            require = 1, expect = 1)
    private static RoutingKey phoenium$controlledMutationKey(ServerLevel level, ChunkPos position,
                                                              Operation<RoutingKey> original) {
        var binding = FullRegistrationFixtureSaveBridge.currentOrNull();
        return binding == null ? original.call(level, position)
                : FullRegistrationFixtureSaveBridge.current(level).key(position);
    }

    @WrapOperation(method = "mutateUnload", at = @At(value = "INVOKE",
            target = "Ldev/phoenium/save/RegionalSaveCoordinator;unloadSource(Ldev/phoenium/topology/RoutingKey;Ljava/lang/Runnable;)V"),
            require = 1, expect = 1)
    private static void phoenium$controlledMutation(RegionalSaveCoordinator coordinator, RoutingKey key,
                                                     Runnable body, Operation<Void> original) {
        var binding = FullRegistrationFixtureSaveBridge.currentOrNull();
        if (binding == null) original.call(coordinator, key, body);
        else binding.coordinator().unloadSource(key, body);
    }
}
