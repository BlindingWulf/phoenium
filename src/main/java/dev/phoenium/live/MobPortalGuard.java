package dev.phoenium.live;

import dev.phoenium.Phoenium;
import java.util.function.Predicate;
import net.minecraft.world.entity.Mob;

/** The single call the lifted-tail early return makes: true while the mob's portal transfer is retained. */
public final class MobPortalGuard {
    /** Test seam only; production leaves it null and consults the live runtime. */
    public static volatile Predicate<Object> hook;

    private MobPortalGuard() { }

    public static boolean suspended(Object self) {
        Predicate<Object> test = hook;
        if (test != null) return test.test(self);
        if (self instanceof Mob mob) return Phoenium.liveRuntime().mobPortalSuspended(mob);
        return self instanceof net.minecraft.world.entity.Entity entity && Phoenium.liveRuntime().entityPortalSuspended(entity);
    }
}
