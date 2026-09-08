package dev.phoenium.stage4probe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import dev.phoenium.Phoenium;
import dev.phoenium.ownership.OwnershipContext;

/** Test-only ordered trace. It records original callbacks without changing their values. */
public final class Stage4Trace {
    private static final Object LOCK = new Object();
    private static final List<String> EVENTS = new ArrayList<>();
    private static volatile boolean active;
    private static volatile long startedAt = -1;
    private static volatile String world = "unknown";
    private static volatile long lastGameTime = -1;
    private static volatile Path output;
    private static volatile long barrierTarget = -1;
    private static volatile String barrierLabel = "";
    private static volatile long stepTarget = -1;
    private static volatile String stepLabel = "";
    private static volatile boolean reloadMode;
    private static volatile boolean lifecycleRequested;
    private static volatile boolean trackingLifecycleRequested;
    private static volatile boolean blockRecipientRequested;
    private static volatile boolean fullReadinessRequested;
    private static volatile boolean deferredEligibilityRequested;
    private static volatile boolean deferredIneligibleObserved;
    private static volatile boolean deferredEligibleObserved;
    private static volatile long restoredUnpackCurrent = -1;
    private static volatile long restoredUnpackTrigger = -1;
    private static volatile int restoredUnpackMatches;
    private static final Set<Integer> changeSeen = new HashSet<>();
    private static final Map<String, BlockPos> BLOCK_PROBES = new LinkedHashMap<>();
    private static final Map<String, Integer> BLOCK_PROBE_A = new LinkedHashMap<>();
    private static final Map<String, Integer> BLOCK_PROBE_B = new LinkedHashMap<>();
    private static final Map<String, Integer> BLOCK_PROBE_PACKETS = new LinkedHashMap<>();
    private static final ThreadLocal<BlockBroadcast> ACTIVE_BLOCK_BROADCAST = new ThreadLocal<>();

    private record BlockBroadcast(Player source, double x, double y, double z, double radius,
                                   ResourceKey<Level> dimension) { }
    private Stage4Trace() { }

    public static void begin(ServerLevel level) {
        synchronized (LOCK) {
            EVENTS.clear();
            active = true;
            startedAt = level.getGameTime();
            lastGameTime = startedAt;
            barrierTarget = -1;
            barrierLabel = "";
            stepTarget = -1;
            stepLabel = "";
            reloadMode = false;
            lifecycleRequested = false;
            trackingLifecycleRequested = false;
            blockRecipientRequested = false;
            fullReadinessRequested = false;
            deferredEligibilityRequested = false;
            deferredIneligibleObserved = false;
            deferredEligibleObserved = false;
            changeSeen.clear();
            BLOCK_PROBES.clear();
            BLOCK_PROBE_A.clear();
            BLOCK_PROBE_B.clear();
            BLOCK_PROBE_PACKETS.clear();
            world = level.dimension().identifier().toString();
            output = Path.of(System.getProperty("phoenium.stage4.tracePath", "run/stage4/acceptance/trace.json"));
            event("trace.begin", "world=" + level.dimension() + " gameTime=" + startedAt);
        }
    }

    public static boolean active() { return active; }

    public static void beginReload(ServerLevel level) {
        begin(level);
        reloadMode = true;
        if (restoredUnpackMatches > 0)
            event("tick.unpack.target", "currentTick=" + restoredUnpackCurrent
                + " trigger=" + restoredUnpackTrigger + " delay="
                + (restoredUnpackTrigger - restoredUnpackCurrent) + " matches=" + restoredUnpackMatches);
        if (restoredUnpackMatches > 0)
            System.out.println("STAGE4_TRACE_RELOAD_UNPACK currentTick=" + restoredUnpackCurrent
                + " trigger=" + restoredUnpackTrigger + " delay="
                + (restoredUnpackTrigger - restoredUnpackCurrent) + " matches=" + restoredUnpackMatches);
        event("trace.reload", "gameTime=" + level.getGameTime());
        System.out.println("STAGE4_TRACE_RELOAD gameTime=" + level.getGameTime());
    }

