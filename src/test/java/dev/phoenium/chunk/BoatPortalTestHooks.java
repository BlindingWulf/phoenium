package dev.phoenium.chunk;

import java.util.function.Consumer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/** Test-only seam for the original source boat's post-portal tick suffix. */
public final class BoatPortalTestHooks {
    private BoatPortalTestHooks() { }
    static Consumer<AbstractBoat> suffix;

    public static void suffix(AbstractBoat boat) {
        if (suffix == null) throw new AssertionError("boat source suffix was not expected");
        suffix.accept(boat);
    }
}
