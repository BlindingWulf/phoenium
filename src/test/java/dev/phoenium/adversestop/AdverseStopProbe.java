package dev.phoenium.adversestop;

import dev.phoenium.Phoenium;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.chunk.FullServerShutdown;
import dev.phoenium.live.PhoeniumLiveRuntime;
import dev.phoenium.save.RegionalSaveCoordinator;
import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.topology.RoutingKey;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/**
 * Disposable installed-only adverse probe. It deliberately terminates its server with one exact
 * regional callback failure. PASS means the real pending chunk write and native failed-stop path
 * settled correctly; an arbitrary process failure never satisfies this witness.
 */
public final class AdverseStopProbe implements DedicatedServerModInitializer {
    public static final IllegalStateException EXPECTED_FAILURE =
            new IllegalStateException("phoenium expected installed regional callback failure");
    private static final AtomicReference<MinecraftServer> SERVER = new AtomicReference<>();
    private static final AtomicReference<PhoeniumLiveRuntime> RUNTIME = new AtomicReference<>();
    private static final AtomicReference<FullFinishingServices> FINISHING = new AtomicReference<>();
    private static final AtomicReference<Throwable> PROBE_FAILURE = new AtomicReference<>();
    private static final AtomicReference<IOWorker> TARGET_WORKER = new AtomicReference<>();
    private static final AtomicReference<ChunkPos> TARGET_POS = new AtomicReference<>();
    private static final CountDownLatch WRITE_ENTERED = new CountDownLatch(1);
    private static final CountDownLatch FAILED_STOP_WAITING = new CountDownLatch(1);
    private static final CountDownLatch RELEASE_WRITE = new CountDownLatch(1);
    private static final AtomicBoolean WRITE_CLAIMED = new AtomicBoolean();
    private static final AtomicLong ORDER = new AtomicLong();
    private static final AtomicLong STOP_WAIT_ORDER = new AtomicLong();
    private static final AtomicLong WRITE_COMPLETE_ORDER = new AtomicLong();
    private static final AtomicLong FIRST_CLEANUP_ENTRY_ORDER = new AtomicLong();
    private static final AtomicLong NATIVE_STOP_ORDER = new AtomicLong();
    private static final AtomicInteger ORIGINAL_SAVE_SELECTED = new AtomicInteger();
    private static final AtomicInteger CALLBACK_FAILURES = new AtomicInteger();
    private static final AtomicInteger WRITE_ENTRIES = new AtomicInteger();
    private static final AtomicInteger WRITE_COMPLETIONS = new AtomicInteger();
    private static final AtomicInteger WRITE_FAILURES = new AtomicInteger();
    private static final AtomicInteger RUNTIME_STOP_WITNESSES = new AtomicInteger();
    private static final AtomicInteger SHUTDOWN_FAILURES = new AtomicInteger();
    private static final AtomicBoolean EXPECTED_FAILURE_RETAINED = new AtomicBoolean();
    private static final AtomicInteger CLEANUP_ENTRIES = new AtomicInteger();
    private static final AtomicInteger NATIVE_STOP_RETURNS = new AtomicInteger();
    private static final Set<Object> EXPECTED_LEVELS = identitySet();
    private static final Set<Object> EXPECTED_CACHES = identitySet();
    private static final Set<Object> EXPECTED_MAPS = identitySet();
    private static final Set<Object> EXPECTED_DATA = identitySet();
    private static final Set<Object> EXPECTED_STORAGE = identitySet();
    private static final Set<Object> EXPECTED_RESOURCES = identitySet();
    private static final Set<Object> CLOSED_LEVELS = identitySet();
    private static final Set<Object> CLOSED_CACHES = identitySet();
    private static final Set<Object> CLOSED_MAPS = identitySet();
    private static final Set<Object> CLOSED_DATA = identitySet();
    private static final Set<Object> CLOSED_STORAGE = identitySet();
    private static final Set<Object> CLOSED_RESOURCES = identitySet();
    private static volatile Thread releaseController;

