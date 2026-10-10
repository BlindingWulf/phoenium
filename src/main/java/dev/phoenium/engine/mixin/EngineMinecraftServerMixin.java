package dev.phoenium.engine.mixin;

import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * S9-E1 (engine mode only): a loot roll from a tile (mob drops name a random sequence) draws from the tile's stream, not
 * from the server's shared sequence map. Applied after C2ME's MinecraftServer mixins: the plugin then makes C2ME's
 * mid-tick chunk tasks skip while a colour runs (EngineMixinPlugin.guardMidTickTasks).
 */
@Mixin(value = MinecraftServer.class, priority = 2000)
abstract class EngineMinecraftServerMixin {
    @Inject(method = "getRandomSequence", at = @At("HEAD"), cancellable = true)
    private void phoenium$tileSequence(Identifier key, CallbackInfoReturnable<RandomSource> cir) {
        TileContext context = TileEngine.current();
        if (context != null) cir.setReturnValue(context.random());
    }
}
