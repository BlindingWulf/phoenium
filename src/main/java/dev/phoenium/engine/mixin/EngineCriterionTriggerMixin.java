package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import java.util.function.Predicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * S9-E1 (engine mode only): a criterion triggered from a tile reads the player's listener map, which awarding rewrites.
 * The trigger is evaluated after the colour, on the server thread, in tile order (the predicate sees the same tick).
 */
@Mixin(SimpleCriterionTrigger.class)
abstract class EngineCriterionTriggerMixin<T extends SimpleCriterionTrigger.SimpleInstance> {
    @Shadow protected abstract void trigger(ServerPlayer player, Predicate<T> matcher);

    @WrapMethod(method = "trigger")
    private void phoenium$deferTrigger(ServerPlayer player, Predicate<T> matcher, Operation<Void> original) {
        TileContext context = TileEngine.current();
        if (context == null) {
            original.call(player, matcher);
            return;
        }
        context.defer(() -> this.trigger(player, matcher));
    }
}
