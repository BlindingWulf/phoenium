package dev.phoenium.chunk;

import dev.phoenium.live.NativePortalTransfer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.portal.TeleportTransition;

/** Test agent equivalents of only the production graph call-site wraps. */
public final class NativePortalGraphTestHooks {
    static java.util.function.BiPredicate<Entity, Boolean> graphTickGate = (entity, passenger) -> false;
    static java.util.function.Consumer<Entity> beforePassengerTransition = ignored -> { };
    public static boolean pauseGraphTick(Entity entity, boolean passenger) {
        return graphTickGate.test(entity, passenger);
    }
    private static final Method PASSENGER_TRANSITION = method();
    private static Method method() {
        try {
            Method method = Entity.class.getDeclaredMethod("calculatePassengerTransition",
                TeleportTransition.class, Entity.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    public static List<Entity> passengers(Entity source) {
        return NativePortalTransfer.sourceRead(source, "CAPTURE_PASSENGERS", source::getPassengers);
    }
    public static TeleportTransition passengerTransition(Entity parent,
            TeleportTransition transition, Entity passenger) {
        return NativePortalTransfer.sourceRead(parent, "PASSENGER_TRANSITION", () -> {
            beforePassengerTransition.accept(passenger);
            try { return (TeleportTransition) PASSENGER_TRANSITION.invoke(parent, transition, passenger); }
            catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failure.getCause() instanceof Error error) throw error;
                throw new AssertionError(failure.getCause());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }
    public static Entity passenger(Entity child, TeleportTransition transition) {
        return NativePortalTransfer.passenger(child, transition, () -> child.teleport(transition));
    }
    public static boolean attachPassenger(Entity child, Entity vehicle, boolean force, boolean suppressCancellation) {
        return NativePortalTransfer.attachPassenger(child, vehicle,
            () -> child.startRiding(vehicle, force, suppressCancellation));
    }
    public static PositionMoveRotation sourcePosition(Entity source) {
        return NativePortalTransfer.sourcePosition(source, () -> PositionMoveRotation.of(source));
    }
}
