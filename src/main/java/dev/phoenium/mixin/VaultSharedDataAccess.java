package dev.phoenium.mixin;

import net.minecraft.world.level.block.entity.vault.VaultSharedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VaultSharedData.class)
public interface VaultSharedDataAccess {
    @Accessor("isDirty") boolean phoenium$isDirty();
    @Accessor("isDirty") void phoenium$isDirty(boolean dirty);
}
