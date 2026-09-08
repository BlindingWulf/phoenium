package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullFinishingQueries;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

/** Original predicate evaluation keeps its position, order, short circuits and failure. */
@Mixin(LocationPredicate.class)
abstract class FullLocationPredicateMixin {
    @WrapMethod(method = "matches(Lnet/minecraft/server/level/ServerLevel;DDD)Z")
    private boolean phoenium$location(ServerLevel world, double x, double y, double z, Operation<Boolean> original) {
        return FullFinishingQueries.locationPredicate(world, x, y, z, () -> original.call(world, x, y, z));
    }
}
