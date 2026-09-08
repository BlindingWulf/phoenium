package dev.phoenium.stage4probe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/** Test-only ordered trace. It records original callbacks without changing their values. */
public final class Stage4Trace {
    private static final Object LOCK = new Object();
    private static final List<String> EVENTS = new ArrayList<>();
    private static volatile boolean active;
    private static volatile long startedAt = -1;
    private static volatile String world = "unknown";
    private static volatile long lastGameTime = -1;
    private static volatile Path output;
    private Stage4Trace() { }

    public static void begin(ServerLevel level) {
        synchronized (LOCK) {
            EVENTS.clear();
            active = true;
            startedAt = level.getGameTime();
            lastGameTime = startedAt;
            world = level.dimension().identifier().toString();
            output = Path.of(System.getProperty("phoenium.stage4.tracePath", "run/stage4/acceptance/trace.json"));
            event("trace.begin", "world=" + level.dimension() + " gameTime=" + startedAt);
        }
    }

    public static boolean active() { return active; }

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
        return pos.equals(new BlockPos(70, 65, 8)) || pos.equals(new BlockPos(85, 65, 8))
            || pos.equals(new BlockPos(86, 65, 8)) || pos.equals(new BlockPos(70, 66, 8));
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
