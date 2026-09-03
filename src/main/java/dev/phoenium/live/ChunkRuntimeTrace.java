package dev.phoenium.live;

import dev.phoenium.Phoenium;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.topology.RegionRoute;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Dormant, bounded observation of selected vanilla chunk stages.
 *
 * <p>This class deliberately stores only strings, primitive identities, and immutable request
 * metadata. It never stores a chunk, holder, world, or future. The optional weak future map is
 * used only while correlating a returned future with its completion callback; its values do not
 * retain the future or any Minecraft object.</p>
 */
public final class ChunkRuntimeTrace {
    private static final boolean ENABLED = Boolean.getBoolean("phoenium.stage3.chunkTrace");
    private static final String TARGET_WORLD = System.getProperty(
        "phoenium.stage3.chunkTrace.world", "minecraft:overworld");
    private static final Set<ChunkCoordinate> TARGETS = parseTargets(
        System.getProperty("phoenium.stage3.chunkTrace.targets", ""));
    private static final int MAX_EVENTS = Math.max(100, Integer.getInteger(
        "phoenium.stage3.chunkTrace.maxEvents", 10_000));
    private static final boolean LAZY_DEMAND = Boolean.getBoolean(
        "phoenium.stage3.chunkTrace.lazyDemand");
    private static final Path OUTPUT = outputPath();

    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();
    private static final AtomicLong CORRELATION_SEQUENCE = new AtomicLong();
    private static final Map<ChunkCoordinate, RequestState> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<CompletableFuture<?>, RequestState> FUTURES =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final List<Event> EVENTS = new CopyOnWriteArrayList<>();
    private static final AtomicLong STORED_EVENTS = new AtomicLong();
    private static volatile boolean finished;

    private ChunkRuntimeTrace() { }

    public static boolean enabled() { return ENABLED && !TARGETS.isEmpty() && !finished; }

    public static String targetDescription() {
        return TARGET_WORLD + ":" + TARGETS.stream()
            .map(target -> target.x + "," + target.z)
            .sorted()
            .reduce((left, right) -> left + ";" + right)
            .orElse("none");
    }

    /** Called once after the Phoenium server lifecycle is ready. */
    public static void start() {
        // The method is intentionally a no-op beyond making the lifecycle boundary explicit.
        // Configuration is immutable and loaded from JVM properties before the server starts.
    }

    public static void futureRequested(ServerLevel level, int x, int z, String status) {
        if (!enabled()) return;
        try {
            ChunkCoordinate coordinate = coordinate(level, x, z);
            if (!selected(level, coordinate)) return;
            RequestState state = ACTIVE.compute(coordinate, (ignored, current) ->
                current == null || current.futureDone || "NOT_OBSERVED".equals(current.requestedStatus)
                    ? new RequestState(coordinate, status, CORRELATION_SEQUENCE.incrementAndGet())
                    : current.withRequestStatus(status));
            stage(state, "CHUNK_FUTURE_REQUEST", status, null, null, null, null);
        } catch (RuntimeException ignored) {
            // Observation must never change vanilla control flow.
        }
    }

    public static void futureReturned(ServerLevel level, int x, int z, String status,
                                      CompletableFuture<?> future) {
        if (!enabled()) return;
        try {
            ChunkCoordinate coordinate = coordinate(level, x, z);
            if (!selected(level, coordinate) || future == null) return;
            RequestState state = ACTIVE.computeIfAbsent(coordinate,
                ignored -> new RequestState(coordinate, status, CORRELATION_SEQUENCE.incrementAndGet()));
            state.withRequestStatus(status);
            stage(state, "CHUNK_FUTURE_RETURN", status, null, null, future, null);
            boolean attach;
            attach = FUTURES.putIfAbsent(future, state) == null;
            if (attach) {
                future.whenComplete((result, failure) -> futureCompleted(state, result, failure));
            }
        } catch (RuntimeException ignored) {
            // Observation must never change vanilla control flow.
        }
    }

    private static void futureCompleted(RequestState state, Object result, Throwable failure) {
        try {
            state.futureDone = true;
            state.futureSuccess = failure == null && result instanceof ChunkResult<?> chunkResult
                && chunkResult.isSuccess();
            state.futureError = failure == null && result instanceof ChunkResult<?> chunkResult
                ? safeError(chunkResult)
                : failure == null ? null : failure.getClass().getName();
            Object data = result instanceof ChunkResult<?> chunkResult ? safeResult(chunkResult) : result;
            stage(state, "CHUNK_FUTURE_COMPLETION", state.requestedStatus, data, null, null,
                state.futureError);
        } catch (RuntimeException ignored) {
            // Observation must never change vanilla control flow.
        }
    }

