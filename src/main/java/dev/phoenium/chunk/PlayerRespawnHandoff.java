package dev.phoenium.chunk;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;

/** One synchronous PlayerList.respawn call; never a UUID-only transaction or a second live session. */
public final class PlayerRespawnHandoff {
    public enum State { OPEN, OLD_RETIRING, OLD_RETIRED, PREPARED, INSERTING, WORLD_INSERTED,
        PUBLISHED, COMMITTED, ROLLING_BACK, ROLLED_BACK, FAILED }
    public enum Action { ADD, REMOVE }
    /** A terminal bit lives on the exact object, without retaining a world, session, or handoff.
     * Entity.equals compares runtime ids, which vanilla deliberately reuses on respawn. */
    public interface Incarnation {
        boolean phoenium$respawnRetired();
        void phoenium$respawnRetired(boolean retired);
        boolean phoenium$deathRemovalCompleted();
        void phoenium$deathRemovalCompleted(boolean completed);
        default FullEntityFacts.PublishedPosition phoenium$deathPosition() { return null; }
        default void phoenium$deathPosition(FullEntityFacts.PublishedPosition position) { throw new IllegalStateException("death position binding absent"); }
        boolean phoenium$respawnPrecheckLogged();
        void phoenium$respawnPrecheckLogged(boolean logged);
        /** ServerPlayer.showEndCredits' own removePlayerImmediately(CHANGED_DIMENSION) returned. */
        default boolean phoenium$creditsRemovalCompleted() { return false; }
        default void phoenium$creditsRemovalCompleted(boolean completed) { }
    }
    public interface Directory {
        List<ServerPlayer> phoenium$respawnPlayers();
        Map<UUID, ServerPlayer> phoenium$respawnUuids();
    }
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(PlayerRespawnHandoff.class);
    private final FullFinishingServices service;
    private final Directory directory;
    private final Thread coordinator = Thread.currentThread();
    public final ServerPlayer oldPlayer;
    public final ServerLevel oldWorld;
    public final UUID uuid;
    public final long generation, sessionId, oldIncarnation;
    public final Connection connection;
    public final ServerGamePacketListenerImpl listener;
    private ServerPlayer replacement;
    private ServerLevel replacementWorld;
    private long replacementIncarnation;
    private State state = State.OPEN;
    private boolean oldWorldRemoved, oldTrackingRemoved, oldDirectoryRemoved, newTrackingAdded, newTrackingRemoved;
    private boolean listPublished, uuidPublished, detached;
    private boolean abortRequested;
    private Tracking tracking;
    private Storage storage;
    private String oldRoute = "NONE", newRoute = "NONE";
    /** Death completed natively, or the End credits removed the player (ServerPlayer.showEndCredits). */
    private final boolean deathRetiredOpening;
    private final boolean retiredOpening;

    PlayerRespawnHandoff(FullFinishingServices service, Directory directory, ServerPlayer oldPlayer,
                         long generation, long sessionId, Connection connection) {
        this.service = service; this.directory = directory; this.oldPlayer = oldPlayer;
        check(dev.phoenium.ownership.OwnershipContext.current().isEmpty(), "respawn must begin before regional admission");
        this.generation = generation; this.sessionId = sessionId; this.connection = connection;
        listener = oldPlayer.connection; uuid = oldPlayer.getUUID(); oldWorld = oldPlayer.level();
        oldIncarnation = incarnation(oldPlayer);
        Entity worldUuid = oldWorld.getEntity(uuid), worldId = oldWorld.getEntity(oldPlayer.getId());
        boolean listed = containsExact(directory.phoenium$respawnPlayers(), oldPlayer);
        boolean worldListed = containsExact(oldWorld.players(), oldPlayer);
        boolean nativePresent = worldUuid == oldPlayer && worldId == oldPlayer && worldListed
            && oldPlayer.getRemovalReason() == null;
        deathRetiredOpening = deathRemovalCompleted(oldPlayer) && oldPlayer.deathTime >= 20
            && oldPlayer.getRemovalReason() == Entity.RemovalReason.KILLED
            && worldUuid == null && worldId == null && !worldListed;
        // ServerPlayer.showEndCredits removes the player with CHANGED_DIMENSION before PERFORM_RESPAWN arrives:
        // native has already run the complete removal and tracking-end callbacks, exactly like a completed death.
        boolean creditsRetiredOpening = oldPlayer instanceof Incarnation credits && credits.phoenium$creditsRemovalCompleted()
            && oldPlayer.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION
            && worldUuid == null && worldId == null && !worldListed;
        boolean retiredOpening = deathRetiredOpening || creditsRetiredOpening;
        if (!(!retired(oldPlayer) && listed && directory.phoenium$respawnUuids().get(uuid) == oldPlayer
                && service.scoreboard.lookupPlayer(uuid) == oldPlayer
                && listener.player == oldPlayer && (nativePresent || retiredOpening)))
            throw new ChunkProtocolException("respawn handoff: old publication is not current: retired=" + retired(oldPlayer)
                + " listed=" + listed + " uuidMap=" + (directory.phoenium$respawnUuids().get(uuid) == oldPlayer)
                + " scoreboard=" + (service.scoreboard.lookupPlayer(uuid) == oldPlayer)
                + " listener=" + (listener.player == oldPlayer) + " nativePresent=" + nativePresent
                + " worldUuid=" + (worldUuid == oldPlayer) + " worldId=" + (worldId == oldPlayer)
                + " worldListed=" + worldListed + " removal=" + oldPlayer.getRemovalReason()
                + " deathRetiredOpening=" + deathRetiredOpening + " creditsRetiredOpening=" + creditsRetiredOpening);
        if (retiredOpening) oldTrackingRemoved = true;
        this.retiredOpening = retiredOpening;
        marker();
    }

