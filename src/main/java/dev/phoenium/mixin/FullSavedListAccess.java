package dev.phoenium.mixin;
import dev.phoenium.chunk.FullSavedCollections;
import java.util.List;
import net.minecraft.nbt.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ListTag.class)
public interface FullSavedListAccess extends FullSavedCollections.ListAccess {
    @Accessor("list") List<Tag> phoenium$savedList();
    @Mutable @Accessor("list") void phoenium$savedList(List<Tag> list);
}
