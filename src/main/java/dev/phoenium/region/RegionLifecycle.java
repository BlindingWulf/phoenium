package dev.phoenium.region;

public enum RegionLifecycle {
    TRANSIENT, READY, TICKING, DEAD;

    public boolean permits(RegionLifecycle next) {
        return switch (this) {
            case TRANSIENT -> next == READY || next == DEAD;
            case READY -> next == TICKING || next == DEAD;
            case TICKING -> next == READY;
            case DEAD -> false;
        };
    }
}
