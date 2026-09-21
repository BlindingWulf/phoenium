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

    @Test void removedOrFrozenTickNeverCapturesVaultContinuation() {
        assertTrue(RegionalChunkGameplayService.tickTicker(new Trace(true, null), true, POS,
            ignored -> fail("removed ticker reached eligibility"), ignored -> fail("removed ticker captured continuation")));
        assertFalse(RegionalChunkGameplayService.tickTicker(new Trace(false, null), false, POS,
            ignored -> fail("frozen ticker reached eligibility"), ignored -> fail("frozen ticker captured continuation")));
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

    @Test void unknownNullPositionTickerFailsClosed() {
        NullableTrace ticker = new NullableTrace();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> RegionalChunkGameplayService.tickTicker(ticker, true, POS,
                ignored -> fail("null position reached eligibility")));
        assertEquals("block entity ticker returned null position", failure.getMessage());
        assertEquals(List.of("removed", "position"), ticker.calls);
    }

    @Test void sentinelNameAndTypeDoNotBypassWrapperIdentity() {
        TickingBlockEntity delegate = new Trace(false, null) {
            @Override public String getType() { return "<lithium_sleeping>"; }
        };
        assertFalse(LithiumSleepingTickerBridge.isSleepingSentinel(new WrapperTrace(delegate)));
    }

    private static class Trace implements TickingBlockEntity {
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

    private static final class NullableTrace implements TickingBlockEntity {
        final List<String> calls = new ArrayList<>();
        @Override public void tick() { calls.add("tick"); }
        @Override public boolean isRemoved() { calls.add("removed"); return false; }
        @Override public BlockPos getPos() { calls.add("position"); return null; }
        @Override public String getType() { return "unknown"; }
    }

    private static final class WrapperTrace implements TickingBlockEntity, WrappedBlockEntityTickerAccess {
        private final TickingBlockEntity delegate;
        WrapperTrace(TickingBlockEntity delegate) { this.delegate = delegate; }
        @Override public void tick() { delegate.tick(); }
        @Override public boolean isRemoved() { return delegate.isRemoved(); }
        @Override public BlockPos getPos() { return delegate.getPos(); }
        @Override public String getType() { return delegate.getType(); }
        @Override public TickingBlockEntity phoenium$wrappedTicker() { return delegate; }
    }
}
