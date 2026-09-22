package dev.phoenium.stage7repair.mixin;
import dev.phoenium.stage7repair.Stage7RepairProbe;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ChunkMap.class)
abstract class TrackingObserver {
    @Inject(method="addEntity",at=@At("RETURN"),require=1)
    private void added(Entity entity,CallbackInfo ci){Stage7RepairProbe.observe("TRACKING_ADD_RETURN",entity);}
    @Inject(method="removeEntity",at=@At("RETURN"),require=1)
    private void removed(Entity entity,CallbackInfo ci){Stage7RepairProbe.observe("TRACKING_REMOVE_RETURN",entity);}
}
