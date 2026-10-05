package dev.phoenium.live;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Mob;

/** Runtime resume order for the lifted outer tails of one concrete Mob class (see {@link MobPortalOuterTails}). */
public final class MobPortalTailPlans {
    private MobPortalTailPlans() { }

    /** Ordered resume steps for one concrete Mob class; {@code legacy} classes are handled elsewhere. */
    public record Plan(List<Step> baseTails, List<Step> tickTails) {
        public void resumeBase(Mob mob, int airSupply) { for (Step step : baseTails) step.invoke(mob, airSupply); }
        public void resumeTick(Mob mob) { for (Step step : tickTails) step.invoke(mob, 0); }
    }

    /** A step is a lifted tail (handle) or one of the three legacy reviewed tails (kind) that sits in the same chain. */
    public record Step(MethodHandle handle, MobPortalOuterTails.Capture capture, String legacy) {
        Step(MethodHandle handle, MobPortalOuterTails.Capture capture) { this(handle, capture, null); }
        void invoke(Mob mob, int airSupply) {
            if (legacy != null) {
                switch (legacy) {
                    case MobPortalNativeSuffixes.CUBE -> ((MobPortalNativeAccess.CubeSuffix) mob).phoenium$resumeMobCubeTickSuffix();
                    case MobPortalNativeSuffixes.HORSE -> ((MobPortalNativeAccess.HorseSuffix) mob).phoenium$resumeMobHorseTickSuffix();
                    case MobPortalNativeSuffixes.VILLAGER -> ((MobPortalNativeAccess.VillagerSuffix) mob).phoenium$resumeMobVillagerTickSuffix();
                    default -> throw new IllegalStateException("unreviewed legacy mob tail " + legacy);
                }
                return;
            }
            try {
                if (capture == MobPortalOuterTails.Capture.AIR_SUPPLY) handle.invoke(mob, airSupply); else handle.invoke(mob);
            } catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("mob tail resume failed", failure); }
        }
    }

    private static final Map<Class<?>, java.util.Optional<Plan>> PLANS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> LEGACY_OWNERS = java.util.Set.of(
        MobPortalNativeSuffixes.CUBE, MobPortalNativeSuffixes.HORSE, MobPortalNativeSuffixes.VILLAGER);

    /** Supported iff every declared tick()/baseTick() between the class and Mob is reviewed; null otherwise. */
    public static Plan plan(Class<?> mobClass) {
        return PLANS.computeIfAbsent(mobClass, MobPortalTailPlans::buildPlan).orElse(null);
    }

    private static java.util.Optional<Plan> buildPlan(Class<?> mobClass) {
        List<Step> base = new ArrayList<>(), tick = new ArrayList<>();
        var chain = new ArrayList<Class<?>>();
        for (Class<?> c = mobClass; c != null && c != Mob.class; c = c.getSuperclass()) chain.add(c);
        if (!Mob.class.isAssignableFrom(mobClass)) return java.util.Optional.empty();
        Collections.reverse(chain);   // closest to Mob first: the order the native tails would have run
        for (Class<?> c : chain) {
            String internal = c.getName().replace('.', '/');
            if (LEGACY_OWNERS.contains(internal)) {
                // AbstractHorse / Villager keep their reviewed hand-pinned tick tails as plan steps. The manager
                // already resumes the AbstractCubeMob tail for every cube mob, so it is not repeated here.
                if (!internal.equals(MobPortalNativeSuffixes.CUBE)) tick.add(new Step(null, MobPortalOuterTails.Capture.NONE, internal));
                continue;
            }
            for (String method : List.of("baseTick", "tick")) {
                if (!declares(c, method)) continue;
                MobPortalOuterTails.Pin pin = MobPortalOuterTails.pins().getOrDefault(internal, List.of()).stream()
                    .filter(p -> p.method().equals(method)).findFirst().orElse(null);
                if (pin == null) return java.util.Optional.empty();
                if (pin.emptyTail()) continue;
                try {
                    MethodType type = pin.capture() == MobPortalOuterTails.Capture.AIR_SUPPLY
                        ? MethodType.methodType(void.class, int.class) : MethodType.methodType(void.class);
                    MethodHandle handle = MethodHandles.privateLookupIn(c, MethodHandles.lookup())
                        .findVirtual(c, MobPortalOuterTails.resumeName(internal, method), type)
                        .asType(pin.capture() == MobPortalOuterTails.Capture.AIR_SUPPLY
                            ? MethodType.methodType(void.class, Mob.class, int.class)
                            : MethodType.methodType(void.class, Mob.class));
                    (method.equals("baseTick") ? base : tick).add(new Step(handle, pin.capture()));
                } catch (ReflectiveOperationException unbound) { return java.util.Optional.empty(); }
            }
        }
        return java.util.Optional.of(new Plan(List.copyOf(base), List.copyOf(tick)));
    }

    private static boolean declares(Class<?> c, String method) {
        try { c.getDeclaredMethod(method); return true; } catch (NoSuchMethodException none) { return false; }
    }

}
