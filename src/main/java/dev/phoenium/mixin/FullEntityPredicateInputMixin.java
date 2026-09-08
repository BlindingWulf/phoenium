package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Codec;
import dev.phoenium.chunk.FullEntityPredicateInputs;
import dev.phoenium.chunk.FullSavedContextInputs;
import java.util.Map;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.predicates.entity.EntitySubPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityPredicate.class)
abstract class FullEntityPredicateInputMixin implements FullEntityPredicateInputs.Access {
    @Unique private FullEntityPredicateInputs.Produced phoenium$entityInputs;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/advancements/predicates/entity/EntityPredicate;combine(Ljava/util/Map;)Lnet/minecraft/advancements/predicates/entity/EntitySubPredicate;"))
    private EntitySubPredicate phoenium$capture(Map<Codec<? extends EntitySubPredicate>, EntitySubPredicate> parts,
                                               Operation<EntitySubPredicate> original) {
        phoenium$entityInputs = FullEntityPredicateInputs.capture(() -> original.call(parts));
        return phoenium$entityInputs.original();
    }

    @ModifyExpressionValue(method = "combine", at = @At(value = "INVOKE", target =
            "Ljava/util/stream/Stream;toArray(Ljava/util/function/IntFunction;)[Ljava/lang/Object;"))
    private static Object[] phoenium$selected(Object[] result) {
        return FullEntityPredicateInputs.selectedArray(result);
    }

    @Override public FullEntityPredicateInputs.Produced phoenium$entityPredicateInputs() { return phoenium$entityInputs; }

    @WrapMethod(method = "matches(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/Entity;)Z")
    private boolean phoenium$originalPredicate(net.minecraft.server.level.ServerLevel world,
                                               net.minecraft.world.phys.Vec3 position,
                                               net.minecraft.world.entity.Entity entity, Operation<Boolean> original) {
        FullSavedContextInputs.requireEntityPredicate((EntityPredicate) (Object) this, world, entity);
        return original.call(world, position, entity);
    }
}
