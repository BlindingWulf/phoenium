package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.phoenium.chunk.FullSavedContextInputs;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.network.chat.contents.NbtContents;
import net.minecraft.network.chat.contents.data.EntityDataSource;
import org.spongepowered.asm.mixin.Mixin;

/** The scope spans lazy entity serialization and interpreted resolution inside the original NBT resolver. */
@Mixin(NbtContents.class)
abstract class FullSavedNbtContentsMixin {
    @WrapMethod(method = "resolve")
    private MutableComponent phoenium$resolve(ResolutionContext context, int depth,
                                               Operation<MutableComponent> original) throws CommandSyntaxException {
        var source = ((NbtContents) (Object) this).dataSource();
        return source instanceof EntityDataSource entities
                ? FullSavedContextInputs.selectorNbt(entities.selector().source(), () -> original.call(context, depth))
                : original.call(context, depth);
    }
}
