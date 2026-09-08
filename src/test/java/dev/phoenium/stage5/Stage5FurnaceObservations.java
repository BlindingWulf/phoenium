package dev.phoenium.stage5probe;

import dev.phoenium.ownership.OwnershipContext;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/** Test-only bounded per-object native HEAD/RETURN observations. Each list is written
 * under that object's real claim; publication and draining occur at joined tick cuts. */
public final class Stage5FurnaceObservations {
    private static final boolean ENABLED = Boolean.getBoolean("phoenium.stage5.callbackTrace");
    private static volatile Map<BlockPos, List<List<Object>>> observations = Map.of();
    private static final Field LIT = field("litTimeRemaining"), COOKING = field("cookingTimer"), TOTAL = field("cookingTotalTime");
    public static void register(List<BlockPos> positions) {
        if (!ENABLED) return;
        if (positions.size() > 1024) throw new IllegalStateException("callback position bound exceeded");
        Map<BlockPos, List<List<Object>>> values = new LinkedHashMap<>();
        positions.forEach(pos -> values.put(pos, new ArrayList<>()));
        observations = Collections.unmodifiableMap(values);
    }
    public static void observe(Level level, BlockPos pos, AbstractFurnaceBlockEntity entity, String phase) {
        if (!ENABLED) return;
        List<List<Object>> values = observations.get(pos);
        if (values == null) return;
        OwnershipContext.current().orElseThrow();
        if (values.size() >= 4096) throw new IllegalStateException("native callback evidence bound exceeded");
        try {
            values.add(List.of(level.getGameTime(), phase, entity.getItem(0).getCount(), entity.getItem(1).getCount(),
                entity.getItem(2).getCount(), LIT.getInt(entity), COOKING.getInt(entity), TOTAL.getInt(entity)));
        } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }
    public static Object snapshot() {
        if (!ENABLED) return "disabled";
        List<Object> result = new ArrayList<>();
        observations.forEach((pos, events) -> result.add(Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ(), "events", List.copyOf(events))));
        return result;
    }
    private static Field field(String name) {
        try { Field result = AbstractFurnaceBlockEntity.class.getDeclaredField(name); result.setAccessible(true); return result; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
