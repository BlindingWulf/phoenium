package dev.phoenium.mixin;
import dev.phoenium.chunk.FullSavedInput;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(LevelChunk.class)
public interface FullPostLoadAccess extends FullSavedInput.PostLoadAccess {
    @Accessor("postLoad") LevelChunk.PostLoadProcessor phoenium$postLoad();
}
