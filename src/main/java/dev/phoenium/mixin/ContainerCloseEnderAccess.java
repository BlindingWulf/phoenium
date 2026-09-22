package dev.phoenium.mixin;

import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerEnderChestContainer.class)
public interface ContainerCloseEnderAccess {
    @Accessor("activeChest") EnderChestBlockEntity phoenium$activeChest();
}