    public static ServerPlayer run(FullFinishingServices service, Directory directory, ServerPlayer oldPlayer,
                                   Supplier<ServerPlayer> original) {
        PlayerRespawnHandoff handoff;
        try (var turn = service.scoreboardAccess()) { handoff = service.scoreboard.openRespawn(directory, oldPlayer); }
        try {
            ServerPlayer result = original.get();
            handoff.commit(result);
            return result;
        } catch (Throwable failure) {
            try { handoff.rollback(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            // The listener still names the dead incarnation. Close this exact transport, never a UUID lookup.
            try { handoff.listener.disconnect(Component.literal("Player respawn failed; please reconnect")); }
            catch (Throwable disconnect) { if (disconnect != failure) failure.addSuppressed(disconnect); }
            throw failure;
        }
    }

    public State state() { return state; }
    public ServerPlayer replacement() { return replacement; }
    public boolean matches(ServerPlayer player) { return player == oldPlayer || player == replacement; }
    public boolean touches(ServerLevel world) { return oldWorld == world || replacementWorld == world; }
    public boolean rollingBack() { return state == State.ROLLING_BACK; }
    /** Vanilla resolves/loads the respawn position before removing its old player. Until that
     * exact list edge, the old incarnation is still current for ordinary tracking recipients. */
    public boolean stillCurrent(ServerPlayer player) { return state == State.OPEN && player == oldPlayer && !retiredOpening && !abortRequested; }
    /** Called only after the native tickDeath body and its removal callback returned normally. */
    public static void recordDeathRemovalCompleted(ServerPlayer player) {
        if (player.deathTime >= 20 && player.getRemovalReason() == Entity.RemovalReason.KILLED)
            ((Incarnation) player).phoenium$deathRemovalCompleted(true);
    }
    public static boolean deathRemovalCompleted(ServerPlayer player) {
        return player instanceof Incarnation access && access.phoenium$deathRemovalCompleted();
    }
    /** Capture while the exact dying entity is still published, before native removal.
     * The receipt is unusable until the existing completed-removal bit is published. */
    public static void captureDeathPosition(ServerPlayer player) {
        if(player.deathTime<20)throw new ChunkProtocolException("death position captured before native retirement");
        var position=FullFinishingServices.forServer(player.level().getServer()).entityFacts.publishedPosition(player);
        ((Incarnation)player).phoenium$deathPosition(position);
    }
    /** Same receipt for ServerPlayer.showEndCredits: captured while the exact player is still published, before native
     * removal(CHANGED_DIMENSION). The player stays in the player list until PERFORM_RESPAWN, so a nearby block-event
     * broadcast in that window needs this position instead of the published one that removal has just discarded.
     * The receipt is unusable until the completed-credits-removal bit is published. */
    public static void captureCreditsPosition(ServerPlayer player) {
        var position=FullFinishingServices.forServer(player.level().getServer()).entityFacts.publishedPosition(player);
        ((Incarnation)player).phoenium$deathPosition(position);
    }
    /** Connection-recipient data only; this never restores entity tracking authority. */
    public static FullEntityFacts.PublishedPosition deathPosition(ServerPlayer player) {
        if(!(player instanceof Incarnation access)||!(access.phoenium$deathRemovalCompleted()||access.phoenium$creditsRemovalCompleted())
            ||access.phoenium$respawnRetired())return null;
        var position=access.phoenium$deathPosition();
        return position!=null&&position.candidate().alias()==player
            &&position.candidate().fact().incarnation()==((FullEntityFacts.Access)player).phoenium$entityIncarnation()
                ? position : null;
    }
    public static boolean retired(ServerPlayer player) {
        return player instanceof Incarnation access && access.phoenium$respawnRetired();
    }
    private static void retire(ServerPlayer player) {
        var access = (Incarnation) player;
        access.phoenium$respawnRetired(true);
        access.phoenium$deathRemovalCompleted(false);
        access.phoenium$creditsRemovalCompleted(false);
        if(access.phoenium$deathPosition()!=null)access.phoenium$deathPosition(null);
    }
    private static long incarnation(ServerPlayer player) {
        return ((FullEntityFacts.Access) player).phoenium$entityIncarnation();
    }
    public static boolean containsExact(List<ServerPlayer> players, ServerPlayer player) {
        return players.stream().anyMatch(value -> value == player);
    }
    /** Diagnostics only. Each lookup is reported independently; a missing entry is never
     * interpreted as proof of a particular incarnation or of completed retirement. */
    static String precheck(Directory directory, ServerPlayer player, ServerPlayer phoeniumUuid, long sessionId,
                           boolean exactSession, long activeAttempt) {
        UUID id = player.getUUID(); ServerLevel world = player.level();
        Entity worldUuid = world.getEntity(id), worldId = world.getEntity(player.getId());
        ServerPlayer listUuid = directory.phoenium$respawnUuids().get(id);
        boolean listed = containsExact(directory.phoenium$respawnPlayers(), player);
        boolean worldListed = containsExact(world.players(), player);
        boolean alias = player.connection != null && player.connection.player == player;
        boolean receipt = deathRemovalCompleted(player);
        boolean present = worldUuid == player && worldId == player && worldListed && !player.isRemoved();
        boolean deathRetired = receipt && player.deathTime >= 20
            && player.getRemovalReason() == Entity.RemovalReason.KILLED
            && worldUuid == null && worldId == null && !worldListed;
        String reason = !exactSession ? "SESSION_NOT_EXACT" : activeAttempt >= 0 ? "ATTEMPT_ACTIVE"
            : retired(player) ? "OLD_TERMINAL" : !alias ? "LISTENER_ALIAS_CHANGED"
            : !listed ? "PLAYER_LIST_NOT_EXACT" : listUuid != player ? "UUID_DIRECTORY_NOT_EXACT"
            : phoeniumUuid != player ? "PHOENIUM_UUID_NOT_EXACT"
            : worldUuid != null && worldUuid != player ? "WORLD_UUID_CONFLICT"
            : worldId != null && worldId != player ? "WORLD_ID_CONFLICT"
            : present ? "ACCEPT_PRESENT" : deathRetired ? "ACCEPT_DEATH_RETIRED"
            : "NATIVE_ABSENCE_WITHOUT_COMPLETED_DEATH";
        return "old=" + System.identityHashCode(player) + " incarnation=" + incarnation(player)
            + " uuid=" + id + " sessionGeneration=" + sessionId + " exactSession=" + exactSession
            + " listenerAlias=" + alias + " playerList=" + listed + " playersByUUID=" + identity(listUuid, player)
            + " phoeniumUuid=" + identity(phoeniumUuid, player)
            + " worldUuid=" + identity(worldUuid, player) + " worldId=" + identity(worldId, player)
            + " worldPlayers=" + worldListed + " alive=" + observe(player::isAlive)
            + " deadOrDying=" + observe(player::isDeadOrDying)
            + " removed=" + player.isRemoved() + " removalReason=" + player.getRemovalReason()
            + " deathTime=" + player.deathTime + " nativeDeathCallbackComplete=" + receipt
            + " tracking=" + (receipt ? "DEATH_CALLBACK_COMPLETE" : "UNAVAILABLE")
            + " identityRetirement=UNAVAILABLE ownership=UNAVAILABLE activeAttempt="
            + (activeAttempt < 0 ? "NONE" : activeAttempt)
            + " result=" + reason;
    }
    private static String identity(Object entry, Object exact) {
        return entry == null ? "ABSENT" : entry == exact ? "EXACT" : "DIFFERENT@" + System.identityHashCode(entry);
    }
    private static String observe(java.util.function.BooleanSupplier observation) {
        try { return Boolean.toString(observation.getAsBoolean()); }
        catch (RuntimeException unavailable) { return "UNAVAILABLE"; }
    }
    private void current() {
        check(Thread.currentThread() == coordinator, "handoff escaped its synchronous coordinator");
        check(!detached && service.scoreboard.respawn(oldPlayer) == this, "handoff no longer owns its session");
        check(oldPlayer.connection == listener && incarnation(oldPlayer) == oldIncarnation,
            "old incarnation or retained listener changed");
        if (replacement != null) check(replacement.connection == listener
            && incarnation(replacement) == replacementIncarnation && replacement.level() == replacementWorld,
            "replacement incarnation, listener, or world changed");
    }
    private void expect(State expected) {
        current(); check(!abortRequested, "disconnect/close requested during respawn");
        check(state == expected, "expected " + expected + ", found " + state);
    }
    /** A reentrant disconnect cannot tear down the entity under an admitted tracking body. */
    public void requestAbort() { abortRequested = true; }
    public void oldListRemoved(ServerPlayer player) {
        expect(State.OPEN); check(player == oldPlayer && !containsExact(directory.phoenium$respawnPlayers(), oldPlayer),
            "old list removal did not retire the exact alias");
        var incarnation=(Incarnation)player;
        if(incarnation.phoenium$deathPosition()!=null)incarnation.phoenium$deathPosition(null);
        oldDirectoryRemoved = true; transition(State.OLD_RETIRING);
    }
    public void removeOldWorld(ServerLevel world, ServerPlayer player, Runnable original) {
        expect(State.OLD_RETIRING); check(world == oldWorld && player == oldPlayer, "foreign old-world removal");
        // The completed native death callback already retired storage, tracking, and identity.
        // Calling remove(KILLED) again would replay death effects and tracker removal.
        if (!retiredOpening) try (var turn = service.referenceAccess()) { original.run(); }
        check(oldTrackingRemoved && world.getEntity(uuid) != oldPlayer && world.getEntity(player.getId()) != oldPlayer
            && !containsExact(world.players(), oldPlayer), "old world/tracker did not retire");
        oldWorldRemoved = true; transition(State.OLD_RETIRED);
    }
    /** Called at restoreFrom entry, immediately after vanilla assigns the retained listener. */
    public void prepare(ServerPlayer player) {
        expect(State.OLD_RETIRED);
        check(player != oldPlayer && player.getUUID().equals(uuid) && player.connection == listener
            && ((FullFinishingScoreboard.ConnectionAccess) listener).phoenium$serviceConnection() == connection,
            "replacement does not retain the exact connection identity");
        check(!retired(player), "replacement is already retired");
        replacement = player; replacementWorld = player.level(); replacementIncarnation = incarnation(player);
        check(replacementIncarnation != oldIncarnation, "replacement reused the old incarnation");
        try (var turn = service.scoreboardAccess()) { service.scoreboard.preparedRespawn(this); }
        transition(State.PREPARED);
    }
    public void insertWorld(ServerLevel world, ServerPlayer player, Runnable original) {
        expect(State.PREPARED); check(player == replacement && world == replacementWorld, "foreign replacement insertion");
        try (var turn = service.referenceAccess()) {
            check(world.getEntity(uuid) == null && world.getEntity(player.getId()) == null,
                "replacement world already contains an incarnation; refusing vanilla force-add");
            transition(State.INSERTING);
            original.run();
            check(!abortRequested, "respawn aborted during world insertion");
            check(newTrackingAdded && world.getEntity(uuid) == replacement && world.getEntity(player.getId()) == replacement
                && containsExact(world.players(), replacement), "replacement world publication is incomplete");
            transition(State.WORLD_INSERTED);
        }
    }
    public void registered(ServerPlayer player) {
        expect(State.WORLD_INSERTED); check(player == replacement && containsExact(directory.phoenium$respawnPlayers(), player),
            "replacement list publication is missing");
        check(!listPublished, "duplicate replacement list publication"); listPublished = true;
    }
    public void uuidPublished(UUID id, ServerPlayer player) {
        expect(State.WORLD_INSERTED); check(listPublished && id.equals(uuid) && player == replacement
            && directory.phoenium$respawnUuids().get(uuid) == player, "replacement UUID publication is missing");
        check(!uuidPublished, "duplicate replacement UUID publication"); uuidPublished = true;
        transition(State.PUBLISHED);
    }
    /** Single commit point: successful respawn return after its entire native publication suffix. */
    public void commit(ServerPlayer result) {
        expect(State.PUBLISHED);
        check(result == replacement && listPublished && uuidPublished && oldWorldRemoved && oldTrackingRemoved
            && newTrackingAdded && !newTrackingRemoved && tracking == null, "incomplete replacement at commit");
        try (var turn = service.scoreboardAccess()) {
            check(containsExact(directory.phoenium$respawnPlayers(), replacement)
                && !containsExact(directory.phoenium$respawnPlayers(), oldPlayer)
                && directory.phoenium$respawnUuids().get(uuid) == replacement
                && replacementWorld.getEntity(uuid) == replacement, "replacement publication changed before commit");
            service.scoreboard.commitRespawn(this);
            retire(oldPlayer); transition(State.COMMITTED); detach();
        }
    }

    /** Tracking is admitted only for this source's exact action. A cut cannot use the other alias. */
    public Tracking beginTracking(ServerPlayer player, Action action) {
        current(); check(tracking == null, "nested respawn tracking operation");
        check(!abortRequested || rollingBack(), "respawn aborted before tracking admission");
        if (action == Action.REMOVE && rollingBack()
                && (player == oldPlayer && oldTrackingRemoved || player == replacement && newTrackingRemoved)) {
            tracking = new Tracking(player, action, true); return tracking;
        }
        boolean allowed = action == Action.ADD ? player == replacement && state == State.INSERTING && !newTrackingAdded
            : player == oldPlayer && (state == State.OLD_RETIRING || state == State.ROLLING_BACK) && !oldTrackingRemoved
                || player == replacement && state == State.ROLLING_BACK && !newTrackingRemoved;
        check(allowed, "unauthorized " + action + " for respawn incarnation");
        tracking = new Tracking(player, action, false); return tracking;
    }
    public Tracking tracking(ServerPlayer player, Action action) {
        return tracking != null && tracking.player == player && tracking.action == action ? tracking : null;
    }
    public final class Tracking implements AutoCloseable {
        public final ServerPlayer player;
        public final Action action;
        public final boolean alreadyRemoved;
        private boolean closed;
        private boolean succeeded;
        private Throwable failure;
        private Tracking(ServerPlayer player, Action action, boolean alreadyRemoved) {
            this.player = player; this.action = action; this.alreadyRemoved = alreadyRemoved;
        }
        public PlayerRespawnHandoff handoff() { return PlayerRespawnHandoff.this; }
        public void route(FullEntityFacts.Candidate candidate, dev.phoenium.topology.RoutingKey key, long topology) {
            require(player, sessionId);
            check(candidate.alias() == player && candidate.fact().world() == (player == oldPlayer ? oldWorld : replacementWorld)
                && candidate.fact().incarnation() == incarnation(player), "tracking route belongs to another incarnation");
            String value = key + "/revision=" + candidate.routingRevision() + "/topology=" + topology;
            if (player == oldPlayer) oldRoute = value; else newRoute = value;
        }
        /** A respawn-owned failure is returned to its rollback coordinator after the lease is
         * released. Ordinary service exceptions still trip the scheduler's common barrier. */
        public void captureOwnerFailure(Runnable body) {
            require(player, sessionId); check(failure == null, "failed tracking body cannot replay");
            try { body.run(); } catch (Throwable problem) { failure = problem; }
        }
        public void rethrowOwnerFailure() {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("respawn tracking failed", failure);
        }
        public void require(ServerPlayer exact, long exactSession) {
            current(); check(!closed && tracking == this && player == exact && sessionId == exactSession,
                "expired respawn tracking receipt");
        }
        public void succeeded() {
            require(player, sessionId);
            check(!succeeded, "tracking completion cannot execute twice");
            rethrowOwnerFailure();
            succeeded = true;
            if (action == Action.ADD) newTrackingAdded = true;
            else if (player == oldPlayer) oldTrackingRemoved = true;
            else newTrackingRemoved = true;
            check(!abortRequested || rollingBack(), "disconnect/close requested during tracking");
        }
        @Override public void close() { if (!closed) { check(tracking == this, "tracking receipt escaped nesting"); tracking = null; closed = true; } }
    }

    /** Receipt for the native storage prefix before visible lookup/tracking publication. */
    public final class Storage {
        private final Runnable unpublishedCleanup;
        private boolean callbackInstalled;
        private Storage(Runnable unpublishedCleanup) { this.unpublishedCleanup = unpublishedCleanup; }
        public void callbackInstalled() { current(); callbackInstalled = true; }
    }
    public Storage storageClaimed(ServerPlayer player, Runnable unpublishedCleanup) {
        expect(State.INSERTING); check(player == replacement && storage == null, "duplicate/foreign storage claim");
        return storage = new Storage(unpublishedCleanup);
    }
    public Storage storage(ServerPlayer player) { return player == replacement ? storage : null; }

    /** Destructive abort, not resurrection of the dead player. The retained connection is retired.
     * The whole cleanup is synchronous; a terminal call is a no-op before touching any native map. */
    public void rollback() {
        if (state == State.ROLLED_BACK || state == State.COMMITTED || detached) return;
        current(); check(tracking == null, "rollback entered inside a tracking callback");
        if (state == State.ROLLING_BACK) return;
        transition(State.ROLLING_BACK);
        try {
            try (var turn = service.referenceAccess()) {
                cleanupWorld(replacement, replacementWorld);
                if (!oldWorldRemoved) cleanupWorld(oldPlayer, oldWorld);
            }
            try (var turn = service.scoreboardAccess()) {
                cleanupDirectory(replacement); cleanupDirectory(oldPlayer);
                service.scoreboard.abortRespawn(this);
                retire(oldPlayer); if (replacement != null) retire(replacement);
                transition(State.ROLLED_BACK); detach();
            }
        } catch (Throwable failure) { transition(State.FAILED); throw failure; }
    }
    private void cleanupWorld(ServerPlayer player, ServerLevel world) {
        if (player == null) return;
        Entity visible = world.getEntity(uuid);
        Entity byId = world.getEntity(player.getId());
        // Vanilla removal deletes UUID/runtime-id entries unconditionally. Prove both first.
        check((visible == null || visible == player) && (byId == null || byId == player),
            "cleanup would touch a newer world incarnation");
        if (player == replacement && storage != null && !storage.callbackInstalled) {
            check(visible == null && byId == null, "visible replacement has no native storage callback");
            storage.unpublishedCleanup.run();
        } else if (visible == player || byId == player || containsExact(world.players(), player)
                || player == replacement && storage != null)
            world.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        check(world.getEntity(uuid) != player && world.getEntity(player.getId()) != player
            && !containsExact(world.players(), player), "exact world cleanup incomplete");
    }
    private void cleanupDirectory(ServerPlayer player) {
        if (player == null) return;
        directory.phoenium$respawnPlayers().removeIf(value -> value == player);
        if (player != oldPlayer || !oldDirectoryRemoved) service.scoreboard.remove(player, false);
        if (directory.phoenium$respawnUuids().get(uuid) == player) {
            directory.phoenium$respawnUuids().remove(uuid);
            service.scoreboard.playerUuidRemoved(uuid);
        }
    }
    private void detach() { service.scoreboard.detachRespawn(this); detached = true; }
    private void transition(State next) { state = next; marker(); }
    private void marker() {
        if (Boolean.getBoolean("phoenium.test.diagnostics")) LOGGER.info(
            "PHOENIUM_PLAYER_RESPAWN_HANDOFF uuid={} generation={} session={} old={}@{} new={}@{} opening={} phase={} oldRoute={} newRoute={}",
            uuid, generation, sessionId, oldIncarnation, System.identityHashCode(oldPlayer),
            replacement == null ? "NONE" : replacementIncarnation, replacement == null ? "NONE" : System.identityHashCode(replacement),
            retiredOpening ? (deathRetiredOpening ? "DEATH_RETIRED" : "CREDITS_RETIRED") : "PRESENT", state, oldRoute, newRoute);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new ChunkProtocolException("respawn handoff: " + message);
    }
}
