package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.phoenium.chunk.FullSavedContextInputs;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.network.chat.contents.ScoreContents;
import org.spongepowered.asm.mixin.Mixin;

/** Selector-form score names retain their original entity selection and scoreboard suffix. */
@Mixin(ScoreContents.class)
abstract class FullSavedScoreContentsMixin {
    @WrapMethod(method = "resolve")
    private MutableComponent phoenium$resolve(ResolutionContext context, int depth,
                                               Operation<MutableComponent> original) throws CommandSyntaxException {
        var selected = ((ScoreContents) (Object) this).name().left();
        return selected.isEmpty() ? original.call(context, depth)
                : FullSavedContextInputs.selectorScore(selected.get().source(), () -> original.call(context, depth));
    }
}
