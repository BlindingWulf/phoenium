package dev.phoenium.acceptance;

/** Test-only provenance retained on the original SerializableChunkData instance. */
public interface FullRuntimeAcceptanceSeedData {
    boolean phoenium$fixtureEligible();
    void phoenium$fixtureEligible(boolean eligible);
}
