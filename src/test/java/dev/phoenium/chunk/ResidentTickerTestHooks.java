package dev.phoenium.chunk;

import java.util.function.BiConsumer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

/** Test equivalent of RegionalBlockEntityTickerMixin's HEAD receipt, never packaged. */
public final class ResidentTickerTestHooks {
    static final ThreadLocal<BiConsumer<Level, TickingBlockEntity>> ADDED = new ThreadLocal<>();
    public static void added(Level level, TickingBlockEntity ticker) {
        var callback = ADDED.get();
        if (callback != null) callback.accept(level, ticker);
    }
}
