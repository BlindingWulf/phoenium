package dev.phoenium.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only view of the two fields that decide whether the native exit lookup searches or creates. */
@Mixin(TheEndGatewayBlockEntity.class)
public interface TheEndGatewayExitAccess {
    @Accessor("exitPortal") BlockPos phoenium$exitPortal();
    @Accessor("exactTeleport") boolean phoenium$exactTeleport();
}
