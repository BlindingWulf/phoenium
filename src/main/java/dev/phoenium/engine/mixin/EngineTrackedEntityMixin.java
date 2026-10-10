package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.EngineTrackerIndex;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * S9-E6 (engine mode only): a tracked entity's entry in the tracker index ({@link EngineTrackerIndex}), and the
 * index's copy of seenBy: each connection TrackedEntity.updatePlayer adds and TrackedEntity.removePlayer removes (the
 * only places vanilla changes seenBy) is added to or removed from the index's reverse map.
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
        boolean added = original.call(seenBy, connection);
        EngineTrackerIndex.TrackerEntry entry = this.phoenium$entry;
        if (added && entry != null) entry.seen(connection);
        return added;
    }

    @WrapOperation(method = "removePlayer", at = @At(value = "INVOKE", target = "Ljava/util/Set;remove(Ljava/lang/Object;)Z"))
    private boolean phoenium$indexUnseen(Set<Object> seenBy, Object connection, Operation<Boolean> original) {
        boolean removed = original.call(seenBy, connection);
        EngineTrackerIndex.TrackerEntry entry = this.phoenium$entry;
        if (removed && entry != null) entry.unseen(connection);
        return removed;
    }
}
