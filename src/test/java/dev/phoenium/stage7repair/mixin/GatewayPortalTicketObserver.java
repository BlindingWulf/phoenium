package dev.phoenium.stage7repair.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.stage7repair.Stage7RepairProbe;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Test-only witness after the exact native portal ticket admission call. */
@Mixin(Entity.class)
abstract class GatewayPortalTicketObserver {
    @WrapOperation(method = "placePortalTicket", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/server/level/ServerChunkCache;addTicketWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V"),
        require = 1, allow = 1)
    private void stage7$portalTicket(ServerChunkCache cache, TicketType type, ChunkPos chunk,
            int radius, Operation<Void> original) {
        original.call(cache, type, chunk, radius);
        Stage7RepairProbe.observeGatewayTicket((Entity) (Object) this, type, chunk, radius);
    }
}
