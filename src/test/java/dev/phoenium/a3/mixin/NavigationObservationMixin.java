package dev.phoenium.a3.mixin;
import dev.phoenium.a3.MobDecisionServerProbe;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(PathNavigation.class)
abstract class NavigationObservationMixin {
    @Shadow @Final protected Mob mob;
    @Inject(method="tick",at=@At("HEAD"),require=1)
    private void tick(CallbackInfo ci){MobDecisionServerProbe.navigation(mob);}
}