    /** Records the native LevelChunkTicks unpack edge, including the restored relative delay. */
    public static void observeUnpack(net.minecraft.world.ticks.LevelChunkTicks<?> container,
                                     long currentTick,
                                     List<? extends net.minecraft.world.ticks.SavedTick<?>> pendingBefore) {
        if (pendingBefore == null) return;
        if (active) for (var saved : pendingBefore) if (fixture(saved.pos())) {
            System.out.println("STAGE4_TRACE_FIXTURE_UNPACK pos=" + saved.pos() + " type=" + saved.type()
                + " delay=" + saved.delay() + " currentTick=" + currentTick);
            event("tick.native.unpack", "pos=" + saved.pos() + " type=" + saved.type()
                + " delay=" + saved.delay() + " currentTick=" + currentTick + " priority=" + saved.priority());
        }
        List<? extends net.minecraft.world.ticks.SavedTick<?>> pending = pendingBefore.stream()
            .filter(tick -> tick.pos().equals(Stage4AcceptanceProbe.REGION_B_FUTURE)
                && tick.type() == net.minecraft.world.level.block.Blocks.STONE_BUTTON)
            .toList();
        if (pending.isEmpty()) return;
        List<? extends net.minecraft.world.ticks.ScheduledTick<?>> scheduled = container.getAll()
            .filter(tick -> tick.pos().equals(Stage4AcceptanceProbe.REGION_B_FUTURE)
                && tick.type() == net.minecraft.world.level.block.Blocks.STONE_BUTTON)
            .toList();
        restoredUnpackCurrent = currentTick;
        restoredUnpackMatches = pending.size();
        restoredUnpackTrigger = -1;
        if (pending.size() == 1 && scheduled.size() == 1) {
            var saved = pending.get(0);
            var unpacked = scheduled.get(0);
            if (unpacked.triggerTick() == currentTick + saved.delay()
                && unpacked.priority() == saved.priority()) restoredUnpackTrigger = unpacked.triggerTick();
        }
        if (active) event("tick.unpack.target", "currentTick=" + currentTick
            + " trigger=" + restoredUnpackTrigger + " delay="
            + (restoredUnpackTrigger < 0 ? -1 : restoredUnpackTrigger - currentTick)
            + " matches=" + pending.size() + " scheduled=" + scheduled.size());
    }

    public static long restoredUnpackCurrent() { return restoredUnpackCurrent; }
    public static long restoredUnpackTrigger() { return restoredUnpackTrigger; }
    public static int restoredUnpackMatches() { return restoredUnpackMatches; }

    public static boolean restoredCallbackObserved() {
        return snapshot().stream().anyMatch(item -> item.startsWith("effect.tick.reached "));
    }

    public static long restoredCallbackCount() {
        return snapshot().stream().filter(item -> item.startsWith("effect.tick.reached ")).count();
    }

    public static void reloadEligibility(ServerLevel level, boolean eligible) {
        event("tick.reload.eligibility", "pos=" + Stage4AcceptanceProbe.REGION_B_FUTURE
            + " eligible=" + eligible + " gameTime=" + level.getGameTime());
        System.out.println("STAGE4_TRACE_RELOAD_ELIGIBILITY pos="
            + Stage4AcceptanceProbe.REGION_B_FUTURE + " eligible=" + eligible
            + " gameTime=" + level.getGameTime());
        if (!eligible)
            throw new IllegalStateException("restored target is not natively block-ticking at reload");
    }

    public static void barrier(ServerLevel level, String label) {
        event("trace.barrier", "label=" + label + " gameTime=" + level.getGameTime());
        System.out.println("STAGE4_TRACE_BARRIER label=" + label + " gameTime=" + level.getGameTime());
    }

    public static void await(ServerLevel level, String label, int ticks) {
        barrierLabel = label;
        barrierTarget = level.getGameTime() + Math.max(1, ticks);
        event("trace.await", "label=" + label + " target=" + barrierTarget);
    }

    public static void clockFrozen(ServerLevel level) {
        event("clock.freeze", "gameTime=" + level.getGameTime());
        System.out.println("STAGE4_TRACE_CLOCK_FROZEN gameTime=" + level.getGameTime());
    }

    /** Records the native reload freeze after the trace has been armed. */
    public static void reloadClockFrozen(ServerLevel level) {
        event("clock.reload.freeze", "gameTime=" + level.getGameTime());
        System.out.println("STAGE4_TRACE_RELOAD_CLOCK_FROZEN gameTime=" + level.getGameTime());
    }

