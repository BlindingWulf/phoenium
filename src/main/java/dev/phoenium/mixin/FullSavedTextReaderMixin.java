package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullSavedContextInputs;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Display.TextDisplay.class)
abstract class FullSavedTextReaderMixin {
    @WrapMethod(method = "readAdditionalSaveData")
    private void phoenium$text(ValueInput input, Operation<Void> original) {
        FullSavedContextInputs.textReader((Display.TextDisplay) (Object) this, input, () -> original.call(input));
    }
}