    public static void stage(String stage, ServerLevel level, ChunkPos pos, String detail,
                             Object data, Object holder) {
        if (!enabled() || level == null || pos == null) return;
        try {
            ChunkCoordinate coordinate = coordinate(level, pos.x(), pos.z());
            if (!selected(level, coordinate)) return;
            RequestState state = ACTIVE.get(coordinate);
            if (state == null && LAZY_DEMAND) {
                state = ACTIVE.computeIfAbsent(coordinate,
                    ignored -> new RequestState(coordinate, "CLIENT_DEMAND", CORRELATION_SEQUENCE.incrementAndGet()));
            }
            if (state == null || "NOT_OBSERVED".equals(state.requestedStatus)) return;
            stage(state, stage, detail, data, holder, null, null);
        } catch (RuntimeException ignored) {
            // Observation must never change vanilla control flow.
        }
    }

    /** Records a stage whose Minecraft method exposes only a chunk position, not a world. */
    public static void stageByTarget(String stage, ChunkPos pos, String detail,
                                     Object data, Object holder) {
        if (!enabled() || pos == null) return;
        try {
            ChunkCoordinate coordinate = new ChunkCoordinate(pos.x(), pos.z());
            if (!TARGETS.contains(coordinate)) return;
            RequestState state = ACTIVE.get(coordinate);
            if (state == null && LAZY_DEMAND) {
                state = ACTIVE.computeIfAbsent(coordinate,
                    ignored -> new RequestState(coordinate, "CLIENT_DEMAND", CORRELATION_SEQUENCE.incrementAndGet()));
            }
            if (state == null || "NOT_OBSERVED".equals(state.requestedStatus)) return;
            stage(state, stage, detail, data, holder, null, null);
        } catch (RuntimeException ignored) {
            // Observation must never change vanilla control flow.
        }
    }

    public static void stageForHolder(String stage, GenerationChunkHolder holder, String detail,
                                      Object data) {
        if (!enabled() || holder == null) return;
        try {
            stageByTarget(stage, holder.getPos(), detail, data, holder);
        } catch (RuntimeException ignored) {
            // Observation must never change vanilla control flow.
        }
    }

    public static String status(String world, int x, int z) {
        ChunkCoordinate coordinate = new ChunkCoordinate(x, z);
        RequestState state = ACTIVE.get(coordinate);
        if (!ENABLED) return "chunkTrace enabled=false";
        if (!TARGETS.contains(coordinate) || !TARGET_WORLD.equals(world)) {
            return "chunkTrace enabled=true selected=false target=" + targetDescription();
        }
        StringBuilder line = new StringBuilder("chunkTrace enabled=true selected=true target=")
            .append(world).append(":").append(x).append(",").append(z)
            .append(" events=").append(eventCount());
        if (state == null) {
            return line.append(" requestIncarnation=UNRESOLVED futureDone=false").toString();
        }
        return line.append(" correlationId=").append(state.correlationId)
            .append(" requestIncarnation=").append(state.requestIncarnation)
            .append(" futureDone=").append(state.futureDone)
            .append(" futureSuccess=").append(state.futureSuccess)
            .append(" lastStage=").append(state.lastStage == null ? "UNRESOLVED" : state.lastStage)
            .append(" futureError=").append(state.futureError == null ? "none" : state.futureError)
            .append(" demandObserved=").append(hasAnyStage(coordinate,
                "CHUNK_MAP_APPLY_TRACKING_VIEW_ADD", "CHUNK_MAP_UPDATE_CHUNK_SCHEDULING_ENTER"))
            .append(" fullObserved=").append(hasAnyStage(coordinate,
                "CHUNK_MAP_PREPARE_ACCESSIBLE_CHUNK_ENTER", "CHUNK_HOLDER_FULL_PROMOTION_ENTER"))
            .append(" tickingObserved=").append(hasAnyStage(coordinate,
                "CHUNK_MAP_PREPARE_TICKING_CHUNK_ENTER", "CHUNK_MAP_TICKING_CONVERSION_RETURN"))
            .append(" startTickingObserved=").append(hasStage(coordinate,
                "SERVER_LEVEL_START_TICKING_CHUNK_ENTER"))
            .append(" sendReadyObserved=").append(hasAnyStage(coordinate,
                "CHUNK_MAP_ON_CHUNK_READY_TO_SEND", "SERVER_CHUNK_CACHE_ON_CHUNK_READY_TO_SEND"))
            .append(" demandRemovedObserved=").append(hasStage(coordinate,
                "CHUNK_MAP_DROP_CHUNK_TO_CLIENT"))
            .append(" fullStatusChangeObserved=").append(hasStage(coordinate,
                "CHUNK_MAP_FULL_STATUS_CHANGE_ENTER"))
            .append(" demotionObserved=").append(directDemotionObserved(coordinate))
            .append(" unloadObserved=").append(targetHolderUnloadObserved(coordinate))
            .toString();
    }