    /**
     * Observe the original ServerLevel block ticking predicate at a controlled boundary.
     * This deliberately calls the native predicate itself; it neither creates a ticket nor
     * asks the regional service for a substitute answer.  The shared reference and candidate
     * workload use the same two observations around the real viewport transition.
     */
    public static boolean observeDeferredEligibility(ServerLevel level, String phase, boolean expected) {
        boolean eligible = level.shouldTickBlocksAt(Stage4AcceptanceProbe.REGION_DEFERRED_EVENT);
        deferredEligibilityRequested = true;
        if (expected) deferredEligibleObserved = true;
        else deferredIneligibleObserved = true;
        event("block.deferred.eligibility", "phase=" + phase + " eligible=" + eligible
            + " expected=" + expected + " pos=" + Stage4AcceptanceProbe.REGION_DEFERRED_EVENT
            + " gameTime=" + level.getGameTime());
        System.out.println("STAGE4_TRACE_DEFERRED_ELIGIBILITY phase=" + phase + " pos="
            + Stage4AcceptanceProbe.REGION_DEFERRED_EVENT + " eligible=" + eligible
            + " expected=" + expected + " gameTime=" + level.getGameTime());
        if (eligible != expected)
            throw new IllegalStateException("deferred native eligibility mismatch phase=" + phase
                + " expected=" + expected + " actual=" + eligible);
        return eligible;
    }

    /** Arms an exact native frozen-tick receipt before requesting ServerTickRateManager steps. */
    public static void clockStep(ServerLevel level, String label, int ticks) {
        if (stepTarget >= 0) throw new IllegalStateException("native tick step already pending: " + stepLabel);
        stepLabel = label;
        stepTarget = level.getGameTime() + ticks;
        event("clock.step", "label=" + label + " ticks=" + ticks + " target=" + stepTarget);
    }

    public static void observeTick(long gameTime) {
        if (!active) return;
        if (stepTarget >= 0 && gameTime >= stepTarget) {
            String label = stepLabel;
            stepTarget = -1;
            stepLabel = "";
            event("clock.step.reached", "label=" + label + " gameTime=" + gameTime);
            System.out.println("STAGE4_TRACE_CLOCK_STEP_REACHED label=" + label + " gameTime=" + gameTime);
        }
        if (barrierTarget < 0 || gameTime < barrierTarget) return;
        String label = barrierLabel;
        long reached = gameTime;
        barrierTarget = -1;
        barrierLabel = "";
        event("trace.barrier.reached", "label=" + label + " gameTime=" + reached);
        System.out.println("STAGE4_TRACE_BARRIER label=" + label + " gameTime=" + reached);
    }

    public static void trackingPacket(String operation, Entity entity, Object packet) {
        if (!relevantEntity(entity) || packet == null) return;
        long gameTime = entity.level() instanceof ServerLevel level ? level.getGameTime() : -1;
        event("tracking.packet", "operation=" + operation + " entityId=" + entity.getId()
            + " uuid=" + entity.getUUID()
            + " packet=" + packet.getClass().getName() + " gameTime=" + gameTime
            + " " + authority(entity.level() instanceof ServerLevel level ? level : null, entity.blockPosition()));
    }

    public static void trackingDelivered(Entity entity, ServerPlayer recipient, Object packet) {
        if (!relevantEntity(entity)) return;
        var fields = new java.util.TreeMap<String, Object>();
        for (Class<?> type = packet.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (!field.getType().isPrimitive()) continue;
                try { field.setAccessible(true); fields.put(field.getName(), field.get(packet)); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            }
        }
        event("tracking.delivered", "recipient=" + recipient.getName().getString() + " uuid=" + entity.getUUID()
            + " entityId=" + entity.getId() + " packet=" + packet.getClass().getName() + " fields=" + fields
            + " gameTime=" + entity.level().getGameTime());
    }

    public static void trackingChanges(Entity entity) {
        if (!relevantEntity(entity)) return;
        synchronized (LOCK) {
            if (!changeSeen.add(entity.getId())) return;
        }
        long gameTime = entity.level() instanceof ServerLevel level ? level.getGameTime() : -1;
        event("tracking.changes", "entityId=" + entity.getId()
            + " uuid=" + entity.getUUID()
            + " pos=" + entity.blockPosition() + " gameTime=" + gameTime
            + " " + authority(entity.level() instanceof ServerLevel level ? level : null, entity.blockPosition()));
    }

