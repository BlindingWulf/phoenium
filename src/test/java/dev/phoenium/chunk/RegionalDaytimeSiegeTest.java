package dev.phoenium.chunk;

import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RegionalDaytimeSiegeTest {
    @Test void nativeDaytimeSiegeResetsWithoutReadingAnyPlayerOrTerrain() throws Exception {
        var level = FullDecisionWitnessSupport.stub(ServerLevel.class,
            java.util.List.of(new FullDecisionWitnessSupport.Answer(
                ServerLevel.class.getMethod("isBrightOutside"), args -> true)));
        VillageSiege siege = new VillageSiege();
        var setup = VillageSiege.class.getDeclaredField("hasSetupSiege"); setup.setAccessible(true);
        setup.setBoolean(siege, true);
        siege.tick(level, true);
        assertFalse(setup.getBoolean(siege));
    }
}
