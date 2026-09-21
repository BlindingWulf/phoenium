package dev.phoenium.mixin;

import net.minecraft.world.level.block.entity.vault.VaultServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VaultServerData.class)
public interface VaultServerDataAccess {
    @Accessor("isDirty") void phoenium$isDirty(boolean dirty);
}