    public static boolean relevantEntity(Entity entity) {
        return entity != null && (entity.entityTags().contains("stage4_trace_a")
            || entity.entityTags().contains("stage4_trace_b")
            || entity.entityTags().contains("stage4_trace_b_far"));
    }

    public static boolean reloadEffect(BlockPos pos) {
        return active && reloadMode && pos.equals(new BlockPos(120, 65, 9));
    }

    public static void event(String kind, String detail) {
        if (!active) return;
        synchronized (LOCK) { EVENTS.add(kind + " " + detail); }
    }

    public static void event(String kind) { event(kind, ""); }

    /** Marks that the installed lifecycle fixture must prove coordinator-backed merge and split. */
    public static void requestLifecycle() { lifecycleRequested = true; }

    /** Enables the client fixture's actual PlayerList.broadcast recipient gate. */
    public static void requestBlockRecipient() { blockRecipientRequested = true; }

    /** Enables the client fixture's per-owner tracking-body gate. */
    public static void requestTrackingLifecycle() { trackingLifecycleRequested = true; }

    /** Enables the gate requiring authentic holder FULL/ticking futures for the final viewport. */
    public static void fullReadiness(String label, int centerX, int centerZ, int radius,
                                     int targetCount, int candidateCount) {
        fullReadinessRequested = true;
        event("full.ready", "label=" + label + " center=" + centerX + "," + centerZ
            + " radius=" + radius + " target=" + targetCount + " candidates=" + candidateCount);
    }

    /** Owner and immutable route generation observed at an actual callback body. */
    public static String authority(ServerLevel level, BlockPos pos) {
        String context = OwnershipContext.current().map(active ->
            "contextRegion=" + active.region().value() + " lease=" + active.lease().value()
                + " category=" + active.category()).orElse("contextRegion=NONE");
        if (level == null || pos == null) return context + " route=UNAVAILABLE";
        if (!Boolean.getBoolean("phoenium.stage4.candidate")) return context + " route=REFERENCE";
        String route = Phoenium.liveRuntime().routeDiagnostic(level.dimension().identifier().toString(),
            pos.getX() >> 4, pos.getZ() >> 4);
        return context + " " + route;
    }

    /** The PlayerList distance loop calls send() only for an actual in-range recipient. */
    public static void blockEventRecipient(Player source, ServerPlayer recipient, Packet<?> packet,
                                           double x, double y, double z, double radius,
                                           ResourceKey<Level> dimension) {
        if (!active || !(packet instanceof ClientboundBlockEventPacket)) return;
        synchronized (LOCK) {
            for (var entry : BLOCK_PROBES.entrySet()) {
                if (entry.getValue().getX() == (int) x && entry.getValue().getY() == (int) y
                        && entry.getValue().getZ() == (int) z)
                    BLOCK_PROBE_PACKETS.merge(entry.getKey(), 1, Integer::sum);
            }
        }
        event("block.recipient.packet", "recipient=" + recipient.getName().getString()
            + " packet=" + packet.getClass().getName() + " center=" + x + "," + y + "," + z
            + " radius=" + radius + " dimension=" + dimension
            + " recipientState=connection-send");
    }

    /** Keeps the original broadcast arguments available while its wrapped send calls run. */
    public static void beginBlockEventBroadcast(Player source, double x, double y, double z,
                                                double radius, ResourceKey<Level> dimension) {
        ACTIVE_BLOCK_BROADCAST.set(new BlockBroadcast(source, x, y, z, radius, dimension));
    }

    public static void endBlockEventBroadcast() {
        ACTIVE_BLOCK_BROADCAST.remove();
    }

    public static void blockEventRecipient(ServerPlayer recipient, Packet<?> packet) {
        BlockBroadcast broadcast = ACTIVE_BLOCK_BROADCAST.get();
        if (broadcast != null)
            blockEventRecipient(broadcast.source(), recipient, packet, broadcast.x(), broadcast.y(),
                broadcast.z(), broadcast.radius(), broadcast.dimension());
    }

    public static void beginBlockProbe(String label, BlockPos pos, int paramA, int paramB) {
        synchronized (LOCK) {
            BLOCK_PROBES.put(label, pos);
            BLOCK_PROBE_A.put(label, paramA);
            BLOCK_PROBE_B.put(label, paramB);
            BLOCK_PROBE_PACKETS.put(label, 0);
        }
        event("block.probe", "label=" + label + " pos=" + pos + " a=" + paramA + " b=" + paramB);
    }

