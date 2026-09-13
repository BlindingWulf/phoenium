package ca.spottedleaf.starlight.common.light;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

/** Test-only exact-name provider used to exercise Phoenium's closed ScalableLux seam. */
public class StarLightLightingProvider extends ThreadedLevelLightEngine {
    public int retainCalls;
    public int sectionCalls;

    protected StarLightLightingProvider() { super(null, null, false, null, null); }

    @Override public void retainData(ChunkPos pos, boolean retain) { retainCalls++; }
    @Override public void queueSectionData(LightLayer layer, SectionPos pos, DataLayer data) { sectionCalls++; }
}
