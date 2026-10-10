package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;

/**
 * S9-E1 (engine mode only): advancement progress changed from a tile (its rewards can run functions anywhere) is
 * changed after the colour, on the server thread, in tile order. A deferred call answers false: nothing completed yet.
 */
@Mixin(PlayerAdvancements.class)
abstract class EngineAdvancementsMixin {
    @WrapMethod(method = "award")
    private boolean phoenium$deferAward(AdvancementHolder holder, String criterion, Operation<Boolean> original) {
        TileContext context = TileEngine.current();
        if (context == null) return original.call(holder, criterion);
        PlayerAdvancements advancements = (PlayerAdvancements) (Object) this;
        context.defer(() -> advancements.award(holder, criterion));
        return false;
    }

    @WrapMethod(method = "revoke")
    private boolean phoenium$deferRevoke(AdvancementHolder holder, String criterion, Operation<Boolean> original) {
        TileContext context = TileEngine.current();
        if (context == null) return original.call(holder, criterion);
        PlayerAdvancements advancements = (PlayerAdvancements) (Object) this;
        context.defer(() -> advancements.revoke(holder, criterion));
        return false;
    }
}
