package dev.phoenium.mem;

/** S9-M1 (MEM-2): memory switches, read once at class load. This class loads no Minecraft class, so the mixin config
 * plugin may read it before any target is transformed (same rule as core.DiagnosticSwitches). */
public final class MemSwitches {
    /** Release a proto chunk's noise chunk after CARVERS (NoiseRelease). Default on; -Dphoenium.mem.releaseNoise=false
     * keeps vanilla behaviour and the two mixins are then not applied. */
    public static final boolean RELEASE_NOISE = !"false".equalsIgnoreCase(System.getProperty("phoenium.mem.releaseNoise", "true"));

    private MemSwitches() { }
}
