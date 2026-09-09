package dev.phoenium.stage7;

import com.google.gson.GsonBuilder;
import dev.phoenium.ownership.OwnershipContext;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded observation only: original Chunky futures, native furnace bodies and block writes.
 * No request completion, ticket, topology, gameplay or persistence is supplied by this probe. */
public final class Stage7ConcurrentProbe implements DedicatedServerModInitializer {
    private static final String OUTPUT = System.getProperty("phoenium.stage7.observations");
    private static final AtomicInteger IDS = new AtomicInteger(), EVENTS = new AtomicInteger();
    private static final Queue<Request> REQUESTS = new ConcurrentLinkedQueue<>();
    private static final Queue<Map<String, Object>> ACTIVITY = new ConcurrentLinkedQueue<>();
    private static final Queue<Map<String, Object>> LIFETIME = new ConcurrentLinkedQueue<>();
    private static volatile boolean overflow;
    private static final Field COOKING = cookingField();
    public static final class Request {
        final int id, x, z; final long startNanos; final String producerThread;
        volatile long endNanos; volatile String failure;
        Request(int id, int x, int z) {
            this.id = id; this.x = x; this.z = z;
            startNanos = System.nanoTime(); producerThread = Thread.currentThread().getName();
        }
        public void ended(Throwable problem) {
            failure = problem == null ? null : problem.toString(); endNanos = System.nanoTime();
        }
    }
    public static Request begin(int x, int z) {
        if (OUTPUT == null) return null;
        int id = IDS.incrementAndGet();
        if (id > 4096) { overflow = true; return null; }
        var request = new Request(id, x, z); REQUESTS.add(request); return request;
    }
    public static void furnace(Level level, BlockPos pos, AbstractFurnaceBlockEntity furnace, String phase) {
        if (OUTPUT == null || IDS.get() == 0) return;
        try {
            var row = base(level, pos, "furnace");
            row.put("phase", phase); row.put("cooking", COOKING.getInt(furnace));
            row.put("output", furnace.getItem(2).getCount()); record(row);
        } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }
    public static void block(Level level, BlockPos pos, BlockState state, boolean changed) {
        if (OUTPUT == null || IDS.get() == 0 || !changed
                || !(state.getBlock() instanceof net.minecraft.world.level.block.RedStoneWireBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.RedstoneLampBlock)) return;
        var row = base(level, pos, "redstone"); row.put("state", state.toString()); record(row);
    }
    private static Map<String, Object> base(Level level, BlockPos pos, String kind) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nanos", System.nanoTime()); row.put("kind", kind); row.put("gameTime", level.getGameTime());
        row.put("x", pos.getX()); row.put("y", pos.getY()); row.put("z", pos.getZ());
        row.put("thread", Thread.currentThread().getName());
        row.put("owner", OwnershipContext.current().map(active -> active.region().toString()).orElse("NONE"));
        return row;
    }
    private static void record(Map<String, Object> row) {
        if (EVENTS.incrementAndGet() <= 8192) ACTIVITY.add(Map.copyOf(row)); else overflow = true;
    }
    public static void lifetime(String kind) {
        if (OUTPUT != null && LIFETIME.size() < 32)
            LIFETIME.add(Map.of("kind", kind, "nanos", System.nanoTime()));
    }
    private static Field cookingField() {
        try { var field = AbstractFurnaceBlockEntity.class.getDeclaredField("cookingTimer"); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @Override public void onInitializeServer() {
        if (OUTPUT == null) return;
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> lifetime("server-stopping"));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            try {
                var path = Path.of(OUTPUT); Files.createDirectories(path.getParent());
                Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                        "requests", List.copyOf(REQUESTS), "activity", List.copyOf(ACTIVITY),
                        "lifetime", List.copyOf(LIFETIME), "overflow", overflow)),
                        StandardOpenOption.CREATE_NEW);
                System.out.println("STAGE7_OBSERVATIONS requests=" + REQUESTS.size() + " activity=" + ACTIVITY.size() + " overflow=" + overflow);
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        });
    }
}
