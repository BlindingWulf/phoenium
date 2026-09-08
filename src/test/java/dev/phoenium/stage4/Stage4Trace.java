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
    private static volatile boolean reloadMode;
    private static final Set<Integer> changeSeen = new HashSet<>();
    private Stage4Trace() { }

    public static void begin(ServerLevel level) {
        synchronized (LOCK) {
            EVENTS.clear();
            active = true;
            startedAt = level.getGameTime();
            lastGameTime = startedAt;
            barrierTarget = -1;
            barrierLabel = "";
            reloadMode = false;
            changeSeen.clear();
            world = level.dimension().identifier().toString();
            output = Path.of(System.getProperty("phoenium.stage4.tracePath", "run/stage4/acceptance/trace.json"));
            event("trace.begin", "world=" + level.dimension() + " gameTime=" + startedAt);
        }
    }

    public static boolean active() { return active; }

    public static void beginReload(ServerLevel level) {
        begin(level);
        reloadMode = true;
        event("trace.reload", "gameTime=" + level.getGameTime());
        System.out.println("STAGE4_TRACE_RELOAD gameTime=" + level.getGameTime());
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

    public static void observeTick(long gameTime) {
        if (!active || barrierTarget < 0 || gameTime < barrierTarget) return;
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
            + " packet=" + packet.getClass().getName() + " gameTime=" + gameTime);
    }

    public static void trackingChanges(Entity entity) {
        if (!relevantEntity(entity)) return;
        synchronized (LOCK) {
            if (!changeSeen.add(entity.getId())) return;
        }
        long gameTime = entity.level() instanceof ServerLevel level ? level.getGameTime() : -1;
        event("tracking.changes", "entityId=" + entity.getId()
            + " pos=" + entity.blockPosition() + " gameTime=" + gameTime);
    }

    public static boolean relevantEntity(Entity entity) {
        return entity != null && (entity.entityTags().contains("stage4_trace_a")
            || entity.entityTags().contains("stage4_trace_b"));
    }

    public static boolean reloadEffect(BlockPos pos) {
        return active && reloadMode && pos.equals(new BlockPos(120, 65, 9));
    }

    public static void event(String kind, String detail) {
        if (!active) return;
        synchronized (LOCK) { EVENTS.add(kind + " " + detail); }
    }

    public static void event(String kind) { event(kind, ""); }

    public static String block(Block block) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    /** Positions owned by the two-region fixture; background world activity is excluded. */
    public static boolean fixture(BlockPos pos) {
        return pos.equals(new BlockPos(8, 65, 8)) || pos.equals(new BlockPos(120, 65, 8))
            || pos.equals(new BlockPos(120, 65, 9))
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

    /** Called from the real server stop hook after the command has flushed its report. */
    public static void serverStopped() {
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
        require(events, "tracking.pair", missing);
        require(events, "tracking.remove", missing);
        require(events, "tracking.recipient", missing);
        require(events, "tracking.changes", missing);
        require(events, "save.enter", missing);
        require(events, "save.exit", missing);
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
