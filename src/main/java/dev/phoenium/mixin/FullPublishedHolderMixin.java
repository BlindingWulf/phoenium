package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.chunk.FullPublishedInputs;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GenerationChunkHolder.class)
abstract class FullPublishedHolderMixin {
    @Unique private volatile FullPublishedInputs.Entry phoenium$publication;
    @Unique private volatile dev.phoenium.chunk.ChunkHolderLifetime phoenium$lifetime;
    public dev.phoenium.chunk.ChunkHolderLifetime phoenium$lifetime() { return phoenium$lifetime; }
    public void phoenium$lifetime(dev.phoenium.chunk.ChunkHolderLifetime value) { phoenium$lifetime = value; }
    public FullPublishedInputs.Entry phoenium$publication() { return phoenium$publication; }
    public void phoenium$publication(FullPublishedInputs.Entry entry) { phoenium$publication = entry; }
    @WrapOperation(method = "completeFuture", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;complete(Ljava/lang/Object;)Z"), require = 1, expect = 1)
    private boolean phoenium$complete(CompletableFuture<ChunkResult<ChunkAccess>> slot, Object value,
            Operation<Boolean> original, @Local(argsOnly = true) ChunkStatus status) {
        @SuppressWarnings("unchecked") var result = (ChunkResult<ChunkAccess>) value;
        var holder = (GenerationChunkHolder) (Object) this;
        FullPublishedInputs.proposing(holder, status, slot, result);
        boolean won = original.call(slot, value);
        FullPublishedInputs.completed(holder, status, won);
        return won;
    }
    @WrapOperation(method = "completeFuture", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/atomic/AtomicReferenceArray;compareAndSet(ILjava/lang/Object;Ljava/lang/Object;)Z"), require = 1, expect = 1)
    private boolean phoenium$install(AtomicReferenceArray<?> slots, int index, Object previous, Object value,
            Operation<Boolean> original, @Local(argsOnly = true) ChunkStatus status) {
        @SuppressWarnings("unchecked") var slot = (CompletableFuture<ChunkResult<ChunkAccess>>) value;
        var holder = (GenerationChunkHolder) (Object) this;
        FullPublishedInputs.proposing(holder, status, slot, slot.getNow(null));
        boolean won = original.call(slots, index, previous, value);
        FullPublishedInputs.completed(holder, status, won);
        return won;
    }
    @Inject(method = "replaceProtoChunk", at = @At("HEAD"), require = 1, expect = 1)
    private void phoenium$replacing(CallbackInfo ci) { FullPublishedInputs.replacing((GenerationChunkHolder) (Object) this); }
    @Inject(method = "updateHighestAllowedStatus", at = @At("RETURN"), require = 1, expect = 1)
    private void phoenium$eligibility(CallbackInfo ci) { FullPublishedInputs.eligibilityChanged((GenerationChunkHolder) (Object) this); }
    @Inject(method = "failAndClearPendingFuture", at = @At("HEAD"), require = 1, expect = 1)
    private void phoenium$invalidating(int index, CompletableFuture<ChunkResult<ChunkAccess>> future, CallbackInfo ci) {
        if (index == ChunkStatus.FULL.getIndex() && !future.isDone()) FullPublishedInputs.invalidating((GenerationChunkHolder) (Object) this, future);
    }
}
