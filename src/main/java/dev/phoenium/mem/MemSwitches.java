package dev.phoenium.mem;

/** S9-M1 (MEM-2): memory switches, read once at class load. This class loads no Minecraft class, so the mixin config
 * plugin may read it before any target is transformed (same rule as core.DiagnosticSwitches). */
public final class MemSwitches {
    /** Release a proto chunk's noise chunk after CARVERS (NoiseRelease). Default on; -Dphoenium.mem.releaseNoise=false
     * keeps vanilla behaviour and the two mixins are then not applied. */
    public static final boolean RELEASE_NOISE = !"false".equalsIgnoreCase(System.getProperty("phoenium.mem.releaseNoise", "true"));

    /** S9-M2 heap-pressure governor (MemoryGovernor): steps the view distance down under sustained heap pressure and
     * back up afterwards. Default off; -Dphoenium.mem.governor=true. */
    public static final boolean GOVERNOR = Boolean.getBoolean("phoenium.mem.governor");

    private MemSwitches() { }
}
