package dev.phoenium.engine.mixin;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Stage 11 P1 prototype: the death-confinement mixins apply only in engine mode with
 * {@code phoenium.engine.deathConfinement=true}; otherwise the classes are exactly as on the v2.1 baseline.
 */
class DeathConfinementMixinGateTest {
    private static final String P = "dev.phoenium.engine.mixin.";
    private static final String[] CONFINEMENT = {"EngineDeathConfinementMixin", "EngineDeathConfinementArmorStandMixin",
        "EngineDeathConfinementCreakingMixin"};

    @Test
    void confinementMixinsApplyOnlyWithEngineAndFlag() {
        for (String m : CONFINEMENT) {
            assertTrue(EngineMixinPlugin.applies(P + m, true, true), m);
            assertFalse(EngineMixinPlugin.applies(P + m, true, false), m);
            assertFalse(EngineMixinPlugin.applies(P + m, false, true), m);
            assertFalse(EngineMixinPlugin.applies(P + m, false, false), m);
        }
    }

    @Test
    void otherEngineMixinsIgnoreTheFlag() {
        assertTrue(EngineMixinPlugin.applies(P + "EngineTamableAnimalMixin", true, false));
        assertTrue(EngineMixinPlugin.applies(P + "EngineTamableAnimalMixin", true, true));
        assertFalse(EngineMixinPlugin.applies(P + "EngineTamableAnimalMixin", false, true));
    }
}
