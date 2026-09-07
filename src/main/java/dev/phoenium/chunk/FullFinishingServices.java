package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Explicit service scheduling for the pinned, one-worker serial server. A turn is a
 * reservation, never a monitor around Minecraft. The only lock protects this ledger;
 * no callback, send, entity read, submission or completion runs while it is held.
 * Regional ownership is separate: these turns grant no foreign-entity access.
 *
 * The ordinary serial scheduler participates before its enclosing body. At an
 * owner-free event-loop poll, a suspended serial body can lend its service turn to
 * one FULL operation. Restoring the parent on exit preserves vanilla's synchronous
 * pumping order and avoids retaining a turn that its own awaited FULL needs.
 */
public final class FullFinishingServices {
    public enum Kind { CONNECTIONS, SCOREBOARD, CLOCK, ENVIRONMENT, ENTITY_REGISTRY, TRACKER, WAYPOINT, TICK_LIST, DEBUG, UNSAVED, RAID, TICKET, COMMAND_STORAGE }
    public enum Phase { SERVER_TICK, SERVER_TASK, SAVE, STOP, PLAYER_LIFECYCLE, SCOREBOARD_SYNC, ENTITY_LIFECYCLE }
    private static final Map<MinecraftServer, FullFinishingServices> SERVERS = new IdentityHashMap<>();
    /** Weak terminal identities prevent late callbacks from recreating a service after stop. */
    private static final Set<MinecraftServer> TERMINATED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Object, WorldResource> ENVIRONMENTS = new IdentityHashMap<>();
    private static final ReentrantLock DIRECTORY = new ReentrantLock();
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final int MAX_RESERVATIONS = 256;
    private final MinecraftServer server;
    public final FullLegacyOwners legacyOwners;
    public final FullFinishingScoreboard scoreboard;
    public final FullEntityFacts entityFacts;
    private final ReentrantLock metadata = new ReentrantLock();
    private final IdentityHashMap<ServerLevel, Long> worlds = new IdentityHashMap<>();
    private final IdentityHashMap<ServerLevel, Object> environments = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Reservation> reservations = new IdentityHashMap<>();
    private final Set<java.util.concurrent.CompletableFuture<?>> environmentRequests = Collections.newSetFromMap(new IdentityHashMap<>());
    private final EnumMap<Kind, Long> revisions = new EnumMap<>(Kind.class);
    private long worldSequence, sequence, serialEntries, fullEntries, releases;
    private volatile long revision;
    private volatile Scope active;
    private volatile Reservation reserved;
    private volatile boolean stopped;
    private boolean shutdownStarted, cleanupStarted;

    private FullFinishingServices(MinecraftServer server) {
        this.server = Objects.requireNonNull(server);
        legacyOwners = new FullLegacyOwners(server);
        scoreboard = new FullFinishingScoreboard(this);
        entityFacts = new FullEntityFacts(this);
        for (Kind kind : Kind.values()) revisions.put(kind, 0L);
    }
    public static FullFinishingServices forServer(MinecraftServer server) {
        Objects.requireNonNull(server);
        FullFinishingServices service;
        DIRECTORY.lock();
        try {
            if (TERMINATED.contains(server)) throw new ChunkProtocolException("finishing services requested after server shutdown");
            service = SERVERS.computeIfAbsent(server, FullFinishingServices::new);
        }
        finally { DIRECTORY.unlock(); }
        service.tryFinishShutdown();
        return service;
    }
    public static void stopped(MinecraftServer server) {
        FullFinishingServices service;
        DIRECTORY.lock();
        try {
            service = SERVERS.get(server);
            if (service == null) TERMINATED.add(server);
        }
        finally { DIRECTORY.unlock(); }
        if (service == null) {
            FullCommandStorageInputs.stopped(server);
            return;
        }
        service.shutdown();
    }
    public MinecraftServer server() { return server; }
    public Access scoreboardAccess() {
        Scope current = CURRENT.get();
        if (current != null && current.service == this) {
            current.require(Kind.SCOREBOARD, null); return new Access(this, null, null);
        }
        return new Access(this, serial(Phase.SERVER_TASK), null);
    }
    public Access referenceAccess() {
        Scope current = CURRENT.get();
        if (current != null && current.service == this) {
            current.require(Kind.ENTITY_REGISTRY, null); return new Access(this, null, null);
        }
        return new Access(this, serial(Phase.ENTITY_LIFECYCLE), null);
    }

