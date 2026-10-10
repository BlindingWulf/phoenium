package dev.phoenium.engine;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * S9-E1: the value of {@code Level.random} in engine mode. A thread running a tile draws from that tile's stream
 * (seeded from the world seed, the dimension, the game time and the tile); every other call goes to the level's own
 * source (C2ME's checked source on the pinned stack), exactly as before.
 */
public final class EngineRandom implements RandomSource {
    private final RandomSource own;

    public EngineRandom(RandomSource own) { this.own = own; }

    public RandomSource own() { return own; }

    private RandomSource source() {
        TileContext context = TileEngine.current();
        return context != null ? context.random : own;
    }

    @Override public RandomSource fork() { return source().fork(); }
    @Override public PositionalRandomFactory forkPositional() { return source().forkPositional(); }
    @Override public void setSeed(long seed) { source().setSeed(seed); }
    @Override public int nextInt() { return source().nextInt(); }
    @Override public int nextInt(int bound) { return source().nextInt(bound); }
    @Override public int nextIntBetweenInclusive(int min, int maxInclusive) { return source().nextIntBetweenInclusive(min, maxInclusive); }
    @Override public long nextLong() { return source().nextLong(); }
    @Override public boolean nextBoolean() { return source().nextBoolean(); }
    @Override public float nextFloat() { return source().nextFloat(); }
    @Override public double nextDouble() { return source().nextDouble(); }
    @Override public double nextGaussian() { return source().nextGaussian(); }
    @Override public double triangle(double mean, double spread) { return source().triangle(mean, spread); }
    @Override public float triangle(float mean, float spread) { return source().triangle(mean, spread); }
    @Override public void consumeCount(int rounds) { source().consumeCount(rounds); }
    @Override public int nextInt(int origin, int bound) { return source().nextInt(origin, bound); }
}
