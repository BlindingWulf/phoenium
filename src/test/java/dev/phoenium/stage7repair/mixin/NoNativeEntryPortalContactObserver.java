package dev.phoenium.stage7repair.mixin;

import dev.phoenium.stage7repair.Stage7RepairProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe native block contact before the canUsePortal guard; never alter flow. */
@Mixin({NetherPortalBlock.class,EndPortalBlock.class,EndGatewayBlock.class})
public abstract class NoNativeEntryPortalContactObserver {
    @Inject(method="entityInside",at=@At("HEAD"))
    private void stage7$noEntryPortalContact(BlockState state,Level level,BlockPos pos,Entity entity,
                                              InsideBlockEffectApplier applier,boolean precise,CallbackInfo ci) {
        if(!level.isClientSide()) {
            Stage7RepairProbe.observeNoEntryContact(entity,state,pos);
            if(state.getBlock() instanceof EndGatewayBlock)
                Stage7RepairProbe.observeGatewayContact(entity,pos);
        }
    }
}
