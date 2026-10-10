package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.EngineMoveSplit;
import dev.phoenium.engine.EngineTrackerIndex;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * S9-E6 (engine mode only): a tracked entity's entry in the tracker index ({@link EngineTrackerIndex}), and the
 * index's copy of seenBy: each connection TrackedEntity.updatePlayer adds and TrackedEntity.removePlayer removes (the
 * only places vanilla changes seenBy) is added to or removed from the index's reverse map.
 *
 * <p>S9-E13 R-C2: while {@link EngineMoveSplit} evaluates deferred moves, updatePlayer's seenBy add and its removePlayer
 * call change nothing: each records the change it would make (an add of an absent member, a removal of a present one).
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class EngineTrackedEntityMixin implements EngineTrackerIndex.Indexed {
    @Unique private EngineTrackerIndex.TrackerEntry phoenium$entry;

    @Override
    public EngineTrackerIndex.TrackerEntry phoenium$indexEntry() { return this.phoenium$entry; }

    @Override
    public void phoenium$setIndexEntry(EngineTrackerIndex.TrackerEntry entry) { this.phoenium$entry = entry; }

    @WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE", target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"))
    private boolean phoenium$indexSeen(Set<Object> seenBy, Object connection, Operation<Boolean> original) {
        if (EngineMoveSplit.evaluating()) {
            if (!seenBy.contains(connection)) EngineMoveSplit.record(this, (ServerPlayerConnection) connection);
            return false;
        }
        boolean added = original.call(seenBy, connection);
        EngineTrackerIndex.TrackerEntry entry = this.phoenium$entry;
        if (added && entry != null) entry.seen(connection);
        return added;
    }

    @WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;removePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void phoenium$evaluateUnseen(@Coerce Object self, ServerPlayer player, Operation<Void> original) {
        if (EngineMoveSplit.evaluating()) {
            if (((EngineTrackedEntityAccess) self).phoenium$seenBy().contains(player.connection)) EngineMoveSplit.record(self, player.connection);
            return;
        }
        original.call(self, player);
    }

    @WrapOperation(method = "removePlayer", at = @At(value = "INVOKE", target = "Ljava/util/Set;remove(Ljava/lang/Object;)Z"))
    private boolean phoenium$indexUnseen(Set<Object> seenBy, Object connection, Operation<Boolean> original) {
        boolean removed = original.call(seenBy, connection);
        EngineTrackerIndex.TrackerEntry entry = this.phoenium$entry;
        if (removed && entry != null) entry.unseen(connection);
        return removed;
    }
}