    /**
     * Test-only, non-blocking snapshot of the vanilla holder/cache state. It is intentionally
     * read-only: no ticket, future, holder, or chunk is created, completed, replaced, or joined.
     */
    public static String inspect(ServerLevel level, int x, int z) {
        if (level == null) return "chunkTrace inspect level=UNRESOLVED";
        ChunkPos pos = new ChunkPos(x, z);
        var cache = level.getChunkSource();
        var map = cache.chunkMap;
        ChunkHolder holder = map.getUpdatingChunkIfPresent(pos.pack());
        ChunkStatus latest = map.getLatestStatus(pos.pack());
        LevelChunk loaded = cache.getChunkNow(x, z);
        RouteContext route = routeContext(new ChunkCoordinate(x, z));
        return "chunkTrace inspect world=" + level.dimension().identifier()
            + " chunk=" + x + "," + z
            + " route=" + (route.resolved ? route.region : "UNRESOLVED")
            + " generation=" + route.topologyGeneration
            + " ownerEpoch=" + route.ownerEpoch
            + " latestStatus=" + (latest == null ? "UNRESOLVED" : latest.getName())
            + " holderPresent=" + (holder != null)
            + " holderIdentity=" + identity(holder)
            + " ticketLevel=" + (holder == null ? "UNRESOLVED" : holder.getTicketLevel())
            + " fullFutureDone=" + futureDone(holder == null ? null : holder.getFullChunkFuture())
            + " tickingFutureDone=" + futureDone(holder == null ? null : holder.getTickingChunkFuture())
            + " sendSyncDone=" + futureDone(holder == null ? null : holder.getSendSyncFuture())
            + " getChunkNow=" + identity(loaded)
            + " hasChunk=" + cache.hasChunk(x, z);
    }

    public static void finish() {
        if (!ENABLED || OUTPUT == null || finished) return;
        finished = true;
        List<Event> snapshot;
        snapshot = List.copyOf(EVENTS);
        StringBuilder json = new StringBuilder("{\n");
        append(json, "trace", "STAGE3-CHUNK-RUNTIME");
        append(json, "enabled", true);
        append(json, "world", TARGET_WORLD);
        append(json, "targets", targetDescription());
        append(json, "maxEvents", MAX_EVENTS);
        append(json, "droppedEvents", Math.max(0, EVENT_SEQUENCE.get() - snapshot.size()));
        json.append("  \"events\": [");
        for (int i = 0; i < snapshot.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\n    ").append(snapshot.get(i).toJson());
        }
        if (!snapshot.isEmpty()) json.append("\n  ");
        json.append("]\n}\n");
        try {
            Path parent = OUTPUT.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(OUTPUT, json.toString());
        } catch (IOException | RuntimeException ignored) {
            // A failed evidence write cannot be allowed to change server shutdown semantics.
        }
    }

    public static int eventCount() {
        return EVENTS.size();
    }

    private static boolean futureDone(CompletableFuture<?> future) {
        return future != null && future.isDone();
    }

    private static boolean hasStage(ChunkCoordinate coordinate, String stage) {
        for (Event event : EVENTS) {
            if (event.coordinate.equals(coordinate) && event.stage.equals(stage)) return true;
        }
        return false;
    }

    private static boolean hasAnyStage(ChunkCoordinate coordinate, String... stages) {
        for (String stage : stages) if (hasStage(coordinate, stage)) return true;
        return false;
    }

