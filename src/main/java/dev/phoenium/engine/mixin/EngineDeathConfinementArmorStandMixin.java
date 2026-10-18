package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Stage 11 P1 prototype ({@code phoenium.engine.deathConfinement}): a broken armor stand drops its loot through
 * {@code dropAllDeathLoot} without dying. It is vanilla 26.2's only caller of that method outside {@code die}, so it is
 * deferred too, and {@code dropAllDeathLoot} and the methods it calls stay death-only
 * ({@link dev.phoenium.engine.EntityHookGate#DEATH_PATH}).
 *
 * <p>Order change, to be verified by the equivalence fixture: from a tile, the stand is killed (removed) first, and its
 * sound, loot and equipment drops follow after the colour, at the same position.
 */
@Mixin(ArmorStand.class)
abstract class EngineDeathConfinementArmorStandMixin {
    @WrapMethod(method = "brokenByAnything")
    private void phoenium$confineBreak(ServerLevel level, DamageSource source, Operation<Void> original) {
        TileContext context = TileEngine.current();
        if (context == null) {
            original.call(level, source);
            return;
        }
        context.defer(() -> original.call(level, source));
    }
}
