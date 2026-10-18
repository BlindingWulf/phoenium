package dev.phoenium.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Stage 11 P1 prototype: the flag is off unless the operator sets it. */
class DeathConfinementScanTest {
    @Test
    void confinementIsOffByDefault() {
        assertFalse(EngineConfig.DEATH_CONFINEMENT, "phoenium.engine.deathConfinement must default to off");
    }
}
