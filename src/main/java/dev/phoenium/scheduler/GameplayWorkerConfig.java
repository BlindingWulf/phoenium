package dev.phoenium.scheduler;

/** Fixed server-lifetime bounds. One worker uses the identical selected-body path inline. */
public record GameplayWorkerConfig(int workers, int callbacksPerRegion) {
    public static final int MAX_WORKERS = 8;
    public GameplayWorkerConfig {
        if (workers < 1 || workers > MAX_WORKERS)
            throw new IllegalArgumentException("phoenium.gameplay.workers must be between 1 and " + MAX_WORKERS);
        if (callbacksPerRegion < 1 || callbacksPerRegion > 256)
            throw new IllegalArgumentException("phoenium.gameplay.callbacksPerRegion must be between 1 and 256");
    }
    public static GameplayWorkerConfig serial() { return new GameplayWorkerConfig(1, 64); }
    public static GameplayWorkerConfig fromProperties() {
        return new GameplayWorkerConfig(integer("phoenium.gameplay.workers", 1),
            integer("phoenium.gameplay.callbacksPerRegion", 64));
    }
    private static int integer(String name, int fallback) {
        String value = System.getProperty(name);
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("invalid " + name + ": " + value, failure); }
    }
}
