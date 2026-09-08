package dev.phoenium.acceptance;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.phoenium.chunk.FullRuntimeAcceptanceSeedData;
import dev.phoenium.save.RetainedChunkSnapshot;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Carries only the probe's fixture-source receipt across Stage 4's detached snapshot.
 * Native fields and the original serializer/writer remain unchanged. The probe still
 * seeds the exact reload roots at original write and verifies the actual MCA bytes. */
@Mixin(RetainedChunkSnapshot.class)
abstract class FullRuntimeAcceptanceRetainedSnapshotMixin {
    @ModifyReturnValue(method = "detach", at = @At("RETURN"))
    private static SerializableChunkData retained(SerializableChunkData result, SerializableChunkData source) {
        ((FullRuntimeAcceptanceSeedData) (Object) result).phoenium$fixtureEligible(
            ((FullRuntimeAcceptanceSeedData) (Object) source).phoenium$fixtureEligible());
        return result;
    }
}
