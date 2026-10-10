package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import org.spongepowered.asm.mixin.Mixin;

/**
 * S9-E1 (engine mode only): a stat awarded from a tile (an arrow's owner, kill credit) is awarded after the colour, on
 * the server thread, in tile order: the counter's read-modify-write and the scoreboard update run there as one step.
 */
@Mixin(ServerPlayer.class)
abstract class EngineServerPlayerMixin {
    @WrapMethod(method = "awardStat(Lnet/minecraft/stats/Stat;I)V")
    private void phoenium$deferStat(Stat<?> stat, int count, Operation<Void> original) {
        TileContext context = TileEngine.current();
        if (context == null) {
            original.call(stat, count);
            return;
        }
        ServerPlayer player = (ServerPlayer) (Object) this;
        context.defer(() -> player.awardStat(stat, count));
    }
}