    /** Install actual world/environment identities at the original serial lifecycle. */
    public void registerWorld(ServerLevel world) {
        if (world.getServer() != server) throw new ChunkProtocolException("foreign finishing-service world");
        Object environment = world.environmentAttributes();
        metadata.lock();
        try { worlds.computeIfAbsent(world, ignored -> ++worldSequence); }
        finally { metadata.unlock(); }
        DIRECTORY.lock(); try {
            Object old = environments.put(world, environment);
            if (old != null && old != environment) {
                ENVIRONMENTS.remove(old); changed(Kind.ENVIRONMENT);
            }
            WorldResource previous = ENVIRONMENTS.putIfAbsent(environment, new WorldResource(this, world));
            if (previous != null && (previous.service != this || previous.world != world))
                throw new ChunkProtocolException("environment cache shared by unrelated finishing worlds");
        } finally { DIRECTORY.unlock(); }
    }

    /** Called by installed serial scheduler wrappers, before any regional admission. */
    public Scope serial(Phase phase) {
        Scope parent = CURRENT.get();
        if (parent != null && parent.service == this) {
            parent.requireCurrent();
            Scope nested = new Scope(this, parent, null, phase, parent.sequence);
            metadata.lock(); try { active = nested; } finally { metadata.unlock(); }
            CURRENT.set(nested); return nested;
        }
        if (OwnershipContext.current().isPresent())
            throw new ChunkProtocolException("serial finishing service entered after regional admission");
        metadata.lock();
        try {
            if (active != null || reserved != null)
                throw new ChunkProtocolException("serial scheduler bypassed a reserved finishing service");
            if (stopped && phase != Phase.STOP) throw new ChunkProtocolException("serial service after shutdown");
            Scope scope = new Scope(this, parent, null, phase, ++sequence);
            active = scope; serialEntries++; CURRENT.set(scope); return scope;
        } finally { metadata.unlock(); }
    }

