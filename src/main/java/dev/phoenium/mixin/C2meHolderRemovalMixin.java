package dev.phoenium.mixin;

import dev.phoenium.chunk.ChunkHolderLifetime;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** C2ME never runs vanilla's pendingUnloads removal; its flowsched item removal is the holder's final unload. */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.TheChunkSystem", remap = false)
abstract class C2meHolderRemovalMixin {
    @Shadow @Final private ChunkMap tacs;

    @Inject(method = "onItemRemoval", at = @At("RETURN"), require = 1, expect = 1)
    private void phoenium$providerHolderRemoved(@Coerce Object item, CallbackInfo ci) {
        if (vanillaHolder(item) instanceof GenerationChunkHolder holder)
            ChunkHolderLifetime.providerHolderRemoved(holder, ((ChunkMapLoadAccess) tacs).phoenium$mainExecutor()::schedule);
    }

    private static Object vanillaHolder(Object item) {
        try { return ((AtomicReference<?>) item.getClass().getMethod("getUserData").invoke(item)).get(); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("C2ME ItemHolder userData drift", failure); }
    }
}
