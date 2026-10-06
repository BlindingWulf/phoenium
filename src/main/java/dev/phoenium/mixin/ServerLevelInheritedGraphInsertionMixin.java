package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.Phoenium;
import dev.phoenium.chunk.CommandPassengerAdmission;
import dev.phoenium.chunk.FreshPassengerAdmission;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * ServerLevel inherits {@code ServerLevelAccessor.addFreshEntityWithPassengers} and every native caller
 * reaches it by virtual dispatch. Overriding it here gives each fresh vehicle graph the command-path
 * identity custody before the root-first native loop starts tracking the root.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelInheritedGraphInsertionMixin {
    @Shadow public abstract boolean addFreshEntity(Entity entity);

    public void addFreshEntityWithPassengers(Entity root) {
        ServerLevel level = (ServerLevel) (Object) this;
        CommandPassengerAdmission.insertInheritedGraph(level, root,
            () -> Phoenium.liveRuntime().commandSpawnPassengerPublisher(level),
            () -> phoenium$nativeGraphLoop(root));
    }

    /** The exact inherited native body: root first, then Entity.getIndirectPassengersStream order. */
    private void phoenium$nativeGraphLoop(Entity root) {
        root.getSelfAndPassengers().forEach(this::addFreshEntity);
    }

    /** A detached finalization candidate is re-staged where its caller inserts it, and the custody
     * settles once that native insertion has returned. */
    @WrapMethod(method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z")
    private boolean phoenium$detachedCustodyInsertion(Entity entity, Operation<Boolean> original) {
        FreshPassengerAdmission.insertionStarting(entity);
        boolean accepted = original.call(entity);
        FreshPassengerAdmission.insertionReturned(entity);
        return accepted;
    }
}
