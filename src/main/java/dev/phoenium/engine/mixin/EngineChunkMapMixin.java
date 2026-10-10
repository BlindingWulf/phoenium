package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;

/**
 * S9-E1 (engine mode only): a chunk first marked unsaved from a tile joins the eager-save set after the colour. The
 * whole method is wrapped, so C2ME's off-thread catcher inside it runs only on the server thread.
 */
@Mixin(value = ChunkMap.class, priority = 2000)
abstract class EngineChunkMapMixin {
    @WrapMethod(method = "setChunkUnsaved")
    private void phoenium$deferUnsaved(ChunkPos pos, Operation<Void> original) {
        TileContext context = TileEngine.current();
        if (context == null) {
            original.call(pos);
            return;
        }
        context.defer(() -> original.call(pos));
    }
}
