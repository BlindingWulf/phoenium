package dev.phoenium.acceptance;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import dev.phoenium.chunk.FullFinishingServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import dev.phoenium.diagnostics.FullSpawnDiagnosticState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs the actual candidate scheduling path which produces PrepareSpawnTask.spawnPosition. */
@Mixin(PlayerSpawnFinder.class)
abstract class FullRuntimeAcceptancePlayerSpawnFinderMixin {
    @Shadow private ServerLevel level;
    @Shadow private BlockPos spawnSuggestion;
    @Shadow private int radius;
    @Shadow private int nextCandidateIndex;
    @Shadow private CompletableFuture<Vec3> finishedFuture;
    @Unique private int phoenium$candidates;

    @Inject(method = "scheduleCandidate", at = @At("HEAD"))
    private void phoenium$traceCandidate(int x, int z, int candidate,
                                          Supplier<Optional<Vec3>> supplier, CallbackInfo callback) {
        int sequence = ++phoenium$candidates;
        if (sequence > 24 && sequence % 100 != 0) return;
        System.out.println("PHOENIUM_FULL_SPAWN_CANDIDATE sequence=" + sequence
                + " candidate=" + candidate + " next=" + nextCandidateIndex
                + " origin=" + spawnSuggestion + " radius=" + radius
                + " block=" + x + "," + z + " level=" + level.dimension());
    }

    @ModifyArg(method = "scheduleCandidate", at = @At(value = "INVOKE", target =
            "Ljava/util/concurrent/CompletableFuture;whenCompleteAsync(Ljava/util/function/BiConsumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"), index = 1)
    private Executor phoenium$traceCallbackExecutor(Executor executor) {
        return command -> {
            System.out.println("PHOENIUM_FULL_SPAWN_CALLBACK_SUBMIT thread=" + Thread.currentThread().getName()
                    + " finishing=" + FullFinishingServices.finishing()
                    + " ownership=" + ownershipState());
            try {
                executor.execute(command);
            } catch (Throwable failure) {
                System.out.println("PHOENIUM_FULL_SPAWN_CALLBACK_EXECUTOR_FAILURE thread="
                        + Thread.currentThread().getName() + " finishing=" + FullFinishingServices.finishing()
                        + " ownership=" + ownershipState() + " failure=" + failureState(failure));
                throw failure;
            }
        };
    }

    @Inject(method = "lambda$scheduleCandidate$0(Ljava/util/function/Supplier;IIILjava/lang/Object;Ljava/lang/Throwable;)V", at = @At("HEAD"))
    private void phoenium$spawnCallbackEnter(Supplier<Optional<Vec3>> supplier, int x, int z, int candidate,
                                               Object value, Throwable failure, CallbackInfo callback) {
        if (!FullSpawnDiagnosticState.isTarget(new ChunkPos(x >> 4, z >> 4))) return;
        System.out.println("PHOENIUM_FULL_SPAWN_CALLBACK_ENTER x=" + x + " z=" + z
                + " candidate=" + candidate + " value=" + valueState(value)
                + " failure=" + failureState(failure)
                + " finished=" + futureState(finishedFuture)
                + " target=" + FullSpawnDiagnosticState.summary());
    }

    @Inject(method = "lambda$scheduleCandidate$0(Ljava/util/function/Supplier;IIILjava/lang/Object;Ljava/lang/Throwable;)V", at = @At("RETURN"))
    private void phoenium$spawnCallbackExit(Supplier<Optional<Vec3>> supplier, int x, int z, int candidate,
                                              Object value, Throwable failure, CallbackInfo callback) {
        if (!FullSpawnDiagnosticState.isTarget(new ChunkPos(x >> 4, z >> 4))) return;
        System.out.println("PHOENIUM_FULL_SPAWN_CALLBACK_EXIT x=" + x + " z=" + z
                + " candidate=" + candidate + " finished=" + futureState(finishedFuture)
                + " target=" + FullSpawnDiagnosticState.summary());
    }

    @Unique
    private static String futureState(CompletableFuture<?> future) {
        if (future == null) return "null";
        if (!future.isDone()) return "pending";
        return future.isCompletedExceptionally() ? "exceptional" : "done";
    }

    @Unique
    private static String valueState(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    @Unique
    private static String failureState(Throwable failure) {
        return failure == null ? "none" : failure.getClass().getName() + ":" + failure.getMessage();
    }

    @Unique
    private static String ownershipState() {
        return dev.phoenium.ownership.OwnershipContext.current()
                .map(active -> "present:" + active)
                .orElse("none");
    }
}
