package dev.phoenium.service;

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
}
