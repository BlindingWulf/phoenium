package dev.phoenix.spawnprep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Spawn preparation settings: safe defaults, bad values fall back with a warning instead of failing startup. */
class SpawnPrepConfigTest {
    private static Properties props(String text) throws Exception {
        Properties p = new Properties();
        p.load(new StringReader(text));
        return p;
    }

    @Test
    void defaultsAreTheProvisional256BlockRadius() throws Exception {
        SpawnPrepConfig c = SpawnPrepConfig.from(props(""));
        assertTrue(c.enabled());
        assertEquals(256, c.radiusBlocks());
        assertEquals(16, c.maxInFlight());
        assertFalse(c.existingWorlds());
        assertTrue(c.warnings().isEmpty());
    }

    @Test
    void largerRadiiStayAvailable() throws Exception {
        assertEquals(1280, SpawnPrepConfig.from(props("radiusBlocks=1280")).radiusBlocks());
        assertTrue(SpawnPrepConfig.from(props("existingWorlds=true")).existingWorlds());
        assertFalse(SpawnPrepConfig.from(props("enabled=false")).enabled());
    }

    @Test
    void badValuesFallBackOrClampWithAWarning() throws Exception {
        SpawnPrepConfig c = SpawnPrepConfig.from(props("radiusBlocks=abc\nmaxInFlight=0"));
        assertEquals(256, c.radiusBlocks());
        assertEquals(1, c.maxInFlight());
        assertEquals(2, c.warnings().size());
        assertEquals(4096, SpawnPrepConfig.from(props("radiusBlocks=99999")).radiusBlocks());
        assertEquals(0, SpawnPrepConfig.from(props("radiusBlocks=-5")).radiusBlocks());
        assertEquals(256, SpawnPrepConfig.from(props("maxInFlight=10000")).maxInFlight());
    }

    @Test
    void writtenDefaultsReadBackUnchanged() throws Exception {
        SpawnPrepConfig c = SpawnPrepConfig.from(props(SpawnPrepConfig.defaultFileText()));
        assertEquals(SpawnPrepConfig.from(props("")), c);
    }
}
