package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.engine.EngineConfig;
import dev.phoenium.engine.EngineMoveSplit;
import dev.phoenium.engine.EngineTimers;
import dev.phoenium.engine.EngineTracker;
import dev.phoenium.engine.EngineTrackerIndex;
import dev.phoenium.engine.EngineTrackerSends;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectIterators;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S9-E4 (engine mode only): ChunkMap's tracker loops check each tracked entity against each player. A check whose
 * entity is out of the player's reach and not seen by the player can only unpair nothing, so it is skipped
 * ({@link EngineTracker}); the others call TrackedEntity.updatePlayer as before. ChunkMap.move (every player move
 * packet) checked every tracked entity of the level; tick() and addEntity check the moved entities and players.
 *
 * <p>S9-E6: ChunkMap.move's loop and tick()'s pass for the players that changed section no longer go through every
 * tracked entity of the level: they visit the trackers {@link EngineTrackerIndex} finds, exactly those the filter would
 * pass (the player's own tracker is still visited first in move). The index follows the entity map's put and remove
 * here, its own check runs at the head of tick(), and when it cannot answer the vanilla loop runs.
 *
 * <p>S9-E9: tick()'s loop collects its ServerEntity.sendChanges calls, which run on the tile threads after the loop and
 * before the moved-players pass ({@link EngineTrackerSends}); the chunk tracking, the sends and the moved pass are timed.
 *
 * <p>S9-E13 R-C2: the ChunkMap side of {@link EngineMoveSplit}: a deferred move's evaluation (move's tracker loop through
 * the index, read only), the re-check of the player's own tracker, and move(player) with its tracker loop left out.
 */
@Mixin(value = ChunkMap.class, priority = 2000)
abstract class EngineChunkMapTrackerMixin implements EngineTrackerIndex.Holder, EngineMoveSplit.Target {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Int2ObjectMap<?> entityMap;

    @Unique private EngineTrackerIndex phoenium$trackerIndex;
    @Unique private final EngineTrackerSends phoenium$sends = new EngineTrackerSends();

    @Shadow private int getPlayerViewDistance(ServerPlayer player) { throw new AssertionError(); }

    @Override
    public EngineTrackerIndex phoenium$trackerIndex() {
        EngineTrackerIndex index = this.phoenium$trackerIndex;
        if (index == null) {
            index = new EngineTrackerIndex(this.level.getServer().getRunningThread(), this.entityMap, player -> this.getPlayerViewDistance(player));
            this.phoenium$trackerIndex = index;
        }
        return index;
    }

    @Override
    public EngineTrackerIndex phoenium$trackerIndexIfPresent() { return this.phoenium$trackerIndex; }

