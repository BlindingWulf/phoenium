package dev.phoenium.acceptance;
import dev.phoenium.chunk.FullRuntimeAcceptanceProbe;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Entity.class)
abstract class FullRuntimeAcceptanceEntityMixin {
    @Inject(method = "load", at = @At("RETURN"))
    private void observed(ValueInput input, CallbackInfo callback) { FullRuntimeAcceptanceProbe.loaded((Entity) (Object) this); }
}
