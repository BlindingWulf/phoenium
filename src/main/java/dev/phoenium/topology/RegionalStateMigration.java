package dev.phoenium.topology;

/**
 * Declared regional service state prepared against a proposed immutable view and installed only
 * after that exact view is published. Both calls are owner-free and outside the topology metadata
 * gate while participant admission remains closed.
 */
public interface RegionalStateMigration {
    Prepared prepare(RegionDirectory.View proposed);

    interface Prepared {
        void install(RegionDirectory.View published);
    }

    RegionalStateMigration NONE = proposed -> published -> {
        if (!published.equals(proposed)) throw new IllegalStateException("topology view changed without state migration");
    };
}
