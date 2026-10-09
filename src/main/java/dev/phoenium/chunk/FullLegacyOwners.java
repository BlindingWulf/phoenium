package dev.phoenium.chunk;

import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.ProfileLookupCallback;
import dev.phoenium.ownership.OwnershipContext;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.*;
import net.minecraft.util.StringUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.level.entity.UniquelyIdentifyable;

/** Approved D2-P: bounded, detached owner occurrences settle before any FULL turn. */
public final class FullLegacyOwners {
    private static final int MAX_PLANS = 128;
    private static final ThreadLocal<Plan> ACTIVE = new dev.phoenium.core.FastThreadLocal<>();
    private final MinecraftServer server;
    private final Set<Plan> plans = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<FullLegacyOwnerCache> caches = Collections.newSetFromMap(new IdentityHashMap<>());
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private final java.util.concurrent.locks.ReentrantLock plansGate = new java.util.concurrent.locks.ReentrantLock();
    private final ThreadPoolExecutor producer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PLANS), runnable -> {
                Thread thread = new Thread(runnable, "Phoenium-legacy-owner-preparation"); thread.setDaemon(true); return thread;
            }) {
                @Override protected void terminated() { closeStorageAfterAcknowledgement(); }
            };
    private volatile boolean stopped;
    private long completed, acknowledged;
    public FullLegacyOwners(MinecraftServer server) { this.server = Objects.requireNonNull(server); }
    public record Result(String path, String originalName, UUID identity, Throwable failure,
                         FullLegacyOwnerCache.Cut cut, FullLegacyOwnerCache.SaveAttempt persistence) { }

    /** Null is bounded preclaim pressure or a missing concrete cache adapter, never fabricated readiness. */
    public Plan prepare(ChunkCustodyModel.StageId stage, FullSavedInput.Snapshot saved) {
        if (OwnershipContext.current().isPresent() || FullFinishingServices.finishing())
            throw new ChunkProtocolException("legacy owner preparation started after gameplay admission");
        Objects.requireNonNull(saved);
        List<FullSavedInput.EntityFact> occurrences = saved.entities().stream().filter(f -> f.services().legacyOwner() != null).toList();
        if (occurrences.isEmpty()) return new Plan(stage, saved, occurrences, null, null, null, false, false);
        UserNameToIdResolver actual = server.services().nameToIdCache();
        if (!(actual instanceof FullLegacyOwnerCache.Access access)) return null;
        Plan plan = new Plan(stage, saved, occurrences, actual, access.phoenium$ownerCache(), server.services().profileRepository(),
                server.usesAuthentication(), server.isSingleplayer());
        plansGate.lock(); try {
            if (stopped || plans.size() >= MAX_PLANS) return null;
            plans.add(plan); caches.add(plan.cache);
        } finally { plansGate.unlock(); }
        try { producer.execute(plan::produce); }
        catch (RejectedExecutionException pressure) { plansGate.lock(); try { plans.remove(plan); } finally { plansGate.unlock(); } return null; }
        return plan;
    }
    public final class Plan implements AutoCloseable {
        private final ChunkCustodyModel.StageId stage;
        private final FullSavedInput.Snapshot saved;
        private final List<FullSavedInput.EntityFact> occurrences;
        private final UserNameToIdResolver actual;
        private final FullLegacyOwnerCache cache;
        private final GameProfileRepository provider;
        private final boolean authenticated, singleplayer;
        private volatile List<Result> results;
        private volatile boolean finished, closed;
        private final Set<String> consumed = new HashSet<>();
        private ChunkStageClaim claim;
        private final java.util.concurrent.atomic.AtomicReference<Runnable> readyListener = new java.util.concurrent.atomic.AtomicReference<>();
        private Plan(ChunkCustodyModel.StageId stage, FullSavedInput.Snapshot saved, List<FullSavedInput.EntityFact> occurrences,
                     UserNameToIdResolver actual, FullLegacyOwnerCache cache, GameProfileRepository provider, boolean auth, boolean singleplayer) {
            this.stage = stage; this.saved = saved; this.occurrences = occurrences; this.actual = actual;
            this.cache = cache; this.provider = provider; authenticated = auth; this.singleplayer = singleplayer;
            if (occurrences.isEmpty()) { results = List.of(); finished = true; }
        }
        private void produce() {
            try {
                var found = new ArrayList<Result>(occurrences.size());
                for (var fact : occurrences) {
                    if (closed) break;
                    String name = fact.services().legacyOwner();
                    UUID identity = null; Throwable failure = null;
                    cache.beginOccurrence();
                    try { identity = resolve(name); }
                    catch (Throwable reached) { failure = reached; }
                    found.add(new Result(fact.path(), name, identity, failure, cache.cut(name), cache.lastSave()));
                    cache.endOccurrence();
                }
                if (!closed) results = List.copyOf(found);
            } finally {
                finished = true;
                plansGate.lock(); try { completed++; if (closed) { plans.remove(this); acknowledged++; } } finally { plansGate.unlock(); }
                signalReady();
            }
        }
        private UUID resolve(String owner) {
            // The exact pinned OldUsersConverter branch order with the captured server policy.
            // The cache/provider calls remain original public operations and preserve failure cuts.
            if (!StringUtil.isNullOrEmpty(owner) && owner.length() <= 16) {
                Optional<UUID> cached = actual.get(owner).map(NameAndId::id);
                if (cached.isPresent()) return cached.get();
                if (!singleplayer && authenticated) {
                    var profiles = new ArrayList<NameAndId>();
                    provider.findProfilesByNames(new String[]{owner}, new ProfileLookupCallback() {
                        public void onProfileLookupSucceeded(String name, UUID id) {
                            NameAndId profile = new NameAndId(id, name); actual.add(profile); profiles.add(profile);
                        }
                        public void onProfileLookupFailed(String name, Exception error) {
                            org.slf4j.LoggerFactory.getLogger(OldUsersConverter.class).warn("Could not lookup user whitelist entry for {}", name, error);
                        }
                    });
                    return profiles.isEmpty() ? null : profiles.getFirst().id();
                }
                return UUIDUtil.createOfflinePlayerUUID(owner);
            }
            try { return UUID.fromString(owner); } catch (IllegalArgumentException malformed) { return null; }
        }
        public boolean ready() { return finished && !closed && results != null && results.size() == occurrences.size(); }
        public void onReady(Runnable notify) {
            if (closed) return;
            if (!readyListener.compareAndSet(null, notify) && readyListener.get() != notify)
                throw new ChunkProtocolException("legacy owner preparation has a different completion subscriber");
            if (finished) signalReady();
        }
        private void signalReady() {
            Runnable notify = readyListener.getAndSet(null);
            if (!closed && notify != null) notify.run();
        }
        public boolean valid(ChunkCustodyModel.StageId expected, FullSavedInput.Snapshot current) {
            if (!ready() || !stage.equals(expected) || saved != current) return false;
            if (occurrences.isEmpty()) return true;
            return server.services().nameToIdCache() == actual && server.services().profileRepository() == provider
                    && server.usesAuthentication() == authenticated && server.isSingleplayer() == singleplayer
                    && results.stream().allMatch(result -> cache.valid(result.originalName, result.cut));
        }
        /** Validation occurs at P before R; entering fixes the approved earlier observation. */
        public OwnerScope enter(ChunkStageClaim claim) {
            claim.require(stage);
            if (!ready() || ACTIVE.get() != null || this.claim != null)
                throw new ChunkProtocolException("legacy owner receipt entered twice or before producer acknowledgement");
            this.claim = claim; ACTIVE.set(this);
            return new OwnerScope(this);
        }
        public List<Result> results() { return results == null ? List.of() : results; }
        @Override public void close() {
            if (closed) return;
            if (claim != null) throw new ChunkProtocolException("legacy owner input released before original body exited");
            closed = true; results = null;
            readyListener.set(null);
            plansGate.lock(); try { if (finished && plans.remove(this)) acknowledged++; } finally { plansGate.unlock(); }
        }
        private UUID consume(ValueInput input, String key, Level level, String name) {
            claim.require(stage);
            if (level.getServer() != server || !key.equals("Owner") || !(input instanceof FullSavedValueInput bound))
                throw new ChunkProtocolException("legacy Owner edge does not match its saved input capability");
            var tag = bound.phoenium$originalSavedTag();
            FullSavedInput source = FullSavedCollections.source(tag);
            String path = source == null ? null : source.pathOf(tag);
            if (source == null || source.snapshot() != saved || path == null)
                throw new ChunkProtocolException("legacy Owner edge lost protected input identity");
            Result result = results.stream().filter(value -> value.path.equals(path)).findFirst()
                    .orElseThrow(() -> new ChunkProtocolException("unprepared legacy Owner occurrence " + path));
            if (!name.equals(result.originalName) || !consumed.add(path))
                throw new ChunkProtocolException("mismatched or repeated legacy Owner occurrence " + path);
            if (result.failure instanceof Error error) throw error;
            if (result.failure instanceof RuntimeException runtime) throw runtime;
            if (result.failure != null) throw new ChunkProtocolException("legacy Owner original failure", result.failure);
            return result.identity;
        }
    }
    public static final class OwnerScope implements AutoCloseable {
        private Plan plan;
        private OwnerScope(Plan plan) { this.plan = plan; }
        @Override public void close() {
            if (plan == null) return;
            if (ACTIVE.get() != plan) throw new ChunkProtocolException("legacy owner operation escaped");
            ACTIVE.set(null); plan.claim = null; plan = null;
        }
    }
    public static UUID atOriginalEdge(ValueInput input, String key, Level level, String name) {
        Plan plan = ACTIVE.get();
        if (plan == null) {
            if (FullFinishingServices.finishing()) throw new ChunkProtocolException("FULL legacy Owner reached without its prepared occurrence");
            return OldUsersConverter.convertMobOwnerIfNecessary(level.getServer(), name);
        }
        return plan.consume(input, key, level, name);
    }
    /** The original UUID-first reader, shared by production and the test-only call-site adapter. */
    public static <T extends UniquelyIdentifyable> EntityReference<T> readOriginal(ValueInput input, String key, Level level) {
        try {
            Optional<UUID> uuid = input.read(key, UUIDUtil.CODEC);
            if (uuid.isPresent()) return EntityReference.of(uuid.get());
            return input.getString(key).map(name -> atOriginalEdge(input, key, level, name))
                    .map(EntityReference::<T>of).orElse(null);
        } catch (ChunkProtocolException failure) { throw FullFinishingServices.failure(failure); }
    }
    public void shutdown() {
        List<Plan> pending;
        plansGate.lock();
        try {
            if (stopped) return;
            stopped = true; pending = List.copyOf(plans);
        } finally { plansGate.unlock(); }
        Throwable failure = null;
        try {
            var actualServices = server.services();
            if (actualServices != null && actualServices.nameToIdCache() instanceof FullLegacyOwnerCache.Access access) {
                FullLegacyOwnerCache cache = access.phoenium$ownerCache();
                plansGate.lock(); try { caches.add(cache); } finally { plansGate.unlock(); }
            }
        } catch (Throwable problem) { failure = problem; }
        for (Plan plan : pending) try { plan.close(); }
        catch (Throwable problem) {
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
        }
        try { producer.shutdown(); }
        catch (Throwable problem) {
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null) throw new ChunkProtocolException("legacy owner shutdown", failure);
    }
    /** The last real producer acknowledgement retires its ordered writer, even after
     * subscribers and the server directory have gone away. No owner waits for it. */
    private void closeStorageAfterAcknowledgement() {
        List<FullLegacyOwnerCache> retired;
        plansGate.lock(); try { retired = List.copyOf(caches); caches.clear(); } finally { plansGate.unlock(); }
        Throwable failure = null;
        for (FullLegacyOwnerCache cache : retired) try { cache.shutdown(); }
        catch (Throwable problem) {
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
        }
        if (failure == null) termination.complete(null); else termination.completeExceptionally(failure);
    }
    public CompletionStage<Void> termination() { return termination.minimalCompletionStage(); }
    public Map<String, Long> measurements() {
        long retained, finished, released, cacheRoots;
        plansGate.lock(); try { retained = plans.size(); finished = completed; released = acknowledged; cacheRoots = caches.size(); }
        finally { plansGate.unlock(); }
        return Map.of("plans", retained, "completed", finished, "acknowledged", released,
                "queued", (long) producer.getQueue().size(), "running", (long) producer.getActiveCount(),
                "producerTerminated", producer.isTerminated() ? 1L : 0L, "cacheRoots", cacheRoots);
    }
}
