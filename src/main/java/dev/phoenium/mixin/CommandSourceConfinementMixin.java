package dev.phoenium.mixin;

import dev.phoenium.live.CommandOwnerConfinement;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Backstop: an in-owner command source never moves into another region's world or entity (e.g. {@code execute on}). */
@Mixin(CommandSourceStack.class)
abstract class CommandSourceConfinementMixin {
    @Inject(method = "withLevel", at = @At("HEAD"), require = 1)
    private void phoenium$confineLevel(ServerLevel level, CallbackInfoReturnable<CommandSourceStack> cir) {
        CommandOwnerConfinement.requireLevel(level);
    }

    @Inject(method = "withEntity", at = @At("HEAD"), require = 1)
    private void phoenium$confineEntity(Entity entity, CallbackInfoReturnable<CommandSourceStack> cir) {
        CommandOwnerConfinement.requireEntity(entity);
    }
}
