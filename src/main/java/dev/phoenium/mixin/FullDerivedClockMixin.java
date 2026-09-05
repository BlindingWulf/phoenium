package dev.phoenium.mixin;

import dev.phoenium.chunk.*;
import net.minecraft.world.level.storage.*;
import org.spongepowered.asm.mixin.*;

@Mixin(DerivedLevelData.class)
abstract class FullDerivedClockMixin implements FullPreparationClockAccess {
    @Shadow @Final private ServerLevelData wrapped;
    @Override public FullPreparationClock phoenium$preparationClock() {
        return ((FullPreparationClockAccess) wrapped).phoenium$preparationClock();
    }
}
