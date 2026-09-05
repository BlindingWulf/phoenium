package dev.phoenium.mixin;

import dev.phoenium.chunk.*;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PrimaryLevelData.class)
abstract class FullPreparationClockMixin implements FullPreparationClockAccess {
    @Unique private FullPreparationClock phoenium$clock;
    @Override public synchronized FullPreparationClock phoenium$preparationClock() {
        if (phoenium$clock == null) phoenium$clock = new FullPreparationClock();
        return phoenium$clock;
    }
    @Inject(method = "setGameTime(J)V", at = @At("HEAD"), require = 1)
    private void phoenium$clockWriter(long time, CallbackInfo ci) { phoenium$preparationClock().beforeWrite(); }
    @Inject(method = "setGameTime(J)V", at = @At("RETURN"), require = 1)
    private void phoenium$clockRelease(long time, CallbackInfo ci) { phoenium$preparationClock().afterWrite(); }
}
