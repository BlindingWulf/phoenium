package dev.phoenium.service;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RegionalBlockEntityTickOrderTest {
    private static final BlockPos POS = new BlockPos(32, 64, 48);

    @Test void removedTickerIsObservedBeforePositionEvenWhenDelegateHasReboundToNullTicker() {
        Trace ticker = new Trace(true, null);
        assertTrue(RegionalChunkGameplayService.tickTicker(ticker, true, POS,
            ignored -> fail("removed ticker reached eligibility")));
        assertEquals(List.of("removed"), ticker.calls);
    }

    @Test void frozenTickerObservesOnlyRemovalAndNeverPositionOrEligibility() {
        Trace ticker = new Trace(false, null);
        assertFalse(RegionalChunkGameplayService.tickTicker(ticker, false, POS,
            ignored -> fail("frozen ticker reached eligibility")));
        assertEquals(List.of("removed"), ticker.calls);
    }

    @Test void runningTickerPreservesRemovalPositionEligibilityAndTickOrder() {
        Trace ticker = new Trace(false, POS);
        assertFalse(RegionalChunkGameplayService.tickTicker(ticker, true, POS, position -> {
            ticker.calls.add("eligible:" + position); return true;
        }));
        assertEquals(List.of("removed", "position", "eligible:" + POS, "tick"), ticker.calls);
    }

    @Test void changedLivePositionFailsBeforeEligibilityOrTick() {
        Trace ticker = new Trace(false, POS.east());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> RegionalChunkGameplayService.tickTicker(ticker, true, POS,
                ignored -> fail("changed position reached eligibility")));
        assertEquals("block entity ticker position changed", failure.getMessage());
        assertEquals(List.of("removed", "position"), ticker.calls);
    }

    private static final class Trace implements TickingBlockEntity {
        final List<String> calls = new ArrayList<>();
        final boolean removed;
        final BlockPos position;
        Trace(boolean removed, BlockPos position) { this.removed = removed; this.position = position; }
        @Override public void tick() { calls.add("tick"); }
        @Override public boolean isRemoved() { calls.add("removed"); return removed; }
        @Override public BlockPos getPos() {
            calls.add("position");
            if (position == null) throw new AssertionError("position must not be observed");
            return position;
        }
        @Override public String getType() { return "trace"; }
    }
}
