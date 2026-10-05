package dev.phoenium.live;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Runtime resume order for the lifted outer tails of one concrete class (see {@link MobPortalOuterTails}).
 * {@link #plan} serves Mob subclasses (chain root Mob); {@link #entityPlan} serves other entities (chain root
 * Entity). Both return null unless every declared tick()/baseTick() override in the chain is a reviewed pin.
 */
public final class MobPortalTailPlans {
    private MobPortalTailPlans() { }

    /**
     * {@code viaBaseTick}: the portal cut unwinds through Entity.baseTick, so its native tail must be resumed first.
     * False for classes whose reviewed tick calls this.handlePortal() itself.
     */
    public record Plan(List<Step> baseTails, List<Step> tickTails, boolean viaBaseTick) {
        public void resumeBase(Entity entity, int airSupply) { for (Step step : baseTails) step.invoke(entity, airSupply); }
        public void resumeTick(Entity entity) { for (Step step : tickTails) step.invoke(entity, 0); }
    }

    /** A step is a lifted tail (handle) or one of the legacy reviewed tails (kind) that sits in the same chain. */
    public record Step(MethodHandle handle, MobPortalOuterTails.Capture capture, String legacy) {
        Step(MethodHandle handle, MobPortalOuterTails.Capture capture) { this(handle, capture, null); }
        void invoke(Entity entity, int airSupply) {
            if (legacy != null) {
                switch (legacy) {
                    case MobPortalNativeSuffixes.CUBE -> ((MobPortalNativeAccess.CubeSuffix) entity).phoenium$resumeMobCubeTickSuffix();
                    case MobPortalNativeSuffixes.HORSE -> ((MobPortalNativeAccess.HorseSuffix) entity).phoenium$resumeMobHorseTickSuffix();
                    case MobPortalNativeSuffixes.VILLAGER -> ((MobPortalNativeAccess.VillagerSuffix) entity).phoenium$resumeMobVillagerTickSuffix();
                    default -> throw new IllegalStateException("unreviewed legacy mob tail " + legacy);
                }
                return;
            }
            try {
                if (capture == MobPortalOuterTails.Capture.AIR_SUPPLY) handle.invoke(entity, airSupply); else handle.invoke(entity);
            } catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("tail resume failed", failure); }
        }
    }

    private static final Map<Class<?>, Optional<Plan>> MOB_PLANS = new ConcurrentHashMap<>(),
        ENTITY_PLANS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> LEGACY_OWNERS = java.util.Set.of(
        MobPortalNativeSuffixes.CUBE, MobPortalNativeSuffixes.HORSE, MobPortalNativeSuffixes.VILLAGER);

    /** Mob subclass plan; null means the class falls through to a native destination search. */
    public static Plan plan(Class<?> mobClass) {
        return MOB_PLANS.computeIfAbsent(mobClass, type -> build(type, Mob.class)).orElse(null);
    }

    /** Plan for a non-living, non-Mob entity class. */
    public static Plan entityPlan(Class<?> type) {
        return ENTITY_PLANS.computeIfAbsent(type, t -> build(t, Entity.class)).orElse(null);
    }

    private static Optional<Plan> build(Class<?> type, Class<?> root) {
        if (!root.isAssignableFrom(type)) return Optional.empty();
        if (root == Entity.class && LivingEntity.class.isAssignableFrom(type)) return Optional.empty();
        var chain = new ArrayList<Class<?>>();
        boolean viaBaseTick = true;
        for (Class<?> c = type; c != null && c != root; c = c.getSuperclass()) {
            chain.add(c);
            // A reviewed tick that calls this.handlePortal() itself never reaches its super method's code.
            String internal = c.getName().replace('.', '/');
            var tickPin = MobPortalOuterTails.pins().getOrDefault(internal, List.of()).stream()
                .filter(p -> p.method().equals("tick")).findFirst().orElse(null);
            if (tickPin != null && tickPin.anchor() == MobPortalOuterTails.Anchor.HANDLE_PORTAL && declares(c, "tick")) {
                viaBaseTick = false;
                break;
            }
        }
        Collections.reverse(chain);   // closest to the root first: the order the native tails would have run
        List<Step> base = new ArrayList<>(), tick = new ArrayList<>();
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
                if (pin == null) return Optional.empty();
                if (pin.emptyTail()) continue;
                try {
                    boolean air = pin.capture() == MobPortalOuterTails.Capture.AIR_SUPPLY;
                    MethodHandle handle = MethodHandles.privateLookupIn(c, MethodHandles.lookup())
                        .findVirtual(c, MobPortalOuterTails.resumeName(internal, method),
                            air ? MethodType.methodType(void.class, int.class) : MethodType.methodType(void.class))
                        .asType(air ? MethodType.methodType(void.class, Entity.class, int.class)
                            : MethodType.methodType(void.class, Entity.class));
                    (method.equals("baseTick") ? base : tick).add(new Step(handle, pin.capture()));
                } catch (ReflectiveOperationException unbound) { return Optional.empty(); }
            }
        }
        return Optional.of(new Plan(List.copyOf(base), List.copyOf(tick), viaBaseTick));
    }

    private static boolean declares(Class<?> c, String method) {
        try { c.getDeclaredMethod(method); return true; } catch (NoSuchMethodException none) { return false; }
    }
}
