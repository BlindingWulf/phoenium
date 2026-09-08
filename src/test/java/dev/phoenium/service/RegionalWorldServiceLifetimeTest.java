package dev.phoenium.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.live.PhoeniumLiveRuntime;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.topology.RegionDirectory;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

final class RegionalWorldServiceLifetimeTest {
    @Test
    void endedBindingsCannotBecomeTheOriginalBootstrapPathAgain() throws Exception {
        PhoeniumLiveRuntime live = new PhoeniumLiveRuntime(LoggerFactory.getLogger(getClass()));
        var directory = new RegionDirectory();
        var runtimes = new LinkedHashMap<RegionId, RegionRuntime>();
        var scheduler = new RegionScheduler(runtimes, directory, new FailureBarrier());
        var gateway = new OwnerGateway(directory, runtimes, scheduler);
        var dispatcher = new RegionalServiceDispatcher("lifetime-test", 1, directory, runtimes, gateway, scheduler);
        var services = new RegionalWorldServices(directory, dispatcher, new ObjectOwnershipRegistry(directory));
        var nativeTicks = new LevelTicks<String>(ignored -> true);
        var tick = new ScheduledTick<>("test", BlockPos.ZERO, 1, 0);
        AtomicInteger originalMutations = new AtomicInteger();

        // Before live start, the real native producers still have their original bootstrap.
        assertFalse(live.scheduleRegionalTick(nativeTicks, tick));
        assertDoesNotThrow(live::requireRegionalServiceLifetime);
        var field = PhoeniumLiveRuntime.class.getDeclaredField("regionalServices");
        field.setAccessible(true);
        field.set(live, services);
        assertFalse(live.scheduleRegionalTick(nativeTicks, tick)); // not a bound world yet

        services.unbindAll();
        services.unbindAll(); // protocol release itself remains idempotent
        var failure = assertThrows(IllegalStateException.class, () -> {
            if (!live.scheduleRegionalTick(nativeTicks, tick)) originalMutations.incrementAndGet();
        });
        assertTrue(failure.getMessage().contains("after shutdown"));
        assertThrows(IllegalStateException.class, () -> {
            if (!live.mutateRegionalTickArea(nativeTicks, nativeTicks, null, null,
                    originalMutations::incrementAndGet)) originalMutations.incrementAndGet();
        });
        assertThrows(IllegalStateException.class, () -> {
            if (!live.enqueueRegionalBlockEvent(null, null)) originalMutations.incrementAndGet();
        });
        assertThrows(IllegalStateException.class, () -> {
            if (!live.regionalTrackingAdd(null, null, originalMutations::incrementAndGet))
                originalMutations.incrementAndGet();
        });
        assertThrows(IllegalStateException.class, () -> live.runRegionalScheduledTicks(nativeTicks, 1, 1,
            (pos, type) -> originalMutations.incrementAndGet()));
        assertThrows(IllegalStateException.class, () -> live.regionalTickContainerAdded(nativeTicks,
            new ChunkPos(0, 0), null));
        assertThrows(IllegalStateException.class, () -> live.regionalWillTickThisTick(nativeTicks, BlockPos.ZERO, "test"));
        assertThrows(IllegalStateException.class, live::requireRegionalServiceLifetime);
        assertThrows(IllegalStateException.class, () -> services.bind(null));
        assertEquals(0, originalMutations.get());
        assertEquals(0, dispatcher.pendingCount());
        assertFalse(scheduler.failureBarrier().tripped()); // rejection never enters a gameplay body
    }
}
