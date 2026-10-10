package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.EngineAutosave;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * S9-E12 (engine mode only): the periodic autosave's saveEverything records the per-chunk save loops instead of running
 * them when {@link EngineAutosave#shouldSpread} says so ({@link EngineAutosave}); the player files, the scoreboard, the
 * level data and the saved data are saved in this tick as vanilla does. /save-all and the shutdown save do not come
 * through autoSave and run as vanilla.
 */
@Mixin(MinecraftServer.class)
abstract class EngineAutosaveMixin {
    @WrapOperation(method = "autoSave", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;saveEverything(ZZZ)Z"))
    private boolean phoenium$spreadAutosave(MinecraftServer server, boolean silent, boolean flush, boolean force, Operation<Boolean> original) {
        if (flush || !EngineAutosave.shouldSpread(server.getPlayerCount())) return original.call(server, silent, flush, force);
        EngineAutosave.beginRecording();
        try {
            return original.call(server, silent, flush, force);
        } finally {
            EngineAutosave.endRecording();
        }
    }
}