    public static void checkBlockProbe(String label, boolean expectRecipient) {
        int packets;
        BlockPos position;
        int paramA;
        int paramB;
        synchronized (LOCK) {
            packets = BLOCK_PROBE_PACKETS.getOrDefault(label, 0);
            position = BLOCK_PROBES.get(label);
            paramA = BLOCK_PROBE_A.getOrDefault(label, Integer.MIN_VALUE);
            paramB = BLOCK_PROBE_B.getOrDefault(label, Integer.MIN_VALUE);
        }
        List<String> events = snapshot();
        int probeStart = -1;
        String probeMarker = "block.probe label=" + label + " pos=" + position;
        for (int index = 0; index < events.size(); index++)
            if (events.get(index).startsWith(probeMarker)) probeStart = index;
        String dispatchPrefix = "block.dispatch.result pos=" + position + " a=" + paramA + " b=" + paramB;
        boolean dispatchAccepted = probeStart >= 0 && events.subList(probeStart + 1, events.size()).stream().anyMatch(item ->
            item.startsWith(dispatchPrefix) && item.contains("result=true"));
        boolean passed = dispatchAccepted && (expectRecipient ? packets > 0 : packets == 0);
        event("block.probe.result", "label=" + label + " packets=" + packets + " dispatchAccepted="
            + dispatchAccepted + " expected=" + (expectRecipient ? "recipient" : "none") + " pass=" + passed);
    }

    /** The deferred fixture event is observable through its real server callback. */
    public static boolean checkDeferredEvent() {
        String prefix = "block.dispatch pos=BlockPos{x=520, y=65, z=200}";
        int reached = 0;
        for (String item : snapshot())
            if (item.startsWith(prefix) && item.contains("a=3 b=11")) reached++;
        boolean pass = reached == 1;
        event("block.deferred.result", "pos=BlockPos{x=520, y=65, z=200} reached=" + reached + " pass=" + pass);
        System.out.println("STAGE4_TRACE_DEFERRED_CHECK pass=" + pass + " reached=" + reached);
        return pass;
    }

    /** Confirms that the two far obligations actually reached their native callbacks
     * during the controlled post-transition step. */
    public static boolean checkFarEffects() {
        int block = 0;
        int fluid = 0;
        for (String item : snapshot()) {
            if (item.startsWith("tick.block.vanilla pos=" + Stage4AcceptanceProbe.REGION_B_FAR_BLOCK)) block++;
            if (item.startsWith("tick.fluid pos=BlockPos{x=521, y=65, z=8}")) fluid++;
        }
        boolean pass = block == 1 && fluid > 0;
        event("tick.migrated.result", "block=" + block + " fluid=" + fluid + " pass=" + pass);
        System.out.println("STAGE4_TRACE_FAR_CHECK pass=" + pass + " block=" + block + " fluid=" + fluid);
        return pass;
    }

    /** Records a callback observed after the real coordinator transition. */
    public static void bodyObserved(ServerLevel level, BlockPos pos, String callback) {
        if (lifecycleRequested) event("topology.body", "callback=" + callback + " pos=" + pos + " " + authority(level, pos));
    }

    public static void trackingBodyObserved(Entity entity, String callback) {
        if (!trackingLifecycleRequested || !(entity.level() instanceof ServerLevel level)) return;
        event("tracking.body", "callback=" + callback + " entityId=" + entity.getId()
            + " pos=" + entity.blockPosition() + " " + authority(level, entity.blockPosition()));
    }

    /** Record only the detached result returned by the original serialization callback. */
    public static void packedTicks(ChunkAccess chunk, long gameTime, ChunkAccess.PackedTicks packed) {
        if (!active || packed == null) return;
        event("save.serialization.ticks", "chunk=" + chunk.getPos() + " gameTime=" + gameTime
            + " blocks=" + packed.blocks() + " fluids=" + packed.fluids()
            + " " + packedAuthority(chunk));
    }

