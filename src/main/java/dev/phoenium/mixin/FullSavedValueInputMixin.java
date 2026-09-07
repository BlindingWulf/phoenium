package dev.phoenium.mixin;

import dev.phoenium.chunk.FullSavedValueInput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.TagValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TagValueInput.class)
public interface FullSavedValueInputMixin extends FullSavedValueInput {
    @Accessor("input") CompoundTag phoenium$originalSavedTag();
}
