package dev.phoenium.mixin;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
/** Late cut preserves installed start/end world tick callbacks at their original ends. */
@Mixin(value = ServerLevel.class, priority = 100)
abstract class LiveLevelTickPhaseMixin { }
