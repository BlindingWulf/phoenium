package dev.phoenium.acceptance;

import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Acceptance-only diagnostic for preserving the first vanilla chunk-load failure chain. */
@Mixin(net.minecraft.server.level.ChunkMap.class)
abstract class FullRuntimeAcceptanceChunkFailureMixin {
    @Inject(method = "handleChunkLoadFailure(Ljava/lang/Throwable;Lnet/minecraft/world/level/ChunkPos;)"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"), require = 1)
    private void phoenium$failure(Throwable failure, ChunkPos position,
                                    CallbackInfoReturnable<ChunkAccess> callback) {
        if (!Boolean.getBoolean("phoenium.full.authoritative") || failure == null) return;
        StringBuilder chain = new StringBuilder();
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (!chain.isEmpty()) chain.append(" <- ");
            chain.append(current.getClass().getName()).append(":").append(current.getMessage());
        }
        System.out.println("PHOENIUM_FULL_LOAD_FAILURE chunk=" + position + " chain=" + chain);
    }
}
