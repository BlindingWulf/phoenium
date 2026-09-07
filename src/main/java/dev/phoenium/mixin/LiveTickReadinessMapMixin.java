package dev.phoenium.mixin;
import dev.phoenium.live.LiveTickReadiness;
import java.util.stream.Stream;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(ChunkMap.class)
public interface LiveTickReadinessMapMixin extends LiveTickReadiness.MapAccess {
    @Invoker("allChunksWithAtLeastStatus") Stream<ChunkHolder> phoenium$tickCandidates(ChunkStatus status);
}
