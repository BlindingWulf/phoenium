package dev.phoenium.acceptance;
import dev.phoenium.chunk.FullRuntimeAcceptanceProbe;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Entity.class)
abstract class FullRuntimeAcceptanceEntityMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void removed(Entity.RemovalReason reason, CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        if (entity.entityTags().stream().anyMatch(tag -> tag.startsWith("phoenium_full_")))
            System.out.println("PHOENIUM_FULL_ENTITY_REMOVAL entity=" + entity.getUUID() + " runtimeId=" + entity.getId()
                + " chunk=" + entity.chunkPosition() + " type=" + entity.getType() + " tags=" + entity.entityTags()
                + " reason=" + reason);
    }
    @WrapMethod(method = "load")
    private void phoenium$traceSavedReader(ValueInput input, Operation<Void> original) {
        FullRuntimeAcceptanceProbe.savedReader((Entity) (Object) this, () -> original.call(input));
    }
    @Inject(method = "load", at = @At("RETURN"))
    private void observed(ValueInput input, CallbackInfo callback) { FullRuntimeAcceptanceProbe.loaded((Entity) (Object) this); }
}
