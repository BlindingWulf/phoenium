package dev.phoenium.mixin;

import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Level.class)
public interface RegionalBlockEntityTickAccess {
    @Accessor("blockEntityTickers") List<TickingBlockEntity> phoenium$blockEntityTickers();
    @Accessor("pendingBlockEntityTickers") List<TickingBlockEntity> phoenium$pendingBlockEntityTickers();
    @Accessor("tickingBlockEntities") void phoenium$tickingBlockEntities(boolean ticking);
}
