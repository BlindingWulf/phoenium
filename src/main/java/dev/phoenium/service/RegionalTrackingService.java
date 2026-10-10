package dev.phoenium.service;

import dev.phoenium.core.WorldId;
import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.chunk.FullFinishingScoreboard;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.chunk.PlayerRespawnHandoff;
import dev.phoenium.mixin.RegionalChunkMapAccess;
import dev.phoenium.mixin.RegionalTrackedEntityAccess;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;

/**
 * Owner-partitioned index of the actual ChunkMap tracker objects. The source map remains the
 * named TRACKER registry; this service retains its identities and original iteration order while
 * executing each reached update under the tracked entity's current owner.
 */
public final class RegionalTrackingService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(RegionalTrackingService.class);
    private final WorldId world;
    private final ServerLevel level;
    private final FullFinishingServices finishing;
    private final RegionDirectory directory;
    private final RegionalServiceDispatcher dispatcher;
    private final ObjectOwnershipRegistry objects;
    private final RegionalTrackingPartitions<Entry> index = new RegionalTrackingPartitions<>();
    private final ThreadLocal<AddPlan> activeAdd = new dev.phoenium.core.FastThreadLocal<>();
    private final ThreadLocal<RemovePlan> activeRemove = new dev.phoenium.core.FastThreadLocal<>();
    private final ThreadLocal<MovePlan> activeMove = new dev.phoenium.core.FastThreadLocal<>();
    private final AtomicBoolean routeHandoffLogged = new AtomicBoolean();
    private final AtomicBoolean staleRouteFailureLogged = new AtomicBoolean();
    private boolean cycling;
    private long cycles, updates, migrations, sourceReconciliations;
    /** S9-D18: entries added to or replaced in the index (each starts unverified); see Verified. */
    private long additions;
    /** S9-D18: every non-player entry in the index when {@code additions} had this value was verified at publication
     * {@code stamp} and routed in {@code view} (a pass that verified each entry records it). A removal keeps it (a
     * removed entry is never selected); an addition, another stamp or another view ends it. */
    private record Verified(long additions, long stamp, RegionDirectory.View view) { }
    private Verified verified;
    /** S9-D19: movePlayer's selections by the path they took (all worlds), for the status line: the cell index, or the
     * full list because nothing was verified, an entry was added, a non-player fact was published, the view or route
     * changed, or the index changed during selection; and the full-list passes that recorded a Verified. */
    private static final int MOVE_FAST = 0, MOVE_UNVERIFIED = 1, MOVE_ADDED = 2, MOVE_PUBLISHED = 3, MOVE_VIEW = 4,
        MOVE_INDEX = 5, MOVE_RECORDED = 6;
    private static final java.util.concurrent.atomic.LongAdder[] MOVE_COUNTS = new java.util.concurrent.atomic.LongAdder[7];
    static { for (int i = 0; i < MOVE_COUNTS.length; i++) MOVE_COUNTS[i] = new java.util.concurrent.atomic.LongAdder(); }
    public static String moveCounts() {
        return "fast=" + MOVE_COUNTS[MOVE_FAST].sum() + ",unverified=" + MOVE_COUNTS[MOVE_UNVERIFIED].sum()
            + ",added=" + MOVE_COUNTS[MOVE_ADDED].sum() + ",published=" + MOVE_COUNTS[MOVE_PUBLISHED].sum()
            + ",view=" + MOVE_COUNTS[MOVE_VIEW].sum() + ",index=" + MOVE_COUNTS[MOVE_INDEX].sum()
            + ",recorded=" + MOVE_COUNTS[MOVE_RECORDED].sum();
    }
    /** S9-D18: each indexed entry under every listener of its seenConnections (kept by setSeen and forgetViewers). */
    private final IdentityHashMap<Object, Set<Entry>> viewers = new IdentityHashMap<>();
    /** S9-D18: the player entries of the index list {@code playerEntriesOf}, in its order. */
    private List<Entry> playerEntriesOf, playerEntries = List.of();

    public RegionalTrackingService(WorldId world, ServerLevel level, RegionDirectory directory,
                                   RegionalServiceDispatcher dispatcher,
                                   ObjectOwnershipRegistry objects) {
        this.world = Objects.requireNonNull(world);
        this.level = Objects.requireNonNull(level);
        this.finishing = FullFinishingServices.forServer(level.getServer());
        this.directory = Objects.requireNonNull(directory);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.objects = Objects.requireNonNull(objects);
    }

    /** Bootstrap/reconciliation reads only the named tracker registry's identity/order. */
    public void reconcileSource(Int2ObjectMap<Object> source) {
        List<Source> captured = new ArrayList<>();
        for (Int2ObjectMap.Entry<Object> sourceEntry : source.int2ObjectEntrySet()) {
            Object tracker = sourceEntry.getValue();
            Entity entity = access(tracker).phoenium$getEntity();
            int id = sourceEntry.getIntKey();
            FullEntityFacts.Candidate candidate = finishing.entityFacts.trackingCandidate(level, entity);
            if (candidate.fact().runtimeId() != id)
                throw new IllegalStateException("tracker source id differs from retained entity fact " + id);
            RoutingKey key = key(candidate);
            RegionId owner = route(key).region();
            captured.add(new Source(id, tracker, entity, candidate, key, owner));
        }
        synchronized (this) {
            if (cycling) throw new IllegalStateException("tracker source changed during regional cycle");
            List<RegionalTrackingPartitions.Item<Entry>> next = new ArrayList<>();
            for (Source sourceEntry : captured) {
                Entry retained = index.value(sourceEntry.id);
                if (retained == null) retained = new Entry(sourceEntry.id, sourceEntry.tracker, sourceEntry.entity,
                    sourceEntry.candidate, sourceEntry.key, sourceEntry.owner);
                else {
                    retained.candidate = sourceEntry.candidate;
                    retained.key = sourceEntry.key;
                    retained.owner = sourceEntry.owner;
                    retained.routedView = null;
                }
                next.add(new RegionalTrackingPartitions.Item<>(sourceEntry.id, sourceEntry.tracker, retained,
                    sourceEntry.key, sourceEntry.owner));
            }
            List<Entry> before = index.values();
            index.replaceAll(next); sourceReconciliations++; additions++;
            for (Entry dropped : before) if (index.value(dropped.id) != dropped) forgetViewers(dropped);
        }
    }

    /** Exact successful ChunkMap.addEntity suffix. The original tracker and pairing callbacks
     * have already completed; only this retained owner-partitioned index is published here. */
    public void sourceAdded(Int2ObjectMap<Object> source, Entity entity) {
        FullEntityFacts.Candidate candidate = finishing.entityFacts.trackingCandidate(level, entity);
        RoutingKey key = key(candidate);
        dispatcher.requireCurrentOwner(key);
        int id = candidate.fact().runtimeId();
        Object tracker = source.get(id);
        if (tracker == null) return; // vanilla deliberately does not track zero-range/dragon-part entities
        if (access(tracker).phoenium$getEntity() != entity)
            throw new IllegalStateException("successful tracker insertion has no exact source identity " + id);
        AddPlan add = activeAdd.get();
        RangeProjection relation = add != null && add.entity == entity
            ? add.sourceRelation : prepareRange(candidate);
        requireRelation(relation);
        int effectiveRange = relation.effectiveRange;
        Set<Object> seenConnections = Set.copyOf(access(tracker).phoenium$getSeenBy());
        synchronized (this) {
            if (cycling) throw new IllegalStateException("tracker source changed during regional cycle");
            Entry prior = index.value(id);
            if (prior != null && prior.tracker != tracker)
                throw new IllegalStateException("tracker insertion displaced a retained identity " + id);
            Entry retained = prior == null ? new Entry(id, tracker, entity, candidate, key, route(key).region()) : prior;
            retained.candidate = candidate; retained.key = key; retained.owner = route(key).region(); retained.routedView = null;
            retained.effectiveRange = effectiveRange;
            index.put(new RegionalTrackingPartitions.Item<>(id, tracker, retained, key, retained.owner));
            setSeen(retained, seenConnections);
            additions++;
            reorderSource(source);
            sourceReconciliations++;
        }
    }

    /** Exact successful ChunkMap.removeEntity suffix; vanilla has already broadcast removal. */
    public void sourceRemoved(Int2ObjectMap<Object> source, Entity entity) {
        FullEntityFacts.Candidate candidate = finishing.entityFacts.trackingCandidate(level, entity);
        RoutingKey key = key(candidate);
        dispatcher.requireCurrentOwner(key);
        int id = candidate.fact().runtimeId();
        if (source.containsKey(id))
            throw new IllegalStateException("tracker removal suffix still contains source identity " + id);
        synchronized (this) {
            if (cycling) throw new IllegalStateException("tracker source changed during regional cycle");
            Entry previous = index.value(id);
            Entry removed = index.remove(id, previous == null ? entity : previous.tracker);
            if (removed != null && removed.entity != entity)
                throw new IllegalStateException("tracker removal names a displaced entity " + id);
            if (removed != null) forgetViewers(removed);
            reorderSource(source);
            sourceReconciliations++;
        }
    }

    public void tick(ChunkMap map, List<ServerPlayer> activePlayers, List<ServerPlayer> levelPlayers) {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("regional tracker entered with owner authority");
        List<Entry> entries; long additionsAt;
        synchronized (this) {
            if (cycling) throw new IllegalStateException("nested regional tracker cycle");
            entries = index.values();
            additionsAt = additions;
        }

        var allPlayers = new ArrayList<ServerPlayer>(levelPlayers);
        for (ServerPlayer player : activePlayers) if (!allPlayers.contains(player)) allPlayers.add(player);
        FullFinishingScoreboard.TrackingCut connectionCut = finishing.scoreboard.trackingCut(allPlayers);
        Map<ServerPlayer, PlayerReceipt> players = new IdentityHashMap<>();
        Map<ServerGamePacketListenerImpl, PlayerReceipt> byListener = new IdentityHashMap<>();
        for (FullFinishingScoreboard.TrackingSession session : connectionCut.sessions()) {
            FullEntityFacts.Candidate candidate = finishing.entityFacts.trackingCandidate(level, session.player());
            PlayerReceipt receipt = new PlayerReceipt(session, candidate, key(candidate));
            players.put(session.player(), receipt);
            byListener.put(session.listener(), receipt);
        }
        // S9-D11: per entry, its preparation (candidate, key, range admission) and then its read-only preview turn;
        // executeEach lets consecutive entries share one turn where nothing could run between them.
        int count = entries.size();
        FullEntityFacts.Candidate[] preparedCandidates = new FullEntityFacts.Candidate[count];
        RoutingKey[] preparedKeys = new RoutingKey[count];
        RangeProjection[] preparedRelations = new RangeProjection[count];
        // S9-D16: entry i's preview at index i (entries are distinct; every pass below walks the same indices)
        Preview[] previews = new Preview[count];
        // S9-D17: read before any candidate below is looked up, so it never claims a later publication (as in
        // refreshCandidates): each previewed non-player entry is then verified at it, and a move before the next
        // publication keeps its candidate without a lookup or a re-route.
        long stamp = finishing.entityFacts.nonPlayerPublications();
        RegionDirectory.View[] previewView = {null};
        boolean[] mixedViews = {false};
        dispatcher.executeEach(world, count, i -> {
            FullEntityFacts.Candidate prepared = finishing.entityFacts.trackingCandidate(level, entries.get(i).entity);
            preparedCandidates[i] = prepared;
            preparedKeys[i] = key(prepared);
            preparedRelations[i] = prepareRange(prepared);
            return preparedKeys[i];
        }, RegionalServiceKind.TRACKING_UPDATE, i -> {
            Entry entry = entries.get(i);
            FullEntityFacts.Candidate candidate = preparedCandidates[i];
            RoutingKey key = preparedKeys[i];
            RangeProjection relation = preparedRelations[i];
            Preview[] captured = new Preview[1];
            {
                requireEntityOwner(entry, candidate, key);
                requireEntry(entry, candidate);
                RegionalTrackedEntityAccess tracker = access(entry.tracker);
                SectionPos now = SectionPos.of(entry.entity);
                var seenBy = tracker.phoenium$getSeenBy();
                Set<Object> seenListeners;
                if (seenBy.isEmpty()) {
                    // S9-D14: most tracked entities have no viewer; their (empty) copies are the empty set.
                    seenListeners = Set.of();
                } else {
                    seenBy.forEach(connection -> {
                        if (byListener.get(connection) == null)
                            throw new IllegalStateException("tracked connection is absent from the retained live session cut");
                    });
                    // S9-D16: the retained viewer set when its members are unchanged (as refreshProjection keeps it).
                    // Viewer players are asked through their listeners (seesPlayer): the cut pairs them one to one.
                    Set<Object> retainedViewers = entry.seenConnections;
                    seenListeners = retainedViewers.size() == seenBy.size() && retainedViewers.containsAll(seenBy)
                        ? retainedViewers : Set.<Object>copyOf(seenBy);
                }
                captured[0] = new Preview(key, now, !now.equals(tracker.phoenium$getLastSectionPos()),
                    relation.effectiveRange, seenListeners, candidate, relation);
            }
            previews[i] = Objects.requireNonNull(captured[0]);
            // S9-D17: read before route() resolves the owner (as refreshCandidate does), so it never names a later view
            RegionDirectory.View routedIn = directory.snapshot();
            if (previewView[0] == null) previewView[0] = routedIn; else if (previewView[0] != routedIn) mixedViews[0] = true;
            synchronized (this) {
                entry.candidate = candidate;
                entry.key = key;
                entry.owner = route(key).region();
                entry.routedView = routedIn;
                if (!(entry.entity instanceof net.minecraft.world.entity.player.Player)) entry.verifiedAt = stamp;
                entry.effectiveRange = captured[0].effectiveRange;
                setSeen(entry, captured[0].seenConnections);
                index.update(entry.id, key, entry.owner);
            }
        });
        // S9-D18: every entry of the list is now verified at the stamp, in one view
        if (!mixedViews[0]) synchronized (this) {
            verified = new Verified(additionsAt, stamp, previewView[0] != null ? previewView[0] : directory.snapshot());
        }

        // S9-D7: every level player must have a receipt whatever the entry; check it where the first entry did.
        // A uniformly routed world then admits each entry's footprint (all keys in this world) without its keys.
        if (!entries.isEmpty()) for (ServerPlayer player : levelPlayers) requirePlayer(players, player);
        for (int i = 0; i < count; i++) {
            Preview preview = previews[i];
            if (dispatcher.ensureUniformClosure(preview.key)) continue;
            LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>();
            footprint.addAll(preview.relation.footprint);
            for (ServerPlayer player : levelPlayers) {
                PlayerReceipt receipt = requirePlayer(players, player);
                if (seesPlayer(preview, receipt) || candidate(preview, receipt.key)) footprint.add(receipt.key);
            }
            dispatcher.ensureClosure(footprint);
        }

        synchronized (this) { cycling = true; cycles++; }
        try {
            RegionalChunkMapAccess mapAccess = (RegionalChunkMapAccess) map;
            Set<ServerPlayer> deferredPlayers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            dispatcher.executeEach(world, activePlayers.size(), i -> requirePlayer(players, activePlayers.get(i)).key,
                    RegionalServiceKind.TRACKING_UPDATE, i -> {
                ServerPlayer player = activePlayers.get(i);
                PlayerReceipt receipt = requirePlayer(players, player);
                if (currentTickPlayer(receipt, connectionCut, deferredPlayers))
                    mapAccess.phoenium$invokeUpdateChunkTracking(player);
            });

            List<ServerPlayer> moved = new ArrayList<>();
            dispatcher.executeEach(world, count, i -> previews[i].key, RegionalServiceKind.TRACKING_UPDATE, i -> {
                Entry entry = entries.get(i);
                Preview preview = previews[i];
                {
                    // A movement packet can run while executeAndDrain reaches this owner turn.
                    // Retire the old player preview; the next tick will recapture its route.
                    if (entry.entity instanceof ServerPlayer
                            && !finishing.entityFacts.validTrackingCandidate(preview.candidate)) return;
                    requireEntityOwner(entry, preview.candidate, preview.key);
                    requireEntryRelation(entry, preview.candidate, preview.relation);
                    RegionalTrackedEntityAccess tracker = access(entry.tracker);
                    if (preview.sectionChanged) {
                        // S9-D11: the recipient candidates are a pure selection over the retained cut; only a section
                        // change reads them.
                        for (PlayerReceipt player : trackingCandidates(levelPlayers, players, preview)) {
                            if (currentTickPlayer(player, connectionCut, deferredPlayers))
                                tracker.phoenium$invokeUpdatePlayer(player.session.player());
                        }
                        refreshProjection(entry);
                        if (entry.entity instanceof ServerPlayer player) moved.add(player);
                        tracker.phoenium$setLastSectionPos(preview.section);
                    }
                    if (preview.sectionChanged || entry.entity.needsSync
                            || map.getDistanceManager().inEntityTickingRange(preview.section.chunk().pack()))
                        tracker.phoenium$getServerEntity().sendChanges();
                    synchronized (this) { updates++; }
                }
            });
            if (!moved.isEmpty()) dispatcher.executeEach(world, count, i -> previews[i].key,
                    RegionalServiceKind.TRACKING_PAIR, i -> {
                Entry entry = entries.get(i);
                Preview preview = previews[i];
                List<PlayerReceipt> candidates = trackingCandidates(moved, players, preview);
                {
                    if (entry.entity instanceof ServerPlayer
                            && !finishing.entityFacts.validTrackingCandidate(preview.candidate)) return;
                    requireEntityOwner(entry, preview.candidate, preview.key);
                    requireEntryRelation(entry, preview.candidate, preview.relation);
                    for (PlayerReceipt player : candidates) {
                        if (currentTickPlayer(player, connectionCut, deferredPlayers))
                            access(entry.tracker).phoenium$invokeUpdatePlayer(player.session.player());
                    }
                    refreshProjection(entry);
                }
            });
        } finally {
            synchronized (this) { cycling = false; }
        }
    }

    /** Completes bootstrap projections after live object ownership has been installed. */
    public void initialize() {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("tracker bootstrap projections require an owner-free boundary");
        FullFinishingScoreboard.TrackingCut cut = finishing.scoreboard.trackingCut(List.copyOf(level.players()));
        Set<Object> listeners = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        cut.sessions().forEach(session -> listeners.add(session.listener()));
        List<Entry> entries;
        synchronized (this) { entries = index.values(); }
        for (Entry entry : entries) {
            RangeProjection relation = prepareRange(entry.candidate);
            var seen = new java.util.concurrent.atomic.AtomicReference<Set<Object>>();
            dispatcher.executeAndDrain(entry.key, RegionalServiceKind.TRACKING_UPDATE, ignored -> {
                requireEntityOwner(entry, entry.candidate, entry.key);
                requireEntry(entry, entry.candidate);
                requireRelation(relation);
                Set<Object> exact = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                exact.addAll(access(entry.tracker).phoenium$getSeenBy());
                if (!listeners.containsAll(exact))
                    throw new IllegalStateException("bootstrap tracker retains a connection outside the live session cut");
                seen.set(Set.copyOf(exact));
            });
            synchronized (this) {
                entry.effectiveRange = relation.effectiveRange;
                setSeen(entry, Objects.requireNonNull(seen.get()));
            }
        }
    }

    /** Runs the complete original add synchronously after preparing its finite recipient/source
     * footprint. The two original pairing call sites are intercepted below so far, unseen aliases
     * are decided from owner-published routes without dereferencing foreign entities. */
    public void addEntity(ChunkMap map, Entity entity, Runnable original) {
        Objects.requireNonNull(original, "original");
        if (activeAdd.get() != null) throw new IllegalStateException("nested ChunkMap.addEntity escaped its prepared tracking footprint");
        // An older accepted movement envelope must settle before the initial recipient cut.
        // Otherwise the add's first owner drain can invalidate that cut before updatePlayers.
        if (OwnershipContext.current().isEmpty()) dispatcher.drainBeforeSelection();
        FullEntityFacts.Candidate source = finishing.entityFacts.trackingCandidate(level, entity);
        RoutingKey sourceKey = key(source);
        List<ServerPlayer> levelPlayers = List.copyOf(level.players());
        var connectionPlayers = new ArrayList<>(levelPlayers);
        if (entity instanceof ServerPlayer player && !connectionPlayers.contains(player)) connectionPlayers.add(player);
        var respawn = respawnTracking(entity, PlayerRespawnHandoff.Action.ADD);
        FullFinishingScoreboard.TrackingCut connectionCut = finishing.scoreboard.trackingCut(connectionPlayers, respawn);
        Map<ServerPlayer, PlayerReceipt> players = playerReceipts(connectionCut);
        RangeProjection sourceRelation = prepareRange(source);
        int sourceRange = sourceRelation.effectiveRange;
        var selectedPlayers = java.util.Collections.newSetFromMap(new IdentityHashMap<ServerPlayer, Boolean>());
        LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>();
        footprint.addAll(sourceRelation.footprint);
        for (ServerPlayer player : levelPlayers) {
            PlayerReceipt receipt = requirePlayer(players, player);
            if (candidateChunk(sourceKey, sourceRange, receipt.key)) {
                selectedPlayers.add(player);
                footprint.add(receipt.key);
            }
        }

        var selectedTrackers = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        if (entity instanceof ServerPlayer) {
            List<Entry> currentEntries;
            synchronized (this) { currentEntries = index.values(); }
            for (Entry entry : currentEntries) {
                refreshCandidate(entry);
                if (candidateChunk(entry.key, publishedRange(entry.candidate).effectiveRange, sourceKey)) {
                    selectedTrackers.add(entry.tracker);
                    footprint.addAll(publishedRange(entry.candidate).footprint);
                }
            }
        }

        if (OwnershipContext.current().isPresent()) dispatcher.requireCurrentClosure(footprint);
        else dispatcher.ensureClosure(footprint);
        AddPlan plan = new AddPlan(entity, source, sourceKey, sourceRelation, connectionCut, players,
            Set.copyOf(selectedPlayers), Set.copyOf(selectedTrackers));
        if (respawn != null) respawn.route(source, sourceKey, directory.snapshot().generation().value());
        Runnable body = () -> {
            dispatcher.requireCurrentOwner(sourceKey);
            requireRelation(sourceRelation);
            if (!finishing.entityFacts.validTrackingCandidate(source))
                throw new IllegalStateException("addEntity source identity changed before original body");
            if (entity instanceof ServerPlayer player)
                finishing.scoreboard.requireTrackingSession(connectionCut, players.get(player).session);
            activeAdd.set(plan);
            try { original.run(); }
            catch (Throwable failure) {
                if (respawn != null || dev.phoenium.live.NativePortalTransfer.inserting(entity)) {
                    // A native send/pairing callback can fail after entityMap.put, before the
                    // normal sourceAdded suffix. Retain precisely that prefix for strict removal.
                    try {
                        sourceAdded(((RegionalChunkMapAccess) map).phoenium$getTrackedEntities(), entity);
                        for (Entry entry : index.values()) if (selectedTrackers.contains(entry.tracker)) refreshProjection(entry);
                    } catch (Throwable retention) { if (retention != failure) failure.addSuppressed(retention); }
                }
                throw failure;
            }
            finally { activeAdd.set(null); }
        };
        Runnable admitted = respawn == null ? body : () -> respawn.captureOwnerFailure(body);
        if (OwnershipContext.current().isPresent()) admitted.run();
        else dispatcher.executeAndDrain(sourceKey, RegionalServiceKind.TRACKING_PAIR, ignored -> admitted.run());
        if (respawn != null) respawn.rethrowOwnerFailure();
    }

    /** Exact new TrackedEntity.updatePlayers call inside ChunkMap.addEntity. */
    public void addPlayers(Object trackerIdentity, List<ServerPlayer> originalPlayers) {
        AddPlan plan = Objects.requireNonNull(activeAdd.get(), "unprepared addEntity updatePlayers call");
        if (access(trackerIdentity).phoenium$getEntity() != plan.source.alias())
            throw new IllegalStateException("addEntity updatePlayers names a foreign tracker");
        for (ServerPlayer player : originalPlayers) if (plan.selectedPlayers.contains(player)) {
            PlayerReceipt receipt = requirePlayer(plan.players, player);
            requirePlayer(receipt, plan.connections);
            access(trackerIdentity).phoenium$invokeUpdatePlayer(player);
        }
    }

    /** Exact reverse existing-tracker updatePlayer call for a newly added ServerPlayer. */
    public void addPlayer(Object trackerIdentity, ServerPlayer player, Runnable original) {
        AddPlan plan = Objects.requireNonNull(activeAdd.get(), "unprepared addEntity updatePlayer call");
        if (player != plan.source.alias()) throw new IllegalStateException("addEntity reverse pairing player changed");
        if (!plan.selectedTrackers.contains(trackerIdentity)) return;
        Entry entry = retainedTracker(trackerIdentity, "addEntity reverse pairing tracker is not retained");
        requireEntityOwner(entry, entry.candidate, entry.key);
        requireEntry(entry, entry.candidate);
        requireRelation(publishedRange(entry.candidate));
        finishing.scoreboard.requireTrackingSession(plan.connections,
            plan.players.get(player).session);
        original.run();
        refreshProjection(entry);
    }

    /** Prepares the exact immediate removal recipients before vanilla mutates the tracker map.
     * Unseen reverse-loop entries are skipped because their original removePlayer is a set remove
     * with no callback; every seen entry and every broadcastRemoved recipient is enclosed. */
    public void removeEntity(ChunkMap map, Entity entity, Runnable original) {
        if (activeRemove.get() != null) throw new IllegalStateException("nested ChunkMap.removeEntity escaped its tracking footprint");
        // As in addEntity: older accepted owner work (another player's movement envelope) settles
        // before the recipient cut, not inside this removal's drain after the cut.
        if (OwnershipContext.current().isEmpty()) dispatcher.drainBeforeSelection();
        FullEntityFacts.Candidate source = finishing.entityFacts.trackingCandidate(level, entity);
        RoutingKey sourceKey = key(source);
        var connectionPlayers = new ArrayList<>(level.players());
        if (entity instanceof ServerPlayer player && !connectionPlayers.contains(player)) connectionPlayers.add(player);
        var respawn = respawnTracking(entity, PlayerRespawnHandoff.Action.REMOVE);
        FullFinishingScoreboard.TrackingCut cut = finishing.scoreboard.trackingCut(connectionPlayers, respawn);
        Map<ServerPlayer, PlayerReceipt> players = playerReceipts(cut);
        Map<Object, PlayerReceipt> listeners = new IdentityHashMap<>();
        players.values().forEach(receipt -> listeners.put(receipt.session.listener(), receipt));
        List<Entry> entries;
        synchronized (this) { entries = index.values(); }
        Entry sourceEntry = entries.stream().filter(value -> value.entity == entity).findFirst().orElse(null);
        RangeProjection sourceRelation = prepareRange(source);
        LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>(sourceRelation.footprint);
        if (sourceEntry != null) for (Object listener : sourceEntry.seenConnections) {
            PlayerReceipt receipt = Objects.requireNonNull(listeners.get(listener),
                "removed tracker retains a connection outside the live session cut");
            footprint.add(receipt.key);
        }
        Set<Object> selectedTrackers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        if (entity instanceof ServerPlayer player) {
            PlayerReceipt removedPlayer = requirePlayer(players, player);
            for (Entry entry : entries) {
                refreshCandidate(entry);
                if (entry.seenConnections.contains(removedPlayer.session.listener())) {
                    selectedTrackers.add(entry.tracker); footprint.addAll(publishedRange(entry.candidate).footprint);
                }
            }
        }
        if (OwnershipContext.current().isPresent()) dispatcher.requireCurrentClosure(footprint);
        else dispatcher.ensureClosure(footprint);
        RemovePlan plan = new RemovePlan(entity, sourceRelation, cut, players, Set.copyOf(selectedTrackers));
        if (respawn != null) respawn.route(source, sourceKey, directory.snapshot().generation().value());
        Runnable body = () -> {
            dispatcher.requireCurrentOwner(sourceKey);
            requireRelation(sourceRelation);
            if (entity instanceof ServerPlayer player)
                finishing.scoreboard.requireTrackingSession(cut, players.get(player).session);
            activeRemove.set(plan);
            try { original.run(); }
            finally { activeRemove.set(null); }
        };
        Runnable admitted = respawn == null ? body : () -> respawn.captureOwnerFailure(body);
        if (OwnershipContext.current().isPresent()) admitted.run();
        else dispatcher.executeAndDrain(sourceKey, RegionalServiceKind.TRACKING_PAIR, ignored -> admitted.run());
        if (respawn != null) respawn.rethrowOwnerFailure();
    }

    private PlayerRespawnHandoff.Tracking respawnTracking(Entity entity, PlayerRespawnHandoff.Action action) {
        if (!(entity instanceof ServerPlayer player)) return null;
        var handoff = finishing.scoreboard.respawn(player);
        if (handoff == null) return null;
        return Objects.requireNonNull(handoff.tracking(player, action), "respawn tracking has no exact action receipt");
    }

    public void removePlayer(Object trackerIdentity, ServerPlayer player, Runnable original) {
        RemovePlan plan = Objects.requireNonNull(activeRemove.get(), "unprepared removeEntity removePlayer call");
        if (player != plan.entity) throw new IllegalStateException("removeEntity reverse player changed");
        if (!plan.selectedTrackers.contains(trackerIdentity)) return;
        Entry entry = retainedTracker(trackerIdentity, "removeEntity tracker is not retained");
        requireEntityOwner(entry, entry.candidate, entry.key);
        requireEntry(entry, entry.candidate);
        requireRelation(publishedRange(entry.candidate));
        finishing.scoreboard.requireTrackingSession(plan.connections, plan.players.get(player).session);
        original.run();
        refreshProjection(entry);
    }

    /** Prepare the synchronous ChunkMap.move suffix before the movement packet acquires a lease.
     * Selection uses published facts and every possible settled chunk in the swept path. */
    public dev.phoenium.topology.LiveMovementFootprint prepareMovement(ServerPlayer player,
            dev.phoenium.topology.LiveMovementFootprint movement) {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("movement tracking preparation owns a region");
        var source = finishing.entityFacts.trackingCandidate(level, player);
        RangeProjection relation = publishedRange(source);
        var footprint = new LinkedHashSet<>(relation.footprint);
        footprint.add(movement.routingDestination());
        // S9-D12: while this world is uniformly routed, the admission authority is the whole world: every tracked
        // entity's footprint key (each names this world) is admitted whether or not it is listed, so the world-wide
        // selection below changes neither the closure nor the admission. The uniform closure is tried once, first.
        boolean uniformTried = false;
        if (RegionDirectory.sameWorld(footprint, world) && directory.uniformRoute(world) != null) {
            uniformTried = true;
            if (dispatcher.ensureUniformClosure(key(source))) return movement.withCallbacks(footprint);
        }
        List<Entry> entries;
        synchronized (this) { entries = index.values(); }
        refreshCandidates(entries);
        for (Entry entry : entries) {
            if (entry.entity == player) {
                for (ServerPlayer recipient : level.players()) {
                    var candidate = finishing.entityFacts.trackingCandidate(level, recipient);
                    if (entry.seenConnections.contains(recipient.connection)
                            || movementCandidate(key(candidate), relation.effectiveRange, movement)) footprint.add(key(candidate));
                }
            } else {
                boolean seen = entry.seenConnections.contains(player.connection);
                if (!seen && !movementCandidate(entry.key, maxVisibleRange(), movement)) continue;
                RangeProjection tracked = publishedRange(entry.candidate);
                if (seen || movementCandidate(entry.key, tracked.effectiveRange, movement)) footprint.addAll(tracked.footprint);
            }
        }
        // S9-D7: the movement destination comes from the admission, so its world is checked like ensureClosure's.
        if (!RegionDirectory.sameWorld(footprint, world) || uniformTried || !dispatcher.ensureUniformClosure(key(source)))
            dispatcher.ensureClosure(footprint);
        return movement.withCallbacks(footprint);
    }

    static boolean movementCandidate(RoutingKey entity, int range,
            dev.phoenium.topology.LiveMovementFootprint movement) {
        long radius = Math.max(1L, (range + 15L) / 16L + 1L);
        if (candidateChunk(entity, range, movement.routingDestination())) return true;
        for (var box : movement.sweptCollision())
            if (entity.world().equals(box.world())
                    && entity.x() >= box.minX() - radius && entity.x() <= box.maxX() + radius
                    && entity.z() >= box.minZ() - radius && entity.z() <= box.maxZ() + radius) return true;
        return false;
    }

    /** S9-D4: publishedRange's visible range is capped by the server view distance, and the candidate
     * radius of candidateChunk/movementCandidate never shrinks as the range grows. An entity whose
     * moving recipient has not seen it and that is outside this bound is never selected, so its
     * range projection is not built. Unlike before, such a far entity's projection no longer runs
     * (and cannot throw) inside an unrelated player's move. */
    private int maxVisibleRange() {
        return ((RegionalChunkMapAccess) level.getChunkSource().chunkMap).phoenium$getServerViewDistance() * 16;
    }

    /** Preserves ChunkMap.move's complete original suffix while replacing its world-wide tracker
     * scan with the exact retained seen/range candidate set prepared before entry. */
    public void movePlayer(ChunkMap map, ServerPlayer player, Runnable original) {
        if (activeMove.get() != null) throw new IllegalStateException("nested ChunkMap.move escaped its tracking footprint");
        if (OwnershipContext.current().isEmpty()) dispatcher.drainBeforeSelection();
        var connectionPlayers = new ArrayList<>(level.players());
        if (!connectionPlayers.contains(player)) connectionPlayers.add(player);
        FullFinishingScoreboard.TrackingCut cut = finishing.scoreboard.trackingCut(connectionPlayers);
        Map<ServerPlayer, PlayerReceipt> players = playerReceipts(cut);
        PlayerReceipt moving = requirePlayer(players, player);
        // S9-D17: (a listener-to-receipt map built here was never read)
        List<Entry> entries; long entriesAdditions; Verified standing;
        synchronized (this) { entries = index.values(); entriesAdditions = additions; standing = verified; }
        RangeProjection movingRelation = prepareRange(moving.candidate);
        // S9-D16: the footprint's parts in insertion order (a key or a key set); its set is built only where a world
        // is not uniformly routed, whose closure check needs its keys.
        List<Object> footprint = new ArrayList<>();
        footprint.add(movingRelation.footprint);
        Set<Object> selectedTrackers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ServerPlayer> selectedPlayers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Object playerTracker = null;
        int maxVisible = maxVisibleRange(); // S9-D16: the server view distance, read once per move
        // S9-D18: while every non-player entry is verified at the current publication and routed in the current view
        // (Verified), refreshCandidates leaves each one unchanged, so its key is current. The loop below can then
        // select only players, entries within maxVisible's candidate radius of the moving key (the cell index) and
        // entries whose viewers hold the moving listener (viewers). It visits exactly those, players first in index
        // order (the only entries refreshCandidates then refreshes, as over the whole list), and selects as before.
        // Only a uniformly routed world, whose closure reads no footprint order, takes this; others keep the list.
        List<Entry> live = null;
        // S9-D19: the same checks in the same order, each counted by the path it leaves on (moveCounts)
        int path = standing == null ? MOVE_UNVERIFIED : standing.additions != entriesAdditions ? MOVE_ADDED
            : standing.stamp != finishing.entityFacts.nonPlayerPublications() ? MOVE_PUBLISHED
            : standing.view != directory.snapshot() || directory.uniformRoute(world) == null ? MOVE_VIEW : MOVE_FAST;
        if (path == MOVE_FAST) {
            live = nearbyEntries(entries, entriesAdditions, moving, maxVisible, player);
            if (live == null) path = MOVE_INDEX;
        }
        MOVE_COUNTS[path].increment();
        if (live == null) {
            live = new ArrayList<>(entries.size());
            boolean skipped = false;
            for (Entry entry : entries) {
                if (!removalInProgress(entry.entity, player)) live.add(entry);
                else if (!(entry.entity instanceof net.minecraft.world.entity.player.Player)) skipped = true;
            }
            Pass pass = refreshCandidates(live);
            // every non-player entry of the list was verified (none was skipped as in removal)
            if (pass != null && !skipped) {
                synchronized (this) { verified = new Verified(entriesAdditions, pass.stamp, pass.view); }
                MOVE_COUNTS[MOVE_RECORDED].increment();
            }
        } else refreshCandidates(live);
        for (Entry entry : live) {
            if (entry.entity == player) {
                synchronized (this) {
                    entry.candidate = moving.candidate;
                    entry.key = moving.key;
                    entry.owner = route(moving.key).region();
                    entry.routedView = null;
                    index.update(entry.id, entry.key, entry.owner);
                }
                playerTracker = entry.tracker;
                int currentRange = movingRelation.effectiveRange;
                for (ServerPlayer candidate : level.players()) {
                    PlayerReceipt receipt = requirePlayer(players, candidate);
                    if (entry.seenConnections.contains(receipt.session.listener())
                            || candidateChunk(entry.key, currentRange, receipt.key)) {
                        selectedPlayers.add(candidate); footprint.add(receipt.key);
                    }
                }
            } else {
                boolean seen = entry.seenConnections.contains(moving.session.listener());
                if (!seen && !candidateChunk(entry.key, maxVisible, moving.key)) continue;
                RangeProjection tracked = publishedRange(entry.candidate);
                if (seen || candidateChunk(entry.key, tracked.effectiveRange, moving.key)) {
                    selectedTrackers.add(entry.tracker); footprint.add(tracked.footprint);
                }
            }
        }
        // S9-D7: the footprint's first key is the moving player's; in a uniformly routed world its check is every key's.
        if (OwnershipContext.current().isPresent()) {
            if (!dispatcher.requireUniformClosure(moving.key)) dispatcher.requireCurrentClosure(footprintKeys(footprint));
        } else if (!dispatcher.ensureUniformClosure(moving.key)) dispatcher.ensureClosure(footprintKeys(footprint));
        MovePlan plan = new MovePlan(player, playerTracker, movingRelation, cut, players,
            selectedPlayers, selectedTrackers, this, new ArrayList<>());
        Runnable body = () -> {
            dispatcher.requireCurrentOwner(moving.key);
            requireRelation(movingRelation);
            activeMove.set(plan);
            // S9-D5: TrackingMoveMarks lets the original loop skip unselected trackers' callbacks.
            TrackingMoveMarks.mark(plan.selectedTrackers, player, plan);
            try {
                original.run();
                // S9-D14: each admitted tracker's refresh, after its original updatePlayer as before, from the
                // relation its admission built (nothing in the move changes a tracked entity's passengers).
                for (Updated updated : plan.updated) refreshProjection(updated.entry, updated.relation);
            } finally {
                TrackingMoveMarks.unmark(plan.selectedTrackers, player, plan);
                activeMove.set(null);
            }
        };
        if (OwnershipContext.current().isPresent()) body.run();
        else dispatcher.executeAndDrain(moving.key, RegionalServiceKind.TRACKING_UPDATE, ignored -> body.run());
    }

    /** S9-D18: movePlayer's entries while Verified stands (see there): the players of {@code entries} in its order, then
     * each entry in the cells within maxVisible's candidate radius of the moving key, then each indexed entry seen by
     * the moving listener; each once, none in removal. */
    private List<Entry> nearbyEntries(List<Entry> entries, long entriesAdditions, PlayerReceipt moving, int maxVisible,
                                      ServerPlayer player) {
        List<Entry> result = new ArrayList<>();
        Set<Entry> added = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        int radius = (int) Math.max(1L, (maxVisible + 15L) / 16L + 1L);
        Object listener = moving.session.listener();
        synchronized (this) {
            // the cells and viewers describe the index now; they are the move's list only while it is unchanged
            if (index.values() != entries || additions != entriesAdditions) return null;
            for (Entry entry : playerEntries(entries))
                if (!removalInProgress(entry.entity, player) && added.add(entry)) result.add(entry);
            index.forEachNear(moving.key.x(), moving.key.z(), radius, entry -> {
                if (!removalInProgress(entry.entity, player) && added.add(entry)) result.add(entry);
            });
            Set<Entry> seen = viewers.get(listener);
            if (seen != null) for (Entry entry : seen) {
                if (!entry.seenConnections.contains(listener) || index.value(entry.id) != entry)
                    throw new IllegalStateException("tracker viewer index diverged " + entry.id);
                if (!removalInProgress(entry.entity, player) && added.add(entry)) result.add(entry);
            }
        }
        return result;
    }
    /** Called with this service's lock held. */
    private List<Entry> playerEntries(List<Entry> entries) {
        if (playerEntriesOf != entries) {
            List<Entry> players = new ArrayList<>();
            for (Entry entry : entries) if (entry.entity instanceof net.minecraft.world.entity.player.Player) players.add(entry);
            playerEntries = players; playerEntriesOf = entries;
        }
        return playerEntries;
    }
    /** S9-D18: the one write path of an entry's seenConnections. Its viewers registration follows: the set while
     * the entry is indexed, nothing once it is not. */
    private void setSeen(Entry entry, Set<Object> next) {
        synchronized (this) {
            entry.seenConnections = next;
            register(entry, index.value(entry.id) == entry ? next : Set.of());
        }
    }
    /** S9-D18: after an entry left the index. */
    private void forgetViewers(Entry entry) {
        synchronized (this) { register(entry, Set.of()); }
    }
    private void register(Entry entry, Set<Object> next) {
        Set<Object> previous = entry.registered;
        if (previous == next) return;
        for (Object listener : previous) if (!next.contains(listener)) {
            Set<Entry> seen = viewers.get(listener);
            if (seen == null || !seen.remove(entry)) throw new IllegalStateException("tracker viewer index lost " + entry.id);
            if (seen.isEmpty()) viewers.remove(listener);
        }
        for (Object listener : next) if (!previous.contains(listener))
            viewers.computeIfAbsent(listener, ignored -> java.util.Collections.newSetFromMap(new IdentityHashMap<>())).add(entry);
        entry.registered = next;
    }

    /** S9-D16: a move footprint's keys, in the order its parts added them. */
    @SuppressWarnings("unchecked")
    private static LinkedHashSet<RoutingKey> footprintKeys(List<Object> parts) {
        LinkedHashSet<RoutingKey> keys = new LinkedHashSet<>();
        for (Object part : parts) {
            if (part instanceof RoutingKey key) keys.add(key);
            else keys.addAll((Set<RoutingKey>) part);
        }
        return keys;
    }

    public void movedPlayerUpdates(Object trackerIdentity, List<ServerPlayer> originalPlayers) {
        MovePlan plan = Objects.requireNonNull(activeMove.get(), "unprepared move updatePlayers call");
        if (trackerIdentity != plan.playerTracker)
            throw new IllegalStateException("move updatePlayers names a foreign player tracker");
        requireRelation(plan.playerRelation);
        for (ServerPlayer player : originalPlayers) if (plan.selectedPlayers.contains(player)) {
            PlayerReceipt receipt = requirePlayer(plan.players, player);
            requirePlayer(receipt, plan.connections);
            access(trackerIdentity).phoenium$invokeUpdatePlayer(player);
        }
        Entry entry = retainedTracker(trackerIdentity, "move player tracker is not retained");
        refreshProjection(entry);
    }

    /**
     * S9-D14: the service whose move plan {@code player} carries, when that service tracks {@code level}. A plan is
     * on the player only while its service's movePlayer runs the original ChunkMap.move of its own level, so this is
     * the service a lookup by that level finds; null otherwise (the caller then looks it up).
     */
    public static RegionalTrackingService movingService(ServerPlayer player, Object level) {
        return player instanceof TrackingMoveMarks.Mover mover && mover.phoenium$activeMovePlan() instanceof MovePlan plan
            && plan.service.level == level ? plan.service : null;
    }

    public void movedPlayerUpdate(Object trackerIdentity, ServerPlayer player, Runnable original) {
        Updated admitted = admitMove(trackerIdentity, player);
        if (admitted == null) return;
        original.run();
        refreshProjection(admitted.entry, admitted.relation);
    }
    /** S9-D14: movedPlayerUpdate's checks; true when the original updatePlayer runs, its refresh then following the
     * original move. */
    public boolean admitMovedPlayer(Object trackerIdentity, ServerPlayer player) {
        Updated admitted = admitMove(trackerIdentity, player);
        if (admitted == null) return false;
        activeMove.get().updated.add(admitted);
        return true;
    }
    private record Updated(Entry entry, RangeProjection relation) { }
    private Updated admitMove(Object trackerIdentity, ServerPlayer player) {
        MovePlan plan = Objects.requireNonNull(activeMove.get(), "unprepared move updatePlayer call");
        if (player != plan.player) throw new IllegalStateException("move player alias changed");
        if (!plan.selectedTrackers.contains(trackerIdentity)) return null;
        Entry entry = retainedTracker(trackerIdentity, "move tracker is not retained");
        // The tracked entity can enter removal after selection; its own removeEntity broadcast owns the exit.
        if (removalInProgress(entry.entity, player)) return null;
        requireEntityOwner(entry, entry.candidate, entry.key);
        requireEntry(entry, entry.candidate);
        // S9-D14: the relation is built from the current facts (its source route checked) right after requireEntry
        // validated that source, so only its closure is checked; the same relation then gives the refreshed range.
        RangeProjection relation = publishedRange(entry.candidate);
        if (!dispatcher.requireUniformClosure(key(relation.source))) dispatcher.requireCurrentClosure(relation.footprint);
        finishing.scoreboard.requireTrackingSession(plan.connections, plan.players.get(player).session);
        return new Updated(entry, relation);
    }

    public synchronized MigrationPlan planMigration(RegionDirectory.View proposed) {
        if (OwnershipContext.current().isPresent() || cycling)
            throw new IllegalStateException("tracker migration is not owner-free");
        var plan = index.plan(proposed.generation().value(), key -> resolve(proposed, key).region());
        return new MigrationPlan(plan.generation(), plan.owners());
    }

    public synchronized void installMigration(MigrationPlan plan, RegionDirectory.View published) {
        if (plan.generation != published.generation().value())
            throw new IllegalStateException("tracker migration plan changed");
        migrations += index.install(new RegionalTrackingPartitions.Plan(plan.generation, plan.owners));
        for (Entry entry : index.values()) {
            entry.owner = Objects.requireNonNull(plan.owners.get(entry.id));
            entry.routedView = null;
        }
    }

    public synchronized Measurements measurements() {
        return new Measurements(index.partitionCount(), index.size(), cycles, updates, migrations,
            sourceReconciliations, cycling);
    }

    private void requireEntry(Entry entry, FullEntityFacts.Candidate candidate) {
        if (entry.entity != candidate.alias() || entry.candidate.fact().incarnation() != candidate.fact().incarnation()
                || access(entry.tracker).phoenium$getEntity() != entry.entity
                || !finishing.entityFacts.validTrackingCandidate(candidate))
            throw new IllegalStateException("retained tracker identity or entity incarnation changed");
    }
    /** setRemoved retires the object binding before ChunkMap.removeEntity drains the scheduler, so a
     * queued move that runs inside that drain sees a still-indexed tracker whose entity is already
     * removed. Vanilla never interleaves there; the removal broadcast owns that tracker's exit. */
    static boolean removalInProgress(Entity tracked, ServerPlayer moving) {
        return tracked != moving && tracked.isRemoved();
    }

    /** requireEntityOwner for a retained entry's entity, reusing the entry's owner answer while it stands. */
    private void requireEntityOwner(Entry entry, FullEntityFacts.Candidate candidate, RoutingKey key) {
        var answer = objects.requireCurrentOwner(candidate.fact().objectId(), route(key).region(), entry.ownerAnswer);
        if (answer == null) throw new IllegalStateException("tracked entity lacks current object ownership");
        entry.ownerAnswer = answer;
        dispatcher.requireCurrentOwner(key);
    }
    private void requireEntityOwner(FullEntityFacts.Candidate candidate, RoutingKey key) {
        objects.requireCurrentOwnerIfPresent(candidate.fact().objectId(), route(key).region()).orElseThrow(
            () -> new IllegalStateException("tracked entity lacks current object ownership"));
        dispatcher.requireCurrentOwner(key);
    }
    private void requirePlayer(PlayerReceipt receipt, FullFinishingScoreboard.TrackingCut cut) {
        requireEntityOwner(receipt.candidate, receipt.key);
        if (!finishing.entityFacts.validTrackingCandidate(receipt.candidate)) {
            logRouteHandoff(receipt, staleRouteFailureLogged, "REJECTED");
            throw new IllegalStateException("tracking player route became stale");
        }
        finishing.scoreboard.requireTrackingSession(cut, receipt.session);
    }
    private boolean currentTickPlayer(PlayerReceipt receipt, FullFinishingScoreboard.TrackingCut cut,
                                      Set<ServerPlayer> deferred) {
        // A route revision is an exact publication receipt, including same-chunk movement.
        // The old route never grants access after it changes. Do not reacquire a different
        // owner inside this callback; the next bounded tracking cycle admits a fresh receipt.
        if (!currentTickReceipt(receipt.session.player(), deferred,
                () -> finishing.entityFacts.validTrackingCandidate(receipt.candidate))) {
            logRouteHandoff(receipt, routeHandoffLogged, "DEFERRED");
            return false;
        }
        requireEntityOwner(receipt.candidate, receipt.key);
        if (!currentTickReceipt(receipt.session.player(), deferred,
                () -> finishing.entityFacts.validTrackingCandidate(receipt.candidate))) {
            logRouteHandoff(receipt, routeHandoffLogged, "DEFERRED");
            return false;
        }
        finishing.scoreboard.requireTrackingSession(cut, receipt.session);
        return true;
    }
    private void logRouteHandoff(PlayerReceipt receipt, AtomicBoolean once, String action) {
        if (!Boolean.getBoolean("phoenium.test.diagnostics") || !once.compareAndSet(false, true)) return;
        FullEntityFacts.Candidate current;
        try { current = finishing.entityFacts.trackingCandidate(level, receipt.session.player()); }
        catch (dev.phoenium.chunk.ChunkProtocolException departed) { current = null; }
        LOGGER.info("PHOENIUM_TRACKING_ROUTE_HANDOFF player={} from={} fromRevision={} to={} toRevision={} "
                + "sameFact={} topologyGeneration={} action={}", receipt.candidate.fact().uuid(), receipt.key,
            receipt.candidate.routingRevision(), current == null ? "NONE" : key(current),
            current == null ? "NONE" : current.routingRevision(),
            current != null && current.fact() == receipt.candidate.fact(), directory.snapshot().generation().value(),
            current == null ? "RETIRED" : action);
    }
    static <P> boolean currentTickReceipt(P player, Set<P> deferred, BooleanSupplier valid) {
        if (deferred.contains(player)) return false;
        if (valid.getAsBoolean()) return true;
        deferred.add(player);
        return false;
    }
    private Map<ServerPlayer, PlayerReceipt> playerReceipts(FullFinishingScoreboard.TrackingCut cut) {
        Map<ServerPlayer, PlayerReceipt> result = new IdentityHashMap<>();
        for (FullFinishingScoreboard.TrackingSession session : cut.sessions()) {
            FullEntityFacts.Candidate candidate = finishing.entityFacts.trackingCandidate(level, session.player());
            result.put(session.player(), new PlayerReceipt(session, candidate, key(candidate)));
        }
        return result;
    }
    private void refreshCandidate(Entry entry) {
        FullEntityFacts.Candidate current = finishing.entityFacts.refreshTrackingCandidate(entry.candidate);
        // S9-D4: the view is read before route() resolves, so a stamp never names a view newer than the
        // one the owner came from. Every other key/owner write clears the stamp.
        RegionDirectory.View view = directory.snapshot();
        synchronized (this) {
            if (current == entry.candidate && entry.routedView == view) return;
            RoutingKey key = key(current);
            entry.candidate = current;
            entry.key = key;
            entry.owner = route(key).region();
            index.update(entry.id, key, entry.owner);
            entry.routedView = view;
        }
    }
    /** S9-D18: the publication and view every non-player entry of a pass is verified at and routed in. */
    private record Pass(long stamp, RegionDirectory.View view) { }
    /** S9-D5: refreshCandidate for each entry in order, with one facts lock, one directory snapshot and one
     * monitor entry. On a refresh failure the entries before it are applied and the failure is rethrown.
     * S9-D18: returns the pass, or null when the view changed during it. */
    private Pass refreshCandidates(List<Entry> entries) {
        if (entries.isEmpty()) return new Pass(finishing.entityFacts.nonPlayerPublications(), directory.snapshot());
        // S9-D7: read before the refresh, so a stamp never claims a later publication. Player candidates are
        // always looked up: each movement publishes its player.
        long stamp = finishing.entityFacts.nonPlayerPublications();
        // S9-D16: a non-player entry verified at this stamp and routed in the current view is a no-op below (its
        // retained candidate is written back, equal to its own, and its route is current), so only the others are
        // passed on. refreshTrackingCandidates' per-world checks run first, as it makes them before any lookup; it
        // repeats them for the entries passed on. S9-D17: every retained candidate is this tracker's level's (each
        // comes from trackingCandidate(level, ...), which requires it, or from a refresh, which keeps its world), so
        // those checks are this one check.
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.ENTITY_REGISTRY);
        RegionDirectory.View routedIn = directory.snapshot();
        List<Entry> changing = null;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            boolean noop = entry.verifiedAt == stamp && entry.routedView == routedIn
                && !(entry.entity instanceof net.minecraft.world.entity.player.Player);
            if (changing == null) {
                if (!noop) continue;
                changing = new ArrayList<>(entries.size());
                changing.addAll(entries.subList(0, i));
            } else if (!noop) changing.add(entry);
        }
        if (changing != null) {
            if (changing.isEmpty()) return new Pass(stamp, routedIn);
            entries = changing;
        }
        List<FullEntityFacts.Candidate> retained = new ArrayList<>(entries.size());
        boolean[] unchanged = new boolean[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            retained.add(entry.candidate);
            unchanged[i] = entry.verifiedAt == stamp && !(entry.entity instanceof net.minecraft.world.entity.player.Player);
        }
        FullEntityFacts.Candidate[] current = new FullEntityFacts.Candidate[entries.size()];
        RuntimeException failure = null;
        try { finishing.entityFacts.refreshTrackingCandidates(retained, current, unchanged); }
        catch (RuntimeException refreshFailure) { failure = refreshFailure; }
        RegionDirectory.View view = directory.snapshot();
        synchronized (this) {
            for (int i = 0; i < current.length && current[i] != null; i++) {
                Entry entry = entries.get(i);
                if (!(entry.entity instanceof net.minecraft.world.entity.player.Player)) entry.verifiedAt = stamp;
                if (current[i] == entry.candidate && entry.routedView == view) continue;
                RoutingKey key = key(current[i]);
                entry.candidate = current[i];
                entry.key = key;
                entry.owner = route(key).region();
                index.update(entry.id, key, entry.owner);
                entry.routedView = view;
            }
        }
        if (failure != null) throw failure;
        for (FullEntityFacts.Candidate refreshed : current) if (refreshed == null) return null;
        return routedIn == view ? new Pass(stamp, view) : null;
    }
    /** Exact retained record for a live tracker callback (S9-D2: identity index instead of a copied O(n) scan). */
    private Entry retainedTracker(Object trackerIdentity, String missing) {
        Entry entry;
        synchronized (this) { entry = index.valueByIdentity(trackerIdentity); }
        if (entry == null) throw new IllegalStateException(missing);
        if (entry.tracker != trackerIdentity) throw new IllegalStateException("tracker identity index diverged " + entry.id);
        return entry;
    }

    private void refreshProjection(Entry entry) { refreshProjection(entry, publishedRange(entry.candidate)); }
    private void refreshProjection(Entry entry, RangeProjection relation) {
        entry.effectiveRange = relation.effectiveRange;
        // S9-D10: seenConnections is only ever asked contains(); a retained copy with the same members is the same
        // answer, so a move that left the tracker's seenBy unchanged (nearly every move) keeps it uncopied.
        var live = access(entry.tracker).phoenium$getSeenBy();
        Set<Object> retained = entry.seenConnections;
        if (retained.size() != live.size() || !retained.containsAll(live)) setSeen(entry, Set.copyOf(live));
    }
    private void reorderSource(Int2ObjectMap<Object> source) {
        List<RegionalTrackingPartitions.Identity> order = new ArrayList<>(source.size());
        for (Int2ObjectMap.Entry<Object> item : source.int2ObjectEntrySet())
            order.add(new RegionalTrackingPartitions.Identity(item.getIntKey(), item.getValue()));
        index.reorder(order);
    }
    private RangeProjection prepareRange(FullEntityFacts.Candidate source) {
        RangeProjection projection = publishedRange(source);
        RoutingKey center = key(source);
        if (OwnershipContext.current().isPresent()) {
            if (!dispatcher.requireUniformClosure(center)) dispatcher.requireCurrentClosure(projection.footprint);
        } else if (!dispatcher.ensureUniformClosure(center)) dispatcher.ensureClosure(projection.footprint);
        return projection;
    }
    private RangeProjection publishedRange(FullEntityFacts.Candidate source) {
        List<FullEntityFacts.Candidate> passengers = finishing.entityFacts.trackingPassengers(source);
        if (passengers.isEmpty()) {
            // S9-D5: without passengers the footprint is the source key and the range is the source type's own.
            int visible = Math.min(level.getServer().getScaledTrackingDistance(source.fact().type().clientTrackingRange() * 16),
                ((RegionalChunkMapAccess) level.getChunkSource().chunkMap).phoenium$getServerViewDistance() * 16);
            return new RangeProjection(source, visible, Set.of(key(source)), List.of());
        }
        LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>();
        footprint.add(key(source));
        int effective = source.fact().type().clientTrackingRange() * 16;
        for (FullEntityFacts.Candidate passenger : passengers) {
            footprint.add(key(passenger));
            effective = Math.max(effective, passenger.fact().type().clientTrackingRange() * 16);
        }
        // Native updatePlayer caps every requested player distance by this current server
        // distance, before any entity predicate. Keep seen recipients separately for removal.
        int visible = Math.min(level.getServer().getScaledTrackingDistance(effective),
            ((RegionalChunkMapAccess) level.getChunkSource().chunkMap).phoenium$getServerViewDistance() * 16);
        return new RangeProjection(source, visible,
            Set.copyOf(footprint), passengers);
    }
    /**
     * S9-D14: requireEntry(entry, candidate) then requireRelation(relation). When the relation's source is that
     * candidate, requireEntry has just validated it on this thread, so the relation check goes on to its closure and
     * passenger graph (whose check still requires the source's current fact and route).
     */
    private void requireEntryRelation(Entry entry, FullEntityFacts.Candidate candidate, RangeProjection relation) {
        requireEntry(entry, candidate);
        if (relation.source != candidate) { requireRelation(relation); return; }
        if (!dispatcher.requireUniformClosure(key(relation.source))) dispatcher.requireCurrentClosure(relation.footprint);
        if (!finishing.entityFacts.validTrackingPassengers(relation.source, relation.passengers))
            throw new IllegalStateException("tracking passenger relation changed");
    }
    private void requireRelation(RangeProjection projection) {
        // S9-D14: every footprint key names this world (the source's and its passengers'); while it is uniformly
        // routed, the source key's owner check is each key's (S9-D7).
        if (!dispatcher.requireUniformClosure(key(projection.source))) dispatcher.requireCurrentClosure(projection.footprint);
        if (!finishing.entityFacts.validTrackingCandidate(projection.source))
            throw new IllegalStateException("tracking relation source identity changed");
        if (!finishing.entityFacts.validTrackingPassengers(projection.source, projection.passengers))
            throw new IllegalStateException("tracking passenger relation changed");
    }
    private static PlayerReceipt requirePlayer(Map<ServerPlayer, PlayerReceipt> players, ServerPlayer player) {
        return Objects.requireNonNull(players.get(player), "player is absent from retained tracking connection cut");
    }
    private RoutingKey key(FullEntityFacts.Candidate candidate) {
        return new RoutingKey(world, candidate.position().x(), candidate.position().z());
    }
    /** The retained receipts of {@code from}, in order, that already see the entity or are in its candidate chunks. */
    private List<PlayerReceipt> trackingCandidates(List<ServerPlayer> from, Map<ServerPlayer, PlayerReceipt> players,
                                                   Preview preview) {
        List<PlayerReceipt> selected = new ArrayList<>();
        for (ServerPlayer value : from) {
            PlayerReceipt player = players.get(value);
            if (player != null && (seesPlayer(preview, player) || candidate(preview, player.key)))
                selected.add(player);
        }
        return selected;
    }
    /** S9-D16: whether the previewed tracker's viewers held {@code player}'s session. A cut's sessions have distinct
     * players (trackingCut rejects a repeated one) and each session's listener is its player's live connection, so
     * a receipt's player was a viewer exactly when its listener was (the former per-entity viewer-player copy). */
    private static boolean seesPlayer(Preview preview, PlayerReceipt player) {
        return preview.seenConnections.contains(player.session.listener());
    }
    private boolean candidate(Preview entity, RoutingKey player) {
        return candidateChunk(entity.key, entity.effectiveRange, player);
    }
    static boolean candidateChunk(RoutingKey entity, int effectiveRange, RoutingKey player) {
        long radius = Math.max(1L, (effectiveRange + 15L) / 16L + 1L);
        return Math.abs((long) entity.x() - player.x()) <= radius
            && Math.abs((long) entity.z() - player.z()) <= radius;
    }
    private RegionRoute route(RoutingKey key) {
        return directory.resolve(key).orElseThrow(() -> new IllegalStateException("unresolved tracker key " + key));
    }
    private static RegionRoute resolve(RegionDirectory.View view, RoutingKey key) {
        RegionRoute route = view.routes().get(key);
        if (route != null) return route;
        if (view.hasExplicitRoutesForWorld(key.world())) throw new IllegalStateException("unresolved tracker migration key " + key);
        route = view.worldDefaults().get(key.world());
        if (route == null) throw new IllegalStateException("unresolved tracker migration key " + key);
        return route;
    }
    private static RegionalTrackedEntityAccess access(Object tracker) { return (RegionalTrackedEntityAccess) tracker; }

    private static final class Entry {
        final int id; final Object tracker; final Entity entity; FullEntityFacts.Candidate candidate; RoutingKey key; RegionId owner;
        int effectiveRange = -1;
        Set<Object> seenConnections = Set.of();
        /** S9-D18: the listeners this entry is registered under in viewers (see setSeen). */
        Set<Object> registered = Set.of();
        /** Directory view under which {@link #owner} was last resolved by refreshCandidate; null = re-resolve. */
        RegionDirectory.View routedView;
        /** S9-D7: nonPlayerPublications read before the last refresh that validated {@link #candidate}. */
        long verifiedAt = Long.MIN_VALUE;
        /** S9-D17: the entity's last current-owner answer (ObjectOwnershipRegistry.requireCurrentOwner). */
        ObjectOwnershipRegistry.OwnerAnswer ownerAnswer;
        Entry(int id, Object tracker, Entity entity, FullEntityFacts.Candidate candidate, RoutingKey key, RegionId owner) {
            this.id = id; this.tracker = tracker; this.entity = entity; this.candidate = candidate; this.key = key; this.owner = owner;
        }
    }
    private record Preview(RoutingKey key, SectionPos section, boolean sectionChanged,
                           int effectiveRange, Set<Object> seenConnections,
                           FullEntityFacts.Candidate candidate, RangeProjection relation) { }
    private record RangeProjection(FullEntityFacts.Candidate source, int effectiveRange,
                                   Set<RoutingKey> footprint,
                                   List<FullEntityFacts.Candidate> passengers) {
        RangeProjection { footprint = Set.copyOf(footprint); passengers = List.copyOf(passengers); }
    }
    private record PlayerReceipt(FullFinishingScoreboard.TrackingSession session,
                                 FullEntityFacts.Candidate candidate, RoutingKey key) { }
    private record Source(int id, Object tracker, Entity entity, FullEntityFacts.Candidate candidate,
                          RoutingKey key, RegionId owner) { }
    private record AddPlan(Entity entity, FullEntityFacts.Candidate source, RoutingKey sourceKey,
                           RangeProjection sourceRelation,
                           FullFinishingScoreboard.TrackingCut connections,
                           Map<ServerPlayer, PlayerReceipt> players,
                           Set<ServerPlayer> selectedPlayers, Set<Object> selectedTrackers) {
        AddPlan {
            players = Map.copyOf(players);
            selectedPlayers = Set.copyOf(selectedPlayers);
            selectedTrackers = Set.copyOf(selectedTrackers);
        }
    }
    private record RemovePlan(Entity entity, RangeProjection sourceRelation,
                              FullFinishingScoreboard.TrackingCut connections,
                              Map<ServerPlayer, PlayerReceipt> players, Set<Object> selectedTrackers) {
        RemovePlan { players = Map.copyOf(players); selectedTrackers = Set.copyOf(selectedTrackers); }
    }
    private record MovePlan(ServerPlayer player, Object playerTracker, RangeProjection playerRelation,
                            FullFinishingScoreboard.TrackingCut connections,
                            Map<ServerPlayer, PlayerReceipt> players,
                            Set<ServerPlayer> selectedPlayers, Set<Object> selectedTrackers,
                            RegionalTrackingService service, List<Updated> updated) {
        MovePlan {
            players = Map.copyOf(players);
            // S9-D16: the move's own identity sets, unchanged after the plan is built, without copies. A cut holds one
            // player per identity, and a tracker index one tracker per entity id, so identity is equality here.
            selectedPlayers = java.util.Collections.unmodifiableSet(selectedPlayers);
            selectedTrackers = java.util.Collections.unmodifiableSet(selectedTrackers);
        }
    }
    public record MigrationPlan(long generation, Map<Integer, RegionId> owners) {
        public MigrationPlan { owners = Map.copyOf(owners); }
    }
    public record Measurements(int ownerPartitions, int tracked, long cycles, long updates,
                               long migrations, long sourceReconciliations, boolean cycling) { }
}
