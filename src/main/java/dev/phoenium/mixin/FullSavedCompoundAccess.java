package dev.phoenium.mixin;
import dev.phoenium.chunk.FullSavedCollections;
import java.util.Map;
import net.minecraft.nbt.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(CompoundTag.class)
public interface FullSavedCompoundAccess extends FullSavedCollections.CompoundAccess {
    @Accessor("tags") Map<String, Tag> phoenium$savedMap();
    @Mutable @Accessor("tags") void phoenium$savedMap(Map<String, Tag> tags);
}
