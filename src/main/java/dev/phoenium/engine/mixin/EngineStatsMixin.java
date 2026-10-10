package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** S9-E1 (engine mode only): a player's stat written from a tile is written after the colour, on the server thread. */
@Mixin(ServerStatsCounter.class)
abstract class EngineStatsMixin {
    @WrapMethod(method = "setValue")
    private void phoenium$deferValue(Player player, Stat<?> stat, int count, Operation<Void> original) {
        TileContext context = TileEngine.current();
        if (context == null) {
            original.call(player, stat, count);
            return;
        }
        ServerStatsCounter stats = (ServerStatsCounter) (Object) this;
        context.defer(() -> stats.setValue(player, stat, count));
    }
}