    private static boolean directDemotionObserved(ChunkCoordinate coordinate) {
        String holderIdentity = latestReadyHolderIdentity(coordinate);
        return holderIdentity != null && EVENTS.stream().anyMatch(event ->
            event.coordinate.equals(coordinate)
                && "CHUNK_HOLDER_DEMOTE_FULL_CHUNK_ENTER".equals(event.stage)
                && holderIdentity.equals(event.holderIdentity));
    }

    private static boolean targetHolderUnloadObserved(ChunkCoordinate coordinate) {
        String holderIdentity = latestReadyHolderIdentity(coordinate);
        String chunkIdentity = latestReadyChunkIdentity(coordinate);
        if (holderIdentity == null && chunkIdentity == null) return false;
        return EVENTS.stream().anyMatch(event -> {
            if (!event.coordinate.equals(coordinate)) return false;
            boolean unloadStage = event.stage.startsWith("CHUNK_MAP_UNLOAD_")
                || event.stage.startsWith("CHUNK_MAP_SCHEDULE_UNLOAD")
                || event.stage.startsWith("SERVER_LEVEL_UNLOAD_CHUNK_");
            if (!unloadStage) return false;
            return holderIdentity != null && holderIdentity.equals(event.holderIdentity)
                || chunkIdentity != null && chunkIdentity.equals(event.dataIdentity);
        });
    }

    private static String latestReadyHolderIdentity(ChunkCoordinate coordinate) {
        for (int index = EVENTS.size() - 1; index >= 0; index--) {
            Event event = EVENTS.get(index);
            if (event.coordinate.equals(coordinate)
                    && "CHUNK_MAP_ON_CHUNK_READY_TO_SEND".equals(event.stage)) {
                return event.holderIdentity;
            }
        }
        return null;
    }

    private static String latestReadyChunkIdentity(ChunkCoordinate coordinate) {
        for (int index = EVENTS.size() - 1; index >= 0; index--) {
            Event event = EVENTS.get(index);
            if (event.coordinate.equals(coordinate)
                    && "CHUNK_MAP_ON_CHUNK_READY_TO_SEND".equals(event.stage)) {
                return event.dataIdentity;
            }
        }
        return null;
    }

    private static void stage(RequestState state, String stage, String detail, Object data,
                              Object holder, CompletableFuture<?> future, String exception) {
        state.lastStage = stage;
        Event event = new Event(
            EVENT_SEQUENCE.incrementAndGet(), state.coordinate, state.correlationId,
            state.requestIncarnation, state.requestedStatus, stage, detail,
            threadClass(), Thread.currentThread().getName(), Thread.currentThread().threadId(),
            identity(data), identity(holder), future == null ? state.futureDone : future.isDone(),
            state.futureSuccess, exception, routeContext(state.coordinate));
        if (STORED_EVENTS.getAndIncrement() < MAX_EVENTS) EVENTS.add(event);
    }

    private static RouteContext routeContext(ChunkCoordinate coordinate) {
        try {
            return Phoenium.liveRuntime().chunkTraceRoute(TARGET_WORLD, coordinate.x, coordinate.z);
        } catch (RuntimeException ignored) {
            return RouteContext.unresolved();
        }
    }

    private static ChunkCoordinate coordinate(ServerLevel level, int x, int z) {
        return new ChunkCoordinate(x, z);
    }

    private static boolean selected(ServerLevel level, ChunkCoordinate coordinate) {
        return level != null && TARGET_WORLD != null
            && TARGET_WORLD.equals(level.dimension().identifier().toString())
            && TARGETS.contains(coordinate);
    }

    private static String identity(Object value) {
        if (value == null) return null;
        return value.getClass().getName() + "#" + Integer.toUnsignedString(System.identityHashCode(value));
    }

    private static String safeError(ChunkResult<?> result) {
        try {
            return result.isSuccess() ? null : result.getError();
        } catch (RuntimeException ignored) {
            return "chunk-result-error-unreadable";
        }
    }