    /** Serializer evidence uses only detached packed data plus route/context metadata. */
    private static String packedAuthority(ChunkAccess chunk) {
        String context = OwnershipContext.current().map(active ->
            "contextRegion=" + active.region().value() + " lease=" + active.lease().value()
                + " category=" + active.category()).orElse("contextRegion=NONE");
        String route = Boolean.getBoolean("phoenium.stage4.candidate")
            ? Phoenium.liveRuntime().routeDiagnostic("minecraft:overworld", chunk.getPos().x(), chunk.getPos().z())
            : "route=REFERENCE";
        return context + " " + route;
    }

    public static String block(Block block) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    /** Positions owned by the two-region fixture; background world activity is excluded. */
    public static boolean fixture(BlockPos pos) {
        return pos.equals(new BlockPos(8, 65, 8)) || pos.equals(new BlockPos(120, 65, 8))
            || pos.equals(new BlockPos(120, 65, 9))
            || pos.equals(new BlockPos(520, 65, 8))
            || pos.equals(new BlockPos(520, 65, 200))
            || pos.equals(new BlockPos(521, 65, 8))
            || pos.equals(Stage4AcceptanceProbe.REGION_B_FAR_BLOCK)
            || pos.equals(new BlockPos(121, 65, 8)) || pos.equals(new BlockPos(8, 66, 8));
    }

    public static List<String> snapshot() {
        synchronized (LOCK) { return List.copyOf(EVENTS); }
    }

    public static Result finish(ServerLevel level) {
        List<String> events = snapshot();
        lastGameTime = level.getGameTime();
        List<String> missing = missing(events, false);
        writeReport("PARTIAL", missing, events);
        return new Result(missing.isEmpty(), missing, events.size(), output);
    }

    /** Records entry before the original stop method; completion is recorded only after it returns. */
    public static void serverStopBeginning() {
        if (active) event("server.stop.begin");
    }

    /** Called only after the original stop method returns normally. */
    public static void serverStopCompleted() {
        if (!active) return;
        event("server.stop");
        List<String> events = snapshot();
        List<String> missing = missing(events, true);
        writeReport(missing.isEmpty() ? "PASS" : "PARTIAL", missing, events);
        active = false;
    }

    private static List<String> missing(List<String> events, boolean includeStop) {
        List<String> missing = new ArrayList<>();
        require(events, "tick.schedule", missing);
        requireAny(events, missing, "tick.block", "tick.block.vanilla");
        require(events, "tick.fluid", missing);
        require(events, "block.enqueue", missing);
        require(events, "block.dispatch", missing);
        requireContains(events, "tick.pending.input", "present=true", "tick.pending.input", missing);
        requireContains(events, "tick.migrated.result", "pass=true", "tick.migrated.result", missing);
        requireContains(events, "block.deferred.result", "pass=true", "block.deferred.result", missing);
        require(events, "tracking.pair", missing);
        require(events, "tracking.remove", missing);
        require(events, "tracking.recipient", missing);
        require(events, "tracking.changes", missing);
        require(events, "save.enter", missing);
        require(events, "save.exit", missing);
        if (blockRecipientRequested) require(events, "block.recipient.packet", missing);
        if (blockRecipientRequested) {
            if (events.stream().noneMatch(item -> item.startsWith("block.probe.result label=a ")
                    && item.contains("packets=0") && item.contains("dispatchAccepted=true")
                    && item.contains("expected=none") && item.contains("pass=true")))
                missing.add("block.probe.a.noRecipient");
            if (events.stream().noneMatch(item -> item.startsWith("block.probe.result label=b ")
                    && item.contains("packets=") && item.contains("dispatchAccepted=true")
                    && item.contains("expected=recipient") && item.contains("pass=true")))
                missing.add("block.probe.b.recipient");
        }
        if (trackingLifecycleRequested) {
            require(events, "tracking.body", missing);
            requireContains(events, "tracking.body", "contextRegion=stage3-fixture-a",
                "tracking.body.owner.a", missing);
            requireContains(events, "tracking.body", "contextRegion=stage3-fixture-b",
                "tracking.body.owner.b", missing);
        }
        if (lifecycleRequested) {
            require(events, "topology.merge", missing);
            require(events, "topology.split", missing);
            require(events, "topology.body", missing);
            requireContains(events, "topology.body", "contextRegion=stage3-fixture-a", "topology.body.owner.a", missing);
            requireContains(events, "topology.body", "contextRegion=stage3-fixture-b", "topology.body.owner.b", missing);
            requireBodyOwner(events, "block.tick", "stage3-fixture-a", "service.block.owner.a", missing);
            requireBodyOwner(events, "block.tick", "stage3-fixture-b", "service.block.owner.b", missing);
            requireBodyOwner(events, "fluid.tick", "stage3-fixture-a", "service.fluid.owner.a", missing);
            requireBodyOwner(events, "fluid.tick", "stage3-fixture-b", "service.fluid.owner.b", missing);
            requireBodyOwner(events, "block.dispatch", "stage3-fixture-a", "service.event.owner.a", missing);
            requireBodyOwner(events, "block.dispatch", "stage3-fixture-b", "service.event.owner.b", missing);
            requireContains(events, "save.serialization.ticks", "contextRegion=stage3-fixture-a", "save.owner.a", missing);
            requireContains(events, "save.serialization.ticks", "contextRegion=stage3-fixture-b", "save.owner.b", missing);
            requireContains(events, "topology.split", "fluids=Measurements[", "service.fluid.split", missing);
            require(events, "block.deferred", missing);
            if (fullReadinessRequested) {
                requireContains(events, "full.ready", "label=far ", "full.ready.far", missing);
                requireContains(events, "full.ready", "label=deferred ", "full.ready.deferred", missing);
                require(events, "clock.freeze", missing);
                requireContains(events, "clock.step.reached", "label=post-split ", "clock.step.post-split", missing);
            }
        }
        if (deferredEligibilityRequested) {
            requireContains(events, "block.deferred.eligibility", "phase=before-viewport eligible=false expected=false",
                "block.deferred.ineligible", missing);
            requireContains(events, "block.deferred.eligibility", "phase=after-viewport eligible=true expected=true",
                "block.deferred.eligible", missing);
        }
        if (includeStop) require(events, "server.stop", missing);
        return missing;
    }

