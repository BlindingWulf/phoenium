package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.phoenium.chunk.FullSavedContextInputs;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.network.chat.contents.SelectorContents;
import org.spongepowered.asm.mixin.Mixin;

/** Keeps selector enumeration, predicates, ordering and name reads in the original resolver. */
@Mixin(SelectorContents.class)
abstract class FullSavedSelectorContentsMixin {
    @WrapMethod(method = "resolve")
    private MutableComponent phoenium$resolve(ResolutionContext context, int depth,
                                               Operation<MutableComponent> original) throws CommandSyntaxException {
        String source = ((SelectorContents) (Object) this).selector().source();
        return FullSavedContextInputs.selectorName(source, () -> original.call(context, depth));
    }
}
