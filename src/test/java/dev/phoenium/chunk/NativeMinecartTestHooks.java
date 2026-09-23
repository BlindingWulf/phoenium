package dev.phoenium.chunk;

import java.util.function.Consumer;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.phys.Vec3;

/** Test-only seams for the two pinned Minecart source tick tails. */
public final class NativeMinecartTestHooks {
    public static Consumer<Minecart> afterAbstract = ignored -> { };
    public static OuterSuffix afterOuter = (ignored, rot, pos) -> { };
    @FunctionalInterface public interface OuterSuffix {
        void accept(Minecart cart, double lastKnownYRot, Vec3 lastKnownPos);
    }
    public static void abstractSuffix(Minecart cart) { afterAbstract.accept(cart); }
    public static void outerSuffix(Minecart cart, double lastKnownYRot, Vec3 lastKnownPos) {
        afterOuter.accept(cart, lastKnownYRot, lastKnownPos);
    }
}
