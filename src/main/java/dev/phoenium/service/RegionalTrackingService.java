package dev.phoenium.service;

import dev.phoenium.core.WorldId;
import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.chunk.FullFinishingScoreboard;
import dev.phoenium.chunk.FullFinishingServices;
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
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Owner-partitioned index of the actual ChunkMap tracker objects. The source map remains the
 * named TRACKER registry; this service retains its identities and original iteration order while
 * executing each reached update under the tracked entity's current owner.
 */
public final class RegionalTrackingService {
    private final WorldId world;
    private final ServerLevel level;
    private final FullFinishingServices finishing;
    private final RegionDirectory directory;
    private final RegionalServiceDispatcher dispatcher;
    private final ObjectOwnershipRegistry objects;
    private final int maximumTrackingRange;
    private final RegionalTrackingPartitions<Entry> index = new RegionalTrackingPartitions<>();
    private final ThreadLocal<AddPlan> activeAdd = new ThreadLocal<>();
    private final ThreadLocal<RemovePlan> activeRemove = new ThreadLocal<>();
    private final ThreadLocal<MovePlan> activeMove = new ThreadLocal<>();
    private boolean cycling;
    private long cycles, updates, migrations, sourceReconciliations;

    public RegionalTrackingService(WorldId world, ServerLevel level, RegionDirectory directory,
                                   RegionalServiceDispatcher dispatcher,
                                   ObjectOwnershipRegistry objects) {
        this.world = Objects.requireNonNull(world);
        this.level = Objects.requireNonNull(level);
        this.finishing = FullFinishingServices.forServer(level.getServer());
        this.directory = Objects.requireNonNull(directory);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.objects = Objects.requireNonNull(objects);
        int maximum = 0;
        for (var type : BuiltInRegistries.ENTITY_TYPE) maximum = Math.max(maximum, type.clientTrackingRange() * 16);
        this.maximumTrackingRange = level.getServer().getScaledTrackingDistance(maximum);
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
                }
                next.add(new RegionalTrackingPartitions.Item<>(sourceEntry.id, sourceEntry.tracker, retained,
                    sourceEntry.key, sourceEntry.owner));
            }
            index.replaceAll(next); sourceReconciliations++;
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
        int effectiveRange = access(tracker).phoenium$invokeEffectiveRange();
        Set<Object> seenConnections = Set.copyOf(access(tracker).phoenium$getSeenBy());
        synchronized (this) {
            if (cycling) throw new IllegalStateException("tracker source changed during regional cycle");
            Entry prior = index.value(id);
            if (prior != null && prior.tracker != tracker)
                throw new IllegalStateException("tracker insertion displaced a retained identity " + id);
            Entry retained = prior == null ? new Entry(id, tracker, entity, candidate, key, route(key).region()) : prior;
            retained.candidate = candidate; retained.key = key; retained.owner = route(key).region();
            retained.effectiveRange = effectiveRange;
            retained.seenConnections = seenConnections;
            index.put(new RegionalTrackingPartitions.Item<>(id, tracker, retained, key, retained.owner));
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
            reorderSource(source);
            sourceReconciliations++;
        }
    }

    public void tick(ChunkMap map, List<ServerPlayer> activePlayers, List<ServerPlayer> levelPlayers) {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("regional tracker entered with owner authority");
        List<Entry> entries;
        synchronized (this) {
            if (cycling) throw new IllegalStateException("nested regional tracker cycle");
            entries = index.values();
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
        Map<Entry, Preview> previews = new IdentityHashMap<>();
        for (Entry entry : entries) {
            FullEntityFacts.Candidate candidate = finishing.entityFacts.trackingCandidate(level, entry.entity);
            RoutingKey key = key(candidate);
            Preview[] captured = new Preview[1];
            dispatcher.executeAndDrain(key, RegionalServiceKind.TRACKING_UPDATE, ignored -> {
                requireEntityOwner(candidate, key);
                requireEntry(entry, candidate);
                RegionalTrackedEntityAccess tracker = access(entry.tracker);
                SectionPos now = SectionPos.of(entry.entity);
                Set<ServerPlayer> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                Set<Object> seenConnections = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                tracker.phoenium$getSeenBy().forEach(connection -> {
                    PlayerReceipt receipt = byListener.get(connection);
                    if (receipt == null)
                        throw new IllegalStateException("tracked connection is absent from the retained live session cut");
                    seen.add(receipt.session.player());
                    seenConnections.add(connection);
                });
                captured[0] = new Preview(key, now, !now.equals(tracker.phoenium$getLastSectionPos()),
                    tracker.phoenium$invokeEffectiveRange(), Set.copyOf(seen), Set.copyOf(seenConnections), candidate);
            });
            previews.put(entry, Objects.requireNonNull(captured[0]));
            synchronized (this) {
                entry.candidate = candidate;
                entry.key = key;
                entry.owner = route(key).region();
                entry.effectiveRange = captured[0].effectiveRange;
                entry.seenConnections = captured[0].seenConnections;
                index.update(entry.id, key, entry.owner);
            }
        }

        for (Entry entry : entries) {
            Preview preview = previews.get(entry);
            LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>();
            footprint.add(preview.key);
            for (ServerPlayer player : levelPlayers) {
                PlayerReceipt receipt = requirePlayer(players, player);
                if (preview.seen.contains(player) || candidate(preview, receipt.key)) footprint.add(receipt.key);
            }
            dispatcher.ensureClosure(footprint);
        }

        synchronized (this) { cycling = true; cycles++; }
        try {
            RegionalChunkMapAccess mapAccess = (RegionalChunkMapAccess) map;
            for (ServerPlayer player : activePlayers) {
                PlayerReceipt receipt = requirePlayer(players, player);
                RoutingKey key = receipt.key;
                dispatcher.executeAndDrain(key, RegionalServiceKind.TRACKING_UPDATE, ignored -> {
                    requirePlayer(receipt, connectionCut);
                    mapAccess.phoenium$invokeUpdateChunkTracking(player);
                });
            }

            List<ServerPlayer> moved = new ArrayList<>();
            for (Entry entry : entries) {
                Preview preview = previews.get(entry);
                List<PlayerReceipt> candidates = levelPlayers.stream().map(players::get)
                    .filter(Objects::nonNull)
                    .filter(player -> preview.seen.contains(player.session.player()) || candidate(preview, player.key)).toList();
                dispatcher.executeAndDrain(preview.key, RegionalServiceKind.TRACKING_UPDATE, ignored -> {
                    requireEntityOwner(preview.candidate, preview.key);
                    requireEntry(entry, preview.candidate);
                    RegionalTrackedEntityAccess tracker = access(entry.tracker);
                    if (preview.sectionChanged) {
                        for (PlayerReceipt player : candidates) {
                            requirePlayer(player, connectionCut);
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
                });
            }
            if (!moved.isEmpty()) for (Entry entry : entries) {
                Preview preview = previews.get(entry);
                List<PlayerReceipt> candidates = moved.stream().map(players::get)
                    .filter(Objects::nonNull)
                    .filter(player -> preview.seen.contains(player.session.player()) || candidate(preview, player.key)).toList();
                dispatcher.executeAndDrain(preview.key, RegionalServiceKind.TRACKING_PAIR, ignored -> {
                    requireEntityOwner(preview.candidate, preview.key);
                    requireEntry(entry, preview.candidate);
                    for (PlayerReceipt player : candidates) {
                        requirePlayer(player, connectionCut);
                        access(entry.tracker).phoenium$invokeUpdatePlayer(player.session.player());
                    }
                    refreshProjection(entry);
                });
            }
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
            int[] range = new int[1];
            var seen = new java.util.concurrent.atomic.AtomicReference<Set<Object>>();
            dispatcher.executeAndDrain(entry.key, RegionalServiceKind.TRACKING_UPDATE, ignored -> {
                requireEntityOwner(entry.candidate, entry.key);
                requireEntry(entry, entry.candidate);
                range[0] = access(entry.tracker).phoenium$invokeEffectiveRange();
                Set<Object> exact = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                exact.addAll(access(entry.tracker).phoenium$getSeenBy());
                if (!listeners.containsAll(exact))
                    throw new IllegalStateException("bootstrap tracker retains a connection outside the live session cut");
                seen.set(Set.copyOf(exact));
            });
            synchronized (this) {
                entry.effectiveRange = range[0];
                entry.seenConnections = Objects.requireNonNull(seen.get());
            }
        }
    }

    /** Runs the complete original add synchronously after preparing its finite recipient/source
     * footprint. The two original pairing call sites are intercepted below so far, unseen aliases
     * are decided from owner-published routes without dereferencing foreign entities. */
    public void addEntity(ChunkMap map, Entity entity, Runnable original) {
        Objects.requireNonNull(original, "original");
        if (activeAdd.get() != null) throw new IllegalStateException("nested ChunkMap.addEntity escaped its prepared tracking footprint");
        FullEntityFacts.Candidate source = finishing.entityFacts.trackingCandidate(level, entity);
        RoutingKey sourceKey = key(source);
        List<ServerPlayer> levelPlayers = List.copyOf(level.players());
        var connectionPlayers = new ArrayList<>(levelPlayers);
        if (entity instanceof ServerPlayer player && !connectionPlayers.contains(player)) connectionPlayers.add(player);
        FullFinishingScoreboard.TrackingCut connectionCut = finishing.scoreboard.trackingCut(connectionPlayers);
        Map<ServerPlayer, PlayerReceipt> players = playerReceipts(connectionCut);
        int sourceRange = captureRange(entity, sourceKey);
        var selectedPlayers = java.util.Collections.newSetFromMap(new IdentityHashMap<ServerPlayer, Boolean>());
        LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>();
        footprint.add(sourceKey);
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
                if (candidateChunk(entry.key, maximumTrackingRange, sourceKey)) {
                    selectedTrackers.add(entry.tracker);
                    footprint.add(entry.key);
                }
            }
        }

        if (OwnershipContext.current().isPresent()) dispatcher.requireCurrentClosure(footprint);
        else dispatcher.ensureClosure(footprint);
        AddPlan plan = new AddPlan(entity, source, sourceKey, connectionCut, players,
            Set.copyOf(selectedPlayers), Set.copyOf(selectedTrackers));
        Runnable body = () -> {
            dispatcher.requireCurrentOwner(sourceKey);
            if (!finishing.entityFacts.validTrackingCandidate(source))
                throw new IllegalStateException("addEntity source identity changed before original body");
            activeAdd.set(plan);
            try { original.run(); }
            finally { activeAdd.remove(); }
        };
        if (OwnershipContext.current().isPresent()) body.run();
        else dispatcher.executeAndDrain(sourceKey, RegionalServiceKind.TRACKING_PAIR, ignored -> body.run());
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
        List<Entry> currentEntries;
        synchronized (this) { currentEntries = index.values(); }
        Entry entry = currentEntries.stream().filter(value -> value.tracker == trackerIdentity).findFirst()
            .orElseThrow(() -> new IllegalStateException("addEntity reverse pairing tracker is not retained"));
        requireEntityOwner(entry.candidate, entry.key);
        requireEntry(entry, entry.candidate);
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
        FullEntityFacts.Candidate source = finishing.entityFacts.trackingCandidate(level, entity);
        RoutingKey sourceKey = key(source);
        var connectionPlayers = new ArrayList<>(level.players());
        if (entity instanceof ServerPlayer player && !connectionPlayers.contains(player)) connectionPlayers.add(player);
        FullFinishingScoreboard.TrackingCut cut = finishing.scoreboard.trackingCut(connectionPlayers);
        Map<ServerPlayer, PlayerReceipt> players = playerReceipts(cut);
        Map<Object, PlayerReceipt> listeners = new IdentityHashMap<>();
        players.values().forEach(receipt -> listeners.put(receipt.session.listener(), receipt));
        List<Entry> entries;
        synchronized (this) { entries = index.values(); }
        Entry sourceEntry = entries.stream().filter(value -> value.entity == entity).findFirst().orElse(null);
        LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>(); footprint.add(sourceKey);
        if (sourceEntry != null) for (Object listener : sourceEntry.seenConnections) {
            PlayerReceipt receipt = Objects.requireNonNull(listeners.get(listener),
                "removed tracker retains a connection outside the live session cut");
            footprint.add(receipt.key);
        }
        Set<Object> selectedTrackers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        if (entity instanceof ServerPlayer player) {
            PlayerReceipt removedPlayer = requirePlayer(players, player);
            for (Entry entry : entries) if (entry.seenConnections.contains(removedPlayer.session.listener())) {
                selectedTrackers.add(entry.tracker); footprint.add(entry.key);
            }
        }
        if (OwnershipContext.current().isPresent()) dispatcher.requireCurrentClosure(footprint);
        else dispatcher.ensureClosure(footprint);
        RemovePlan plan = new RemovePlan(entity, cut, players, Set.copyOf(selectedTrackers));
        Runnable body = () -> {
            dispatcher.requireCurrentOwner(sourceKey);
            activeRemove.set(plan);
            try { original.run(); }
            finally { activeRemove.remove(); }
        };
        if (OwnershipContext.current().isPresent()) body.run();
        else dispatcher.executeAndDrain(sourceKey, RegionalServiceKind.TRACKING_PAIR, ignored -> body.run());
    }

    public void removePlayer(Object trackerIdentity, ServerPlayer player, Runnable original) {
        RemovePlan plan = Objects.requireNonNull(activeRemove.get(), "unprepared removeEntity removePlayer call");
        if (player != plan.entity) throw new IllegalStateException("removeEntity reverse player changed");
        if (!plan.selectedTrackers.contains(trackerIdentity)) return;
        List<Entry> entries;
        synchronized (this) { entries = index.values(); }
        Entry entry = entries.stream().filter(value -> value.tracker == trackerIdentity).findFirst()
            .orElseThrow(() -> new IllegalStateException("removeEntity tracker is not retained"));
        requireEntityOwner(entry.candidate, entry.key);
        requireEntry(entry, entry.candidate);
        finishing.scoreboard.requireTrackingSession(plan.connections, plan.players.get(player).session);
        original.run();
        refreshProjection(entry);
    }

    /** Preserves ChunkMap.move's complete original suffix while replacing its world-wide tracker
     * scan with the exact retained seen/range candidate set prepared before entry. */
    public void movePlayer(ChunkMap map, ServerPlayer player, Runnable original) {
        if (activeMove.get() != null) throw new IllegalStateException("nested ChunkMap.move escaped its tracking footprint");
        var connectionPlayers = new ArrayList<>(level.players());
        if (!connectionPlayers.contains(player)) connectionPlayers.add(player);
        FullFinishingScoreboard.TrackingCut cut = finishing.scoreboard.trackingCut(connectionPlayers);
        Map<ServerPlayer, PlayerReceipt> players = playerReceipts(cut);
        PlayerReceipt moving = requirePlayer(players, player);
        Map<Object, PlayerReceipt> listeners = new IdentityHashMap<>();
        players.values().forEach(receipt -> listeners.put(receipt.session.listener(), receipt));
        List<Entry> entries;
        synchronized (this) { entries = index.values(); }
        LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>(); footprint.add(moving.key);
        Set<Object> selectedTrackers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ServerPlayer> selectedPlayers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Object playerTracker = null;
        for (Entry entry : entries) {
            if (entry.entity == player) {
                synchronized (this) {
                    entry.candidate = moving.candidate;
                    entry.key = moving.key;
                    entry.owner = route(moving.key).region();
                    index.update(entry.id, entry.key, entry.owner);
                }
                playerTracker = entry.tracker;
                int currentRange = captureRange(entry.entity, moving.key);
                for (ServerPlayer candidate : level.players()) {
                    PlayerReceipt receipt = requirePlayer(players, candidate);
                    if (entry.seenConnections.contains(receipt.session.listener())
                            || candidateChunk(entry.key, currentRange, receipt.key)) {
                        selectedPlayers.add(candidate); footprint.add(receipt.key);
                    }
                }
            } else if (entry.seenConnections.contains(moving.session.listener())
                    || candidateChunk(entry.key, maximumTrackingRange, moving.key)) {
                selectedTrackers.add(entry.tracker); footprint.add(entry.key);
            }
        }
        if (OwnershipContext.current().isPresent()) dispatcher.requireCurrentClosure(footprint);
        else dispatcher.ensureClosure(footprint);
        MovePlan plan = new MovePlan(player, playerTracker, cut, players,
            Set.copyOf(selectedPlayers), Set.copyOf(selectedTrackers));
        Runnable body = () -> {
            dispatcher.requireCurrentOwner(moving.key);
            activeMove.set(plan);
            try { original.run(); }
            finally { activeMove.remove(); }
        };
        if (OwnershipContext.current().isPresent()) body.run();
        else dispatcher.executeAndDrain(moving.key, RegionalServiceKind.TRACKING_UPDATE, ignored -> body.run());
    }

    public void movedPlayerUpdates(Object trackerIdentity, List<ServerPlayer> originalPlayers) {
        MovePlan plan = Objects.requireNonNull(activeMove.get(), "unprepared move updatePlayers call");
        if (trackerIdentity != plan.playerTracker)
            throw new IllegalStateException("move updatePlayers names a foreign player tracker");
        for (ServerPlayer player : originalPlayers) if (plan.selectedPlayers.contains(player)) {
            PlayerReceipt receipt = requirePlayer(plan.players, player);
            requirePlayer(receipt, plan.connections);
            access(trackerIdentity).phoenium$invokeUpdatePlayer(player);
        }
        List<Entry> entries;
        synchronized (this) { entries = index.values(); }
        Entry entry = entries.stream().filter(value -> value.tracker == trackerIdentity).findFirst()
            .orElseThrow(() -> new IllegalStateException("move player tracker is not retained"));
        refreshProjection(entry);
    }

    public void movedPlayerUpdate(Object trackerIdentity, ServerPlayer player, Runnable original) {
        MovePlan plan = Objects.requireNonNull(activeMove.get(), "unprepared move updatePlayer call");
        if (player != plan.player) throw new IllegalStateException("move player alias changed");
        if (!plan.selectedTrackers.contains(trackerIdentity)) return;
        List<Entry> entries;
        synchronized (this) { entries = index.values(); }
        Entry entry = entries.stream().filter(value -> value.tracker == trackerIdentity).findFirst()
            .orElseThrow(() -> new IllegalStateException("move tracker is not retained"));
        requireEntityOwner(entry.candidate, entry.key);
        requireEntry(entry, entry.candidate);
        finishing.scoreboard.requireTrackingSession(plan.connections, plan.players.get(player).session);
        original.run();
        refreshProjection(entry);
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
        for (Entry entry : index.values()) entry.owner = Objects.requireNonNull(plan.owners.get(entry.id));
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
    private void requireEntityOwner(FullEntityFacts.Candidate candidate, RoutingKey key) {
        var binding = objects.current(candidate.fact().uuid().toString()).orElseThrow(
            () -> new IllegalStateException("tracked entity lacks current object ownership"));
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), route(key).region());
        dispatcher.requireCurrentOwner(key);
    }
    private void requirePlayer(PlayerReceipt receipt, FullFinishingScoreboard.TrackingCut cut) {
        requireEntityOwner(receipt.candidate, receipt.key);
        if (!finishing.entityFacts.validTrackingCandidate(receipt.candidate))
            throw new IllegalStateException("tracking player route became stale");
        finishing.scoreboard.requireTrackingSession(cut, receipt.session);
    }
    private Map<ServerPlayer, PlayerReceipt> playerReceipts(FullFinishingScoreboard.TrackingCut cut) {
        Map<ServerPlayer, PlayerReceipt> result = new IdentityHashMap<>();
        for (FullFinishingScoreboard.TrackingSession session : cut.sessions()) {
            FullEntityFacts.Candidate candidate = finishing.entityFacts.trackingCandidate(level, session.player());
            result.put(session.player(), new PlayerReceipt(session, candidate, key(candidate)));
        }
        return result;
    }
    private void refreshProjection(Entry entry) {
        entry.effectiveRange = access(entry.tracker).phoenium$invokeEffectiveRange();
        entry.seenConnections = Set.copyOf(access(entry.tracker).phoenium$getSeenBy());
    }
    private void reorderSource(Int2ObjectMap<Object> source) {
        List<RegionalTrackingPartitions.Identity> order = new ArrayList<>(source.size());
        for (Int2ObjectMap.Entry<Object> item : source.int2ObjectEntrySet())
            order.add(new RegionalTrackingPartitions.Identity(item.getIntKey(), item.getValue()));
        index.reorder(order);
    }
    private int captureRange(Entity entity, RoutingKey key) {
        int[] range = new int[1];
        Runnable capture = () -> {
            dispatcher.requireCurrentOwner(key);
            int effective = entity.getType().clientTrackingRange() * 16;
            for (Entity passenger : entity.getIndirectPassengers())
                effective = Math.max(effective, passenger.getType().clientTrackingRange() * 16);
            range[0] = level.getServer().getScaledTrackingDistance(effective);
        };
        if (OwnershipContext.current().isPresent()) capture.run();
        else dispatcher.executeAndDrain(key, RegionalServiceKind.TRACKING_UPDATE, ignored -> capture.run());
        return range[0];
    }
    private static PlayerReceipt requirePlayer(Map<ServerPlayer, PlayerReceipt> players, ServerPlayer player) {
        return Objects.requireNonNull(players.get(player), "player is absent from retained tracking connection cut");
    }
    private RoutingKey key(FullEntityFacts.Candidate candidate) {
        return new RoutingKey(world, candidate.position().x(), candidate.position().z());
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
        Entry(int id, Object tracker, Entity entity, FullEntityFacts.Candidate candidate, RoutingKey key, RegionId owner) {
            this.id = id; this.tracker = tracker; this.entity = entity; this.candidate = candidate; this.key = key; this.owner = owner;
        }
    }
    private record Preview(RoutingKey key, SectionPos section, boolean sectionChanged,
                           int effectiveRange, Set<ServerPlayer> seen, Set<Object> seenConnections,
                           FullEntityFacts.Candidate candidate) { }
    private record PlayerReceipt(FullFinishingScoreboard.TrackingSession session,
                                 FullEntityFacts.Candidate candidate, RoutingKey key) { }
    private record Source(int id, Object tracker, Entity entity, FullEntityFacts.Candidate candidate,
                          RoutingKey key, RegionId owner) { }
    private record AddPlan(Entity entity, FullEntityFacts.Candidate source, RoutingKey sourceKey,
                           FullFinishingScoreboard.TrackingCut connections,
                           Map<ServerPlayer, PlayerReceipt> players,
                           Set<ServerPlayer> selectedPlayers, Set<Object> selectedTrackers) {
        AddPlan {
            players = Map.copyOf(players);
            selectedPlayers = Set.copyOf(selectedPlayers);
            selectedTrackers = Set.copyOf(selectedTrackers);
        }
    }
    private record RemovePlan(Entity entity, FullFinishingScoreboard.TrackingCut connections,
                              Map<ServerPlayer, PlayerReceipt> players, Set<Object> selectedTrackers) {
        RemovePlan { players = Map.copyOf(players); selectedTrackers = Set.copyOf(selectedTrackers); }
    }
    private record MovePlan(ServerPlayer player, Object playerTracker,
                            FullFinishingScoreboard.TrackingCut connections,
                            Map<ServerPlayer, PlayerReceipt> players,
                            Set<ServerPlayer> selectedPlayers, Set<Object> selectedTrackers) {
        MovePlan {
            players = Map.copyOf(players);
            selectedPlayers = Set.copyOf(selectedPlayers);
            selectedTrackers = Set.copyOf(selectedTrackers);
        }
    }
    public record MigrationPlan(long generation, Map<Integer, RegionId> owners) {
        public MigrationPlan { owners = Map.copyOf(owners); }
    }
    public record Measurements(int ownerPartitions, int tracked, long cycles, long updates,
                               long migrations, long sourceReconciliations, boolean cycling) { }
}