    /**
     * Owner-free preparation. The exact holder and StageId identify the operation;
     * a data receipt alone does not reserve any service. Null means retry before R.
     */
    public Reservation tryReserve(ChunkCustodyModel.StageId stage, Object holder,
                                  List<ServerLevel> requiredWorlds, int occurrenceSlots) {
        Objects.requireNonNull(stage); Objects.requireNonNull(holder);
        if (stage.kind() != ChunkStageKind.FULL_FINISH)
            throw new ChunkProtocolException("service reservation is not a FULL finishing operation");
        if (OwnershipContext.current().isPresent())
            throw new ChunkProtocolException("finishing services reserved while holding a region");
        if (occurrenceSlots < 0) throw new IllegalArgumentException("negative service occurrence capacity");
        // No state reads under metadata; world creation is itself a serial lifecycle edge.
        for (ServerLevel world : requiredWorlds) registerWorld(world);
        Set<FullPreparationClock> requiredClocks = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServerLevel world : requiredWorlds) {
            if (!(world.getLevelData() instanceof FullPreparationClockAccess access)) return null;
            FullPreparationClock clock = access.phoenium$preparationClock();
            if (!clock.available()) return null;
            requiredClocks.add(clock);
        }
        Scope parent = CURRENT.get();
        metadata.lock();
        try {
            if (stopped || reserved != null || reservations.size() >= MAX_RESERVATIONS) return null;
            if (active != null && (active != parent || parent.service != this || parent.full())) return null;
            if (parent != null && parent.service != this) return null;
            var ordered = requiredWorlds.stream().distinct().sorted(Comparator.comparingLong(worlds::get)).toList();
            var cut = new IdentityHashMap<ServerLevel, Long>();
            ordered.forEach(world -> cut.put(world, worlds.get(world)));
            FullFinishingScoreboard.Operation journal = scoreboard.reserve(occurrenceSlots);
            if (journal == null) return null;
            Reservation result = new Reservation(stage, holder, ++sequence, cut, parent, occurrenceSlots, journal, requiredWorlds);
            for (FullPreparationClock clock : requiredClocks) {
                if (!clock.reserveFinishing(result)) { result.closeLocked(); return null; }
                result.clocks.add(clock);
            }
            reservations.put(result, result); reserved = result;
            return result;
        } finally { metadata.unlock(); }
    }

    public static boolean finishing() { Scope scope = CURRENT.get(); return scope != null && scope.full(); }
    public static Reservation currentReservation(MinecraftServer server) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.service.server != server || !scope.full()) return null;
        scope.requireCurrent(); return scope.fullReservation();
    }
    public static Reservation currentReservation() {
        Scope scope = CURRENT.get();
        if (scope == null || !scope.full()) return null;
        scope.requireCurrent(); return scope.fullReservation();
    }
    public static ChunkProtocolException failure(ChunkProtocolException failure) {
        Scope scope = CURRENT.get();
        if (scope != null && scope.full()) scope.fullReservation().conflict(failure);
        return failure;
    }
    /** Storage does not retain even the conservative ordinary serial service turn. */
    public static <T> T ownerFreeIo(java.util.function.Supplier<T> body) {
        Scope serial = CURRENT.get();
        if (serial == null) return body.get();
        if (serial.full() || OwnershipContext.current().isPresent())
            throw new ChunkProtocolException("storage wait attempted from an admitted gameplay body");
        serial.requireCurrent();
        serial.service.metadata.lock();
        try {
            if (serial.service.reserved != null) throw new ChunkProtocolException("storage wait retains a FULL reservation");
            serial.service.active = null; CURRENT.remove();
        } finally { serial.service.metadata.unlock(); }
        try { return body.get(); }
        finally {
            serial.service.metadata.lock();
            try {
                if (serial.service.active != null || serial.service.reserved != null)
                    throw new ChunkProtocolException("storage completion overlaps a service operation");
                serial.service.active = serial; CURRENT.set(serial);
            } finally { serial.service.metadata.unlock(); }
        }
    }
    public static void requireWorld(ServerLevel level, Kind kind) {
        Scope scope = CURRENT.get();
        if (scope == null) throw new ChunkProtocolException("world service access has no admitted serial or FULL turn: " + kind);
        scope.require(kind, level);
    }
    public static void requireServer(MinecraftServer server, Kind kind) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.service.server != server)
            throw new ChunkProtocolException("server service access has no matching operation: " + kind);
        scope.require(kind, null);
    }
    /** Original cache readers also write the tick cache. Both sides use this turn. */
    public static Access environment(Object environment) {
        WorldResource resource;
        DIRECTORY.lock(); try { resource = ENVIRONMENTS.get(environment); } finally { DIRECTORY.unlock(); }
        if (resource == null) return Access.UNTRACKED; // Before its real server-world installation.
        Scope scope = CURRENT.get();
        if (scope != null && scope.service == resource.service) {
            scope.require(Kind.ENVIRONMENT, resource.world);
            return new Access(resource.service, null, Kind.ENVIRONMENT);
        }
        // Ordinary world construction can precede the first tick/task wrapper.
        // It obtains an explicit, owner-free serial operation; a competing FULL is excluded.
        Scope serial = resource.service.serial(Phase.SERVER_TASK);
        return new Access(resource.service, serial, Kind.ENVIRONMENT);
    }
    /** World-generation workers enqueue a named environment read before acquiring any
     * service turn. The original chunk event loop runs the getter once, without R.
     * Pending requests hold only their original continuation and outcome, not a turn. */
    static <T> java.util.concurrent.CompletableFuture<T> scheduleEnvironment(ServerLevel world,
            java.util.concurrent.Executor executor, java.util.function.Supplier<T> original) {
        FullFinishingServices service;
        DIRECTORY.lock(); try { service = SERVERS.get(world.getServer()); } finally { DIRECTORY.unlock(); }
        if (service == null) throw new ChunkProtocolException("world-generation environment has no installed world service");
        var result = new java.util.concurrent.CompletableFuture<T>();
        service.metadata.lock();
        try {
            if (service.stopped || !service.worlds.containsKey(world))
                throw new ChunkProtocolException("world-generation environment requested after world retirement");
            service.environmentRequests.add(result);
        } finally { service.metadata.unlock(); }
        try {
            executor.execute(() -> {
                service.metadata.lock();
                try { if (!service.environmentRequests.remove(result)) return; }
                finally { service.metadata.unlock(); }
                try {
                    if (!world.getServer().isSameThread() || OwnershipContext.current().isPresent())
                        throw new ChunkProtocolException("world-generation environment delivery is not owner-free on the original server thread");
                    T value;
                    try (var turn = service.serial(Phase.SERVER_TASK)) {
                        requireWorld(world, Kind.ENVIRONMENT);
                        value = original.get();
                    }
                    result.complete(value);
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
        } catch (Throwable failure) {
            service.metadata.lock(); try { service.environmentRequests.remove(result); } finally { service.metadata.unlock(); }
            result.completeExceptionally(failure);
        }
        return result;
    }
    public void changed(Kind kind) {
        metadata.lock(); try { revisions.put(kind, Math.incrementExact(revisions.get(kind))); revision = Math.incrementExact(revision); }
        finally { metadata.unlock(); }
    }
    public Map<String, Long> measurements() {
        metadata.lock();
        try { return Map.of("serialEntries", serialEntries, "fullEntries", fullEntries, "releases", releases,
                "reservations", (long) reservations.size(), "active", active == null ? 0L : 1L,
                "worlds", (long) worlds.size(), "environmentRequests", (long) environmentRequests.size()); }
        finally { metadata.unlock(); }
    }
    /** Mark terminal before touching a child service. A winning synchronous body drains first. */
    private void shutdown() {
        metadata.lock();
        try {
            stopped = true;
            shutdownStarted = true;
            for (Reservation reservation : List.copyOf(reservations.values())) {
                if (!reservation.entered) reservation.closeLocked();
            }
        } finally { metadata.unlock(); }
        tryFinishShutdown();
    }
    private void tryFinishShutdown() {
        boolean cleanup;
        metadata.lock();
        try {
            cleanup = shutdownStarted && active == null && OwnershipContext.current().isEmpty() && !cleanupStarted;
            if (cleanup) cleanupStarted = true;
        } finally { metadata.unlock(); }
        if (cleanup) finishShutdown();
    }
    private void finishShutdown() {
        Throwable failure = null;
        List<java.util.concurrent.CompletableFuture<?>> pendingEnvironment;
        metadata.lock();
        try { pendingEnvironment = List.copyOf(environmentRequests); environmentRequests.clear(); }
        finally { metadata.unlock(); }
        // A queued callback loses only its own accepted request; it cannot revive after stop.
        for (var request : pendingEnvironment)
            request.completeExceptionally(new java.util.concurrent.CancellationException("world-generation environment service stopped"));
        // No service/directory/region lock spans a child callback or executor transition.
        try { FullCommandStorageInputs.stopped(server); } catch (Throwable problem) { failure = accumulate(failure, problem); }
        try { legacyOwners.shutdown(); } catch (Throwable problem) { failure = accumulate(failure, problem); }
        try { entityFacts.close(); } catch (Throwable problem) { failure = accumulate(failure, problem); }
        try { scoreboard.close(); } catch (Throwable problem) { failure = accumulate(failure, problem); }
        metadata.lock();
        try {
            for (Reservation reservation : List.copyOf(reservations.values())) reservation.closeLocked();
            worlds.clear(); environments.clear();
        } catch (Throwable problem) { failure = accumulate(failure, problem); }
        finally { metadata.unlock(); }
        DIRECTORY.lock();
        try {
            ENVIRONMENTS.entrySet().removeIf(entry -> entry.getValue().service == this);
            SERVERS.remove(server, this); TERMINATED.add(server);
        } catch (Throwable problem) { failure = accumulate(failure, problem); }
        finally { DIRECTORY.unlock(); }
        if (failure != null) throw unchecked(failure);
    }
    private static Throwable accumulate(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
    private static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof Error error) throw error;
        return failure instanceof RuntimeException runtime ? runtime : new ChunkProtocolException("finishing service shutdown", failure);
    }
    private record WorldResource(FullFinishingServices service, ServerLevel world) { }

    public final class Reservation implements AutoCloseable {
        private final ChunkCustodyModel.StageId stage;
        private final Object holder;
        private final long sequence;
        private final IdentityHashMap<ServerLevel, Long> worldCut;
        private final Scope parent;
        private final int occurrenceSlots;
        private final List<ServerLevel> lookupWorlds;
        private final Set<net.minecraft.world.entity.Entity> constructed = Collections.newSetFromMap(new IdentityHashMap<>());
        private final long serviceRevision = revision;
        private final FullFinishingScoreboard.Operation scoreboard;
        private final List<FullPreparationClock> clocks = new ArrayList<>();
        private volatile ChunkProtocolException failure;
        private final EnumMap<Kind, Long> cut = new EnumMap<>(revisions);
        private volatile boolean entered, closed;
        private Reservation(ChunkCustodyModel.StageId stage, Object holder, long sequence,
                            IdentityHashMap<ServerLevel, Long> worlds, Scope parent, int slots, FullFinishingScoreboard.Operation journal,
                            List<ServerLevel> lookupOrder) {
            this.stage = stage; this.holder = holder; this.sequence = sequence;
            worldCut = worlds; this.parent = parent; occurrenceSlots = slots;
            lookupWorlds = List.copyOf(lookupOrder);
            scoreboard = journal;
        }
        /** Metadata only: safe to consume inside custody without acquiring a service lock. */
        public boolean valid(ChunkCustodyModel.StageId expected, Object exactHolder) {
            return scoreboard != null && scoreboard.valid() && failure == null && !closed && !entered && !stopped && reserved == this && stage.equals(expected)
                    && holder == exactHolder && active == parent && CURRENT.get() == parent && serviceRevision == revision;
        }
        public ChunkCustodyModel.StageId stage() { return stage; }
        public long sequence() { return sequence; }
        public int occurrenceSlots() { return occurrenceSlots; }
        public FullFinishingScoreboard.Operation scoreboard() { return scoreboard; }
        public List<ServerLevel> lookupWorlds() { return lookupWorlds; }
        public void constructed(net.minecraft.world.entity.Entity entity) { requireHealthy(); constructed.add(entity); }
        public boolean owns(net.minecraft.world.entity.Entity entity) { return constructed.contains(entity); }
        void conflict(ChunkProtocolException cause) { failure = cause; }
        public void requireHealthy() { if (failure != null) throw failure; }
        public void requireActive(ChunkStageClaim expected) {
            expected.require(stage); requireHealthy();
            Scope scope = CURRENT.get();
            if (closed || !entered || reserved != this || scope == null || scope.fullReservation() != this)
                throw FullFinishingServices.failure(new ChunkProtocolException("finishing service capability is not the active claimed operation"));
            Scope full = scope; while (full.reservation == null) full = full.parent;
            if (full.claim != expected) throw FullFinishingServices.failure(new ChunkProtocolException("finishing service claim identity changed"));
            scope.requireCurrent();
        }
        public Scope enter(ChunkStageClaim claim) {
            claim.require(stage);
            metadata.lock();
            try {
                if (!valid(stage, holder) || !worldCut.entrySet().stream().allMatch(e -> Objects.equals(worlds.get(e.getKey()), e.getValue()))
                        || !revisions.equals(cut)) throw new ChunkProtocolException("stale finishing service reservation at original suffix");
                entered = true;
                Scope scope = new Scope(FullFinishingServices.this, parent, this, null, sequence);
                scope.claim = claim; active = scope; fullEntries++; CURRENT.set(scope); return scope;
            } finally { metadata.unlock(); }
        }
        @Override public void close() {
            boolean cleanup = false;
            metadata.lock();
            try {
                if (closed) return;
                if (entered && active != parent) throw new ChunkProtocolException("finishing reservation released before synchronous exit");
                closeLocked();
                cleanup = shutdownStarted;
            } finally { metadata.unlock(); }
            if (cleanup) tryFinishShutdown();
        }
        private void closeLocked() {
            closed = true; reservations.remove(this); if (reserved == this) reserved = null; releases++;
            try { if (scoreboard != null) scoreboard.close(); }
            finally {
                clocks.forEach(clock -> clock.releaseFinishing(this)); clocks.clear();
                worldCut.clear(); constructed.clear();
            }
        }
    }
    public static final class Scope implements AutoCloseable {
        private final FullFinishingServices service;
        private final Scope parent;
        private final Reservation reservation;
        private final Phase phase;
        private final long sequence;
        private ChunkStageClaim claim;
        private boolean closed;
        private Scope(FullFinishingServices service, Scope parent, Reservation reservation, Phase phase, long sequence) {
            this.service = service; this.parent = parent; this.reservation = reservation; this.phase = phase; this.sequence = sequence;
        }
        private boolean full() { return reservation != null || parent != null && parent.service == service && parent.full(); }
        private Reservation fullReservation() { return reservation != null ? reservation : parent == null ? null : parent.fullReservation(); }
        private void requireCurrent() {
            if (closed || CURRENT.get() != this || service.active != this)
                throw new ChunkProtocolException("expired or displaced finishing service operation");
            Reservation full = fullReservation();
            if (full != null) {
                Scope source = this; while (source.reservation == null) source = source.parent;
                source.claim.require(full.stage);
            }
        }
        private void require(Kind kind, ServerLevel world) {
            requireCurrent();
            Reservation full = fullReservation();
            if (world != null && (world.getServer() != service.server || full != null && !full.worldCut.containsKey(world)))
                throw new ChunkProtocolException("world outside exact finishing service footprint: " + kind);
        }
        @Override public void close() {
            if (closed) return;
            requireCurrent();
            service.metadata.lock();
            try {
                service.active = parent != null && parent.service == service ? parent : null; closed = true; claim = null;
            }
            finally { service.metadata.unlock(); }
            if (parent == null) CURRENT.remove(); else CURRENT.set(parent);
            try { if (reservation != null) reservation.requireHealthy(); }
            finally { service.tryFinishShutdown(); }
        }
    }
    public static final class Access implements AutoCloseable {
        private static final Access UNTRACKED = new Access(null, null, null);
        private final FullFinishingServices service;
        private final Scope owned;
        private final Kind kind;
        private Access(FullFinishingServices service, Scope owned, Kind kind) { this.service = service; this.owned = owned; this.kind = kind; }
        @Override public void close() { if (service != null && kind != null) service.changed(kind); if (owned != null) owned.close(); }
    }
}