    private static void writeReport(String result, List<String> missing, List<String> events) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("kind", "stage4-original-service-trace");
        report.put("minecraft", "26.2");
        report.put("world", world);
        report.put("gameTime", lastGameTime);
        report.put("startedAt", startedAt);
        report.put("events", events);
        report.put("result", result);
        report.put("missing", missing);
        write(report);
    }

    private static void require(List<String> events, String marker, List<String> missing) {
        if (events.stream().noneMatch(event -> event.startsWith(marker + " ") || event.equals(marker))) missing.add(marker);
    }

    private static void requireAny(List<String> events, List<String> missing, String... markers) {
        for (String marker : markers) {
            if (events.stream().anyMatch(event -> event.startsWith(marker + " ") || event.equals(marker))) return;
        }
        missing.add(markers[0]);
    }

    private static void requireContains(List<String> events, String marker, String text,
                                        String missingMarker, List<String> missing) {
        if (events.stream().noneMatch(event -> (event.startsWith(marker + " ") || event.equals(marker))
                && event.contains(text))) missing.add(missingMarker);
    }

    private static void requireBodyOwner(List<String> events, String callback, String owner,
                                         String missingMarker, List<String> missing) {
        requireContains(events, "topology.body", "callback=" + callback,
            missingMarker + ".callback", missing);
        requireContains(events, "topology.body", "callback=" + callback + " ",
            missingMarker + ".owner", missing);
        if (events.stream().noneMatch(event -> event.startsWith("topology.body ")
                && event.contains("callback=" + callback + " ")
                && event.contains("contextRegion=" + owner))) missing.add(missingMarker);
    }

    private static void write(Map<String, Object> report) {
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            StringBuilder json = new StringBuilder("{\n");
            for (var entry : report.entrySet()) {
                json.append("  \"").append(entry.getKey()).append("\": ");
                if (entry.getValue() instanceof List<?> list) {
                    json.append("[");
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) json.append(",");
                        json.append("\"").append(escape(String.valueOf(list.get(i)))).append("\"");
                    }
                    json.append("]");
                } else if (entry.getValue() instanceof Boolean || entry.getValue() instanceof Number) json.append(entry.getValue());
                else json.append("\"").append(escape(String.valueOf(entry.getValue()))).append("\"");
                json.append(",\n");
            }
            if (json.toString().endsWith(",\n")) json.setLength(json.length() - 2);
            json.append("\n}\n");
            Files.writeString(output, json);
        } catch (IOException failure) {
            event("trace.write.failure", failure.toString());
        }
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }

    public record Result(boolean pass, List<String> missing, int eventCount, Path output) { }
}