    private static Object safeResult(ChunkResult<?> result) {
        try {
            return result.orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String threadClass() {
        String name = Thread.currentThread().getName().toLowerCase();
        if (name.contains("server thread") || name.equals("main")) return "server-thread";
        if (name.contains("worldgen") || name.contains("generation")) return "generation-worker";
        if (name.contains("light")) return "lighting-worker";
        if (name.contains("io") || name.contains("parse")) return "io-worker";
        if (name.contains("worker") || name.contains("forkjoin") || name.contains("background")) return "background-worker";
        return "unknown";
    }

    private static Set<ChunkCoordinate> parseTargets(String spec) {
        Set<ChunkCoordinate> targets = new HashSet<>();
        for (String part : spec.split(";")) {
            String[] values = part.trim().split(",");
            if (values.length != 2) continue;
            try {
                targets.add(new ChunkCoordinate(Integer.parseInt(values[0].trim()), Integer.parseInt(values[1].trim())));
            } catch (NumberFormatException ignored) {
                // Ignore malformed opt-in targets; an empty selection is safer than tracing all chunks.
            }
        }
        return Set.copyOf(targets);
    }

    private static Path outputPath() {
        String configured = System.getProperty("phoenium.stage3.chunkTrace.output", "").trim();
        return configured.isEmpty() ? null : Path.of(configured).toAbsolutePath();
    }

    private static void append(StringBuilder json, String name, Object value) {
        json.append("  \"").append(escape(name)).append("\": ");
        if (value instanceof String string) json.append('"').append(escape(string)).append('"');
        else json.append(value);
        json.append(",\n");
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    public record RouteContext(boolean resolved, String region, long topologyGeneration, long ownerEpoch,
                               boolean ownershipContext, boolean regionLease, String contextRegion,
                               long leaseEpoch) {
        private static RouteContext unresolved() {
            return new RouteContext(false, "UNRESOLVED", -1, -1, false, false, null, -1);
        }
    }

    private record ChunkCoordinate(int x, int z) { }

    private static final class RequestState {
        private final ChunkCoordinate coordinate;
        private volatile String requestedStatus;
        private final long correlationId;
        private final long requestIncarnation;
        private volatile boolean futureDone;
        private volatile boolean futureSuccess;
        private volatile String futureError;
        private volatile String lastStage;

        private RequestState(ChunkCoordinate coordinate, String requestedStatus, long correlationId) {
            this.coordinate = coordinate;
            this.requestedStatus = requestedStatus == null ? "UNKNOWN" : requestedStatus;
            this.correlationId = correlationId;
            this.requestIncarnation = correlationId;
        }

        private RequestState withRequestStatus(String status) {
            if (status != null) requestedStatus = status;
            return this;
        }
    }

    private record Event(long sequence, ChunkCoordinate coordinate, long correlationId,
                         long requestIncarnation, String requestedStatus, String stage,
                         String detail, String threadClass, String thread, long threadId,
                         String dataIdentity, String holderIdentity, boolean futureDone,
                         boolean futureSuccess, String exception, RouteContext route) {
        private String toJson() {
            StringBuilder json = new StringBuilder("{");
            field(json, "sequence", sequence);
            field(json, "world", TARGET_WORLD);
            field(json, "chunk", coordinate.x + "," + coordinate.z);
            field(json, "chunkX", coordinate.x);
            field(json, "chunkZ", coordinate.z);
            field(json, "correlationId", correlationId);
            field(json, "requestIncarnation", requestIncarnation);
            field(json, "requestedStatus", requestedStatus);
            field(json, "requestIncarnationSource", "trace-assigned correlation; vanilla incarnation not exposed");
            field(json, "stage", stage);
            field(json, "detail", detail);
            field(json, "threadClass", threadClass);
            field(json, "thread", thread);
            field(json, "threadId", threadId);
            field(json, "executorContext", "thread-name-classification");
            field(json, "ownershipContext", route.ownershipContext);
            field(json, "regionLease", route.regionLease);
            field(json, "contextRegion", route.contextRegion);
            field(json, "leaseEpoch", route.leaseEpoch);
            field(json, "region", route.region);
            field(json, "topologyGeneration", route.topologyGeneration);
            field(json, "ownerEpoch", route.ownerEpoch);
            field(json, "holderIdentity", holderIdentity);
            field(json, "holderIncarnation", "NOT_EXPOSED_BY_VANILLA");
            field(json, "dataIdentity", dataIdentity);
            field(json, "futureDone", futureDone);
            field(json, "futureSuccess", futureSuccess);
            field(json, "exception", exception);
            field(json, "timestampNanos", System.nanoTime());
            if (json.charAt(json.length() - 1) == ',') json.setLength(json.length() - 1);
            return json.append('}').toString();
        }

        private static void field(StringBuilder json, String name, Object value) {
            if (json.length() > 1) json.append(',');
            json.append('"').append(escape(name)).append("\":");
            if (value == null) json.append("null");
            else if (value instanceof String string) json.append('"').append(escape(string)).append('"');
            else json.append(value);
        }
    }
}