    @WrapWithCondition(method = {"move", "addEntity"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private boolean phoenium$updatePlayerMayChange(@Coerce Object tracker, ServerPlayer player) {
        return !EngineConfig.TRACKER || EngineTracker.mayChange((EngineTrackedEntityAccess) tracker, player, this.getPlayerViewDistance(player));
    }

    @WrapOperation(method = {"tick()V", "move", "addEntity"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayers(Ljava/util/List;)V"))
    private void phoenium$updatePlayersMayChange(@Coerce Object tracker, List<ServerPlayer> players, Operation<Void> original) {
        if (!EngineConfig.TRACKER) {
            original.call(tracker, players);
            return;
        }
        this.phoenium$updatePlayersFiltered((EngineTrackedEntityAccess) tracker, players);
    }

    @Unique
    private void phoenium$updatePlayersFiltered(EngineTrackedEntityAccess tracker, List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            if (EngineTracker.mayChange(tracker, player, this.getPlayerViewDistance(player))) tracker.phoenium$updatePlayer(player);
        }
    }

    // ---- S9-E6 ----

    @WrapOperation(method = "move", at = @At(value = "INVOKE",
        target = "Lit/unimi/dsi/fastutil/objects/ObjectCollection;iterator()Lit/unimi/dsi/fastutil/objects/ObjectIterator;"))
    private ObjectIterator<?> phoenium$moveVisitsIndexed(ObjectCollection<?> trackers, Operation<ObjectIterator<?>> original,
                                                         @Local(argsOnly = true) ServerPlayer player) {
        if (EngineMoveSplit.restOnly()) return ObjectIterators.emptyIterator();   // S9-E13: the apply has run the checks
        if (EngineConfig.TRACKER_INDEX) {
            ObjectIterator<Object> visits = this.phoenium$trackerIndex().forMove(player);
            if (visits != null) return visits;
        }
        return original.call(trackers);
    }

    /**
     * The only isEmpty in tick() guards the pass over every tracker for the players that changed section. S9-E9: the
     * loop is over here, so the collected sendChanges calls run first.
     */
    @WrapOperation(method = "tick()V", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"))
    private boolean phoenium$movedPlayersIndexed(List<ServerPlayer> movedPlayers, Operation<Boolean> original) {
        this.phoenium$sends.flush();
        boolean empty = original.call(movedPlayers);
        if (empty || !EngineConfig.TRACKER_INDEX) return empty;
        ObjectIterator<Object> visits = this.phoenium$trackerIndex().forMoved(movedPlayers);
        if (visits == null) return false;
        while (visits.hasNext()) this.phoenium$updatePlayersFiltered((EngineTrackedEntityAccess) visits.next(), movedPlayers);
        return true;   // every tracker the pass can change has been visited: the pass over all of them is left out
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void phoenium$checkTrackerIndex(CallbackInfo ci) {
        this.phoenium$sends.begin();
        if (EngineConfig.TRACKER_INDEX) this.phoenium$trackerIndex().tick();
    }

    // ---- S9-E9 ----

    @WrapOperation(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap;updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void phoenium$timeChunkTracking(ChunkMap chunkMap, ServerPlayer player, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(chunkMap, player); } finally { EngineTimers.add(EngineTimers.TRACKER_CHUNKS, start); }
    }

    /** The loop's sendChanges calls run after the loop (EngineTrackerSends); item frames, or with the flag off, in place. */
    @WrapOperation(method = "tick()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerEntity;sendChanges()V"))
    private void phoenium$collectSendChanges(ServerEntity serverEntity, Operation<Void> original) {
        if (!this.phoenium$sends.collect(serverEntity, original)) original.call(serverEntity);
    }

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void phoenium$timeMovedPass(CallbackInfo ci) {
        this.phoenium$sends.end();
    }

    // ---- S9-E13 R-C2 ----

    @Override
    public ServerLevel phoenium$level() { return this.level; }

    @Override
    public boolean phoenium$splitReady() { return EngineConfig.TRACKER_INDEX && this.phoenium$trackerIndex().readyForSplit(); }

    @Override
    public Object phoenium$ownTracker(ServerPlayer player) {
        Object own = this.entityMap.get(player.getId());
        return own != null && ((EngineTrackedEntityAccess) own).phoenium$entity() == player ? own : null;
    }

    /** move(player)'s tracker loop as the index visits it, each check through the S9-E4 filter (read only: records). */
    @Override
    public void phoenium$evaluateMove(ServerPlayer player) {
        Object own = this.phoenium$ownTracker(player);
        if (own != null) {
            EngineTrackedEntityAccess tracker = (EngineTrackedEntityAccess) own;
            List<ServerPlayer> players = this.level.players();
            for (int i = 0, n = players.size(); i < n; i++) {
                ServerPlayer other = players.get(i);
                if (EngineTracker.mayChangeQuiet(tracker, other, this.getPlayerViewDistance(other))) tracker.phoenium$updatePlayer(other);
            }
        }
        int view = this.getPlayerViewDistance(player);
        this.phoenium$trackerIndex().forEachMoveCandidate(player, view + 1, item -> {
            if (item == own) return;
            EngineTrackedEntityAccess tracker = (EngineTrackedEntityAccess) item;
            if (EngineTracker.mayChangeQuiet(tracker, player, view)) tracker.phoenium$updatePlayer(player);
        });
    }

    @Override
    public int phoenium$recheckOwn(ServerPlayer player, List<ServerPlayer> players) {
        Object own = this.phoenium$ownTracker(player);
        if (own == null) return 0;
        EngineTrackedEntityAccess tracker = (EngineTrackedEntityAccess) own;
        int checked = 0;
        for (int i = 0, n = players.size(); i < n; i++) {
            ServerPlayer other = players.get(i);
            if (other == player || other.level() != this.level) continue;
            if (EngineTracker.mayChange(tracker, other, this.getPlayerViewDistance(other))) {
                tracker.phoenium$updatePlayer(other);
                checked++;
            }
        }
        return checked;
    }

    @Override
    public void phoenium$moveNow(ServerPlayer player) { ((ChunkMap) (Object) this).move(player); }

    @WrapOperation(method = "addEntity", at = @At(value = "INVOKE",
        target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;put(ILjava/lang/Object;)Ljava/lang/Object;"))
    private Object phoenium$indexAdded(Int2ObjectMap<Object> map, int id, Object tracker, Operation<Object> original) {
        Object previous = original.call(map, id, tracker);
        if (EngineConfig.TRACKER_INDEX) this.phoenium$trackerIndex().added(tracker);
        return previous;
    }

    @WrapOperation(method = "removeEntity", at = @At(value = "INVOKE",
        target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;remove(I)Ljava/lang/Object;"))
    private Object phoenium$indexRemoved(Int2ObjectMap<Object> map, int id, Operation<Object> original) {
        Object tracker = original.call(map, id);
        if (EngineConfig.TRACKER_INDEX && tracker != null) this.phoenium$trackerIndex().removed(tracker);
        return tracker;
    }
}