    @Override public void onInitializeServer() {
        Runtime.getRuntime().addShutdownHook(new Thread(AdverseStopProbe::report,
                "phoenium-adverse-stop-witness"));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> server.execute(() -> arm(server)));
    }

    private static void arm(MinecraftServer server) {
        try {
            SERVER.set(server);
            PhoeniumLiveRuntime runtime = Phoenium.liveRuntime();
            RUNTIME.set(runtime);
            FINISHING.set(FullFinishingServices.forServer(server));
            List<ServerLevel> levels = new ArrayList<>();
            server.getAllLevels().forEach(levels::add);
            require(!levels.isEmpty(), "native server has no levels");
            for (ServerLevel level : levels) {
                EXPECTED_LEVELS.add(level);
                ServerChunkCache cache = level.getChunkSource();
                EXPECTED_CACHES.add(cache);
                EXPECTED_MAPS.add(cache.chunkMap);
                EXPECTED_DATA.add(cache.getDataStorage());
            }
            EXPECTED_DATA.add(server.getDataStorage());
            EXPECTED_STORAGE.add(field(server, MinecraftServer.class, "storageSource"));
            EXPECTED_RESOURCES.add(field(server, MinecraftServer.class, "resources"));

            Selection selection = select(levels);
            TARGET_POS.set(selection.holder().getPos());
            TARGET_WORKER.set((IOWorker) field(selection.map(), SimpleRegionStorage.class, "worker"));
            ChunkPos pos = selection.holder().getPos();
            var authority = runtime.requireChunkWorld(selection.level());
            RoutingKey key = new RoutingKey(authority.world(), pos.x(), pos.z());
            AtomicReference<ChunkAccess> selectedChunk = new AtomicReference<>();
            runtime.regionalDispatcher().executeAndDrain(key, RegionalServiceKind.CHUNK_GAMEPLAY, execution -> {
                ChunkAccess chunk = selection.holder().getLatestChunk();
                require(chunk instanceof LevelChunk && chunk.getPos().equals(pos),
                        "selected native holder changed before dirty mutation");
                chunk.markUnsaved();
                selectedChunk.set(chunk);
            });
            require(selectedChunk.get() != null, "owned dirty mutation did not execute");
            require(runtime.regionalSaves().pending() == 0, "probe began with residual save custody");

            boolean saved = (boolean) invoke(selection.map(), ChunkMap.class, "saveChunkIfNeeded",
                    new Class<?>[]{ChunkHolder.class, long.class}, selection.holder(), Long.MAX_VALUE - 10_000L);
            require(saved, "original selected save did not submit a write");
            ORIGINAL_SAVE_SELECTED.incrementAndGet();
            require(WRITE_ENTERED.await(10, TimeUnit.SECONDS), "native IOWorker did not reach its original write");
            require(WRITE_COMPLETIONS.get() == 0, "native write completed before the adverse callback");
            require(runtime.regionalSaves().pending() == 1, "original write lacks one retained save attempt");

            try {
                runtime.regionalDispatcher().executeAndDrain(key, RegionalServiceKind.CHUNK_GAMEPLAY, execution -> {
                    CALLBACK_FAILURES.incrementAndGet();
                    throw EXPECTED_FAILURE;
                });
                throw new AssertionError("expected regional callback returned normally");
            } catch (Throwable failure) {
                require(failure == EXPECTED_FAILURE, "regional dispatcher changed the initiating exception identity");
            }
            require(runtime.regionalDispatcher().pendingCount() == 0,
                    "failed callback retained a dispatcher body after synchronous observation");
            releaseController = Thread.ofPlatform().daemon(true).name("phoenium-adverse-write-release").start(() -> {
                try {
                    if (!FAILED_STOP_WAITING.await(15, TimeUnit.SECONDS))
                        fail(new IllegalStateException("failed stop never waited on the retained native write"));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); fail(interrupted);
                } finally { RELEASE_WRITE.countDown(); }
            });
            System.out.println("PHOENIUM_ADVERSE_STOP ARMED originalSelected=1 pendingSaves=1 nativeWriteEntered=1"
                    + " callbackFailure=expected durablePersistenceClaim=false");
            server.halt(false);
        } catch (Throwable failure) {
            fail(unwrap(failure));
            RELEASE_WRITE.countDown();
            server.halt(false);
            throw failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure);
        }
    }

    private static Selection select(List<ServerLevel> levels) throws Exception {
        for (ServerLevel level : levels) {
            ChunkMap map = level.getChunkSource().chunkMap;
            @SuppressWarnings("unchecked") var visible =
                    (Long2ObjectLinkedOpenHashMap<ChunkHolder>) field(map, ChunkMap.class, "visibleChunkMap");
            for (ChunkHolder holder : visible.values()) {
                if (holder.wasAccessibleSinceLastSave() && holder.isReadyForSaving()
                        && holder.getLatestChunk() instanceof LevelChunk)
                    return new Selection(level, map, holder);
            }
        }
        throw new IllegalStateException("no accessible ready native FULL holder for adverse save");
    }

    public static boolean beginNativeWrite(IOWorker worker, ChunkPos pos) {
        if (worker != TARGET_WORKER.get() || !pos.equals(TARGET_POS.get())
                || !WRITE_CLAIMED.compareAndSet(false, true)) return false;
        WRITE_ENTRIES.incrementAndGet();
        WRITE_ENTERED.countDown();
        try {
            if (!RELEASE_WRITE.await(20, TimeUnit.SECONDS))
                throw new IllegalStateException("bounded adverse write gate expired");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("adverse write gate interrupted", interrupted);
        }
        return true;
    }

    public static void nativeWriteComplete() {
        WRITE_COMPLETIONS.incrementAndGet();
        WRITE_COMPLETE_ORDER.compareAndSet(0, ORDER.incrementAndGet());
    }
    public static void nativeWriteFailed(Throwable failure) {
        WRITE_FAILURES.incrementAndGet(); fail(failure);
    }

    public static void runtimeStopEntered(PhoeniumLiveRuntime runtime) {
        if (runtime != RUNTIME.get()) return;
        RUNTIME_STOP_WITNESSES.incrementAndGet();
        STOP_WAIT_ORDER.compareAndSet(0, ORDER.incrementAndGet());
        if (!FullServerShutdown.failedStop()) fail(new IllegalStateException("runtime stop lacked failed-stop scope"));
        if (WRITE_COMPLETIONS.get() != 0) fail(new IllegalStateException("write completed before runtime settlement waited"));
        RegionalSaveCoordinator saves = runtime.regionalSaves();
        if (saves == null || saves.pending() != 1)
            fail(new IllegalStateException("runtime settlement did not retain exactly one native write"));
        FAILED_STOP_WAITING.countDown();
    }

    public static void shutdownFailure(MinecraftServer server, Throwable failure) {
        if (server != SERVER.get()) return;
        SHUTDOWN_FAILURES.incrementAndGet();
        if (containsIdentity(failure, EXPECTED_FAILURE)) EXPECTED_FAILURE_RETAINED.set(true);
        else
            fail(new IllegalStateException("shutdown lost the initiating callback identity", failure));
    }
    public static void nativeStopReturned(MinecraftServer server) {
        if (server != SERVER.get()) return;
        NATIVE_STOP_RETURNS.incrementAndGet();
        NATIVE_STOP_ORDER.compareAndSet(0, ORDER.incrementAndGet());
    }
    public static void levelCloseEntered(Object value) { cleanupEntered(value, EXPECTED_LEVELS); }
    public static void cacheCloseEntered(Object value) { cleanupEntered(value, EXPECTED_CACHES); }
    public static void mapCloseEntered(Object value) { cleanupEntered(value, EXPECTED_MAPS); }
    public static void dataCloseEntered(Object value) { cleanupEntered(value, EXPECTED_DATA); }
    public static void storageCloseEntered(Object value) { cleanupEntered(value, EXPECTED_STORAGE); }
    public static void resourcesCloseEntered(Object value) { cleanupEntered(value, EXPECTED_RESOURCES); }
    public static void levelClosed(Object value) { closed(value, EXPECTED_LEVELS, CLOSED_LEVELS); }
    public static void cacheClosed(Object value) { closed(value, EXPECTED_CACHES, CLOSED_CACHES); }
    public static void mapClosed(Object value) { closed(value, EXPECTED_MAPS, CLOSED_MAPS); }
    public static void dataClosed(Object value) { closed(value, EXPECTED_DATA, CLOSED_DATA); }
    public static void storageClosed(Object value) { closed(value, EXPECTED_STORAGE, CLOSED_STORAGE); }
    public static void resourcesClosed(Object value) { closed(value, EXPECTED_RESOURCES, CLOSED_RESOURCES); }

    private static void cleanupEntered(Object value, Set<Object> expected) {
        if (!expected.contains(value)) return;
        CLEANUP_ENTRIES.incrementAndGet();
        PhoeniumLiveRuntime runtime = RUNTIME.get();
        RegionalSaveCoordinator saves = runtime == null ? null : runtime.regionalSaves();
        if (WRITE_COMPLETIONS.get() != 1 || WRITE_COMPLETE_ORDER.get() == 0)
            fail(new IllegalStateException("native resource cleanup entered before the original write outcome"));
        if (saves == null || saves.pending() != 0 || value(saves.measurements(), "failedSettlements") != 1)
            fail(new IllegalStateException("native resource cleanup entered before terminal save acknowledgement"));
        if (runtime == null || !runtime.diagnostics().lifecycle().toString().equals("STOPPED")
                || runtime.diagnostics().activeLeases() != 0)
            fail(new IllegalStateException("native resource cleanup entered before runtime termination"));
        FIRST_CLEANUP_ENTRY_ORDER.compareAndSet(0, ORDER.incrementAndGet());
    }

    private static void closed(Object value, Set<Object> expected, Set<Object> actual) {
        if (expected.contains(value) && !actual.add(value)) fail(new IllegalStateException("native resource closed twice"));
    }

    private static void report() {
        RELEASE_WRITE.countDown();
        Thread controller = releaseController;
        if (controller != null) try { controller.join(5_000L); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); fail(interrupted); }
        PhoeniumLiveRuntime runtime = RUNTIME.get();
        FullFinishingServices finishing = FINISHING.get();
        Map<String, Long> saves = runtime == null || runtime.regionalSaves() == null
                ? Map.of() : runtime.regionalSaves().measurements();
        Map<String, Long> service = Map.of(), entities = Map.of(), scoreboard = Map.of(), legacy = Map.of();
        long serviceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (finishing != null) {
                service = finishing.measurements();
                entities = finishing.entityFacts.measurements();
                scoreboard = finishing.scoreboard.measurements();
                legacy = finishing.legacyOwners.measurements();
            }
            if (namedServicesClean(service, entities, scoreboard, legacy)) break;
            try { Thread.sleep(10L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); fail(interrupted); break; }
        } while (System.nanoTime() < serviceDeadline);
        String chunks = runtime == null ? "" : runtime.chunkBinding().diagnostics();
        int expectedCleanupEntries = expectedCleanupEntries();
        boolean cleanupAfterAcknowledgement = FIRST_CLEANUP_ENTRY_ORDER.get() > WRITE_COMPLETE_ORDER.get()
                && CLEANUP_ENTRIES.get() == expectedCleanupEntries;
        boolean order = STOP_WAIT_ORDER.get() > 0 && STOP_WAIT_ORDER.get() < WRITE_COMPLETE_ORDER.get()
                && WRITE_COMPLETE_ORDER.get() < FIRST_CLEANUP_ENTRY_ORDER.get()
                && FIRST_CLEANUP_ENTRY_ORDER.get() <= NATIVE_STOP_ORDER.get();
        boolean clean = runtime != null && runtime.diagnostics().lifecycle().toString().equals("STOPPED")
                && runtime.diagnostics().barrierTripped() && runtime.diagnostics().activeLeases() == 0
                && runtime.regionalDispatcher().pendingCount() == 0
                && value(saves, "pending") == 0 && value(saves, "failedSettlements") == 1
                && chunks.contains("retainedGraphs=0") && chunks.contains("returnedIndex=0")
                && chunks.contains("poiIndex=0") && chunks.contains("custodyEntries=0")
                && runtime.diagnostics().ownedEntities() == 0 && runtime.diagnostics().ownedPlayers() == 0
                && namedServicesClean(service, entities, scoreboard, legacy);
        boolean resources = complete(EXPECTED_LEVELS, CLOSED_LEVELS) && complete(EXPECTED_CACHES, CLOSED_CACHES)
                && complete(EXPECTED_MAPS, CLOSED_MAPS) && complete(EXPECTED_DATA, CLOSED_DATA)
                && complete(EXPECTED_STORAGE, CLOSED_STORAGE) && complete(EXPECTED_RESOURCES, CLOSED_RESOURCES);
        boolean pass = accepted(new Gate(PROBE_FAILURE.get() == null, ORIGINAL_SAVE_SELECTED.get(),
                CALLBACK_FAILURES.get(), WRITE_ENTRIES.get(), WRITE_COMPLETIONS.get(), WRITE_FAILURES.get(),
                RUNTIME_STOP_WITNESSES.get(), SHUTDOWN_FAILURES.get(), EXPECTED_FAILURE_RETAINED.get(),
                NATIVE_STOP_RETURNS.get(), cleanupAfterAcknowledgement, order, clean, resources));
        String line = "PHOENIUM_ADVERSE_SAVE_SHUTDOWN " + (pass ? "PASS" : "FAIL")
                + " expectedFailureIdentity=" + EXPECTED_FAILURE_RETAINED.get()
                + " callbackFailures=" + CALLBACK_FAILURES.get()
                + " originalWrites=" + WRITE_ENTRIES.get() + " writeOutcomes=" + WRITE_COMPLETIONS.get()
                + " writeFailures=" + WRITE_FAILURES.get() + " pendingAtFailedStop=" + RUNTIME_STOP_WITNESSES.get()
                + " pendingSaves=" + value(saves, "pending") + " failedSettlements=" + value(saves, "failedSettlements")
                + " dispatcherPending=" + (runtime == null ? -1 : runtime.regionalDispatcher().pendingCount())
                + " activeLeases=" + (runtime == null ? -1 : runtime.diagnostics().activeLeases())
                + " namedReservations=" + value(service, "reservations")
                + " entityAddressable=" + value(entities, "addressable")
                + " scoreboardSessions=" + value(scoreboard, "sessions")
                + " legacyPlans=" + value(legacy, "plans")
                + " legacyProducerTerminated=" + value(legacy, "producerTerminated")
                + " cleanupEntries=" + CLEANUP_ENTRIES.get() + "/" + expectedCleanupEntries
                + " resourceSets=" + CLOSED_LEVELS.size() + "/" + CLOSED_CACHES.size() + "/" + CLOSED_MAPS.size()
                + "/" + CLOSED_DATA.size() + "/" + CLOSED_STORAGE.size() + "/" + CLOSED_RESOURCES.size()
                + " orderedWaitWriteCleanup=" + order + " durablePersistenceClaim=false"
                + (PROBE_FAILURE.get() == null ? "" : " reason=" + PROBE_FAILURE.get());
        try (var out = new PrintStream(new FileOutputStream(FileDescriptor.out), true)) { out.println(line); }
    }

    private static boolean complete(Set<Object> expected, Set<Object> actual) {
        return !expected.isEmpty() && actual.size() == expected.size() && actual.containsAll(expected);
    }
    private static int expectedCleanupEntries() {
        return EXPECTED_LEVELS.size() + EXPECTED_CACHES.size() + EXPECTED_MAPS.size()
                + EXPECTED_DATA.size() + EXPECTED_STORAGE.size() + EXPECTED_RESOURCES.size();
    }
    private static boolean namedServicesClean(Map<String, Long> service, Map<String, Long> entities,
                                              Map<String, Long> scoreboard, Map<String, Long> legacy) {
        return value(service, "reservations") == 0 && value(service, "active") == 0
                && value(service, "worlds") == 0 && value(service, "environmentRequests") == 0
                && value(entities, "addressable") == 0
                && value(scoreboard, "sessions") == 0 && value(scoreboard, "catchup") == 0
                && value(legacy, "plans") == 0 && value(legacy, "queued") == 0
                && value(legacy, "running") == 0 && value(legacy, "cacheRoots") == 0
                && value(legacy, "producerTerminated") == 1;
    }
    static boolean accepted(Gate gate) {
        return gate.noUnexpectedFailure && gate.originalSaveSelected == 1 && gate.callbackFailures == 1
                && gate.writeEntries == 1 && gate.writeCompletions == 1 && gate.writeFailures == 0
                && gate.runtimeStopWitnesses == 1 && gate.shutdownFailures == 1 && gate.expectedFailureRetained
                && gate.nativeStopReturns == 1 && gate.cleanupAfterAcknowledgement && gate.ordered
                && gate.protocolClean && gate.resourcesComplete;
    }
    private static long value(Map<String, Long> values, String key) { return values.getOrDefault(key, -1L); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void fail(Throwable failure) { PROBE_FAILURE.compareAndSet(null, unwrap(failure)); }
    private static Throwable unwrap(Throwable failure) {
        return failure instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : failure;
    }
    private static boolean containsIdentity(Throwable root, Throwable expected) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return containsIdentity(root, expected, visited);
    }
    private static boolean containsIdentity(Throwable root, Throwable expected, Set<Throwable> visited) {
        if (root == expected) return true;
        if (root == null || !visited.add(root)) return false;
        if (containsIdentity(root.getCause(), expected, visited)) return true;
        for (Throwable suppressed : root.getSuppressed())
            if (containsIdentity(suppressed, expected, visited)) return true;
        return false;
    }
    private static Object field(Object target, Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Object invoke(Object target, Class<?> owner, String name, Class<?>[] types, Object... args) throws Throwable {
        var method = owner.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static Set<Object> identitySet() {
        return Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    }
    record Gate(boolean noUnexpectedFailure, int originalSaveSelected, int callbackFailures,
                int writeEntries, int writeCompletions, int writeFailures, int runtimeStopWitnesses,
                int shutdownFailures, boolean expectedFailureRetained, int nativeStopReturns,
                boolean cleanupAfterAcknowledgement, boolean ordered, boolean protocolClean,
                boolean resourcesComplete) { }
    private record Selection(ServerLevel level, ChunkMap map, ChunkHolder holder) { }
}
