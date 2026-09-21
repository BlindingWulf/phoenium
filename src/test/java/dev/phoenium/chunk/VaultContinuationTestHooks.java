package dev.phoenium.chunk;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class VaultContinuationTestHooks {
    static final ThreadLocal<Consumer<String>> TRACE = new ThreadLocal<>();
    public static void event(String event) { var trace = TRACE.get(); if (trace != null) trace.accept(event); }
    public static void blockEntityChanged(Level level, BlockPos pos) {
        event("unsaved:" + pos.toShortString());
        FullFinishingQueryTestHooks.invoke(Level.class, level, "phoenium$originalPredicate$blockEntityChanged", new Class<?>[]{BlockPos.class}, pos);
    }
}
