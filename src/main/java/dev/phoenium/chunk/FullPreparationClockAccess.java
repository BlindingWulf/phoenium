package dev.phoenium.chunk;

/** Implemented on real PrimaryLevelData and delegated by DerivedLevelData to the same clock. */
public interface FullPreparationClockAccess {
    FullPreparationClock phoenium$preparationClock();
}
