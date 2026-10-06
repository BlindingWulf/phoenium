package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.phoenium.live.CommandOwnerConfinement;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@code execute in}: an in-owner command source cannot name a dimension owned by another region. */
@Mixin(DimensionArgument.class)
abstract class CommandDimensionConfinementMixin {
    @ModifyReturnValue(method = "getDimension", at = @At("RETURN"), require = 1)
    private static ServerLevel phoenium$confineDimension(ServerLevel level) throws CommandSyntaxException {
        CommandOwnerConfinement.requireDimension(level);
        return level;
    }
}
