package dev.phoenium.mixin;
import dev.phoenium.chunk.FullInputNotifications;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ChunkMap.class)
abstract class FullInputPublicationMixin {
    @Unique private final FullInputNotifications phoenium$inputChanges = new FullInputNotifications();
    public FullInputNotifications phoenium$inputChanges() { return phoenium$inputChanges; }
    @Inject(method = "promoteChunkMap", at = @At("RETURN"), require = 1, expect = 1)
    private void phoenium$visiblePublished(CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValue()) phoenium$inputChanges.published();
    }
}
