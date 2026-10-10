package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import java.util.function.Consumer;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.spongepowered.asm.mixin.Mixin;

/** S9-E1 (engine mode only): scoreboard criteria updates from a tile (kill credit) run after the colour, in tile order. */
@Mixin(Scoreboard.class)
abstract class EngineScoreboardMixin {
    @WrapMethod(method = "forAllObjectives")
    private void phoenium$deferObjectives(ObjectiveCriteria criteria, ScoreHolder name, Consumer<ScoreAccess> operation,
                                          Operation<Void> original) {
        TileContext context = TileEngine.current();
        if (context == null) {
            original.call(criteria, name, operation);
            return;
        }
        Scoreboard scoreboard = (Scoreboard) (Object) this;
        context.defer(() -> scoreboard.forAllObjectives(criteria, name, operation));
    }
}
