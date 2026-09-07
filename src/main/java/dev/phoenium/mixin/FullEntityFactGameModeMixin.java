package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullEntityFacts;
import net.minecraft.server.level.*;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.*;

@Mixin(ServerPlayerGameMode.class)
abstract class FullEntityFactGameModeMixin {
    @Shadow @Final protected ServerPlayer player;
    @WrapMethod(method = "setGameModeForPlayer")
    private void phoenium$mode(GameType mode, GameType previous, Operation<Void> original) {
        try (var turn = FullEntityFacts.writer(player)) {
            try { original.call(mode, previous); } finally { FullEntityFacts.publish(player); }
        }
    }
}
