package dev.phoenium.mixin;
import dev.phoenium.chunk.FullInputView;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(ChunkMap.class)
public interface FullInputViewMixin extends FullInputView {
    @Invoker("getVisibleChunkIfPresent") ChunkHolder phoenium$visibleInput(long position);
}
