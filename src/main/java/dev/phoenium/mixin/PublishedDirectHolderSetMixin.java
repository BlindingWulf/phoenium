package dev.phoenium.mixin;

import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native direct ingredient membership otherwise initializes this shared cache on
 * first contains(). Construct its identical immutable set before publication so
 * parallel recipe matches remain reads. Contents and native matching are unchanged. */
@Mixin(targets = "net.minecraft.core.HolderSet$Direct")
abstract class PublishedDirectHolderSetMixin<T> {
    @Shadow private Set<Holder<T>> contentsSet;
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void phoenium$publishMembership(List<Holder<T>> contents, CallbackInfo ci) {
        contentsSet = Set.copyOf(contents);
    }
}
