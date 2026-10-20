package dev.phoenium.service;

import dev.phoenium.chunk.ChunkProtocolException;
import dev.phoenium.chunk.ChunkSynchronousAccessGuard;
import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.LeaseEpoch;
import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RegionalCustomSpawnerBoundaryTest {
    @Test void concreteInstalledSpawnersCrossOnlyWhenTheirPinnedTerrainPathCanBeReached() throws Exception {
        assertEquals(-1, RegionalChunkGameplayService.customSpawnerRadius(CatSpawner.class, 2, true));
        assertEquals(5, RegionalChunkGameplayService.customSpawnerRadius(CatSpawner.class, 1, true));
        assertEquals(-1, RegionalChunkGameplayService.customSpawnerRadius(PhantomSpawner.class, 1, false));
        assertEquals(1, RegionalChunkGameplayService.customSpawnerRadius(PhantomSpawner.class, 1, true));
        assertEquals(4, RegionalChunkGameplayService.customSpawnerRadius(PatrolSpawner.class, 1, true));
        assertEquals(-1, RegionalChunkGameplayService.customSpawnerRadius(VillageSiege.class, 0, false));
        assertEquals(3, RegionalChunkGameplayService.customSpawnerRadius(VillageSiege.class, 0, true));

        CustomSpawner unknown = (level, enemies) -> { };
        assertThrows(IllegalStateException.class,
            () -> RegionalChunkGameplayService.customSpawnerRadius(unknown.getClass(), 0, true));
    }

    @Test void nativeCatTimerCallbackRunsOnceWithoutSelectingTerrain() throws Exception {
        CatSpawner spawner = new CatSpawner();
        var counter = CatSpawner.class.getDeclaredField("nextTick");
        counter.setAccessible(true); counter.setInt(spawner, 2);
        assertEquals(-1, RegionalChunkGameplayService.customSpawnerRadius(CatSpawner.class,
            counter.getInt(spawner), true));
        spawner.tick(null, true);
        assertEquals(1, counter.getInt(spawner));
    }

    /** Phone B 2026-10-20: after a respawn at spawn, CatSpawner's hasChunksAt gate passed for chunk [1, -1] while it
     * was still loading, and its terrain read inside the owner transaction stopped the server. */
    @Test void terrainStillLoadingEndsOnlyThatSpawnerAttempt() {
        int[] reached = {0};
        try (var ignored = OwnershipContext.enter(new RegionId(new WorldId("spawner-terrain"), "owner"),
                new LeaseEpoch(1), OperationCategory.TICK)) {
            assertFalse(RegionalChunkGameplayService.runSpawnerAttempt(() -> {
                reached[0]++;
                ChunkSynchronousAccessGuard.beforeWait(() -> false);
                reached[0] += 100;
            }));
            assertTrue(RegionalChunkGameplayService.runSpawnerAttempt(() -> reached[0]++));
        }
        assertEquals(2, reached[0], "the attempt ends at the unfinished read and the next attempt runs");
    }

    @Test void everyOtherSpawnerFailureStillFails() {
        try (var ignored = OwnershipContext.enter(new RegionId(new WorldId("spawner-terrain"), "owner"),
                new LeaseEpoch(1), OperationCategory.TICK)) {
            assertThrows(ChunkProtocolException.class, () -> RegionalChunkGameplayService.runSpawnerAttempt(() -> {
                throw new ChunkProtocolException("published route unavailable");
            }));
            assertThrows(ChunkProtocolException.class, () -> RegionalChunkGameplayService.runSpawnerAttempt(() -> {
                throw new ChunkProtocolException("unfinished synchronous chunk dependency inside an owner transaction",
                    new IllegalStateException("a wrapped wait is not the bare guard marker"));
            }));
            assertThrows(IllegalStateException.class, () -> RegionalChunkGameplayService.runSpawnerAttempt(() -> {
                throw new IllegalStateException("retained siege origin changed during regional admission");
            }));
        }
    }

}
