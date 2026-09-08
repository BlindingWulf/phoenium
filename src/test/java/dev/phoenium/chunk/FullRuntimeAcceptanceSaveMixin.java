package dev.phoenium.acceptance;
import dev.phoenium.chunk.FullRuntimeAcceptanceProbe;
import dev.phoenium.chunk.FullRuntimeAcceptanceSeedData;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(SerializableChunkData.class)
abstract class FullRuntimeAcceptanceSaveMixin implements FullRuntimeAcceptanceSeedData {
    @org.spongepowered.asm.mixin.Unique private boolean phoenium$fixtureEligible;

    @ModifyReturnValue(method = "copyOf", at = @At("RETURN"))
    private static SerializableChunkData fixtureSource(SerializableChunkData data, ServerLevel level,
                                                        ChunkAccess chunk) {
        if (FullRuntimeAcceptanceProbe.persistenceFixtureEligible(level, chunk)) {
            FullRuntimeAcceptanceSeedData eligible = (FullRuntimeAcceptanceSeedData) (Object) data;
            eligible.phoenium$fixtureEligible(true);
        }
        return data;
    }

    @Shadow public abstract ChunkPos chunkPos();
    @Shadow public abstract ChunkStatus chunkStatus();

    @Override public boolean phoenium$fixtureEligible() { return phoenium$fixtureEligible; }
    @Override public void phoenium$fixtureEligible(boolean eligible) { phoenium$fixtureEligible = eligible; }

    @ModifyReturnValue(method = "write", at = @At("RETURN"))
    private CompoundTag fixture(CompoundTag output) {
        if (!phoenium$fixtureEligible) return output;
        return FullRuntimeAcceptanceProbe.persistedWrite(chunkPos(), chunkStatus(), output);
    }
}
