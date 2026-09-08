package dev.phoenium.mixin;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractFurnaceBlockEntity.class)
public interface LocalFurnaceTickAccess {
    @Accessor("litTimeRemaining") int phoenium$litTimeRemaining();
    @Accessor("cookingTimer") int phoenium$cookingTimer();
    @Accessor("cookingTotalTime") int phoenium$cookingTotalTime();
}
