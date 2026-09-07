package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.*;
import java.lang.ref.WeakReference;
import net.minecraft.world.entity.player.*;
import org.spongepowered.asm.mixin.*;

@Mixin(Abilities.class)
abstract class FullEntityFactAbilitiesMixin implements FullPlayerAbilitiesAccess {
    @Unique private WeakReference<Player> phoenium$owner = new WeakReference<>(null);
    public Player phoenium$abilitiesOwner() { return phoenium$owner.get(); }
    public void phoenium$abilitiesOwner(Player player) { phoenium$owner = new WeakReference<>(player); }
    @WrapMethod(method = "apply")
    private void phoenium$apply(Abilities.Packed value, Operation<Void> original) {
        Player player = phoenium$owner.get();
        try (var turn = player == null ? null : FullEntityFacts.writer(player)) {
            try { original.call(value); } finally { if (player != null) FullEntityFacts.publish(player); }
        }
    }
}
