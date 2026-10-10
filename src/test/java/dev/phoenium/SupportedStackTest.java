package dev.phoenium;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** S9-E16: the supported-stack guard accepts only the patched C2ME build. */
class SupportedStackTest {
    @Test
    void thePatchedC2meIsSupported() {
        assertNull(SupportedStack.problem("0.4.2-alpha.0.43-phoenium-settlement2+26.2"));
    }

    @Test
    void aMissingOrStockC2meIsNot() {
        assertTrue(SupportedStack.problem(null).contains("not installed"));
        assertTrue(SupportedStack.problem("0.4.2-alpha.0.43+26.2").contains("not the patched"));
        assertTrue(SupportedStack.problem("0.3.0+alpha.0.362").contains("not the patched"));
    }
}
