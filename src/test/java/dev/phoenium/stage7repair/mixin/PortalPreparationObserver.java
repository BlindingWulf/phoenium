package dev.phoenium.stage7repair.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.live.CrossDimensionReplacementBoundary;
import dev.phoenium.live.NativePortalTransfer;
import dev.phoenium.live.PortalDestinationObservation;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.stage7repair.PortalLifecycleObserverBridge;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only snapshots at the product's owner-free native lookup boundary. */
@Mixin(value = PortalDestinationObservation.class, remap = false)
abstract class PortalPreparationObserver {
    @Inject(method = "prepare", at = @At("HEAD"), require = 1)
    private static void phoenium$lookupHead(Entity entity, ObjectOwnership binding,
            CrossDimensionReplacementBoundary.Token token, NativePortalTransfer.Authority authority,
            BooleanSupplier sourceCurrent, Supplier<TeleportTransition> nativeLookup,
            CallbackInfoReturnable<TeleportTransition> cir) {
        PortalLifecycleObserverBridge.preparation(entity, token, null, false);
    }

    @Inject(method = "prepare", at = @At("RETURN"), require = 1)
    private static void phoenium$lookupReturn(Entity entity, ObjectOwnership binding,
            CrossDimensionReplacementBoundary.Token token, NativePortalTransfer.Authority authority,
            BooleanSupplier sourceCurrent, Supplier<TeleportTransition> nativeLookup,
            CallbackInfoReturnable<TeleportTransition> cir) {
        PortalLifecycleObserverBridge.preparation(entity, token, cir.getReturnValue(), true);
    }

    @Inject(method = "query", at = @At("HEAD"), require = 1)
    private static void phoenium$query(ServerLevel level, int x, int z, ChunkStatus status,
            boolean create, CallbackInfo ci) {
        PortalLifecycleObserverBridge.destinationQuery(level, x, z, status, create,
            "DESTINATION_QUERY", null);
    }

    @Inject(method = "selected", at = @At("HEAD"), require = 1)
    private static void phoenium$selected(ServerLevel level, int x, int z, ChunkStatus status,
            CompletableFuture<?> future, CallbackInfo ci) {
        PortalLifecycleObserverBridge.destinationQuery(level, x, z, status, true,
            "DESTINATION_FUTURE_SELECTED", future.isDone());
    }

    @Inject(method = "result", at = @At("HEAD"), require = 1)
    private static void phoenium$result(ServerLevel level, int x, int z, ChunkStatus status,
            ChunkAccess result, CallbackInfo ci) {
        PortalLifecycleObserverBridge.destinationQuery(level, x, z, status, true,
            result == null ? "DESTINATION_QUERY_NULL" : "DESTINATION_QUERY_RESULT", true);
    }

    @Inject(method = "endPlatform", at = @At("HEAD"), require = 1)
    private static void phoenium$platformHead(Entity entity, ServerLevel level, BlockPos origin,
            Runnable nativePlatform, CallbackInfo ci) {
        PortalLifecycleObserverBridge.platform(entity, level, false);
    }

    @Inject(method = "endPlatform", at = @At("RETURN"), require = 1)
    private static void phoenium$platformReturn(Entity entity, ServerLevel level, BlockPos origin,
            Runnable nativePlatform, CallbackInfo ci) {
        PortalLifecycleObserverBridge.platform(entity, level, true);
    }

    @WrapOperation(method = "endPlatform", at = @At(value = "INVOKE", target =
        "Ldev/phoenium/live/NativePortalTransfer$Authority;withDestinationPlatform(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/util/function/BooleanSupplier;Ljava/lang/Runnable;)V"),
        require = 1, allow = 1)
    private static void phoenium$nativePlatformScope(NativePortalTransfer.Authority authority,
            ServerLevel level, BlockPos origin, BooleanSupplier sourceCurrent,
            Runnable nativePlatform, Operation<Void> original) {
        original.call(authority, level, origin, sourceCurrent,
            (Runnable) () -> PortalLifecycleObserverBridge.nativeEndPlatform(level, nativePlatform));
    }
}
